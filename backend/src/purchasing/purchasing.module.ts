import { Module } from '@nestjs/common';
import { CatalogModule } from '../catalog/catalog.module';
import { InventoryModule } from '../inventory/inventory.module';
import { ImportService } from './import.service';
import { PurchasingController } from './purchasing.controller';
import { PurchasingService } from './purchasing.service';

@Module({
  imports: [InventoryModule, CatalogModule],
  controllers: [PurchasingController],
  providers: [PurchasingService, ImportService],
})
export class PurchasingModule {}
