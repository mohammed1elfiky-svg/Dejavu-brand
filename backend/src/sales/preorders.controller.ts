import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags, PartialType } from '@nestjs/swagger';
import { IsEnum, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min } from 'class-validator';
import { PreOrderStatus } from '../generated/prisma/client';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { IsAmount } from '../common/decorators/validation.decorators';
import { PreOrdersService } from './preorders.service';

export class StorePreOrderDto {
  @ApiProperty() @IsUUID() variantId: string;
  @ApiProperty({ minimum: 1, maximum: 20 }) @IsInt() @Min(1) @Max(20) quantity: number;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(300) note?: string;
}

export class AdminPreOrderFieldsDto {
  @ApiPropertyOptional({ description: 'The size; leave out for a piece not in the catalogue yet (then itemName)' }) @IsOptional() @IsUUID() variantId?: string;
  @ApiProperty({ minimum: 1, maximum: 50 }) @IsInt() @Min(1) @Max(50) quantity: number;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(300) note?: string;
  @ApiPropertyOptional({ example: 'كعب لامع أسود' }) @IsOptional() @IsString() @MaxLength(150) itemName?: string;
  @ApiPropertyOptional({ example: '38' }) @IsOptional() @IsString() @MaxLength(30) itemSize?: string;
  @ApiPropertyOptional({ example: 'أسود' }) @IsOptional() @IsString() @MaxLength(40) itemColor?: string;
  @ApiPropertyOptional({ example: '120', description: 'Agreed price per piece, SAR' }) @IsOptional() @IsAmount() unitPrice?: string;
}

export class AdminPreOrderDto extends AdminPreOrderFieldsDto {
  @ApiProperty() @IsUUID() customerId: string;
}

export class UpdatePreOrderDto extends PartialType(AdminPreOrderFieldsDto) {}

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

  /** "تأكيد الطلب" once the pieces arrived: they are put aside for the customer. */
  @Post(':id/confirm')
  @HttpCode(HttpStatus.OK)
  confirm(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.confirm(id, u.id);
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
    return this.preOrders.create(customerId, rest, u);
  }

  @Patch(':id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdatePreOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.update(id, dto, u);
  }

  /** The pieces arrived (in stock): tell the customer now / put aside a staff pre-order. */
  @Post(':id/arrived')
  @HttpCode(HttpStatus.OK)
  arrived(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.preOrders.arrived(id, u);
  }

  /** Runs the arrival check for every waiting pre-order now (after receiving goods). */
  @Post('check-arrivals')
  @HttpCode(HttpStatus.OK)
  async checkArrivals() {
    return { handled: await this.preOrders.checkArrivals() };
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
