import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import { AdminCatalogController, StoreCatalogController } from './catalog.controller';
import { CatalogService } from './catalog.service';

@Module({
  imports: [InventoryModule],
  controllers: [AdminCatalogController, StoreCatalogController],
  providers: [CatalogService],
})
export class CatalogModule {}
