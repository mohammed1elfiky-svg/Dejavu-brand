import { randomInt } from 'node:crypto';
import { BadRequestException, Body, Controller, Delete, Get, HttpCode, HttpStatus, Injectable, NotFoundException, Param, ParseUUIDPipe, Post, Query, UnprocessableEntityException } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { ArrayMaxSize, IsArray, IsEnum, IsInt, IsNumberString, IsOptional, IsString, IsUUID, Matches, Max, MaxLength, Min } from 'class-validator';
import { normalizePhone, PHONE_REGEX } from '../auth/dto/auth.dto';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { CouponType, CustomerStatus, InstagramStatus, NotificationType } from '../generated/prisma/client';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';

export class BlockDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) reason?: string;
}

export class BlockPhoneDto extends BlockDto {
  @ApiProperty({ example: '777123456' }) @Matches(PHONE_REGEX, { message: 'رقم الجوال غير صحيح' }) phone: string;
}

export class InstagramCodeDto {
  @ApiProperty({ example: '4821', description: 'The code the customer sent from Instagram' }) @Matches(/^\d{4}$/, { message: 'الكود 4 أرقام' }) code: string;
}

export class DormantCouponDto {
  @ApiPropertyOptional({ type: [String], description: 'Leave empty for every dormant customer of [days]' })
  @IsOptional() @IsArray() @ArrayMaxSize(1000) @IsUUID('all', { each: true })
  customerIds?: string[];
  @ApiPropertyOptional({ default: 60 }) @IsOptional() @Type(() => Number) @IsInt() @Min(7) @Max(3650) days?: number;
  @ApiProperty({ enum: CouponType }) @IsEnum(CouponType) type: CouponType;
  @ApiProperty({ example: '10' }) @IsNumberString() value: string;
  @ApiPropertyOptional() @IsOptional() @IsNumberString() minOrderAmount?: string;
  @ApiPropertyOptional() @IsOptional() @IsNumberString() maxDiscount?: string;
  @ApiPropertyOptional({ default: 14 }) @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(365) validDays?: number;
  @ApiPropertyOptional({ description: 'Notification text; the code is added after it' }) @IsOptional() @IsString() @MaxLength(300) message?: string;
}

const CODE_LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const missYouCode = () => `MISS-${Array.from({ length: 6 }, () => CODE_LETTERS[randomInt(CODE_LETTERS.length)]).join('')}`;

/**
 * Owner tools around customers: block a number (and its account) for good, see what customers
 * have in their carts, and verify Instagram usernames with the code they sent from Instagram.
 */
@Injectable()
export class CustomerAdminService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
    private readonly audit: AuditService,
  ) {}

  async block(customerId: string, reason: string | undefined, userId: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId } });
    if (!c || c.code === 'CUS-WALKIN') throw new NotFoundException('Customer not found');
    await this.prisma.$transaction(async (tx) => {
      await tx.customer.update({ where: { id: customerId }, data: { status: CustomerStatus.BLOCKED } });
      await tx.blockedPhone.upsert({ where: { phone: c.phone }, update: { reason }, create: { phone: c.phone, reason, createdById: userId } });
      // Signed out everywhere.
      await tx.refreshToken.deleteMany({ where: { customerId } });
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Customer', entityId: customerId, after: { blocked: true, reason } });
    return { blocked: true };
  }

  async unblock(customerId: string, userId: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId } });
    if (!c) throw new NotFoundException('Customer not found');
    await this.prisma.$transaction([
      this.prisma.customer.update({ where: { id: customerId }, data: { status: CustomerStatus.ACTIVE } }),
      this.prisma.blockedPhone.deleteMany({ where: { phone: c.phone } }),
    ]);
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Customer', entityId: customerId, after: { blocked: false } });
    return { blocked: false };
  }

  /** Customers who bought before but not in the last [days] days, longest away first. */
  async dormant(days: number) {
    const rows = await this.prisma.$queryRaw<{ id: string; last_order_at: Date; orders: number }[]>`
      SELECT o.customer_id AS id, MAX(o.placed_at) AS last_order_at, COUNT(*)::int AS orders
      FROM orders o JOIN customers c ON c.id = o.customer_id
      WHERE o.status NOT IN ('CANCELLED', 'RETURNED') AND c.status = 'ACTIVE' AND c.code NOT IN ('CUS-WALKIN', 'CUS-GIFTPOOL')
      GROUP BY o.customer_id
      HAVING MAX(o.placed_at) < now() - make_interval(days => ${days}::int)
      ORDER BY MAX(o.placed_at) ASC
      LIMIT 1000`;
    const people = new Map(
      (await this.prisma.customer.findMany({ where: { id: { in: rows.map((r) => r.id) } }, select: { id: true, code: true, name: true, phone: true, username: true, governorate: true } })).map((c) => [c.id, c]),
    );
    // Personal coupons still open for them, so the owner does not send a second one by mistake.
    const open = await this.prisma.coupon.findMany({
      where: { customerId: { in: rows.map((r) => r.id) }, isActive: true, usedCount: 0, OR: [{ endsAt: null }, { endsAt: { gt: new Date() } }] },
      select: { customerId: true, code: true },
    });
    const now = Date.now();
    return rows.map((r) => ({
      ...people.get(r.id),
      lastOrderAt: r.last_order_at,
      daysAway: Math.floor((now - new Date(r.last_order_at).getTime()) / 86_400_000),
      orders: r.orders,
      openCoupon: open.find((c) => c.customerId === r.id)?.code ?? null,
    }));
  }

  /** A personal "we miss you" coupon for each chosen dormant customer, with a notification. */
  async dormantCoupon(dto: DormantCouponDto, userId: string) {
    if (dto.type === CouponType.PERCENTAGE && Number(dto.value) > 90) throw new BadRequestException('Percentage must be 90 or less');
    let ids = dto.customerIds ?? [];
    if (!ids.length) ids = (await this.dormant(dto.days ?? 60)).map((c) => c.id!).filter(Boolean);
    const customers = await this.prisma.customer.findMany({ where: { id: { in: ids }, status: CustomerStatus.ACTIVE }, select: { id: true, name: true } });
    if (!customers.length) throw new UnprocessableEntityException('لا يوجد عملاء');
    const endsAt = new Date(Date.now() + (dto.validDays ?? 14) * 86_400_000);
    const off =
      dto.type === CouponType.PERCENTAGE ? `خصم ${Number(dto.value)}%` : dto.type === CouponType.FIXED_AMOUNT ? `خصم ${Number(dto.value)} ر.س` : 'شحن مجاني';
    const sent: { customerId: string; code: string }[] = [];
    for (const c of customers) {
      await this.prisma.$transaction(async (tx) => {
        let code = missYouCode();
        while (await tx.coupon.findUnique({ where: { code }, select: { id: true } })) code = missYouCode();
        await tx.coupon.create({
          data: {
            code,
            customerId: c.id,
            description: 'وحشتنا',
            type: dto.type,
            value: dto.type === CouponType.FREE_SHIPPING ? 0 : dto.value,
            minOrderAmount: dto.minOrderAmount ?? null,
            maxDiscount: dto.maxDiscount ?? null,
            usageLimit: 1,
            perCustomerLimit: 1,
            startsAt: new Date(),
            endsAt,
          },
        });
        await this.notifications.notifyCustomer(tx, c.id, {
          type: NotificationType.PROMOTION,
          title: `وحشتنا يا ${c.name.split(' ')[0]} 💛`,
          body: `${dto.message?.trim() || `${off} على طلبك القادم`} — الكود: ${code} (صالح حتى ${endsAt.toISOString().slice(0, 10)})`,
          data: { coupon: code, screen: 'home' },
        });
        sent.push({ customerId: c.id, code });
      });
    }
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Coupon', after: { dormant: sent.length, type: dto.type, value: dto.value } });
    return { sent: sent.length, coupons: sent };
  }

  blockedPhones() {
    return this.prisma.blockedPhone.findMany({ orderBy: { createdAt: 'desc' }, take: 500 });
  }

  /** Blocks a number that has no account (yet): it can never sign up. */
  async blockPhone(dto: BlockPhoneDto, userId: string) {
    const phone = normalizePhone(dto.phone);
    const row = await this.prisma.blockedPhone.upsert({ where: { phone }, update: { reason: dto.reason }, create: { phone, reason: dto.reason, createdById: userId } });
    const c = await this.prisma.customer.findUnique({ where: { phone }, select: { id: true } });
    if (c) await this.block(c.id, dto.reason, userId);
    await this.audit.log({ userId, action: 'CREATE', entityType: 'BlockedPhone', entityId: row.id, after: row });
    return row;
  }

  async unblockPhone(id: string, userId: string) {
    const row = await this.prisma.blockedPhone.findUnique({ where: { id } });
    if (!row) throw new NotFoundException('Not blocked');
    const c = await this.prisma.customer.findUnique({ where: { phone: row.phone }, select: { id: true, status: true } });
    if (c && c.status === CustomerStatus.BLOCKED) await this.unblock(c.id, userId);
    else await this.prisma.blockedPhone.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'BlockedPhone', entityId: id, before: row });
    return { deleted: true };
  }

  /** Every cart with something in it: who, what and how many, newest activity first. */
  async carts() {
    const carts = await this.prisma.cart.findMany({
      where: { items: { some: {} } },
      orderBy: { updatedAt: 'desc' },
      take: 200,
      select: {
        id: true,
        updatedAt: true,
        couponCode: true,
        customer: { select: { id: true, code: true, name: true, phone: true, username: true } },
        items: {
          orderBy: { addedAt: 'desc' },
          select: {
            quantity: true,
            addedAt: true,
            variant: { select: { id: true, sku: true, size: true, color: true, price: true, product: { select: { nameAr: true, basePrice: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } } },
          },
        },
      },
    });
    return carts.map((c) => ({
      id: c.id,
      updatedAt: c.updatedAt,
      couponCode: c.couponCode,
      customer: c.customer,
      pieces: c.items.reduce((n, i) => n + i.quantity, 0),
      items: c.items.map((i) => ({
        variantId: i.variant.id,
        sku: i.variant.sku,
        name: i.variant.product.nameAr,
        size: i.variant.size,
        color: i.variant.color,
        quantity: i.quantity,
        price: i.variant.price ?? i.variant.product.basePrice,
        imageUrl: i.variant.product.images[0]?.url ?? null,
        addedAt: i.addedAt,
      })),
    }));
  }

  /** Usernames waiting for their Instagram check, oldest first. */
  instagramPending() {
    return this.prisma.customer.findMany({
      where: { igStatus: InstagramStatus.PENDING },
      orderBy: { updatedAt: 'asc' },
      select: { id: true, code: true, name: true, phone: true, username: true, avatarUrl: true, updatedAt: true },
    });
  }

  /** The code the customer sent by Instagram DM matches: the username shows the Instagram badge. */
  async verifyInstagram(customerId: string, code: string, userId: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { igStatus: true, igCode: true, username: true } });
    if (!c || c.igStatus !== InstagramStatus.PENDING) throw new UnprocessableEntityException('لا يوجد طلب توثيق لهذا العميل');
    if (c.igCode !== code) throw new BadRequestException('الكود غير مطابق للكود المرسل للعميل');
    await this.prisma.$transaction(async (tx) => {
      await tx.customer.update({ where: { id: customerId }, data: { igStatus: InstagramStatus.VERIFIED, igCode: null, igVerifiedAt: new Date() } });
      await this.notifications.notifyCustomer(tx, customerId, {
        type: NotificationType.SYSTEM,
        title: 'تم توثيق يوزرك ✔️',
        body: `أصبح @${c.username} موثقاً بشارة انستجرام في ديجافو.`,
        data: { screen: 'profile' },
      });
    });
    await this.audit.log({ userId, action: 'APPROVE', entityType: 'InstagramUsername', entityId: customerId, after: { username: c.username } });
    return { verified: true };
  }

  async rejectInstagram(customerId: string, userId: string) {
    const res = await this.prisma.customer.updateMany({ where: { id: customerId, igStatus: InstagramStatus.PENDING }, data: { igStatus: InstagramStatus.NONE, igCode: null } });
    if (!res.count) throw new UnprocessableEntityException('لا يوجد طلب توثيق لهذا العميل');
    await this.audit.log({ userId, action: 'CANCEL', entityType: 'InstagramUsername', entityId: customerId });
    return { verified: false };
  }
}

@ApiTags('Admin · Customers')
@Controller('admin')
export class AdminCustomerToolsController {
  constructor(private readonly tools: CustomerAdminService) {}

  /** Blocks the customer and their number: signed out, and the number can never sign up again. */
  @RequirePermissions('customers.write')
  @Post('customers/:id/block')
  @HttpCode(HttpStatus.OK)
  block(@Param('id', ParseUUIDPipe) id: string, @Body() dto: BlockDto, @CurrentUser() u: AuthPrincipal) {
    return this.tools.block(id, dto.reason, u.id);
  }

  @RequirePermissions('customers.write')
  @Post('customers/:id/unblock')
  @HttpCode(HttpStatus.OK)
  unblock(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.tools.unblock(id, u.id);
  }

  @RequirePermissions('customers.read')
  @Get('blocked-phones')
  blocked() {
    return this.tools.blockedPhones();
  }

  @RequirePermissions('customers.write')
  @Post('blocked-phones')
  blockPhone(@Body() dto: BlockPhoneDto, @CurrentUser() u: AuthPrincipal) {
    return this.tools.blockPhone(dto, u.id);
  }

  @RequirePermissions('customers.write')
  @Delete('blocked-phones/:id')
  @HttpCode(HttpStatus.OK)
  unblockPhone(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.tools.unblockPhone(id, u.id);
  }

  /** Customers who stopped buying (default: nothing in 60 days). */
  @RequirePermissions('customers.read')
  @Get('dormant-customers')
  dormant(@Query('days') days?: string) {
    return this.tools.dormant(Math.min(Math.max(Number(days) || 60, 7), 3650));
  }

  @RequirePermissions('coupons.manage')
  @Post('dormant-customers/coupon')
  dormantCoupon(@Body() dto: DormantCouponDto, @CurrentUser() u: AuthPrincipal) {
    return this.tools.dormantCoupon(dto, u.id);
  }

  /** What customers have in their carts right now. */
  @RequirePermissions('customers.read')
  @Get('carts')
  carts() {
    return this.tools.carts();
  }

  @RequirePermissions('customers.read')
  @Get('instagram-verifications')
  instagramPending() {
    return this.tools.instagramPending();
  }

  @RequirePermissions('customers.write')
  @Post('customers/:id/instagram/verify')
  @HttpCode(HttpStatus.OK)
  verifyInstagram(@Param('id', ParseUUIDPipe) id: string, @Body() dto: InstagramCodeDto, @CurrentUser() u: AuthPrincipal) {
    return this.tools.verifyInstagram(id, dto.code, u.id);
  }

  @RequirePermissions('customers.write')
  @Post('customers/:id/instagram/reject')
  @HttpCode(HttpStatus.OK)
  rejectInstagram(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.tools.rejectInstagram(id, u.id);
  }
}
