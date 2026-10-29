import { AdminTodayController, TodayService } from './today';
import { AdminCustomerAccessController, CustomerAccessService } from './customer-access';
import { AdminPreOrdersController, StorePreOrdersController } from './preorders.controller';
import { PreOrdersService } from './preorders.service';
import { AdminDealsController } from './deals.controller';
import { DealsService } from './deals.service';
import { AdminGiftCardsController, AdminLayawaysController, StoreGiftCardsController } from './layaway-giftcard.controller';
import { GiftCardsService } from './gift-cards.service';
import { LayawayService } from './layaway.service';
import { Module } from '@nestjs/common';
import { AdminCustomerToolsController, CustomerAdminService } from './customer-admin';
import { AdminLoyaltyController, LoyaltyService, StoreLoyaltyController } from './loyalty';
import { AdminCarriersController, CarriersService, StoreCarriersController } from './carriers';
import { InventoryModule } from '../inventory/inventory.module';
import {
  AdminCourierSettlementsController,
  AdminCustomersController,
  AdminOrdersController,
  AdminReturnsController,
  AdminSalesSettingsController,
} from './admin-sales.controller';
import { CartService } from './cart.service';
import { ShiftService } from './shift.service';
import { CourierSettlementsService } from './courier-settlements.service';
import { AdminCurrenciesController, StoreCurrenciesController } from './currency.controller';
import { CustomersService } from './customers.service';
import { OrdersService } from './orders.service';
import { OnlinePaymentsService } from './online-payments.service';
import { PaymentsController } from './payments.controller';
import { AdminPosController } from './pos.controller';
import { PosService } from './pos.service';
import { PaymentsService } from './payments.service';
import { ReturnsService } from './returns.service';
import {
  StoreAddressesController,
  StoreCartController,
  StoreOrdersController,
  StorePaymentMethodsController,
  StoreReturnsController,
  StoreShippingController,
  StoreStockAlertsController,
} from './store.controller';
import { EngagementService } from './engagement.service';
import { StoreSettingsService } from './store-settings.service';

@Module({
  imports: [InventoryModule],
  controllers: [
    AdminTodayController,
    AdminCustomerToolsController,
    AdminLoyaltyController,
    StoreLoyaltyController,
    AdminCarriersController,
    StoreCarriersController,
    AdminCustomerAccessController,
    StoreShippingController,
    StoreAddressesController,
    StoreCartController,
    StoreOrdersController,
    StoreReturnsController,
    StorePaymentMethodsController,
    PaymentsController,
    StoreCurrenciesController,
    AdminCurrenciesController,
    AdminOrdersController,
    AdminReturnsController,
    AdminCustomersController,
    AdminSalesSettingsController,
    AdminCourierSettlementsController,
    AdminPosController,
    StoreStockAlertsController,
    AdminLayawaysController,
    AdminGiftCardsController,
    StoreGiftCardsController,
    AdminDealsController,
    StorePreOrdersController,
    AdminPreOrdersController,
  ],
  providers: [
    TodayService,
    CarriersService,
    CustomerAdminService,
    LoyaltyService,
    CustomerAccessService, CartService, CourierSettlementsService, CustomersService, OnlinePaymentsService, OrdersService, PaymentsService, PosService, ReturnsService, ShiftService, EngagementService, StoreSettingsService, LayawayService, GiftCardsService, DealsService, PreOrdersService],
  exports: [OrdersService, PosService, CartService, LoyaltyService, CarriersService],
})
export class SalesModule {}
