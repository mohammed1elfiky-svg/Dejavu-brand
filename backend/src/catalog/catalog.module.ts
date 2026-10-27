import { AdminPricingController } from './pricing.controller';
import { BulkPricingService } from './bulk-pricing.service';
import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import { AdminCatalogController, AdminPromotionsController, StoreCatalogController } from './catalog.controller';
import { CatalogService } from './catalog.service';
import { PromotionsService } from './promotions.service';
import { AdminReviewsController, StoreReviewsController } from './reviews.controller';
import { ReviewsService } from './reviews.service';
import { AdminStoreOrderController, StoreOrderService } from './store-order';

@Module({
  imports: [InventoryModule],
  controllers: [AdminStoreOrderController, AdminPricingController, AdminCatalogController, StoreCatalogController, AdminPromotionsController, StoreReviewsController, AdminReviewsController],
  providers: [StoreOrderService, CatalogService, PromotionsService, ReviewsService, BulkPricingService],
  exports: [CatalogService],
})
export class CatalogModule {}
