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
