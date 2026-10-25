import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { randomInt } from 'node:crypto';
import { GiftCardStatus, Prisma } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { CurrencyService, inCurrency, toBase } from '../common/currency.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { PrismaService } from '../prisma/prisma.service';
import { TreasuryService } from '../treasury/treasury.service';

type User = { id: string; permissions: string[] };

/** No 0/O/1/I so codes can be read over the phone. */
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const newCode = () => `GC-${Array.from({ length: 8 }, (_, i) => (i === 4 ? '-' : '') + ALPHABET[randomInt(ALPHABET.length)]).join('')}`;
const normal = (code: string) => code.trim().toUpperCase().replace(/\s+/g, '');

/**
 * Gift cards. Selling one puts its money in a box and on the gift-card liability; redeeming
 * moves the whole value onto a customer's account, where it pays at the till or in the app
 * like any other credit.
 */
@Injectable()
export class GiftCardsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly treasury: TreasuryService,
    private readonly currencies: CurrencyService,
    private readonly audit: AuditService,
  ) {}

  async issue(dto: { currency: string; amount: string; cashBoxId?: string; buyerName?: string; note?: string; expiresAt?: string }, user: User) {
    const { rate } = await this.currencies.requireActive(dto.currency);
    const amount = inCurrency(dto.amount, dto.currency);
    if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const expiresAt = dto.expiresAt ? new Date(dto.expiresAt) : null;
    if (expiresAt && Number.isNaN(expiresAt.getTime())) throw new BadRequestException('Invalid expiry date');
    await this.treasury.ensureCashierBoxes(user.id);
    const id = await this.prisma.$transaction(async (tx) => {
      const box = dto.cashBoxId ? await this.treasury.visibleBox(dto.cashBoxId, user) : await this.treasury.defaultBox(tx, dto.currency, { userId: user.id });
      const sar = toBase(amount, dto.currency, rate);
      let code = newCode();
      while (await tx.giftCard.findUnique({ where: { code } })) code = newCode();
      const card = await tx.giftCard.create({
        data: { code, currency: dto.currency, amount, baseAmount: sar, buyerName: dto.buyerName, note: dto.note, expiresAt, issuedById: user.id, cashBoxId: box.id },
      });
      const into = await this.treasury.moneyLines(tx, { account: { id: box.accountId }, currency: dto.currency, amount, sar });
      const booked = into.reduce((t, l) => t.add(l.debit ?? 0).sub(l.credit ?? 0), new Prisma.Decimal(0));
      const entry = await this.ledger.post(tx, {
        description: `بيع بطاقة هدية ${code}${dto.buyerName ? ` لـ ${dto.buyerName}` : ''}`,
        sourceType: 'GIFT_CARD',
        sourceId: card.id,
        userId: user.id,
        lines: [...into, { account: 'GIFT_CARDS', credit: booked }],
      });
      await tx.giftCard.update({ where: { id: card.id }, data: { issueEntryId: entry.id, baseAmount: booked } });
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'GiftCard', entityId: card.id, after: { ...dto, code } }, tx);
      return card.id;
    });
    return this.get(id);
  }

  /** What a card is worth and whether it can still be used. */
  async check(code: string) {
    const card = await this.prisma.giftCard.findUnique({ where: { code: normal(code) } });
    if (!card) throw new NotFoundException('بطاقة الهدية غير موجودة');
    return { code: card.code, currency: card.currency, amount: card.amount, status: card.status, expiresAt: card.expiresAt, usable: this.usable(card) === null, reason: this.usable(card) };
  }

  /** Moves the card's value to [customerId]'s account (a customer redeeming it in the app, or staff at the till). */
  async redeem(code: string, customerId: string, userId?: string) {
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, name: true, code: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    if (customer.code === 'CUS-WALKIN') throw new BadRequestException('Pick a named customer');
    const id = await this.prisma.$transaction(async (tx) => {
      const found = await tx.giftCard.findUnique({ where: { code: normal(code) } });
      if (!found) throw new NotFoundException('بطاقة الهدية غير موجودة');
      await tx.$queryRaw`SELECT id FROM gift_cards WHERE id = ${found.id}::uuid FOR UPDATE`;
      const card = await tx.giftCard.findUniqueOrThrow({ where: { id: found.id } });
      const why = this.usable(card);
      if (why) throw new UnprocessableEntityException(why);
      const lines = await this.treasury.moneyLines(tx, {
        account: 'CUSTOMER_ACCOUNTS',
        customerId,
        currency: card.currency,
        amount: card.amount.neg(),
        sar: card.baseAmount.neg(),
      });
      const entry = await this.ledger.post(tx, {
        description: `استخدام بطاقة هدية ${card.code} في حساب ${customer.name}`,
        sourceType: 'GIFT_CARD',
        sourceId: card.id,
        userId,
        lines: [{ account: 'GIFT_CARDS', debit: card.baseAmount }, ...lines],
      });
      await tx.giftCard.update({ where: { id: card.id }, data: { status: GiftCardStatus.REDEEMED, redeemedAt: new Date(), redeemedCustomerId: customerId, redeemEntryId: entry.id } });
      await this.audit.log({ userId: userId ?? null, action: 'UPDATE', entityType: 'GiftCard', entityId: card.id, after: { redeemedBy: customerId } }, tx);
      return card.id;
    });
    return this.get(id);
  }

  /** Stops an unused card; its sale is reversed (the money leaves the box again). */
  async cancel(id: string, user: User) {
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM gift_cards WHERE id = ${id}::uuid FOR UPDATE`;
      const card = await tx.giftCard.findUnique({ where: { id } });
      if (!card) throw new NotFoundException('Gift card not found');
      if (card.status !== GiftCardStatus.ACTIVE) throw new UnprocessableEntityException('Only an unused card can be cancelled');
      if (card.issueEntryId) await this.ledger.reverse(tx, card.issueEntryId, user.id, `Gift card ${card.code} cancelled`);
      await tx.giftCard.update({ where: { id }, data: { status: GiftCardStatus.CANCELLED } });
      await this.audit.log({ userId: user.id, action: 'UPDATE', entityType: 'GiftCard', entityId: id, after: { cancelled: true } }, tx);
    });
    return this.get(id);
  }

  async list(q: PaginationQueryDto & { status?: GiftCardStatus; q?: string }) {
    const where: Prisma.GiftCardWhereInput = {
      status: q.status,
      ...(q.q ? { OR: [{ code: { contains: normal(q.q) } }, { buyerName: { contains: q.q, mode: 'insensitive' } }] } : {}),
    };
    const [rows, total, outstanding] = await Promise.all([
      this.prisma.giftCard.findMany({ where, orderBy: { createdAt: 'desc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.giftCard.count({ where }),
      this.prisma.giftCard.groupBy({ by: ['currency'], where: { status: GiftCardStatus.ACTIVE }, _sum: { amount: true }, _count: { _all: true } }),
    ]);
    return {
      ...paginated(await this.withNames(rows), total, q),
      outstanding: outstanding.map((o) => ({ currency: o.currency, amount: o._sum.amount, cards: o._count._all })),
    };
  }

  async get(id: string) {
    const card = await this.prisma.giftCard.findUnique({ where: { id } });
    if (!card) throw new NotFoundException('Gift card not found');
    return (await this.withNames([card]))[0];
  }

  private async withNames(rows: Prisma.GiftCardGetPayload<object>[]) {
    const ids = [...new Set(rows.map((r) => r.redeemedCustomerId).filter((x): x is string => !!x))];
    const names = new Map((await this.prisma.customer.findMany({ where: { id: { in: ids } }, select: { id: true, name: true } })).map((c) => [c.id, c.name]));
    return rows.map((r) => ({ ...r, usable: this.usable(r) === null, redeemedBy: r.redeemedCustomerId ? { id: r.redeemedCustomerId, name: names.get(r.redeemedCustomerId) ?? '' } : null }));
  }

  /** Why a card cannot be used, or null. */
  private usable(card: { status: GiftCardStatus; expiresAt: Date | null }) {
    if (card.status === GiftCardStatus.REDEEMED) return 'هذه البطاقة استُخدمت من قبل';
    if (card.status === GiftCardStatus.CANCELLED) return 'هذه البطاقة ملغاة';
    if (card.expiresAt && card.expiresAt < new Date()) return 'انتهت صلاحية هذه البطاقة';
    return null;
  }
}
