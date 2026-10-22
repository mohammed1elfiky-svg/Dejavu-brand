import { ApiProperty, ApiPropertyOptional, PartialType } from '@nestjs/swagger';
import { Transform } from 'class-transformer';
import { IsBoolean, IsEnum, IsIn, IsInt, IsOptional, IsString, IsUUID, MaxLength, MinLength, ValidateIf } from 'class-validator';
import { CashBoxKind, TopUpStatus } from '../generated/prisma/client';
import { IsAmount, IsCurrencyCode } from '../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';

export class CreateCashBoxDto {
  @ApiProperty({ example: 'الخزنة الرئيسية - دولار' })
  @IsString()
  @MinLength(2)
  @MaxLength(80)
  name: string;

  @ApiProperty({ enum: [CashBoxKind.VAULT, CashBoxKind.BANK] })
  @IsIn([CashBoxKind.VAULT, CashBoxKind.BANK])
  kind: CashBoxKind;

  @ApiProperty({ example: 'USD' })
  @IsCurrencyCode()
  currency: string;

  @ApiPropertyOptional({ example: '💵', description: 'A symbol shown on the box (emoji); "" clears it' })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(16)
  icon?: string | null;

  @ApiPropertyOptional({ description: 'Uploaded picture or logo; "" clears it' })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(500)
  imageUrl?: string | null;
}

export class UpdateCashBoxDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(80)
  name?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiPropertyOptional({ example: '🏦' })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(16)
  icon?: string | null;

  @ApiPropertyOptional()
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(500)
  imageUrl?: string | null;
}

export class CancelTransferDto {
  @ApiPropertyOptional({ example: 'خطأ في المبلغ' })
  @IsOptional()
  @IsString()
  @MaxLength(255)
  reason?: string;
}

export class CashBoxesQueryDto {
  @ApiPropertyOptional({ enum: CashBoxKind })
  @IsOptional()
  @IsEnum(CashBoxKind)
  kind?: CashBoxKind;

  @ApiPropertyOptional()
  @IsOptional()
  @IsCurrencyCode()
  currency?: string;

  @ApiPropertyOptional({ description: 'Drawers of one cashier' })
  @IsOptional()
  @IsUUID()
  userId?: string;
}

export class CashTransferDto {
  @ApiProperty()
  @IsUUID()
  fromBoxId: string;

  @ApiProperty()
  @IsUUID()
  toBoxId: string;

  @ApiProperty({ example: '1000', description: "Amount taken out, in the source box's currency" })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ example: '140000', description: 'Amount received in the other currency (exchanges only)' })
  @IsOptional()
  @IsAmount()
  toAmount?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;
}

export class TransfersQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  boxId?: string;

  @ApiPropertyOptional({ description: 'Only currency exchanges' })
  @IsOptional()
  @Transform(({ value }) => value === true || value === 'true')
  @IsBoolean()
  exchangesOnly?: boolean;
}

export class CustomerReceiptDto {
  @ApiProperty({ example: 'YER' })
  @IsCurrencyCode()
  currency: string;

  @ApiProperty({ example: '20000' })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ description: "Box the money goes into; defaults to the cashier's own drawer" })
  @IsOptional()
  @IsUUID()
  cashBoxId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;

  @ApiPropertyOptional({ description: 'Text the customer the amount received and the balance (sent from the store phone)' })
  @IsOptional()
  @IsBoolean()
  sendSms?: boolean;
}

/** Converts part of a customer's balance from one currency to another. */
export class CustomerExchangeDto {
  @ApiProperty({ example: 'SAR', description: 'Currency taken from the customer\'s account' })
  @IsCurrencyCode()
  fromCurrency: string;

  @ApiProperty({ example: '100' })
  @IsAmount()
  amount: string;

  @ApiProperty({ example: 'YER', description: 'Currency credited to the account' })
  @IsCurrencyCode()
  toCurrency: string;

  @ApiProperty({ example: '14000', description: 'Amount credited in the other currency (the agreed rate)' })
  @IsAmount()
  toAmount: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;

  @ApiPropertyOptional({ description: 'Text the customer the exchange and the new balances' })
  @IsOptional()
  @IsBoolean()
  sendSms?: boolean;
}

export class AccountStatementQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsCurrencyCode()
  currency?: string;
}

export class BankAccountDto {
  @ApiProperty({ example: 'بنك الكريمي' })
  @IsString()
  @MinLength(2)
  @MaxLength(80)
  bankName: string;

  @ApiProperty({ example: 'مؤسسة ديجافو' })
  @IsString()
  @MinLength(2)
  @MaxLength(120)
  accountName: string;

  @ApiProperty({ example: '3001234567' })
  @IsString()
  @MinLength(3)
  @MaxLength(60)
  accountNumber: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(40)
  iban?: string;

  @ApiProperty({ example: 'YER' })
  @IsCurrencyCode()
  currency: string;

  @ApiPropertyOptional({ example: 'اكتب رقم جوالك في خانة الملاحظات' })
  @IsOptional()
  @IsString()
  @MaxLength(500)
  instructions?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  sortOrder?: number;
}

export class UpdateBankAccountDto extends PartialType(BankAccountDto) {}

export class TopUpRequestDto {
  @ApiProperty({ example: 'YER' })
  @IsCurrencyCode()
  currency: string;

  @ApiProperty({ example: '50000' })
  @IsAmount()
  amount: string;

  @ApiProperty({ description: 'Id returned by POST /store/me/files' })
  @IsUUID()
  receiptFileId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  bankAccountId?: string;

  @ApiPropertyOptional({ description: 'Transfer / slip number' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

export class UpdateTopUpRequestDto extends PartialType(TopUpRequestDto) {}

export class TopUpsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: TopUpStatus })
  @IsOptional()
  @IsEnum(TopUpStatus)
  status?: TopUpStatus;
}

export class ApproveTopUpDto {
  @ApiProperty({ description: 'Bank account / safe (box) the money arrived in; same currency as the request' })
  @IsUUID()
  cashBoxId: string;

  @ApiPropertyOptional({ description: 'Corrected amount, if what arrived differs' })
  @IsOptional()
  @IsAmount()
  amount?: string;
}

export class RejectTopUpDto {
  @ApiProperty({ example: 'لم يصل المبلغ إلى الحساب' })
  @IsString()
  @MinLength(3)
  @MaxLength(255)
  reason: string;
}

export class OpeningBalanceDto {
  @ApiProperty({ example: 'YER' })
  @IsCurrencyCode()
  currency: string;

  @ApiProperty({ example: '50000' })
  @IsAmount()
  amount: string;

  @ApiProperty({ enum: ['OWES', 'CREDIT'], description: 'OWES: the customer owes the store; CREDIT: the store holds money for the customer' })
  @IsIn(['OWES', 'CREDIT'])
  direction: 'OWES' | 'CREDIT';

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;
}
