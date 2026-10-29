import { BadRequestException, Body, Controller, Get, HttpCode, HttpStatus, Injectable, NotFoundException, Param, ParseUUIDPipe, Patch, Post, Put, UnprocessableEntityException } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsDateString, IsIn, IsInt, IsNumber, IsOptional, IsString, Max, MaxLength, Min, ValidateIf } from 'class-validator';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { dec, money } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';

const KEY = 'loyalty.settings';

export interface LoyaltySettings {
  enabled: boolean;
  /** Who earns: everyone, retail customers only, merchants only, or only customers switched on one by one. */
  scope: 'ALL' | 'RETAIL' | 'MERCHANTS' | 'SELECTED';
  /** Points per 1 SAR spent. */
  pointsPerSar: number;
  /** SAR of wallet credit for every 100 points. */
  sarPer100Points: number;
  /** Purchases from this day count (null = from when it was switched on). */
  startsAt: string | null;
  minRedeemPoints: number;
}

const DEFAULTS: LoyaltySettings = { enabled: false, scope: 'ALL', pointsPerSar: 1, sarPer100Points: 5, startsAt: null, minRedeemPoints: 100 };

export class LoyaltySettingsDto {
  @ApiPropertyOptional() @IsOptional() @IsBoolean() enabled?: boolean;
  @ApiPropertyOptional({ enum: ['ALL', 'RETAIL', 'MERCHANTS', 'SELECTED'] }) @IsOptional() @IsIn(['ALL', 'RETAIL', 'MERCHANTS', 'SELECTED']) scope?: LoyaltySettings['scope'];
  @ApiPropertyOptional({ example: 1 }) @IsOptional() @IsNumber() @Min(0) @Max(100) pointsPerSar?: number;
  @ApiPropertyOptional({ example: 5 }) @IsOptional() @IsNumber() @Min(0) @Max(100) sarPer100Points?: number;
  @ApiPropertyOptional({ description: 'Count purchases from this day; null = from now' }) @IsOptional() @ValidateIf((_, v) => v !== null) @IsDateString() startsAt?: string | null;
  @ApiPropertyOptional({ example: 100 }) @IsOptional() @IsInt() @Min(1) minRedeemPoints?: number;
}

export class CustomerLoyaltyDto {
  @ApiPropertyOptional({ description: 'true / false for this customer; null or left out follows the store setting', nullable: true })
  @IsOptional()
  @IsBoolean()
  enabled?: boolean | null;
}

export class AdjustPointsDto {
  @ApiProperty({ example: 50, description: '+ adds, - removes' }) @IsInt() @Min(-1_000_000) @Max(1_000_000) points: number;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) note?: string;
}

export class RedeemPointsDto {
  @ApiProperty({ example: 100 }) @IsInt() @Min(1) points: number;
}

/**
 * Loyalty points: earned on every purchase (app orders when received, till sales at once) by the
 * customers the owner switched on, and turned into wallet credit by the customer.
 */
@Injectable()
export class LoyaltyService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly accounts: CustomerAccountsService,
    private readonly notifications: NotificationsService,
    private readonly audit: AuditService,
  ) {}

  async settings(db: Tx | PrismaService = this.prisma): Promise<LoyaltySettings> {
    const row = await db.setting.findUnique({ where: { key: KEY } });
    return { ...DEFAULTS, ...((row?.value as Partial<LoyaltySettings>) ?? {}) };
  }

  async setSettings(dto: LoyaltySettingsDto, userId: string) {
    const before = await this.settings();
    const value: LoyaltySettings = { ...before, ...dto } as LoyaltySettings;
    // Switched on without a start day: purchases count from now.
    if (dto.enabled && !before.enabled && dto.startsAt === undefined && !before.startsAt) value.startsAt = new Date().toISOString();
    await this.prisma.setting.upsert({ where: { key: KEY }, update: { value: value as object }, create: { key: KEY, value: value as object } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Setting', entityId: KEY, before, after: value });
    return value;
  }

  private eligible(c: { isMerchant: boolean; loyaltyEnabled: boolean | null; code: string }, s: LoyaltySettings) {
    if (!s.enabled || c.code === 'CUS-WALKIN') return false;
    if (c.loyaltyEnabled !== null) return c.loyaltyEnabled;
    return s.scope === 'ALL' || (s.scope === 'RETAIL' && !c.isMerchant) || (s.scope === 'MERCHANTS' && c.isMerchant);
  }

  /** Points for a sale (once): what was paid for the goods, times the rate. */
  async earnForOrder(tx: Tx, orderId: string) {
    const s = await this.settings(tx);
    if (!s.enabled || s.pointsPerSar <= 0) return 0;
    const order = await tx.order.findUnique({
      where: { id: orderId },
      select: { id: true, number: true, status: true, placedAt: true, grandTotal: true, shippingFee: true, customer: { select: { id: true, code: true, isMerchant: true, loyaltyEnabled: true } } },
    });
    if (!order || order.status === 'CANCELLED' || order.status === 'RETURNED') return 0;
    if (!this.eligible(order.customer, s)) return 0;
    if (s.startsAt && order.placedAt < new Date(s.startsAt)) return 0;
    if (await tx.loyaltyEntry.count({ where: { orderId, kind: 'EARN' } })) return 0;
    const points = Math.floor(dec(order.grandTotal).sub(order.shippingFee).toNumber() * s.pointsPerSar);
    if (points <= 0) return 0;
    await tx.loyaltyEntry.create({ data: { customerId: order.customer.id, points, kind: 'EARN', orderId, note: order.number } });
    await tx.customer.update({ where: { id: order.customer.id }, data: { loyaltyPoints: { increment: points } } });
    await this.notifications.notifyCustomer(tx, order.customer.id, {
      type: 'PROMOTION',
      title: `ربحت ${points} نقطة ⭐`,
      body: `من طلبك ${order.number}. حوّل نقاطك إلى رصيد من «نقاطي».`,
      data: { screen: 'loyalty' },
    });
    return points;
  }

  /** A deleted / cancelled / refused sale takes back what it earned. */
  async reverseForOrder(tx: Tx, orderId: string) {
    const earned = await tx.loyaltyEntry.findMany({ where: { orderId, kind: 'EARN' } });
    if (!earned.length || (await tx.loyaltyEntry.count({ where: { orderId, kind: 'REVERSE' } }))) return;
    for (const e of earned) {
      await tx.loyaltyEntry.create({ data: { customerId: e.customerId, points: -e.points, kind: 'REVERSE', orderId, note: e.note } });
      await tx.customer.update({ where: { id: e.customerId }, data: { loyaltyPoints: { decrement: e.points } } });
    }
  }

  async adjust(customerId: string, dto: AdjustPointsDto, userId: string) {
    if (!dto.points) throw new BadRequestException('Give the points to add or remove');
    await this.prisma.$transaction(async (tx) => {
      const c = await tx.customer.findUnique({ where: { id: customerId }, select: { id: true } });
      if (!c) throw new NotFoundException('Customer not found');
      await tx.loyaltyEntry.create({ data: { customerId, points: dto.points, kind: 'ADJUST', note: dto.note?.trim() || null, createdById: userId } });
      await tx.customer.update({ where: { id: customerId }, data: { loyaltyPoints: { increment: dto.points } } });
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'LoyaltyPoints', entityId: customerId, after: dto });
    return this.forCustomer(customerId);
  }

  async setCustomer(customerId: string, enabled: boolean | null, userId: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId } });
    if (!c) throw new NotFoundException('Customer not found');
    await this.prisma.customer.update({ where: { id: customerId }, data: { loyaltyEnabled: enabled } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Customer', entityId: customerId, after: { loyaltyEnabled: enabled } });
    return this.forCustomer(customerId);
  }

  /** Points as wallet credit (SAR), [minRedeemPoints] at least. */
  async redeem(customerId: string, points: number) {
    const s = await this.settings();
    if (!s.enabled) throw new UnprocessableEntityException('نقاط الولاء غير مفعّلة حالياً');
    if (points < s.minRedeemPoints) throw new UnprocessableEntityException(`أقل عدد للاستبدال ${s.minRedeemPoints} نقطة`);
    const value = money(dec(points).mul(s.sarPer100Points).div(100));
    if (!value.gt(0)) throw new UnprocessableEntityException('النقاط لا تكفي');
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM customers WHERE id = ${customerId}::uuid FOR UPDATE`;
      const c = await tx.customer.findUniqueOrThrow({ where: { id: customerId }, select: { loyaltyPoints: true } });
      if (c.loyaltyPoints < points) throw new UnprocessableEntityException('نقاطك لا تكفي');
      const entry = await tx.loyaltyEntry.create({ data: { customerId, points: -points, kind: 'REDEEM', note: `${value.toFixed(2)} ر.س` } });
      await tx.customer.update({ where: { id: customerId }, data: { loyaltyPoints: { decrement: points } } });
      await this.accounts.reward(tx, { customerId, amountSar: value, description: `Loyalty points ${points} → wallet`, sourceId: entry.id });
    });
    return this.forCustomer(customerId);
  }

  async forCustomer(customerId: string) {
    const [c, s, entries] = await Promise.all([
      this.prisma.customer.findUnique({ where: { id: customerId }, select: { loyaltyPoints: true, loyaltyEnabled: true, isMerchant: true, code: true } }),
      this.settings(),
      this.prisma.loyaltyEntry.findMany({ where: { customerId }, orderBy: { createdAt: 'desc' }, take: 50 }),
    ]);
    if (!c) throw new NotFoundException('Customer not found');
    return {
      points: c.loyaltyPoints,
      /** SAR the points are worth now. */
      value: money(dec(Math.max(c.loyaltyPoints, 0)).mul(s.sarPer100Points).div(100)),
      earning: this.eligible(c, s),
      customerSetting: c.loyaltyEnabled,
      rules: { enabled: s.enabled, pointsPerSar: s.pointsPerSar, sarPer100Points: s.sarPer100Points, minRedeemPoints: s.minRedeemPoints },
      entries,
    };
  }
}

@ApiTags('Admin · Loyalty')
@Controller('admin')
export class AdminLoyaltyController {
  constructor(private readonly loyalty: LoyaltyService) {}

  @RequirePermissions('customers.read')
  @Get('loyalty/settings')
  settings() {
    return this.loyalty.settings();
  }

  @RequirePermissions('settings.manage')
  @Put('loyalty/settings')
  setSettings(@Body() dto: LoyaltySettingsDto, @CurrentUser() u: AuthPrincipal) {
    return this.loyalty.setSettings(dto, u.id);
  }

  @RequirePermissions('customers.read')
  @Get('customers/:id/loyalty')
  forCustomer(@Param('id', ParseUUIDPipe) id: string) {
    return this.loyalty.forCustomer(id);
  }

  /** Switch points on / off for one customer (null = follow the store setting). */
  @RequirePermissions('customers.write')
  @Patch('customers/:id/loyalty')
  setCustomer(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CustomerLoyaltyDto, @CurrentUser() u: AuthPrincipal) {
    return this.loyalty.setCustomer(id, dto.enabled ?? null, u.id);
  }

  @RequirePermissions('customers.write')
  @Post('customers/:id/loyalty/adjust')
  adjust(@Param('id', ParseUUIDPipe) id: string, @Body() dto: AdjustPointsDto, @CurrentUser() u: AuthPrincipal) {
    return this.loyalty.adjust(id, dto, u.id);
  }
}

@ApiTags('Store · Loyalty')
@CustomerOnly()
@Controller('store/me/loyalty')
export class StoreLoyaltyController {
  constructor(private readonly loyalty: LoyaltyService) {}

  @Get()
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.loyalty.forCustomer(u.id);
  }

  /** Turns points into wallet credit. */
  @Post('redeem')
  @HttpCode(HttpStatus.OK)
  redeem(@Body() dto: RedeemPointsDto, @CurrentUser() u: AuthPrincipal) {
    return this.loyalty.redeem(u.id, dto.points);
  }
}
