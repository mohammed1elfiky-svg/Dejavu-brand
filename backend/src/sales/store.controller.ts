import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
  Put,
  Query,
  Req,
  UnprocessableEntityException,
} from '@nestjs/common';
import { EngagementService } from './engagement.service';
import type { Request } from 'express';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { CartService } from './cart.service';
import { CustomersService } from './customers.service';
import {
  AddCartItemDto,
  AddressDto,
  ApplyCouponDto,
  CancelOrderDto,
  CartQueryDto,
  CHECKOUT_METHODS,
  CheckoutDto,
  CreateReturnDto,
  UpdateAddressDto,
  UpdateCartItemDto,
} from './dto/sales.dto';
import { PaymentMethod } from '../generated/prisma/client';
import { OnlinePaymentsService } from './online-payments.service';
import { OrdersService } from './orders.service';
import { ReturnsService } from './returns.service';

@ApiTags('Store · Shipping')
@Public()
@Controller('store/shipping-zones')
export class StoreShippingController {
  constructor(private readonly customers: CustomersService) {}

  @Get()
  zones() {
    return this.customers.listZones(true);
  }
}

@ApiTags('Store · Account')
@CustomerOnly()
@Controller('store/me/addresses')
export class StoreAddressesController {
  constructor(private readonly customers: CustomersService) {}

  @Get()
  list(@CurrentUser() u: AuthPrincipal) {
    return this.customers.listAddresses(u.id);
  }

  @Post()
  create(@CurrentUser() u: AuthPrincipal, @Body() dto: AddressDto) {
    return this.customers.createAddress(u.id, dto);
  }

  @Patch(':id')
  update(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateAddressDto) {
    return this.customers.updateAddress(u.id, id, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.customers.deleteAddress(u.id, id);
  }
}

@ApiTags('Store · Cart')
@CustomerOnly()
@Controller('store/cart')
export class StoreCartController {
  constructor(private readonly cart: CartService) {}

  @Get()
  view(@CurrentUser() u: AuthPrincipal, @Query() q: CartQueryDto) {
    return this.cart.view(u.id, q.addressId);
  }

  @Post('items')
  async add(@CurrentUser() u: AuthPrincipal, @Body() dto: AddCartItemDto) {
    await this.cart.addItem(u.id, dto);
    return this.cart.view(u.id);
  }

  @Patch('items/:variantId')
  async update(
    @CurrentUser() u: AuthPrincipal,
    @Param('variantId', ParseUUIDPipe) variantId: string,
    @Body() dto: UpdateCartItemDto,
  ) {
    await this.cart.setQuantity(u.id, variantId, dto.quantity);
    return this.cart.view(u.id);
  }

  @Delete('items/:variantId')
  async remove(@CurrentUser() u: AuthPrincipal, @Param('variantId', ParseUUIDPipe) variantId: string) {
    await this.cart.removeItem(u.id, variantId);
    return this.cart.view(u.id);
  }

  @Put('coupon')
  async applyCoupon(@CurrentUser() u: AuthPrincipal, @Body() dto: ApplyCouponDto) {
    await this.cart.setCoupon(u.id, dto.code);
    return this.cart.view(u.id);
  }

  @Delete('coupon')
  async removeCoupon(@CurrentUser() u: AuthPrincipal) {
    await this.cart.setCoupon(u.id, null);
    return this.cart.view(u.id);
  }
}

@ApiTags('Store · Orders')
@CustomerOnly()
@Controller('store/orders')
export class StoreOrdersController {
  constructor(
    private readonly orders: OrdersService,
    private readonly returns: ReturnsService,
    private readonly online: OnlinePaymentsService,
  ) {}

  @Post()
  checkout(@CurrentUser() u: AuthPrincipal, @Body() dto: CheckoutDto) {
    if (dto.paymentMethod === PaymentMethod.CARD && !this.online.enabled) {
      throw new UnprocessableEntityException('Card payments are not available at the moment');
    }
    return this.orders.checkout(u.id, dto);
  }

  /** Opens the card payment page for an unpaid card order. Open `checkoutUrl` in a browser. */
  @Post(':number/pay')
  @HttpCode(HttpStatus.OK)
  pay(@CurrentUser() u: AuthPrincipal, @Param('number') number: string, @Req() req: Request) {
    const host = (req.headers['x-forwarded-host'] as string | undefined)?.split(',')[0] ?? req.headers.host;
    const proto = (req.headers['x-forwarded-proto'] as string | undefined)?.split(',')[0] ?? req.protocol;
    return this.online.start(u.id, number, host ? `${proto}://${host}` : undefined);
  }

  @Get()
  list(@CurrentUser() u: AuthPrincipal, @Query() q: PaginationQueryDto) {
    return this.orders.customerOrders(u.id, q);
  }

  @Get(':number')
  get(@CurrentUser() u: AuthPrincipal, @Param('number') number: string) {
    return this.orders.customerOrder(u.id, number);
  }

  @Post(':number/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@CurrentUser() u: AuthPrincipal, @Param('number') number: string, @Body() dto: CancelOrderDto) {
    return this.orders.customerCancel(u.id, number, dto.reason);
  }

  @Post(':number/returns')
  requestReturn(@CurrentUser() u: AuthPrincipal, @Param('number') number: string, @Body() dto: CreateReturnDto) {
    return this.returns.customerRequest(u.id, number, dto);
  }
}

@ApiTags('Store · Orders')
@CustomerOnly()
@Controller('store/returns')
export class StoreReturnsController {
  constructor(private readonly returns: ReturnsService) {}

  @Get()
  list(@CurrentUser() u: AuthPrincipal) {
    return this.returns.customerReturns(u.id);
  }
}

@ApiTags('Store · Orders')
@Public()
@Controller('store/payment-methods')
export class StorePaymentMethodsController {
  constructor(private readonly online: OnlinePaymentsService) {}

  /** Payment methods the checkout currently accepts. */
  @Get()
  list() {
    return CHECKOUT_METHODS.filter((m) => m !== PaymentMethod.CARD || this.online.enabled);
  }
}

/** "Tell me when it is back in stock". */
@ApiTags('Store · Stock alerts')
@CustomerOnly()
@Controller('store/stock-alerts')
export class StoreStockAlertsController {
  constructor(private readonly engagement: EngagementService) {}

  @Get()
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.engagement.mine(u.id);
  }

  @Post(':variantId')
  @HttpCode(HttpStatus.OK)
  watch(@Param('variantId', ParseUUIDPipe) variantId: string, @CurrentUser() u: AuthPrincipal) {
    return this.engagement.watch(u.id, variantId);
  }

  @Delete(':variantId')
  @HttpCode(HttpStatus.OK)
  unwatch(@Param('variantId', ParseUUIDPipe) variantId: string, @CurrentUser() u: AuthPrincipal) {
    return this.engagement.unwatch(u.id, variantId);
  }
}
