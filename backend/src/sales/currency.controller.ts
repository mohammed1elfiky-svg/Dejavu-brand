import { Body, Controller, Get, Param, Patch, Post, Put } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags, PartialType, OmitType } from '@nestjs/swagger';
import { Transform } from 'class-transformer';
import { IsBoolean, IsInt, IsOptional, IsString, Matches, Max, MaxLength, Min, MinLength } from 'class-validator';
import { AuthPrincipal, CurrentUser, Public, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { CurrencyService } from '../common/currency.service';

const RATE = /^\d{1,12}(\.\d{1,6})?$/;

export class SetExchangeRateDto {
  @ApiProperty({ example: '140', description: 'Units of the currency per 1 SAR (up to 6 decimals)' })
  @Transform(({ value }) => (typeof value === 'number' ? String(value) : value))
  @Matches(RATE, { message: 'rate must be a positive number with up to 6 decimals' })
  rate: string;

  @ApiPropertyOptional({ example: 'سعر السوق اليوم' })
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;
}

export class CreateCurrencyDto {
  @ApiProperty({ example: 'USD' })
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toUpperCase() : value))
  @Matches(/^[A-Z][A-Z0-9_]{1,9}$/, { message: 'code must be 2-10 capital letters/digits, e.g. USD' })
  code: string;

  @ApiProperty({ example: 'دولار أمريكي' })
  @IsString()
  @MinLength(2)
  @MaxLength(60)
  nameAr: string;

  @ApiPropertyOptional({ example: 'US Dollar' })
  @IsOptional()
  @IsString()
  @MaxLength(60)
  nameEn?: string;

  @ApiPropertyOptional({ example: '$' })
  @IsOptional()
  @IsString()
  @MinLength(1)
  @MaxLength(10)
  symbol?: string;

  @ApiPropertyOptional({ example: 2, description: 'Digits after the decimal point (0 for YER)' })
  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(4)
  decimals?: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

  @ApiPropertyOptional({ description: 'Offer it to shoppers in the customer app' })
  @IsOptional()
  @IsBoolean()
  showInCustomerApp?: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsInt()
  sortOrder?: number;

  @ApiPropertyOptional({ example: '0.266667', description: 'Initial rate: units per 1 SAR' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'number' ? String(value) : value))
  @Matches(RATE)
  rate?: string;
}

export class UpdateCurrencyDto extends PartialType(OmitType(CreateCurrencyDto, ['code', 'rate'] as const)) {}

@ApiTags('Store · Currencies')
@Public()
@Controller('store/currencies')
export class StoreCurrenciesController {
  constructor(private readonly currency: CurrencyService) {}

  /** Base currency (SAR) and the currencies offered to shoppers, with their current rates. */
  @Get()
  list() {
    return this.currency.overview('customer');
  }
}

@ApiTags('Admin · Currencies')
@Controller('admin')
export class AdminCurrenciesController {
  constructor(private readonly currency: CurrencyService) {}

  /** Active currencies for the till and the payment dialogs. */
  @StaffOnly()
  @Get('currencies/active')
  active() {
    return this.currency.overview('staff');
  }

  /** Every currency, for the settings screen. */
  @StaffOnly()
  @Get('currencies')
  all() {
    return this.currency.overview('all');
  }

  @RequirePermissions('settings.manage')
  @Post('currencies')
  create(@Body() dto: CreateCurrencyDto, @CurrentUser() u: AuthPrincipal) {
    return this.currency.create(dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Patch('currencies/:code')
  update(@Param('code') code: string, @Body() dto: UpdateCurrencyDto, @CurrentUser() u: AuthPrincipal) {
    return this.currency.update(code.toUpperCase(), dto, u.id);
  }

  @StaffOnly()
  @Get('currencies/:code/rates')
  history(@Param('code') code: string) {
    return this.currency.history(code.toUpperCase());
  }

  /** Kept for older admin apps: base + YER with its history. */
  @RequirePermissions('orders.read')
  @Get('exchange-rates')
  async list() {
    return { ...(await this.currency.overview('staff')), history: await this.currency.history('YER') };
  }

  /** Sets the rate used for new orders from now on. */
  @RequirePermissions('settings.manage')
  @Put('exchange-rates/:currency')
  async set(@Param('currency') currency: string, @Body() dto: SetExchangeRateDto, @CurrentUser() u: AuthPrincipal) {
    await this.currency.setRate(currency.toUpperCase(), dto.rate, dto.note, u.id);
    return { ...(await this.currency.overview('staff')), history: await this.currency.history(currency.toUpperCase()) };
  }
}
