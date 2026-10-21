import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import { Order, OrderItem, OrderStatus, Prisma, ReturnStatus, SalesReturn, StockMovementType } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { Decimal, DecimalLike, dec, money, sum, ZERO } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { returnMessage } from '../notifications/messages';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { CreateReturnDto, ReceiveReturnDto, RefundReturnDto, ReturnsQueryDto } from './dto/sales.dto';
import { PaymentsService } from './payments.service';
import { getSetting } from './settings';

const RETURNABLE: OrderStatus[] = [OrderStatus.DELIVERED, OrderStatus.COMPLETED];
const OPEN: ReturnStatus[] = [ReturnStatus.REQUESTED, ReturnStatus.APPROVED];

/**
 * Refund and VAT for returning `qty` more pieces of a line that already has
 * `already` returned. Cumulative, so returning a whole line in several steps
 * refunds exactly its original total (discount included, shipping excluded).
 */
export function returnSlice(item: Pick<OrderItem, 'lineTotal' | 'taxAmount' | 'quantity'>, already: number, qty: number) {
  const share = (amount: DecimalLike, n: number) => money(dec(amount).mul(n).div(item.quantity));
  return {
    refund: share(item.lineTotal, already + qty).sub(share(item.lineTotal, already)),
    tax: share(item.taxAmount, already + qty).sub(share(item.taxAmount, already)),
  };
}

const returnInclude = {
  order: { select: { id: true, number: true, paymentMethod: true, deliveredAt: true } },
  customer: { select: { id: true, code: true, name: true, phone: true } },
  handledBy: { select: { id: true, name: true } },
  items: {
    include: {
      orderItem: { select: { id: true, productName: true, sku: true, size: true, color: true, quantity: true, unitPrice: true, returnedQty: true } },
    },
  },
} satisfies Prisma.SalesReturnInclude;

/**
 * REQUESTED ─approve─▶ APPROVED ─receive─▶ RECEIVED ─refund─▶ REFUNDED
 *     └────────reject────────┘
 *
 * Receiving books the return:
 *   Dr Sales returns (net) + Dr VAT output  —  Cr AR          (customer credited)
 *   Dr Inventory                            —  Cr COGS        (restocked pieces, at their sale cost)
 * Refunding pays the credit out:  Dr AR — Cr Cash/Bank
 */
@Injectable()
export class ReturnsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly payments: PaymentsService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly notifications: NotificationsService,
  ) {}

  // ------------------------------------------------------------- creating ---

  async customerRequest(customerId: string, orderNumber: string, dto: CreateReturnDto) {
    const order = await this.prisma.order.findFirst({ where: { number: orderNumber, customerId } });
    if (!order) throw new NotFoundException('Order not found');
    const days = await getSetting(this.prisma, 'returns.windowDays', 14);
    if (order.deliveredAt && Date.now() - order.deliveredAt.getTime() > days * 86_400_000) {
      throw new ForbiddenException(`Returns are accepted within ${days} days of delivery`);
    }
    const id = await this.create(order.id, dto, ReturnStatus.REQUESTED, null);
    return this.customerReturn(customerId, id);
  }

  /** Staff-created returns (e.g. at the counter) skip the window and start APPROVED. */
  async staffCreate(orderId: string, dto: CreateReturnDto, userId: string) {
    const id = await this.create(orderId, dto, ReturnStatus.APPROVED, userId);
    return this.get(id);
  }

  private async create(orderId: string, dto: CreateReturnDto, status: ReturnStatus, userId: string | null) {
    if (new Set(dto.items.map((i) => i.orderItemId)).size !== dto.items.length) {
      throw new BadRequestException('Each item may appear only once');
    }
    return this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, orderId);
      if (!RETURNABLE.includes(order.status)) {
        throw new UnprocessableEntityException('Only delivered orders can be returned');
      }
      const items = await tx.orderItem.findMany({ where: { orderId } });
      const pending = await tx.salesReturnItem.groupBy({
        by: ['orderItemId'],
        where: { salesReturn: { orderId, status: { in: OPEN } } },
        _sum: { quantity: true },
      });

      const rows = dto.items.map((r) => {
        const item = items.find((i) => i.id === r.orderItemId);
        if (!item) throw new BadRequestException('Item does not belong to this order');
        const open = pending.find((p) => p.orderItemId === item.id)?._sum.quantity ?? 0;
        const returnable = item.quantity - item.returnedQty - open;
        if (r.quantity > returnable) {
          throw new UnprocessableEntityException(`${item.sku}: only ${returnable} piece(s) can still be returned`);
        }
        // Estimate; the final amount is fixed when the goods are received.
        const slice = returnSlice(item, item.returnedQty + open, r.quantity);
        return { orderItemId: item.id, quantity: r.quantity, refundAmount: slice.refund, taxAmount: slice.tax };
      });

      const ret = await tx.salesReturn.create({
        data: {
          number: await this.sequences.next(tx, 'RET'),
          orderId,
          customerId: order.customerId,
          warehouseId: order.warehouseId,
          status,
          reason: dto.reason,
          refundAmount: sum(rows.map((r) => r.refundAmount)),
          handledById: userId,
          items: { create: rows },
        },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'SalesReturn', entityId: ret.id, after: dto }, tx);
      return ret.id;
    });
  }

  // -------------------------------------------------------------- reading ---

  async list(q: ReturnsQueryDto) {
    const where: Prisma.SalesReturnWhereInput = { status: q.status };
    const [items, total] = await Promise.all([
      this.prisma.salesReturn.findMany({
        where,
        include: { order: { select: { number: true } }, customer: { select: { name: true, phone: true } }, _count: { select: { items: true } } },
        orderBy: { requestedAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.salesReturn.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async get(id: string) {
    const ret = await this.prisma.salesReturn.findUnique({ where: { id }, include: returnInclude });
    if (!ret) throw new NotFoundException('Return not found');
    const order = await this.prisma.order.findUniqueOrThrow({ where: { id: ret.orderId } });
    const journalEntries = await this.prisma.journalEntry.findMany({
      where: { sourceType: 'SALES_RETURN', sourceId: id },
      include: { lines: { orderBy: { lineNo: 'asc' }, include: { account: { select: { code: true, nameAr: true } } } } },
    });
    return { ...ret, refundDue: ret.status === ReturnStatus.RECEIVED ? this.refundDue(order, ret) : money(0), journalEntries };
  }

  customerReturns(customerId: string) {
    return this.prisma.salesReturn.findMany({
      where: { customerId },
      select: this.customerSelect,
      orderBy: { requestedAt: 'desc' },
    });
  }

  private async customerReturn(customerId: string, id: string) {
    return this.prisma.salesReturn.findFirstOrThrow({ where: { id, customerId }, select: this.customerSelect });
  }

  private readonly customerSelect = {
    id: true,
    number: true,
    status: true,
    reason: true,
    rejectReason: true,
    refundAmount: true,
    requestedAt: true,
    resolvedAt: true,
    order: { select: { number: true } },
    items: { select: { quantity: true, refundAmount: true, orderItem: { select: { productName: true, sku: true, size: true, color: true } } } },
  } satisfies Prisma.SalesReturnSelect;

  // ------------------------------------------------------------ decisions ---

  async approve(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const ret = await this.lock(tx, id);
      if (ret.status !== ReturnStatus.REQUESTED) throw new BadRequestException('Only requested returns can be approved');
      await tx.salesReturn.update({ where: { id }, data: { status: ReturnStatus.APPROVED, handledById: userId } });
      await this.notifyCustomer(tx, ret, 'approved');
      await this.audit.log({ userId, action: 'APPROVE', entityType: 'SalesReturn', entityId: id }, tx);
    });
    return this.get(id);
  }

  async reject(id: string, reason: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const ret = await this.lock(tx, id);
      if (!OPEN.includes(ret.status)) throw new BadRequestException(`Cannot reject a ${ret.status} return`);
      await tx.salesReturn.update({
        where: { id },
        data: { status: ReturnStatus.REJECTED, rejectReason: reason, handledById: userId, resolvedAt: new Date() },
      });
      await this.notifyCustomer(tx, ret, 'rejected', reason);
      await this.audit.log({ userId, action: 'CANCEL', entityType: 'SalesReturn', entityId: id, after: { reason } }, tx);
    });
    return this.get(id);
  }

  async receive(id: string, dto: ReceiveReturnDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const ret = await this.lock(tx, id);
      if (ret.status !== ReturnStatus.APPROVED) throw new BadRequestException('Approve the return before receiving the goods');
      const order = await this.lockOrder(tx, ret.orderId);
      const lines = await tx.salesReturnItem.findMany({ where: { salesReturnId: id }, include: { orderItem: true } });
      if (dto.items?.some((d) => !lines.find((l) => l.id === d.itemId))) {
        throw new BadRequestException('Item does not belong to this return');
      }

      let refund = ZERO;
      let tax = ZERO;
      let restockedCost = ZERO;
      for (const line of [...lines].sort((a, b) => a.orderItem.variantId.localeCompare(b.orderItem.variantId))) {
        const item = line.orderItem;
        if (item.returnedQty + line.quantity > item.quantity) {
          throw new UnprocessableEntityException(`${item.sku} was already returned`);
        }
        const restock = dto.items?.find((d) => d.itemId === line.id)?.restock ?? true;
        const slice = returnSlice(item, item.returnedQty, line.quantity);
        refund = refund.add(slice.refund);
        tax = tax.add(slice.tax);

        if (restock) {
          const r = await this.stock.stockIn(tx, {
            type: StockMovementType.SALES_RETURN,
            variantId: item.variantId,
            warehouseId: ret.warehouseId,
            quantity: line.quantity,
            unitCost: item.unitCost,
            sourceType: 'SALES_RETURN',
            sourceId: ret.id,
            note: `${ret.number} · ${order.number}`,
            userId,
          });
          restockedCost = restockedCost.add(r.value);
        }
        await tx.salesReturnItem.update({
          where: { id: line.id },
          data: { refundAmount: slice.refund, taxAmount: slice.tax, restock },
        });
        await tx.orderItem.update({ where: { id: item.id }, data: { returnedQty: { increment: line.quantity } } });
      }

      await this.ledger.post(tx, {
        description: `Sales return ${ret.number} for order ${order.number}`,
        sourceType: 'SALES_RETURN',
        sourceId: ret.id,
        userId,
        lines: [
          { account: 'SALES_RETURNS', debit: refund.sub(tax) },
          { account: 'VAT_OUTPUT', debit: tax },
          { account: 'AR', credit: refund, customerId: order.customerId },
          { account: 'INVENTORY', debit: restockedCost },
          { account: 'COGS', credit: restockedCost },
        ],
      });
      await tx.order.update({ where: { id: order.id }, data: { returnedTotal: { increment: refund } } });
      await tx.salesReturn.update({
        where: { id },
        data: { status: ReturnStatus.RECEIVED, refundAmount: refund, receivedAt: new Date(), handledById: userId },
      });
      await this.notifyCustomer(tx, ret, 'received');
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'SalesReturn', entityId: id, after: { received: true, refund } }, tx);
    });
    return this.get(id);
  }

  async refund(id: string, dto: RefundReturnDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const ret = await this.lock(tx, id);
      if (ret.status !== ReturnStatus.RECEIVED) throw new BadRequestException('Only received returns can be refunded');
      const order = await this.lockOrder(tx, ret.orderId);
      const amount = this.refundDue(order, ret);
      if (!amount.gt(0)) {
        throw new UnprocessableEntityException('Nothing to refund: the return already reduced what the customer owes');
      }
      await this.payments.refundReturn(tx, order, { ...dto, amount, reason: `Return ${ret.number}` }, userId);
      await tx.salesReturn.update({ where: { id }, data: { status: ReturnStatus.REFUNDED, resolvedAt: new Date() } });
    });
    return this.get(id);
  }

  /** Cash owed back: what was paid beyond the order's value after returns, capped at this return. */
  private refundDue(order: Order, ret: SalesReturn) {
    const overpaid = dec(order.amountPaid).sub(dec(order.grandTotal).sub(order.returnedTotal));
    return money(Decimal.max(ZERO, Decimal.min(overpaid, ret.refundAmount)));
  }

  private async notifyCustomer(tx: Tx, ret: SalesReturn, event: 'approved' | 'rejected' | 'received', reason?: string) {
    const order = await tx.order.findUniqueOrThrow({ where: { id: ret.orderId }, select: { number: true, customerId: true } });
    await this.notifications.notifyCustomer(tx, order.customerId, returnMessage({ number: ret.number, order }, event, reason));
  }

  private async lock(tx: Tx, id: string): Promise<SalesReturn> {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM sales_returns WHERE id = ${id}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException('Return not found');
    return tx.salesReturn.findUniqueOrThrow({ where: { id } });
  }

  private async lockOrder(tx: Tx, id: string): Promise<Order> {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM orders WHERE id = ${id}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException('Order not found');
    return tx.order.findUniqueOrThrow({ where: { id } });
  }
}
