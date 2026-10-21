import { ApiPropertyOptional } from '@nestjs/swagger';
import { IsDateString, IsIn, IsOptional } from 'class-validator';

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
