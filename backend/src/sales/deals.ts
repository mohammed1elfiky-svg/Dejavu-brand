import { Decimal, DecimalLike, dec, money, sum, ZERO } from '../common/utils/money';

/** A running quantity offer and the products it covers. */
export interface ActiveDeal {
  id: string;
  name: string;
  type: 'BUY_X_GET_Y' | 'BUNDLE';
  buyQty: number;
  freeQty: number;
  bundlePrice: DecimalLike | null;
  productIds: string[];
}

export interface DealLine {
  productId: string;
  quantity: number;
  /** Price per piece the buyer pays before offers (promotions already applied). */
  unitPrice: DecimalLike;
}

export interface AppliedDeal {
  dealId: string;
  name: string;
  /** How many times the offer applied (groups / sets). */
  times: number;
  discount: Decimal;
}

export interface DealResult {
  /** Discount per input line, same order. */
  lineDiscounts: Decimal[];
  applied: AppliedDeal[];
  total: Decimal;
}

/**
 * Applies quantity offers to a basket. Each piece counts in one offer only. Sets (BUNDLE)
 * go first, then "buy X get Y" offers, in the order given.
 *
 * - BUY_X_GET_Y: the covered pieces are sorted from dearest to cheapest and taken in groups
 *   of X + Y; in every full group the Y cheapest pieces are free.
 * - BUNDLE: every product of the set must be in the basket; each full set (one piece of
 *   each, the cheapest pieces first) costs [bundlePrice] instead of their sum.
 */
export function applyDeals(lines: DealLine[], deals: ActiveDeal[]): DealResult {
  // Every piece of the basket, so a piece can be used by one offer only.
  const pieces = lines.flatMap((l, line) => Array.from({ length: l.quantity }, () => ({ line, productId: l.productId, price: money(l.unitPrice), used: false })));
  const lineDiscounts = lines.map(() => ZERO);
  const applied: AppliedDeal[] = [];
  const ordered = [...deals.filter((d) => d.type === 'BUNDLE'), ...deals.filter((d) => d.type === 'BUY_X_GET_Y')];

  for (const deal of ordered) {
    const covers = new Set(deal.productIds);
    if (deal.type === 'BUY_X_GET_Y') {
      const x = Math.max(1, deal.buyQty);
      const y = Math.max(1, deal.freeQty);
      const pool = pieces.filter((p) => !p.used && covers.has(p.productId)).sort((a, b) => b.price.cmp(a.price));
      const groups = Math.floor(pool.length / (x + y));
      if (!groups) continue;
      let discount = ZERO;
      for (let g = 0; g < groups; g++) {
        const group = pool.slice(g * (x + y), (g + 1) * (x + y));
        group.forEach((p) => (p.used = true));
        for (const free of group.slice(x)) {
          lineDiscounts[free.line] = lineDiscounts[free.line].add(free.price);
          discount = discount.add(free.price);
        }
      }
      applied.push({ dealId: deal.id, name: deal.name, times: groups, discount: money(discount) });
    } else {
      const price = deal.bundlePrice === null ? null : money(deal.bundlePrice);
      if (price === null || !deal.productIds.length) continue;
      let times = 0;
      let discount = ZERO;
      for (;;) {
        // The cheapest free piece of each product of the set.
        const set = deal.productIds.map((pid) =>
          pieces.filter((p) => !p.used && p.productId === pid).sort((a, b) => a.price.cmp(b.price))[0],
        );
        if (set.some((p) => !p)) break;
        const full = sum(set.map((p) => p!.price));
        const off = full.sub(price);
        if (!off.gt(0)) break;
        set.forEach((p) => (p!.used = true));
        // The saving is shared across the set in proportion to the prices (remainder on the last).
        let given = ZERO;
        set.forEach((p, i) => {
          const share = i === set.length - 1 ? off.sub(given) : money(off.mul(p!.price).div(full));
          given = given.add(share);
          lineDiscounts[p!.line] = lineDiscounts[p!.line].add(share);
        });
        discount = discount.add(off);
        times++;
      }
      if (times) applied.push({ dealId: deal.id, name: deal.name, times, discount: money(discount) });
    }
  }
  const capped = lineDiscounts.map((d, i) => Decimal.min(money(d), money(dec(lines[i].unitPrice).mul(lines[i].quantity))));
  return { lineDiscounts: capped, applied, total: sum(capped) };
}

/** "قطعة + قطعة مجاناً" / "اشترِ 2 والثالثة مجاناً" / "طقم بـ 250" for showing on a product. */
export function dealLabel(d: Pick<ActiveDeal, 'type' | 'buyQty' | 'freeQty' | 'bundlePrice'>): string {
  if (d.type === 'BUNDLE') return `طقم بسعر ${money(d.bundlePrice ?? 0).toFixed()}`;
  if (d.buyQty === 1 && d.freeQty === 1) return 'قطعة + قطعة مجاناً';
  const free = d.freeQty === 1 ? 'قطعة مجاناً' : `${d.freeQty} قطع مجاناً`;
  return `اشترِ ${d.buyQty} واحصل على ${free}`;
}
