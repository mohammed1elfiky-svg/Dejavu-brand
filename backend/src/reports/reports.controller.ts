import { Body, Controller, Get, HttpCode, HttpStatus, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { RequirePermissions } from '../common/decorators/auth.decorators';
import { AsOfQueryDto, DailySummarySettingsDto, PeriodQueryDto, ProductsReportQueryDto, ReorderQueryDto, SalesReportQueryDto } from './dto/reports.dto';
import { DailySummaryService } from './daily-summary.service';
import { ReportsService } from './reports.service';

@ApiTags('Admin · Reports')
@Controller('admin/reports')
export class ReportsController {
  constructor(
    private readonly reports: ReportsService,
    private readonly daily: DailySummaryService,
  ) {}

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

  /** Revenue of till and app sales, retail and merchants, separately and together. */
  @RequirePermissions('reports.sales')
  @Get('revenue')
  revenue(@Query() q: PeriodQueryDto) {
    return this.reports.revenue(q);
  }

  /** Best sellers of the period with profit, and dead stock. */
  @RequirePermissions('reports.sales')
  @Get('products')
  products(@Query() q: ProductsReportQueryDto) {
    return this.reports.products(q);
  }

  /** Suggested quantities to order again, from the pace of sales. */
  @RequirePermissions('reports.inventory')
  @Get('reorder')
  reorder(@Query() q: ReorderQueryDto) {
    return this.reports.reorder(q);
  }

  /** The evening summary texted to the owner: settings, preview and send now. */
  @RequirePermissions('settings.manage')
  @Get('daily-summary/settings')
  dailySettings() {
    return this.daily.settings();
  }

  @RequirePermissions('settings.manage')
  @Put('daily-summary/settings')
  saveDailySettings(@Body() dto: DailySummarySettingsDto) {
    return this.daily.updateSettings(dto);
  }

  @RequirePermissions('dashboard.view')
  @Get('daily-summary')
  async dailyPreview() {
    return { text: await this.daily.text() };
  }

  @RequirePermissions('settings.manage')
  @Post('daily-summary/send')
  @HttpCode(HttpStatus.OK)
  sendDaily() {
    return this.daily.send();
  }

  @RequirePermissions('reports.inventory')
  @Get('inventory-valuation')
  inventoryValuation() {
    return this.reports.inventoryValuation();
  }
}
