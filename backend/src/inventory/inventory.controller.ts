import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import {
  CreateStockCountDto,
  CreateWarehouseDto,
  LowStockQueryDto,
  MovementsQueryDto,
  StockAdjustmentDto,
  StockCountsQueryDto,
  StockLevelsQueryDto,
  StockTransferDto,
} from './dto/inventory.dto';
import { InventoryService } from './inventory.service';

@ApiTags('Admin · Inventory')
@Controller('admin')
export class InventoryController {
  constructor(private readonly inventory: InventoryService) {}

  @RequirePermissions('stock.read')
  @Get('warehouses')
  listWarehouses() {
    return this.inventory.listWarehouses();
  }

  @RequirePermissions('settings.manage')
  @Post('warehouses')
  createWarehouse(@Body() dto: CreateWarehouseDto, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.createWarehouse(dto, u.id);
  }

  @RequirePermissions('stock.read')
  @Get('inventory/levels')
  levels(@Query() q: StockLevelsQueryDto) {
    return this.inventory.levels(q);
  }

  @RequirePermissions('stock.read')
  @Get('inventory/low-stock')
  lowStock(@Query() q: LowStockQueryDto) {
    return this.inventory.lowStock(q);
  }

  @RequirePermissions('stock.read')
  @Get('inventory/movements')
  movements(@Query() q: MovementsQueryDto) {
    return this.inventory.movements(q);
  }

  @RequirePermissions('stock.adjust')
  @Post('inventory/adjustments')
  adjust(@Body() dto: StockAdjustmentDto, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.adjust(dto, u.id);
  }

  @RequirePermissions('stock.transfer')
  @Post('inventory/transfers')
  transfer(@Body() dto: StockTransferDto, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.transfer(dto, u.id);
  }

  @RequirePermissions('stock.read')
  @Get('stock-counts')
  listCounts(@Query() q: StockCountsQueryDto) {
    return this.inventory.listCounts(q);
  }

  @RequirePermissions('stock.read')
  @Get('stock-counts/:id')
  getCount(@Param('id', ParseUUIDPipe) id: string) {
    return this.inventory.getCount(id);
  }

  @RequirePermissions('stock.count')
  @Post('stock-counts')
  createCount(@Body() dto: CreateStockCountDto, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.createCount(dto, u.id);
  }

  @RequirePermissions('stock.count', 'stock.adjust')
  @Post('stock-counts/:id/post')
  @HttpCode(HttpStatus.OK)
  postCount(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.postCount(id, u.id);
  }

  @RequirePermissions('stock.count')
  @Delete('stock-counts/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteCount(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.inventory.deleteCount(id, u.id);
  }
}
