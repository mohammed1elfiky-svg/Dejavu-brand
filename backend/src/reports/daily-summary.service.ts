import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Prisma } from '../generated/prisma/client';
import { formatIn, inCurrency } from '../common/currency.service';
import { businessDate } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { SmsService } from '../sms/sms.service';
import { ReportsService } from './reports.service';

const SETTINGS = 'reports.dailySummary';
const LAST_SENT = 'reports.dailySummary.lastSent';

export interface DailySummarySettings {
  enabled: boolean;
  /** Mobile number the summary is texted to (the owner). */
  phone: string | null;
  /** Hour of the day (store time zone, 0-23) it is sent at. */
  hour: number;
}

/**
 * The evening summary texted to the owner: the day's sales (till / app / merchants),
 * gross profit, the cash in each currency and what customers owe.
 */
@Injectable()
export class DailySummaryService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('DailySummary');
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly reports: ReportsService,
    private readonly sms: SmsService,
    private readonly config: ConfigService,
  ) {}

  onModuleInit() {
    if (process.env.NODE_ENV === 'test') return;
    this.timer = setInterval(() => void this.tick().catch((e) => this.log.error(e)), 5 * 60_000);
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  async settings(): Promise<DailySummarySettings> {
    const v = (await this.prisma.setting.findUnique({ where: { key: SETTINGS } }))?.value as Partial<DailySummarySettings> | undefined;
    return { enabled: v?.enabled ?? false, phone: v?.phone ?? null, hour: v?.hour ?? 21 };
  }

  async updateSettings(dto: Partial<DailySummarySettings>) {
    const value = { ...(await this.settings()), ...Object.fromEntries(Object.entries(dto).filter(([, v]) => v !== undefined)) };
    await this.prisma.setting.upsert({ where: { key: SETTINGS }, update: { value: value as Prisma.InputJsonValue }, create: { key: SETTINGS, value: value as Prisma.InputJsonValue } });
    return value;
  }

  /** Sends today's summary when its hour has come and it was not sent yet today. */
  async tick(now = new Date()) {
    const s = await this.settings();
    if (!s.enabled || !s.phone) return false;
    const tz = this.config.getOrThrow<string>('STORE_TIMEZONE');
    const hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', hour12: false }).format(now));
    const day = businessDate(tz, now).toISOString().slice(0, 10);
    if (hour < s.hour) return false;
    const last = (await this.prisma.setting.findUnique({ where: { key: LAST_SENT } }))?.value;
    if (last === day) return false;
    await this.send(s.phone, day);
    await this.prisma.setting.upsert({ where: { key: LAST_SENT }, update: { value: day }, create: { key: LAST_SENT, value: day } });
    return true;
  }

  /** Texts the summary of [day] (today by default) now. */
  async send(phone?: string | null, day?: string) {
    const to = phone ?? (await this.settings()).phone;
    if (!to) throw new Error('No phone number set for the daily summary');
    const text = await this.text(day);
    await this.sms.enqueue(to, text, 'OTHER');
    return { queued: true, text };
  }

  async text(day?: string) {
    const tz = this.config.getOrThrow<string>('STORE_TIMEZONE');
    const date = day ?? businessDate(tz).toISOString().slice(0, 10);
    const r = await this.reports.revenue({ from: date, to: date });
    const cash = await this.prisma.$queryRaw<{ currency: string; amount: string }[]>`
      SELECT b.currency, COALESCE(SUM(e.amount), 0)::text AS amount
      FROM cash_boxes b LEFT JOIN cash_box_entries e ON e.cash_box_id = b.id
      WHERE b.is_active GROUP BY b.currency ORDER BY b.currency`;
    const [owed] = await this.prisma.$queryRaw<{ total: string | null }[]>`
      SELECT SUM(base)::text AS total FROM (
        SELECT SUM(base_amount) AS base FROM customer_account_entries GROUP BY customer_id, currency HAVING SUM(amount) > 0
      ) d`;
    const sar = (v: Prisma.Decimal | string) => formatIn(money(v), 'SAR');
    const lines = [
      `Dejavu — ملخص يوم ${date}`,
      `المبيعات: ${sar(r.total.revenue)} (${r.total.orders} فاتورة)`,
      `الكاشير: ${sar(r.pos.total.revenue)} | التطبيق: ${sar(r.app.total.revenue)}`,
      `التاجرات: ${sar(r.merchants.revenue)}`,
      `الربح الإجمالي: ${sar(r.total.grossProfit)}`,
      `النقد: ${cash.filter((c) => !dec(c.amount).isZero()).map((c) => formatIn(inCurrency(c.amount, c.currency), c.currency)).join(' | ') || '0'}`,
      `ديون العملاء: ${sar(owed?.total ?? '0')}`,
    ];
    return lines.join('\n');
  }
}
