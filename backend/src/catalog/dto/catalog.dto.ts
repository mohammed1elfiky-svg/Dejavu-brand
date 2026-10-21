import { ApiProperty, ApiPropertyOptional, OmitType, PartialType } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsBoolean,
  IsDateString,
  IsEnum,
  IsIn,
  IsInt,
  IsOptional,
  IsString,
  IsUrl,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateIf,
  ValidateNested,
} from 'class-validator';
import { ProductStatus } from '../../generated/prisma/client';
import { IsAmount, SLUG_REGEX } from '../../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../../common/dto/pagination.dto';

const toBool = ({ value }: { value: unknown }) => value === true || value === 'true';

// ------------------------------------------------------------- categories ---

export class CreateCategoryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  parentId?: string;

  @ApiProperty({ example: 'فساتين' })
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  nameAr: string;

  @ApiProperty({ example: 'Dresses' })
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  nameEn: string;

  @ApiProperty({ example: 'dresses' })
  @Matches(SLUG_REGEX, { message: 'slug must be lowercase-kebab-case' })
  slug: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUrl()
  imageUrl?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  sortOrder?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

export class UpdateCategoryDto extends PartialType(CreateCategoryDto) {}

// ---------------------------------------------------------------- products ---

export class VariantDto {
  @ApiPropertyOptional({ example: '100-1', description: 'Leave out to number it automatically: <product SKU>-<n>' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toUpperCase() : value))
  @Matches(/^[A-Z0-9][A-Z0-9-]{1,49}$/, { message: 'sku must be uppercase letters, digits and dashes' })
  sku?: string;

  @ApiPropertyOptional({ description: "Manufacturer's barcode printed on the item (scanned with the camera)" })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim() || null : value))
  @ValidateIf((_, v) => v !== null)
  @IsString()
  @MaxLength(64)
  barcode?: string | null;

  @ApiPropertyOptional({ example: 'M' })
  @IsOptional()
  @IsString()
  @MaxLength(20)
  size?: string;

  @ApiPropertyOptional({ example: 'أسود' })
  @IsOptional()
  @IsString()
  @MaxLength(40)
  color?: string;

  @ApiPropertyOptional({ example: '#000000' })
  @IsOptional()
  @Matches(/^#[0-9a-fA-F]{6}$/)
  colorHex?: string;

  @ApiPropertyOptional({ description: 'Overrides the product base price' })
  @IsOptional()
  @IsAmount()
  price?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  @Min(0)
  weightGram?: number;

  @ApiPropertyOptional({ default: 5 })
  @IsOptional()
  @IsInt()
  @Min(0)
  lowStockThreshold?: number;
}

/** A new variant, optionally with its opening stock in the default warehouse. */
export class NewVariantDto extends VariantDto {
  @ApiPropertyOptional({ example: 10, description: 'Pieces on hand now (booked as opening stock)' })
  @IsOptional()
  @IsInt()
  @Min(0)
  initialQuantity?: number;

  @ApiPropertyOptional({ example: '45.00', description: "Cost per piece in SAR; defaults to the product's cost" })
  @IsOptional()
  @IsAmount()
  unitCost?: string;
}

export class UpdateVariantDto extends PartialType(VariantDto) {
  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;
}

export class ProductImageDto {
  @ApiProperty()
  @IsUrl()
  url: string;

  @ApiPropertyOptional({ description: 'Image specific to one variant' })
  @IsOptional()
  @IsUUID()
  variantId?: string;

  @ApiPropertyOptional({ example: 'أسود', description: 'Shown when the shopper picks this color' })
  @IsOptional()
  @IsString()
  @MaxLength(40)
  color?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  altText?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  sortOrder?: number;
}

export class ReplaceImagesDto {
  @ApiProperty({ type: [ProductImageDto], maxItems: 10 })
  @ArrayMaxSize(10)
  @ValidateNested({ each: true })
  @Type(() => ProductImageDto)
  images: ProductImageDto[];
}

export class CreateProductDto {
  @ApiProperty()
  @IsUUID()
  categoryId: string;

  @ApiProperty({ example: 'فستان سهرة' })
  @IsString()
  @MinLength(2)
  @MaxLength(200)
  nameAr: string;

  @ApiProperty({ example: 'Evening Dress' })
  @IsString()
  @MinLength(2)
  @MaxLength(200)
  nameEn: string;

  @ApiProperty({ example: 'evening-dress' })
  @Matches(SLUG_REGEX, { message: 'slug must be lowercase-kebab-case' })
  slug: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  descriptionAr?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  descriptionEn?: string;

  @ApiPropertyOptional({ enum: ProductStatus, default: ProductStatus.DRAFT })
  @IsOptional()
  @IsEnum(ProductStatus)
  status?: ProductStatus;

  @ApiProperty({ example: '1250.00' })
  @IsAmount()
  basePrice: string;

  @ApiPropertyOptional({ example: '450.00', description: 'Cost per piece in SAR, shared by all sizes and colors' })
  @IsOptional()
  @IsAmount()
  costPrice?: string;

  @ApiPropertyOptional({ example: 100, description: 'Numeric product SKU (100-100000); assigned automatically when left out' })
  @IsOptional()
  @IsInt()
  @Min(100)
  @Max(100000)
  skuNumber?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsAmount()
  compareAtPrice?: string;

  @ApiPropertyOptional({
    nullable: true,
    description: 'Omit to use the default tax rate; null for a tax-exempt product',
  })
  @IsOptional()
  @ValidateIf((_, v) => v !== null)
  @IsUUID()
  taxRateId?: string | null;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isFeatured?: boolean;

  @ApiProperty({ type: [NewVariantDto] })
  @ValidateNested({ each: true })
  @Type(() => NewVariantDto)
  @ArrayMinSize(1)
  variants: NewVariantDto[];

  @ApiPropertyOptional({ type: [ProductImageDto], maxItems: 10 })
  @IsOptional()
  @ArrayMaxSize(10)
  @ValidateNested({ each: true })
  @Type(() => ProductImageDto)
  images?: ProductImageDto[];
}

export class UpdateProductDto extends PartialType(OmitType(CreateProductDto, ['variants', 'images', 'skuNumber'] as const)) {}

export class AdminProductsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  categoryId?: string;

  @ApiPropertyOptional({ enum: ProductStatus })
  @IsOptional()
  @IsEnum(ProductStatus)
  status?: ProductStatus;
}

export class StoreProductsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional({ description: 'Category slug; includes sub-categories' })
  @IsOptional()
  @IsString()
  category?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @Transform(toBool)
  @IsBoolean()
  featured?: boolean;

  @ApiPropertyOptional({ enum: ['newest', 'price_asc', 'price_desc'] })
  @IsOptional()
  @IsIn(['newest', 'price_asc', 'price_desc'])
  sort?: 'newest' | 'price_asc' | 'price_desc';
}

export class PromotionDto {
  @ApiProperty({ example: 'تخفيضات نهاية الموسم' })
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name: string;

  @ApiProperty({ example: '20', description: 'Percent off, e.g. 20 for 20%' })
  @IsAmount()
  percent: string;

  @ApiPropertyOptional({ description: 'Defaults to now' })
  @IsOptional()
  @IsDateString()
  startsAt?: string;

  @ApiPropertyOptional({ nullable: true })
  @IsOptional()
  @ValidateIf((_, v) => v !== null)
  @IsDateString()
  endsAt?: string | null;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiProperty({ type: [String] })
  @IsUUID('all', { each: true })
  @ArrayMinSize(1)
  productIds: string[];
}

export class UpdatePromotionDto extends PartialType(PromotionDto) {}
