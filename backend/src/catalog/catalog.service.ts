import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma, ProductStatus, ProductVisibility } from '../generated/prisma/client';
import { AdjustmentReason } from '../inventory/dto/inventory.dto';
import { AuditService } from '../common/audit.service';
import { formatAmount } from '../common/currency.service';
import { NotificationsService } from '../notifications/notifications.service';
import { paginated } from '../common/dto/pagination.dto';
import { InventoryService } from '../inventory/inventory.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { activePromotions, Buyer, buyerPrice, promotedPrice } from './promotions';
import {
  AdminProductsQueryDto,
  CreateBrandDto,
  CreateCategoryDto,
  CreateProductDto,
  UpdateBrandDto,
  ProductImageDto,
  StoreProductsQueryDto,
  UpdateCategoryDto,
  UpdateProductDto,
  UpdateVariantDto,
  NewVariantDto,
  VariantDto,
} from './dto/catalog.dto';

const brandSelect = { id: true, name: true, slug: true, logoUrl: true } satisfies Prisma.BrandSelect;

const adminProductInclude = {
  category: { select: { id: true, nameAr: true, nameEn: true, slug: true } },
  brand: { select: brandSelect },
  taxRate: { select: { id: true, name: true, rate: true } },
  images: { orderBy: { sortOrder: 'asc' } },
  variants: {
    orderBy: { sku: 'asc' },
    include: { stockLevels: { select: { warehouseId: true, onHand: true, reserved: true } } },
  },
} satisfies Prisma.ProductInclude;

/** Customer-facing projection: never exposes cost or internal flags. */
const storeProductSelect = {
  id: true,
  slug: true,
  nameAr: true,
  nameEn: true,
  descriptionAr: true,
  descriptionEn: true,
  basePrice: true,
  compareAtPrice: true,
  wholesalePrice: true,
  isFeatured: true,
  ratingAvg: true,
  ratingCount: true,
  category: { select: { id: true, slug: true, nameAr: true, nameEn: true } },
  brand: { select: brandSelect },
  images: { orderBy: { sortOrder: 'asc' }, select: { url: true, altText: true, variantId: true, color: true } },
  variants: {
    where: { isActive: true },
    orderBy: { sku: 'asc' },
    select: {
      id: true,
      sku: true,
      size: true,
      color: true,
      colorHex: true,
      price: true,
      // Online orders are fulfilled from the default warehouse.
      stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } },
    },
  },
} satisfies Prisma.ProductSelect;

type StoreProductRow = Prisma.ProductGetPayload<{ select: typeof storeProductSelect }>;

/** Shopper view of a product: running promotion applied, original price shown struck through. */
const presentStoreProduct = ({ variants, wholesalePrice, ...p }: StoreProductRow, promos: Awaited<ReturnType<typeof activePromotions>>, buyer?: Buyer | null) => {
  const promo = promos.get(p.id);
  const merchant = !!buyer?.isMerchant;
  const shaped = variants.map(({ stockLevels, price, ...v }) => {
    const available = stockLevels.reduce((n, s) => n + s.onHand - s.reserved, 0);
    const shelf = price ?? p.basePrice;
    const paid = buyerPrice(shelf, wholesalePrice, promo, buyer);
    return {
      ...v,
      price: paid,
      compareAtPrice: promo || (merchant && paid.lt(shelf)) ? shelf : null,
      availableQty: available,
      inStock: available > 0,
    };
  });
  const base = buyerPrice(p.basePrice, wholesalePrice, promo, buyer);
  return {
    ...p,
    basePrice: base,
    compareAtPrice: promo || (merchant && base.lt(p.basePrice)) ? p.basePrice : p.compareAtPrice,
    /** Shown as "سعر التاجر" in the app. */
    merchantPrice: merchant,
    promotion: promo ? { name: promo.name, percent: promo.percent, endsAt: promo.endsAt } : null,
    inStock: shaped.some((v) => v.inStock),
    variants: shaped,
  };
};

@Injectable()
export class CatalogService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly inventory: InventoryService,
    private readonly notifications: NotificationsService,
  ) {}

  // --------------------------------------------------------------- brands ---

  async listBrands() {
    const rows = await this.prisma.brand.findMany({
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
      include: { _count: { select: { products: true } } },
    });
    return rows.map(({ _count, ...b }) => ({ ...b, productCount: _count.products }));
  }

  /** Brands shown to shoppers: active ones that have something on sale. */
  storeBrands() {
    return this.prisma.brand.findMany({
      where: { isActive: true, products: { some: { status: ProductStatus.ACTIVE, visibility: ProductVisibility.ALL } } },
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
      select: brandSelect,
    });
  }

  async createBrand(dto: CreateBrandDto, userId: string) {
    const brand = await this.saveBrand(() =>
      this.prisma.brand.create({ data: { ...dto, slug: dto.slug ?? textSlug(dto.name, 'brand') } }),
    );
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Brand', entityId: brand.id, after: brand });
    return brand;
  }

  async updateBrand(id: string, dto: UpdateBrandDto, userId: string) {
    await this.findBrand(id);
    const brand = await this.saveBrand(() => this.prisma.brand.update({ where: { id }, data: dto }));
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Brand', entityId: id, after: brand });
    return brand;
  }

  /** Products of a deleted brand simply lose their brand. */
  async deleteBrand(id: string, userId: string) {
    const before = await this.findBrand(id);
    await this.prisma.brand.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'Brand', entityId: id, before });
  }

  private async findBrand(id: string) {
    const brand = await this.prisma.brand.findUnique({ where: { id } });
    if (!brand) throw new NotFoundException('Brand not found');
    return brand;
  }

  private async saveBrand<T>(write: () => Promise<T>): Promise<T> {
    try {
      return await write();
    } catch (e) {
      if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
        throw new ConflictException('A brand with this name or link already exists');
      }
      throw e;
    }
  }

  // ----------------------------------------------------------- categories ---

  listCategories() {
    return this.prisma.category.findMany({ orderBy: [{ sortOrder: 'asc' }, { nameEn: 'asc' }] });
  }

  async categoryTree() {
    const rows = await this.prisma.category.findMany({
      where: { isActive: true },
      orderBy: [{ sortOrder: 'asc' }, { nameEn: 'asc' }],
      select: { id: true, parentId: true, slug: true, nameAr: true, nameEn: true, imageUrl: true },
    });
    type Node = (typeof rows)[number] & { children: Node[] };
    const nodes = new Map<string, Node>(rows.map((r) => [r.id, { ...r, children: [] }]));
    const roots: Node[] = [];
    for (const n of nodes.values()) {
      const parent = n.parentId ? nodes.get(n.parentId) : undefined;
      if (parent) parent.children.push(n);
      else if (!n.parentId) roots.push(n);
    }
    return roots;
  }

  async createCategory(dto: CreateCategoryDto, userId: string) {
    if (dto.parentId) await this.findCategory(dto.parentId);
    const category = await this.prisma.category.create({ data: dto });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Category', entityId: category.id, after: category });
    return category;
  }

  async updateCategory(id: string, dto: UpdateCategoryDto, userId: string) {
    await this.findCategory(id);
    if (dto.parentId) {
      if (dto.parentId === id || (await this.descendantIds(id)).includes(dto.parentId)) {
        throw new BadRequestException('A category cannot be moved under itself');
      }
      await this.findCategory(dto.parentId);
    }
    const category = await this.prisma.category.update({ where: { id }, data: dto });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Category', entityId: id, after: category });
    return category;
  }

  async deleteCategory(id: string, userId: string) {
    const cat = await this.prisma.category.findUnique({
      where: { id },
      include: { _count: { select: { products: true, children: true } } },
    });
    if (!cat) throw new NotFoundException('Category not found');
    if (cat._count.products || cat._count.children) {
      throw new ConflictException('Category still has products or sub-categories; deactivate it instead');
    }
    await this.prisma.category.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'Category', entityId: id, before: cat });
  }

  private async findCategory(id: string) {
    const cat = await this.prisma.category.findUnique({ where: { id } });
    if (!cat) throw new NotFoundException('Category not found');
    return cat;
  }

  private async descendantIds(rootId: string): Promise<string[]> {
    const rows = await this.prisma.$queryRaw<{ id: string }[]>`
      WITH RECURSIVE tree AS (
        SELECT id FROM categories WHERE parent_id = ${rootId}::uuid
        UNION ALL
        SELECT c.id FROM categories c JOIN tree t ON c.parent_id = t.id
      ) SELECT id FROM tree`;
    return rows.map((r) => r.id);
  }

  // ------------------------------------------------------- admin products ---

  async listProducts(q: AdminProductsQueryDto) {
    // A parent category includes its sub-categories.
    const categoryIds = q.categoryId ? [q.categoryId, ...(await this.descendantIds(q.categoryId))] : undefined;
    const where: Prisma.ProductWhereInput = {
      categoryId: categoryIds ? { in: categoryIds } : undefined,
      brandId: q.brandId,
      status: q.status,
      ...(q.q && this.searchFilter(q.q)),
    };
    const [items, total] = await Promise.all([
      this.prisma.product.findMany({
        where,
        include: adminProductInclude,
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.product.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async getProduct(id: string) {
    const product = await this.prisma.product.findUnique({ where: { id }, include: adminProductInclude });
    if (!product) throw new NotFoundException('Product not found');
    return product;
  }

  async createProduct(dto: CreateProductDto, userId: string) {
    await this.findCategory(dto.categoryId);
    if (dto.brandId) await this.findBrand(dto.brandId);
    const taxRateId = dto.taxRateId === undefined ? await this.defaultTaxRateId() : dto.taxRateId;
    const { variants, images, notifyCustomers, ...fields } = dto;

    const product = await this.prisma.$transaction(async (tx) => {
      const skuNumber = fields.skuNumber ?? (await this.nextSkuNumber(tx));
      // Variants are numbered 1..n inside the product; their SKU defaults to "<skuNumber>-<n>".
      const numbered = variants.map((v, i) => ({ ...v, variantNo: i + 1, sku: v.sku ?? `${skuNumber}-${i + 1}` }));
      const created = await tx.product.create({
        data: {
          ...fields,
          nameEn: fields.nameEn ?? fields.nameAr,
          slug: fields.slug ?? productSlug(fields.nameEn ?? fields.nameAr, skuNumber),
          skuNumber,
          taxRateId,
          variants: { create: numbered.map((v) => ({ ...variantData(v), variantNo: v.variantNo })) } },
        include: { variants: true },
      });
      if (images?.length) {
        await tx.productImage.createMany({ data: this.imageRows(created.id, images, created.variants.map((v) => v.id)) });
      }
      await this.openingStock(
        tx,
        numbered.map((v) => ({ ...v, unitCost: v.unitCost ?? fields.costPrice ?? '0', id: created.variants.find((c) => c.sku === v.sku)!.id })),
        userId,
      );
      return created;
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Product', entityId: product.id, after: dto });
    return this.withAnnouncement(await this.getProduct(product.id), notifyCustomers);
  }

  async updateProduct(id: string, dto: UpdateProductDto, userId: string) {
    const before = await this.getProduct(id);
    if (dto.categoryId) await this.findCategory(dto.categoryId);
    if (dto.brandId) await this.findBrand(dto.brandId);
    const { notifyCustomers, ...data } = dto;
    if (dto.status === ProductStatus.ACTIVE && !before.variants.some((v) => v.isActive)) {
      throw new BadRequestException('A product needs at least one active variant to be published');
    }
    await this.prisma.$transaction(async (tx) => {
      await tx.product.update({ where: { id }, data });
      // The product cost is the cost of pieces not yet valued (nothing in stock).
      if (dto.costPrice !== undefined) {
        await tx.$executeRaw`
          UPDATE product_variants v SET avg_cost = ${dto.costPrice}::numeric
          WHERE v.product_id = ${id}::uuid
            AND NOT EXISTS (SELECT 1 FROM stock_levels s WHERE s.variant_id = v.id AND s.on_hand > 0)`;
      }
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Product', entityId: id, after: dto });
    return this.withAnnouncement(await this.getProduct(id), notifyCustomers);
  }

  /**
   * "New product" push to customers (name, price and photo). Only a published product
   * customers can see is announced; [notifiedCustomers] says how many got it (0 = not sent).
   */
  private async withAnnouncement<P extends Awaited<ReturnType<CatalogService['getProduct']>>>(product: P, notify?: boolean) {
    if (!notify) return product;
    const visible = product.status === ProductStatus.ACTIVE && (product.visibility === ProductVisibility.ALL || product.visibility === ProductVisibility.MERCHANTS);
    if (!visible) return { ...product, notifiedCustomers: 0 };
    const image = product.images[0]?.url ?? null;
    const notifiedCustomers = await this.notifications.broadcast(
      {
        type: 'PROMOTION',
        title: 'وصل حديثاً ✨',
        body: `${product.nameAr} متوفر الآن بسعر ${formatAmount(product.basePrice)}. اطّلع عليه قبل نفاد الكمية!`,
        data: { product: product.slug },
        image,
      },
      { merchantsOnly: product.visibility === ProductVisibility.MERCHANTS },
    );
    return { ...product, notifiedCustomers };
  }

  async addVariant(productId: string, dto: NewVariantDto, userId: string) {
    const product = await this.getProduct(productId);
    const variant = await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM products WHERE id = ${productId}::uuid FOR UPDATE`;
      // Older products get their numeric SKU now, so new variants can follow the pattern.
      const skuNumber = product.skuNumber ?? (await this.nextSkuNumber(tx));
      if (!product.skuNumber) await tx.product.update({ where: { id: productId }, data: { skuNumber } });
      const last = await tx.productVariant.aggregate({ where: { productId }, _max: { variantNo: true } });
      const variantNo = Math.max(last._max.variantNo ?? 0, product.variants.length) + 1;
      if (variantNo > 1000) throw new BadRequestException('A product can have at most 1000 variants');
      const created = await tx.productVariant.create({
        data: { ...variantData({ ...dto, sku: dto.sku ?? `${skuNumber}-${variantNo}` }), variantNo, productId, avgCost: product.costPrice },
      });
      await this.openingStock(tx, [{ ...dto, unitCost: dto.unitCost ?? product.costPrice.toString(), id: created.id }], userId);
      return created;
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'ProductVariant', entityId: variant.id, after: dto });
    return variant;
  }

  async updateVariant(variantId: string, dto: UpdateVariantDto, userId: string) {
    const before = await this.prisma.productVariant.findUnique({ where: { id: variantId } });
    if (!before) throw new NotFoundException('Variant not found');
    const variant = await this.prisma.productVariant.update({ where: { id: variantId }, data: variantData(dto) });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'ProductVariant', entityId: variantId, before, after: variant });
    return variant;
  }

  async replaceImages(productId: string, images: ProductImageDto[], userId: string) {
    const product = await this.getProduct(productId);
    await this.prisma.$transaction([
      this.prisma.productImage.deleteMany({ where: { productId } }),
      this.prisma.productImage.createMany({ data: this.imageRows(productId, images, product.variants.map((v) => v.id)) }),
    ]);
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'ProductImages', entityId: productId, after: images });
    return this.getProduct(productId);
  }

  /** Books the initial pieces of new variants in the default warehouse, with their journal entry. */
  private async openingStock(tx: Tx, variants: (NewVariantDto & { id: string })[], userId: string) {
    const items = variants
      .filter((v) => (v.initialQuantity ?? 0) > 0)
      .map((v) => ({ variantId: v.id, quantity: v.initialQuantity!, unitCost: v.unitCost! }));
    if (!items.length) return;
    const warehouse = await tx.warehouse.findFirst({ where: { isDefault: true, isActive: true }, select: { id: true } });
    if (!warehouse) throw new BadRequestException('No default warehouse is configured');
    await this.inventory.adjustIn(tx, { warehouseId: warehouse.id, reason: AdjustmentReason.OPENING, items, note: 'Initial stock' }, userId);
  }

  private imageRows(productId: string, images: ProductImageDto[], variantIds: string[]) {
    if (images.length > 10) throw new BadRequestException('A product can have at most 10 images');
    return images.map((img, i) => {
      if (img.variantId && !variantIds.includes(img.variantId)) {
        throw new BadRequestException(`Image ${i + 1}: variant does not belong to this product`);
      }
      return { productId, url: img.url, variantId: img.variantId, color: img.color, altText: img.altText, sortOrder: img.sortOrder ?? i };
    });
  }

  /** Next free numeric product SKU, from 100 up to 100000. */
  private async nextSkuNumber(tx: Tx): Promise<number> {
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext('product_sku_number'))`;
    const [{ next }] = await tx.$queryRaw<{ next: number }[]>`SELECT (COALESCE(MAX(sku_number), 99) + 1)::int AS next FROM products`;
    if (next > 100000) throw new BadRequestException('All product SKU numbers (100-100000) are in use');
    return next;
  }

  private async defaultTaxRateId() {
    const rate = await this.prisma.taxRate.findFirst({ where: { isDefault: true, isActive: true }, select: { id: true } });
    return rate?.id ?? null;
  }

  private searchFilter(term: string): Prisma.ProductWhereInput {
    const contains = { contains: term, mode: 'insensitive' as const };
    return {
      OR: [
        { nameAr: contains },
        { nameEn: contains },
        ...(/^\d{3,6}$/.test(term) ? [{ skuNumber: Number(term) }] : []),
        { variants: { some: { OR: [{ sku: contains }, { barcode: term }] } } },
      ],
    };
  }

  // -------------------------------------------------------- store catalog ---

  /** Merchant pricing applies to signed-in merchant customers. */
  private buyer(customerId?: string): Promise<Buyer | null> {
    if (!customerId) return Promise.resolve(null);
    return this.prisma.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true, merchantDiscount: true } });
  }

  async storeProducts(q: StoreProductsQueryDto, customerId?: string) {
    const buyer = await this.buyer(customerId);
    let categoryIds: string[] | undefined;
    if (q.category) {
      const cat = await this.prisma.category.findUnique({ where: { slug: q.category } });
      if (!cat || !cat.isActive) return paginated([], 0, q);
      categoryIds = [cat.id, ...(await this.descendantIds(cat.id))];
    }
    const where: Prisma.ProductWhereInput = {
      status: ProductStatus.ACTIVE,
      visibility: { in: storeVisibility(buyer) },
      category: { isActive: true },
      categoryId: categoryIds ? { in: categoryIds } : undefined,
      ...(q.brand && { brand: { slug: q.brand, isActive: true } }),
      isFeatured: q.featured || undefined,
      ...(q.q && this.searchFilter(q.q)),
    };
    const orderBy: Prisma.ProductOrderByWithRelationInput =
      q.sort === 'price_asc' ? { basePrice: 'asc' } : q.sort === 'price_desc' ? { basePrice: 'desc' } : { createdAt: 'desc' };

    const [rows, total] = await Promise.all([
      this.prisma.product.findMany({ where, select: storeProductSelect, orderBy, skip: q.skip, take: q.pageSize }),
      this.prisma.product.count({ where }),
    ]);
    const promos = await activePromotions(this.prisma, rows.map((r) => r.id));
    return paginated(
      rows.map((r) => presentStoreProduct(r, promos, buyer)),
      total,
      q,
    );
  }

  async storeProduct(slug: string, customerId?: string) {
    const buyer = await this.buyer(customerId);
    const row = await this.prisma.product.findFirst({
      where: { slug, status: ProductStatus.ACTIVE, visibility: { in: storeVisibility(buyer) }, category: { isActive: true } },
      select: storeProductSelect,
    });
    if (!row) throw new NotFoundException('Product not found');
    return presentStoreProduct(row, await activePromotions(this.prisma, [row.id]), buyer);
  }
}

function variantData(dto: Partial<VariantDto> & { isActive?: boolean }) {
  return {
    sku: dto.sku,
    barcode: dto.barcode,
    size: dto.size,
    color: dto.color,
    colorHex: dto.colorHex,
    price: dto.price,
    weightGram: dto.weightGram,
    lowStockThreshold: dto.lowStockThreshold,
    isActive: dto.isActive,
  } as Prisma.ProductVariantCreateWithoutProductInput;
}

/** What the customer app may show: everything public, plus merchant-only items for merchants. */
export const storeVisibility = (buyer?: { isMerchant: boolean } | null): ProductVisibility[] =>
  buyer?.isMerchant ? [ProductVisibility.ALL, ProductVisibility.MERCHANTS] : [ProductVisibility.ALL];

const ARABIC: Record<string, string> = {
  ا: 'a', أ: 'a', إ: 'e', آ: 'a', ب: 'b', ت: 't', ث: 'th', ج: 'j', ح: 'h', خ: 'kh', د: 'd', ذ: 'th', ر: 'r', ز: 'z',
  س: 's', ش: 'sh', ص: 's', ض: 'd', ط: 't', ظ: 'z', ع: 'a', غ: 'gh', ف: 'f', ق: 'q', ك: 'k', ل: 'l', م: 'm', ن: 'n',
  ه: 'h', ة: 'a', و: 'w', ؤ: 'o', ي: 'y', ى: 'a', ئ: 'e', ء: '',
};

const latinBase = (name: string) => {
  const latin = [...name.toLowerCase()].map((c) => ARABIC[c] ?? c).join('');
  return latin.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60).replace(/-+$/, '');
};

/** URL name from the product name (Arabic transliterated) and its SKU number: "fstan-shra-100". */
export function productSlug(name: string, skuNumber: number) {
  const base = latinBase(name);
  return base ? `${base}-${skuNumber}` : `p-${skuNumber}`;
}

/** URL name from a name alone ("زارا" → "zara"); [fallback] plus a short random tail if nothing is left. */
export function textSlug(name: string, fallback: string) {
  return latinBase(name) || `${fallback}-${Math.random().toString(36).slice(2, 8)}`;
}
