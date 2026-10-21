import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsDateString, IsEnum, IsOptional, IsString, IsUrl, IsUUID, MaxLength, MinLength } from 'class-validator';
import { ExpenseStatus } from '../../generated/prisma/client';
import { IsAmount } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

export class CreateExpenseCategoryDto {
  @ApiProperty({ example: 'صيانة' })
  @IsString()
  @MinLength(2)
  @MaxLength(60)
  name: string;

  @ApiProperty({ description: 'Postable EXPENSE account this category books to' })
  @IsUUID()
  accountId: string;
}

export class ExpenseDto {
  @ApiProperty()
  @IsUUID()
  categoryId: string;

  @ApiProperty({ example: '1500.00', description: 'Amount before VAT' })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ example: '210.00', description: 'Recoverable input VAT on the bill' })
  @IsOptional()
  @IsAmount()
  taxAmount?: string;

  @ApiProperty({ example: '2026-10-21' })
  @IsDateString({ strict: true })
  expenseDate: string;

  @ApiProperty({ example: 'فاتورة كهرباء أكتوبر' })
  @IsString()
  @MinLength(3)
  @MaxLength(500)
  description: string;

  @ApiPropertyOptional({ example: 'شركة الكهرباء' })
  @IsOptional()
  @IsString()
  @MaxLength(150)
  payee?: string;

  @ApiPropertyOptional({ description: 'Photo/PDF of the bill' })
  @IsOptional()
  @IsUrl()
  attachmentUrl?: string;
}

export class ApproveExpenseDto {
  @ApiPropertyOptional({
    description: 'Cash/bank account it was paid from. Omit if it is still unpaid (booked as an accrued expense).',
  })
  @IsOptional()
  @IsUUID()
  paidFromAccountId?: string;
}

export class PayExpenseDto {
  @ApiProperty({ description: 'Cash/bank account it is paid from' })
  @IsUUID()
  paidFromAccountId: string;
}

export class CancelExpenseDto {
  @ApiProperty()
  @IsString()
  @MinLength(3)
  @MaxLength(255)
  reason: string;
}

export class ExpensesQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: ExpenseStatus })
  @IsOptional()
  @IsEnum(ExpenseStatus)
  status?: ExpenseStatus;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  categoryId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}
