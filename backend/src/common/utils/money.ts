import { Prisma } from '../../generated/prisma/client';

export const Decimal = Prisma.Decimal;
export type Decimal = Prisma.Decimal;

export type DecimalLike = Prisma.Decimal | number | string;

export const ZERO = new Decimal(0);

// Note: decimal.js isPositive()/isNegative() check the sign bit, so they are true
// for 0 and -0 respectively. Compare with gt(0) / lt(0) instead.

export const dec = (v: DecimalLike | null | undefined): Decimal => new Decimal(v ?? 0);

/** Rounds to currency precision (2 dp, half-up). */
export const money = (v: DecimalLike): Decimal => dec(v).toDecimalPlaces(2, Decimal.ROUND_HALF_UP);

export const sum = (values: DecimalLike[]): Decimal =>
  values.reduce<Decimal>((acc, v) => acc.add(dec(v)), ZERO);
