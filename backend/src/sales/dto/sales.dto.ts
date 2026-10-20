import { ApiProperty, ApiPropertyOptional, PartialType } from '@nestjs/swagger';
import { Transform } from 'class-transformer';
import {
  IsBoolean,
  IsDateString,
  IsEnum,
  IsIn,
  IsInt,
  IsLatitude,
  IsLongitude,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
  MinLength,
} from 'class-validator';
import { CouponType, OrderStatus, PaymentMethod, PaymentStatus } from '../../generated/prisma/client';
import { PHONE_REGEX } from '../../auth/dto/auth.dto';
import { IsAmount } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

const upper = ({ value }: { value: unknown }) => (typeof value === 'string' ? value.trim().toUpperCase() : value);

// --------------------------------------------------------------- addresses ---

export class AddressDto {
  @ApiPropertyOptional({ example: 'المنزل' })
  @IsOptional()
  @IsString()
  @MaxLength(40)
  label?: string;

  @ApiProperty()
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  recipient: string;

  @ApiProperty({ example: '01012345678' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Egyptian mobile number' })
  phone: string;

  @ApiProperty({ example: 'القاهرة' })
  @IsString()
  @MinLength(2)
  @MaxLength(60)
  governorate: string;

  @ApiProperty({ example: 'مدينة نصر' })
  @IsString()
  @MinLength(2)
  @MaxLength(60)
  city: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(60)
  district?: string;

  @ApiProperty()
  @IsString()
  @MinLength(2)
  @MaxLength(200)
  street: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(20)
  building?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(20)
  floor?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(20)
  apartment?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(200)
  landmark?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsLatitude()
  latitude?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsLongitude()
  longitude?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isDefault?: boolean;
}

export class UpdateAddressDto extends PartialType(AddressDto) {}

// -------------------------------------------------------------------- cart ---

export class AddCartItemDto {
  @ApiProperty()
  @IsUUID()
  variantId: string;

  @ApiProperty({ minimum: 1, maximum: 20 })
  @IsInt()
  @Min(1)
  @Max(20)
  quantity: number;
}

export class UpdateCartItemDto {
  @ApiProperty({ minimum: 1, maximum: 20 })
  @IsInt()
  @Min(1)
  @Max(20)
  quantity: number;
}

export class ApplyCouponDto {
  @ApiProperty({ example: 'WELCOME10' })
  @Transform(upper)
  @IsString()
  @MinLength(3)
  @MaxLength(40)
  code: string;
}

export class CartQueryDto {
  @ApiPropertyOptional({ description: 'Address used to estimate shipping' })
  @IsOptional()
  @IsUUID()
  addressId?: string;
}

// --------------------------------------------------------------- checkout ---

export const CHECKOUT_METHODS = [
  PaymentMethod.CASH_ON_DELIVERY,
  PaymentMethod.CARD,
  PaymentMethod.WALLET,
  PaymentMethod.INSTAPAY,
  PaymentMethod.BANK_TRANSFER,
] as const;

export class CheckoutDto {
  @ApiProperty()
  @IsUUID()
  addressId: string;

  @ApiProperty({ enum: CHECKOUT_METHODS })
  @IsIn(CHECKOUT_METHODS)
  paymentMethod: PaymentMethod;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  customerNote?: string;
}

export class CancelOrderDto {
  @ApiProperty()
  @IsString()
  @MinLength(3)
  @MaxLength(255)
  reason: string;
}

// ------------------------------------------------------------ admin orders ---

export class AdminOrdersQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: OrderStatus })
  @IsOptional()
  @IsEnum(OrderStatus)
  status?: OrderStatus;

  @ApiPropertyOptional({ enum: PaymentStatus })
  @IsOptional()
  @IsEnum(PaymentStatus)
  paymentStatus?: PaymentStatus;

  @ApiPropertyOptional({ description: 'Order number, customer name or phone' })
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

export class ChangeOrderStatusDto {
  @ApiProperty({ enum: OrderStatus })
  @IsEnum(OrderStatus)
  status: OrderStatus;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(500)
  note?: string;

  @ApiPropertyOptional({ description: 'SHIPPED only', example: 'Bosta' })
  @IsOptional()
  @IsString()
  @MaxLength(60)
  carrier?: string;

  @ApiPropertyOptional({ description: 'SHIPPED only' })
  @IsOptional()
  @IsString()
  @MaxLength(100)
  trackingNumber?: string;
}

export class RecordPaymentDto {
  @ApiProperty({ enum: PaymentMethod })
  @IsEnum(PaymentMethod)
  method: PaymentMethod;

  @ApiProperty({ example: '500.00' })
  @IsAmount()
  amount: string;

  @ApiPropertyOptional({ description: 'Cash/bank account; defaults from the payment method' })
  @IsOptional()
  @IsUUID()
  accountId?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(100)
  reference?: string;
}

export class RefundDto extends RecordPaymentDto {
  @ApiProperty()
  @IsString()
  @MinLength(3)
  @MaxLength(255)
  reason: string;
}

// ------------------------------------------------------ coupons & shipping ---

export class CreateCouponDto {
  @ApiProperty({ example: 'WELCOME10' })
  @Transform(upper)
  @Matches(/^[A-Z0-9_-]{3,40}$/)
  code: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  description?: string;

  @ApiProperty({ enum: CouponType })
  @IsEnum(CouponType)
  type: CouponType;

  @ApiProperty({ description: 'Percent for PERCENTAGE, amount for FIXED_AMOUNT, 0 for FREE_SHIPPING' })
  @IsAmount()
  value: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsAmount()
  minOrderAmount?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsAmount()
  maxDiscount?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  @Min(1)
  usageLimit?: number;

  @ApiPropertyOptional({ default: 1 })
  @IsOptional()
  @IsInt()
  @Min(1)
  perCustomerLimit?: number;

  @ApiProperty()
  @IsDateString()
  startsAt: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsDateString()
  endsAt?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

export class UpdateCouponDto extends PartialType(CreateCouponDto) {}

export class ShippingZoneDto {
  @ApiProperty({ example: 'القاهرة' })
  @IsString()
  @MinLength(2)
  @MaxLength(60)
  governorate: string;

  @ApiProperty({ example: '50.00' })
  @IsAmount()
  fee: string;

  @ApiPropertyOptional({ default: 3 })
  @IsOptional()
  @IsNumber()
  @Min(0)
  estimatedDays?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

export class CustomersQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ description: 'Name, phone, email or code' })
  @IsOptional()
  @IsString()
  q?: string;
}

