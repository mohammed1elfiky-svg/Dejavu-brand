import { PrismaService, Tx } from '../prisma/prisma.service';
import { ActiveDeal } from './deals';

/**
 * Offers running now that cover any of [productIds], for the till ('pos') or the app ('online').
 * Each comes with all its products (a set needs to know every piece of it).
 */
export async function activeDeals(db: Tx | PrismaService, productIds: string[], channel: 'pos' | 'online', at = new Date()): Promise<ActiveDeal[]> {
  if (!productIds.length) return [];
  const rows = await db.deal.findMany({
    where: {
      isActive: true,
      startsAt: { lte: at },
      OR: [{ endsAt: null }, { endsAt: { gt: at } }],
      ...(channel === 'pos' ? { atPos: true } : { online: true }),
      products: { some: { productId: { in: [...new Set(productIds)] } } },
    },
    orderBy: { createdAt: 'asc' },
    include: { products: { select: { productId: true } } },
  });
  return rows.map((d) => ({
    id: d.id,
    name: d.name,
    type: d.type,
    buyQty: d.buyQty,
    freeQty: d.freeQty,
    bundlePrice: d.bundlePrice,
    productIds: d.products.map((p) => p.productId),
  }));
}
