import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import { AdminCatalogController, AdminPromotionsController, StoreCatalogController } from './catalog.controller';
import { CatalogService } from './catalog.service';
import { PromotionsService } from './promotions.service';

@Module({
  imports: [InventoryModule],
  controllers: [AdminCatalogController, StoreCatalogController, AdminPromotionsController],
  providers: [CatalogService, PromotionsService],
  exports: [CatalogService],
})
export class CatalogModule {}
