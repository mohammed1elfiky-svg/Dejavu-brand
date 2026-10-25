import { Module } from '@nestjs/common';
import { CatalogModule } from '../catalog/catalog.module';
import { ExcelController } from './excel.controller';
import { ExcelService } from './excel.service';

/** Excel exports and the product import sheet. */
@Module({
  imports: [CatalogModule],
  controllers: [ExcelController],
  providers: [ExcelService],
})
export class ExcelModule {}
