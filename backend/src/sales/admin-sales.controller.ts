import { Body, Controller, ForbiddenException, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { CustomersService } from './customers.service';
import {
  AdminOrdersQueryDto,
  ChangeOrderStatusDto,
  CreateCouponDto,
  CustomersQueryDto,
  RecordPaymentDto,
  RefundDto,
  ShippingZoneDto,
  UpdateCouponDto,
} from './dto/sales.dto';
import { OrdersService } from './orders.service';

@ApiTags('Admin · Orders')
@Controller('admin/orders')
export class AdminOrdersController {
  constructor(private readonly orders: OrdersService) {}

  @RequirePermissions('orders.read')
  @Get()
  list(@Query() q: AdminOrdersQueryDto) {
    return this.orders.adminList(q);
  }

  @RequirePermissions('orders.read')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.orders.adminGet(id);
  }

  @RequirePermissions('orders.update_status')
  @Post(':id/status')
  @HttpCode(HttpStatus.OK)
  changeStatus(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ChangeOrderStatusDto, @CurrentUser() u: AuthPrincipal) {
    if (dto.status === 'CANCELLED' && !u.permissions.includes('orders.cancel')) {
      throw new ForbiddenException('Missing permission: orders.cancel');
    }
    return this.orders.changeStatus(id, dto, u.id);
  }

  @RequirePermissions('payments.record')
  @Post(':id/payments')
  recordPayment(@Param('id', ParseUUIDPipe) id: string, @Body() dto: RecordPaymentDto, @CurrentUser() u: AuthPrincipal) {
    return this.orders.recordPayment(id, dto, u.id);
  }

  @RequirePermissions('payments.record')
  @Post(':id/refunds')
  refund(@Param('id', ParseUUIDPipe) id: string, @Body() dto: RefundDto, @CurrentUser() u: AuthPrincipal) {
    return this.orders.refund(id, dto, u.id);
  }
}

@ApiTags('Admin · Customers')
@Controller('admin/customers')
export class AdminCustomersController {
  constructor(private readonly customers: CustomersService) {}

  @RequirePermissions('customers.read')
  @Get()
  list(@Query() q: CustomersQueryDto) {
    return this.customers.listCustomers(q);
  }

  @RequirePermissions('customers.read')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.customers.getCustomer(id);
  }
}

@ApiTags('Admin · Sales settings')
@Controller('admin')
export class AdminSalesSettingsController {
  constructor(private readonly customers: CustomersService) {}

  @RequirePermissions('coupons.manage')
  @Get('coupons')
  listCoupons() {
    return this.customers.listCoupons();
  }

  @RequirePermissions('coupons.manage')
  @Post('coupons')
  createCoupon(@Body() dto: CreateCouponDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.createCoupon(dto, u.id);
  }

  @RequirePermissions('coupons.manage')
  @Patch('coupons/:id')
  updateCoupon(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateCouponDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.updateCoupon(id, dto, u.id);
  }

  @RequirePermissions('orders.read')
  @Get('shipping-zones')
  listZones() {
    return this.customers.listZones();
  }

  @RequirePermissions('settings.manage')
  @Put('shipping-zones')
  upsertZone(@Body() dto: ShippingZoneDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.upsertZone(dto, u.id);
  }
}
