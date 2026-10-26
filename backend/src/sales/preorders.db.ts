import { PrismaService, Tx } from '../prisma/prisma.service';

export interface Incoming {
  /** Pieces on open purchase / import orders. */
  incoming: number;
  /** Already claimed by waiting pre-orders. */
  claimed: number;
  /** What a new pre-order may still take. */
  open: number;
  /** Earliest expected arrival of an order carrying it. */
  expectedDate: Date | null;
}

/** Pieces on their way (ordered, not received) per variant, less what waiting pre-orders took. */
export async function incomingFor(db: Tx | PrismaService, variantIds: string[]): Promise<Map<string, Incoming>> {
  const out = new Map<string, Incoming>();
  if (!variantIds.length) return out;
  const rows = await db.$queryRaw<{ variant_id: string; incoming: number; expected: Date | null }[]>`
    SELECT i.variant_id::text, SUM(GREATEST(i.quantity - i.received_qty, 0))::int AS incoming, MIN(po.expected_date) AS expected
    FROM purchase_order_items i
    JOIN purchase_orders po ON po.id = i.purchase_order_id AND po.status IN ('ORDERED', 'PARTIALLY_RECEIVED')
    WHERE i.variant_id = ANY(${variantIds}::uuid[])
    GROUP BY i.variant_id`;
  if (!rows.length) return out;
  const claimed = await db.preOrder.groupBy({
    by: ['variantId'],
    where: { variantId: { in: rows.map((r) => r.variant_id) }, status: 'WAITING' },
    _sum: { quantity: true },
  });
  for (const r of rows) {
    if (r.incoming <= 0) continue;
    const c = claimed.find((x) => x.variantId === r.variant_id)?._sum.quantity ?? 0;
    out.set(r.variant_id, { incoming: r.incoming, claimed: c, open: Math.max(0, r.incoming - c), expectedDate: r.expected });
  }
  return out;
}
