import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, formatIn, inCurrency, toBase } from '../common/currency.service';
import { SmsService } from '../sms/sms.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { Decimal, dec, money } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from './treasury.service';

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
      entries: recent.map((e) => ({ ...e, amount: dec(e.amount).neg(), note: walletNote(e.sourceType) })),
    };
  }

  /**
   * Money the customer brings in, into [box] (a drawer, safe or bank of the same currency):
   * pays off what they owe, and anything beyond becomes wallet credit. The box takes the
   * account's book value, so no exchange difference arises until the money is exchanged.
   */
  async credit(
    tx: Tx,
    p: { customerId: string; currency: string; amount: Decimal; boxId: string; sourceType: 'CUSTOMER_PAYMENT' | 'WALLET_TOPUP'; sourceId?: string; description: string; userId: string },
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
    dto: { currency: string; amount: string; cashBoxId?: string; note?: string; sendSms?: boolean },
    user: { id: string; permissions: string[] },
  ) {
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, name: true, phone: true } });
    if (!customer) throw new NotFoundException('Customer not found');
    const currency = dto.currency ?? BASE_CURRENCY;
    await this.currencies.requireActive(currency);
    await this.prisma.$transaction(async (tx) => {
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
      // Giving up [amount] of credit = owing that much more in that currency.
      const out = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId, currency: from.code, amount, sar: fromSar, description });
      const inn = await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId, currency: to.code, amount: toAmount.neg(), sar: toSar.neg(), description });
      const fx = fromSar.sub(toSar);
      const entry = await this.ledger.post(tx, {
        description,
        sourceType: 'CUSTOMER_PAYMENT',
        sourceId: customerId,
        userId,
        lines: [...out, ...inn, ...(fx.isZero() ? [] : [fx.gt(0) ? { account: 'FX_DIFFERENCE' as const, credit: fx } : { account: 'FX_DIFFERENCE' as const, debit: fx.neg() }])],
      });
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
}

/** "رصيدك لدينا: …" / "المتبقي عليك: …" for the customer's balance in [currency] (+ = owes). */
function balanceLine(balance: Decimal, currency: string) {
  if (balance.isZero()) return `لا يوجد رصيد مستحق بعملة ${currency}.`;
  return balance.gt(0) ? `المتبقي عليك: ${formatIn(balance, currency)}` : `رصيدك لدينا: ${formatIn(balance.neg(), currency)}`;
}

const WALLET_NOTES: Record<string, string> = {
  OPENING_BALANCE: 'رصيد افتتاحي',
  WALLET_TOPUP: 'شحن رصيد',
  CUSTOMER_PAYMENT: 'دفعة / مشتريات',
  SALES_RETURN: 'مرتجع',
};
const walletNote = (sourceType: string) => WALLET_NOTES[sourceType] ?? 'حركة';
