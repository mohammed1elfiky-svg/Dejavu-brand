import { Body, Controller, Get, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { startOfBusinessDay } from '../common/utils/dates';
import { CustomersService } from './customers.service';
import { PosArrangeDto, PosLookupQueryDto, PosProductsQueryDto, PosSaleDto, QuickCustomerDto } from './dto/pos.dto';
import { PosService } from './pos.service';

@ApiTags('Admin · POS')
@Controller('admin/pos')
export class AdminPosController {
  constructor(
    private readonly pos: PosService,
    private readonly customers: CustomersService,
    private readonly config: ConfigService,
  ) {}

  /** Products for the cashier grid, in the arranged order, with promotion prices and stock. */
  @RequirePermissions('pos.sell')
  @Get('products')
  products(@Query() q: PosProductsQueryDto) {
    return this.pos.products(q);
  }

  /** Resolves a scanned barcode: variant SKU (100-2), factory barcode, or product SKU (100). */
  @RequirePermissions('pos.sell')
  @Get('lookup')
  lookup(@Query() q: PosLookupQueryDto) {
    return this.pos.lookup(q.code);
  }

  /** Saves the grid order (drag and drop). */
  @RequirePermissions('products.write')
  @Put('product-order')
  arrange(@Body() dto: PosArrangeDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.arrange(dto.productIds, u.id);
  }

  /** Rings up a sale: cash (any mix of currencies, with change) or credit to the customer's account. */
  @RequirePermissions('pos.sell')
  @Post('sales')
  sell(@Body() dto: PosSaleDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.sell(dto, u.id);
  }

  @RequirePermissions('pos.sell')
  @Get('sales/:id')
  receipt(@Param('id', ParseUUIDPipe) id: string) {
    return this.pos.receipt(id);
  }

  /** The cashier's sales today and their drawers. */
  @RequirePermissions('pos.sell')
  @Get('shift')
  shift(@CurrentUser() u: AuthPrincipal) {
    return this.pos.shift(u.id, startOfBusinessDay(this.config.getOrThrow<string>('STORE_TIMEZONE')));
  }

  /** Adds a customer from the till (name + phone), e.g. for a credit sale. */
  @RequirePermissions('pos.sell')
  @Post('customers')
  quickCustomer(@Body() dto: QuickCustomerDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.quickCreate(dto, u.id);
  }
}
