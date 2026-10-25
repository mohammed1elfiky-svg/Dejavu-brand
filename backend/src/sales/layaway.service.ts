import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { LayawayStatus, Prisma, ProductStatus } from '../generated/prisma/client';
import { activePromotions, promotedPrice } from '../catalog/promotions';
import { AuditService } from '../common/audit.service';
import { CurrencyService, inCurrency } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { Decimal, dec, money, sum } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';
import { TreasuryService } from '../treasury/treasury.service';
import { PosTenderDto } from './dto/pos.dto';
import { PosService } from './pos.service';

type User = { id: string; permissions: string[] };
type LayawayItem = { variantId: string; quantity: number; unitPrice: string };

export interface DepositInput {
  currency: string;
  amount: string;
  /** Box the money went into; the employee's own drawer by default. */
  cashBoxId?: string;
}

const include = {
  customer: { select: { id: true, name: true, phone: true, code: true } },
  payments: { orderBy: { createdAt: 'asc' } },
} satisfies Prisma.LayawayInclude;

/**
 * حجز بعربون: pieces put aside for a customer against a deposit. The pieces are reserved
 * (not sold to anyone else); every deposit is a receipt onto the customer's account, so the
 * money is the customer's until the sale. Completing it rings up a normal till sale paid
 * first from that credit; cancelling frees the pieces and leaves the deposit as credit.
 */
@Injectable()
export class LayawayService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly stock: StockService,
    private readonly treasury: TreasuryService,
    private readonly accounts: CustomerAccountsService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly pos: PosService,
  ) {}

  async create(dto: { customerId: string; items: { variantId: string; quantity: number }[]; deposit?: DepositInput; expiresAt?: string; note?: string }, user: User) {
    const customer = await this.prisma.customer.findUnique({ where: { id: dto.customerId }, select: { id: true, name: true, code: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    if (customer.code === 'CUS-WALKIN') throw new BadRequestException('Pick a named customer');
    const quantities = new Map<string, number>();
    for (const i of dto.items) quantities.set(i.variantId, (quantities.get(i.variantId) ?? 0) + i.quantity);
    const variants = await this.prisma.productVariant.findMany({
      where: { id: { in: [...quantities.keys()] } },
      select: { id: true, sku: true, isActive: true, price: true, product: { select: { id: true, basePrice: true, status: true } } },
    });
    if (variants.length !== quantities.size) throw new NotFoundException('Some items were not found');
    const bad = variants.find((v) => !v.isActive || v.product.status !== ProductStatus.ACTIVE);
    if (bad) throw new UnprocessableEntityException(`${bad.sku} is not for sale`);
    const promos = await activePromotions(this.prisma, variants.map((v) => v.product.id));
    const items: LayawayItem[] = variants.map((v) => ({
      variantId: v.id,
      quantity: quantities.get(v.id)!,
      unitPrice: promotedPrice(v.price ?? v.product.basePrice, promos.get(v.product.id)).toFixed(2),
    }));
    const total = money(sum(items.map((i) => dec(i.unitPrice).mul(i.quantity))));
    const expiresAt = dto.expiresAt ? new Date(dto.expiresAt) : null;
    if (expiresAt && Number.isNaN(expiresAt.getTime())) throw new BadRequestException('Invalid expiry date');

    const id = await this.prisma.$transaction(async (tx) => {
      const warehouse = await tx.warehouse.findFirst({ where: { isDefault: true, isActive: true } });
      if (!warehouse) throw new UnprocessableEntityException('No default warehouse is configured');
      for (const i of [...items].sort((a, b) => a.variantId.localeCompare(b.variantId))) await this.stock.reserve(tx, i.variantId, warehouse.id, i.quantity);
      const lay = await tx.layaway.create({
        data: {
          number: await this.sequences.next(tx, 'LAY'),
          customerId: customer.id,
          warehouseId: warehouse.id,
          items,
          total,
          expiresAt,
          note: dto.note,
          createdById: user.id,
        },
      });
      if (dto.deposit) await this.depositIn(tx, lay.id, customer, dto.deposit, user);
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'Layaway', entityId: lay.id, after: dto }, tx);
      return lay.id;
    });
    return this.get(id);
  }

  /** Another instalment towards the reservation. */
  async addPayment(id: string, dto: DepositInput, user: User) {
    await this.prisma.$transaction(async (tx) => {
      const lay = await this.lockOpen(tx, id);
      const customer = await tx.customer.findUniqueOrThrow({ where: { id: lay.customerId }, select: { id: true, name: true } });
      await this.depositIn(tx, id, customer, dto, user);
    });
    return this.get(id);
  }

  private async depositIn(tx: Tx, layawayId: string, customer: { id: string; name: string }, d: DepositInput, user: User) {
    const lay = await tx.layaway.findUniqueOrThrow({ where: { id: layawayId }, include: { payments: true } });
    // All instalments in one currency, so the sale can take them from the account in one go.
    const first = lay.payments[0]?.currency;
    if (first && first !== d.currency) throw new UnprocessableEntityException(`This reservation is paid in ${first}`);
    await this.currencies.requireActive(d.currency, tx);
    const amount = inCurrency(d.amount, d.currency);
    if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const box = d.cashBoxId ? await this.treasury.visibleBox(d.cashBoxId, user) : await this.treasury.defaultBox(tx, d.currency, { userId: user.id });
    const { entry, booked } = await this.accounts.credit(tx, {
      customerId: customer.id,
      currency: d.currency,
      amount,
      boxId: box.id,
      sourceType: 'CUSTOMER_PAYMENT',
      sourceId: layawayId,
      description: `عربون الحجز ${lay.number} من ${customer.name}`,
      userId: user.id,
    });
    await tx.layawayPayment.create({ data: { layawayId, currency: d.currency, amount, baseAmount: booked, journalEntryId: entry.id, createdById: user.id } });
  }

  /**
   * Hands the pieces over: a till sale for the reserved items, paid first from the deposits
   * (on the customer's account) and then with [payments] for the rest.
   */
  async complete(id: string, dto: { payments?: PosTenderDto[]; paymentMethod?: 'CASH' | 'BANK_TRANSFER'; changeCurrency?: string; note?: string }, user: User) {
    await this.treasury.ensureCashierBoxes(user.id);
    const orderId = await this.prisma.$transaction(async (tx) => {
      const lay = await this.lockOpen(tx, id);
      const items = lay.items as LayawayItem[];
      for (const i of [...items].sort((a, b) => a.variantId.localeCompare(b.variantId))) await this.stock.release(tx, i.variantId, lay.warehouseId, i.quantity);
      const payments = await tx.layawayPayment.findMany({ where: { layawayId: id } });
      const currency = payments[0]?.currency ?? 'SAR';
      const deposited = sum(payments.map((p) => dec(p.amount)));
      const orderId = await this.pos.sellIn(
        tx,
        {
          items: items.map((i) => ({ variantId: i.variantId, quantity: i.quantity })),
          currency,
          saleType: 'CASH',
          customerId: lay.customerId,
          payments: dto.payments,
          paymentMethod: dto.paymentMethod,
          changeCurrency: dto.changeCurrency,
          // Up to what was deposited (less if the customer spent part of the credit since).
          fromAccountMax: deposited.gt(0) ? deposited : undefined,
          note: [`استلام الحجز ${lay.number}`, dto.note].filter(Boolean).join(' — '),
        },
        user.id,
      );
      await tx.layaway.update({ where: { id }, data: { status: LayawayStatus.COMPLETED, saleOrderId: orderId, closedAt: new Date() } });
      await this.audit.log({ userId: user.id, action: 'UPDATE', entityType: 'Layaway', entityId: id, after: { completed: orderId } }, tx);
      return orderId;
    });
    return { layaway: await this.get(id), sale: await this.pos.receipt(orderId) };
  }

  /** Frees the pieces; what was deposited stays on the customer's account as credit. */
  async cancel(id: string, reason: string | undefined, user: User) {
    await this.prisma.$transaction(async (tx) => {
      const lay = await this.lockOpen(tx, id);
      for (const i of [...(lay.items as LayawayItem[])].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        await this.stock.release(tx, i.variantId, lay.warehouseId, i.quantity);
      }
      await tx.layaway.update({ where: { id }, data: { status: LayawayStatus.CANCELLED, closedAt: new Date(), note: [lay.note, reason && `أُلغي: ${reason}`].filter(Boolean).join(' — ') || null } });
      await this.audit.log({ userId: user.id, action: 'UPDATE', entityType: 'Layaway', entityId: id, after: { cancelled: reason ?? true } }, tx);
    });
    return this.get(id);
  }

  async list(q: PaginationQueryDto & { status?: LayawayStatus; customerId?: string }) {
    const where: Prisma.LayawayWhereInput = { status: q.status, customerId: q.customerId };
    const [rows, total] = await Promise.all([
      this.prisma.layaway.findMany({ where, include, orderBy: { createdAt: 'desc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.layaway.count({ where }),
    ]);
    return paginated(await this.present(rows), total, q);
  }

  async get(id: string) {
    const row = await this.prisma.layaway.findUnique({ where: { id }, include });
    if (!row) throw new NotFoundException('Reservation not found');
    return (await this.present([row]))[0];
  }

  private async present(rows: Prisma.LayawayGetPayload<{ include: typeof include }>[]) {
    const ids = [...new Set(rows.flatMap((r) => (r.items as LayawayItem[]).map((i) => i.variantId)))];
    const variants = new Map(
      (
        await this.prisma.productVariant.findMany({
          where: { id: { in: ids } },
          select: { id: true, sku: true, size: true, color: true, product: { select: { id: true, nameAr: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } },
        })
      ).map((v) => [v.id, v]),
    );
    const now = new Date();
    return Promise.all(
      rows.map(async (r) => {
        const currency = r.payments[0]?.currency ?? null;
        const paidSar = money(sum(r.payments.map((p) => dec(p.baseAmount))));
        const rate = currency ? await this.currencies.rate(currency) : dec(1);
        return {
          id: r.id,
          number: r.number,
          status: r.status,
          customer: r.customer,
          total: r.total,
          paid: { currency, amount: sum(r.payments.map((p) => dec(p.amount))), sar: paidSar },
          remaining: money(Decimal.max(dec(0), dec(r.total).sub(paidSar))),
          /** What is left, in the deposit currency at today's rate. */
          remainingInCurrency: currency ? inCurrency(Decimal.max(dec(0), dec(r.total).sub(paidSar)).mul(rate), currency) : null,
          expiresAt: r.expiresAt,
          expired: r.status === LayawayStatus.OPEN && !!r.expiresAt && r.expiresAt < now,
          note: r.note,
          saleOrderId: r.saleOrderId,
          createdAt: r.createdAt,
          closedAt: r.closedAt,
          payments: r.payments.map((p) => ({ id: p.id, currency: p.currency, amount: p.amount, createdAt: p.createdAt })),
          items: (r.items as LayawayItem[]).map((i) => {
            const v = variants.get(i.variantId);
            return {
              variantId: i.variantId,
              quantity: i.quantity,
              unitPrice: i.unitPrice,
              sku: v?.sku ?? '',
              size: v?.size ?? null,
              color: v?.color ?? null,
              productId: v?.product.id ?? null,
              name: v?.product.nameAr ?? '',
              imageUrl: v?.product.images[0]?.url ?? null,
            };
          }),
        };
      }),
    );
  }

  private async lockOpen(tx: Tx, id: string) {
    await tx.$queryRaw`SELECT id FROM layaways WHERE id = ${id}::uuid FOR UPDATE`;
    const lay = await tx.layaway.findUnique({ where: { id } });
    if (!lay) throw new NotFoundException('Reservation not found');
    if (lay.status !== LayawayStatus.OPEN) throw new UnprocessableEntityException('This reservation is already closed');
    return lay;
  }
}

