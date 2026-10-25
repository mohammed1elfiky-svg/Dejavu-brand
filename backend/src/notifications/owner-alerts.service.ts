import { Injectable, Logger } from '@nestjs/common';
import { SmsService } from '../sms/sms.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { NotificationsService } from './notifications.service';

export type OwnerAlertKind = 'VOID' | 'SHORTAGE' | 'PRICE';

export interface OwnerAlertSettings {
  /** A till invoice was deleted. */
  voids: boolean;
  /** A drawer came up short at a shift close by more than this (SAR); null = off. */
  shortageAbove: number | null;
  /** A selling price was changed (one product or many at once). */
  priceChanges: boolean;
  /** Also text the alert to this phone (through the SMS gateway); empty = push only. */
  smsPhone: string | null;
}

const KEY = 'alerts.owner';
const DEFAULTS: OwnerAlertSettings = { voids: true, shortageAbove: 0, priceChanges: true, smsPhone: null };
/** Who counts as "the owner": staff with this permission. */
export const OWNER_PERMISSION = 'settings.manage';

/**
 * Things the owner wants to know at once. Each alert is kept (shown in the admin app's
 * alerts, also without Firebase), pushed to the owner's devices and, if set, texted.
 */
@Injectable()
export class OwnerAlertsService {
  private readonly log = new Logger('OwnerAlerts');

  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
    private readonly sms: SmsService,
  ) {}

  async settings(db: Tx | PrismaService = this.prisma): Promise<OwnerAlertSettings> {
    const row = await db.setting.findUnique({ where: { key: KEY } });
    return { ...DEFAULTS, ...((row?.value as Partial<OwnerAlertSettings>) ?? {}) };
  }

  async setSettings(dto: Partial<OwnerAlertSettings>) {
    const value = { ...(await this.settings()), ...dto };
    if (value.smsPhone !== null && value.smsPhone !== undefined && !value.smsPhone.trim()) value.smsPhone = null;
    await this.prisma.setting.upsert({ where: { key: KEY }, update: { value: value as object }, create: { key: KEY, value: value as object } });
    return value;
  }

  /** Raises an alert of [kind] if the owner wants that kind; never fails the caller's work. */
  async raise(kind: OwnerAlertKind, title: string, body: string, data: Record<string, string> = {}, opts: { shortage?: number } = {}) {
    try {
      const s = await this.settings();
      if (kind === 'VOID' && !s.voids) return;
      if (kind === 'PRICE' && !s.priceChanges) return;
      if (kind === 'SHORTAGE' && (s.shortageAbove === null || (opts.shortage ?? 0) <= s.shortageAbove)) return;
      await this.prisma.ownerAlert.create({ data: { kind, title, body, data } });
      await this.notifications.notifyStaff(OWNER_PERMISSION, { type: 'SYSTEM', title, body, data: { ...data, ownerAlert: kind } });
      if (s.smsPhone) await this.sms.enqueue(s.smsPhone, `Dejavu: ${title}\n${body}`, 'OTHER');
    } catch (e) {
      this.log.warn(`Owner alert failed: ${(e as Error).message}`);
    }
  }

  list(take = 50) {
    return this.prisma.ownerAlert.findMany({ orderBy: { createdAt: 'desc' }, take: Math.min(Math.max(take, 1), 200) });
  }
}
