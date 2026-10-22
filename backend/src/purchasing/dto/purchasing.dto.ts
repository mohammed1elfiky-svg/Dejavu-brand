import { applyDecorators } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, PartialType } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  ArrayMinSize,
  IsBoolean,
  IsDateString,
  IsEmail,
  IsEnum,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { PaymentMethod, PurchaseOrderStatus } from '../../generated/prisma/client';
import { IsAmount } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

export class CreateSupplierDto {
  @ApiProperty({ example: 'مصنع النيل للملابس' })
  @IsString()
  @MinLength(2)
  @MaxLength(150)
  name: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(100)
  contactName?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(30)
  phone?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsEmail()
  email?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  address?: string;

  @ApiPropertyOptional({ description: 'Tax registration number' })
  @IsOptional()
  @IsString()
  @MaxLength(30)
  taxNumber?: string;

  @ApiPropertyOptional({ example: '5', description: 'Supplier / agent commission, percent of the goods value (import costs)' })
  @IsOptional()
  @IsDecimalText(2)
  commissionPercent?: string;
}

export class UpdateSupplierDto extends PartialType(CreateSupplierDto) {
  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

/** A non-negative decimal as text (numbers accepted), with up to [places] decimals. */
function IsDecimalText(places: number) {
  return applyDecorators(
    Transform(({ value }) => (typeof value === 'number' ? String(value) : value)),
    Matches(new RegExp(`^\\d{1,10}(\\.\\d{1,${places}})?$`), { message: `$property must be a non-negative number with up to ${places} decimals` }),
  );
}

export class SuppliersQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;
}

export class PurchaseItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;

  @ApiProperty({ example: '350.00', description: 'Supplier price per piece, excluding VAT' })
  @IsAmount()
  unitCost: string;

  @ApiPropertyOptional({ default: 14, description: 'Input VAT %' })
  @IsOptional()
  @IsNumber({ maxDecimalPlaces: 2 })
  @Min(0)
  @Max(100)
  taxRate?: number;
}

export class PurchaseOrderDto {
  @ApiProperty()
  @IsUUID()
  supplierId: string;

  @ApiPropertyOptional({ description: 'Defaults to the default warehouse' })
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  supplierInvoiceNo?: string;

  @ApiPropertyOptional({ example: '2026-11-01' })
  @IsOptional()
  @IsDateString({ strict: true })
  expectedDate?: string;

  @ApiPropertyOptional({ description: 'Freight paid to the supplier; spread into the landed cost' })
  @IsOptional()
  @IsAmount()
  shippingCost?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiProperty({ type: [PurchaseItemDto] })
  @ValidateNested({ each: true })
  @Type(() => PurchaseItemDto)
  @ArrayMinSize(1)
  items: PurchaseItemDto[];
}

export class ReceiveItemDto {
  @ApiProperty({ description: 'Purchase order item id' })
  @IsUUID()
  itemId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  quantity: number;
}

export class ReceivePurchaseDto {
  @ApiProperty({ type: [ReceiveItemDto] })
  @ValidateNested({ each: true })
  @Type(() => ReceiveItemDto)
  @ArrayMinSize(1)
  items: ReceiveItemDto[];

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

export class PurchaseOrdersQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: PurchaseOrderStatus })
  @IsOptional()
  @IsEnum(PurchaseOrderStatus)
  status?: PurchaseOrderStatus;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  supplierId?: string;
}

export class SupplierPaymentDto {
  @ApiProperty()
  @IsUUID()
  supplierId: string;

  @ApiPropertyOptional({ description: 'Purchase order this payment settles; omit for a payment on account' })
  @IsOptional()
  @IsUUID()
  purchaseOrderId?: string;

  @ApiProperty({ enum: [PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER, PaymentMethod.INSTAPAY, PaymentMethod.WALLET] })
  @IsEnum(PaymentMethod)
  method: PaymentMethod;

  @ApiProperty({ example: '5000.00' })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ description: 'Cash/bank account paid from; defaults from the method' })
  @IsOptional()
  @IsUUID()
  accountId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;
}

export class StatementQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

export class SupplierPaymentsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  supplierId?: string;
}

export class QuickPurchaseDto extends PurchaseOrderDto {
  @ApiPropertyOptional({ default: true, description: 'Receive all pieces into stock right away' })
  @IsOptional()
  @IsBoolean()
  receiveNow?: boolean;

  @ApiPropertyOptional({ enum: [PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER], description: 'Pay the supplier now (omit to buy on account)' })
  @IsOptional()
  @IsEnum(PaymentMethod)
  payMethod?: PaymentMethod;

  @ApiPropertyOptional({ description: 'Amount paid now (SAR); the full invoice when omitted' })
  @IsOptional()
  @IsAmount()
  payAmount?: string;

  @ApiPropertyOptional({ description: 'Cash box / bank account paid from' })
  @IsOptional()
  @IsUUID()
  payAccountId?: string;
}

// ------------------------------------------------------------- imports ---

export class ImportItemDto {
  @ApiProperty({ description: 'An existing size/colour' })
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  @Max(100000)
  quantity: number;

  @ApiProperty({ example: '35.50', description: 'Supplier price per piece in the order currency (e.g. CNY)' })
  @IsDecimalText(4)
  unitPriceForeign: string;
}

export class ImportNewVariantDto {
  @ApiPropertyOptional({ example: 'M' })
  @IsOptional()
  @IsString()
  @MaxLength(30)
  size?: string;

  @ApiPropertyOptional({ example: 'أسود' })
  @IsOptional()
  @IsString()
  @MaxLength(40)
  color?: string;

  @ApiPropertyOptional({ example: '#000000' })
  @IsOptional()
  @Matches(/^#[0-9A-Fa-f]{6}$/)
  colorHex?: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  @Max(100000)
  quantity: number;
}

/** A product that does not exist yet: created as a draft, sold only once received. */
export class ImportNewProductDto {
  @ApiProperty()
  @IsUUID()
  categoryId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  brandId?: string;

  @ApiProperty({ example: 'فستان سهرة' })
  @IsString()
  @MinLength(2)
  @MaxLength(200)
  nameAr: string;

  @ApiProperty({ example: '250', description: 'Selling price, SAR' })
  @IsAmount()
  basePrice: string;

  @ApiProperty({ example: '35.50', description: 'Supplier price per piece in the order currency' })
  @IsDecimalText(4)
  unitPriceForeign: string;

  @ApiPropertyOptional({ description: 'Product photo (uploaded image URL)' })
  @IsOptional()
  @IsString()
  @MaxLength(500)
  imageUrl?: string;

  @ApiProperty({ type: [ImportNewVariantDto] })
  @ValidateNested({ each: true })
  @Type(() => ImportNewVariantDto)
  @ArrayMinSize(1)
  variants: ImportNewVariantDto[];
}

export class ImportOrderDto {
  @ApiProperty()
  @IsUUID()
  supplierId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  warehouseId?: string;

  @ApiPropertyOptional({ example: 'CNY', default: 'CNY' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toUpperCase() : value))
  @Matches(/^[A-Z]{3}$/)
  foreignCurrency?: string;

  @ApiProperty({ example: '7.2', description: 'Order currency units per 1 USD' })
  @IsDecimalText(6)
  foreignPerUsd: string;

  @ApiPropertyOptional({ example: '3.75', description: 'SAR per 1 USD; defaults to the current USD rate' })
  @IsOptional()
  @IsDecimalText(6)
  sarPerUsd?: string;

  @ApiPropertyOptional({ description: "Defaults to the supplier's commission" })
  @IsOptional()
  @IsDecimalText(2)
  commissionPercent?: string;

  @ApiPropertyOptional({ example: '300', description: 'Inland shipping in the origin country, order currency' })
  @IsOptional()
  @IsDecimalText(2)
  domesticShipping?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  supplierInvoiceNo?: string;

  @ApiPropertyOptional({ example: '2026-12-01' })
  @IsOptional()
  @IsDateString()
  expectedDate?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiPropertyOptional({ type: [ImportItemDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => ImportItemDto)
  items?: ImportItemDto[];

  @ApiPropertyOptional({ type: [ImportNewProductDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => ImportNewProductDto)
  newProducts?: ImportNewProductDto[];
}

export class ImportReceiveItemDto {
  @ApiProperty()
  @IsUUID()
  itemId: string;

  @ApiProperty({ minimum: 0, description: 'Pieces actually counted on arrival' })
  @IsInt()
  @Min(0)
  quantity: number;

  @ApiPropertyOptional({ example: '0.35', description: 'Volume of this line (m³), if measured on its own' })
  @IsOptional()
  @IsDecimalText(4)
  cbm?: string;
}

/** Arrival of an import shipment: counted pieces and the costs known now. */
export class ImportReceiveDto {
  @ApiProperty({ type: [ImportReceiveItemDto] })
  @ValidateNested({ each: true })
  @Type(() => ImportReceiveItemDto)
  @ArrayMinSize(1)
  items: ImportReceiveItemDto[];

  @ApiPropertyOptional({ example: '2.5', description: 'Whole shipment volume (m³)' })
  @IsOptional()
  @IsDecimalText(4)
  totalCbm?: string;

  @ApiProperty({ example: '180', description: 'Freight + customs per m³, USD' })
  @IsDecimalText(2)
  cbmRateUsd: string;

  @ApiPropertyOptional({ description: 'Correct the rates or costs known at ordering' })
  @IsOptional()
  @IsDecimalText(6)
  foreignPerUsd?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDecimalText(6)
  sarPerUsd?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDecimalText(2)
  commissionPercent?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDecimalText(2)
  domesticShipping?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}
