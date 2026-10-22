import { Decimal, DecimalLike, dec, money, sum, ZERO } from '../common/utils/money';

export interface PurchaseLine {
  quantity: number;
  /** Supplier price per unit, excluding VAT. */
  unitCost: DecimalLike;
  /** Input VAT percentage. */
  taxRate: DecimalLike;
}

const lineNet = (qty: number, l: PurchaseLine) => money(dec(l.unitCost).mul(qty));
const lineTax = (qty: number, l: PurchaseLine) => money(dec(l.unitCost).mul(qty).mul(dec(l.taxRate)).div(100));

/** Totals of a purchase order. Supplier prices exclude VAT; shipping is added on top. */
export function purchaseTotals(lines: PurchaseLine[], shippingCost: DecimalLike) {
  const shipping = money(shippingCost);
  const nets = lines.map((l) => lineNet(l.quantity, l));
  const taxes = lines.map((l) => lineTax(l.quantity, l));
  const subtotal = sum(nets);
  const taxTotal = sum(taxes);
  return {
    lines: lines.map((_, i) => ({ net: nets[i], tax: taxes[i], lineTotal: nets[i].add(taxes[i]) })),
    subtotal,
    taxTotal,
    shippingCost: shipping,
    grandTotal: subtotal.add(taxTotal).add(shipping),
  };
}

/**
 * Value of the goods received so far: inventory (goods + their share of
 * shipping) and input VAT. Each receipt posts the difference between two of
 * these cumulative snapshots, so rounding never drifts and a fully received
 * order books exactly its grand total.
 */
export function receivedValue(
  lines: PurchaseLine[],
  received: number[],
  subtotal: DecimalLike,
  shippingCost: DecimalLike,
): { inventory: Decimal; tax: Decimal; total: Decimal } {
  const goods = sum(lines.map((l, i) => lineNet(received[i], l)));
  const tax = sum(lines.map((l, i) => lineTax(received[i], l)));
  const sub = dec(subtotal);
  const shipping = sub.isZero() ? ZERO : money(dec(shippingCost).mul(goods).div(sub));
  const inventory = goods.add(shipping);
  return { inventory, tax, total: inventory.add(tax) };
}

/** Unit cost that lands in inventory: supplier price plus its share of shipping. */
export function landedUnitCost(unitCost: DecimalLike, subtotal: DecimalLike, shippingCost: DecimalLike): Decimal {
  const sub = dec(subtotal);
  const factor = sub.isZero() ? dec(1) : dec(1).add(dec(shippingCost).div(sub));
  return dec(unitCost).mul(factor).toDecimalPlaces(4);
}

// ------------------------------------------------------------- imports ---

export interface ImportLine {
  /** Pieces counted on arrival. */
  quantity: number;
  /** Supplier price per piece in the foreign currency (e.g. CNY). */
  priceForeign: DecimalLike;
  /** Volume of this line (m³), if it was measured on its own. */
  cbm?: DecimalLike | null;
}

export interface ImportCostInput {
  lines: ImportLine[];
  /** Foreign units per 1 USD (e.g. 7.2 CNY). */
  foreignPerUsd: DecimalLike;
  /** SAR per 1 USD (e.g. 3.75). */
  sarPerUsd: DecimalLike;
  /** Supplier commission, percent of the goods value. */
  commissionPercent: DecimalLike;
  /** Inland shipping in the origin country, foreign currency, shared by value. */
  domesticShipping: DecimalLike;
  /** Whole shipment volume; lines without their own CBM share what is left, by value. */
  totalCbm?: DecimalLike | null;
  /** Freight + customs per m³, USD. */
  cbmRateUsd: DecimalLike;
}

export interface ImportLineCost {
  goods: Decimal;
  domestic: Decimal;
  commission: Decimal;
  freight: Decimal;
  /** Everything this line cost, SAR. */
  total: Decimal;
  /** The line's CBM after sharing the shipment volume. */
  cbm: Decimal;
  /** Cost per piece, SAR (4 decimals). */
  unitCost: Decimal;
}

/**
 * Landed cost of an import shipment, in SAR:
 *   goods      = price × qty, converted foreign → USD → SAR
 *   domestic   = inland shipping, shared by goods value
 *   commission = supplier commission % of the goods
 *   freight    = CBM × freight & customs rate (USD → SAR); lines without their own CBM
 *                share the rest of the shipment volume by goods value
 * Each component is rounded per line, so the line totals add up exactly to what is booked.
 */
export function importLandedCosts(input: ImportCostInput): { lines: ImportLineCost[]; totals: Omit<ImportLineCost, 'unitCost' | 'cbm'> & { cbm: Decimal } } {
  const toSar = dec(input.sarPerUsd).div(dec(input.foreignPerUsd)); // SAR per foreign unit
  const valueForeign = input.lines.map((l) => dec(l.priceForeign).mul(l.quantity));
  const share = (i: number, among: number[]) => {
    const base = sum(among.map((j) => valueForeign[j]));
    if (base.isZero()) {
      const pieces = among.reduce((n, j) => n + input.lines[j].quantity, 0);
      return pieces ? dec(input.lines[i].quantity).div(pieces) : dec(1).div(among.length || 1);
    }
    return valueForeign[i].div(base);
  };
  const all = input.lines.map((_, i) => i);

  const measured = input.lines.map((l) => (l.cbm == null ? null : dec(l.cbm)));
  const measuredTotal = sum(measured.filter((c): c is Decimal => c !== null));
  const rest = Decimal.max(dec(input.totalCbm ?? measuredTotal).sub(measuredTotal), 0);
  const unmeasured = all.filter((i) => measured[i] === null);

  const lines = input.lines.map((l, i) => {
    const goods = money(valueForeign[i].mul(toSar));
    const domestic = money(dec(input.domesticShipping).mul(share(i, all)).mul(toSar));
    const commission = money(valueForeign[i].mul(dec(input.commissionPercent)).div(100).mul(toSar));
    const cbm = measured[i] ?? rest.mul(share(i, unmeasured));
    const freight = money(cbm.mul(dec(input.cbmRateUsd)).mul(dec(input.sarPerUsd)));
    const total = goods.add(domestic).add(commission).add(freight);
    const unitCost = l.quantity > 0 ? total.div(l.quantity).toDecimalPlaces(4) : ZERO;
    return { goods, domestic, commission, freight, total, cbm: cbm.toDecimalPlaces(4), unitCost };
  });
  return {
    lines,
    totals: {
      goods: sum(lines.map((l) => l.goods)),
      domestic: sum(lines.map((l) => l.domestic)),
      commission: sum(lines.map((l) => l.commission)),
      freight: sum(lines.map((l) => l.freight)),
      total: sum(lines.map((l) => l.total)),
      cbm: sum(lines.map((l) => l.cbm)),
    },
  };
}
