import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsEnum, IsInt, IsNumberString, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateIf } from 'class-validator';
import { Type } from 'class-transformer';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { DeliveryMethod, GiftStatus } from '../generated/prisma/client';
import { GiftsService } from './gifts.service';

export class PosGiftDto {
  @ApiProperty({ description: 'The till sale that pays for the gift' }) @IsUUID() saleOrderId: string;
  @ApiProperty({ description: "Receiver's mobile number or username" }) @IsString() @MinLength(2) @MaxLength(40) to: string;
  @ApiProperty({ description: 'Shown to the receiver: «معك هدية من …»' }) @IsString() @MinLength(1) @MaxLength(80) giverName: string;
  @ApiPropertyOptional({ example: 'عيد ميلادك' }) @IsOptional() @IsString() @MaxLength(80) occasion?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) message?: string;
}

export class GiftDoneDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(80) trackingNumber?: string;
}

export class GiftChoiceDto {
  @ApiProperty({ enum: DeliveryMethod }) @IsEnum(DeliveryMethod) deliveryMethod: DeliveryMethod;
  @ApiPropertyOptional() @ValidateIf((o: GiftChoiceDto) => o.deliveryMethod === DeliveryMethod.HOME) @IsUUID() addressId?: string;
  @ApiPropertyOptional() @IsOptional() @IsUUID() carrierId?: string;
  @ApiPropertyOptional() @ValidateIf((o: GiftChoiceDto) => o.deliveryMethod === DeliveryMethod.OFFICE) @IsUUID() carrierOfficeId?: string;
}

export class StartGroupGiftDto {
  @ApiProperty({ description: "Receiver's username (or mobile)" }) @IsString() @MinLength(2) @MaxLength(40) to: string;
  @ApiProperty() @IsUUID() variantId: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(80) title?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(80) occasion?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) message?: string;
  @ApiPropertyOptional({ default: 7 }) @IsOptional() @Type(() => Number) @IsInt() @Min(1) @Max(30) days?: number;
}

export class ContributeDto {
  @ApiProperty({ example: '50.00', description: 'SAR' }) @IsNumberString() amount: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) message?: string;
}

@ApiTags('Admin · Gifts')
@Controller('admin/gifts')
export class AdminGiftsController {
  constructor(private readonly gifts: GiftsService) {}

  /** Marks a till sale as a gift: the receiver is notified and picks delivery or pickup. */
  @Post()
  @RequirePermissions('pos.sell')
  create(@CurrentUser() u: AuthPrincipal, @Body() dto: PosGiftDto) {
    return this.gifts.fromSale(dto, u.id);
  }

  @Get()
  @RequirePermissions('orders.read')
  list(@Query('status') status?: string) {
    return this.gifts.list(status && status in GiftStatus ? (status as GiftStatus) : undefined);
  }

  @Get(':id')
  @RequirePermissions('orders.read')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.gifts.get(id);
  }

  /** Handed over in the store, or shipped. */
  @Post(':id/done')
  @HttpCode(HttpStatus.OK)
  @RequirePermissions('orders.update_status')
  done(@Param('id', ParseUUIDPipe) id: string, @Body() dto: GiftDoneDto) {
    return this.gifts.done(id, dto.trackingNumber);
  }
}

@ApiTags('Store · Gifts')
@CustomerOnly()
@Controller('store')
export class StoreGiftsController {
  constructor(private readonly gifts: GiftsService) {}

  @Get('me/gifts')
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.gifts.mine(u.id);
  }

  @Post('me/gifts/:id/choose')
  @HttpCode(HttpStatus.OK)
  choose(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string, @Body() dto: GiftChoiceDto) {
    return this.gifts.choose(u.id, id, dto);
  }

  @Post('group-gifts')
  start(@CurrentUser() u: AuthPrincipal, @Body() dto: StartGroupGiftDto) {
    return this.gifts.startGroup(u.id, dto);
  }

  @Get('group-gifts/mine')
  myGroups(@CurrentUser() u: AuthPrincipal) {
    return this.gifts.myGroups(u.id);
  }

  /** Open collections for someone, to join. */
  @Get('group-gifts/for/:username')
  forUser(@CurrentUser() u: AuthPrincipal, @Param('username') username: string) {
    return this.gifts.groupsFor(username, u.id);
  }

  @Get('group-gifts/:id')
  group(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.gifts.group(id, u.id);
  }

  @Post('group-gifts/:id/contribute')
  @HttpCode(HttpStatus.OK)
  contribute(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string, @Body() dto: ContributeDto) {
    return this.gifts.contribute(u.id, id, dto.amount, dto.message);
  }

  @Post('group-gifts/:id/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.gifts.cancelGroup(u.id, id);
  }
}
