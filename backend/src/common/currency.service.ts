import { ConflictException, Injectable, NotFoundException, OnModuleInit, UnprocessableEntityException } from '@nestjs/common';
import { Currency, Prisma } from '../generated/prisma/client';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { AuditService } from './audit.service';
import { Decimal, DecimalLike, dec, money } from './utils/money';

/** The base currency: prices, costs, payments and every journal line are stored in it. */
export const BASE_CURRENCY = 'SAR';

/**
 * Display rules of the known currencies, refreshed from the database at start-up and
 * whenever a currency is saved. Pure helpers below read it, so they stay synchronous.
 */
const REGISTRY = new Map<string, { symbol: string; decimals: number }>([
  ['SAR', { symbol: 'ر.س', decimals: 2 }],
  ['YER', { symbol: 'ر.ي', decimals: 0 }],
]);

export const currencyDecimals = (code: string) => REGISTRY.get(code)?.decimals ?? 2;
export const currencySymbol = (code: string) => REGISTRY.get(code)?.symbol ?? code;

/** Rounds an amount held or handed over in [currency] to its smallest unit. */
export const inCurrency = (amount: DecimalLike, currency: string) =>
  dec(amount).toDecimalPlaces(currencyDecimals(currency), Decimal.ROUND_HALF_UP);

/**
 * Converts a base (SAR) amount for display or collection in [currency] at [rate]
 * (units per 1 SAR), rounded to the currency's smallest unit (whole riyals for YER).
 * Every converted figure (line, total, balance) is rounded on its own with this rule,
 * so apps and invoices always show the same numbers.
 */
export function toCurrency(amountSar: DecimalLike, currency: string, rate: DecimalLike): Decimal {
  if (currency === BASE_CURRENCY) return money(amountSar);
  return inCurrency(dec(amountSar).mul(dec(rate)), currency);
}

/** The SAR value of [amount] handed over in [currency] at [rate]. */
export function toBase(amount: DecimalLike, currency: string, rate: DecimalLike): Decimal {
  if (currency === BASE_CURRENCY) return money(amount);
  return money(dec(amount).div(dec(rate)));
}

export function formatAmount(amountSar: DecimalLike, currency: string = BASE_CURRENCY, rate: DecimalLike = 1) {
  return formatIn(toCurrency(amountSar, currency, rate), currency);
}

/** An amount already in [currency]: "14000 ر.ي". */
export function formatIn(amount: DecimalLike, currency: string) {
  return `${inCurrency(amount, currency).toFixed(currencyDecimals(currency))} ${currencySymbol(currency)}`;
}

export interface CurrencyInput {
  code?: string;
  nameAr?: string;
  nameEn?: string;
  symbol?: string;
  decimals?: number;
  isActive?: boolean;
  showInCustomerApp?: boolean;
  sortOrder?: number;
  /** Picture of a note or coin; null clears it. */
  imageUrl?: string | null;
}

@Injectable()
export class CurrencyService implements OnModuleInit {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  async onModuleInit() {
    await this.refreshRegistry();
  }

  private async refreshRegistry() {
    const rows = await this.prisma.currency.findMany({ select: { code: true, symbol: true, decimals: true } });
    for (const r of rows) REGISTRY.set(r.code, { symbol: r.symbol, decimals: r.decimals });
  }

  /** Current rate of [currency] (1 for the base currency). */
  async rate(currency: string, tx: Tx | PrismaService = this.prisma): Promise<Decimal> {
    if (currency === BASE_CURRENCY) return dec(1);
    const row = await tx.exchangeRate.findFirst({ where: { currency }, orderBy: { effectiveAt: 'desc' } });
    if (!row) throw new UnprocessableEntityException(`No exchange rate is set for ${currency}`);
    return dec(row.rate);
  }

  /** An active currency (optionally one offered in the customer app), with its current rate. */
  async requireActive(code: string, tx: Tx | PrismaService = this.prisma, opts: { customerApp?: boolean } = {}) {
    const row = await tx.currency.findUnique({ where: { code } });
    if (!row || !row.isActive || (opts.customerApp && !row.showInCustomerApp && !row.isBase)) {
      throw new UnprocessableEntityException(`Currency ${code} is not available`);
    }
    return { ...row, rate: await this.rate(code, tx) };
  }

  /**
   * Currencies with their current rates. The customer app gets only those offered to
   * shoppers; staff get every active one (or all of them for the settings screen).
   */
  async overview(scope: 'customer' | 'staff' | 'all' = 'customer') {
    const where: Prisma.CurrencyWhereInput =
      scope === 'all' ? {} : scope === 'staff' ? { isActive: true } : { isActive: true, OR: [{ showInCustomerApp: true }, { isBase: true }] };
    const rows = await this.prisma.currency.findMany({ where, orderBy: [{ sortOrder: 'asc' }, { code: 'asc' }] });
    const currencies = await Promise.all(rows.map((c) => this.present(c)));
    return {
      base: BASE_CURRENCY,
      // A currency without a rate cannot be sold in yet; settings still show it.
      currencies: scope === 'all' ? currencies : currencies.filter((c) => c.rate !== null),
    };
  }

  private async present(c: Currency) {
    const latest = c.isBase
      ? null
      : await this.prisma.exchangeRate.findFirst({ where: { currency: c.code }, orderBy: { effectiveAt: 'desc' } });
    return {
      code: c.code,
      nameAr: c.nameAr,
      nameEn: c.nameEn,
      symbol: c.symbol,
      decimals: c.decimals,
      isBase: c.isBase,
      isActive: c.isActive,
      showInCustomerApp: c.showInCustomerApp,
      sortOrder: c.sortOrder,
      imageUrl: c.imageUrl,
      rate: c.isBase ? '1' : latest ? dec(latest.rate).toString() : null,
      updatedAt: latest?.effectiveAt ?? null,
    };
  }

  async history(currency: string, take = 30) {
    return this.prisma.exchangeRate.findMany({
      where: { currency },
      orderBy: { effectiveAt: 'desc' },
      take,
      select: { id: true, currency: true, rate: true, note: true, effectiveAt: true, createdBy: { select: { id: true, name: true } } },
    });
  }

  async create(dto: CurrencyInput & { code: string; rate?: string }, userId: string) {
    const code = dto.code.toUpperCase();
    if (await this.prisma.currency.findUnique({ where: { code } })) throw new ConflictException(`Currency ${code} already exists`);
    const row = await this.prisma.currency.create({
      data: {
        code,
        nameAr: dto.nameAr!,
        nameEn: dto.nameEn ?? dto.nameAr!,
        symbol: dto.symbol ?? code,
        decimals: dto.decimals ?? 2,
        isActive: dto.isActive ?? true,
        showInCustomerApp: dto.showInCustomerApp ?? false,
        sortOrder: dto.sortOrder ?? 10,
        imageUrl: dto.imageUrl ?? null,
      },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Currency', entityId: code, after: row });
    if (dto.rate) await this.setRate(code, dto.rate, 'Initial rate', userId);
    await this.refreshRegistry();
    return this.present(row);
  }

  async update(code: string, dto: CurrencyInput, userId: string) {
    const before = await this.prisma.currency.findUnique({ where: { code } });
    if (!before) throw new NotFoundException('Currency not found');
    if (before.isBase && (dto.isActive === false || (dto.decimals !== undefined && dto.decimals !== before.decimals))) {
      throw new UnprocessableEntityException('The base currency cannot be deactivated or change its decimals');
    }
    const row = await this.prisma.currency.update({
      where: { code },
      data: {
        nameAr: dto.nameAr,
        nameEn: dto.nameEn,
        symbol: dto.symbol,
        decimals: dto.decimals,
        isActive: dto.isActive,
        showInCustomerApp: dto.showInCustomerApp,
        sortOrder: dto.sortOrder,
        imageUrl: dto.imageUrl,
      },
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Currency', entityId: code, before, after: row });
    await this.refreshRegistry();
    return this.present(row);
  }

  /** New rate for future orders. Orders already placed keep the rate they were placed at. */
  async setRate(currency: string, rate: string, note: string | undefined, userId: string) {
    if (currency === BASE_CURRENCY) throw new UnprocessableEntityException('The base currency has no exchange rate');
    if (!(await this.prisma.currency.findUnique({ where: { code: currency } }))) throw new NotFoundException('Currency not found');
    const value = dec(rate);
    if (!value.gt(0)) throw new UnprocessableEntityException('Rate must be greater than zero');
    const before = await this.prisma.exchangeRate.findFirst({ where: { currency }, orderBy: { effectiveAt: 'desc' } });
    const row = await this.prisma.exchangeRate.create({ data: { currency, rate: value, note, createdById: userId } });
    await this.audit.log({
      userId,
      action: 'UPDATE',
      entityType: 'ExchangeRate',
      entityId: row.id,
      before: before ? { currency, rate: before.rate } : undefined,
      after: { currency, rate: value, note },
    });
    return row;
  }
}
