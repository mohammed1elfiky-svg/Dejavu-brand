import { dec } from '../common/utils/money';
import { applyDeals, dealLabel } from './deals';
import { priceOrder } from './pricing';

const bogo = { id: 'd1', name: 'قطعة وقطعة', type: 'BUY_X_GET_Y' as const, buyQty: 1, freeQty: 1, bundlePrice: null, productIds: ['a', 'b'] };

describe('applyDeals', () => {
  it('gives the cheapest piece of each group free', () => {
    const r = applyDeals(
      [
        { productId: 'a', quantity: 1, unitPrice: '100' },
        { productId: 'b', quantity: 1, unitPrice: '60' },
        { productId: 'c', quantity: 1, unitPrice: '10' },
      ],
      [bogo],
    );
    expect(r.lineDiscounts.map((d) => d.toFixed())).toEqual(['0', '60', '0']);
    expect(r.applied).toEqual([{ dealId: 'd1', name: 'قطعة وقطعة', times: 1, discount: dec(60) }]);
  });

  it('buy two get one: only full groups count', () => {
    const deal = { ...bogo, buyQty: 2, freeQty: 1 };
    const r = applyDeals([{ productId: 'a', quantity: 5, unitPrice: '50' }], [deal]);
    expect(r.total.toFixed()).toBe('50'); // one group of 3, two pieces left over
    expect(applyDeals([{ productId: 'a', quantity: 6, unitPrice: '50' }], [deal]).total.toFixed()).toBe('100');
  });

  it('prices a set and shares the saving across its pieces', () => {
    const set = { id: 's', name: 'طقم', type: 'BUNDLE' as const, buyQty: 1, freeQty: 1, bundlePrice: '200', productIds: ['shoe', 'bag'] };
    const r = applyDeals(
      [
        { productId: 'shoe', quantity: 2, unitPrice: '150' },
        { productId: 'bag', quantity: 1, unitPrice: '100' },
      ],
      [set, { ...bogo, productIds: ['shoe'] }],
    );
    // One set (150 + 100 → 200: 50 off, 30 / 20); the second shoe is alone, so no free piece.
    expect(r.lineDiscounts.map((d) => d.toFixed())).toEqual(['30', '20']);
    expect(r.applied.map((a) => a.dealId)).toEqual(['s']);
  });

  it('takes offers before the coupon and keeps the totals consistent', () => {
    const lines = [
      { variantId: 'v1', quantity: 1, unitPrice: '100', taxRate: 0 },
      { variantId: 'v2', quantity: 1, unitPrice: '60', taxRate: 0 },
    ];
    const p = priceOrder({ lines, lineDiscounts: ['0', '60'], coupon: { type: 'PERCENTAGE', value: 10 }, shippingFee: 0, pricesIncludeTax: true });
    expect(p.discountTotal.toFixed()).toBe('70'); // 60 free + 10% of the 100 left
    expect(p.grandTotal.toFixed()).toBe('90');
    expect(p.lines[1].lineTotal.toFixed()).toBe('0');
  });

  it('labels offers', () => {
    expect(dealLabel(bogo)).toBe('قطعة + قطعة مجاناً');
    expect(dealLabel({ ...bogo, buyQty: 2 })).toBe('اشترِ 2 واحصل على قطعة مجاناً');
  });
});
