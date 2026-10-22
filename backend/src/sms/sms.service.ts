import { BadRequestException, ConflictException, HttpException, HttpStatus, Injectable, NotFoundException } from '@nestjs/common';
import { createHash, randomInt } from 'node:crypto';
import { Prisma, SmsStatus } from '../generated/prisma/client';
import { normalizePhone } from '../auth/dto/auth.dto';
import { PrismaService, Tx } from '../prisma/prisma.service';

const REQUIRE_OTP = 'auth.requirePhoneOtp';
const GATEWAY_SEEN = 'sms.gatewaySeenAt';
const OTP_MINUTES = 10;
/** A message taken by the gateway but not reported after this long is offered again. */
const STALE_CLAIM_MS = 5 * 60_000;

const hash = (phone: string, code: string) => createHash('sha256').update(`${phone}:${code}`).digest('hex');

/**
 * Text messages sent from the store's own phone. The admin app on that phone is the
 * gateway: it takes pending messages and sends them with its SIM, then reports back.
 */
@Injectable()
export class SmsService {
  constructor(private readonly prisma: PrismaService) {}

  /** Queues a text to [phone]; part of [tx] when given, so a rolled-back change sends nothing. */
  async enqueue(phone: string, body: string, purpose: 'OTP' | 'RECEIPT' | 'EXCHANGE' | 'OTHER', tx: Tx | PrismaService = this.prisma) {
    return tx.outgoingSms.create({ data: { phone: dialable(phone), body, purpose } });
  }

  // ------------------------------------------------------------- gateway ---

  /** The gateway phone takes up to [limit] messages to send now. */
  async claim(limit = 10) {
    const now = new Date();
    await this.setSetting(GATEWAY_SEEN, now.toISOString());
    const rows = await this.prisma.outgoingSms.findMany({
      where: {
        OR: [{ status: SmsStatus.PENDING }, { status: SmsStatus.SENDING, claimedAt: { lt: new Date(now.getTime() - STALE_CLAIM_MS) } }],
        attempts: { lt: 3 },
        createdAt: { gt: new Date(now.getTime() - 24 * 3600_000) },
      },
      orderBy: { createdAt: 'asc' },
      take: Math.min(Math.max(limit, 1), 50),
    });
    if (!rows.length) return [];
    await this.prisma.outgoingSms.updateMany({
      where: { id: { in: rows.map((r) => r.id) } },
      data: { status: SmsStatus.SENDING, claimedAt: now, attempts: { increment: 1 } },
    });
    return rows.map((r) => ({ id: r.id, phone: r.phone, body: r.body, purpose: r.purpose }));
  }

  async report(id: string, ok: boolean, error?: string) {
    const row = await this.prisma.outgoingSms.findUnique({ where: { id } });
    if (!row) throw new NotFoundException('Message not found');
    return this.prisma.outgoingSms.update({
      where: { id },
      data: ok ? { status: SmsStatus.SENT, sentAt: new Date(), error: null } : { status: row.attempts >= 3 ? SmsStatus.FAILED : SmsStatus.PENDING, error: error?.slice(0, 300) },
    });
  }

  async recent(status?: SmsStatus) {
    return this.prisma.outgoingSms.findMany({ where: { status }, orderBy: { createdAt: 'desc' }, take: 100 });
  }

  async settings() {
    const [require, seen] = await Promise.all([this.getSetting(REQUIRE_OTP), this.getSetting(GATEWAY_SEEN)]);
    const seenAt = typeof seen === 'string' ? seen : null;
    return {
      requirePhoneOtp: require === true,
      gatewaySeenAt: seenAt,
      /** The gateway phone checked in within the last 10 minutes. */
      gatewayOnline: !!seenAt && Date.now() - new Date(seenAt).getTime() < 10 * 60_000,
      pending: await this.prisma.outgoingSms.count({ where: { status: { in: [SmsStatus.PENDING, SmsStatus.SENDING] } } }),
    };
  }

  async updateSettings(dto: { requirePhoneOtp?: boolean }) {
    if (dto.requirePhoneOtp !== undefined) await this.setSetting(REQUIRE_OTP, dto.requirePhoneOtp);
    return this.settings();
  }

  // ----------------------------------------------------------------- OTP ---

  async otpRequired() {
    return (await this.getSetting(REQUIRE_OTP)) === true;
  }

  /** Texts a 6-digit code to a phone number that is about to register. */
  async sendOtp(rawPhone: string) {
    const phone = normalizePhone(rawPhone);
    if (await this.prisma.customer.findUnique({ where: { phone }, select: { id: true } })) {
      throw new ConflictException('Phone number already registered');
    }
    const recent = await this.prisma.phoneOtp.count({ where: { phone, createdAt: { gt: new Date(Date.now() - OTP_MINUTES * 60_000) } } });
    if (recent >= 3) throw new HttpException('Too many codes requested; try again in a few minutes', HttpStatus.TOO_MANY_REQUESTS);
    const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
    await this.prisma.$transaction(async (tx) => {
      await tx.phoneOtp.create({ data: { phone, codeHash: hash(phone, code), expiresAt: new Date(Date.now() + OTP_MINUTES * 60_000) } });
      await this.enqueue(phone, `رمز التحقق لتطبيق Dejavu: ${code}\nصالح لمدة ${OTP_MINUTES} دقائق. لا تشاركه مع أحد.`, 'OTP', tx);
    });
    return { sent: true, expiresInMinutes: OTP_MINUTES };
  }

  /** Checks the code for [phone]; throws when it is wrong, expired or used too often. */
  async verifyOtp(tx: Tx, rawPhone: string, code: string | undefined) {
    const phone = normalizePhone(rawPhone);
    if (!code) throw new BadRequestException('Enter the verification code sent to your phone');
    const otp = await tx.phoneOtp.findFirst({ where: { phone, verifiedAt: null, expiresAt: { gt: new Date() } }, orderBy: { createdAt: 'desc' } });
    if (!otp) throw new BadRequestException('The verification code expired; request a new one');
    if (otp.attempts >= 5) throw new BadRequestException('Too many wrong codes; request a new one');
    if (otp.codeHash !== hash(phone, code.trim())) {
      await tx.phoneOtp.update({ where: { id: otp.id }, data: { attempts: { increment: 1 } } });
      throw new BadRequestException('Wrong verification code');
    }
    await tx.phoneOtp.update({ where: { id: otp.id }, data: { verifiedAt: new Date() } });
  }

  // -------------------------------------------------------------- helpers ---

  private async getSetting(key: string) {
    return (await this.prisma.setting.findUnique({ where: { key } }))?.value ?? null;
  }

  private async setSetting(key: string, value: Prisma.InputJsonValue) {
    await this.prisma.setting.upsert({ where: { key }, update: { value }, create: { key, value } });
  }
}

/** Numbers as the phone dials them: Yemeni 9-digit mobiles get +967, the rest stay local. */
export function dialable(phone: string) {
  const p = phone.replace(/[\s-]/g, '');
  return /^7\d{8}$/.test(p) ? `+967${p}` : p;
}
