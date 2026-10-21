import { Body, Controller, Get, Param, ParseEnumPipe, Put } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsOptional, IsString, MaxLength } from 'class-validator';
import { AuthPrincipal, CurrentUser, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { IsAmount } from '../common/decorators/validation.decorators';
import { CurrencyService } from '../common/currency.service';
import { Currency } from '../generated/prisma/client';

export class SetExchangeRateDto {
  @ApiProperty({ example: '140', description: 'Units of the currency per 1 SAR' })
  @IsAmount()
  rate: string;

  @ApiPropertyOptional({ example: 'سعر السوق اليوم' })
  @IsOptional()
  @IsString()
  @MaxLength(255)
  note?: string;
}

@ApiTags('Store · Currencies')
@Public()
@Controller('store/currencies')
export class StoreCurrenciesController {
  constructor(private readonly currency: CurrencyService) {}

  /** Base currency (SAR) and the sales currencies with their current rates. */
  @Get()
  list() {
    return this.currency.overview();
  }
}

@ApiTags('Admin · Currencies')
@Controller('admin/exchange-rates')
export class AdminExchangeRatesController {
  constructor(private readonly currency: CurrencyService) {}

  @RequirePermissions('orders.read')
  @Get()
  async list() {
    return { ...(await this.currency.overview()), history: await this.currency.history(Currency.YER) };
  }

  /** Sets the rate used for new orders from now on. */
  @RequirePermissions('settings.manage')
  @Put(':currency')
  async set(
    @Param('currency', new ParseEnumPipe(Currency)) currency: Currency,
    @Body() dto: SetExchangeRateDto,
    @CurrentUser() u: AuthPrincipal,
  ) {
    await this.currency.setRate(currency, dto.rate, dto.note, u.id);
    return this.list();
  }
}
