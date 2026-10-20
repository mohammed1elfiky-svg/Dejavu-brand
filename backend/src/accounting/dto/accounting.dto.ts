import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  ArrayMinSize,
  IsBoolean,
  IsDateString,
  IsEnum,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  MaxLength,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { AccountType, JournalEntryStatus, JournalSourceType } from '../../generated/prisma/client';
import { IsAmount } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

export class CreateAccountDto {
  @ApiProperty({ example: '5209' })
  @Matches(/^\d{1,10}$/, { message: 'code must be digits only' })
  code: string;

  @ApiProperty()
  @IsString()
  @MinLength(2)
  nameAr: string;

  @ApiProperty()
  @IsString()
  @MinLength(2)
  nameEn: string;

  @ApiProperty({ description: 'Parent group account; type and normal balance are inherited from it' })
  @IsUUID()
  parentId: string;

  @ApiPropertyOptional({ default: true })
  @IsOptional()
  @IsBoolean()
  isPostable?: boolean;

  @ApiPropertyOptional({ default: false })
  @IsOptional()
  @IsBoolean()
  isCashOrBank?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  description?: string;
}

export class UpdateAccountDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MinLength(2)
  nameAr?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MinLength(2)
  nameEn?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isCashOrBank?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  description?: string;
}

export class AccountsQueryDto {
  @ApiPropertyOptional({ enum: AccountType })
  @IsOptional()
  @IsEnum(AccountType)
  type?: AccountType;

  @ApiPropertyOptional({ description: 'Only cash boxes and bank accounts' })
  @IsOptional()
  @Transform(({ value }) => value === true || value === 'true')
  @IsBoolean()
  cashOrBank?: boolean;
}

export class JournalLineDto {
  @ApiProperty()
  @IsUUID()
  accountId: string;

  @ApiPropertyOptional({ example: '100.00' })
  @IsOptional()
  @IsAmount()
  debit?: string;

  @ApiPropertyOptional({ example: '0' })
  @IsOptional()
  @IsAmount()
  credit?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  description?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  customerId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  supplierId?: string;
}

export class ManualJournalEntryDto {
  @ApiProperty({ example: '2026-10-20' })
  @IsDateString({ strict: true })
  entryDate: string;

  @ApiProperty()
  @IsString()
  @MinLength(3)
  @MaxLength(500)
  description: string;

  @ApiProperty({ type: [JournalLineDto] })
  @ValidateNested({ each: true })
  @Type(() => JournalLineDto)
  @ArrayMinSize(2)
  lines: JournalLineDto[];
}

export class ReverseEntryDto {
  @ApiProperty()
  @IsString()
  @MinLength(3)
  @MaxLength(255)
  reason: string;
}

export class JournalQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: JournalEntryStatus })
  @IsOptional()
  @IsEnum(JournalEntryStatus)
  status?: JournalEntryStatus;

  @ApiPropertyOptional({ enum: JournalSourceType })
  @IsOptional()
  @IsEnum(JournalSourceType)
  sourceType?: JournalSourceType;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  sourceId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

export class DateRangeQueryDto {
  @ApiPropertyOptional({ example: '2026-01-01' })
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional({ example: '2026-12-31' })
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

