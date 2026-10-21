import { Injectable, Logger, NotFoundException, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { AppType, NotificationType } from '../generated/prisma/client';
import { AuthPrincipal } from '../common/decorators/auth.decorators';
import { paginated } from '../common/dto/pagination.dto';
import { Env } from '../config/env';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { FcmClient, parseServiceAccount } from './fcm.client';

export interface NewNotification {
  type: NotificationType;
  title: string;
  body: string;
  /** Lets the app open the right screen, e.g. { order: "SO-2026-000123" }. */
  data?: Record<string, string>;
}

const BATCH = 50;

/**
 * In-app inbox plus push. Notifications are written inside the business
 * transaction, so a rolled-back change never notifies anyone; a dispatcher then
 * pushes unsent rows (an outbox), retrying on the next tick if FCM is down.
 */
@Injectable()
export class NotificationsService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('Notifications');
  private readonly fcm: FcmClient | null;
  private timer?: NodeJS.Timeout;
  private kickTimer?: NodeJS.Timeout;
  private running = false;
  private closed = false;

  constructor(
    private readonly prisma: PrismaService,
    config: ConfigService<Env, true>,
  ) {
    const sa = config.get('FCM_SERVICE_ACCOUNT', { infer: true });
    this.fcm = sa ? new FcmClient(parseServiceAccount(sa)) : null;
  }

  get pushEnabled() {
    return this.fcm !== null;
  }

  onModuleInit() {
    if (this.fcm) this.log.log('Push notifications enabled (FCM)');
    if (process.env.JEST_WORKER_ID) return;
    this.timer = setInterval(() => void this.dispatch(), 15_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    this.closed = true;
    clearInterval(this.timer);
    clearTimeout(this.kickTimer);
  }

  // ------------------------------------------------------------- writing ---

  /** Adds to the customer's inbox as part of [tx]; pushed once the transaction commits. */
  async notifyCustomer(tx: Tx, customerId: string, n: NewNotification) {
    await tx.notification.create({ data: { customerId, type: n.type, title: n.title, body: n.body, data: n.data ?? {} } });
    this.kick();
  }

  /** Push-only alert to staff who hold [permission] (e.g. a new order for the sales team). */
  async notifyStaff(permission: string, n: NewNotification) {
    if (!this.fcm) return;
    try {
      const devices = await this.prisma.device.findMany({
        where: {
          appType: AppType.ADMIN,
          user: {
            status: 'ACTIVE',
            roles: { some: { role: { permissions: { some: { permission: { key: permission } } } } } },
          },
        },
        select: { id: true, fcmToken: true },
      });
      await this.pushTo(devices, n, 'staff');
    } catch (e) {
      this.log.warn(`Staff push failed: ${(e as Error).message}`);
    }
  }

  // ------------------------------------------------------------- dispatch ---

  private kick() {
    if (this.closed) return;
    clearTimeout(this.kickTimer);
    // Shortly after the surrounding transaction commits.
    this.kickTimer = setTimeout(() => void this.dispatch(), 300);
    this.kickTimer.unref();
  }

  /** Pushes unsent notifications. Returns how many rows were processed. */
  async dispatch(): Promise<number> {
    if (this.running || this.closed) return 0;
    this.running = true;
    let processed = 0;
    try {
      for (;;) {
        const rows = await this.prisma.notification.findMany({
          where: { pushedAt: null },
          orderBy: { sentAt: 'asc' },
          take: BATCH,
        });
        if (!rows.length) break;
        for (const n of rows) {
          if (this.fcm && n.customerId) {
            const devices = await this.prisma.device.findMany({
              where: { customerId: n.customerId, appType: AppType.CUSTOMER },
              select: { id: true, fcmToken: true },
            });
            const data = { ...((n.data as Record<string, string>) ?? {}), notificationId: n.id, type: n.type };
            await this.pushTo(devices, { type: n.type, title: n.title, body: n.body, data }, 'orders');
          }
          await this.prisma.notification.update({ where: { id: n.id }, data: { pushedAt: new Date() } });
          processed += 1;
        }
        if (rows.length < BATCH) break;
      }
    } catch (e) {
      if (!this.closed) this.log.warn(`Dispatch stopped, will retry: ${(e as Error).message}`);
    } finally {
      this.running = false;
    }
    return processed;
  }

  private async pushTo(devices: { id: string; fcmToken: string }[], n: NewNotification, channel: string) {
    if (!this.fcm) return;
    for (const d of devices) {
      const outcome = await this.fcm.send({ token: d.fcmToken, title: n.title, body: n.body, data: n.data, channel });
      if (outcome === 'unregistered') await this.prisma.device.deleteMany({ where: { id: d.id } });
    }
  }

  // --------------------------------------------------------------- inbox ---

  async inbox(customerId: string, q: { page: number; pageSize: number; skip: number }) {
    const where = { customerId };
    const [items, total, unread] = await Promise.all([
      this.prisma.notification.findMany({
        where,
        select: { id: true, type: true, title: true, body: true, data: true, readAt: true, sentAt: true },
        orderBy: { sentAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.notification.count({ where }),
      this.unreadCount(customerId),
    ]);
    return { ...paginated(items, total, q), unread };
  }

  unreadCount(customerId: string) {
    return this.prisma.notification.count({ where: { customerId, readAt: null } });
  }

  async markRead(customerId: string, id: string) {
    const res = await this.prisma.notification.updateMany({ where: { id, customerId, readAt: null }, data: { readAt: new Date() } });
    if (!res.count && !(await this.prisma.notification.count({ where: { id, customerId } }))) {
      throw new NotFoundException('Notification not found');
    }
    return { unread: await this.unreadCount(customerId) };
  }

  async markAllRead(customerId: string) {
    await this.prisma.notification.updateMany({ where: { customerId, readAt: null }, data: { readAt: new Date() } });
    return { unread: 0 };
  }

  // ------------------------------------------------------------- devices ---

  /** Registers (or moves) an FCM token to the signed-in user of this app. */
  async registerDevice(p: AuthPrincipal, token: string, appVersion?: string) {
    const owner =
      p.type === 'staff'
        ? { appType: AppType.ADMIN, userId: p.id, customerId: null }
        : { appType: AppType.CUSTOMER, customerId: p.id, userId: null };
    await this.prisma.device.upsert({
      where: { fcmToken: token },
      create: { fcmToken: token, appVersion, ...owner },
      update: { appVersion, lastSeenAt: new Date(), ...owner },
    });
    return { registered: true, pushEnabled: this.pushEnabled };
  }

  async unregisterDevice(p: AuthPrincipal, token: string) {
    await this.prisma.device.deleteMany({
      where: { fcmToken: token, ...(p.type === 'staff' ? { userId: p.id } : { customerId: p.id }) },
    });
  }
}
