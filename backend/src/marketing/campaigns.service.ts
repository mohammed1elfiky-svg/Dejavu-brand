import { BadRequestException, Injectable } from '@nestjs/common';
import { NotificationType, Prisma } from '../generated/prisma/client';
import { NotificationsService } from '../notifications/notifications.service';
import { SmsService } from '../sms/sms.service';
import { PrismaService } from '../prisma/prisma.service';

/** Which customers get a message. Every filter that is set must match (AND). */
export interface Segment {
  merchantsOnly?: boolean;
  governorates?: string[];
  /** Bought something from these categories (and their sub-categories)… */
  boughtCategoryIds?: string[];
  /** …within this many days (any time when left out). */
  boughtWithinDays?: number;
  /** Bought before, but nothing in the last N days. */
  notBoughtForDays?: number;
  /** Spent at least this much (SAR, all orders not cancelled). */
  minSpent?: number;
  /** Signed up within the last N days. */
  joinedWithinDays?: number;
  /** Has one of these products in the wish list. */
  wishlistProductIds?: string[];
  /** Left items in the cart. */
  hasCart?: boolean;
  /** Picked by hand. */
  customerIds?: string[];
}

/** Where tapping the notification leads in the customer app. */
export interface Target {
  type: 'none' | 'product' | 'category' | 'url';
  value?: string;
}

/** SMS go out one by one through the owner's phone; a campaign texts at most this many. */
const SMS_MAX = 500;

/** Messages to a chosen group of customers: an app notification (with picture and link) and/or SMS. */
@Injectable()
export class CampaignsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
    private readonly sms: SmsService,
  ) {}

  private async where(s: Segment): Promise<Prisma.CustomerWhereInput> {
    const and: Prisma.CustomerWhereInput[] = [{ status: 'ACTIVE' }, { code: { not: 'CUS-WALKIN' } }];
    const ago = (d: number) => new Date(Date.now() - d * 24 * 3600_000);
    if (s.merchantsOnly) and.push({ isMerchant: true });
    if (s.governorates?.length) and.push({ governorate: { in: s.governorates } });
    if (s.customerIds?.length) and.push({ id: { in: s.customerIds } });
    if (s.joinedWithinDays) and.push({ createdAt: { gte: ago(s.joinedWithinDays) } });
    if (s.wishlistProductIds?.length) and.push({ wishlist: { some: { productId: { in: s.wishlistProductIds } } } });
    if (s.hasCart) and.push({ cart: { items: { some: {} } } });
    if (s.boughtCategoryIds?.length) {
      const rows = await this.prisma.$queryRaw<{ id: string }[]>`
        WITH RECURSIVE t AS (
          SELECT id FROM categories WHERE id = ANY(${s.boughtCategoryIds}::uuid[])
          UNION SELECT c.id FROM categories c JOIN t ON c.parent_id = t.id
        ) SELECT id::text FROM t`;
      and.push({
        orders: {
          some: {
            status: { not: 'CANCELLED' },
            ...(s.boughtWithinDays ? { placedAt: { gte: ago(s.boughtWithinDays) } } : {}),
            items: { some: { variant: { product: { categoryId: { in: rows.map((r) => r.id) } } } } },
          },
        },
      });
    } else if (s.boughtWithinDays) {
      and.push({ orders: { some: { status: { not: 'CANCELLED' }, placedAt: { gte: ago(s.boughtWithinDays) } } } });
    }
    if (s.notBoughtForDays) {
      and.push({ orders: { some: { status: { not: 'CANCELLED' } } } });
      and.push({ orders: { none: { status: { not: 'CANCELLED' }, placedAt: { gte: ago(s.notBoughtForDays) } } } });
    }
    if (s.minSpent) {
      const spenders = await this.prisma.order.groupBy({
        by: ['customerId'],
        where: { status: { not: 'CANCELLED' } },
        _sum: { grandTotal: true },
        having: { grandTotal: { _sum: { gte: s.minSpent } } },
      });
      and.push({ id: { in: spenders.map((x) => x.customerId) } });
    }
    return { AND: and };
  }

  /** How many customers the filters pick, and a few of their names. */
  async preview(segment: Segment) {
    const where = await this.where(segment);
    const [count, sample, withApp] = await Promise.all([
      this.prisma.customer.count({ where }),
      this.prisma.customer.findMany({ where, take: 8, orderBy: { createdAt: 'desc' }, select: { id: true, name: true, phone: true } }),
      this.prisma.customer.count({ where: { AND: [where, { devices: { some: {} } }] } }),
    ]);
    return { count, withApp, sample, smsMax: SMS_MAX };
  }

  async send(
    dto: { title: string; body: string; imageUrl?: string; target?: Target; segment: Segment; channels: ('PUSH' | 'SMS')[] },
    userId: string,
  ) {
    if (!dto.channels.length) throw new BadRequestException('Pick push and/or SMS');
    const target: Target = dto.target ?? { type: 'none' };
    if (target.type !== 'none' && !target.value?.trim()) throw new BadRequestException('Pick where the notification leads');
    if (target.type === 'url' && !/^https?:\/\//.test(target.value!)) throw new BadRequestException('The link must start with http:// or https://');
    const customers = await this.prisma.customer.findMany({ where: await this.where(dto.segment), select: { id: true, phone: true } });
    if (!customers.length) throw new BadRequestException('No customers match these filters');

    const data: Record<string, string> = { campaign: '1' };
    if (target.type === 'product') data.product = target.value!;
    if (target.type === 'category') data.category = target.value!;
    if (target.type === 'url') data.url = target.value!;
    if (dto.channels.includes('PUSH')) {
      await this.notifications.notifyMany(
        customers.map((c) => c.id),
        { type: NotificationType.PROMOTION, title: dto.title, body: dto.body, data, image: dto.imageUrl || null },
      );
    }
    let smsCount = 0;
    if (dto.channels.includes('SMS')) {
      const text = `${dto.title}\n${dto.body}${target.type === 'url' ? `\n${target.value}` : ''}`;
      for (const c of customers.slice(0, SMS_MAX)) {
        await this.sms.enqueue(c.phone, text, 'OTHER');
        smsCount++;
      }
    }
    return this.prisma.campaign.create({
      data: {
        title: dto.title,
        body: dto.body,
        imageUrl: dto.imageUrl || null,
        target: target as object,
        segment: dto.segment as object,
        channels: dto.channels,
        recipients: customers.length,
        smsCount,
        createdById: userId,
      },
    });
  }

  list(take = 50) {
    return this.prisma.campaign.findMany({ orderBy: { createdAt: 'desc' }, take: Math.min(Math.max(take, 1), 200) });
  }
}
