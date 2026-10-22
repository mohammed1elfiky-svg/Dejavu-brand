import { Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { AuditService } from '../common/audit.service';
import { dec } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';

export interface PromotionInput {
  name: string;
  percent: string;
  startsAt?: string;
  endsAt?: string | null;
  isActive?: boolean;
  productIds: string[];
  notifyCustomers?: boolean;
}

const include = {
  products: {
    select: { product: { select: { id: true, nameAr: true, nameEn: true, basePrice: true, skuNumber: true, images: { take: 1, orderBy: { sortOrder: 'asc' as const }, select: { url: true } } } } },
  },
};

/** Bulk percentage discounts on chosen products for a period. */
@Injectable()
export class PromotionsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly notifications: NotificationsService,
  ) {}

  async list() {
    const now = new Date();
    const rows = await this.prisma.promotion.findMany({ include, orderBy: [{ isActive: 'desc' }, { startsAt: 'desc' }] });
    return rows.map((p) => present(p, now));
  }

  async get(id: string) {
    const p = await this.prisma.promotion.findUnique({ where: { id }, include });
    if (!p) throw new NotFoundException('Promotion not found');
    return present(p, new Date());
  }

  async create(dto: PromotionInput, userId: string) {
    const data = await this.validate(dto);
    const p = await this.prisma.promotion.create({
      data: { ...data, products: { create: dto.productIds.map((productId) => ({ productId })) } },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Promotion', entityId: p.id, after: dto });
    return this.announce(await this.get(p.id), dto.notifyCustomers);
  }

  async update(id: string, dto: Partial<PromotionInput>, userId: string) {
    const before = await this.prisma.promotion.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Promotion not found');
    const data = await this.validate({
      name: dto.name ?? before.name,
      percent: dto.percent ?? before.percent.toString(),
      startsAt: dto.startsAt ?? before.startsAt.toISOString(),
      endsAt: dto.endsAt === undefined ? before.endsAt?.toISOString() : dto.endsAt,
      isActive: dto.isActive ?? before.isActive,
      productIds: dto.productIds ?? [],
    });
    await this.prisma.$transaction(async (tx) => {
      await tx.promotion.update({ where: { id }, data });
      if (dto.productIds) {
        await tx.promotionProduct.deleteMany({ where: { promotionId: id } });
        await tx.promotionProduct.createMany({ data: dto.productIds.map((productId) => ({ promotionId: id, productId })) });
      }
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Promotion', entityId: id, after: dto });
    return this.announce(await this.get(id), dto.notifyCustomers);
  }

  async remove(id: string, userId: string) {
    await this.prisma.promotion.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'Promotion', entityId: id });
  }

  /** Offer push to all customers, only while the promotion is running. */
  private async announce(p: Awaited<ReturnType<PromotionsService['get']>>, notify?: boolean) {
    if (!notify) return p;
    if (!p.running) return { ...p, notifiedCustomers: 0 };
    const percent = p.percent.toDecimalPlaces(0).toString();
    const count = p.products.length;
    const notifiedCustomers = await this.notifications.broadcast({
      type: 'PROMOTION',
      title: `${p.name} 🔥`,
      body: `خصم ${percent}% على ${count === 1 ? p.products[0].nameAr : `${count} منتجاً`}. تسوّق الآن قبل انتهاء العرض!`,
      data: { promotion: p.id },
      image: p.products[0]?.images[0]?.url ?? null,
    });
    return { ...p, notifiedCustomers };
  }

  private async validate(dto: PromotionInput) {
    const percent = dec(dto.percent);
    if (!percent.gt(0) || !percent.lt(100)) throw new UnprocessableEntityException('Discount must be between 0 and 100%');
    const startsAt = dto.startsAt ? new Date(dto.startsAt) : new Date();
    const endsAt = dto.endsAt ? new Date(dto.endsAt) : null;
    if (endsAt && endsAt <= startsAt) throw new UnprocessableEntityException('The end must be after the start');
    if (dto.productIds.length) {
      const found = await this.prisma.product.count({ where: { id: { in: dto.productIds } } });
      if (found !== new Set(dto.productIds).size) throw new NotFoundException('Some products were not found');
    }
    return { name: dto.name, percent, startsAt, endsAt, isActive: dto.isActive ?? true };
  }
}

function present<T extends { startsAt: Date; endsAt: Date | null; isActive: boolean; products: { product: object }[] }>(p: T, now: Date) {
  const { products, ...rest } = p;
  const running = p.isActive && p.startsAt <= now && (!p.endsAt || p.endsAt > now);
  return { ...rest, running, products: products.map((x) => x.product as T['products'][number]['product']) };
}
