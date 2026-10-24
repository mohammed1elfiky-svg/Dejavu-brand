import { applyDecorators } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, PartialType } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  ArrayMinSize,
  IsBoolean,
  IsDateString,
  IsEmail,
  IsEnum,
  IsIn,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  ValidateIf,
  Matches,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { PaymentMethod, PurchaseOrderStatus, SupplierKind } from '../../generated/prisma/client';
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

  @ApiPropertyOptional({ enum: SupplierKind, default: SupplierKind.SUPPLIER, description: 'A goods supplier (yuan) or a shipping company (dollars)' })
  @IsOptional()
  @IsEnum(SupplierKind)
  kind?: SupplierKind;
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

  @ApiPropertyOptional({ enum: SupplierKind })
  @IsOptional()
  @IsEnum(SupplierKind)
  kind?: SupplierKind;
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

  @ApiPropertyOptional({ description: "Photo of the supplier's invoice (upload with POST admin/files/private); \"\" removes it" })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(500)
  invoiceImageUrl?: string | null;

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
  items: PurchaseItemDto[];

  @ApiPropertyOptional({ type: () => [LocalNewProductDto], description: 'Items not in the app yet: created with their SKU and barcode now, on sale once received' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => LocalNewProductDto)
  newProducts?: LocalNewProductDto[];

  @ApiPropertyOptional({ enum: ['USD', 'CNY', 'SAR'], default: 'SAR', description: 'Currency the prices and shipping are in; costs and the books are kept in SAR' })
  @IsOptional()
  @IsIn(['USD', 'CNY', 'SAR'])
  currency?: 'USD' | 'CNY' | 'SAR';

  @ApiPropertyOptional({ example: '7.2', description: 'CNY only: yuan per dollar; the last payment voucher rate by default' })
  @IsOptional()
  @IsDecimalText(6)
  foreignPerUsd?: string;
}

export class LocalNewVariantDto {
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

  @ApiPropertyOptional({ description: "Manufacturer's barcode, if the piece has one" })
  @IsOptional()
  @IsString()
  @MaxLength(64)
  barcode?: string;

  @ApiProperty({ minimum: 1 })
  @IsInt()
  @Min(1)
  @Max(100000)
  quantity: number;
}

/** A product bought for the first time (not in the app yet). */
export class LocalNewProductDto {
  @ApiProperty()
  @IsUUID()
  categoryId: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  brandId?: string;

  @ApiProperty({ example: 'بلوزة قطن' })
  @IsString()
  @MinLength(2)
  @MaxLength(200)
  nameAr: string;

  @ApiProperty({ example: '120', description: 'Selling price, SAR' })
  @IsAmount()
  basePrice: string;

  @ApiProperty({ example: '55', description: 'Purchase price per piece, SAR' })
  @IsAmount()
  unitCost: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  imageUrl?: string;

  @ApiProperty({ type: [LocalNewVariantDto] })
  @ValidateNested({ each: true })
  @Type(() => LocalNewVariantDto)
  @ArrayMinSize(1)
  variants: LocalNewVariantDto[];
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

  @ApiPropertyOptional({ description: 'Orders in USD / CNY: the dollar box paid from (payAmount is then in USD)' })
  @IsOptional()
  @IsUUID()
  payBoxId?: string;
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

  @ApiPropertyOptional({ example: 'مصنع قوانغتشو', description: 'e.g. the factory; shown on the supplier statement' })
  @IsOptional()
  @IsString()
  @MaxLength(200)
  note?: string;
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

  @ApiPropertyOptional({ example: 'مصنع قوانغتشو' })
  @IsOptional()
  @IsString()
  @MaxLength(200)
  note?: string;
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

  @ApiPropertyOptional({ example: '7.2', description: 'Order currency per 1 USD, for the estimate only; the rate comes from the payment vouchers' })
  @IsOptional()
  @IsDecimalText(6)
  foreignPerUsd?: string;

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

  @ApiPropertyOptional({ description: "Photo of the supplier's invoice (upload with POST admin/files/private); \"\" removes it" })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(500)
  invoiceImageUrl?: string | null;

  @ApiPropertyOptional({ description: 'Shipping company owed the freight and customs (a supplier of kind SHIPPING_COMPANY)' })
  @IsOptional()
  @IsUUID()
  shippingCompanyId?: string;

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

  @ApiPropertyOptional({ description: 'Shipping company owed the freight and customs (a supplier of kind SHIPPING_COMPANY)' })
  @IsOptional()
  @IsUUID()
  shippingCompanyId?: string;
}

/** Payment voucher (سند صرف): dollars paid to the supplier at a yuan rate. */
export class SupplierPaymentVoucherDto {
  @ApiProperty()
  @IsUUID()
  supplierId: string;

  @ApiProperty({ example: '1000', description: 'Dollars paid' })
  @IsAmount()
  usdAmount: string;

  @ApiProperty({ description: 'The USD box (safe, bank or drawer) the dollars leave' })
  @IsUUID()
  cashBoxId: string;

  @ApiPropertyOptional({ example: '7.2', description: "Supplier currency per 1 USD; required when paying for goods" })
  @IsOptional()
  @IsDecimalText(6)
  foreignPerUsd?: string;

  @ApiPropertyOptional({ example: 'CNY', default: 'CNY' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toUpperCase() : value))
  @Matches(/^[A-Z]{3}$/)
  foreignCurrency?: string;

  @ApiPropertyOptional({ enum: ['GOODS', 'COMMISSION'], default: 'GOODS', description: 'GOODS: credited in the supplier currency at the rate; COMMISSION: pays the USD commission owed' })
  @IsOptional()
  @IsIn(['GOODS', 'COMMISSION'])
  applyTo?: 'GOODS' | 'COMMISSION';

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

/** The agent's commission for a period, in USD (added when reconciling). */
export class SupplierCommissionDto {
  @ApiProperty()
  @IsUUID()
  supplierId: string;

  @ApiProperty({ example: '150' })
  @IsAmount()
  usdAmount: string;

  @ApiProperty({ example: '2026-10-01' })
  @IsDateString()
  periodFrom: string;

  @ApiProperty({ example: '2026-10-31' })
  @IsDateString()
  periodTo: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

/** Changes to an import order not received yet. */
export class UpdateImportOrderDto {
  @ApiProperty({ type: [ImportItemDto], description: 'All lines of existing sizes/colours (replaces them)' })
  @ValidateNested({ each: true })
  @Type(() => ImportItemDto)
  items: ImportItemDto[];

  @ApiPropertyOptional({ type: [ImportNewProductDto], description: 'More brand-new items to add' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => ImportNewProductDto)
  newProducts?: ImportNewProductDto[];

  @ApiPropertyOptional()
  @IsOptional()
  @IsDecimalText(2)
  domesticShipping?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  supplierInvoiceNo?: string;

  @ApiPropertyOptional({ description: "Photo of the supplier's invoice (upload with POST admin/files/private); \"\" removes it" })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(500)
  invoiceImageUrl?: string | null;

  @ApiPropertyOptional({ description: 'Shipping company owed the freight and customs (a supplier of kind SHIPPING_COMPANY)' })
  @IsOptional()
  @IsUUID()
  shippingCompanyId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;
}

/** A supplier balance from before the system. */
export class SupplierOpeningBalanceDto {
  @ApiProperty({ enum: ['CNY', 'USD'], description: 'Suppliers are dealt with in yuan or dollars only' })
  @IsIn(['CNY', 'USD'])
  currency: 'CNY' | 'USD';

  @ApiProperty({ example: '12000' })
  @IsDecimalText(2)
  amount: string;

  @ApiPropertyOptional({ example: '6250', description: 'SAR value, if you want to set it yourself (otherwise from the rates)' })
  @IsOptional()
  @IsDecimalText(2)
  sarAmount?: string;

  @ApiPropertyOptional({ example: '7.2', description: 'Yuan per dollar for a CNY balance; the last payment voucher rate by default' })
  @IsOptional()
  @IsDecimalText(6)
  foreignPerUsd?: string;

  @ApiProperty({ enum: ['WE_OWE', 'SUPPLIER_OWES'], description: 'WE_OWE: we owe the supplier; SUPPLIER_OWES: we paid ahead' })
  @IsIn(['WE_OWE', 'SUPPLIER_OWES'])
  direction: 'WE_OWE' | 'SUPPLIER_OWES';

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;
}
