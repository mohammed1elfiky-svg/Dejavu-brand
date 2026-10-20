import { Body, Controller, Get, Post, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import {
  CreateWarehouseDto,
  LowStockQueryDto,
  MovementsQueryDto,
  StockAdjustmentDto,
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
}
