import { Controller, Get, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { RequirePermissions } from '../common/decorators/auth.decorators';
import { AsOfQueryDto, PeriodQueryDto, SalesReportQueryDto } from './dto/reports.dto';
import { ReportsService } from './reports.service';

@ApiTags('Admin · Reports')
@Controller('admin/reports')
export class ReportsController {
  constructor(private readonly reports: ReportsService) {}

  @RequirePermissions('dashboard.view')
  @Get('dashboard')
  dashboard() {
    return this.reports.dashboard();
  }

  @RequirePermissions('reports.financial')
  @Get('income-statement')
  incomeStatement(@Query() q: PeriodQueryDto) {
    return this.reports.incomeStatement(q);
  }

  @RequirePermissions('reports.financial')
  @Get('balance-sheet')
  balanceSheet(@Query() q: AsOfQueryDto) {
    return this.reports.balanceSheet(q);
  }

  @RequirePermissions('reports.sales')
  @Get('sales')
  sales(@Query() q: SalesReportQueryDto) {
    return this.reports.sales(q);
  }

  @RequirePermissions('reports.inventory')
  @Get('inventory-valuation')
  inventoryValuation() {
    return this.reports.inventoryValuation();
  }
}
