import { BadRequestException, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { NotificationType, ProductStatus } from '../generated/prisma/client';
import { CatalogService } from '../catalog/catalog.service';
import { activePromotions, promotedPrice } from '../catalog/promotions';
import { formatAmount } from '../common/currency.service';
import { Decimal, dec } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';

/** How the customer app behaves: sign-in before browsing, Google ads. */
export interface StoreAppSettings {
  requireLogin: boolean;
  ads: {
    enabled: boolean;
    /** AdMob ad unit ids (ca-app-pub-…/…); empty = Google's test ads. */
    bannerUnitId: string;
    interstitialUnitId: string;
    /** Where banners show. */
    homeBanner: boolean;
    productBanner: boolean;
    /** A full-screen ad after every N product pages; 0 = never. */
    interstitialEvery: number;
  };
}

/** The "عن المتجر" page. */
export interface StoreAbout {
  name: string;
  about: string;
  address: string;
  /** Google Maps link, or coordinates. */
  mapUrl: string;
  latitude: number | null;
  longitude: number | null;
  hours: string;
  returnPolicy: string;
  phones: string[];
  whatsapp: string;
  instagram: string;
  facebook: string;
  tiktok: string;
}

const APP_KEY = 'store.app';
const ABOUT_KEY = 'store.about';

const APP_DEFAULTS: StoreAppSettings = {
  requireLogin: false,
  ads: { enabled: false, bannerUnitId: '', interstitialUnitId: '', homeBanner: true, productBanner: true, interstitialEvery: 0 },
};

const ABOUT_DEFAULTS: StoreAbout = {
  name: 'Dejavu',
  about: '',
  address: '',
  mapUrl: '',
  latitude: null,
  longitude: null,
  hours: '',
  returnPolicy: '',
  phones: [],
  whatsapp: '',
  instagram: '',
  facebook: '',
  tiktok: '',
};

/** At or below this many pieces left a wish-listed product gets an "almost gone" alert. */
const FEW_LEFT = 3;
/** No second "almost gone" alert for the same product within this many days. */
const LOW_STOCK_EVERY_DAYS = 7;
/** Ask for a review this long after delivery… */
const REVIEW_AFTER_HOURS = 24;
/** …but not for orders delivered longer ago than this. */
const REVIEW_WITHIN_DAYS = 14;

/**
 * The customer app's extras: settings the owner controls (sign-in, ads, the "about" page),
 * the wish list and its alerts (price dropped, almost sold out), suggestions for the cart,
 * popular searches, and the "how was it?" review prompt after delivery.
 */
@Injectable()
export class StorefrontService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Storefront');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly catalog: CatalogService,
    private readonly notifications: NotificationsService,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.run().catch((e) => this.log.error(e)), 10 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  async run() {
    return { wishlist: await this.checkWishlist(), reviews: await this.promptReviews() };
  }

  // ------------------------------------------------------------- settings ---

  async appSettings(): Promise<StoreAppSettings> {
    const row = await this.prisma.setting.findUnique({ where: { key: APP_KEY } });
    const saved = (row?.value ?? {}) as Partial<StoreAppSettings>;
    return { ...APP_DEFAULTS, ...saved, ads: { ...APP_DEFAULTS.ads, ...(saved.ads ?? {}) } };
  }

  async setAppSettings(dto: { requireLogin?: boolean; ads?: Partial<StoreAppSettings['ads']> }) {
    const current = await this.appSettings();
    const value: StoreAppSettings = { ...current, ...(dto.requireLogin !== undefined ? { requireLogin: dto.requireLogin } : {}), ads: { ...current.ads, ...(dto.ads ?? {}) } };
    for (const id of [value.ads.bannerUnitId, value.ads.interstitialUnitId]) {
      if (id && !/^ca-app-pub-\d{16}\/\d{10}$/.test(id)) throw new BadRequestException('رقم الإعلان يجب أن يكون بصيغة ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX');
    }
    await this.prisma.setting.upsert({ where: { key: APP_KEY }, update: { value: value as object }, create: { key: APP_KEY, value: value as object } });
    this.catalog.resetGate();
    return value;
  }

  async about(): Promise<StoreAbout> {
    const row = await this.prisma.setting.findUnique({ where: { key: ABOUT_KEY } });
    return { ...ABOUT_DEFAULTS, ...((row?.value ?? {}) as Partial<StoreAbout>) };
  }

  async setAbout(dto: Partial<StoreAbout>) {
    const value = { ...(await this.about()), ...dto };
    value.phones = (value.phones ?? []).map((p) => p.trim()).filter(Boolean);
    await this.prisma.setting.upsert({ where: { key: ABOUT_KEY }, update: { value: value as object }, create: { key: ABOUT_KEY, value: value as object } });
    return value;
  }

  /** What the customer app needs at start: sign-in rule, ads, whether the assistant is on. */
  async config() {
    const [app, chat] = await Promise.all([this.appSettings(), this.prisma.setting.findUnique({ where: { key: 'chat.settings' } })]);
    const c = (chat?.value ?? {}) as { enabled?: boolean; welcome?: string };
    return {
      requireLogin: app.requireLogin,
      ads: app.ads,
      chat: { enabled: !!c.enabled && !!process.env.GEMINI_API_KEY, welcome: c.welcome ?? '' },
    };
  }

  // ------------------------------------------------------------- wishlist ---

  async wishlist(customerId: string) {
    const rows = await this.prisma.wishlistItem.findMany({ where: { customerId }, orderBy: { createdAt: 'desc' }, select: { productId: true } });
    return this.catalog.storeProductsByIds(rows.map((r) => r.productId), customerId);
  }

  async wishlistIds(customerId: string) {
    const rows = await this.prisma.wishlistItem.findMany({ where: { customerId }, select: { productId: true } });
    return rows.map((r) => r.productId);
  }

  async addToWishlist(customerId: string, productId: string) {
    const p = await this.prisma.product.findFirst({ where: { id: productId, status: ProductStatus.ACTIVE }, select: { id: true } });
    if (!p) throw new NotFoundException('Product not found');
    const price = (await this.currentPrices([productId])).get(productId)?.price ?? null;
    await this.prisma.wishlistItem.upsert({
      where: { customerId_productId: { customerId, productId } },
      update: {},
      create: { customerId, productId, lastPrice: price },
    });
    return this.wishlistIds(customerId);
  }

  async removeFromWishlist(customerId: string, productId: string) {
    await this.prisma.wishlistItem.deleteMany({ where: { customerId, productId } });
    return this.wishlistIds(customerId);
  }

  /** Lowest price a shopper pays now (promotions applied) and the pieces left, per product. */
  private async currentPrices(productIds: string[]) {
    const products = await this.prisma.product.findMany({
      where: { id: { in: productIds } },
      select: {
        id: true,
        basePrice: true,
        variants: {
          where: { isActive: true },
          select: { price: true, stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } } },
        },
      },
    });
    const promos = await activePromotions(this.prisma, productIds);
    const out = new Map<string, { price: Decimal; left: number }>();
    for (const p of products) {
      const promo = promos.get(p.id);
      const prices = p.variants.map((v) => promotedPrice(v.price ?? p.basePrice, promo));
      const price = prices.length ? prices.reduce((a, b) => (b.lt(a) ? b : a)) : promotedPrice(p.basePrice, promo);
      const left = p.variants.reduce((n, v) => n + v.stockLevels.reduce((m, s) => m + Math.max(0, s.onHand - s.reserved), 0), 0);
      out.set(p.id, { price, left });
    }
    return out;
  }

  /**
   * Wish-list alerts: the price went down since the customer last heard (or saved it), or only
   * a few pieces are left. Returns how many alerts were sent.
   */
  async checkWishlist(now = new Date()) {
    const items = await this.prisma.wishlistItem.findMany({
      where: { product: { status: ProductStatus.ACTIVE }, customer: { status: 'ACTIVE' } },
      select: { customerId: true, productId: true, lastPrice: true, lowStockNotifiedAt: true, product: { select: { nameAr: true, slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } },
      take: 5000,
    });
    if (!items.length) return 0;
    const prices = await this.currentPrices([...new Set(items.map((i) => i.productId))]);
    const quietSince = new Date(now.getTime() - LOW_STOCK_EVERY_DAYS * 24 * 3600_000);
    let sent = 0;
    for (const it of items) {
      const cur = prices.get(it.productId);
      if (!cur) continue;
      const where = { customerId_productId: { customerId: it.customerId, productId: it.productId } };
      const image = it.product.images[0]?.url;
      const data: Record<string, string> = { product: it.product.slug, ...(image ? { image } : {}) };
      const last = it.lastPrice === null ? null : dec(it.lastPrice);
      if (last && cur.price.lt(last)) {
        await this.prisma.$transaction(async (tx) => {
          await this.notifications.notifyCustomer(tx, it.customerId, {
            type: NotificationType.PROMOTION,
            title: 'نزل السعر ❤️',
            body: `${it.product.nameAr} في مفضلتك صار بـ ${formatAmount(cur.price)} بدل ${formatAmount(last)}`,
            data,
          });
          await tx.wishlistItem.update({ where, data: { lastPrice: cur.price } });
        });
        sent++;
      } else if (!last || !cur.price.eq(last)) {
        await this.prisma.wishlistItem.update({ where, data: { lastPrice: cur.price } });
      }
      if (cur.left > 0 && cur.left <= FEW_LEFT && (!it.lowStockNotifiedAt || it.lowStockNotifiedAt < quietSince)) {
        await this.prisma.$transaction(async (tx) => {
          await this.notifications.notifyCustomer(tx, it.customerId, {
            type: NotificationType.LOW_STOCK,
            title: 'قربت تخلص ⏳',
            body: `${it.product.nameAr} في مفضلتك: باقي ${cur.left === 1 ? 'قطعة واحدة' : cur.left === 2 ? 'قطعتان' : `${cur.left} قطع`} فقط`,
            data,
          });
          await tx.wishlistItem.update({ where, data: { lowStockNotifiedAt: now } });
        });
        sent++;
      }
    }
    return sent;
  }

  // ---------------------------------------------------------- suggestions ---

  /** "قد يعجبك أيضاً" under the cart: bought together with what is in it, else best rated in its categories. */
  async cartSuggestions(customerId: string, limit = 10) {
    const cart = await this.prisma.cart.findUnique({ where: { customerId }, select: { items: { select: { variant: { select: { productId: true, product: { select: { categoryId: true } } } } } } } });
    const inCart = [...new Set((cart?.items ?? []).map((i) => i.variant.productId))];
    if (!inCart.length) return [];
    const ids = await this.catalog.boughtWith(inCart, limit);
    if (ids.length < limit) {
      const categories = [...new Set(cart!.items.map((i) => i.variant.product.categoryId))];
      const more = await this.prisma.product.findMany({
        where: {
          id: { notIn: [...inCart, ...ids] },
          status: ProductStatus.ACTIVE,
          categoryId: { in: categories },
          variants: { some: { isActive: true, stockLevels: { some: { onHand: { gt: 0 } } } } },
        },
        orderBy: [{ ratingAvg: 'desc' }, { createdAt: 'desc' }],
        take: limit - ids.length,
        select: { id: true },
      });
      ids.push(...more.map((m) => m.id));
    }
    return this.catalog.storeProductsByIds(ids, customerId);
  }

  /** The most searched words of the last 30 days that found something. */
  async popularSearches(limit = 10) {
    const since = new Date(Date.now() - 30 * 24 * 3600_000);
    const rows = await this.prisma.searchLog.groupBy({
      by: ['query'],
      where: { createdAt: { gte: since }, results: { gt: 0 } },
      _count: { _all: true },
      orderBy: { _count: { query: 'desc' } },
      take: limit,
    });
    return rows.map((r) => r.query);
  }

  // -------------------------------------------------------------- reviews ---

  /** "كيف كانت مشترياتك؟" a day after delivery, once per order. Returns how many were asked. */
  async promptReviews(now = new Date()) {
    const before = new Date(now.getTime() - REVIEW_AFTER_HOURS * 3600_000);
    const after = new Date(now.getTime() - REVIEW_WITHIN_DAYS * 24 * 3600_000);
    const orders = await this.prisma.order.findMany({
      where: {
        reviewPromptedAt: null,
        status: { in: ['DELIVERED', 'COMPLETED'] },
        customer: { status: 'ACTIVE', passwordHash: { not: null }, code: { not: 'CUS-WALKIN' } },
        OR: [{ deliveredAt: { lte: before, gte: after } }, { deliveredAt: null, placedAt: { lte: before, gte: after } }],
      },
      take: 200,
      select: {
        id: true,
        number: true,
        customerId: true,
        items: { take: 1, select: { productName: true, variant: { select: { product: { select: { slug: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } } } } },
      },
    });
    let sent = 0;
    for (const o of orders) {
      const first = o.items[0];
      await this.prisma.$transaction(async (tx) => {
        if (first) {
          const image = first.variant.product.images[0]?.url;
          await this.notifications.notifyCustomer(tx, o.customerId, {
            type: NotificationType.ORDER_STATUS,
            title: 'كيف كانت مشترياتك؟ ⭐',
            body: `قيّمي ${first.productName} وساعدي غيرك في الاختيار`,
            data: { product: first.variant.product.slug, review: o.number, ...(image ? { image } : {}) },
          });
          sent++;
        }
        await tx.order.update({ where: { id: o.id }, data: { reviewPromptedAt: now } });
      });
    }
    return sent;
  }
}
