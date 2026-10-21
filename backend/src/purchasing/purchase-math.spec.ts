import { landedUnitCost, purchaseTotals, receivedValue } from './purchase-math';

describe('purchase math', () => {
  const lines = [
    { quantity: 3, unitCost: '33.33', taxRate: '14' },
    { quantity: 7, unitCost: '12.10', taxRate: '14' },
  ];

  it('computes totals with VAT and shipping on top', () => {
    const t = purchaseTotals(lines, '25');
    expect(t.subtotal.toFixed(2)).toBe('184.69'); // 99.99 + 84.70
    expect(t.taxTotal.toFixed(2)).toBe('25.86'); // 14.00 + 11.86
    expect(t.grandTotal.toFixed(2)).toBe('235.55');
  });

  it('books exactly the grand total across partial receipts', () => {
    const t = purchaseTotals(lines, '25');
    const steps = [
      [1, 2],
      [1, 4],
      [3, 7],
    ];
    let previous = receivedValue(lines, [0, 0], t.subtotal, t.shippingCost);
    let posted = 0;
    for (const s of steps) {
      const now = receivedValue(lines, s, t.subtotal, t.shippingCost);
      posted += Number(now.total.sub(previous.total));
      previous = now;
    }
    expect(posted.toFixed(2)).toBe(t.grandTotal.toFixed(2));
    expect(previous.inventory.add(previous.tax).toFixed(2)).toBe(t.grandTotal.toFixed(2));
  });

  it('spreads shipping into the landed unit cost', () => {
    expect(landedUnitCost('100', '1000', '50').toFixed(4)).toBe('105.0000');
    expect(landedUnitCost('100', '0', '50').toFixed(4)).toBe('100.0000');
  });
});
