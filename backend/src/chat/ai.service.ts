import { BadRequestException, HttpException, HttpStatus, Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { formatAmount } from '../common/currency.service';
import { dec, money } from '../common/utils/money';
import { Env } from '../config/env';
import { OwnerAlertsService } from '../notifications/owner-alerts.service';
import { PrismaService } from '../prisma/prisma.service';
import { ChatService } from './chat.service';
import { GeminiClient } from './gemini.client';

const WEEKLY_KEY = 'ai.weekly';

export interface WeeklyStats {
  from: string;
  to: string;
  revenue: string;
  previousRevenue: string;
  changePercent: number | null;
  orders: number;
  previousOrders: number;
  posOrders: number;
  appOrders: number;
  newCustomers: number;
  returns: number;
  topProducts: { name: string; units: number; revenue: string; available: number }[];
  /** Best sellers with nothing (or almost nothing) left. */
  runningOut: { name: string; size: string | null; color: string | null; available: number; sold: number }[];
  mostViewedNotBought: { name: string; views: number }[];
  noResultSearches: { query: string; count: number }[];
}

/**
 * The owner's helpers on Gemini: a product draft from a photo, wording for campaigns and a
 * weekly summary of the business with suggestions.
 */
const PRODUCT_PROMPT_KEY = 'ai.productPrompt';
const DEFAULT_PRODUCT_PROMPT = 'أنت تساعد متجر ديجافو للأحذية والشنط النسائية في إدخال المنتجات. أجب بالعربية.';

@Injectable()
export class AiService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('AI');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly gemini: GeminiClient,
    private readonly chat: ChatService,
    private readonly ownerAlerts: OwnerAlertsService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    // Saturday morning (store time): the summary of the week that ended.
    this.timer = setInterval(() => void this.weeklyIfDue().catch((e) => this.log.error(e)), 60 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  private async model() {
    return (await this.chat.settings()).model;
  }

  private requireKey() {
    if (!this.gemini.configured) throw new HttpException('أضف مفتاح Gemini (GEMINI_API_KEY) في إعدادات الخادم لاستخدام الذكاء الاصطناعي', HttpStatus.SERVICE_UNAVAILABLE);
  }

  private parse<T>(raw: string): T {
    const text = raw.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '').trim();
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new HttpException('لم يفهم الذكاء الاصطناعي الصورة، جرّب صورة أوضح', HttpStatus.BAD_GATEWAY);
    }
  }

  // ------------------------------------------------------- product draft ---

  /** The owner's own words for drafting products (style, what to mention); the JSON shape stays fixed. */
  async productPrompt() {
    const row = await this.prisma.setting.findUnique({ where: { key: PRODUCT_PROMPT_KEY } });
    const saved = (row?.value as { prompt?: string } | null)?.prompt;
    return { prompt: saved ?? DEFAULT_PRODUCT_PROMPT, isDefault: !saved, defaultPrompt: DEFAULT_PRODUCT_PROMPT };
  }

  /** "" restores the default. */
  async setProductPrompt(prompt: string) {
    const value = prompt.trim();
    if (!value) await this.prisma.setting.deleteMany({ where: { key: PRODUCT_PROMPT_KEY } });
    else await this.prisma.setting.upsert({ where: { key: PRODUCT_PROMPT_KEY }, update: { value: { prompt: value } }, create: { key: PRODUCT_PROMPT_KEY, value: { prompt: value } } });
    return this.productPrompt();
  }

  /** Name, description, category, brand and colour suggested from a product photo. */
  async productDraft(imageFileId: string, prompt?: string) {
    this.requireKey();
    const file = await this.prisma.storedFile.findUnique({ where: { id: imageFileId }, select: { mimeType: true, data: true } });
    if (!file || !file.mimeType.startsWith('image/')) throw new NotFoundException('Image not found');
    const [categories, brands] = await Promise.all([
      this.prisma.category.findMany({ where: { isActive: true }, select: { id: true, nameAr: true } }),
      this.prisma.brand.findMany({ where: { isActive: true }, select: { id: true, name: true } }),
    ]);
    const system = `${prompt?.trim() || (await this.productPrompt()).prompt}\nأجب بصيغة JSON فقط.`;
    const ask = `صف المنتج في الصورة وأعد JSON بهذا الشكل:
{"nameAr": "اسم قصير وجذاب (3-6 كلمات)", "descriptionAr": "وصف تسويقي من جملتين أو ثلاث: الخامة، الشكل، المناسبات", "categoryId": "من القائمة أو null", "brandId": "من القائمة إن ظهر الشعار أو null", "color": "اللون بالعربي", "colorHex": "#RRGGBB", "kind": "نوع المنتج مثل كعب، صندل، شنطة يد", "heelHeightCm": "رقم أو null", "tags": ["كلمات بحث"]}
الأقسام: ${JSON.stringify(categories)}
الماركات: ${JSON.stringify(brands)}`;
    const raw = await this.gemini.text(await this.model(), system, [{ inline_data: { mime_type: file.mimeType, data: Buffer.from(file.data).toString('base64') } }, { text: ask }], true);
    const d = this.parse<Record<string, unknown>>(raw);
    const str = (v: unknown) => (typeof v === 'string' && v.trim() && v !== 'null' ? v.trim() : null);
    const categoryId = str(d.categoryId);
    const brandId = str(d.brandId);
    const heel = d.heelHeightCm === null || d.heelHeightCm === undefined ? null : Number(d.heelHeightCm);
    return {
      nameAr: str(d.nameAr) ?? '',
      descriptionAr: str(d.descriptionAr) ?? '',
      categoryId: categories.some((c) => c.id === categoryId) ? categoryId : null,
      brandId: brands.some((b) => b.id === brandId) ? brandId : null,
      color: str(d.color),
      colorHex: str(d.colorHex)?.match(/^#[0-9a-fA-F]{6}$/) ? str(d.colorHex) : null,
      kind: str(d.kind),
      heelHeightCm: heel !== null && Number.isFinite(heel) && heel > 0 && heel < 30 ? heel : null,
      tags: Array.isArray(d.tags) ? d.tags.filter((t): t is string => typeof t === 'string').slice(0, 10) : [],
    };
  }

  // ------------------------------------------------------ marketing text ---

  /** Three ready-to-send wordings for a notification or SMS about [idea]. */
  async marketingText(dto: { idea: string; channel: 'PUSH' | 'SMS' }) {
    this.requireKey();
    const idea = dto.idea.trim();
    if (idea.length < 3) throw new BadRequestException('اكتب فكرة العرض');
    const limit = dto.channel === 'SMS' ? 'النص كله أقل من 150 حرفاً (رسالة SMS واحدة).' : 'العنوان أقل من 40 حرفاً والنص أقل من 120 حرفاً، ويمكن استخدام إيموجي واحد أو اثنين.';
    const system = 'أنت كاتب إعلانات لمتجر ديجافو للأحذية والشنط النسائية في اليمن. اكتب بالعربية بأسلوب جذاب ومهذب وبسيط.';
    const raw = await this.gemini.text(
      await this.model(),
      system,
      [{ text: `اكتب 3 صيغ مختلفة لرسالة عن: "${idea}". ${limit}\nأعد JSON فقط: {"suggestions": [{"title": "...", "body": "..."}]}` }],
      true,
    );
    const d = this.parse<{ suggestions?: { title?: string; body?: string }[] }>(raw);
    return {
      suggestions: (d.suggestions ?? [])
        .map((s) => ({ title: (s.title ?? '').trim(), body: (s.body ?? '').trim() }))
        .filter((s) => s.body)
        .slice(0, 3),
    };
  }

  // ------------------------------------------------------ weekly summary ---

  /** Figures of the 7 days before [end] compared with the 7 days before that. */
  async weeklyStats(end = new Date()): Promise<WeeklyStats> {
    const day = 24 * 3600_000;
    const from = new Date(end.getTime() - 7 * day);
    const prevFrom = new Date(end.getTime() - 14 * day);
    const sales = (a: Date, b: Date) =>
      this.prisma.order.aggregate({ where: { status: { not: 'CANCELLED' }, placedAt: { gte: a, lt: b } }, _sum: { grandTotal: true }, _count: { _all: true } });
    const [now, before, pos, newCustomers, returns] = await Promise.all([
      sales(from, end),
      sales(prevFrom, from),
      this.prisma.order.count({ where: { status: { not: 'CANCELLED' }, channel: 'POS', placedAt: { gte: from, lt: end } } }),
      this.prisma.customer.count({ where: { createdAt: { gte: from, lt: end } } }),
      this.prisma.salesReturn.count({ where: { requestedAt: { gte: from, lt: end } } }),
    ]);
    const top = await this.prisma.$queryRaw<{ id: string; name: string; units: number; revenue: string; available: number }[]>`
      SELECT p.id::text, p.name_ar AS name, SUM(oi.quantity)::int AS units, SUM(oi.line_total)::text AS revenue,
             COALESCE((SELECT SUM(sl.on_hand - sl.reserved) FROM stock_levels sl JOIN product_variants pv ON pv.id = sl.variant_id WHERE pv.product_id = p.id), 0)::int AS available
      FROM order_items oi JOIN orders o ON o.id = oi.order_id AND o.status <> 'CANCELLED' AND o.placed_at >= ${from} AND o.placed_at < ${end}
      JOIN product_variants v ON v.id = oi.variant_id JOIN products p ON p.id = v.product_id
      GROUP BY p.id ORDER BY 3 DESC LIMIT 5`;
    const runningOut = await this.prisma.$queryRaw<{ name: string; size: string | null; color: string | null; available: number; sold: number }[]>`
      SELECT p.name_ar AS name, v.size, v.color,
             COALESCE((SELECT SUM(sl.on_hand - sl.reserved) FROM stock_levels sl WHERE sl.variant_id = v.id), 0)::int AS available,
             SUM(oi.quantity)::int AS sold
      FROM order_items oi JOIN orders o ON o.id = oi.order_id AND o.status <> 'CANCELLED' AND o.placed_at >= ${prevFrom} AND o.placed_at < ${end}
      JOIN product_variants v ON v.id = oi.variant_id JOIN products p ON p.id = v.product_id
      GROUP BY v.id, p.name_ar
      HAVING COALESCE((SELECT SUM(sl.on_hand - sl.reserved) FROM stock_levels sl WHERE sl.variant_id = v.id), 0) <= 2
      ORDER BY 5 DESC LIMIT 8`;
    const viewed = await this.prisma.$queryRaw<{ name: string; views: number }[]>`
      SELECT p.name_ar AS name, COUNT(*)::int AS views FROM product_views pv JOIN products p ON p.id = pv.product_id
      WHERE pv.viewed_at >= ${from} AND pv.viewed_at < ${end}
        AND NOT EXISTS (SELECT 1 FROM order_items oi JOIN orders o ON o.id = oi.order_id JOIN product_variants v ON v.id = oi.variant_id
                        WHERE v.product_id = p.id AND o.placed_at >= ${from} AND o.status <> 'CANCELLED')
      GROUP BY p.id, p.name_ar HAVING COUNT(*) >= 3 ORDER BY 2 DESC LIMIT 5`;
    const searches = await this.prisma.searchLog.groupBy({
      by: ['query'],
      where: { createdAt: { gte: from, lt: end }, results: 0 },
      _count: { _all: true },
      orderBy: { _count: { query: 'desc' } },
      take: 5,
    });
    const revenue = money(dec(now._sum.grandTotal ?? 0));
    const previous = money(dec(before._sum.grandTotal ?? 0));
    return {
      from: from.toISOString(),
      to: end.toISOString(),
      revenue: revenue.toFixed(2),
      previousRevenue: previous.toFixed(2),
      changePercent: previous.isZero() ? null : Math.round(revenue.sub(previous).div(previous).mul(1000).toNumber()) / 10,
      orders: now._count._all,
      previousOrders: before._count._all,
      posOrders: pos,
      appOrders: now._count._all - pos,
      newCustomers,
      returns,
      topProducts: top.map((t) => ({ name: t.name, units: t.units, revenue: money(dec(t.revenue)).toFixed(2), available: t.available })),
      runningOut,
      mostViewedNotBought: viewed,
      noResultSearches: searches.map((s) => ({ query: s.query, count: s._count._all })),
    };
  }

  /** The summary text: written by Gemini when configured, otherwise put together from the figures. */
  private async write(stats: WeeklyStats) {
    if (this.gemini.configured) {
      try {
        const text = await this.gemini.text(
          await this.model(),
          'أنت مستشار تجاري لمتجر ديجافو للأحذية والشنط النسائية في اليمن. المبالغ بالريال السعودي.',
          [
            {
              text: `هذه أرقام الأسبوع الماضي مقارنة بالأسبوع الذي قبله (JSON):\n${JSON.stringify(stats)}\n
اكتب للمالك ملخصاً قصيراً بالعربية (حتى 900 حرف): ماذا حدث في المبيعات ولماذا على الأرجح (اربط الأسباب بالأرقام مثل نفاد مقاس من منتج مطلوب)، ثم 3 اقتراحات عملية مرقّمة. بدون مقدمات وبدون جداول.`,
            },
          ],
        );
        if (text) return text;
      } catch (e) {
        this.log.warn(`Weekly summary by Gemini failed: ${(e as Error).message}`);
      }
    }
    const lines = [
      `المبيعات: ${formatAmount(stats.revenue)} (${stats.orders} فاتورة)${stats.changePercent === null ? '' : ` — ${stats.changePercent >= 0 ? 'ارتفاع' : 'انخفاض'} ${Math.abs(stats.changePercent)}% عن الأسبوع السابق`}.`,
      `من المحل ${stats.posOrders} ومن التطبيق ${stats.appOrders}. عملاء جدد: ${stats.newCustomers}. مرتجعات: ${stats.returns}.`,
    ];
    if (stats.topProducts.length) lines.push(`الأكثر مبيعاً: ${stats.topProducts.map((t) => `${t.name} (${t.units})`).join('، ')}.`);
    if (stats.runningOut.length) lines.push(`قارب على النفاد: ${stats.runningOut.map((r) => [r.name, r.size].filter(Boolean).join(' ')).join('، ')}.`);
    if (stats.noResultSearches.length) lines.push(`بحث عنه العملاء ولم يجدوه: ${stats.noResultSearches.map((s) => s.query).join('، ')}.`);
    return lines.join('\n');
  }

  /** The latest summary (made now when there is none or [refresh]). */
  async weekly(refresh = false, at = new Date()) {
    if (!refresh) {
      const saved = await this.prisma.setting.findUnique({ where: { key: WEEKLY_KEY } });
      if (saved) return saved.value as unknown as { text: string; stats: WeeklyStats; generatedAt: string; byAi: boolean };
    }
    const stats = await this.weeklyStats(at);
    const text = await this.write(stats);
    const value = { text, stats, generatedAt: at.toISOString(), byAi: this.gemini.configured };
    await this.prisma.setting.upsert({ where: { key: WEEKLY_KEY }, update: { value: value as object }, create: { key: WEEKLY_KEY, value: value as object } });
    return value;
  }

  /** Saturday from 9 in the morning (store time), once a week: make it and tell the owner. */
  async weeklyIfDue(now = new Date()) {
    const tz = this.config.getOrThrow<string>('STORE_TIMEZONE');
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'short', hour: 'numeric', hour12: false }).formatToParts(now);
    const weekday = parts.find((p) => p.type === 'weekday')?.value;
    const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? 0);
    if (weekday !== 'Sat' || hour < 9) return false;
    const saved = await this.prisma.setting.findUnique({ where: { key: WEEKLY_KEY } });
    const last = saved ? new Date((saved.value as { generatedAt: string }).generatedAt) : null;
    if (last && now.getTime() - last.getTime() < 6 * 24 * 3600_000) return false;
    const w = await this.weekly(true, now);
    await this.ownerAlerts.raise('WEEKLY', 'ملخص الأسبوع 📊', w.text, { weekly: '1' });
    return true;
  }
}
