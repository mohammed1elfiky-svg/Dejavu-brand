import { Module } from '@nestjs/common';
import { InventoryModule } from '../inventory/inventory.module';
import {
  AdminCourierSettlementsController,
  AdminCustomersController,
  AdminOrdersController,
  AdminReturnsController,
  AdminSalesSettingsController,
} from './admin-sales.controller';
import { CartService } from './cart.service';
import { CourierSettlementsService } from './courier-settlements.service';
import { CustomersService } from './customers.service';
import { OrdersService } from './orders.service';
import { PaymentsService } from './payments.service';
import { ReturnsService } from './returns.service';
import {
  StoreAddressesController,
  StoreCartController,
  StoreOrdersController,
  StoreReturnsController,
  StoreShippingController,
} from './store.controller';

@Module({
  imports: [InventoryModule],
  controllers: [
    StoreShippingController,
    StoreAddressesController,
    StoreCartController,
    StoreOrdersController,
    StoreReturnsController,
    AdminOrdersController,
    AdminReturnsController,
    AdminCustomersController,
    AdminSalesSettingsController,
    AdminCourierSettlementsController,
  ],
  providers: [CartService, CourierSettlementsService, CustomersService, OrdersService, PaymentsService, ReturnsService],
})
export class SalesModule {}
