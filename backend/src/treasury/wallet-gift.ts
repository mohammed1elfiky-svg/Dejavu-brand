import { BadRequestException, Body, Controller, Get, HttpCode, HttpStatus, Injectable, NotFoundException, Post, Query, UnauthorizedException, UnprocessableEntityException } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsNotEmpty, IsOptional, IsString, Matches, MaxLength } from 'class-validator';
import { normalizePhone, PHONE_REGEX } from '../auth/dto/auth.dto';
import { isUsername, normalizeUsername } from '../common/username';
import { verifyPassword } from '../auth/password';
import { AuditService } from '../common/audit.service';
import { CurrencyService, formatIn, inCurrency } from '../common/currency.service';
import { AuthPrincipal, CurrentUser, CustomerOnly } from '../common/decorators/auth.decorators';
import { CustomerStatus, NotificationType } from '../generated/prisma/client';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';
import { CustomerAccountsService } from './customer-accounts.service';

export class WalletGiftDto {
  @ApiProperty({ example: '777123456', description: "The receiver's mobile number, or their username (@sara.ali)" })
  @IsString()
  @MaxLength(40)
  phone: string;

  @ApiProperty({ example: 'YER' })
  @IsString()
  @MaxLength(5)
  currency: string;

  @ApiProperty({ example: '5000' })
  @Matches(/^\d+(\.\d{1,2})?$/, { message: 'المبلغ غير صحيح' })
  amount: string;

  @ApiProperty({ description: 'The sender confirms with their password' })
  @IsString()
  @IsNotEmpty()
  password: string;

  @ApiPropertyOptional({ example: 'كل عام وأنت بخير' })
  @IsOptional()
  @IsString()
  @MaxLength(120)
  note?: string;
}

/** "محمد علي" → "محمد ع." so the sender can check the receiver without seeing private data. */
const maskName = (name: string) => {
  const [first, ...rest] = name.trim().split(/\s+/);
  return rest.length ? `${first} ${rest[rest.length - 1].charAt(0)}.` : first;
};

/** A customer gives part of their wallet balance to another customer, at once. */
@Injectable()
export class WalletGiftService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly accounts: CustomerAccountsService,
    private readonly currencies: CurrencyService,
    private readonly notifications: NotificationsService,
    private readonly audit: AuditService,
  ) {}

  /** [who]: a mobile number or a username (with or without @). */
  private async receiver(who: string, senderId: string) {
    const raw = who.trim();
    const byName = /^@?[A-Za-z]/.test(raw);
    if (byName ? !isUsername(raw) : !PHONE_REGEX.test(raw.replace(/[\s-]/g, ''))) throw new BadRequestException(byName ? 'اليوزر غير صحيح' : 'رقم الجوال غير صحيح');
    const select = { id: true, name: true, status: true, code: true } as const;
    const to = byName
      ? await this.prisma.customer.findUnique({ where: { username: normalizeUsername(raw) }, select })
      : await this.prisma.customer.findUnique({ where: { phone: normalizePhone(raw.replace(/[\s-]/g, '')) }, select });
    if (!to || to.status !== CustomerStatus.ACTIVE || to.code === 'CUS-WALKIN') throw new NotFoundException(byName ? 'لا يوجد عميل بهذا اليوزر' : 'لا يوجد عميل مسجّل بهذا الرقم');
    if (to.id === senderId) throw new BadRequestException('لا يمكنك الإهداء لنفسك');
    return to;
  }

  /** Who gets the gift (masked name), before the sender confirms. */
  async recipient(senderId: string, phone: string) {
    const to = await this.receiver(phone ?? '', senderId);
    return { name: maskName(to.name) };
  }

  async gift(senderId: string, dto: WalletGiftDto) {
    const sender = await this.prisma.customer.findUniqueOrThrow({ where: { id: senderId }, select: { name: true, passwordHash: true } });
    if (!(await verifyPassword(dto.password, sender.passwordHash))) throw new UnauthorizedException('كلمة المرور غير صحيحة');
    const to = await this.receiver(dto.phone, senderId);
    const currency = await this.currencies.requireActive(dto.currency);
    const amount = inCurrency(dto.amount, currency.code);
    if (!amount.gt(0)) throw new BadRequestException('أدخل مبلغاً أكبر من صفر');

    await this.prisma.$transaction(async (tx) => {
      const available = (await this.accounts.balances(senderId, tx)).find((b) => b.currency === currency.code)?.available ?? inCurrency(0, currency.code);
      if (available.lt(amount)) throw new UnprocessableEntityException(`رصيدك المتاح ${formatIn(available, currency.code)} لا يكفي`);
      const note = dto.note?.trim();
      const description = `إهداء رصيد من ${sender.name} إلى ${to.name}${note ? ` — ${note}` : ''}`;
      const entry = await this.accounts.transfer(tx, { fromId: senderId, toId: to.id, currency: currency.code, amount, description });
      await this.notifications.notifyCustomer(tx, to.id, {
        type: NotificationType.WALLET,
        title: 'وصلتك هدية رصيد 🎁',
        body: `أهداك ${maskName(sender.name)} ${formatIn(amount, currency.code)}${note ? `: ${note}` : ''}`,
        data: { kind: 'wallet' },
      });
      await this.audit.log({ action: 'CREATE', entityType: 'WalletGift', entityId: entry.id, after: { senderId, receiverId: to.id, currency: currency.code, amount: amount.toFixed() } }, tx);
    });
    return { sent: true, to: maskName(to.name), amount: formatIn(amount, currency.code), wallet: await this.accounts.wallet(senderId) };
  }
}

@ApiTags('Store · Wallet')
@CustomerOnly()
@Controller('store/me/wallet/gift')
export class StoreWalletGiftController {
  constructor(private readonly gifts: WalletGiftService) {}

  /** The receiver's masked name, to check the number before sending. */
  @Get('recipient')
  recipient(@CurrentUser() u: AuthPrincipal, @Query('phone') phone = '') {
    return this.gifts.recipient(u.id, phone);
  }

  @Post()
  @HttpCode(HttpStatus.OK)
  gift(@CurrentUser() u: AuthPrincipal, @Body() dto: WalletGiftDto) {
    return this.gifts.gift(u.id, dto);
  }
}
