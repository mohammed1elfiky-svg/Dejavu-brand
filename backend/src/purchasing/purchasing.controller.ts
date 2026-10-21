import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import {
  CreateSupplierDto,
  PurchaseOrderDto,
  PurchaseOrdersQueryDto,
  ReceivePurchaseDto,
  StatementQueryDto,
  SupplierPaymentDto,
  SupplierPaymentsQueryDto,
  SuppliersQueryDto,
  UpdateSupplierDto,
} from './dto/purchasing.dto';
import { PurchasingService } from './purchasing.service';

@ApiTags('Admin · Purchasing')
@Controller('admin')
export class PurchasingController {
  constructor(private readonly purchasing: PurchasingService) {}

  // suppliers
  @RequirePermissions('purchases.read')
  @Get('suppliers')
  listSuppliers(@Query() q: SuppliersQueryDto) {
    return this.purchasing.listSuppliers(q);
  }

  @RequirePermissions('purchases.read')
  @Get('suppliers/:id')
  getSupplier(@Param('id', ParseUUIDPipe) id: string) {
    return this.purchasing.getSupplier(id);
  }

  @RequirePermissions('purchases.read')
  @Get('suppliers/:id/statement')
  supplierStatement(@Param('id', ParseUUIDPipe) id: string, @Query() q: StatementQueryDto) {
    return this.purchasing.supplierStatement(id, q);
  }

  @RequirePermissions('suppliers.manage')
  @Post('suppliers')
  createSupplier(@Body() dto: CreateSupplierDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.createSupplier(dto, u.id);
  }

  @RequirePermissions('suppliers.manage')
  @Patch('suppliers/:id')
  updateSupplier(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateSupplierDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.updateSupplier(id, dto, u.id);
  }

  // purchase orders
  @RequirePermissions('purchases.read')
  @Get('purchase-orders')
  listOrders(@Query() q: PurchaseOrdersQueryDto) {
    return this.purchasing.listOrders(q);
  }

  @RequirePermissions('purchases.read')
  @Get('purchase-orders/:id')
  getOrder(@Param('id', ParseUUIDPipe) id: string) {
    return this.purchasing.getOrder(id);
  }

  @RequirePermissions('purchases.write')
  @Post('purchase-orders')
  createOrder(@Body() dto: PurchaseOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.createOrder(dto, u.id);
  }

  @RequirePermissions('purchases.write')
  @Put('purchase-orders/:id')
  updateOrder(@Param('id', ParseUUIDPipe) id: string, @Body() dto: PurchaseOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.updateOrder(id, dto, u.id);
  }

  @RequirePermissions('purchases.write')
  @Post('purchase-orders/:id/place')
  @HttpCode(HttpStatus.OK)
  placeOrder(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.placeOrder(id, u.id);
  }

  @RequirePermissions('purchases.write')
  @Post('purchase-orders/:id/cancel')
  @HttpCode(HttpStatus.OK)
  cancelOrder(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.cancelOrder(id, u.id);
  }

  @RequirePermissions('purchases.receive')
  @Post('purchase-orders/:id/receive')
  @HttpCode(HttpStatus.OK)
  receive(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ReceivePurchaseDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.receive(id, dto, u.id);
  }

  @RequirePermissions('purchases.write')
  @Post('purchase-orders/:id/close')
  @HttpCode(HttpStatus.OK)
  closeOrder(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.closeOrder(id, u.id);
  }

  // supplier payments
  @RequirePermissions('supplier_payments.record')
  @Post('supplier-payments')
  paySupplier(@Body() dto: SupplierPaymentDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.paySupplier(dto, u.id);
  }

  @RequirePermissions('purchases.read')
  @Get('supplier-payments')
  listPayments(@Query() q: SupplierPaymentsQueryDto) {
    return this.purchasing.listPayments(q);
  }
}
