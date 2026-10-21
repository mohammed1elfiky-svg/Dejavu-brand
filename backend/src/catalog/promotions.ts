import { Decimal, DecimalLike, dec, money } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';

export interface ActivePromotion {
  promotionId: string;
  name: string;
  percent: Decimal;
  endsAt: Date | null;
}

/** The best running promotion of each product (highest percentage wins). */
export async function activePromotions(db: Tx | PrismaService, productIds: string[], at = new Date()) {
  const map = new Map<string, ActivePromotion>();
  if (!productIds.length) return map;
  const rows = await db.promotionProduct.findMany({
    where: {
      productId: { in: [...new Set(productIds)] },
      promotion: { isActive: true, startsAt: { lte: at }, OR: [{ endsAt: null }, { endsAt: { gt: at } }] },
    },
    select: { productId: true, promotion: { select: { id: true, name: true, percent: true, endsAt: true } } },
  });
  for (const r of rows) {
    const current = map.get(r.productId);
    if (!current || dec(r.promotion.percent).gt(current.percent)) {
      map.set(r.productId, { promotionId: r.promotion.id, name: r.promotion.name, percent: dec(r.promotion.percent), endsAt: r.promotion.endsAt });
    }
  }
  return map;
}

/** Shelf price after a percentage promotion, rounded to halalas. */
export function promotedPrice(price: DecimalLike, promo: ActivePromotion | undefined): Decimal {
  if (!promo) return money(price);
  return money(dec(price).mul(dec(100).sub(promo.percent)).div(100));
}
