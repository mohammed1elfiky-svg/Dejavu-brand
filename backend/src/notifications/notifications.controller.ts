import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsNumber, IsOptional, IsString, MaxLength, Min, MinLength } from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { NotificationsService } from './notifications.service';
import { OwnerAlertsService } from './owner-alerts.service';

export class RegisterDeviceDto {
  @ApiProperty({ description: 'Firebase Cloud Messaging registration token' })
  @IsString()
  @MinLength(20)
  @MaxLength(4096)
  token: string;

  @ApiPropertyOptional({ example: '1.0.0' })
  @IsOptional()
  @IsString()
  @MaxLength(40)
  appVersion?: string;
}

export class UnregisterDeviceDto {
  @ApiProperty()
  @IsString()
  @MaxLength(4096)
  token: string;
}

@ApiTags('Store · Notifications')
@CustomerOnly()
@Controller('store/notifications')
export class StoreNotificationsController {
  constructor(private readonly notifications: NotificationsService) {}

  @Get()
  list(@CurrentUser() u: AuthPrincipal, @Query() q: PaginationQueryDto) {
    return this.notifications.inbox(u.id, q);
  }

  @Get('unread-count')
  async unread(@CurrentUser() u: AuthPrincipal) {
    return { unread: await this.notifications.unreadCount(u.id) };
  }

  @Post('read-all')
  @HttpCode(HttpStatus.OK)
  readAll(@CurrentUser() u: AuthPrincipal) {
    return this.notifications.markAllRead(u.id);
  }

  @Post(':id/read')
  @HttpCode(HttpStatus.OK)
  read(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.notifications.markRead(u.id, id);
  }
}

@ApiTags('Store · Notifications')
@CustomerOnly()
@Controller('store/devices')
export class StoreDevicesController {
  constructor(private readonly notifications: NotificationsService) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  register(@CurrentUser() u: AuthPrincipal, @Body() dto: RegisterDeviceDto) {
    return this.notifications.registerDevice(u, dto.token, dto.appVersion);
  }

  /** Call on logout so this phone stops receiving the account's notifications. */
  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  async unregister(@CurrentUser() u: AuthPrincipal, @Body() dto: UnregisterDeviceDto) {
    await this.notifications.unregisterDevice(u, dto.token);
  }
}

@ApiTags('Admin · Notifications')
@StaffOnly()
@Controller('admin/devices')
export class AdminDevicesController {
  constructor(private readonly notifications: NotificationsService) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  register(@CurrentUser() u: AuthPrincipal, @Body() dto: RegisterDeviceDto) {
    return this.notifications.registerDevice(u, dto.token, dto.appVersion);
  }

  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  async unregister(@CurrentUser() u: AuthPrincipal, @Body() dto: UnregisterDeviceDto) {
    await this.notifications.unregisterDevice(u, dto.token);
  }
}

@ApiTags('Admin · Notifications')
@StaffOnly()
@Controller('admin/alerts')
export class StaffAlertsController {
  constructor(private readonly notifications: NotificationsService) {}

  /** New app orders and top-up requests since [since] (ISO time), newest first. */
  @Get()
  alerts(@CurrentUser() u: AuthPrincipal, @Query('since') since?: string) {
    const at = since ? new Date(since) : undefined;
    return this.notifications.staffAlerts(u, at && !Number.isNaN(at.getTime()) ? at : undefined);
  }
}

export class OwnerAlertSettingsDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  voids?: boolean;

  @ApiPropertyOptional({ nullable: true, description: 'Alert when a drawer is short by more than this (SAR); null = off' })
  @IsOptional()
  @IsNumber()
  @Min(0)
  shortageAbove?: number | null;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  priceChanges?: boolean;

  @ApiPropertyOptional({ nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(20)
  smsPhone?: string | null;
}

@ApiTags('Admin · Owner alerts')
@RequirePermissions('settings.manage')
@Controller('admin/owner-alerts')
export class OwnerAlertsController {
  constructor(private readonly alerts: OwnerAlertsService) {}

  @Get()
  list(@Query('take') take?: string) {
    return this.alerts.list(take ? Number(take) : 50);
  }

  @Get('settings')
  settings() {
    return this.alerts.settings();
  }

  @Put('settings')
  setSettings(@Body() dto: OwnerAlertSettingsDto) {
    return this.alerts.setSettings(dto);
  }
}
