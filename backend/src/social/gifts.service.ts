import { BadRequestException, ForbiddenException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit, UnprocessableEntityException } from '@nestjs/common';
import { CustomerStatus, DeliveryMethod, GiftSource, GiftStatus, GroupGiftStatus, NotificationType, OrderChannel, Prisma, ProductStatus } from '../generated/prisma/client';
import { normalizePhone, PHONE_REGEX } from '../auth/dto/auth.dto';
import { activePromotions, promotedPrice } from '../catalog/promotions';
import { SequenceService } from '../common/sequence.service';
import { isUsername, normalizeUsername } from '../common/username';
import { dec, Decimal, money } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { PosService } from '../sales/pos.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';
import { firstName, publicCard, publicSelect } from './profile';

/** Hidden account that holds group-gift contributions until the piece is bought. */
const POOL_CODE = 'CUS-GIFTPOOL';

export interface GiftChoice {
  deliveryMethod: DeliveryMethod;
  addressId?: string;
  carrierId?: string;
  carrierOfficeId?: string;
}

/**
 * Presents for customers: bought for them at the till (the cashier tags the sale as a gift), or
 * collected by friends (a group gift paid from their wallets). The receiver is notified and
 * chooses delivery (paying the courier on receipt) or pickup from the store.
 */
@Injectable()
export class GiftsService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Gifts');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly pos: PosService,
    private readonly accounts: CustomerAccountsService,
    private readonly sequences: SequenceService,
    private readonly notifications: NotificationsService,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.expireGroupGifts().catch((e) => this.log.error(e)), 30 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  /** A customer by mobile number or username. */
  async findCustomer(who: string) {
    const raw = who.trim();
    const byName = /^@?[A-Za-z]/.test(raw);
    if (byName ? !isUsername(raw) : !PHONE_REGEX.test(raw.replace(/[\s-]/g, ''))) throw new BadRequestException(byName ? 'اليوزر غير صحيح' : 'رقم الجوال غير صحيح');
    const c = byName
      ? await this.prisma.customer.findUnique({ where: { username: normalizeUsername(raw) } })
      : await this.prisma.customer.findUnique({ where: { phone: normalizePhone(raw.replace(/[\s-]/g, '')) } });
    if (!c || c.status !== CustomerStatus.ACTIVE || c.code === 'CUS-WALKIN' || c.code === POOL_CODE) {
      throw new NotFoundException(byName ? 'لا يوجد عميل بهذا اليوزر' : 'لا يوجد عميل مسجّل بهذا الرقم');
    }
    return c;
  }

  // ------------------------------------------------------------- till gift ---

  /** The cashier marks a till sale as a gift for a customer: they are told and choose how to get it. */
  async fromSale(dto: { saleOrderId: string; to: string; giverName: string; occasion?: string; message?: string }, userId: string) {
    const sale = await this.prisma.order.findUnique({ where: { id: dto.saleOrderId }, include: { items: true } });
    if (!sale || sale.channel !== OrderChannel.POS || sale.status === 'CANCELLED') throw new NotFoundException('Sale not found');
    if (await this.prisma.gift.count({ where: { saleOrderId: sale.id, status: { not: GiftStatus.CANCELLED } } })) throw new UnprocessableEntityException('هذه الفاتورة مسجّلة كهدية مسبقاً');
    const to = await this.findCustomer(dto.to);
    const items = await this.itemsOf(sale.items.map((i) => ({ variantId: i.variantId, quantity: i.quantity })));
    const gift = await this.prisma.$transaction(async (tx) => {
      const g = await tx.gift.create({
        data: {
          number: await this.sequences.next(tx, 'GFT'),
          recipientId: to.id,
          giverName: dto.giverName.trim(),
          occasion: dto.occasion?.trim() || null,
          message: dto.message?.trim() || null,
          source: GiftSource.POS,
          items: items as unknown as Prisma.InputJsonValue,
          saleOrderId: sale.id,
          createdById: userId,
        },
      });
      await this.tellReceiver(tx, g.id, to.id, dto.giverName, dto.occasion);
      return g;
    });
    return this.get(gift.id);
  }

  private async tellReceiver(tx: Tx, giftId: string, recipientId: string, from: string, occasion?: string | null) {
    await this.notifications.notifyCustomer(tx, recipientId, {
      type: NotificationType.PROMOTION,
      title: 'معك هدية 🎁',
      body: `هدية من ${from}${occasion ? ` بمناسبة ${occasion}` : ''}. اختر توصيلها لعنوانك أو استلامها من المحل.`,
      data: { gift: giftId, screen: 'gifts' },
    });
  }

  private async itemsOf(lines: { variantId: string; quantity: number }[]) {
    const variants = await this.prisma.productVariant.findMany({
      where: { id: { in: lines.map((l) => l.variantId) } },
      select: { id: true, size: true, color: true, product: { select: { nameAr: true, slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } },
    });
    return lines.map((l) => {
      const v = variants.find((x) => x.id === l.variantId);
      return { variantId: l.variantId, quantity: l.quantity, name: v?.product.nameAr ?? '', slug: v?.product.slug ?? null, size: v?.size ?? null, color: v?.color ?? null, imageUrl: v?.product.images[0]?.url ?? null };
    });
  }

  // ------------------------------------------------------ receiver chooses ---

  mine(customerId: string) {
    return this.prisma.gift.findMany({ where: { recipientId: customerId, status: { not: GiftStatus.CANCELLED } }, orderBy: { createdAt: 'desc' }, take: 50 });
  }

  /** Delivery to an address or an office (paid to the courier on receipt), or pickup from the store. */
  async choose(customerId: string, id: string, c: GiftChoice) {
    const g = await this.prisma.gift.findUnique({ where: { id } });
    if (!g || g.recipientId !== customerId) throw new NotFoundException('Gift not found');
    if (g.status !== GiftStatus.WAITING && g.status !== GiftStatus.PICKUP && g.status !== GiftStatus.DELIVERY) throw new UnprocessableEntityException('تم تسليم هذه الهدية');
    let delivery: Record<string, unknown> = { method: c.deliveryMethod };
    let carrierId: string | null = null;
    let officeId: string | null = null;
    if (c.deliveryMethod === DeliveryMethod.HOME) {
      const a = c.addressId ? await this.prisma.customerAddress.findFirst({ where: { id: c.addressId, customerId } }) : null;
      if (!a) throw new BadRequestException('اختر عنوان التوصيل');
      delivery = { method: c.deliveryMethod, recipient: a.recipient, phone: a.phone, governorate: a.governorate, city: a.city, street: a.street, details: (a as { details?: string | null }).details ?? null };
      if (c.carrierId) carrierId = (await this.prisma.carrier.findUnique({ where: { id: c.carrierId } }))?.id ?? null;
    } else if (c.deliveryMethod === DeliveryMethod.OFFICE) {
      const o = c.carrierOfficeId ? await this.prisma.carrierOffice.findUnique({ where: { id: c.carrierOfficeId }, include: { carrier: true } }) : null;
      if (!o || !o.isActive) throw new BadRequestException('اختر مكتب شركة الشحن');
      const me = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { name: true, phone: true } });
      delivery = { method: c.deliveryMethod, recipient: me.name, phone: me.phone, governorate: o.governorate, city: o.city, street: o.address, carrier: o.carrier.name, officeName: o.name, officePhones: o.phones };
      carrierId = o.carrierId;
      officeId = o.id;
    }
    await this.prisma.gift.update({
      where: { id },
      data: {
        status: c.deliveryMethod === DeliveryMethod.PICKUP ? GiftStatus.PICKUP : GiftStatus.DELIVERY,
        deliveryMethod: c.deliveryMethod,
        delivery: delivery as Prisma.InputJsonValue,
        carrierId,
        carrierOfficeId: officeId,
        chosenAt: new Date(),
      },
    });
    void this.notifications.notifyStaff('orders.update_status', {
      type: NotificationType.ORDER_STATUS,
      title: c.deliveryMethod === DeliveryMethod.PICKUP ? 'هدية ستُستلم من المحل' : 'هدية للتوصيل',
      body: `${g.number}`,
      data: { giftId: id },
    });
    return this.get(id);
  }

  // ----------------------------------------------------------------- staff ---

  list(status?: GiftStatus) {
    return this.prisma.gift.findMany({
      where: { status },
      orderBy: { createdAt: 'desc' },
      take: 200,
      include: { recipient: { select: { id: true, name: true, phone: true, username: true } } },
    });
  }

  async get(id: string) {
    const g = await this.prisma.gift.findUnique({ where: { id }, include: { recipient: { select: { id: true, name: true, phone: true, username: true } } } });
    if (!g) throw new NotFoundException('Gift not found');
    return g;
  }

  /** Handed over in the store, or shipped (with the tracking number). */
  async done(id: string, trackingNumber: string | undefined) {
    const g = await this.prisma.gift.findUnique({ where: { id } });
    if (!g) throw new NotFoundException('Gift not found');
    if (g.status === GiftStatus.DONE || g.status === GiftStatus.CANCELLED) throw new UnprocessableEntityException('Already closed');
    await this.prisma.$transaction(async (tx) => {
      await tx.gift.update({ where: { id }, data: { status: GiftStatus.DONE, doneAt: new Date(), trackingNumber: trackingNumber?.trim() || null } });
      await this.notifications.notifyCustomer(tx, g.recipientId, {
        type: NotificationType.ORDER_STATUS,
        title: g.deliveryMethod === DeliveryMethod.PICKUP ? 'استلمت هديتك 🎁' : 'شُحنت هديتك 🚚',
        body: trackingNumber ? `رقم التتبع: ${trackingNumber}` : `هدية ${g.number}`,
        data: { gift: id, screen: 'gifts' },
      });
    });
    return this.get(id);
  }

  // ----------------------------------------------------------- group gifts ---

  private async pool(tx: Tx) {
    const found = await tx.customer.findUnique({ where: { code: POOL_CODE }, select: { id: true } });
    if (found) return found.id;
    return (await tx.customer.create({ data: { code: POOL_CODE, name: 'صندوق الهدايا الجماعية', phone: POOL_CODE, status: CustomerStatus.BLOCKED } })).id;
  }

  /** Starts a collection for one piece for [to] (their username), open for [days]. */
  async startGroup(organizerId: string, dto: { to: string; variantId: string; title?: string; occasion?: string; message?: string; days?: number }) {
    const to = await this.findCustomer(dto.to);
    if (to.id === organizerId) throw new BadRequestException('لا يمكنك جمع هدية لنفسك');
    const v = await this.prisma.productVariant.findUnique({ where: { id: dto.variantId }, include: { product: { select: { id: true, nameAr: true, basePrice: true, status: true } } } });
    if (!v || !v.isActive || v.product.status !== ProductStatus.ACTIVE) throw new NotFoundException('القطعة غير متاحة');
    const promo = (await activePromotions(this.prisma, [v.product.id])).get(v.product.id);
    const target = promotedPrice(v.price ?? v.product.basePrice, promo);
    const days = Math.min(Math.max(dto.days ?? 7, 1), 30);
    const g = await this.prisma.$transaction(async (tx) =>
      tx.groupGift.create({
        data: {
          number: await this.sequences.next(tx, 'GGF'),
          organizerId,
          recipientId: to.id,
          variantId: v.id,
          title: dto.title?.trim() || `هدية لـ ${firstName(to.name)}`,
          occasion: dto.occasion?.trim() || null,
          message: dto.message?.trim() || null,
          target,
          endsAt: new Date(Date.now() + days * 86_400_000),
        },
      }),
    );
    return this.group(g.id, organizerId);
  }

  async group(id: string, viewerId?: string) {
    const g = await this.prisma.groupGift.findUnique({ where: { id }, include: { contributions: { where: { refunded: false }, orderBy: { createdAt: 'asc' } } } });
    if (!g) throw new NotFoundException('Group gift not found');
    // A surprise: the receiver sees it only once it is bought.
    if (viewerId && viewerId === g.recipientId && g.status !== GroupGiftStatus.FUNDED) throw new NotFoundException('Group gift not found');
    const people = new Map(
      (await this.prisma.customer.findMany({ where: { id: { in: [g.organizerId, g.recipientId, ...g.contributions.map((c) => c.customerId)] } }, select: publicSelect })).map((c) => [c.id, publicCard(c)]),
    );
    const v = await this.prisma.productVariant.findUnique({ where: { id: g.variantId }, select: { size: true, color: true, product: { select: { nameAr: true, slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } } });
    return {
      id: g.id,
      number: g.number,
      title: g.title,
      occasion: g.occasion,
      message: g.message,
      status: g.status,
      target: g.target,
      raised: g.raised,
      remaining: money(Decimal.max(dec(0), dec(g.target).sub(g.raised))),
      endsAt: g.endsAt,
      organizer: people.get(g.organizerId) ?? null,
      recipient: people.get(g.recipientId) ?? null,
      product: v ? { name: v.product.nameAr, slug: v.product.slug, size: v.size, color: v.color, imageUrl: v.product.images[0]?.url ?? null } : null,
      contributions: g.contributions.map((c) => ({ by: people.get(c.customerId) ?? null, amount: c.amount, message: c.message, at: c.createdAt })),
      isOrganizer: viewerId === g.organizerId,
    };
  }

  /** Open collections for a customer (for their friends to join), and the ones I started or joined. */
  async groupsFor(username: string, viewerId: string) {
    const to = await this.findCustomer(username);
    if (to.id === viewerId) return [];
    const rows = await this.prisma.groupGift.findMany({ where: { recipientId: to.id, status: GroupGiftStatus.OPEN, endsAt: { gt: new Date() } }, orderBy: { createdAt: 'desc' }, select: { id: true } });
    return Promise.all(rows.map((r) => this.group(r.id, viewerId)));
  }

  async myGroups(customerId: string) {
    const rows = await this.prisma.groupGift.findMany({
      where: { OR: [{ organizerId: customerId }, { contributions: { some: { customerId } } }] },
      orderBy: { createdAt: 'desc' },
      take: 50,
      select: { id: true },
    });
    return Promise.all(rows.map((r) => this.group(r.id, customerId)));
  }

  /**
   * Chips in [amount] SAR from the wallet (other currencies are changed into SAR). Reaching the
   * target buys the piece for the receiver right away: they are told and choose delivery.
   */
  async contribute(customerId: string, id: string, amountRaw: string, message?: string) {
    const amount = money(amountRaw);
    if (!amount.gt(0)) throw new BadRequestException('أدخل مبلغاً أكبر من صفر');
    const funded = await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM group_gifts WHERE id = ${id}::uuid FOR UPDATE`;
      const g = await tx.groupGift.findUnique({ where: { id } });
      if (!g || g.status !== GroupGiftStatus.OPEN) throw new UnprocessableEntityException('الهدية الجماعية مغلقة');
      if (g.endsAt < new Date()) throw new UnprocessableEntityException('انتهت مدة جمع الهدية');
      if (g.recipientId === customerId) throw new ForbiddenException('Not allowed');
      const remaining = dec(g.target).sub(g.raised);
      if (amount.gt(remaining)) throw new UnprocessableEntityException(`المتبقي ${money(remaining).toFixed(2)} ر.س فقط`);
      const me = await tx.customer.findUniqueOrThrow({ where: { id: customerId }, select: { name: true } });
      await this.accounts.cover(tx, customerId, 'SAR', amount, `مساهمة في ${g.number}`);
      const poolId = await this.pool(tx);
      await this.accounts.transfer(tx, { fromId: customerId, toId: poolId, currency: 'SAR', amount, description: `Group gift ${g.number}: ${me.name}`, sourceType: 'GROUP_GIFT' });
      await tx.groupGiftContribution.create({ data: { groupGiftId: id, customerId, amount, message: message?.trim() || null } });
      const raised = dec(g.raised).add(amount);
      await tx.groupGift.update({ where: { id }, data: { raised } });
      if (raised.lt(g.target)) {
        if (g.organizerId !== customerId) {
          await this.notifications.notifyCustomer(tx, g.organizerId, {
            type: NotificationType.PROMOTION,
            title: `${firstName(me.name)} ساهم في «${g.title}»`,
            body: `جُمع ${money(raised).toFixed(2)} من ${money(g.target).toFixed(2)} ر.س`,
            data: { groupGift: id, screen: 'group-gift' },
          });
        }
        return false;
      }
      await this.fund(tx, id, poolId);
      return true;
    });
    return { funded, groupGift: await this.group(id, customerId) };
  }

  /** The target was reached: the pool pays the piece into the receiver's name as a gift. */
  private async fund(tx: Tx, id: string, poolId: string) {
    const g = await tx.groupGift.findUniqueOrThrow({ where: { id }, include: { contributions: { where: { refunded: false } } } });
    await this.accounts.transfer(tx, { fromId: poolId, toId: g.recipientId, currency: 'SAR', amount: dec(g.target), description: `Group gift ${g.number}`, sourceType: 'GROUP_GIFT' });
    const owner = await tx.userRole.findFirst({ where: { role: { name: 'admin' } }, orderBy: { userId: 'asc' }, select: { userId: true } });
    if (!owner) throw new UnprocessableEntityException('No admin account to record the sale');
    // The piece may cost more now than when the collection started: the store gives the difference.
    const v = await tx.productVariant.findUniqueOrThrow({ where: { id: g.variantId }, include: { product: { select: { id: true, basePrice: true } } } });
    const promo = (await activePromotions(tx, [v.product.id])).get(v.product.id);
    const price = promotedPrice(v.price ?? v.product.basePrice, promo);
    const extra = price.sub(g.target);
    const saleId = await this.pos.sellIn(
      tx,
      {
        items: [{ variantId: g.variantId, quantity: 1 }],
        currency: 'SAR',
        saleType: 'CASH',
        customerId: g.recipientId,
        fromAccount: money(Decimal.min(price, g.target)).toFixed(2),
        ...(extra.gt(0) ? { discountType: 'AMOUNT' as const, discountValue: money(extra).toFixed(2) } : {}),
        applyDeals: false,
        note: `هدية جماعية ${g.number}`,
      },
      owner.userId,
    );
    const contributors = [...new Set(g.contributions.map((c) => c.customerId))];
    const names = (await tx.customer.findMany({ where: { id: { in: contributors } }, select: { name: true } })).map((c) => firstName(c.name));
    const giverName = names.length > 3 ? `${names.slice(0, 3).join('، ')} و${names.length - 3} آخرين` : names.join(' و');
    const gift = await tx.gift.create({
      data: {
        number: await this.sequences.next(tx, 'GFT'),
        recipientId: g.recipientId,
        giverCustomerId: g.organizerId,
        giverName,
        occasion: g.occasion,
        message: g.message,
        source: GiftSource.GROUP,
        items: (await this.itemsOf([{ variantId: g.variantId, quantity: 1 }])) as unknown as Prisma.InputJsonValue,
        saleOrderId: saleId,
      },
    });
    await tx.groupGift.update({ where: { id }, data: { status: GroupGiftStatus.FUNDED, giftId: gift.id } });
    await this.tellReceiver(tx, gift.id, g.recipientId, giverName, g.occasion);
    for (const c of contributors) {
      await this.notifications.notifyCustomer(tx, c, {
        type: NotificationType.PROMOTION,
        title: `اكتملت «${g.title}» 🎉`,
        body: 'اشترينا الهدية ووصل الإشعار لصاحبها',
        data: { groupGift: id, screen: 'group-gift' },
      });
    }
  }

  /** The organizer stops it: every contribution goes back to its wallet. */
  async cancelGroup(customerId: string, id: string) {
    const g = await this.prisma.groupGift.findUnique({ where: { id } });
    if (!g || g.organizerId !== customerId) throw new NotFoundException('Group gift not found');
    await this.refund(id, 'أوقف المنظّم جمع الهدية');
    return this.group(id, customerId);
  }

  /** Collections past their end date without reaching the target are refunded. */
  async expireGroupGifts(now = new Date()) {
    const rows = await this.prisma.groupGift.findMany({ where: { status: GroupGiftStatus.OPEN, endsAt: { lt: now } }, select: { id: true } });
    for (const r of rows) await this.refund(r.id, 'انتهت مدة جمع الهدية قبل اكتمال المبلغ');
    return rows.length;
  }

  private async refund(id: string, reason: string) {
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM group_gifts WHERE id = ${id}::uuid FOR UPDATE`;
      const g = await tx.groupGift.findUniqueOrThrow({ where: { id }, include: { contributions: { where: { refunded: false } } } });
      if (g.status !== GroupGiftStatus.OPEN) throw new UnprocessableEntityException('الهدية الجماعية مغلقة');
      const poolId = await this.pool(tx);
      for (const c of g.contributions) {
        await this.accounts.transfer(tx, { fromId: poolId, toId: c.customerId, currency: 'SAR', amount: dec(c.amount), description: `Group gift ${g.number} refunded`, sourceType: 'GROUP_GIFT' });
        await tx.groupGiftContribution.update({ where: { id: c.id }, data: { refunded: true } });
        await this.notifications.notifyCustomer(tx, c.customerId, {
          type: NotificationType.WALLET,
          title: 'أُعيدت مساهمتك',
          body: `${reason} (${g.title}). أُعيد ${money(c.amount).toFixed(2)} ر.س إلى رصيدك.`,
          data: { groupGift: id },
        });
      }
      await tx.groupGift.update({ where: { id }, data: { status: GroupGiftStatus.CANCELLED, raised: 0 } });
    });
  }
}
