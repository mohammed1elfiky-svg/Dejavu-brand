import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsEnum, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min } from 'class-validator';
import { PreOrderStatus } from '../generated/prisma/client';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { PreOrdersService } from './preorders.service';

export class StorePreOrderDto {
  @ApiProperty() @IsUUID() variantId: string;
  @ApiProperty({ minimum: 1, maximum: 20 }) @IsInt() @Min(1) @Max(20) quantity: number;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(300) note?: string;
}

export class AdminPreOrderDto extends StorePreOrderDto {
  @ApiProperty() @IsUUID() customerId: string;
}

export class PreOrdersQueryDto {
  @ApiPropertyOptional({ enum: PreOrderStatus }) @IsOptional() @IsEnum(PreOrderStatus) status?: PreOrderStatus;
  @ApiPropertyOptional() @IsOptional() @IsUUID() productId?: string;
}

@ApiTags('Store · Pre-orders')
@CustomerOnly()
@Controller('store/pre-orders')
export class StorePreOrdersController {
  constructor(private readonly preOrders: PreOrdersService) {}

  @Get()
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.preOrders.listForCustomer(u.id);
  }

  /** "احجز من الشحنة القادمة" on a size that is out of stock but on its way. */
  @Post()
  create(@Body() dto: StorePreOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.create(u.id, dto);
  }

  @Delete(':id')
  cancel(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.cancel(id, { customerId: u.id });
  }
}

@ApiTags('Admin · Pre-orders')
@RequirePermissions('layaways.manage')
@Controller('admin/pre-orders')
export class AdminPreOrdersController {
  constructor(private readonly preOrders: PreOrdersService) {}

  @Get()
  list(@Query() q: PreOrdersQueryDto) {
    return this.preOrders.list(q);
  }

  /** Pieces on their way that can be pre-ordered. */
  @Get('incoming')
  incoming() {
    return this.preOrders.incoming();
  }

  @Post()
  create(@Body() dto: AdminPreOrderDto, @CurrentUser() u: AuthPrincipal) {
    const { customerId, ...rest } = dto;
    return this.preOrders.create(customerId, rest, u.id);
  }

  @Post(':id/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.cancel(id, { userId: u.id });
  }

  /** Put the arrived pieces aside for the customer (a layaway). */
  @Post(':id/convert')
  @HttpCode(HttpStatus.OK)
  convert(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.convert(id, u);
  }
}
