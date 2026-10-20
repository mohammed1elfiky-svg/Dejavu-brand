import { Module } from '@nestjs/common';
import { AdminCatalogController, StoreCatalogController } from './catalog.controller';
import { CatalogService } from './catalog.service';

@Module({
  controllers: [AdminCatalogController, StoreCatalogController],
  providers: [CatalogService],
})
export class CatalogModule {}
