import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import {
  CreateSupplierDto,
  ImportOrderDto,
  ImportReceiveDto,
  PurchaseOrderDto,
  PurchaseOrdersQueryDto,
  QuickPurchaseDto,
  ReceivePurchaseDto,
  StatementQueryDto,
  SupplierPaymentDto,
  SupplierPaymentsQueryDto,
  SuppliersQueryDto,
  UpdateSupplierDto,
} from './dto/purchasing.dto';
import { ImportService } from './import.service';
import { PurchasingService } from './purchasing.service';

@ApiTags('Admin · Purchasing')
@Controller('admin')
export class PurchasingController {
  constructor(
    private readonly purchasing: PurchasingService,
    private readonly imports: ImportService,
  ) {}

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

  /** Pieces ordered from this supplier and not received yet ("pending delivery"). */
  @RequirePermissions('purchases.read')
  @Get('suppliers/:id/pending-items')
  pendingItems(@Param('id', ParseUUIDPipe) id: string) {
    return this.imports.pendingItems(id);
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

  /** Buy and receive in one go (the till-style purchase screen). */
  /** Import order priced in the supplier's currency; new items are created as drafts held for it. */
  @RequirePermissions('purchases.write', 'products.write')
  @Post('purchase-orders/import')
  createImport(@Body() dto: ImportOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.imports.createOrder(dto, u.id);
  }

  /** Landed cost per piece for the counted quantities and CBM, without saving. */
  @RequirePermissions('purchases.read')
  @Post('purchase-orders/:id/import-costs')
  @HttpCode(HttpStatus.OK)
  importCosts(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ImportReceiveDto) {
    return this.imports.preview(id, dto);
  }

  /** "Receive and confirm matching": counted pieces go into stock at their landed cost. */
  @RequirePermissions('purchases.receive')
  @Post('purchase-orders/:id/import-receive')
  @HttpCode(HttpStatus.OK)
  importReceive(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ImportReceiveDto, @CurrentUser() u: AuthPrincipal) {
    return this.imports.receive(id, dto, u.id);
  }

  @RequirePermissions('purchases.write', 'purchases.receive')
  @Post('purchase-orders/quick')
  quick(@Body() dto: QuickPurchaseDto, @CurrentUser() u: AuthPrincipal) {
    return this.purchasing.quickPurchase(dto, u.id);
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
