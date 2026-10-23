import { Module } from '@nestjs/common';
import { FiscalController } from './fiscal.controller';
import { FiscalService } from './fiscal.service';
import { ReportsController } from './reports.controller';
import { ReportsService } from './reports.service';
import { DailySummaryService } from './daily-summary.service';

@Module({
  controllers: [ReportsController, FiscalController],
  providers: [ReportsService, FiscalService, DailySummaryService],
})
export class ReportsModule {}
