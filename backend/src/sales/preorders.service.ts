import { BadRequestException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit, UnprocessableEntityException } from '@nestjs/common';
import { NotificationType, PreOrderStatus, Prisma } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { SequenceService } from '../common/sequence.service';
import { dec, money } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';
import { LayawayService } from './layaway.service';
import { incomingFor } from './preorders.db';

type User = { id: string; permissions: string[] };

export interface PreOrderInput {
  variantId?: string;
  quantity: number;
  note?: string;
  /** A piece not in the catalogue yet (staff only). */
  itemName?: string;
  itemSize?: string;
  itemColor?: string;
  /** Agreed price per piece, SAR (staff only). */
  unitPrice?: string;
}

const include = {
  customer: { select: { id: true, name: true, phone: true } },
  variant: {
    select: {
      id: true,
      sku: true,
      size: true,
      color: true,
      stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } },
      product: { select: { id: true, nameAr: true, slug: true, status: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } },
    },
  },
} satisfies Prisma.PreOrderInclude;

type Row = Prisma.PreOrderGetPayload<{ include: typeof include }>;

const CLOSED: PreOrderStatus[] = [PreOrderStatus.CONVERTED, PreOrderStatus.CANCELLED];
const free = (r: Row) => r.variant?.stockLevels.reduce((n, s) => n + Math.max(0, s.onHand - s.reserved), 0) ?? 0;
const describe = (r: Row) =>
  [r.variant?.product.nameAr ?? r.itemName, (r.variant?.size ?? r.itemSize) && `مقاس ${r.variant?.size ?? r.itemSize}`, r.variant?.color ?? r.itemColor].filter(Boolean).join(' · ');

/**
 * Pre-orders: a customer claims pieces still on their way (an open purchase or import order),
 * or staff take one for a piece not in the app yet. When the pieces arrive:
 *  - the customer's own pre-order asks the customer to confirm; confirming puts the pieces
 *    aside (a reservation shown with the orders, for staff to prepare);
 *  - one taken by staff is put aside at once and the customer is told it is being prepared.
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
    this.timer = setInterval(() => void this.checkArrivals().catch((e) => this.log.error(e)), 5 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  async create(customerId: string, dto: PreOrderInput, user?: User) {
    if (dto.quantity < 1 || dto.quantity > 20) throw new BadRequestException('Quantity must be 1-20');
    const customer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true, code: true, name: true } });
    if (!customer || customer.code === 'CUS-WALKIN') throw new BadRequestException('Pick a named customer');
    if (!dto.variantId && !(user && dto.itemName?.trim())) throw new BadRequestException('Pick the size, or describe the piece');
    const id = await this.prisma.$transaction(async (tx) => {
      if (dto.variantId) {
        const v = await tx.productVariant.findUnique({ where: { id: dto.variantId }, select: { id: true } });
        if (!v) throw new NotFoundException('Size not found');
      }
      // A shopper takes from what is on its way; staff know what they ordered.
      if (!user && dto.variantId) {
        // One at a time per variant, so two customers cannot both take the last incoming piece.
        await tx.$queryRaw`SELECT id FROM product_variants WHERE id = ${dto.variantId}::uuid FOR UPDATE`;
        const open = (await incomingFor(tx, [dto.variantId])).get(dto.variantId)?.open ?? 0;
        if (open < dto.quantity) {
          throw new UnprocessableEntityException(open > 0 ? `باقي ${open} فقط في الشحنة القادمة` : 'لا توجد قطع قادمة من هذا المقاس');
        }
      }
      const p = await tx.preOrder.create({
        data: {
          number: await this.sequences.next(tx, 'PRE'),
          customerId,
          variantId: dto.variantId ?? null,
          quantity: dto.quantity,
          note: dto.note,
          ...(user
            ? {
                createdById: user.id,
                itemName: dto.itemName?.trim() || null,
                itemSize: dto.itemSize?.trim() || null,
                itemColor: dto.itemColor?.trim() || null,
                unitPrice: dto.unitPrice ? money(dto.unitPrice) : null,
              }
            : {}),
        },
      });
      return p.id;
    });
    await this.audit.log({ userId: user?.id ?? null, action: 'CREATE', entityType: 'PreOrder', entityId: id, after: { customerId, ...dto } });
    const made = await this.get(id);
    if (!user) {
      void this.notifications.notifyStaff('layaways.manage', {
        type: NotificationType.ORDER_STATUS,
        title: 'طلب مسبق جديد',
        body: `${made.number} · ${customer.name} · ${made.name} × ${made.quantity}`,
        data: { preOrderId: id },
      });
    }
    return made;
  }

  /** Staff correct any open pre-order: quantity, the piece (or link it to the size once added), price, note. */
  async update(id: string, dto: Partial<PreOrderInput>, user: User) {
    const p = await this.prisma.preOrder.findUnique({ where: { id } });
    if (!p) throw new NotFoundException('Pre-order not found');
    if (CLOSED.includes(p.status)) throw new UnprocessableEntityException('This pre-order is already closed');
    if (dto.quantity !== undefined && (dto.quantity < 1 || dto.quantity > 50)) throw new BadRequestException('Quantity must be 1-50');
    if (dto.variantId) {
      const v = await this.prisma.productVariant.findUnique({ where: { id: dto.variantId }, select: { id: true } });
      if (!v) throw new NotFoundException('Size not found');
    }
    const clear = (v: string | undefined) => (v === undefined ? undefined : v.trim() || null);
    await this.prisma.preOrder.update({
      where: { id },
      data: {
        quantity: dto.quantity,
        note: clear(dto.note),
        variantId: dto.variantId === undefined ? undefined : dto.variantId || null,
        itemName: clear(dto.itemName),
        itemSize: clear(dto.itemSize),
        itemColor: clear(dto.itemColor),
        unitPrice: dto.unitPrice === undefined ? undefined : dto.unitPrice ? money(dto.unitPrice) : null,
      },
    });
    await this.audit.log({ userId: user.id, action: 'UPDATE', entityType: 'PreOrder', entityId: id, before: p, after: dto });
    const after = await this.prisma.preOrder.findUniqueOrThrow({ where: { id } });
    if (!after.variantId && !after.itemName) throw new BadRequestException('Pick the size, or describe the piece');
    return this.get(id);
  }

  async cancel(id: string, by: { customerId?: string; userId?: string }) {
    const p = await this.prisma.preOrder.findUnique({ where: { id } });
    if (!p || (by.customerId && p.customerId !== by.customerId)) throw new NotFoundException('Pre-order not found');
    if (CLOSED.includes(p.status)) throw new UnprocessableEntityException('This pre-order is already closed');
    await this.prisma.preOrder.update({ where: { id }, data: { status: PreOrderStatus.CANCELLED, closedAt: new Date() } });
    await this.audit.log({ userId: by.userId ?? null, action: 'UPDATE', entityType: 'PreOrder', entityId: id, after: { cancelled: true } });
    return this.get(id);
  }

  /** The customer confirms an arrived pre-order: the pieces are put aside for them. */
  async confirm(id: string, customerId: string) {
    const p = await this.prisma.preOrder.findUnique({ where: { id } });
    if (!p || p.customerId !== customerId) throw new NotFoundException('Pre-order not found');
    if (p.status !== PreOrderStatus.NOTIFIED) throw new UnprocessableEntityException(p.status === PreOrderStatus.WAITING ? 'لم تصل القطع بعد' : 'هذا الطلب مغلق');
    await this.prisma.preOrder.update({ where: { id }, data: { confirmedAt: new Date() } });
    return this.convert(id, null);
  }

  /** The pieces arrived: put them aside for the customer as a reservation (no deposit yet). */
  async convert(id: string, user: User | null) {
    const p = await this.prisma.preOrder.findUnique({ where: { id }, include });
    if (!p) throw new NotFoundException('Pre-order not found');
    if (CLOSED.includes(p.status)) throw new UnprocessableEntityException('This pre-order is already closed');
    if (!p.variantId) throw new UnprocessableEntityException('اربط الطلب بالمنتج والمقاس بعد إضافته، ثم حوّله');
    if (free(p) < p.quantity) throw new UnprocessableEntityException('القطع غير متوفرة في المخزون بعد');
    const byStaff = !!p.createdById;
    const lay = await this.layaways.create(
      { customerId: p.customerId, items: [{ variantId: p.variantId, quantity: p.quantity }], note: [`من الطلب المسبق ${p.number}`, p.note].filter(Boolean).join(' — ') },
      user,
      { anyProduct: byStaff, prices: p.unitPrice ? new Map([[p.variantId, dec(p.unitPrice).toFixed(2)]]) : undefined },
    );
    await this.prisma.$transaction(async (tx) => {
      await tx.preOrder.update({ where: { id }, data: { status: PreOrderStatus.CONVERTED, layawayId: lay.id, closedAt: new Date() } });
      await this.notifications.notifyCustomer(tx, p.customerId, {
        type: NotificationType.ORDER_STATUS,
        title: 'طلبك قيد التجهيز 🛍️',
        body: `${describe(p)} محجوزة لك الآن ونجهّزها (حجز ${lay.number}).`,
        data: { preOrder: p.number, layaway: lay.number },
      });
    });
    return { preOrder: await this.get(id), layaway: lay };
  }

  /** Staff: "وصلت" on one pre-order, without waiting for the next automatic check. */
  async arrived(id: string, user: User) {
    const p = await this.prisma.preOrder.findUnique({ where: { id }, include });
    if (!p) throw new NotFoundException('Pre-order not found');
    if (p.status !== PreOrderStatus.WAITING) throw new UnprocessableEntityException('العميل أُبلغ مسبقاً أو الطلب مغلق');
    if (!p.variantId) throw new UnprocessableEntityException('اربط الطلب بالمنتج والمقاس بعد إضافته');
    if (free(p) < p.quantity) throw new UnprocessableEntityException('القطع غير متوفرة في المخزون بعد');
    await this.handleArrival(p, user);
    return this.get(id);
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
   * Handles waiting pre-orders whose pieces are in stock, oldest first, as long as there is
   * stock for them. Returns how many were handled.
   */
  async checkArrivals() {
    const waiting = await this.prisma.preOrder.findMany({ where: { status: PreOrderStatus.WAITING, variantId: { not: null } }, include, orderBy: { createdAt: 'asc' }, take: 1000 });
    const left = new Map<string, number>();
    let told = 0;
    for (const p of waiting) {
      const variantId = p.variantId!;
      if (!left.has(variantId)) left.set(variantId, free(p));
      const n = left.get(variantId)!;
      if (n < p.quantity) continue;
      left.set(variantId, n - p.quantity);
      try {
        await this.handleArrival(p, null);
        told++;
      } catch (e) {
        this.log.warn(`Pre-order ${p.number}: ${(e as Error).message}`);
      }
    }
    return told;
  }

  /** Staff-taken: put aside now. The customer's own: ask them to confirm. */
  private async handleArrival(p: Row, user: User | null) {
    if (p.createdById) {
      await this.convert(p.id, user);
      return;
    }
    const v = p.variant!;
    const image = v.product.images[0]?.url;
    await this.prisma.$transaction(async (tx) => {
      await this.notifications.notifyCustomer(tx, p.customerId, {
        type: NotificationType.ORDER_STATUS,
        title: 'وصل طلبك المسبق 🎉',
        body: `${describe(p)} وصلت. أكّدي الطلب من «طلباتي المسبقة» لنحجزها لك ونجهّزها.`,
        data: { preOrder: p.number, preOrderId: p.id, action: 'confirm', ...(image ? { image } : {}) },
      });
      await tx.preOrder.update({ where: { id: p.id }, data: { status: PreOrderStatus.NOTIFIED, notifiedAt: new Date() } });
    });
  }

  private async present(rows: Row[]) {
    const incoming = await incomingFor(this.prisma, [...new Set(rows.map((r) => r.variantId).filter((x): x is string => !!x))]);
    return rows.map((r) => ({
      id: r.id,
      number: r.number,
      status: r.status,
      quantity: r.quantity,
      note: r.note,
      layawayId: r.layawayId,
      createdAt: r.createdAt,
      notifiedAt: r.notifiedAt,
      confirmedAt: r.confirmedAt,
      /** Taken by staff (no confirmation asked when it arrives). */
      byStaff: !!r.createdById,
      customer: r.customer,
      variantId: r.variantId,
      sku: r.variant?.sku ?? null,
      size: r.variant?.size ?? r.itemSize,
      color: r.variant?.color ?? r.itemColor,
      productId: r.variant?.product.id ?? null,
      name: r.variant?.product.nameAr ?? r.itemName ?? '',
      itemName: r.itemName,
      itemSize: r.itemSize,
      itemColor: r.itemColor,
      unitPrice: r.unitPrice,
      slug: r.variant?.product.slug ?? null,
      imageUrl: r.variant?.product.images[0]?.url ?? null,
      inStock: free(r),
      expectedDate: (r.variantId && incoming.get(r.variantId)?.expectedDate) || null,
      /** Arrived and waiting for the customer to confirm. */
      awaitingConfirmation: r.status === PreOrderStatus.NOTIFIED && !r.createdById,
    }));
  }
}
