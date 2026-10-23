import { ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { IsBoolean, IsDateString, IsIn, IsInt, IsOptional, Matches, Max, Min } from 'class-validator';

export class PeriodQueryDto {
  @ApiPropertyOptional({ example: '2026-10-01', description: 'Defaults to the first day of the current month' })
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional({ example: '2026-10-31', description: 'Defaults to today' })
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

export class AsOfQueryDto {
  @ApiPropertyOptional({ example: '2026-10-31', description: 'Defaults to today' })
  @IsOptional()
  @IsDateString({ strict: true })
  asOf?: string;
}

export class SalesReportQueryDto extends PeriodQueryDto {
  @ApiPropertyOptional({ enum: ['day', 'product', 'category'], default: 'day' })
  @IsOptional()
  @IsIn(['day', 'product', 'category'])
  groupBy: 'day' | 'product' | 'category' = 'day';
}

export class ProductsReportQueryDto extends PeriodQueryDto {
  @ApiPropertyOptional({ default: 60, description: 'A product with stock that sold nothing for this many days is dead stock' })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(3650)
  deadDays?: number;

  @ApiPropertyOptional({ default: 30 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(200)
  limit?: number;
}

export class ReorderQueryDto {
  @ApiPropertyOptional({ default: 30, description: 'Sales of the last N days set the pace' })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(7)
  @Max(365)
  days?: number;

  @ApiPropertyOptional({ default: 30, description: 'Days the stock should last' })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(365)
  coverDays?: number;
}

export class DailySummarySettingsDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  enabled?: boolean;

  @ApiPropertyOptional({ example: '777123456' })
  @IsOptional()
  @Matches(/^\+?\d{7,15}$/, { message: 'phone must be a mobile number' })
  phone?: string;

  @ApiPropertyOptional({ example: 21, description: 'Hour (0-23, store time) the summary is sent' })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(23)
  hour?: number;
}
