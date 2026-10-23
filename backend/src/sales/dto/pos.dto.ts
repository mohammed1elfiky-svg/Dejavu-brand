import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsBoolean, IsIn, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateNested } from 'class-validator';
import { IsAmount, IsCurrencyCode } from '../../common/decorators/validation.decorators';

export class PosItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  @Max(999)
  quantity: number;
}

export class PosTenderDto {
  @ApiProperty({ example: 'YER' })
  @IsCurrencyCode()
  currency: string;

  @ApiProperty({ example: '14000', description: 'Amount handed over, in that currency' })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ enum: ['CASH', 'BANK_TRANSFER'], description: "Defaults to the sale's paymentMethod" })
  @IsOptional()
  @IsIn(['CASH', 'BANK_TRANSFER'])
  method?: 'CASH' | 'BANK_TRANSFER';

  @ApiPropertyOptional({ description: 'Bank box the transfer went to; the first bank box of the currency by default' })
  @IsOptional()
  @IsUUID()
  bankBoxId?: string;
}

export class PosSaleDto {
  @ApiProperty({ type: [PosItemDto] })
  @ValidateNested({ each: true })
  @Type(() => PosItemDto)
  @ArrayMinSize(1)
  @ArrayMaxSize(200)
  items: PosItemDto[];

  @ApiPropertyOptional({ example: 'YER', description: 'Invoice currency (current rate is locked in); SAR by default' })
  @IsOptional()
  @IsCurrencyCode()
  currency?: string;

  @ApiProperty({ enum: ['CASH', 'CREDIT'], description: 'CREDIT puts whatever is not paid now on the customer account' })
  @IsIn(['CASH', 'CREDIT'])
  saleType: 'CASH' | 'CREDIT';

  @ApiPropertyOptional({ description: 'Required for credit sales; cash sales default to the walk-in customer' })
  @IsOptional()
  @IsUUID()
  customerId?: string;

  @ApiPropertyOptional({ enum: ['PERCENT', 'AMOUNT'] })
  @IsOptional()
  @IsIn(['PERCENT', 'AMOUNT'])
  discountType?: 'PERCENT' | 'AMOUNT';

  @ApiPropertyOptional({ example: '10', description: 'Percent, or an amount in the invoice currency' })
  @IsOptional()
  @IsAmount()
  discountValue?: string;

  @ApiPropertyOptional({ example: 'WELCOME10', description: 'Discount code (instead of a typed discount)' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toUpperCase() || undefined : value))
  @IsString()
  @MaxLength(40)
  couponCode?: string;

  @ApiPropertyOptional({ type: [PosTenderDto], description: 'Money handed over, in one or more currencies' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosTenderDto)
  @ArrayMaxSize(10)
  payments?: PosTenderDto[];

  @ApiPropertyOptional({ enum: ['CASH', 'BANK_TRANSFER'], default: 'CASH', description: 'Cash into the drawer, or a bank transfer / deposit slip' })
  @IsOptional()
  @IsIn(['CASH', 'BANK_TRANSFER'])
  paymentMethod?: 'CASH' | 'BANK_TRANSFER';

  @ApiPropertyOptional({ example: '5000', description: "Pay this much (invoice currency) from the customer's own credit first" })
  @IsOptional()
  @IsAmount()
  fromAccount?: string;

  @ApiPropertyOptional({ description: 'Transfer or deposit slip number' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;

  @ApiPropertyOptional({ example: 'YER', description: 'Currency the change is given in; the invoice currency by default' })
  @IsOptional()
  @IsCurrencyCode()
  changeCurrency?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

export class PosProductsQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  categoryId?: string;
}

export class PosArrangeDto {
  @ApiProperty({ type: [String], description: 'Product ids in the order they should appear' })
  @IsUUID('all', { each: true })
  @ArrayMinSize(1)
  @ArrayMaxSize(2000)
  productIds: string[];
}

export class PosLookupQueryDto {
  @ApiProperty({ example: '100-2' })
  @IsString()
  @MinLength(1)
  @MaxLength(64)
  code: string;
}

export class QuickCustomerDto {
  @ApiProperty({ example: 'محمد علي' })
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name: string;

  @ApiProperty({ example: '771234567' })
  @IsString()
  @MinLength(6)
  @MaxLength(20)
  phone: string;
}

export class PosReturnItemDto {
  @ApiProperty()
  @IsUUID()
  orderItemId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;

  @ApiPropertyOptional({ default: true, description: 'false when the piece is damaged and cannot be sold again' })
  @IsOptional()
  @IsBoolean()
  restock?: boolean;
}

export class PosReturnDto {
  @ApiProperty({ description: 'The sale being returned (any delivered or till order)' })
  @IsUUID()
  orderId: string;

  @ApiProperty({ type: [PosReturnItemDto] })
  @ValidateNested({ each: true })
  @Type(() => PosReturnItemDto)
  @ArrayMinSize(1)
  items: PosReturnItemDto[];

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  reason?: string;

  @ApiPropertyOptional({ enum: ['CASH', 'ACCOUNT'], default: 'CASH', description: "Money back from the drawer, or kept as the customer's credit" })
  @IsOptional()
  @IsIn(['CASH', 'ACCOUNT'])
  refundTo?: 'CASH' | 'ACCOUNT';

  @ApiPropertyOptional({ description: "Currency cash is handed back in: SAR or the sale's currency" })
  @IsOptional()
  @IsCurrencyCode()
  refundCurrency?: string;

  @ApiPropertyOptional({ description: 'Box (drawer, safe or bank account) the refund is paid from; defaults to the cashier\'s drawer' })
  @IsOptional()
  @IsUUID()
  refundBoxId?: string;

  @ApiPropertyOptional({ type: [PosItemDto], description: 'Exchange: the pieces taken instead' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosItemDto)
  @ArrayMaxSize(200)
  newItems?: PosItemDto[];

  @ApiPropertyOptional({ type: [PosTenderDto], description: 'Exchange: what the customer pays on top' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosTenderDto)
  @ArrayMaxSize(10)
  payments?: PosTenderDto[];

  @ApiPropertyOptional({ enum: ['CASH', 'BANK_TRANSFER'] })
  @IsOptional()
  @IsIn(['CASH', 'BANK_TRANSFER'])
  paymentMethod?: 'CASH' | 'BANK_TRANSFER';

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsCurrencyCode()
  changeCurrency?: string;
}

export class PosFreeReturnItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  @Max(999)
  quantity: number;

  @ApiPropertyOptional({ example: '120', description: "Value per piece in the return's currency; defaults to the current shelf price" })
  @IsOptional()
  @IsAmount()
  unitPrice?: string;

  @ApiPropertyOptional({ default: true, description: 'false when the piece is damaged and cannot be sold again' })
  @IsOptional()
  @IsBoolean()
  restock?: boolean;
}

/** A return or exchange with no invoice to look up: the pieces are picked by hand. */
export class PosFreeReturnDto {
  @ApiPropertyOptional({ description: 'Defaults to the walk-in customer' })
  @IsOptional()
  @IsUUID()
  customerId?: string;

  @ApiPropertyOptional({ example: 'SAR', description: 'Currency the pieces are valued and settled in' })
  @IsOptional()
  @IsCurrencyCode()
  currency?: string;

  @ApiProperty({ type: [PosFreeReturnItemDto] })
  @ValidateNested({ each: true })
  @Type(() => PosFreeReturnItemDto)
  @ArrayMinSize(1)
  @ArrayMaxSize(200)
  items: PosFreeReturnItemDto[];

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  reason?: string;

  @ApiPropertyOptional({ enum: ['CASH', 'ACCOUNT'], default: 'CASH' })
  @IsOptional()
  @IsIn(['CASH', 'ACCOUNT'])
  refundTo?: 'CASH' | 'ACCOUNT';

  @ApiPropertyOptional()
  @IsOptional()
  @IsCurrencyCode()
  refundCurrency?: string;

  @ApiPropertyOptional({ description: 'Box (drawer, safe or bank account) the refund is paid from; defaults to the cashier\'s drawer' })
  @IsOptional()
  @IsUUID()
  refundBoxId?: string;

  @ApiPropertyOptional({ type: [PosItemDto], description: 'Exchange: the pieces taken instead' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosItemDto)
  @ArrayMaxSize(200)
  newItems?: PosItemDto[];

  @ApiPropertyOptional({ type: [PosTenderDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosTenderDto)
  @ArrayMaxSize(10)
  payments?: PosTenderDto[];

  @ApiPropertyOptional({ enum: ['CASH', 'BANK_TRANSFER'] })
  @IsOptional()
  @IsIn(['CASH', 'BANK_TRANSFER'])
  paymentMethod?: 'CASH' | 'BANK_TRANSFER';

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsCurrencyCode()
  changeCurrency?: string;
}
