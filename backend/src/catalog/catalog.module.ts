import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import { AdminCatalogController, AdminPromotionsController, StoreCatalogController } from './catalog.controller';
import { CatalogService } from './catalog.service';
import { PromotionsService } from './promotions.service';
import { AdminReviewsController, StoreReviewsController } from './reviews.controller';
import { ReviewsService } from './reviews.service';

@Module({
  imports: [InventoryModule],
  controllers: [AdminCatalogController, StoreCatalogController, AdminPromotionsController, StoreReviewsController, AdminReviewsController],
  providers: [CatalogService, PromotionsService, ReviewsService],
  exports: [CatalogService],
})
export class CatalogModule {}
