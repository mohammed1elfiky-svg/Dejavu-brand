import { Injectable, UnprocessableEntityException } from '@nestjs/common';
import { Currency } from '../generated/prisma/client';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { AuditService } from './audit.service';
import { Decimal, DecimalLike, dec, money } from './utils/money';

/** The base currency: prices, costs, payments and every journal line are stored in it. */
export const BASE_CURRENCY = Currency.SAR;

export const CURRENCY_INFO: Record<Currency, { symbol: string; nameAr: string; decimals: number }> = {
  SAR: { symbol: 'ر.س', nameAr: 'ريال سعودي', decimals: 2 },
  YER: { symbol: 'ر.ي', nameAr: 'ريال يمني', decimals: 0 },
};

/**
 * Converts a base (SAR) amount for display or collection in [currency] at [rate]
 * (units per 1 SAR). YER has no usable sub-units, so it is rounded to whole riyals.
 * Every converted figure (line, total, balance) is rounded on its own with this rule,
 * so apps and invoices always show the same numbers.
 */
export function toCurrency(amountSar: DecimalLike, currency: Currency, rate: DecimalLike): Decimal {
  if (currency === BASE_CURRENCY) return money(amountSar);
  return dec(amountSar).mul(dec(rate)).toDecimalPlaces(CURRENCY_INFO[currency].decimals, Decimal.ROUND_HALF_UP);
}

/** The SAR value of [amount] handed over in [currency] at [rate]. */
export function toBase(amount: DecimalLike, currency: Currency, rate: DecimalLike): Decimal {
  if (currency === BASE_CURRENCY) return money(amount);
  return money(dec(amount).div(dec(rate)));
}

export function formatAmount(amountSar: DecimalLike, currency: Currency = BASE_CURRENCY, rate: DecimalLike = 1) {
  const v = toCurrency(amountSar, currency, rate);
  return `${v.toFixed(CURRENCY_INFO[currency].decimals)} ${CURRENCY_INFO[currency].symbol}`;
}

@Injectable()
export class CurrencyService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  /** Current rate of [currency] (1 for the base currency). */
  async rate(currency: Currency, tx: Tx | PrismaService = this.prisma): Promise<Decimal> {
    if (currency === BASE_CURRENCY) return dec(1);
    const row = await tx.exchangeRate.findFirst({ where: { currency }, orderBy: { effectiveAt: 'desc' } });
    if (!row) throw new UnprocessableEntityException(`No exchange rate is set for ${currency}`);
    return dec(row.rate);
  }

  /** Sales currencies with their current rates, for the apps. */
  async overview() {
    const currencies = await Promise.all(
      Object.values(Currency).map(async (code) => {
        const latest =
          code === BASE_CURRENCY
            ? null
            : await this.prisma.exchangeRate.findFirst({ where: { currency: code }, orderBy: { effectiveAt: 'desc' } });
        if (code !== BASE_CURRENCY && !latest) return null;
        return {
          code,
          ...CURRENCY_INFO[code],
          rate: latest ? dec(latest.rate).toString() : '1',
          updatedAt: latest?.effectiveAt ?? null,
        };
      }),
    );
    return { base: BASE_CURRENCY, currencies: currencies.filter((c) => c !== null) };
  }

  async history(currency: Currency, take = 30) {
    return this.prisma.exchangeRate.findMany({
      where: { currency },
      orderBy: { effectiveAt: 'desc' },
      take,
      select: { id: true, rate: true, note: true, effectiveAt: true, createdBy: { select: { id: true, name: true } } },
    });
  }

  /** New rate for future orders. Orders already placed keep the rate they were placed at. */
  async setRate(currency: Currency, rate: string, note: string | undefined, userId: string) {
    if (currency === BASE_CURRENCY) throw new UnprocessableEntityException('The base currency has no exchange rate');
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
