import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, currencyDecimals, formatIn, inCurrency, toBase } from '../common/currency.service';
import { SmsService } from '../sms/sms.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { Decimal, dec, money } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from './treasury.service';
import { TransfersService } from './transfers.service';

/**
 * Each customer's running account per currency. Positive: the customer owes the store
 * (credit sales). Negative: the store holds the customer's money (wallet top-ups).
 * Lives on the CUSTOMER_ACCOUNTS ledger account, tagged with the customer.
 */
@Injectable()
export class CustomerAccountsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly treasury: TreasuryService,
    private readonly currencies: CurrencyService,
    private readonly audit: AuditService,
    private readonly sms: SmsService,
    private readonly transfers: TransfersService,
  ) {}

  async balances(customerId: string, tx: Tx | PrismaService = this.prisma) {
    const rows = await tx.customerAccountEntry.groupBy({
      by: ['currency'],
      where: { customerId },
      _sum: { amount: true, baseAmount: true },
    });
    return rows
      .map((r) => {
        const balance = inCurrency(dec(r._sum.amount), r.currency);
        // What the customer can spend from their wallet in this currency.
        return { currency: r.currency, balance, available: balance.lt(0) ? balance.neg() : dec(0), bookValue: money(dec(r._sum.baseAmount)) };
      })
      .filter((r) => !r.balance.isZero() || !r.bookValue.isZero());
  }

  /**
   * Customers who owe money: balances per currency (+ = owes), the SAR total, and when
   * they last paid or bought, oldest debt first in time since their last payment.
   */
  async debtors(minDays = 0) {
    const rows = await this.prisma.$queryRaw<
      { customer_id: string; currency: string; balance: string; base: string }[]
    >`SELECT customer_id::text, currency, SUM(amount)::text AS balance, SUM(base_amount)::text AS base
      FROM customer_account_entries GROUP BY 1, 2 HAVING SUM(amount) > 0`;
    const ids = [...new Set(rows.map((r) => r.customer_id))];
    if (!ids.length) return { items: [], totalOwed: money(0) };
    const [customers, payments, purchases] = await Promise.all([
      this.prisma.customer.findMany({ where: { id: { in: ids } }, select: { id: true, code: true, name: true, phone: true, isMerchant: true } }),
      this.prisma.customerAccountEntry.groupBy({ by: ['customerId'], where: { customerId: { in: ids }, amount: { lt: 0 } }, _max: { createdAt: true } }),
      this.prisma.customerAccountEntry.groupBy({ by: ['customerId'], where: { customerId: { in: ids }, amount: { gt: 0 } }, _max: { createdAt: true }, _min: { createdAt: true } }),
    ]);
    const now = Date.now();
    const items = customers
      .filter((c) => c.code !== 'CUS-WALKIN')
      .map((c) => {
        const mine = rows.filter((r) => r.customer_id === c.id);
        const lastPaymentAt = payments.find((p) => p.customerId === c.id)?._max.createdAt ?? null;
        const bought = purchases.find((p) => p.customerId === c.id);
        const since = lastPaymentAt ?? bought?._min.createdAt ?? null;
        return {
          ...c,
          balances: mine.map((r) => ({ currency: r.currency, balance: inCurrency(r.balance, r.currency) })),
          owedSar: money(mine.reduce((t, r) => t.add(dec(r.base)), dec(0))),
          lastPaymentAt,
          lastPurchaseAt: bought?._max.createdAt ?? null,
          /** Days since the last payment (or since the first purchase on account when never paid). */
          daysSincePayment: since ? Math.floor((now - since.getTime()) / 86_400_000) : null,
        };
      })
      .filter((d) => (d.daysSincePayment ?? 0) >= minDays)
      .sort((a, b) => (b.daysSincePayment ?? 0) - (a.daysSincePayment ?? 0) || b.owedSar.cmp(a.owedSar));
    return { items, totalOwed: money(items.reduce((t, d) => t.add(d.owedSar), dec(0))) };
  }

  /** Texts each customer what they still owe, from the store phone. */
  async remind(customerIds: string[], userId: string) {
    let queued = 0;
    const skipped: { customerId: string; reason: string }[] = [];
    for (const id of [...new Set(customerIds)]) {
      const customer = await this.prisma.customer.findUnique({ where: { id }, select: { id: true, name: true, phone: true } });
      if (!customer?.phone) {
        skipped.push({ customerId: id, reason: 'no phone' });
        continue;
      }
      const owed = (await this.balances(id)).filter((b) => b.balance.gt(0));
      if (!owed.length) {
        skipped.push({ customerId: id, reason: 'owes nothing' });
        continue;
      }
      const lines = owed.map((b) => formatIn(b.balance, b.currency)).join(' + ');
      await this.sms.enqueue(customer.phone, `Dejavu: عزيزنا ${customer.name}، نذكّرك بأن المتبقي عليك: ${lines}.\nنسعد بسدادك في أقرب وقت، وشكراً لتعاملك معنا.`, 'OTHER');
      queued++;
    }
    await this.audit.log({ userId, action: 'CREATE', entityType: 'DebtReminder', after: { customerIds, queued } });
    return { queued, skipped };
  }

  async statement(customerId: string, q: PaginationQueryDto & { currency?: string }) {
    const where = { customerId, currency: q.currency };
    const [items, total] = await Promise.all([
      this.prisma.customerAccountEntry.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
        select: { id: true, currency: true, amount: true, sourceType: true, sourceId: true, note: true, createdAt: true },
      }),
      this.prisma.customerAccountEntry.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  /**
   * The full statement for printing: one section per currency the customer dealt in, oldest
   * first with a running balance, each line with its invoice number and pieces so the invoice
   * can be looked up. [owes] = على العميل, [credit] = للعميل, balance > 0 = the customer owes.
   */
  async fullStatement(customerId: string, range: { from?: Date; to?: Date } = {}) {
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, code: true, name: true, phone: true, username: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    const entries = await this.prisma.customerAccountEntry.findMany({
      where: { customerId },
      orderBy: { createdAt: 'asc' },
      select: { id: true, currency: true, amount: true, sourceType: true, sourceId: true, note: true, createdAt: true },
    });
    const ids = [...new Set(entries.map((e) => e.sourceId).filter((x): x is string => !!x))];
    const [orders, topUps, layaways, returns] = await Promise.all([
      this.prisma.order.findMany({ where: { id: { in: ids } }, select: { id: true, number: true, channel: true, items: { select: { quantity: true } } } }),
      this.prisma.walletTopUp.findMany({ where: { id: { in: ids } }, select: { id: true, number: true } }),
      this.prisma.layaway.findMany({ where: { id: { in: ids } }, select: { id: true, number: true } }),
      this.prisma.salesReturn.findMany({ where: { id: { in: ids } }, select: { id: true, number: true, orderId: true } }),
    ]);
    const order = new Map(orders.map((o) => [o.id, o]));
    const topUp = new Map(topUps.map((t) => [t.id, t.number]));
    const layaway = new Map(layaways.map((l) => [l.id, l.number]));
    const ret = new Map(returns.map((r) => [r.id, r]));
    const sections = new Map<string, { currency: string; opening: Decimal; rows: unknown[]; owes: Decimal; credit: Decimal; balance: Decimal }>();
    for (const e of entries) {
      const s = sections.get(e.currency) ?? { currency: e.currency, opening: dec(0), rows: [], owes: dec(0), credit: dec(0), balance: dec(0) };
      sections.set(e.currency, s);
      const amount = dec(e.amount);
      s.balance = s.balance.add(amount);
      if (range.from && e.createdAt < range.from) {
        s.opening = s.balance;
        continue;
      }
      if (range.to && e.createdAt >= range.to) continue;
      const o = e.sourceId ? order.get(e.sourceId) : undefined;
      const r = e.sourceId ? ret.get(e.sourceId) : undefined;
      if (amount.gt(0)) s.owes = s.owes.add(amount);
      else s.credit = s.credit.add(amount.neg());
      s.rows.push({
        id: e.id,
        date: e.createdAt,
        description: STATEMENT_LABELS[e.sourceType] ?? walletNote(e.sourceType),
        note: e.note,
        /** The invoice (or top-up / reservation / return) number, to look it up. */
        reference: o?.number ?? (e.sourceId && (topUp.get(e.sourceId) ?? layaway.get(e.sourceId))) ?? r?.number ?? null,
        orderId: o?.id ?? r?.orderId ?? null,
        pieces: o ? o.items.reduce((n, i) => n + i.quantity, 0) : null,
        owes: amount.gt(0) ? amount : dec(0),
        credit: amount.lt(0) ? amount.neg() : dec(0),
        balance: s.balance,
      });
    }
    return {
      customer,
      from: range.from ?? null,
      to: range.to ?? null,
      // Only the currencies the customer dealt in, each on its own page.
      currencies: [...sections.values()].map((s) => ({ ...s, closing: s.rows.length ? (s.rows[s.rows.length - 1] as { balance: Decimal }).balance : s.opening })),
    };
  }

  /** Customer's wallet: spendable balances and recent movements. */
  async wallet(customerId: string) {
    const [balances, recent, buyer] = await Promise.all([
      this.balances(customerId),
      this.prisma.customerAccountEntry.findMany({
        where: { customerId },
        orderBy: { createdAt: 'desc' },
        take: 30,
        select: { id: true, currency: true, amount: true, sourceType: true, note: true, createdAt: true },
      }),
      this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { isMerchant: true, merchantDiscount: true, creditLimit: true } }),
    ]);
    return {
      /** Merchants may pay orders "on account" (credit) up to their limit. */
      merchant: buyer.isMerchant ? { discount: buyer.merchantDiscount, creditLimit: buyer.creditLimit } : null,
      balances: balances.map((b) => ({ currency: b.currency, balance: b.balance.neg(), owed: b.balance.gt(0) ? b.balance : dec(0) })),
      // Shown from the customer's side: + money in, - money out.
      entries: recent.map((e) => ({ ...e, amount: dec(e.amount).neg(), note: e.sourceType === 'WALLET_GIFT' && e.note ? e.note : walletNote(e.sourceType) })),
    };
  }

  /**
   * Money the customer brings in, into [box] (a drawer, safe or bank of the same currency):
   * pays off what they owe, and anything beyond becomes wallet credit. The box takes the
   * account's book value, so no exchange difference arises until the money is exchanged.
   */
  async credit(
    tx: Tx,
    p: { customerId: string; currency: string; amount: Decimal; boxId: string; sourceType: 'CUSTOMER_PAYMENT' | 'WALLET_TOPUP'; sourceId?: string; description: string; userId: string | null },
  ) {
    const box = await tx.cashBox.findUnique({ where: { id: p.boxId } });
    if (!box || !box.isActive) throw new NotFoundException('Cash box not found');
    if (box.currency !== p.currency) throw new UnprocessableEntityException(`${box.name} holds ${box.currency}, not ${p.currency}`);
    const amount = inCurrency(p.amount, p.currency);
    if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const going = toBase(amount, p.currency, await this.currencies.rate(p.currency, tx));
    const booked = (await this.treasury.bookValue(tx, { customerId: p.customerId, currency: p.currency }, p.currency, amount.neg(), going.neg())).neg();
    const entry = await this.ledger.post(tx, {
      description: p.description,
      sourceType: p.sourceType,
      sourceId: p.sourceId,
      userId: p.userId,
      lines: [
        { account: { id: box.accountId }, debit: booked, holder: { currency: p.currency, amount } },
        { account: 'CUSTOMER_ACCOUNTS', credit: booked, customerId: p.customerId, holder: { currency: p.currency, amount } },
      ],
    });
    return { entry, amount, booked };
  }

  /**
   * A balance the customer had before the system (taking over old books):
   * OWES — the customer owes the store (Dr customer account / Cr opening balance equity);
   * CREDIT — the store holds the customer's money (the reverse).
   */
  async openingBalance(
    customerId: string,
    dto: { currency: string; amount: string; direction: 'OWES' | 'CREDIT'; note?: string },
    userId: string,
  ) {
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, name: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    const currency = dto.currency ?? BASE_CURRENCY;
    const { rate } = await this.currencies.requireActive(currency);
    const amount = inCurrency(dto.amount, currency);
    if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const sign = dto.direction === 'OWES' ? 1 : -1;
    const sar = toBase(amount, currency, rate);
    await this.prisma.$transaction(async (tx) => {
      const lines = await this.treasury.moneyLines(tx, {
        account: 'CUSTOMER_ACCOUNTS',
        customerId,
        currency,
        amount: amount.mul(sign),
        sar: sar.mul(sign),
        description: dto.note,
      });
      const entry = await this.ledger.post(tx, {
        description: `Opening balance of ${customer.name}: ${amount.toFixed()} ${currency} ${dto.direction === 'OWES' ? 'owed' : 'in credit'}${dto.note ? ` — ${dto.note}` : ''}`,
        sourceType: 'OPENING_BALANCE',
        sourceId: customerId,
        userId,
        lines: [...lines, sign > 0 ? { account: 'OPENING_BALANCE', credit: sar } : { account: 'OPENING_BALANCE', debit: sar }],
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'CustomerOpeningBalance', entityId: entry.id, after: { customerId, ...dto } }, tx);
    });
    return { balances: await this.balances(customerId) };
  }

  /** Staff take money from a customer at the till (debt repayment or a cash top-up). */
  async receipt(
    customerId: string,
    dto: { currency: string; amount: string; cashBoxId?: string; note?: string; sendSms?: boolean; reference?: string; slipFileId?: string },
    user: { id: string; permissions: string[] },
  ) {
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, name: true, phone: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    const currency = dto.currency ?? BASE_CURRENCY;
    await this.currencies.requireActive(currency);
    const slip = dto.reference || dto.slipFileId ? await this.transfers.referenceOf(dto.slipFileId, dto.reference) : null;
    await this.prisma.$transaction(async (tx) => {
      if (slip) {
        await this.transfers.register(tx, {
          reference: slip.reference,
          extracted: slip.extracted,
          source: 'RECEIPT',
          customerId,
          currency,
          amount: inCurrency(dto.amount, currency),
          bankName: slip.reading.bankName,
          fileId: dto.slipFileId,
          userId: user.id,
        });
      }
      const box = dto.cashBoxId ? await this.treasury.visibleBox(dto.cashBoxId, user) : await this.treasury.defaultBox(tx, currency, { userId: user.id });
      const { entry } = await this.credit(tx, {
        customerId,
        currency,
        amount: dec(dto.amount),
        boxId: box.id,
        sourceType: 'CUSTOMER_PAYMENT',
        description: `Receipt from ${customer.name}${dto.note ? `: ${dto.note}` : ''}`,
        userId: user.id,
      });
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'CustomerReceipt', entityId: entry.id, after: { customerId, ...dto } }, tx);
      if (dto.sendSms) {
        const left = (await this.balances(customerId, tx)).find((b) => b.currency === currency)?.balance ?? inCurrency(0, currency);
        await this.sms.enqueue(customer.phone, `Dejavu: تم إضافة ${formatIn(inCurrency(dto.amount, currency), currency)} إلى حسابك.\n${balanceLine(left, currency)}`, 'RECEIPT', tx);
      }
    });
    return { balances: await this.balances(customerId), smsQueued: !!dto.sendSms };
  }

  /**
   * Converts part of the customer's balance: [amount] of [fromCurrency] leaves the account
   * and [toAmount] of [toCurrency] comes in. Both sides are valued at the market rate;
   * the difference is the store's exchange gain or loss.
   */
  async exchange(
    customerId: string,
    dto: { fromCurrency: string; amount: string; toCurrency: string; toAmount: string; note?: string; sendSms?: boolean },
    userId: string,
  ) {
    if (dto.fromCurrency === dto.toCurrency) throw new BadRequestException('Pick two different currencies');
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, name: true, phone: true, code: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    if (customer.code === 'CUS-WALKIN') throw new BadRequestException('Pick a named customer');
    const [from, to] = await Promise.all([this.currencies.requireActive(dto.fromCurrency), this.currencies.requireActive(dto.toCurrency)]);
    const amount = inCurrency(dto.amount, from.code);
    const toAmount = inCurrency(dto.toAmount, to.code);
    if (!amount.gt(0) || !toAmount.gt(0)) throw new BadRequestException('Amounts must be greater than zero');
    const fromSar = toBase(amount, from.code, from.rate);
    const toSar = toBase(toAmount, to.code, to.rate);
    await this.prisma.$transaction(async (tx) => {
      const description = `Exchange in ${customer.name}'s account: ${amount.toFixed()} ${from.code} → ${toAmount.toFixed()} ${to.code}${dto.note ? ` — ${dto.note}` : ''}`;
      const entry = await this.exchangeIn(tx, { customerId, from: from.code, amount, fromSar, to: to.code, toAmount, toSar, description, userId });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'CustomerExchange', entityId: entry.id, after: { customerId, ...dto } }, tx);
      if (dto.sendSms) {
        const balances = await this.balances(customerId, tx);
        const line = (c: string) => balanceLine(balances.find((b) => b.currency === c)?.balance ?? inCurrency(0, c), c);
        await this.sms.enqueue(
          customer.phone,
          `Dejavu: تمت مصارفة ${formatIn(amount, from.code)} إلى ${formatIn(toAmount, to.code)} في حسابك.\n${line(from.code)}\n${line(to.code)}`,
          'EXCHANGE',
          tx,
        );
      }
    });
    return { balances: await this.balances(customerId), smsQueued: !!dto.sendSms };
  }

  /** Books [amount] of [from] out of the customer's account and [toAmount] of [to] into it. */
  private async exchangeIn(
    tx: Tx,
    p: { customerId: string; from: string; amount: Decimal; fromSar: Decimal; to: string; toAmount: Decimal; toSar: Decimal; description: string; userId?: string | null },
  ) {
    // Giving up [amount] of credit = owing that much more in that currency.
    const out = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId: p.customerId, currency: p.from, amount: p.amount, sar: p.fromSar, description: p.description });
    const inn = await this.treasury.moneyLines(tx, {
      account: 'CUSTOMER_ACCOUNTS',
      customerId: p.customerId,
      currency: p.to,
      amount: p.toAmount.neg(),
      sar: p.toSar.neg(),
      description: p.description,
    });
    const fx = [...out, ...inn].reduce((t, l) => t.add(dec(l.debit ?? 0)).sub(dec(l.credit ?? 0)), dec(0));
    return this.ledger.post(tx, {
      description: p.description,
      sourceType: 'CUSTOMER_PAYMENT',
      sourceId: p.customerId,
      userId: p.userId ?? undefined,
      lines: [...out, ...inn, ...(fx.isZero() ? [] : [fx.gt(0) ? { account: 'FX_DIFFERENCE' as const, credit: fx } : { account: 'FX_DIFFERENCE' as const, debit: fx.neg() }])],
    });
  }

  /**
   * Makes sure the wallet holds [needed] in [currency], changing the customer's credit in
   * other currencies (SAR first) at today's rates when that currency alone is short. So a
   * shopper with SAR credit can pay a YER order. Throws when all of it together is not enough.
   */
  async cover(tx: Tx, customerId: string, currency: string, needed: Decimal, label: string, userId?: string | null) {
    const own = await this.treasury.balanceOf(tx, { customerId, currency });
    let short = inCurrency(needed.add(own.amount), currency); // own.amount < 0 = credit
    if (!short.gt(0)) return;
    const target = await this.currencies.requireActive(currency, tx);
    const others = (await this.balances(customerId, tx))
      .filter((b) => b.currency !== currency && b.available.gt(0))
      .sort((a, b) => (a.currency === BASE_CURRENCY ? -1 : b.currency === BASE_CURRENCY ? 1 : 0));
    for (const b of others) {
      if (!short.gt(0)) break;
      const src = await this.currencies.requireActive(b.currency, tx);
      const shortSar = dec(short).div(dec(target.rate));
      // Enough of [b] to make up the shortfall, rounded up to its smallest unit.
      const want = shortSar.mul(dec(src.rate)).toDecimalPlaces(currencyDecimals(b.currency), Decimal.ROUND_UP);
      const take = Decimal.min(want, b.available);
      const takeSar = toBase(take, b.currency, src.rate);
      const gives = take.eq(want) ? short : takeSar.mul(dec(target.rate)).toDecimalPlaces(currencyDecimals(currency), Decimal.ROUND_DOWN);
      if (!gives.gt(0)) continue;
      await this.exchangeIn(tx, {
        customerId,
        from: b.currency,
        amount: take,
        fromSar: takeSar,
        to: currency,
        toAmount: gives,
        toSar: toBase(gives, currency, target.rate),
        description: `${label}: ${take.toFixed()} ${b.currency} → ${gives.toFixed()} ${currency}`,
        userId,
      });
      short = short.sub(gives);
    }
    if (short.gt(0)) throw new UnprocessableEntityException('رصيد المحفظة لا يكفي لهذا الطلب');
  }

  /**
   * Wallet credit the store gives away (loyalty points turned into money, ambassador rewards):
   *   Dr Sales discounts  —  Cr Customer accounts (the customer, SAR).
   */
  async reward(tx: Tx, p: { customerId: string; amountSar: Decimal; description: string; sourceId?: string; userId?: string | null }) {
    const amount = money(p.amountSar);
    if (!amount.gt(0)) return null;
    const lines = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId: p.customerId, currency: BASE_CURRENCY, amount: amount.neg(), sar: amount.neg(), description: p.description });
    return this.ledger.post(tx, {
      description: p.description,
      sourceType: 'LOYALTY_REWARD',
      sourceId: p.sourceId,
      userId: p.userId ?? undefined,
      lines: [...lines, { account: 'SALES_DISCOUNTS', debit: amount }],
    });
  }

  /**
   * Moves wallet money from one customer to another, same currency. Both sides sit on the
   * customer-accounts ledger account, tagged with each customer; the receiver takes the
   * sender's book value, so no exchange difference arises.
   */
  async transfer(
    tx: Tx,
    p: { fromId: string; toId: string; currency: string; amount: Decimal; description: string; userId?: string; sourceType?: 'WALLET_GIFT' | 'GROUP_GIFT' },
  ) {
    const rate = await this.currencies.rate(p.currency, tx);
    const sar = toBase(p.amount, p.currency, rate);
    const out = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId: p.fromId, currency: p.currency, amount: p.amount, sar, description: p.description });
    const outSar = out.reduce((sum, l) => sum.add(dec(l.debit ?? 0)).sub(dec(l.credit ?? 0)), dec(0));
    const inn = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId: p.toId, currency: p.currency, amount: p.amount.neg(), sar: outSar.neg(), description: p.description });
    const diff = [...out, ...inn].reduce((sum, l) => sum.add(dec(l.debit ?? 0)).sub(dec(l.credit ?? 0)), dec(0));
    return this.ledger.post(tx, {
      description: p.description,
      sourceType: p.sourceType ?? 'WALLET_GIFT',
      sourceId: p.fromId,
      userId: p.userId,
      lines: [...out, ...inn, ...(diff.isZero() ? [] : [diff.gt(0) ? { account: 'FX_DIFFERENCE' as const, credit: diff } : { account: 'FX_DIFFERENCE' as const, debit: diff.neg() }])],
    });
  }
}

/** "رصيدك لدينا: …" / "المتبقي عليك: …" for the customer's balance in [currency] (+ = owes). */
function balanceLine(balance: Decimal, currency: string) {
  if (balance.isZero()) return `لا يوجد رصيد مستحق بعملة ${currency}.`;
  return balance.gt(0) ? `المتبقي عليك: ${formatIn(balance, currency)}` : `رصيدك لدينا: ${formatIn(balance.neg(), currency)}`;
}

const WALLET_NOTES: Record<string, string> = {
  OPENING_BALANCE: 'رصيد افتتاحي',
  WALLET_TOPUP: 'شحن رصيد',
  WALLET_GIFT: 'إهداء رصيد',
  LOYALTY_REWARD: 'مكافأة نقاط / سفير',
  GROUP_GIFT: 'هدية جماعية',
  CUSTOMER_PAYMENT: 'دفعة / مشتريات',
  SALES_RETURN: 'مرتجع',
};
const walletNote = (sourceType: string) => WALLET_NOTES[sourceType] ?? 'حركة';

const STATEMENT_LABELS: Record<string, string> = {
  SALES_ORDER: 'فاتورة مبيعات',
  CUSTOMER_PAYMENT: 'دفعة / فاتورة',
  OPENING_BALANCE: 'رصيد افتتاحي',
  WALLET_TOPUP: 'شحن رصيد',
  WALLET_GIFT: 'إهداء رصيد',
  SALES_RETURN: 'مرتجع',
  LOYALTY_REWARD: 'مكافأة نقاط / سفير',
  GROUP_GIFT: 'هدية جماعية',
  GIFT_CARD: 'بطاقة هدية',
};
