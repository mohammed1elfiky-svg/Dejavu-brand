import { Module } from '@nestjs/common';
import { CatalogModule } from '../catalog/catalog.module';
import { AnalyticsService } from './analytics.service';
import { AdminStorefrontController, ShareController, StoreAppController, StoreWishlistController } from './storefront.controller';
import { StorefrontService } from './storefront.service';

/** The customer app's extras: settings, wish list, suggestions, usage analytics, share pages. */
@Module({
  imports: [CatalogModule],
  controllers: [StoreAppController, StoreWishlistController, ShareController, AdminStorefrontController],
  providers: [StorefrontService, AnalyticsService],
  exports: [StorefrontService],
})
export class StorefrontModule {}
