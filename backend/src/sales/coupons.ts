import { Coupon } from '../generated/prisma/client';
import { dec } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';

/**
 * How many coupon codes can be used right now (the apps show the code field only then). With
 * [customerId] personal coupons count only for their owner; without one (the till) they all count.
 */
export async function liveCoupons(db: Tx | PrismaService, customerId?: string): Promise<number> {
  const rows = await db.$queryRaw<{ n: number }[]>`
    SELECT COUNT(*)::int AS n FROM coupons
    WHERE is_active AND starts_at <= now() AND (ends_at IS NULL OR ends_at > now())
      AND (usage_limit IS NULL OR used_count < usage_limit)
      AND (${customerId ?? null}::uuid IS NULL OR customer_id IS NULL OR customer_id = ${customerId ?? null}::uuid)`;
  return rows[0]?.n ?? 0;
}

/**
 * The coupon when [code] can be applied to a basket of [subtotal] SAR, otherwise why not.
 * [customerId] null (a walk-in at the till) skips the once-per-customer rule.
 */
export async function checkCoupon(tx: Tx | PrismaService, code: string, customerId: string | null, subtotal: string): Promise<Coupon | string> {
  const coupon = (await tx.coupon.findUnique({ where: { code } })) ?? (await tx.coupon.findUnique({ where: { code: code.trim().toUpperCase() } }));
  const now = new Date();
  if (!coupon || !coupon.isActive) return 'Coupon code is not valid';
  // A personal coupon works for its owner only.
  if (coupon.customerId && coupon.customerId !== customerId) return 'Coupon code is not valid';
  if (coupon.startsAt > now || (coupon.endsAt && coupon.endsAt < now)) return 'Coupon has expired';
  if (coupon.usageLimit !== null && coupon.usedCount >= coupon.usageLimit) return 'Coupon usage limit reached';
  if (coupon.minOrderAmount && dec(subtotal).lessThan(coupon.minOrderAmount)) {
    return `Minimum order for this coupon is ${coupon.minOrderAmount}`;
  }
  if (customerId && coupon.perCustomerLimit !== null) {
    const used = await tx.couponUsage.count({ where: { couponId: coupon.id, customerId } });
    if (used >= coupon.perCustomerLimit) return 'You have already used this coupon';
  }
  return coupon;
}
