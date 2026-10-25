import { BadRequestException, Injectable } from '@nestjs/common';
import { Prisma } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { Decimal, dec, money } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { CatalogService } from './catalog.service';
import { activePromotions, promotedPrice } from './promotions';

export interface BulkPriceInput {
  /** Products picked one by one. */
  productIds?: string[];
  /** Whole categories (with their sub-categories). */
  categoryIds?: string[];
  brandIds?: string[];
  /** PERCENT: +10 / -15 percent. AMOUNT: +5 / -20 SAR. SET: this exact price. */
  mode: 'PERCENT' | 'AMOUNT' | 'SET';
  value: string;
  /** Round the new price to a multiple of this (1, 5, 10…); 0 = halalas. */
  roundTo?: number;
  /** When lowering, show the old price struck through (compareAtPrice). */
  keepOldAsCompareAt?: boolean;
  /** Also change sizes / colours that have their own price. Default true. */
  includeVariants?: boolean;
  /** Only show what would change. */
  preview?: boolean;
}

/** Changing many selling prices at once, and the list of products on offer. */
@Injectable()
export class BulkPricingService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly catalog: CatalogService,
  ) {}

  async bulkPrice(dto: BulkPriceInput, userId: string) {
    if (!dto.productIds?.length && !dto.categoryIds?.length && !dto.brandIds?.length) {
      throw new BadRequestException('Pick products, categories or brands');
    }
    const value = dec(dto.value);
    if (dto.mode === 'SET' && !value.gt(0)) throw new BadRequestException('The new price must be greater than zero');
    if (dto.mode === 'PERCENT' && value.lte(-100)) throw new BadRequestException('A price cannot go down by 100% or more');
    const categoryIds = dto.categoryIds?.length ? await this.withSubCategories(dto.categoryIds) : [];
    const where: Prisma.ProductWhereInput = {
      OR: [
        ...(dto.productIds?.length ? [{ id: { in: dto.productIds } }] : []),
        ...(categoryIds.length ? [{ categoryId: { in: categoryIds } }] : []),
        ...(dto.brandIds?.length ? [{ brandId: { in: dto.brandIds } }] : []),
      ],
    };
    const products = await this.prisma.product.findMany({
      where,
      orderBy: { nameAr: 'asc' },
      take: 2000,
      select: {
        id: true,
        nameAr: true,
        skuNumber: true,
        basePrice: true,
        compareAtPrice: true,
        variants: { where: { isActive: true }, orderBy: { sku: 'asc' }, select: { id: true, sku: true, size: true, color: true, price: true, barcode: true } },
      },
    });
    const round = (v: Decimal) => {
      const step = dto.roundTo ?? 0;
      const r = step > 0 ? v.div(step).toDecimalPlaces(0, Decimal.ROUND_HALF_UP).mul(step) : money(v);
      return r.gt(0) ? r : money(step > 0 ? step : 0.01);
    };
    const priced = (old: Decimal) =>
      round(dto.mode === 'SET' ? value : dto.mode === 'AMOUNT' ? old.add(value) : old.mul(dec(100).add(value)).div(100));
    const includeVariants = dto.includeVariants ?? true;
    const items = products.map((p) => {
      const oldPrice = dec(p.basePrice);
      const newPrice = priced(oldPrice);
      return {
        productId: p.id,
        name: p.nameAr,
        skuNumber: p.skuNumber,
        oldPrice,
        newPrice,
        variants: p.variants.map((v) => {
          const own = v.price === null ? null : dec(v.price);
          const next = own === null || !includeVariants ? own : priced(own);
          return { variantId: v.id, sku: v.sku, barcode: v.barcode, size: v.size, color: v.color, oldPrice: own ?? oldPrice, newPrice: next ?? newPrice };
        }),
      };
    });
    if (!dto.preview && items.length) {
      await this.prisma.$transaction(async (tx) => {
        for (const it of items) {
          const lower = it.newPrice.lt(it.oldPrice);
          await tx.product.update({
            where: { id: it.productId },
            data: {
              basePrice: it.newPrice,
              ...(dto.keepOldAsCompareAt ? { compareAtPrice: lower ? it.oldPrice : null } : {}),
            },
          });
          if (includeVariants) {
            const own = products.find((p) => p.id === it.productId)!.variants.filter((v) => v.price !== null);
            for (const v of own) {
              await tx.productVariant.update({ where: { id: v.id }, data: { price: it.variants.find((x) => x.variantId === v.id)!.newPrice } });
            }
          }
        }
        await this.audit.log({ userId, action: 'UPDATE', entityType: 'BulkPrice', entityId: items[0].productId, after: { ...dto, count: items.length } }, tx);
      });
      const how = dto.mode === 'SET' ? `إلى ${value.toFixed()}` : dto.mode === 'AMOUNT' ? `${value.gt(0) ? '+' : ''}${value.toFixed()} ر.س` : `${value.gt(0) ? '+' : ''}${value.toFixed()}%`;
      void this.catalog.priceAlert(userId, `تعديل جماعي ${how} لـ ${items.length} منتج`, {});
    }
    return { preview: !!dto.preview, count: items.length, items };
  }

  /** Products customers see at a reduced price: a running promotion or a struck-through price. */
  async onOffer() {
    const now = new Date();
    const rows = await this.prisma.product.findMany({
      where: {
        OR: [
          { promotions: { some: { promotion: { isActive: true, startsAt: { lte: now }, OR: [{ endsAt: null }, { endsAt: { gt: now } }] } } } },
          { compareAtPrice: { not: null } },
        ],
      },
      orderBy: { nameAr: 'asc' },
      take: 1000,
      select: {
        id: true,
        nameAr: true,
        slug: true,
        status: true,
        skuNumber: true,
        basePrice: true,
        compareAtPrice: true,
        images: { orderBy: { sortOrder: 'asc' }, take: 1, select: { url: true } },
        category: { select: { id: true, nameAr: true } },
      },
    });
    const promos = await activePromotions(this.prisma, rows.map((r) => r.id), now);
    return rows
      .map((r) => {
        const promo = promos.get(r.id);
        const price = promotedPrice(r.basePrice, promo);
        const was = promo ? dec(r.basePrice) : r.compareAtPrice ? dec(r.compareAtPrice) : null;
        if (!was || !was.gt(price)) return null;
        return {
          productId: r.id,
          name: r.nameAr,
          slug: r.slug,
          status: r.status,
          skuNumber: r.skuNumber,
          imageUrl: r.images[0]?.url ?? null,
          category: r.category,
          price,
          was,
          percentOff: was.sub(price).div(was).mul(100).toDecimalPlaces(0),
          promotion: promo ? { id: promo.promotionId, name: promo.name, percent: promo.percent, endsAt: promo.endsAt } : null,
        };
      })
      .filter((r): r is NonNullable<typeof r> => !!r);
  }

  private async withSubCategories(ids: string[]) {
    const rows = await this.prisma.$queryRaw<{ id: string }[]>`
      WITH RECURSIVE t AS (
        SELECT id FROM categories WHERE id = ANY(${ids}::uuid[])
        UNION SELECT c.id FROM categories c JOIN t ON c.parent_id = t.id
      ) SELECT id::text FROM t`;
    return rows.map((r) => r.id);
  }
}
