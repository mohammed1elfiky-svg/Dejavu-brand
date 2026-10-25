import { Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';

export interface AnalyticsBatch {
  deviceId: string;
  sessions?: { id: string; startedAt: string; seconds: number }[];
  views?: { productId: string; seconds: number; at: string }[];
}

/** A session or product view longer than this is an app left open; it is capped. */
const MAX_SESSION_SECONDS = 4 * 3600;
const MAX_VIEW_SECONDS = 30 * 60;

const n = (v: unknown) => Number(v ?? 0);

/**
 * How shoppers use the customer app: time spent (in total and per customer), the products
 * they open and for how long, and what they search for.
 */
@Injectable()
export class AnalyticsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  private get tz() {
    return this.config.getOrThrow<string>('STORE_TIMEZONE');
  }

  /** Stores what the app collected; sent in batches, a session may come again with more seconds. */
  async ingest(batch: AnalyticsBatch, customerId?: string) {
    const deviceId = batch.deviceId.slice(0, 100);
    const now = Date.now();
    const sane = (iso: string) => {
      const t = new Date(iso).getTime();
      return Number.isNaN(t) || t > now + 5 * 60_000 || t < now - 30 * 24 * 3600_000 ? null : new Date(t);
    };
    let sessions = 0;
    for (const s of batch.sessions ?? []) {
      const startedAt = sane(s.startedAt);
      if (!startedAt) continue;
      const seconds = Math.min(Math.max(0, Math.round(s.seconds)), MAX_SESSION_SECONDS);
      const existing = await this.prisma.appSession.findUnique({ where: { id: s.id }, select: { deviceId: true, seconds: true } });
      if (existing && existing.deviceId !== deviceId) continue;
      await this.prisma.appSession.upsert({
        where: { id: s.id },
        update: { seconds: Math.max(existing?.seconds ?? 0, seconds), ...(customerId ? { customerId } : {}) },
        create: { id: s.id, deviceId, customerId: customerId ?? null, startedAt, seconds },
      });
      sessions++;
    }
    const views = (batch.views ?? [])
      .map((v) => ({ v, at: sane(v.at) }))
      .filter((x): x is { v: (typeof x)['v']; at: Date } => !!x.at);
    let stored = 0;
    if (views.length) {
      const known = new Set(
        (await this.prisma.product.findMany({ where: { id: { in: [...new Set(views.map((x) => x.v.productId))] } }, select: { id: true } })).map((p) => p.id),
      );
      const rows = views
        .filter((x) => known.has(x.v.productId))
        .map((x) => ({ productId: x.v.productId, deviceId, customerId: customerId ?? null, seconds: Math.min(Math.max(0, Math.round(x.v.seconds)), MAX_VIEW_SECONDS), viewedAt: x.at }));
      stored = (await this.prisma.productView.createMany({ data: rows })).count;
    }
    // Signed in now: what this phone did before signing in becomes this customer's.
    if (customerId) {
      await Promise.all([
        this.prisma.appSession.updateMany({ where: { deviceId, customerId: null }, data: { customerId } }),
        this.prisma.productView.updateMany({ where: { deviceId, customerId: null }, data: { customerId } }),
        this.prisma.searchLog.updateMany({ where: { deviceId, customerId: null }, data: { customerId } }),
      ]);
    }
    return { sessions, views: stored };
  }

  /** The "سلوك العملاء" report for [from, to). */
  async behavior(from: Date, to: Date, limit = 30) {
    const tz = this.tz;
    const [totals] = await this.prisma.$queryRaw<{ seconds: bigint; sessions: bigint; devices: bigint; customers: bigint }[]>`
      SELECT COALESCE(SUM(seconds), 0) AS seconds, COUNT(*) AS sessions,
             COUNT(DISTINCT device_id) AS devices, COUNT(DISTINCT customer_id) AS customers
      FROM app_sessions WHERE started_at >= ${from} AND started_at < ${to}`;
    const perDay = await this.prisma.$queryRaw<{ day: string; seconds: bigint; users: bigint; sessions: bigint }[]>`
      SELECT to_char((started_at AT TIME ZONE ${tz})::date, 'YYYY-MM-DD') AS day, SUM(seconds) AS seconds,
             COUNT(DISTINCT device_id) AS users, COUNT(*) AS sessions
      FROM app_sessions WHERE started_at >= ${from} AND started_at < ${to}
      GROUP BY 1 ORDER BY 1`;
    const hours = await this.prisma.$queryRaw<{ hour: number; sessions: bigint }[]>`
      SELECT EXTRACT(HOUR FROM started_at AT TIME ZONE ${tz})::int AS hour, COUNT(*) AS sessions
      FROM app_sessions WHERE started_at >= ${from} AND started_at < ${to}
      GROUP BY 1`;
    const customers = await this.prisma.$queryRaw<{ id: string; name: string; phone: string; seconds: bigint; sessions: bigint; last_seen: Date }[]>`
      SELECT c.id::text, c.name, c.phone, SUM(s.seconds) AS seconds, COUNT(*) AS sessions,
             MAX(s.started_at + make_interval(secs => s.seconds)) AS last_seen
      FROM app_sessions s JOIN customers c ON c.id = s.customer_id
      WHERE s.started_at >= ${from} AND s.started_at < ${to}
      GROUP BY c.id ORDER BY seconds DESC LIMIT ${limit}`;
    const products = await this.prisma.$queryRaw<
      { id: string; name: string; slug: string; image: string | null; views: bigint; viewers: bigint; avg_seconds: number; sold: bigint; buyers: bigint; on_hand: bigint }[]
    >`
      WITH v AS (
        SELECT product_id, COUNT(*) AS views, COUNT(DISTINCT device_id) AS viewers, AVG(seconds)::float AS avg_seconds
        FROM product_views WHERE viewed_at >= ${from} AND viewed_at < ${to} GROUP BY product_id
      ), s AS (
        SELECT pv.product_id, SUM(oi.quantity) AS sold, COUNT(DISTINCT o.customer_id) AS buyers
        FROM order_items oi JOIN orders o ON o.id = oi.order_id AND o.status <> 'CANCELLED'
        JOIN product_variants pv ON pv.id = oi.variant_id
        WHERE o.placed_at >= ${from} AND o.placed_at < ${to} GROUP BY pv.product_id
      ), st AS (
        SELECT pv.product_id, SUM(sl.on_hand - sl.reserved) AS on_hand
        FROM stock_levels sl JOIN product_variants pv ON pv.id = sl.variant_id AND pv.is_active GROUP BY pv.product_id
      )
      SELECT p.id::text, p.name_ar AS name, p.slug,
             (SELECT url FROM product_images i WHERE i.product_id = p.id ORDER BY sort_order LIMIT 1) AS image,
             v.views, v.viewers, v.avg_seconds, COALESCE(s.sold, 0) AS sold, COALESCE(s.buyers, 0) AS buyers, COALESCE(st.on_hand, 0) AS on_hand
      FROM v JOIN products p ON p.id = v.product_id
      LEFT JOIN s ON s.product_id = v.product_id
      LEFT JOIN st ON st.product_id = v.product_id
      ORDER BY v.views DESC LIMIT 500`;
    const searches = await this.prisma.$queryRaw<{ query: string; count: bigint; results: number }[]>`
      SELECT query, COUNT(*) AS count, ROUND(AVG(results))::int AS results
      FROM search_logs WHERE created_at >= ${from} AND created_at < ${to}
      GROUP BY query ORDER BY count DESC LIMIT 200`;

    const product = (p: (typeof products)[number]) => ({
      productId: p.id,
      name: p.name,
      slug: p.slug,
      imageUrl: p.image,
      views: n(p.views),
      viewers: n(p.viewers),
      avgSeconds: Math.round(p.avg_seconds ?? 0),
      sold: n(p.sold),
      /** Buyers per 100 people who opened the product. */
      conversion: n(p.viewers) ? Math.round((n(p.buyers) / n(p.viewers)) * 1000) / 10 : 0,
      available: n(p.on_hand),
    });
    const all = products.map(product);
    const seconds = n(totals.seconds);
    const sessions = n(totals.sessions);
    return {
      from,
      to,
      totals: { seconds, sessions, devices: n(totals.devices), customers: n(totals.customers), avgSessionSeconds: sessions ? Math.round(seconds / sessions) : 0 },
      perDay: perDay.map((d) => ({ date: d.day, seconds: n(d.seconds), users: n(d.users), sessions: n(d.sessions) })),
      hours: Array.from({ length: 24 }, (_, h) => ({ hour: h, sessions: n(hours.find((x) => x.hour === h)?.sessions) })),
      topCustomers: customers.map((c) => ({ customer: { id: c.id, name: c.name, phone: c.phone }, seconds: n(c.seconds), sessions: n(c.sessions), lastSeen: c.last_seen })),
      topViewed: all.slice(0, limit),
      /** Opened a lot but nobody bought: price, photos or sizes may be the reason. */
      viewedNotBought: all.filter((p) => p.sold === 0 && p.views >= 3).slice(0, limit),
      /** Wanted but sold out: worth ordering again. */
      viewedOutOfStock: all.filter((p) => p.available <= 0).slice(0, limit),
      topSearches: searches.filter((s) => s.results > 0).slice(0, limit).map((s) => ({ query: s.query, count: n(s.count), results: s.results })),
      /** Searched for and found nothing: products to stock. */
      noResultSearches: searches.filter((s) => s.results === 0).slice(0, limit).map((s) => ({ query: s.query, count: n(s.count) })),
    };
  }

  /** Time one customer spent in the app, and what they looked at most. */
  async customerUsage(customerId: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { id: true } });
    if (!c) throw new NotFoundException('Customer not found');
    const since30 = new Date(Date.now() - 30 * 24 * 3600_000);
    const [all, month, last, viewed] = await Promise.all([
      this.prisma.appSession.aggregate({ where: { customerId }, _sum: { seconds: true }, _count: { _all: true } }),
      this.prisma.appSession.aggregate({ where: { customerId, startedAt: { gte: since30 } }, _sum: { seconds: true }, _count: { _all: true } }),
      this.prisma.appSession.findFirst({ where: { customerId }, orderBy: { startedAt: 'desc' }, select: { startedAt: true, seconds: true } }),
      this.prisma.productView.groupBy({ by: ['productId'], where: { customerId }, _count: { _all: true }, _sum: { seconds: true }, orderBy: { _count: { productId: 'desc' } }, take: 10 }),
    ]);
    const names = new Map(
      (await this.prisma.product.findMany({ where: { id: { in: viewed.map((v) => v.productId) } }, select: { id: true, nameAr: true, slug: true } })).map((p) => [p.id, p]),
    );
    return {
      seconds: all._sum.seconds ?? 0,
      sessions: all._count._all,
      last30Days: { seconds: month._sum.seconds ?? 0, sessions: month._count._all },
      lastSeen: last ? new Date(last.startedAt.getTime() + last.seconds * 1000) : null,
      mostViewed: viewed.map((v) => ({ productId: v.productId, name: names.get(v.productId)?.nameAr ?? '', views: v._count._all, seconds: v._sum.seconds ?? 0 })),
    };
  }
}
