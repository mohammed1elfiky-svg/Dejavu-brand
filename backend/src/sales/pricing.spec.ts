import { CouponType } from '../generated/prisma/client';
import { dec } from '../common/utils/money';
import { priceOrder, revenueSplit } from './pricing';

const line = (unitPrice: string, quantity: number, taxRate = '14', variantId = 'v') => ({
  variantId,
  unitPrice,
  quantity,
  taxRate,
});

describe('priceOrder', () => {
  it('extracts VAT from tax-inclusive prices', () => {
    const r = priceOrder({ lines: [line('114.00', 2)], shippingFee: '50', pricesIncludeTax: true });
    expect(r.subtotal.toFixed(2)).toBe('228.00');
    expect(r.taxTotal.toFixed(2)).toBe('28.00');
    expect(r.grandTotal.toFixed(2)).toBe('278.00');
    expect(r.lines[0].lineTotal.toFixed(2)).toBe('228.00');
  });

  it('adds VAT on top of tax-exclusive prices', () => {
    const r = priceOrder({ lines: [line('100.00', 1)], shippingFee: '0', pricesIncludeTax: false });
    expect(r.taxTotal.toFixed(2)).toBe('14.00');
    expect(r.grandTotal.toFixed(2)).toBe('114.00');
  });

  it('allocates a percentage discount across lines without losing cents', () => {
    const r = priceOrder({
      lines: [line('33.33', 1, '14', 'a'), line('33.33', 1, '14', 'b'), line('33.34', 1, '14', 'c')],
      coupon: { type: CouponType.PERCENTAGE, value: '10' },
      shippingFee: '0',
      pricesIncludeTax: true,
    });
    expect(r.discountTotal.toFixed(2)).toBe('10.00');
    const allocated = r.lines.reduce((s, l) => s.add(l.discount), dec(0));
    expect(allocated.toFixed(2)).toBe('10.00');
    expect(r.grandTotal.toFixed(2)).toBe('90.00');
  });

  it('caps discounts at maxDiscount and at the subtotal', () => {
    const capped = priceOrder({
      lines: [line('1000', 1)],
      coupon: { type: CouponType.PERCENTAGE, value: '50', maxDiscount: '100' },
      shippingFee: '0',
      pricesIncludeTax: true,
    });
    expect(capped.discountTotal.toFixed(2)).toBe('100.00');

    const fixed = priceOrder({
      lines: [line('80', 1)],
      coupon: { type: CouponType.FIXED_AMOUNT, value: '200' },
      shippingFee: '30',
      pricesIncludeTax: true,
    });
    expect(fixed.discountTotal.toFixed(2)).toBe('80.00');
    expect(fixed.grandTotal.toFixed(2)).toBe('30.00');
  });

  it('waives shipping for free-shipping coupons', () => {
    const r = priceOrder({
      lines: [line('100', 1)],
      coupon: { type: CouponType.FREE_SHIPPING, value: '0' },
      shippingFee: '60',
      pricesIncludeTax: true,
    });
    expect(r.shippingFee.toFixed(2)).toBe('0.00');
    expect(r.grandTotal.toFixed(2)).toBe('100.00');
  });

  it('produces a balanced revenue split', () => {
    for (const pricesIncludeTax of [true, false]) {
      const r = priceOrder({
        lines: [line('199.99', 3, '14', 'a'), line('49.50', 1, '0', 'b')],
        coupon: { type: CouponType.PERCENTAGE, value: '15' },
        shippingFee: '45',
        pricesIncludeTax,
      });
      const s = revenueSplit(r);
      const debits = s.receivable.add(s.discounts);
      const credits = s.sales.add(s.tax).add(s.shipping);
      expect(debits.toFixed(2)).toBe(credits.toFixed(2));
    }
  });
});
