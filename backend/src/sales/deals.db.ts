import { UnprocessableEntityException } from '@nestjs/common';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { ActiveDeal, AppliedDeal } from './deals';

/**
 * Offers running now that cover any of [productIds], for the till ('pos') or the app ('online').
 * Each comes with all its products (a set needs to know every piece of it).
 */
export async function activeDeals(
  db: Tx | PrismaService,
  productIds: string[],
  channel: 'pos' | 'online',
  at = new Date(),
  /** Limited offers: a customer who already took one keeps it even once the customer limit is reached. */
  customerId?: string | null,
): Promise<ActiveDeal[]> {
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
  // First come, first served: an offer that sold its pieces, or reached its customers, stops.
  const limited = rows.filter((d) => d.maxCustomers !== null && d.usedCustomers >= d.maxCustomers).map((d) => d.id);
  const mine = customerId && limited.length
    ? new Set((await db.dealUsage.findMany({ where: { dealId: { in: limited }, customerId }, select: { dealId: true } })).map((u) => u.dealId))
    : new Set<string>();
  return rows
    .filter((d) => d.maxPieces === null || d.usedPieces < d.maxPieces)
    .filter((d) => d.maxCustomers === null || d.usedCustomers < d.maxCustomers || mine.has(d.id))
    .map((d) => ({
    id: d.id,
    name: d.name,
    type: d.type,
    buyQty: d.buyQty,
    freeQty: d.freeQty,
    bundlePrice: d.bundlePrice,
    productIds: d.products.map((p) => p.productId),
  }));
}

/**
 * Counts what a sale took from limited offers: pieces (a group of X + Y, or a whole set) and,
 * the first time, the customer. Throws when the offer ran out meanwhile, so the buyer sees
 * the price without it. A walk-in at the till counts as a new customer each time.
 */
export async function recordDealUsage(tx: Tx, applied: AppliedDeal[], customerId: string | null, orderId: string) {
  for (const a of applied) {
    const deal = await tx.deal.findUnique({ where: { id: a.dealId }, include: { products: { select: { productId: true } } } });
    if (!deal) continue;
    const pieces = deal.type === 'BUNDLE' ? a.times * deal.products.length : a.times * (deal.buyQty + deal.freeQty);
    const known = customerId ? await tx.dealUsage.count({ where: { dealId: deal.id, customerId } }) : 0;
    const claimed = await tx.$executeRaw`
      UPDATE deals SET used_pieces = used_pieces + ${pieces}, used_customers = used_customers + ${known ? 0 : 1}
      WHERE id = ${deal.id}::uuid
        AND (max_pieces IS NULL OR used_pieces < max_pieces)
        AND (max_customers IS NULL OR used_customers < max_customers OR ${known > 0})`;
    if (!claimed) throw new UnprocessableEntityException(`انتهى العرض «${deal.name}»، حدّث السلة`);
    await tx.dealUsage.create({ data: { dealId: deal.id, customerId, orderId, pieces } });
  }
}

/** A cancelled or deleted sale gives its pieces (and, if it was their only one, the customer) back. */
export async function releaseDealUsage(tx: Tx, orderId: string) {
  const rows = await tx.dealUsage.findMany({ where: { orderId } });
  for (const u of rows) {
    await tx.dealUsage.delete({ where: { id: u.id } });
    const still = u.customerId ? await tx.dealUsage.count({ where: { dealId: u.dealId, customerId: u.customerId } }) : 0;
    await tx.deal.update({
      where: { id: u.dealId },
      data: { usedPieces: { decrement: u.pieces }, usedCustomers: { decrement: still ? 0 : 1 } },
    });
  }
}
