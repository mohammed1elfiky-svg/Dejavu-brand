import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { OrderChannel, OrderStatus, ShiftStatus, SmsStatus, TopUpStatus } from '../generated/prisma/client';
import { AuthPrincipal } from '../common/decorators/auth.decorators';
import { toCurrency } from '../common/currency.service';
import { businessDate } from '../common/utils/dates';
import { money } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';

/** What the admin app's home and menus need at a glance: one search box, counters, a sales chart. */
@Injectable()
export class HomeService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
  ) {}

  /**
   * Finds products (name, SKU, barcode), customers (name, phone, code), sales and orders
   * (number), suppliers and purchase orders — only the kinds the user may see.
   */
  async search(q: string, u: AuthPrincipal) {
    const term = q.trim();
    const can = (p: string) => u.permissions.includes(p);
    const like = { contains: term, mode: 'insensitive' as const };
    const digits = term.replace(/[\s-]/g, '');
    const take = 8;
    const [products, customers, orders, suppliers, purchases] = await Promise.all([
      can('products.read')
        ? this.prisma.product.findMany({
            where: {
              OR: [
                { nameAr: like },
                { nameEn: like },
                ...(/^\d+$/.test(digits) ? [{ skuNumber: Number(digits) }] : []),
                { variants: { some: { OR: [{ sku: { equals: term, mode: 'insensitive' } }, { barcode: term }] } } },
              ],
            },
            take,
            orderBy: { updatedAt: 'desc' },
            select: { id: true, nameAr: true, skuNumber: true, status: true, basePrice: true, images: { orderBy: { sortOrder: 'asc' }, take: 1, select: { url: true } } },
          })
        : [],
      can('customers.read')
        ? this.prisma.customer.findMany({
            where: { code: { not: 'CUS-WALKIN' }, OR: [{ name: like }, { phone: { contains: digits || term } }, { code: like }] },
            take,
            orderBy: { createdAt: 'desc' },
            select: { id: true, name: true, phone: true, code: true, isMerchant: true },
          })
        : [],
      can('orders.read') || can('pos.sell')
        ? this.prisma.order.findMany({
            where: { number: like, ...(!can('orders.read') && { cashierId: u.id }) },
            take,
            orderBy: { placedAt: 'desc' },
            select: { id: true, number: true, status: true, channel: true, grandTotal: true, currency: true, exchangeRate: true, placedAt: true, customer: { select: { name: true } } },
          })
        : [],
      can('purchases.read')
        ? this.prisma.supplier.findMany({ where: { OR: [{ name: like }, { code: like }, { phone: { contains: digits || term } }] }, take, select: { id: true, name: true, code: true, kind: true } })
        : [],
      can('purchases.read')
        ? this.prisma.purchaseOrder.findMany({
            where: { OR: [{ number: like }, { supplierInvoiceNo: like }] },
            take,
            orderBy: { orderDate: 'desc' },
            select: { id: true, number: true, status: true, kind: true, supplier: { select: { name: true } } },
          })
        : [],
    ]);
    return {
      products: products.map(({ images, ...p }) => ({ ...p, imageUrl: images[0]?.url ?? null })),
      customers,
      orders: orders.map((o) => ({ ...o, grandTotalInCurrency: toCurrency(o.grandTotal, o.currency, o.exchangeRate) })),
      suppliers,
      purchaseOrders: purchases,
    };
  }

  /** Counters for the badges on the menu and home screen (0 for what the user may not see). */
  async badges(u: AuthPrincipal) {
    const can = (p: string) => u.permissions.includes(p);
    const zero = Promise.resolve(0);
    const [newOrders, topUps, reviews, openShifts, smsWaiting, returns, lowStock] = await Promise.all([
      can('orders.read') ? this.prisma.order.count({ where: { channel: OrderChannel.ONLINE, status: { in: [OrderStatus.PENDING, OrderStatus.CONFIRMED] } } }) : zero,
      can('wallet.manage') ? this.prisma.walletTopUp.count({ where: { status: TopUpStatus.PENDING } }) : zero,
      can('products.write') ? this.prisma.productReview.count({ where: { isApproved: false } }) : zero,
      can('cashboxes.manage')
        ? this.prisma.cashierShift.count({ where: { status: ShiftStatus.OPEN } })
        : can('pos.sell')
          ? this.prisma.cashierShift.count({ where: { status: ShiftStatus.OPEN, userId: u.id } })
          : zero,
      can('settings.manage') ? this.prisma.outgoingSms.count({ where: { status: { in: [SmsStatus.PENDING, SmsStatus.SENDING] } } }) : zero,
      can('returns.manage') ? this.prisma.salesReturn.count({ where: { status: { in: ['REQUESTED', 'APPROVED'] } } }) : zero,
      can('stock.read')
        ? this.prisma.$queryRaw<{ n: number }[]>`SELECT COUNT(*)::int AS n FROM v_variant_availability WHERE is_low_stock`.then((r) => r[0]?.n ?? 0)
        : zero,
    ]);
    return { newOrders, topUps, reviews, openShifts, smsWaiting, returns, lowStock };
  }

  /** Sales of the last [days] days (till + app, SAR, net of tax), oldest first, with the days before for comparison. */
  async dailySales(days: number) {
    const tz = this.config.getOrThrow<string>('STORE_TIMEZONE');
    const n = Math.min(Math.max(days, 1), 90);
    const today = businessDate(tz);
    const from = new Date(today.getTime() - (2 * n - 1) * 86_400_000);
    const rows = await this.prisma.$queryRaw<{ day: Date; revenue: string; orders: number }[]>`
      SELECT (o.placed_at AT TIME ZONE 'UTC' AT TIME ZONE ${tz})::date AS day,
             COALESCE(SUM(o.grand_total - o.tax_total), 0)::text AS revenue,
             COUNT(*)::int AS orders
      FROM orders o
      WHERE o.status NOT IN ('CANCELLED', 'PENDING') AND (o.placed_at AT TIME ZONE 'UTC' AT TIME ZONE ${tz})::date >= ${from}::date
      GROUP BY 1`;
    const series = Array.from({ length: 2 * n }, (_, i) => {
      const d = new Date(from.getTime() + i * 86_400_000);
      const key = d.toISOString().slice(0, 10);
      const r = rows.find((x) => x.day.toISOString().slice(0, 10) === key);
      return { date: key, revenue: money(r?.revenue ?? 0), orders: r?.orders ?? 0 };
    });
    const previous = series.slice(0, n);
    const current = series.slice(n);
    const sum = (xs: typeof series) => xs.reduce((t, x) => t.add(x.revenue), money(0));
    return { days: current, previousTotal: sum(previous), total: sum(current) };
  }
}
