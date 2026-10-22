import { Module } from '@nestjs/common';
import { CatalogModule } from '../catalog/catalog.module';
import { InventoryModule } from '../inventory/inventory.module';
import { TreasuryModule } from '../treasury/treasury.module';
import { ImportService } from './import.service';
import { PurchasingController } from './purchasing.controller';
import { PurchasingService } from './purchasing.service';
import { SupplierLedgerService } from './supplier-ledger.service';

@Module({
  imports: [InventoryModule, CatalogModule, TreasuryModule],
  controllers: [PurchasingController],
  providers: [PurchasingService, ImportService, SupplierLedgerService],
})
export class PurchasingModule {}
