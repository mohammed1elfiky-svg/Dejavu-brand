import { importLandedCosts, landedUnitCost, purchaseTotals, receivedValue } from './purchase-math';

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

describe('importLandedCosts', () => {
  const base = {
    foreignPerUsd: '7.2',
    sarPerUsd: '3.75',
    commissionPercent: '5',
    domesticShipping: '360',
    totalCbm: '2',
    cbmRateUsd: '200',
  };

  it('adds commission, inland shipping and CBM freight to the converted price', () => {
    const r = importLandedCosts({
      ...base,
      lines: [
        { quantity: 100, priceForeign: '10', cbm: '0.5' },
        { quantity: 50, priceForeign: '20' },
      ],
    });
    const [a, b] = r.lines;
    expect(a.goods.toString()).toBe('520.83');
    expect(a.domestic.toString()).toBe('93.75');
    expect(a.commission.toString()).toBe('26.04');
    expect(a.freight.toString()).toBe('375');
    expect(a.unitCost.toString()).toBe('10.1562');
    // The unmeasured line takes the rest of the shipment volume.
    expect(b.cbm.toString()).toBe('1.5');
    expect(b.freight.toString()).toBe('1125');
    expect(b.unitCost.toString()).toBe('35.3124');
    expect(r.totals.total.toString()).toBe('2781.24');
    expect(r.totals.total.equals(a.total.add(b.total))).toBe(true);
  });

  it('shares the volume by value when no line was measured, and copes with free items', () => {
    const r = importLandedCosts({
      ...base,
      domesticShipping: 0,
      commissionPercent: 0,
      lines: [
        { quantity: 10, priceForeign: '30' },
        { quantity: 10, priceForeign: '10' },
        { quantity: 0, priceForeign: '10' },
      ],
    });
    expect(r.lines.map((l) => l.cbm.toString())).toEqual(['1.5', '0.5', '0']);
    expect(r.lines[2].unitCost.toString()).toBe('0');
  });
});
