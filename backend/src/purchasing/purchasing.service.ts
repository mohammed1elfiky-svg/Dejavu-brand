import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { PaymentMethod, Prisma, PurchaseOrder, PurchaseOrderStatus, StockMovementType } from '../generated/prisma/client';
import { AccountingService } from '../accounting/accounting.service';
import { LedgerService, SystemAccount } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import {
  CreateSupplierDto,
  PurchaseOrderDto,
  PurchaseOrdersQueryDto,
  ReceivePurchaseDto,
  StatementQueryDto,
  SupplierPaymentDto,
  SupplierPaymentsQueryDto,
  SuppliersQueryDto,
  UpdateSupplierDto,
} from './dto/purchasing.dto';
import { landedUnitCost, purchaseTotals, receivedValue } from './purchase-math';

const DEFAULT_TAX_RATE = 14;

const PAY_FROM: Partial<Record<PaymentMethod, SystemAccount>> = {
  CASH: 'CASH',
  BANK_TRANSFER: 'BANK',
  INSTAPAY: 'BANK',
  WALLET: 'WALLET',
};

const poInclude = {
  supplier: { select: { id: true, code: true, name: true } },
  warehouse: { select: { id: true, code: true, name: true } },
  createdBy: { select: { id: true, name: true } },
  items: {
    include: { variant: { select: { sku: true, size: true, color: true, product: { select: { nameAr: true, nameEn: true } } } } },
  },
  payments: { orderBy: { paidAt: 'asc' } },
} satisfies Prisma.PurchaseOrderInclude;

@Injectable()
export class PurchasingService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly accounting: AccountingService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  // ------------------------------------------------------------ suppliers ---

  async listSuppliers(q: SuppliersQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const where: Prisma.SupplierWhereInput = contains
      ? { OR: [{ name: contains }, { code: contains }, { phone: contains }] }
      : {};
    const [items, total] = await Promise.all([
      this.prisma.supplier.findMany({ where, orderBy: { name: 'asc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.supplier.count({ where }),
    ]);
    const balances = await this.supplierBalances(items.map((s) => s.id));
    return paginated(items.map((s) => ({ ...s, balance: balances.get(s.id) ?? money(0) })), total, q);
  }

  async getSupplier(id: string) {
    const supplier = await this.prisma.supplier.findUnique({
      where: { id },
      include: {
        purchaseOrders: {
          orderBy: { orderDate: 'desc' },
          take: 20,
          select: { id: true, number: true, status: true, orderDate: true, grandTotal: true, amountPaid: true },
        },
      },
    });
    if (!supplier) throw new NotFoundException('Supplier not found');
    const balance = (await this.supplierBalances([id])).get(id) ?? money(0);
    return { ...supplier, balance };
  }

  async createSupplier(dto: CreateSupplierDto, userId: string) {
    const supplier = await this.prisma.$transaction(async (tx) =>
      tx.supplier.create({ data: { ...dto, code: await this.sequences.next(tx, 'SUP', { year: 0, pad: 4 }) } }),
    );
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Supplier', entityId: supplier.id, after: supplier });
    return supplier;
  }

  async updateSupplier(id: string, dto: UpdateSupplierDto, userId: string) {
    const before = await this.prisma.supplier.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Supplier not found');
    const supplier = await this.prisma.supplier.update({ where: { id }, data: dto });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Supplier', entityId: id, before, after: supplier });
    return supplier;
  }

  supplierStatement(id: string, q: StatementQueryDto) {
    return this.accounting.partyStatement({ supplierId: id }, q);
  }

  /** What we owe each supplier according to the ledger (AP credit balance). */
  private async supplierBalances(ids: string[]) {
    if (!ids.length) return new Map<string, ReturnType<typeof money>>();
    const rows = await this.prisma.journalLine.groupBy({
      by: ['supplierId'],
      where: {
        supplierId: { in: ids },
        account: { systemKey: 'AP' },
        journalEntry: { status: { in: ['POSTED', 'REVERSED'] } },
      },
      _sum: { debit: true, credit: true },
    });
    return new Map(rows.map((r) => [r.supplierId!, money(dec(r._sum.credit).sub(dec(r._sum.debit)))]));
  }

  // ------------------------------------------------------ purchase orders ---

  async listOrders(q: PurchaseOrdersQueryDto) {
    const where: Prisma.PurchaseOrderWhereInput = { status: q.status, supplierId: q.supplierId };
    const [items, total] = await Promise.all([
      this.prisma.purchaseOrder.findMany({
        where,
        include: { supplier: { select: { id: true, name: true } }, _count: { select: { items: true } } },
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.purchaseOrder.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async getOrder(id: string) {
    const po = await this.prisma.purchaseOrder.findUnique({ where: { id }, include: poInclude });
    if (!po) throw new NotFoundException('Purchase order not found');
    const journalEntries = await this.prisma.journalEntry.findMany({
      where: { sourceId: { in: [po.id, ...po.payments.map((p) => p.id)] } },
      include: { lines: { orderBy: { lineNo: 'asc' }, include: { account: { select: { code: true, nameAr: true, nameEn: true } } } } },
      orderBy: { createdAt: 'asc' },
    });
    return { ...po, balanceDue: money(dec(po.grandTotal).sub(po.amountPaid)), journalEntries };
  }

  async createOrder(dto: PurchaseOrderDto, userId: string) {
    const prepared = await this.prepare(dto);
    const id = await this.prisma.$transaction(async (tx) => {
      const po = await tx.purchaseOrder.create({
        data: {
          ...prepared.header,
          number: await this.sequences.next(tx, 'PO'),
          createdById: userId,
          items: { create: prepared.items },
        },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'PurchaseOrder', entityId: po.id, after: dto }, tx);
      return po.id;
    });
    return this.getOrder(id);
  }

  async updateOrder(id: string, dto: PurchaseOrderDto, userId: string) {
    const prepared = await this.prepare(dto);
    await this.prisma.$transaction(async (tx) => {
      const po = await this.lock(tx, id);
      if (po.status !== PurchaseOrderStatus.DRAFT) throw new BadRequestException('Only draft purchase orders can be edited');
      await tx.purchaseOrderItem.deleteMany({ where: { purchaseOrderId: id } });
      await tx.purchaseOrder.update({ where: { id }, data: { ...prepared.header, items: { create: prepared.items } } });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'PurchaseOrder', entityId: id, after: dto }, tx);
    });
    return this.getOrder(id);
  }

  /** DRAFT → ORDERED: sent to the supplier; no accounting effect yet. */
  async placeOrder(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const po = await this.lock(tx, id);
      if (po.status !== PurchaseOrderStatus.DRAFT) throw new BadRequestException('Only draft purchase orders can be placed');
      await tx.purchaseOrder.update({ where: { id }, data: { status: PurchaseOrderStatus.ORDERED } });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'PurchaseOrder', entityId: id, after: { status: 'ORDERED' } }, tx);
    });
    return this.getOrder(id);
  }

  async cancelOrder(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const po = await this.lock(tx, id);
      const items = await tx.purchaseOrderItem.findMany({ where: { purchaseOrderId: id } });
      if (items.some((i) => i.receivedQty > 0)) {
        throw new UnprocessableEntityException('Goods were already received; close the order instead');
      }
      if (!['DRAFT', 'ORDERED'].includes(po.status)) throw new BadRequestException(`Cannot cancel a ${po.status} order`);
      if (dec(po.amountPaid).gt(0)) {
        throw new UnprocessableEntityException('The supplier was already paid for this order; settle that first');
      }
      await tx.purchaseOrder.update({ where: { id }, data: { status: PurchaseOrderStatus.CANCELLED } });
      await this.audit.log({ userId, action: 'CANCEL', entityType: 'PurchaseOrder', entityId: id }, tx);
    });
    return this.getOrder(id);
  }

  /**
   * Receives goods: stock in at landed cost, and
   *   Dr Inventory (goods + shipping share) + Dr VAT input  —  Cr Accounts payable
   */
  async receive(id: string, dto: ReceivePurchaseDto, userId: string) {
    if (new Set(dto.items.map((i) => i.itemId)).size !== dto.items.length) {
      throw new BadRequestException('Each line may appear only once');
    }
    await this.prisma.$transaction(async (tx) => {
      const po = await this.lock(tx, id);
      if (!['ORDERED', 'PARTIALLY_RECEIVED'].includes(po.status)) {
        throw new UnprocessableEntityException(`Cannot receive goods for a ${po.status} purchase order`);
      }
      const items = await tx.purchaseOrderItem.findMany({ where: { purchaseOrderId: id }, orderBy: { id: 'asc' } });
      const before = items.map((i) => i.receivedQty);
      const after = items.map((i) => {
        const r = dto.items.find((x) => x.itemId === i.id);
        if (r && i.receivedQty + r.quantity > i.quantity) {
          throw new UnprocessableEntityException(`Cannot receive more than ordered (${i.quantity - i.receivedQty} left on a line)`);
        }
        return i.receivedQty + (r?.quantity ?? 0);
      });
      if (dto.items.some((r) => !items.find((i) => i.id === r.itemId))) {
        throw new BadRequestException('Item does not belong to this purchase order');
      }

      const lines = items.map((i) => ({ quantity: i.quantity, unitCost: i.unitCost, taxRate: i.taxRate }));
      const prev = receivedValue(lines, before, po.subtotal, po.shippingCost);
      const next = receivedValue(lines, after, po.subtotal, po.shippingCost);

      const note = [po.number, dto.note].filter(Boolean).join(' · ');
      for (const item of [...items].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        const qty = after[items.indexOf(item)] - item.receivedQty;
        if (!qty) continue;
        await this.stock.stockIn(tx, {
          type: StockMovementType.PURCHASE_RECEIPT,
          variantId: item.variantId,
          warehouseId: po.warehouseId,
          quantity: qty,
          unitCost: landedUnitCost(item.unitCost, po.subtotal, po.shippingCost),
          sourceType: 'PURCHASE_ORDER',
          sourceId: po.id,
          note,
          userId,
        });
        await tx.purchaseOrderItem.update({ where: { id: item.id }, data: { receivedQty: { increment: qty } } });
      }

      const inventory = next.inventory.sub(prev.inventory);
      const tax = next.tax.sub(prev.tax);
      await this.ledger.post(tx, {
        description: `Goods received for ${po.number}${po.supplierInvoiceNo ? ` (invoice ${po.supplierInvoiceNo})` : ''}`,
        sourceType: 'PURCHASE_ORDER',
        sourceId: po.id,
        userId,
        lines: [
          { account: 'INVENTORY', debit: inventory },
          { account: 'VAT_INPUT', debit: tax },
          { account: 'AP', credit: inventory.add(tax), supplierId: po.supplierId },
        ],
      });

      const complete = after.every((q, i) => q === items[i].quantity);
      await tx.purchaseOrder.update({
        where: { id },
        data: { status: complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'PurchaseOrder', entityId: id, after: { received: dto.items } }, tx);
    });
    return this.getOrder(id);
  }

  /**
   * Closes a partially received order: the rest will not arrive. Totals shrink to
   * what was received, which is exactly what has been booked to AP.
   */
  async closeOrder(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const po = await this.lock(tx, id);
      if (po.status !== PurchaseOrderStatus.PARTIALLY_RECEIVED) {
        throw new BadRequestException('Only partially received orders can be closed');
      }
      const items = await tx.purchaseOrderItem.findMany({ where: { purchaseOrderId: id } });
      const lines = items.map((i) => ({ quantity: i.quantity, unitCost: i.unitCost, taxRate: i.taxRate }));
      const booked = receivedValue(lines, items.map((i) => i.receivedQty), po.subtotal, po.shippingCost);
      if (booked.total.lessThan(po.amountPaid)) {
        throw new UnprocessableEntityException('The supplier was paid more than was received; settle the difference first');
      }
      const goods = money(items.reduce((s, i) => s.add(dec(i.unitCost).mul(i.receivedQty)), dec(0)));
      await tx.purchaseOrder.update({
        where: { id },
        data: {
          status: PurchaseOrderStatus.RECEIVED,
          subtotal: goods,
          taxTotal: booked.tax,
          shippingCost: booked.inventory.sub(goods),
          grandTotal: booked.total,
          note: [po.note, 'Closed short: remaining quantities cancelled'].filter(Boolean).join(' · '),
        },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'PurchaseOrder', entityId: id, after: { closedShort: true } }, tx);
    });
    return this.getOrder(id);
  }

  // ---------------------------------------------------- supplier payments ---

  /** Dr Accounts payable — Cr Cash/Bank */
  async paySupplier(dto: SupplierPaymentDto, userId: string) {
    const fallback = PAY_FROM[dto.method];
    if (!fallback && !dto.accountId) throw new BadRequestException(`Pay suppliers by cash, bank transfer, InstaPay or wallet`);

    const paymentId = await this.prisma.$transaction(async (tx) => {
      const supplier = await tx.supplier.findUnique({ where: { id: dto.supplierId } });
      if (!supplier) throw new NotFoundException('Supplier not found');
      const amount = money(dto.amount);
      if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');

      let po: PurchaseOrder | null = null;
      if (dto.purchaseOrderId) {
        po = await this.lock(tx, dto.purchaseOrderId);
        if (po.supplierId !== supplier.id) throw new BadRequestException('Purchase order belongs to another supplier');
        if (po.status === PurchaseOrderStatus.CANCELLED) throw new UnprocessableEntityException('Purchase order is cancelled');
        const due = dec(po.grandTotal).sub(po.amountPaid);
        if (amount.greaterThan(due)) throw new UnprocessableEntityException(`Amount exceeds the balance due (${money(due)})`);
      }

      const accountId = await this.payFromAccount(tx, dto.accountId, fallback);
      const payment = await tx.supplierPayment.create({
        data: {
          number: await this.sequences.next(tx, 'PAY'),
          supplierId: supplier.id,
          purchaseOrderId: po?.id,
          method: dto.method,
          amount,
          accountId,
          reference: dto.reference,
          createdById: userId,
        },
      });
      await this.ledger.post(tx, {
        description: `Payment ${payment.number} to ${supplier.name}${po ? ` for ${po.number}` : ''}`,
        sourceType: 'SUPPLIER_PAYMENT',
        sourceId: payment.id,
        userId,
        lines: [
          { account: 'AP', debit: amount, supplierId: supplier.id },
          { account: { id: accountId }, credit: amount },
        ],
      });
      if (po) await tx.purchaseOrder.update({ where: { id: po.id }, data: { amountPaid: { increment: amount } } });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'SupplierPayment', entityId: payment.id, after: payment }, tx);
      return payment.id;
    });
    return this.prisma.supplierPayment.findUniqueOrThrow({
      where: { id: paymentId },
      include: { supplier: { select: { name: true } }, purchaseOrder: { select: { number: true } } },
    });
  }

  listPayments(q: SupplierPaymentsQueryDto) {
    return this.prisma.supplierPayment.findMany({
      where: { supplierId: q.supplierId },
      include: { supplier: { select: { name: true } }, purchaseOrder: { select: { number: true } } },
      orderBy: { paidAt: 'desc' },
      skip: q.skip,
      take: q.pageSize,
    });
  }

  // -------------------------------------------------------------- helpers ---

  private async prepare(dto: PurchaseOrderDto) {
    const supplier = await this.prisma.supplier.findUnique({ where: { id: dto.supplierId } });
    if (!supplier || !supplier.isActive) throw new NotFoundException('Supplier not found or inactive');
    const warehouse = dto.warehouseId
      ? await this.prisma.warehouse.findUnique({ where: { id: dto.warehouseId } })
      : await this.prisma.warehouse.findFirst({ where: { isDefault: true } });
    if (!warehouse || !warehouse.isActive) throw new NotFoundException('Warehouse not found or inactive');
    if (new Set(dto.items.map((i) => i.variantId)).size !== dto.items.length) {
      throw new BadRequestException('Each variant may appear only once per purchase order');
    }
    const found = await this.prisma.productVariant.count({ where: { id: { in: dto.items.map((i) => i.variantId) } } });
    if (found !== dto.items.length) throw new BadRequestException('One or more variants do not exist');

    const lines = dto.items.map((i) => ({ quantity: i.quantity, unitCost: i.unitCost, taxRate: i.taxRate ?? DEFAULT_TAX_RATE }));
    const t = purchaseTotals(lines, dto.shippingCost ?? 0);
    return {
      header: {
        supplierId: supplier.id,
        warehouseId: warehouse.id,
        supplierInvoiceNo: dto.supplierInvoiceNo,
        expectedDate: dto.expectedDate ? parseDateOnly(dto.expectedDate) : null,
        note: dto.note,
        subtotal: t.subtotal,
        taxTotal: t.taxTotal,
        shippingCost: t.shippingCost,
        grandTotal: t.grandTotal,
      },
      items: dto.items.map((i, idx) => ({
        variantId: i.variantId,
        quantity: i.quantity,
        unitCost: i.unitCost,
        taxRate: lines[idx].taxRate,
        lineTotal: t.lines[idx].lineTotal,
      })),
    };
  }

  private async payFromAccount(tx: Tx, accountId: string | undefined, fallback: SystemAccount | undefined) {
    if (accountId) {
      const acc = await tx.account.findUnique({ where: { id: accountId } });
      if (!acc || !acc.isActive || !acc.isPostable || !acc.isCashOrBank) {
        throw new BadRequestException('Choose an active cash, bank or wallet account');
      }
      return acc.id;
    }
    const acc = await tx.account.findUniqueOrThrow({ where: { systemKey: fallback! }, select: { id: true } });
    return acc.id;
  }

  private async lock(tx: Tx, id: string): Promise<PurchaseOrder> {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM purchase_orders WHERE id = ${id}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException('Purchase order not found');
    return tx.purchaseOrder.findUniqueOrThrow({ where: { id } });
  }
}
