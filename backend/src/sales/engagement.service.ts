import { BadRequestException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { NotificationType, ProductStatus } from '../generated/prisma/client';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';

/** A cart left this long is worth a reminder; at most one reminder per change of the cart. */
const CART_IDLE_HOURS = 6;
/** No new reminder within this many days of the last one. */
const CART_REMIND_EVERY_DAYS = 3;

/**
 * Bringing shoppers back: a reminder for carts left with items in them, and a message
 * when a size/colour someone was waiting for is back in stock.
 */
@Injectable()
export class EngagementService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Engagement');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.run().catch((e) => this.log.error(e)), 10 * 60_000);
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  async run() {
    return { carts: await this.remindCarts(), backInStock: await this.notifyBackInStock() };
  }

  /** Reminds customers of carts they left with items in them. Returns how many were reminded. */
  async remindCarts(now = new Date()) {
    const idleSince = new Date(now.getTime() - CART_IDLE_HOURS * 3600_000);
    const quietSince = new Date(now.getTime() - CART_REMIND_EVERY_DAYS * 24 * 3600_000);
    // Last activity: the cart itself or the newest piece put in it.
    const carts = await this.prisma.$queryRaw<{ id: string; customer_id: string; items: number }[]>`
      SELECT c.id::text, c.customer_id::text, COUNT(i.id)::int AS items
      FROM carts c
      JOIN cart_items i ON i.cart_id = c.id
      JOIN customers cu ON cu.id = c.customer_id AND cu.status = 'ACTIVE'
      GROUP BY c.id
      HAVING GREATEST(c.updated_at, MAX(i.added_at)) < ${idleSince}
         AND (c.reminded_at IS NULL OR (c.reminded_at < ${quietSince} AND c.reminded_at < GREATEST(c.updated_at, MAX(i.added_at))))
      LIMIT 200`;
    let sent = 0;
    for (const c of carts) {
      await this.prisma.$transaction(async (tx) => {
        await this.notifications.notifyCustomer(tx, c.customer_id, {
          type: NotificationType.PROMOTION,
          title: 'منتجاتك ما زالت في السلة 🛍️',
          body: c.items === 1 ? 'القطعة التي اخترتها تنتظرك، أكمل طلبك قبل أن تنفد.' : `${c.items} قطع تنتظرك في السلة، أكمل طلبك قبل أن تنفد.`,
          data: { screen: 'cart' },
        });
        // Raw update so the cart's updatedAt stays as it was.
        await tx.$executeRaw`UPDATE carts SET reminded_at = ${now} WHERE id = ${c.id}::uuid`;
      });
      sent++;
    }
    return sent;
  }

  /** Tells waiting customers a size/colour is in stock again. Returns how many were told. */
  async notifyBackInStock() {
    const due = await this.prisma.$queryRaw<{ id: string; customer_id: string; variant_id: string }[]>`
      SELECT a.id::text, a.customer_id::text, a.variant_id::text
      FROM stock_alerts a
      JOIN v_variant_availability av ON av.variant_id = a.variant_id
      JOIN product_variants v ON v.id = a.variant_id
      JOIN products p ON p.id = v.product_id
      WHERE a.notified_at IS NULL AND av.available > 0 AND v.is_active AND p.status = 'ACTIVE'
      LIMIT 500`;
    for (const a of due) {
      const v = await this.prisma.productVariant.findUniqueOrThrow({
        where: { id: a.variant_id },
        select: { size: true, color: true, product: { select: { nameAr: true, slug: true } } },
      });
      const option = [v.size && `مقاس ${v.size}`, v.color].filter(Boolean).join(' · ');
      await this.prisma.$transaction(async (tx) => {
        await this.notifications.notifyCustomer(tx, a.customer_id, {
          type: NotificationType.PROMOTION,
          title: 'عاد للمخزون ✨',
          body: `${v.product.nameAr}${option ? ` (${option})` : ''} متوفر الآن، اطلبه قبل أن ينفد.`,
          data: { productSlug: v.product.slug, variantId: a.variant_id },
        });
        await tx.stockAlert.update({ where: { id: a.id }, data: { notifiedAt: new Date() } });
      });
    }
    return due.length;
  }

  // ---------------------------------------------------------- customer side ---

  /** "Tell me when it is back" for a size/colour that is out of stock. */
  async watch(customerId: string, variantId: string) {
    const v = await this.prisma.productVariant.findUnique({ where: { id: variantId }, select: { id: true, product: { select: { status: true } } } });
    if (!v || v.product.status !== ProductStatus.ACTIVE) throw new NotFoundException('Product is not available');
    const [av] = await this.prisma.$queryRaw<{ available: number }[]>`SELECT available::int FROM v_variant_availability WHERE variant_id = ${variantId}::uuid`;
    if ((av?.available ?? 0) > 0) throw new BadRequestException('This size is in stock now');
    await this.prisma.stockAlert.upsert({
      where: { customerId_variantId: { customerId, variantId } },
      update: { notifiedAt: null, createdAt: new Date() },
      create: { customerId, variantId },
    });
    return this.mine(customerId);
  }

  async unwatch(customerId: string, variantId: string) {
    await this.prisma.stockAlert.deleteMany({ where: { customerId, variantId } });
    return this.mine(customerId);
  }

  /** The sizes/colours the customer is waiting for (not yet back). */
  async mine(customerId: string) {
    const rows = await this.prisma.stockAlert.findMany({
      where: { customerId, notifiedAt: null },
      orderBy: { createdAt: 'desc' },
      select: { variantId: true, createdAt: true, variant: { select: { size: true, color: true, product: { select: { nameAr: true, slug: true } } } } },
    });
    return rows.map((r) => ({ variantId: r.variantId, createdAt: r.createdAt, size: r.variant.size, color: r.variant.color, productName: r.variant.product.nameAr, productSlug: r.variant.product.slug }));
  }
}
