import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsIn, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateNested } from 'class-validator';
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

  @ApiPropertyOptional({ type: [PosTenderDto], description: 'Money handed over, in one or more currencies' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosTenderDto)
  @ArrayMaxSize(10)
  payments?: PosTenderDto[];

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
