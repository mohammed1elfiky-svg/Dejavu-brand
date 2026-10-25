import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsArray,
  IsBoolean,
  IsDateString,
  IsEnum,
  IsInt,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { StockMovementType } from '../../generated/prisma/client';
import { IsAmount } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

export enum AdjustmentReason {
  /** Initial stock when going live; credited to opening-balance equity. */
  OPENING = 'OPENING',
  COUNT_SURPLUS = 'COUNT_SURPLUS',
  OTHER_IN = 'OTHER_IN',
  COUNT_SHORTAGE = 'COUNT_SHORTAGE',
  DAMAGE = 'DAMAGE',
  OTHER_OUT = 'OTHER_OUT',
}

export const INCREASE_REASONS = [AdjustmentReason.OPENING, AdjustmentReason.COUNT_SURPLUS, AdjustmentReason.OTHER_IN];

export class CreateWarehouseDto {
  @ApiProperty({ example: 'ALEX' })
  @Matches(/^[A-Z0-9_-]{2,20}$/)
  code: string;

  @ApiProperty()
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  address?: string;
}

export class AdjustmentItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;

  @ApiPropertyOptional({ description: 'Required for OPENING; defaults to the current average cost otherwise' })
  @IsOptional()
  @IsAmount()
  unitCost?: string;
}

export class StockAdjustmentDto {
  @ApiProperty()
  @IsUUID()
  warehouseId: string;

  @ApiProperty({ enum: AdjustmentReason })
  @IsEnum(AdjustmentReason)
  reason: AdjustmentReason;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiProperty({ type: [AdjustmentItemDto] })
  @ValidateNested({ each: true })
  @Type(() => AdjustmentItemDto)
  @ArrayMinSize(1)
  items: AdjustmentItemDto[];
}

export class TransferItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;
}

export class StockTransferDto {
  @ApiProperty()
  @IsUUID()
  fromWarehouseId: string;

  @ApiProperty()
  @IsUUID()
  toWarehouseId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiProperty({ type: [TransferItemDto] })
  @ValidateNested({ each: true })
  @Type(() => TransferItemDto)
  @ArrayMinSize(1)
  items: TransferItemDto[];
}

export class StockLevelsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  variantId?: string;

  @ApiPropertyOptional({ description: 'Search by SKU or product name' })
  @IsOptional()
  @IsString()
  q?: string;
}

export class MovementsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  variantId?: string;

  @ApiPropertyOptional({ enum: StockMovementType })
  @IsOptional()
  @IsEnum(StockMovementType)
  type?: StockMovementType;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  sourceId?: string;
}

export class LowStockQueryDto {
  @ApiPropertyOptional({ description: 'Include variants that are out of stock entirely', default: true })
  @IsOptional()
  @Transform(({ value }) => value !== false && value !== 'false')
  @IsBoolean()
  includeOutOfStock = true;
}

export class StockCountItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 0, description: 'Physically counted pieces, including those reserved for orders' })
  @IsInt()
  @Min(0)
  countedQty: number;
}

export class CreateStockCountDto {
  @ApiProperty()
  @IsUUID()
  warehouseId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiProperty({ type: [StockCountItemDto] })
  @ValidateNested({ each: true })
  @Type(() => StockCountItemDto)
  @ArrayMinSize(1)
  items: StockCountItemDto[];
}

export class StockCountsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @Transform(({ value }) => value === true || value === 'true')
  @IsBoolean()
  posted?: boolean;
}

export class DamageItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;
}

export class StockDamageDto {
  @ApiPropertyOptional({ description: 'The default warehouse when left out' })
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiProperty({ type: [DamageItemDto] })
  @ValidateNested({ each: true })
  @Type(() => DamageItemDto)
  @ArrayMinSize(1)
  items: DamageItemDto[];

  @ApiPropertyOptional({ description: 'What happened' })
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiPropertyOptional({ type: [String], description: 'Photos (URLs from admin/files or admin/files/private)' })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(6)
  @IsString({ each: true })
  photoUrls?: string[];
}

export class DamagesQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional() @IsOptional() @IsDateString() from?: string;
  @ApiPropertyOptional() @IsOptional() @IsDateString() to?: string;
}
