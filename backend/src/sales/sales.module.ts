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
import { AdminExchangeRatesController, StoreCurrenciesController } from './currency.controller';
import { CustomersService } from './customers.service';
import { OrdersService } from './orders.service';
import { OnlinePaymentsService } from './online-payments.service';
import { PaymentsController } from './payments.controller';
import { PaymentsService } from './payments.service';
import { ReturnsService } from './returns.service';
import {
  StoreAddressesController,
  StoreCartController,
  StoreOrdersController,
  StorePaymentMethodsController,
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
    StorePaymentMethodsController,
    PaymentsController,
    StoreCurrenciesController,
    AdminExchangeRatesController,
    AdminOrdersController,
    AdminReturnsController,
    AdminCustomersController,
    AdminSalesSettingsController,
    AdminCourierSettlementsController,
  ],
  providers: [CartService, CourierSettlementsService, CustomersService, OnlinePaymentsService, OrdersService, PaymentsService, ReturnsService],
})
export class SalesModule {}
