import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import { AdminCustomersController, AdminOrdersController, AdminSalesSettingsController } from './admin-sales.controller';
import { CartService } from './cart.service';
import { CustomersService } from './customers.service';
import { OrdersService } from './orders.service';
import { PaymentsService } from './payments.service';
import { StoreAddressesController, StoreCartController, StoreOrdersController, StoreShippingController } from './store.controller';

@Module({
  imports: [InventoryModule],
  controllers: [
    StoreShippingController,
    StoreAddressesController,
    StoreCartController,
    StoreOrdersController,
    AdminOrdersController,
    AdminCustomersController,
    AdminSalesSettingsController,
  ],
  providers: [CartService, CustomersService, OrdersService, PaymentsService],
})
export class SalesModule {}
