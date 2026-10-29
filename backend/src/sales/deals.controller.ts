import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags, PartialType } from '@nestjs/swagger';
import { ArrayMaxSize, ArrayMinSize, IsArray, IsBoolean, IsDateString, IsEnum, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateIf } from 'class-validator';
import { DealType } from '../generated/prisma/client';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { IsAmount } from '../common/decorators/validation.decorators';
import { DealsService } from './deals.service';

export class DealDto {
  @ApiProperty({ example: 'قطعة وقطعة مجاناً' }) @IsString() @MinLength(2) @MaxLength(100) name: string;
  @ApiProperty({ enum: DealType }) @IsEnum(DealType) type: DealType;
  @ApiPropertyOptional({ default: 1 }) @IsOptional() @IsInt() @Min(1) @Max(20) buyQty?: number;
  @ApiPropertyOptional({ default: 1 }) @IsOptional() @IsInt() @Min(1) @Max(20) freeQty?: number;
  @ApiPropertyOptional({ description: 'Price of the whole set (BUNDLE)' }) @IsOptional() @IsAmount() bundlePrice?: string;
  @ApiPropertyOptional() @IsOptional() @IsDateString() startsAt?: string;
  @ApiPropertyOptional({ description: 'Empty string = no end' }) @IsOptional() @IsString() endsAt?: string;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() isActive?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() atPos?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() online?: boolean;
  @ApiPropertyOptional({ description: 'Only the first N pieces (null = no limit)' }) @IsOptional() @ValidateIf((_, v) => v !== null) @IsInt() @Min(1) maxPieces?: number | null;
  @ApiPropertyOptional({ description: 'Only the first N customers (null = no limit)' }) @IsOptional() @ValidateIf((_, v) => v !== null) @IsInt() @Min(1) maxCustomers?: number | null;

  @ApiProperty({ type: [String] })
  @IsArray()
  @ArrayMinSize(1)
  @ArrayMaxSize(500)
  @IsUUID('all', { each: true })
  productIds: string[];
}

export class UpdateDealDto extends PartialType(DealDto) {}

@ApiTags('Admin · Offers')
@Controller('admin/deals')
export class AdminDealsController {
  constructor(private readonly deals: DealsService) {}

  @RequirePermissions('products.read')
  @Get()
  list(@Query('active') active?: string) {
    return this.deals.list({ active: active === 'true' });
  }

  @RequirePermissions('products.read')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.deals.get(id);
  }

  @RequirePermissions('promotions.manage')
  @Post()
  create(@Body() dto: DealDto, @CurrentUser() u: AuthPrincipal) {
    return this.deals.create({ ...dto, endsAt: dto.endsAt || null }, u.id);
  }

  @RequirePermissions('promotions.manage')
  @Patch(':id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateDealDto, @CurrentUser() u: AuthPrincipal) {
    return this.deals.update(id, { ...dto, endsAt: dto.endsAt === undefined ? undefined : dto.endsAt || null }, u.id);
  }

  @RequirePermissions('promotions.manage')
  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.deals.remove(id, u.id);
  }
}
