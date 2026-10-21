import { ApiProperty, ApiPropertyOptional, PartialType } from '@nestjs/swagger';
import { Type } from 'class-transformer';
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
}

export class UpdateSupplierDto extends PartialType(CreateSupplierDto) {
  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
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
