import { BadRequestException, Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { AccountType, NormalBalance, Prisma } from '../generated/prisma/client';
import { inCurrency } from '../common/currency.service';
import { businessDate, parseDateOnly } from '../common/utils/dates';
import { Decimal, dec, money, sum, ZERO } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { AsOfQueryDto, PeriodQueryDto, SalesReportQueryDto } from './dto/reports.dto';

interface AccountTotal {
  id: string;
  code: string;
  nameAr: string;
  nameEn: string;
  type: AccountType;
  normalBalance: NormalBalance;
  debit: Decimal;
  credit: Decimal;
}

interface ReportLine {
  accountId: string;
  code: string;
  nameAr: string;
  nameEn: string;
  amount: Decimal;
}

const COGS_PREFIX = '51';

/** All figures come from posted journal entries, so every report agrees with the trial balance. */
@Injectable()
export class ReportsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
  ) {}

  private get tz() {
    return this.config.getOrThrow<string>('STORE_TIMEZONE');
  }

  private period(q: PeriodQueryDto) {
    const today = businessDate(this.tz);
    const from = q.from ? parseDateOnly(q.from) : new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1));
    const to = q.to ? parseDateOnly(q.to) : today;
    if (from > to) throw new BadRequestException('"from" must be on or before "to"');
    return { from, to };
  }

  /** Debit/credit totals per postable account for posted entries dated within [from, to]. */
  private async accountTotals(from: Date | null, to: Date): Promise<AccountTotal[]> {
    const rows = await this.prisma.$queryRaw<
      { id: string; code: string; name_ar: string; name_en: string; type: AccountType; normal_balance: NormalBalance; debit: string; credit: string }[]
    >`
      SELECT a.id, a.code, a.name_ar, a.name_en, a.type, a.normal_balance,
             COALESCE(SUM(l.debit), 0)::text AS debit, COALESCE(SUM(l.credit), 0)::text AS credit
      FROM accounts a
      LEFT JOIN (
        journal_lines l
        JOIN journal_entries e ON e.id = l.journal_entry_id
         AND e.status IN ('POSTED', 'REVERSED')
         AND (${from}::date IS NULL OR e.entry_date >= ${from}::date)
         AND e.entry_date <= ${to}::date
      ) ON l.account_id = a.id
      WHERE a.is_postable
      GROUP BY a.id
      ORDER BY a.code`;
    return rows.map((r) => ({
      id: r.id,
      code: r.code,
      nameAr: r.name_ar,
      nameEn: r.name_en,
      type: r.type,
      normalBalance: r.normal_balance,
      debit: dec(r.debit),
      credit: dec(r.credit),
    }));
  }

  private line(a: AccountTotal, amount: Decimal): ReportLine {
    return { accountId: a.id, code: a.code, nameAr: a.nameAr, nameEn: a.nameEn, amount: money(amount) };
  }

  private static nonZero = (l: ReportLine) => !l.amount.isZero();
  private static total = (lines: ReportLine[]) => money(sum(lines.map((l) => l.amount)));

  // ------------------------------------------------------ income statement ---

  async incomeStatement(q: PeriodQueryDto) {
    const { from, to } = this.period(q);
    return { from, to, ...this.profitAndLoss(await this.accountTotals(from, to)) };
  }

  private profitAndLoss(totals: AccountTotal[]) {
    const revenue = totals
      .filter((a) => a.type === AccountType.REVENUE)
      .map((a) => this.line(a, a.credit.sub(a.debit)))
      .filter(ReportsService.nonZero);
    const expenseLines = (cogs: boolean) =>
      totals
        .filter((a) => a.type === AccountType.EXPENSE && a.code.startsWith(COGS_PREFIX) === cogs)
        .map((a) => this.line(a, a.debit.sub(a.credit)))
        .filter(ReportsService.nonZero);
    const costOfSales = expenseLines(true);
    const operatingExpenses = expenseLines(false);

    const netRevenue = ReportsService.total(revenue);
    const totalCostOfSales = ReportsService.total(costOfSales);
    const grossProfit = netRevenue.sub(totalCostOfSales);
    const totalOperatingExpenses = ReportsService.total(operatingExpenses);
    const netIncome = grossProfit.sub(totalOperatingExpenses);
    const pct = (v: Decimal) => (netRevenue.isZero() ? null : money(v.div(netRevenue).mul(100)));

    return {
      revenue: { lines: revenue, total: netRevenue },
      costOfSales: { lines: costOfSales, total: totalCostOfSales },
      grossProfit,
      grossMarginPct: pct(grossProfit),
      operatingExpenses: { lines: operatingExpenses, total: totalOperatingExpenses },
      netIncome,
      netMarginPct: pct(netIncome),
    };
  }

  // ---------------------------------------------------------- balance sheet ---

  async balanceSheet(q: AsOfQueryDto) {
    const asOf = q.asOf ? parseDateOnly(q.asOf) : businessDate(this.tz);
    const totals = await this.accountTotals(null, asOf);
    const section = (type: AccountType) =>
      totals
        .filter((a) => a.type === type)
        .map((a) => this.line(a, type === AccountType.ASSET ? a.debit.sub(a.credit) : a.credit.sub(a.debit)))
        .filter(ReportsService.nonZero);

    const assets = section(AccountType.ASSET);
    const liabilities = section(AccountType.LIABILITY);
    const equity = section(AccountType.EQUITY);
    // Profit not yet closed into retained earnings belongs to the owners.
    const currentEarnings = this.profitAndLoss(totals).netIncome;

    const totalAssets = ReportsService.total(assets);
    const totalLiabilities = ReportsService.total(liabilities);
    const totalEquity = ReportsService.total(equity).add(currentEarnings);
    return {
      asOf,
      assets: { lines: assets, total: totalAssets },
      liabilities: { lines: liabilities, total: totalLiabilities },
      equity: { lines: equity, currentEarnings, total: totalEquity },
      totalLiabilitiesAndEquity: totalLiabilities.add(totalEquity),
      balanced: totalAssets.equals(totalLiabilities.add(totalEquity)),
    };
  }

  // ------------------------------------------------------- staff report ---

  /**
   * Each cashier's period: till invoices and revenue (net of tax and returns), the average
   * invoice, discounts given, invoices deleted, and what their shifts were over or short.
   */
  async staff(q: PeriodQueryDto) {
    const { from, to } = this.period(q);
    const end = new Date(to.getTime() + 24 * 3600_000);
    const sales = await this.prisma.$queryRaw<
      { user_id: string; invoices: number; voided: number; gross: string; discounts: string; net: string; cogs: string }[]
    >`
      SELECT o.cashier_id::text AS user_id,
             COUNT(*) FILTER (WHERE o.status <> 'CANCELLED')::int AS invoices,
             COUNT(*) FILTER (WHERE o.status = 'CANCELLED')::int AS voided,
             COALESCE(SUM(o.grand_total) FILTER (WHERE o.status <> 'CANCELLED'), 0)::text AS gross,
             COALESCE(SUM(o.discount_total) FILTER (WHERE o.status <> 'CANCELLED'), 0)::text AS discounts,
             COALESCE(SUM((SELECT SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)) FROM order_items i WHERE i.order_id = o.id))
               FILTER (WHERE o.status <> 'CANCELLED'), 0)::text AS net,
             COALESCE(SUM((SELECT SUM(i.unit_cost * (i.quantity - i.returned_qty)) FROM order_items i WHERE i.order_id = o.id))
               FILTER (WHERE o.status <> 'CANCELLED'), 0)::text AS cogs
      FROM orders o
      WHERE o.channel = 'POS' AND o.cashier_id IS NOT NULL AND o.placed_at >= ${from} AND o.placed_at < ${end}
      GROUP BY 1`;
    const shifts = await this.prisma.$queryRaw<{ user_id: string; currency: string; difference: string; shifts: number }[]>`
      SELECT s.user_id::text, l.currency, COALESCE(SUM(l.difference), 0)::text AS difference, COUNT(DISTINCT s.id)::int AS shifts
      FROM cashier_shifts s JOIN cashier_shift_lines l ON l.shift_id = s.id
      WHERE s.status = 'CLOSED' AND s.closed_at >= ${from} AND s.closed_at < ${end}
      GROUP BY 1, 2`;
    const ids = [...new Set([...sales.map((s) => s.user_id), ...shifts.map((s) => s.user_id)])];
    const users = await this.prisma.user.findMany({ where: { id: { in: ids } }, select: { id: true, name: true, phone: true } });
    const rows = users.map((u) => {
      const s = sales.find((x) => x.user_id === u.id);
      const revenue = money(s?.net ?? 0);
      const cost = money(s?.cogs ?? 0);
      const invoices = s?.invoices ?? 0;
      const mine = shifts.filter((x) => x.user_id === u.id);
      return {
        user: u,
        invoices,
        voided: s?.voided ?? 0,
        revenue,
        grossProfit: revenue.sub(cost),
        averageInvoice: invoices ? money(dec(s!.gross).div(invoices)) : money(0),
        discounts: money(s?.discounts ?? 0),
        /** Shift counts: + over, − short, per currency. */
        shiftDifferences: mine.filter((x) => !dec(x.difference).isZero()).map((x) => ({ currency: x.currency, difference: inCurrency(x.difference, x.currency) })),
        closedShifts: Math.max(0, ...mine.map((x) => x.shifts)),
      };
    });
    rows.sort((a, b) => b.revenue.cmp(a.revenue));
    return { from, to, staff: rows };
  }

  // ---------------------------------------------------- product reports ---

  /**
   * Best sellers of the period (pieces, revenue net of tax and returns, cost, profit) and
   * dead stock: products with pieces on the shelf that sold nothing for [deadDays].
   */
  async products(q: PeriodQueryDto & { deadDays?: number; limit?: number }) {
    const { from, to } = this.period(q);
    const limit = Math.min(Math.max(q.limit ?? 30, 1), 200);
    const deadDays = Math.max(q.deadDays ?? 60, 1);
    const top = await this.prisma.$queryRaw<
      { id: string; name: string; sku: number | null; units: number; revenue: string; cost: string; on_hand: number; image: string | null }[]
    >`
      WITH sold AS (
        SELECT o.id,
               (COALESCE((SELECT MIN(h.created_at) FROM order_status_history h WHERE h.order_id = o.id AND h.to_status = 'SHIPPED'), o.placed_at)
                 AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date AS day
        FROM orders o WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
      )
      SELECT p.id::text, MAX(p.name_ar) AS name, MAX(p.sku_number) AS sku,
             SUM(i.quantity - i.returned_qty)::int AS units,
             COALESCE(SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)), 0)::text AS revenue,
             COALESCE(SUM(i.unit_cost * (i.quantity - i.returned_qty)), 0)::text AS cost,
             COALESCE((SELECT SUM(sl.on_hand) FROM stock_levels sl JOIN product_variants pv ON pv.id = sl.variant_id WHERE pv.product_id = p.id), 0)::int AS on_hand,
             (SELECT pi.url FROM product_images pi WHERE pi.product_id = p.id ORDER BY pi.sort_order LIMIT 1) AS image
      FROM sold s
      JOIN order_items i ON i.order_id = s.id
      JOIN product_variants v ON v.id = i.variant_id
      JOIN products p ON p.id = v.product_id
      WHERE s.day BETWEEN ${from}::date AND ${to}::date
      GROUP BY p.id
      HAVING SUM(i.quantity - i.returned_qty) > 0
      ORDER BY 4 DESC, 5 DESC
      LIMIT ${limit}`;

    // The same sales, ranked by profit instead of pieces.
    const profitable = await this.prisma.$queryRaw<
      { id: string; name: string; sku: number | null; units: number; revenue: string; cost: string; on_hand: number; image: string | null }[]
    >`
      WITH sold AS (
        SELECT o.id,
               (COALESCE((SELECT MIN(h.created_at) FROM order_status_history h WHERE h.order_id = o.id AND h.to_status = 'SHIPPED'), o.placed_at)
                 AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date AS day
        FROM orders o WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
      )
      SELECT p.id::text, MAX(p.name_ar) AS name, MAX(p.sku_number) AS sku,
             SUM(i.quantity - i.returned_qty)::int AS units,
             COALESCE(SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)), 0)::text AS revenue,
             COALESCE(SUM(i.unit_cost * (i.quantity - i.returned_qty)), 0)::text AS cost,
             COALESCE((SELECT SUM(sl.on_hand) FROM stock_levels sl JOIN product_variants pv ON pv.id = sl.variant_id WHERE pv.product_id = p.id), 0)::int AS on_hand,
             (SELECT pi.url FROM product_images pi WHERE pi.product_id = p.id ORDER BY pi.sort_order LIMIT 1) AS image
      FROM sold s
      JOIN order_items i ON i.order_id = s.id
      JOIN product_variants v ON v.id = i.variant_id
      JOIN products p ON p.id = v.product_id
      WHERE s.day BETWEEN ${from}::date AND ${to}::date
      GROUP BY p.id
      HAVING SUM(i.quantity - i.returned_qty) > 0
      ORDER BY COALESCE(SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)), 0)
             - COALESCE(SUM(i.unit_cost * (i.quantity - i.returned_qty)), 0) DESC
      LIMIT ${limit}`;

    // Per category: pieces, revenue, cost and profit.
    const categories = await this.prisma.$queryRaw<{ id: string; name: string; units: number; revenue: string; cost: string; products: number }[]>`
      WITH sold AS (
        SELECT o.id,
               (COALESCE((SELECT MIN(h.created_at) FROM order_status_history h WHERE h.order_id = o.id AND h.to_status = 'SHIPPED'), o.placed_at)
                 AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date AS day
        FROM orders o WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
      )
      SELECT c.id::text, MAX(c.name_ar) AS name,
             SUM(i.quantity - i.returned_qty)::int AS units,
             COALESCE(SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)), 0)::text AS revenue,
             COALESCE(SUM(i.unit_cost * (i.quantity - i.returned_qty)), 0)::text AS cost,
             COUNT(DISTINCT p.id)::int AS products
      FROM sold s
      JOIN order_items i ON i.order_id = s.id
      JOIN product_variants v ON v.id = i.variant_id
      JOIN products p ON p.id = v.product_id
      JOIN categories c ON c.id = p.category_id
      WHERE s.day BETWEEN ${from}::date AND ${to}::date
      GROUP BY c.id
      HAVING SUM(i.quantity - i.returned_qty) > 0`;

    const dead = await this.prisma.$queryRaw<
      { id: string; name: string; sku: number | null; on_hand: number; stock_value: string; last_sold: Date | null; image: string | null }[]
    >`
      SELECT p.id::text, p.name_ar AS name, p.sku_number AS sku,
             SUM(sl.on_hand)::int AS on_hand,
             SUM(sl.on_hand * v.avg_cost)::text AS stock_value,
             (SELECT MAX(o.placed_at) FROM order_items i JOIN orders o ON o.id = i.order_id JOIN product_variants pv ON pv.id = i.variant_id
                WHERE pv.product_id = p.id AND o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')) AS last_sold,
             (SELECT pi.url FROM product_images pi WHERE pi.product_id = p.id ORDER BY pi.sort_order LIMIT 1) AS image
      FROM products p
      JOIN product_variants v ON v.product_id = p.id
      JOIN stock_levels sl ON sl.variant_id = v.id
      WHERE p.status = 'ACTIVE' AND p.created_at < now() - make_interval(days => ${deadDays})
      GROUP BY p.id
      HAVING SUM(sl.on_hand) > 0
         AND COALESCE((SELECT MAX(o.placed_at) FROM order_items i JOIN orders o ON o.id = i.order_id JOIN product_variants pv ON pv.id = i.variant_id
                WHERE pv.product_id = p.id AND o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')), 'epoch') < now() - make_interval(days => ${deadDays})
      ORDER BY 5 DESC
      LIMIT ${limit}`;

    const productRow = (r: (typeof top)[number]) => {
      const revenue = money(r.revenue);
      const cost = money(r.cost);
      const profit = revenue.sub(cost);
      return {
        productId: r.id,
        nameAr: r.name,
        skuNumber: r.sku,
        imageUrl: r.image,
        units: r.units,
        revenue,
        cost,
        grossProfit: profit,
        /** Profit as % of revenue. */
        margin: revenue.isZero() ? dec(0) : profit.div(revenue).mul(100).toDecimalPlaces(1),
        onHand: r.on_hand,
      };
    };
    const categoryRows = categories.map((c) => {
      const revenue = money(c.revenue);
      const cost = money(c.cost);
      const profit = revenue.sub(cost);
      return {
        categoryId: c.id,
        nameAr: c.name,
        units: c.units,
        products: c.products,
        revenue,
        cost,
        grossProfit: profit,
        margin: revenue.isZero() ? dec(0) : profit.div(revenue).mul(100).toDecimalPlaces(1),
      };
    });
    return {
      from,
      to,
      deadDays,
      topSellers: top.map(productRow),
      /** Products that made the most profit. */
      topProfit: profitable.map(productRow),
      /** Categories by pieces sold… */
      topCategories: [...categoryRows].sort((a, b) => b.units - a.units || b.revenue.cmp(a.revenue)),
      /** …and by profit. */
      profitableCategories: [...categoryRows].sort((a, b) => b.grossProfit.cmp(a.grossProfit)),
      deadStock: dead.map((r) => ({
        productId: r.id,
        nameAr: r.name,
        skuNumber: r.sku,
        imageUrl: r.image,
        onHand: r.on_hand,
        stockValue: money(r.stock_value),
        lastSoldAt: r.last_sold,
      })),
    };
  }

  /**
   * What to order again: for each size/colour sold in the last [days], the daily rate,
   * what is on the shelf and already on order, and the pieces needed to cover [coverDays].
   */
  async reorder(q: { days?: number; coverDays?: number }) {
    const days = Math.min(Math.max(q.days ?? 30, 7), 365);
    const cover = Math.min(Math.max(q.coverDays ?? 30, 1), 365);
    const rows = await this.prisma.$queryRaw<
      { variant_id: string; product_id: string; name: string; sku: string; size: string | null; color: string | null; sold: number; on_hand: number; incoming: number; supplier: string | null; image: string | null }[]
    >`
      SELECT v.id::text AS variant_id, p.id::text AS product_id, p.name_ar AS name, v.sku, v.size, v.color,
             SUM(i.quantity - i.returned_qty)::int AS sold,
             COALESCE((SELECT SUM(sl.on_hand) FROM stock_levels sl WHERE sl.variant_id = v.id), 0)::int AS on_hand,
             COALESCE((SELECT SUM(pi.quantity - pi.received_qty) FROM purchase_order_items pi JOIN purchase_orders po ON po.id = pi.purchase_order_id
                        WHERE pi.variant_id = v.id AND po.status IN ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED')), 0)::int AS incoming,
             (SELECT s.name FROM purchase_order_items pi JOIN purchase_orders po ON po.id = pi.purchase_order_id JOIN suppliers s ON s.id = po.supplier_id
                WHERE pi.variant_id = v.id AND po.status <> 'CANCELLED' ORDER BY po.order_date DESC LIMIT 1) AS supplier,
             (SELECT pim.url FROM product_images pim WHERE pim.product_id = p.id ORDER BY pim.sort_order LIMIT 1) AS image
      FROM order_items i
      JOIN orders o ON o.id = i.order_id
      JOIN product_variants v ON v.id = i.variant_id
      JOIN products p ON p.id = v.product_id
      WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED') AND o.placed_at >= now() - make_interval(days => ${days}) AND p.status = 'ACTIVE'
      GROUP BY v.id, p.id
      HAVING SUM(i.quantity - i.returned_qty) > 0`;
    const items = rows
      .map((r) => {
        const perDay = r.sold / days;
        const need = Math.ceil(perDay * cover) - r.on_hand - r.incoming;
        return {
          variantId: r.variant_id,
          productId: r.product_id,
          nameAr: r.name,
          sku: r.sku,
          size: r.size,
          color: r.color,
          imageUrl: r.image,
          sold: r.sold,
          perDay: Math.round(perDay * 100) / 100,
          onHand: r.on_hand,
          incoming: r.incoming,
          /** Days the shelf lasts at this pace. */
          daysLeft: perDay > 0 ? Math.floor(r.on_hand / perDay) : null,
          suggested: Math.max(need, 0),
          lastSupplier: r.supplier,
        };
      })
      .filter((r) => r.suggested > 0)
      .sort((a, b) => (a.daysLeft ?? 9999) - (b.daysLeft ?? 9999) || b.suggested - a.suggested);
    return { days, coverDays: cover, items };
  }

  // --------------------------------------------------------- revenue report ---

  /**
   * Sales revenue by channel (till / customer app) and buyer (retail / merchants), net of
   * tax and of pieces returned, with cost and gross profit. Till sales count on the day
   * they were rung up, app orders on the day they shipped.
   */
  async revenue(q: PeriodQueryDto) {
    const { from, to } = this.period(q);
    const rows = await this.prisma.$queryRaw<
      { channel: string; merchant: boolean; orders: number; units: number; net: string; cogs: string }[]
    >`
      WITH sold AS (
        SELECT o.id, o.channel::text AS channel, cu.is_merchant AS merchant,
               (COALESCE(
                  (SELECT MIN(h.created_at) FROM order_status_history h WHERE h.order_id = o.id AND h.to_status = 'SHIPPED'),
                  o.placed_at
                ) AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date AS day
        FROM orders o
        JOIN customers cu ON cu.id = o.customer_id
        WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
      )
      SELECT s.channel, s.merchant,
             COUNT(DISTINCT s.id)::int AS orders,
             SUM(i.quantity - i.returned_qty)::int AS units,
             COALESCE(SUM((i.line_total - i.tax_amount) * (i.quantity - i.returned_qty) / NULLIF(i.quantity, 0)), 0)::text AS net,
             COALESCE(SUM(i.unit_cost * (i.quantity - i.returned_qty)), 0)::text AS cogs
      FROM sold s
      JOIN order_items i ON i.order_id = s.id
      WHERE s.day BETWEEN ${from}::date AND ${to}::date
      GROUP BY 1, 2`;

    const zero = { orders: 0, units: 0, revenue: money(0), cost: money(0), grossProfit: money(0) };
    const add = (a: typeof zero, b: typeof zero) => ({
      orders: a.orders + b.orders,
      units: a.units + b.units,
      revenue: a.revenue.add(b.revenue),
      cost: a.cost.add(b.cost),
      grossProfit: a.grossProfit.add(b.grossProfit),
    });
    const cell = (channel: 'POS' | 'ONLINE', merchant: boolean) => {
      const r = rows.find((x) => x.channel === channel && x.merchant === merchant);
      if (!r) return zero;
      const revenue = money(r.net);
      const cost = money(r.cogs);
      return { orders: r.orders, units: r.units, revenue, cost, grossProfit: revenue.sub(cost) };
    };
    const pos = { retail: cell('POS', false), merchants: cell('POS', true) };
    const app = { retail: cell('ONLINE', false), merchants: cell('ONLINE', true) };
    const posTotal = add(pos.retail, pos.merchants);
    const appTotal = add(app.retail, app.merchants);
    return {
      from,
      to,
      pos: { ...pos, total: posTotal },
      app: { ...app, total: appTotal },
      /** Merchants (wholesale) at the till and in the app together. */
      merchants: add(pos.merchants, app.merchants),
      retail: add(pos.retail, app.retail),
      total: add(posTotal, appTotal),
    };
  }

  // ------------------------------------------------------------ sales report ---

  /** Invoiced (shipped) sales by day, product or category, with cost and gross profit. */
  async sales(q: SalesReportQueryDto) {
    const { from, to } = this.period(q);
    const key =
      q.groupBy === 'product'
        ? Prisma.sql`p.id::text`
        : q.groupBy === 'category'
          ? Prisma.sql`c.id::text`
          : Prisma.sql`to_char(s.day, 'YYYY-MM-DD')`;
    const label =
      q.groupBy === 'product' ? Prisma.sql`MAX(p.name_ar)` : q.groupBy === 'category' ? Prisma.sql`MAX(c.name_ar)` : Prisma.sql`MIN(to_char(s.day, 'YYYY-MM-DD'))`;

    const rows = await this.prisma.$queryRaw<
      { key: string; label: string; orders: number; units: number; returned_units: number; gross: string; discounts: string; net: string; cogs: string }[]
    >`
      WITH shipped AS (
        SELECT o.id, (MIN(h.created_at) AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date AS day
        FROM orders o
        JOIN order_status_history h ON h.order_id = o.id AND h.to_status = 'SHIPPED'
        WHERE o.status IN ('SHIPPED', 'DELIVERED', 'COMPLETED')
        GROUP BY o.id
      )
      SELECT ${key} AS key, ${label} AS label,
             COUNT(DISTINCT s.id)::int AS orders,
             SUM(i.quantity)::int AS units,
             SUM(i.returned_qty)::int AS returned_units,
             SUM(i.unit_price * i.quantity)::text AS gross,
             SUM(i.discount)::text AS discounts,
             SUM(i.line_total - i.tax_amount)::text AS net,
             SUM(i.unit_cost * i.quantity)::text AS cogs
      FROM shipped s
      JOIN order_items i ON i.order_id = s.id
      JOIN product_variants v ON v.id = i.variant_id
      JOIN products p ON p.id = v.product_id
      JOIN categories c ON c.id = p.category_id
      WHERE s.day BETWEEN ${from}::date AND ${to}::date
      GROUP BY 1
      ORDER BY ${q.groupBy === 'day' ? Prisma.sql`1` : Prisma.sql`SUM(i.line_total - i.tax_amount) DESC`}`;

    const groups = rows.map((r) => {
      const net = money(r.net);
      const cogs = money(r.cogs);
      return {
        key: r.key,
        label: r.label,
        orders: r.orders,
        units: r.units,
        returnedUnits: r.returned_units,
        grossSales: money(r.gross),
        discounts: money(r.discounts),
        netSales: net,
        costOfGoods: cogs,
        grossProfit: net.sub(cogs),
      };
    });
    const t = (k: 'grossSales' | 'discounts' | 'netSales' | 'costOfGoods' | 'grossProfit') => money(sum(groups.map((g) => g[k])));
    return {
      from,
      to,
      groupBy: q.groupBy,
      groups,
      totals: {
        units: groups.reduce((n, g) => n + g.units, 0),
        returnedUnits: groups.reduce((n, g) => n + g.returnedUnits, 0),
        grossSales: t('grossSales'),
        discounts: t('discounts'),
        netSales: t('netSales'),
        costOfGoods: t('costOfGoods'),
        grossProfit: t('grossProfit'),
      },
      note: 'Net sales exclude VAT and shipping and are before returns; see the income statement for figures net of returns.',
    };
  }

  // ---------------------------------------------------- inventory valuation ---

  /**
   * Stock on hand at cost (average purchase cost in SAR, the product cost for pieces not
   * yet valued) and at selling price, per category and overall.
   */
  async inventoryValuation() {
    const rows = await this.prisma.$queryRaw<
      {
        variant_id: string; sku: string; name_ar: string; category_id: string; category: string;
        on_hand: number; avg_cost: string; value: string; sale_value: string;
      }[]
    >`
      SELECT v.id AS variant_id, v.sku, p.name_ar, c.id AS category_id, c.name_ar AS category,
             COALESCE(SUM(s.on_hand), 0)::int AS on_hand,
             (CASE WHEN v.avg_cost > 0 THEN v.avg_cost ELSE p.cost_price END)::text AS avg_cost,
             ROUND(COALESCE(SUM(s.on_hand), 0) * (CASE WHEN v.avg_cost > 0 THEN v.avg_cost ELSE p.cost_price END), 2)::text AS value,
             ROUND(COALESCE(SUM(s.on_hand), 0) * COALESCE(v.price, p.base_price), 2)::text AS sale_value
      FROM product_variants v
      JOIN products p ON p.id = v.product_id
      JOIN categories c ON c.id = p.category_id
      LEFT JOIN stock_levels s ON s.variant_id = v.id
      GROUP BY v.id, p.id, c.id
      HAVING COALESCE(SUM(s.on_hand), 0) <> 0
      ORDER BY 8 DESC`;
    const stockValue = money(sum(rows.map((r) => r.value)));
    const saleValue = money(sum(rows.map((r) => r.sale_value)));
    const gl = await this.prisma.journalLine.aggregate({
      where: { account: { systemKey: 'INVENTORY' }, journalEntry: { status: { in: ['POSTED', 'REVERSED'] } } },
      _sum: { debit: true, credit: true },
    });
    const ledgerValue = money(dec(gl._sum.debit).sub(dec(gl._sum.credit)));

    const byCategory = new Map<string, { categoryId: string; nameAr: string; products: Set<string>; pieces: number; costValue: Decimal; saleValue: Decimal }>();
    for (const r of rows) {
      const c = byCategory.get(r.category_id) ?? { categoryId: r.category_id, nameAr: r.category, products: new Set(), pieces: 0, costValue: ZERO, saleValue: ZERO };
      c.products.add(r.name_ar);
      c.pieces += r.on_hand;
      c.costValue = c.costValue.add(r.value);
      c.saleValue = c.saleValue.add(r.sale_value);
      byCategory.set(r.category_id, c);
    }
    const categories = [...byCategory.values()]
      .map((c) => ({
        categoryId: c.categoryId,
        nameAr: c.nameAr,
        products: c.products.size,
        pieces: c.pieces,
        costValue: money(c.costValue),
        saleValue: money(c.saleValue),
        expectedProfit: money(c.saleValue.sub(c.costValue)),
      }))
      .sort((a, b) => b.costValue.cmp(a.costValue));
    return {
      categories,
      totals: {
        products: new Set(rows.map((r) => r.name_ar)).size,
        pieces: rows.reduce((n, r) => n + r.on_hand, 0),
        costValue: stockValue,
        saleValue,
        expectedProfit: money(saleValue.sub(stockValue)),
      },
      items: rows.map((r) => ({
        variantId: r.variant_id, sku: r.sku, nameAr: r.name_ar, category: r.category, onHand: r.on_hand,
        avgCost: dec(r.avg_cost), value: money(r.value), saleValue: money(r.sale_value),
      })),
      stockValue,
      ledgerValue,
      // Small differences come from rounding average cost to 4 decimals.
      difference: ledgerValue.sub(stockValue),
    };
  }

  // ---------------------------------------------------------------- dashboard ---

  async dashboard() {
    const today = businessDate(this.tz);
    const monthStart = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1));

    const [todayOrders, byStatus, pendingReturns, lowStock, totals, pendingCod] = await Promise.all([
      this.prisma.$queryRaw<{ orders: number; sales: string }[]>`
        SELECT COUNT(*)::int AS orders, COALESCE(SUM(grand_total), 0)::text AS sales
        FROM orders
        WHERE status <> 'CANCELLED'
          AND (placed_at AT TIME ZONE 'UTC' AT TIME ZONE ${this.tz})::date = ${today}::date`,
      this.prisma.order.groupBy({ by: ['status'], _count: true }),
      this.prisma.salesReturn.count({ where: { status: { in: ['REQUESTED', 'APPROVED'] } } }),
      this.prisma.$queryRaw<{ n: number }[]>`SELECT COUNT(*)::int AS n FROM v_variant_availability WHERE is_low_stock`,
      this.accountTotals(null, today),
      this.prisma.shipment.aggregate({
        where: { status: 'DELIVERED', settlementId: null, codAmount: { gt: 0 } },
        _sum: { codAmount: true },
        _count: true,
      }),
    ]);

    const balanceOf = (pred: (a: AccountTotal) => boolean, sign: 1 | -1 = 1) =>
      money(sum(totals.filter(pred).map((a) => a.debit.sub(a.credit).mul(sign))));
    const accounts = await this.prisma.account.findMany({ where: { systemKey: { not: null } }, select: { id: true, systemKey: true } });
    const key = (k: string) => (a: AccountTotal) => accounts.find((x) => x.id === a.id)?.systemKey === k;
    const cashAccounts = await this.prisma.account.findMany({ where: { isCashOrBank: true, isActive: true }, select: { id: true } });
    const isCash = new Set(cashAccounts.map((a) => a.id));
    const boxes = await this.prisma.cashBox.findMany({ select: { id: true, accountId: true, currency: true, icon: true } });
    const held_ = new Map(
      (await this.prisma.cashBoxEntry.groupBy({ by: ['cashBoxId'], _sum: { amount: true } })).map((r) => [r.cashBoxId, r._sum.amount]),
    );
    const mtd = this.profitAndLoss(await this.accountTotals(monthStart, today));
    const active = await this.prisma.currency.findMany({ where: { isActive: true, isBase: false }, orderBy: { sortOrder: 'asc' }, select: { code: true, nameAr: true } });
    const rates = await Promise.all(
      active.map(async (c) => {
        const r = await this.prisma.exchangeRate.findFirst({ where: { currency: c.code }, orderBy: { effectiveAt: 'desc' }, select: { rate: true, effectiveAt: true } });
        return {
          currency: c.code,
          nameAr: c.nameAr,
          rate: r?.rate ?? null,
          effectiveAt: r?.effectiveAt ?? null,
          /** Not updated in the last 24 hours. */
          stale: !r || Date.now() - r.effectiveAt.getTime() > 24 * 3600_000,
        };
      }),
    );

    return {
      date: today,
      today: { orders: todayOrders[0].orders, sales: money(todayOrders[0].sales) },
      ordersByStatus: Object.fromEntries(byStatus.map((s) => [s.status, s._count])),
      pendingReturns,
      lowStockVariants: lowStock[0]?.n ?? 0,
      /** Exchange rates and whether each is out of date. */
      rates,
      // Cash boxes show what they hold in their own currency (e.g. 100 USD), with the SAR book value.
      cashAndBank: totals
        .filter((a) => isCash.has(a.id))
        .map((a) => {
          const box = boxes.find((b) => b.accountId === a.id);
          const held = box ? held_.get(box.id) : undefined;
          return {
            accountId: a.id,
            code: a.code,
            nameAr: a.nameAr,
            nameEn: a.nameEn,
            balance: money(a.debit.sub(a.credit)),
            currency: box?.currency ?? 'SAR',
            amount: box ? inCurrency(dec(held), box.currency) : money(a.debit.sub(a.credit)),
            icon: box?.icon ?? null,
          };
        }),
      receivables: {
        customers: balanceOf(key('AR')),
        couriersCod: balanceOf(key('COD_RECEIVABLE')),
        pendingCodShipments: pendingCod._count,
      },
      payables: {
        suppliers: balanceOf(key('AP'), -1),
        customerAdvances: balanceOf(key('CUSTOMER_ADVANCES'), -1),
        accruedExpenses: balanceOf(key('ACCRUED_EXPENSES'), -1),
        vatDue: balanceOf((a) => key('VAT_OUTPUT')(a) || key('VAT_INPUT')(a), -1),
      },
      monthToDate: {
        from: monthStart,
        netRevenue: mtd.revenue.total,
        grossProfit: mtd.grossProfit,
        operatingExpenses: mtd.operatingExpenses.total,
        netIncome: mtd.netIncome,
      },
    };
  }
}
