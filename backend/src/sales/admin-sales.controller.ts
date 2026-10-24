import { Body, Controller, ForbiddenException, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query, UploadedFile, UseInterceptors } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { MAX_FILE_BYTES, UploadedPart } from '../files/files.service';
import { ApiConsumes, ApiTags } from '@nestjs/swagger';
import { AccountingService } from '../accounting/accounting.service';
import { DateRangeQueryDto } from '../accounting/dto/accounting.dto';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { CourierSettlementsService } from './courier-settlements.service';
import { CustomersService } from './customers.service';
import {
  AdminOrdersQueryDto,
  ChangeOrderStatusDto,
  CreateCouponDto,
  CourierSettlementDto,
  CreateReturnDto,
  CustomersQueryDto,
  PendingCodQueryDto,
  ReceiveReturnDto,
  RecordPaymentDto,
  RefundDto,
  RefundReturnDto,
  RejectReturnDto,
  ReturnsQueryDto,
  ShippingZoneDto,
  UpdateCouponDto,
  UpdateCustomerDto,
} from './dto/sales.dto';
import { OrdersService } from './orders.service';
import { ReturnsService } from './returns.service';

@ApiTags('Admin · Orders')
@Controller('admin/orders')
export class AdminOrdersController {
  constructor(
    private readonly orders: OrdersService,
    private readonly returns: ReturnsService,
  ) {}

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

  /** Photo of the shipping receipt for the customer (multipart `file`); they are notified in the app. */
  @RequirePermissions('orders.update_status')
  @Post(':id/shipping-receipt')
  @HttpCode(HttpStatus.OK)
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_FILE_BYTES + 1 } }))
  @ApiConsumes('multipart/form-data')
  shippingReceipt(@Param('id', ParseUUIDPipe) id: string, @UploadedFile() file: UploadedPart | undefined, @CurrentUser() u: AuthPrincipal) {
    return this.orders.attachShippingReceipt(id, file, u.id);
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

  @RequirePermissions('returns.manage')
  @Post(':id/returns')
  createReturn(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CreateReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.returns.staffCreate(id, dto, u.id);
  }
}

@ApiTags('Admin · Returns')
@Controller('admin/returns')
export class AdminReturnsController {
  constructor(private readonly returns: ReturnsService) {}

  @RequirePermissions('returns.manage')
  @Get()
  list(@Query() q: ReturnsQueryDto) {
    return this.returns.list(q);
  }

  @RequirePermissions('returns.manage')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.returns.get(id);
  }

  @RequirePermissions('returns.manage')
  @Post(':id/approve')
  @HttpCode(HttpStatus.OK)
  approve(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.returns.approve(id, u.id);
  }

  @RequirePermissions('returns.manage')
  @Post(':id/reject')
  @HttpCode(HttpStatus.OK)
  reject(@Param('id', ParseUUIDPipe) id: string, @Body() dto: RejectReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.returns.reject(id, dto.reason, u.id);
  }

  @RequirePermissions('returns.manage', 'stock.adjust')
  @Post(':id/receive')
  @HttpCode(HttpStatus.OK)
  receive(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ReceiveReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.returns.receive(id, dto, u.id);
  }

  @RequirePermissions('returns.manage', 'payments.record')
  @Post(':id/refund')
  @HttpCode(HttpStatus.OK)
  refund(@Param('id', ParseUUIDPipe) id: string, @Body() dto: RefundReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.returns.refund(id, dto, u.id);
  }
}

@ApiTags('Admin · Customers')
@Controller('admin/customers')
export class AdminCustomersController {
  constructor(
    private readonly customers: CustomersService,
    private readonly accounting: AccountingService,
  ) {}

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

  @RequirePermissions('customers.write')
  @Patch(':id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateCustomerDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.updateMerchant(id, dto, u.id);
  }

  @RequirePermissions('customers.read', 'journal.read')
  @Get(':id/statement')
  statement(@Param('id', ParseUUIDPipe) id: string, @Query() q: DateRangeQueryDto) {
    return this.accounting.partyStatement({ customerId: id }, q);
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

@ApiTags('Admin · Courier settlements')
@Controller('admin/courier-settlements')
export class AdminCourierSettlementsController {
  constructor(private readonly settlements: CourierSettlementsService) {}

  @RequirePermissions('payments.read')
  @Get('pending')
  pending(@Query() q: PendingCodQueryDto) {
    return this.settlements.pending(q);
  }

  @RequirePermissions('payments.read')
  @Get()
  list(@Query() q: PaginationQueryDto) {
    return this.settlements.list(q);
  }

  @RequirePermissions('payments.read')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.settlements.get(id);
  }

  @RequirePermissions('payments.record')
  @Post()
  settle(@Body() dto: CourierSettlementDto, @CurrentUser() u: AuthPrincipal) {
    return this.settlements.settle(dto, u.id);
  }
}
