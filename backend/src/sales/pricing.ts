import { CouponType } from '../generated/prisma/client';
import { Decimal, DecimalLike, dec, money, sum, ZERO } from '../common/utils/money';

export interface PricingLine {
  variantId: string;
  quantity: number;
  /** Shelf price per unit (tax-inclusive when pricesIncludeTax). */
  unitPrice: DecimalLike;
  /** Percentage, e.g. 14 for 14% VAT. */
  taxRate: DecimalLike;
}

export interface PricingCoupon {
  type: CouponType;
  value: DecimalLike;
  maxDiscount?: DecimalLike | null;
}

export interface PricingInput {
  lines: PricingLine[];
  coupon?: PricingCoupon | null;
  shippingFee: DecimalLike;
  pricesIncludeTax: boolean;
  /** Quantity-offer discounts per line (same order as [lines]), taken before the coupon. */
  lineDiscounts?: DecimalLike[];
}

export interface PricedLine extends PricingLine {
  unitPrice: Decimal;
  taxRate: Decimal;
  discount: Decimal;
  taxAmount: Decimal;
  /** What the customer pays for the line (after discount, including tax). */
  lineTotal: Decimal;
}

export interface PricingResult {
  lines: PricedLine[];
  subtotal: Decimal;
  discountTotal: Decimal;
  shippingFee: Decimal;
  taxTotal: Decimal;
  grandTotal: Decimal;
}

/**
 * Prices an order. Pure and deterministic so it can be unit-tested and used for
 * both the cart preview and checkout.
 *
 * - subtotal       = Σ unitPrice × qty (shelf prices)
 * - offers         = [PricingInput.lineDiscounts] (e.g. "قطعة + قطعة مجاناً") on their own lines
 * - discountTotal  = offers + coupon discount; the coupon is worked out on what is left after the
 *                    offers and allocated to lines pro-rata (remainder on the last line)
 * - tax per line   = on (gross − discount); extracted when prices include tax, added otherwise
 * - grandTotal     = Σ lineTotal + shipping
 */
export function priceOrder(input: PricingInput): PricingResult {
  const gross = input.lines.map((l) => money(dec(l.unitPrice).mul(l.quantity)));
  const subtotal = sum(gross);
  const offers = input.lines.map((_, i) => Decimal.min(money(input.lineDiscounts?.[i] ?? 0), gross[i]));
  const afterOffers = gross.map((g, i) => g.sub(offers[i]));
  const couponBase = sum(afterOffers);
  let shippingFee = money(input.shippingFee);
  let couponTotal = ZERO;

  const c = input.coupon;
  if (c?.type === CouponType.FREE_SHIPPING) {
    shippingFee = ZERO;
  } else if (c?.type === CouponType.PERCENTAGE) {
    couponTotal = money(couponBase.mul(dec(c.value)).div(100));
  } else if (c?.type === CouponType.FIXED_AMOUNT) {
    couponTotal = money(c.value);
  }
  if (c?.maxDiscount != null && couponTotal.greaterThan(dec(c.maxDiscount))) couponTotal = money(c.maxDiscount);
  if (couponTotal.greaterThan(couponBase)) couponTotal = couponBase;
  const discountTotal = couponTotal.add(sum(offers));

  let allocated = ZERO;
  // The rounding remainder of the coupon goes on the last line that still has something to pay.
  const lastPaying = afterOffers.reduce((last, a, i) => (a.gt(0) ? i : last), input.lines.length - 1);
  const lines = input.lines.map((l, i): PricedLine => {
    const isLast = i === lastPaying;
    const share = couponBase.isZero() || (!isLast && afterOffers[i].isZero())
      ? ZERO
      : isLast
        ? couponTotal.sub(allocated)
        : money(couponTotal.mul(afterOffers[i]).div(couponBase));
    allocated = allocated.add(share);
    const discount = share.add(offers[i]);

    const rate = dec(l.taxRate);
    const base = gross[i].sub(discount);
    const taxAmount = input.pricesIncludeTax
      ? money(base.mul(rate).div(rate.add(100)))
      : money(base.mul(rate).div(100));
    const lineTotal = input.pricesIncludeTax ? base : base.add(taxAmount);

    return { ...l, unitPrice: money(l.unitPrice), taxRate: rate, discount, taxAmount, lineTotal };
  });

  const taxTotal = sum(lines.map((l) => l.taxAmount));
  const grandTotal = sum(lines.map((l) => l.lineTotal)).add(shippingFee);
  return { lines, subtotal, discountTotal, shippingFee, taxTotal, grandTotal };
}

/**
 * Revenue recognition lines for a priced order. Balanced by construction:
 *   Dr AR (grandTotal) + Dr Sales Discounts (discountTotal)
 *   = Cr Sales (Σ lineTotal − tax + discount) + Cr VAT (tax) + Cr Shipping income
 */
export function revenueSplit(p: Pick<PricingResult, 'discountTotal' | 'shippingFee' | 'taxTotal' | 'grandTotal'>) {
  const sales = p.grandTotal.sub(p.shippingFee).sub(p.taxTotal).add(p.discountTotal);
  return { receivable: p.grandTotal, discounts: p.discountTotal, sales, tax: p.taxTotal, shipping: p.shippingFee };
}
