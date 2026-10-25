import { Body, Controller, Get, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { ArrayMaxSize, IsArray, IsBoolean, IsIn, IsInt, IsNumberString, IsOptional, IsUUID, Max, Min } from 'class-validator';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { BulkPricingService } from './bulk-pricing.service';

export class BulkPriceDto {
  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(2000)
  @IsUUID('all', { each: true })
  productIds?: string[];

  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsArray()
  @IsUUID('all', { each: true })
  categoryIds?: string[];

  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsArray()
  @IsUUID('all', { each: true })
  brandIds?: string[];

  @ApiProperty({ enum: ['PERCENT', 'AMOUNT', 'SET'] })
  @IsIn(['PERCENT', 'AMOUNT', 'SET'])
  mode: 'PERCENT' | 'AMOUNT' | 'SET';

  @ApiProperty({ example: '-10', description: 'Percent or SAR (negative lowers), or the new price for SET' })
  @IsNumberString()
  value: string;

  @ApiPropertyOptional({ example: 5 })
  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(1000)
  roundTo?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  keepOldAsCompareAt?: boolean;

  @ApiPropertyOptional({ default: true })
  @IsOptional()
  @IsBoolean()
  includeVariants?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  preview?: boolean;
}

@ApiTags('Admin · Pricing')
@Controller('admin/pricing')
export class AdminPricingController {
  constructor(private readonly pricing: BulkPricingService) {}

  /** Raise or lower the price of many products at once (preview first). */
  @RequirePermissions('products.write')
  @Post('bulk')
  @HttpCode(HttpStatus.OK)
  bulk(@Body() dto: BulkPriceDto, @CurrentUser() u: AuthPrincipal) {
    return this.pricing.bulkPrice(dto, u.id);
  }

  /** Products on offer now (promotion or struck-through price). */
  @RequirePermissions('products.read')
  @Get('on-offer')
  onOffer() {
    return this.pricing.onOffer();
  }
}
