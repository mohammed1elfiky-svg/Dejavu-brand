import { BadRequestException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit, UnprocessableEntityException } from '@nestjs/common';
import { NotificationType, PreOrderStatus, Prisma } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { SequenceService } from '../common/sequence.service';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';
import { LayawayService } from './layaway.service';
import { incomingFor } from './preorders.db';

type User = { id: string; permissions: string[] };

const include = {
  customer: { select: { id: true, name: true, phone: true } },
  variant: {
    select: {
      id: true,
      sku: true,
      size: true,
      color: true,
      stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } },
      product: { select: { id: true, nameAr: true, slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } },
    },
  },
} satisfies Prisma.PreOrderInclude;

/**
 * Pre-orders: a customer claims pieces still on their way (an open purchase or import order).
 * When they arrive the customer is told; staff then put them aside (a layaway) or sell them.
 */
@Injectable()
export class PreOrdersService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('PreOrders');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly sequences: SequenceService,
    private readonly notifications: NotificationsService,
    private readonly layaways: LayawayService,
    private readonly audit: AuditService,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.checkArrivals().catch((e) => this.log.error(e)), 10 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  async create(customerId: string, dto: { variantId: string; quantity: number; note?: string }, userId?: string) {
    if (dto.quantity < 1 || dto.quantity > 20) throw new BadRequestException('Quantity must be 1-20');
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, code: true } });
    if (!customer || customer.code === 'CUS-WALKIN') throw new BadRequestException('Pick a named customer');
    const id = await this.prisma.$transaction(async (tx) => {
      // One at a time per variant, so two customers cannot both take the last incoming piece.
      await tx.$queryRaw`SELECT id FROM product_variants WHERE id = ${dto.variantId}::uuid FOR UPDATE`;
      const open = (await incomingFor(tx, [dto.variantId])).get(dto.variantId)?.open ?? 0;
      if (open < dto.quantity) {
        throw new UnprocessableEntityException(open > 0 ? `باقي ${open} فقط في الشحنة القادمة` : 'لا توجد قطع قادمة من هذا المقاس');
      }
      const p = await tx.preOrder.create({
        data: { number: await this.sequences.next(tx, 'PRE'), customerId, variantId: dto.variantId, quantity: dto.quantity, note: dto.note },
      });
      return p.id;
    });
    await this.audit.log({ userId: userId ?? null, action: 'CREATE', entityType: 'PreOrder', entityId: id, after: { customerId, ...dto } });
    return this.get(id);
  }

  async cancel(id: string, by: { customerId?: string; userId?: string }) {
    const p = await this.prisma.preOrder.findUnique({ where: { id } });
    if (!p || (by.customerId && p.customerId !== by.customerId)) throw new NotFoundException('Pre-order not found');
    if (p.status === PreOrderStatus.CONVERTED || p.status === PreOrderStatus.CANCELLED) throw new UnprocessableEntityException('This pre-order is already closed');
    await this.prisma.preOrder.update({ where: { id }, data: { status: PreOrderStatus.CANCELLED, closedAt: new Date() } });
    await this.audit.log({ userId: by.userId ?? null, action: 'UPDATE', entityType: 'PreOrder', entityId: id, after: { cancelled: true } });
    return this.get(id);
  }

  /** The pieces arrived: put them aside for the customer as a layaway (no deposit yet). */
  async convert(id: string, user: User) {
    const p = await this.prisma.preOrder.findUnique({ where: { id } });
    if (!p) throw new NotFoundException('Pre-order not found');
    if (p.status === PreOrderStatus.CONVERTED || p.status === PreOrderStatus.CANCELLED) throw new UnprocessableEntityException('This pre-order is already closed');
    const lay = await this.layaways.create({ customerId: p.customerId, items: [{ variantId: p.variantId, quantity: p.quantity }], note: `من الحجز المسبق ${p.number}` }, user);
    await this.prisma.preOrder.update({ where: { id }, data: { status: PreOrderStatus.CONVERTED, layawayId: lay.id, closedAt: new Date() } });
    return { preOrder: await this.get(id), layaway: lay };
  }

  async get(id: string) {
    const p = await this.prisma.preOrder.findUnique({ where: { id }, include });
    if (!p) throw new NotFoundException('Pre-order not found');
    return (await this.present([p]))[0];
  }

  async listForCustomer(customerId: string) {
    const rows = await this.prisma.preOrder.findMany({ where: { customerId, status: { not: PreOrderStatus.CANCELLED } }, include, orderBy: { createdAt: 'desc' }, take: 50 });
    return this.present(rows);
  }

  async list(q: { status?: PreOrderStatus; productId?: string }) {
    const rows = await this.prisma.preOrder.findMany({
      where: { status: q.status, variant: q.productId ? { productId: q.productId } : undefined },
      include,
      orderBy: { createdAt: 'asc' },
      take: 500,
    });
    return this.present(rows);
  }

  /** Pieces on their way per product (for the admin: what can be pre-ordered and what was). */
  async incoming() {
    const rows = await this.prisma.$queryRaw<{ variant_id: string }[]>`
      SELECT DISTINCT i.variant_id::text FROM purchase_order_items i
      JOIN purchase_orders po ON po.id = i.purchase_order_id AND po.status IN ('ORDERED', 'PARTIALLY_RECEIVED')
      WHERE i.quantity > i.received_qty`;
    const map = await incomingFor(this.prisma, rows.map((r) => r.variant_id));
    const variants = await this.prisma.productVariant.findMany({
      where: { id: { in: [...map.keys()] } },
      select: { id: true, sku: true, size: true, color: true, product: { select: { id: true, nameAr: true, slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } },
    });
    return variants
      .map((v) => ({ variantId: v.id, sku: v.sku, size: v.size, color: v.color, productId: v.product.id, name: v.product.nameAr, imageUrl: v.product.images[0]?.url ?? null, ...map.get(v.id)! }))
      .sort((a, b) => a.name.localeCompare(b.name, 'ar'));
  }

  /**
   * Tells waiting customers their pieces arrived, oldest pre-order first, as long as there is
   * stock for them. Returns how many were told.
   */
  async checkArrivals() {
    const waiting = await this.prisma.preOrder.findMany({ where: { status: PreOrderStatus.WAITING }, include, orderBy: { createdAt: 'asc' }, take: 1000 });
    const left = new Map<string, number>();
    let told = 0;
    for (const p of waiting) {
      if (!left.has(p.variantId)) left.set(p.variantId, p.variant.stockLevels.reduce((n, s) => n + Math.max(0, s.onHand - s.reserved), 0));
      const free = left.get(p.variantId)!;
      if (free < p.quantity) continue;
      left.set(p.variantId, free - p.quantity);
      const v = p.variant;
      const what = [v.product.nameAr, v.size && `مقاس ${v.size}`, v.color].filter(Boolean).join(' · ');
      const image = v.product.images[0]?.url;
      await this.prisma.$transaction(async (tx) => {
        await this.notifications.notifyCustomer(tx, p.customerId, {
          type: NotificationType.LOW_STOCK,
          title: 'وصلت قطعتك المحجوزة 🎉',
          body: `${what} وصلت. اطلبيها الآن أو مرّي على المحل قبل نفادها.`,
          data: { product: v.product.slug, ...(image ? { image } : {}) },
        });
        await tx.preOrder.update({ where: { id: p.id }, data: { status: PreOrderStatus.NOTIFIED, notifiedAt: new Date() } });
      });
      told++;
    }
    return told;
  }

  private async present(rows: Prisma.PreOrderGetPayload<{ include: typeof include }>[]) {
    const incoming = await incomingFor(this.prisma, [...new Set(rows.map((r) => r.variantId))]);
    return rows.map((r) => ({
      id: r.id,
      number: r.number,
      status: r.status,
      quantity: r.quantity,
      note: r.note,
      layawayId: r.layawayId,
      createdAt: r.createdAt,
      notifiedAt: r.notifiedAt,
      customer: r.customer,
      variantId: r.variantId,
      sku: r.variant.sku,
      size: r.variant.size,
      color: r.variant.color,
      productId: r.variant.product.id,
      name: r.variant.product.nameAr,
      slug: r.variant.product.slug,
      imageUrl: r.variant.product.images[0]?.url ?? null,
      inStock: r.variant.stockLevels.reduce((n, s) => n + Math.max(0, s.onHand - s.reserved), 0),
      expectedDate: incoming.get(r.variantId)?.expectedDate ?? null,
    }));
  }
}
