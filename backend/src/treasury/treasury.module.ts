import { StoreWalletGiftController, WalletGiftService } from './wallet-gift';
import { Global, Module } from '@nestjs/common';
import { CustomerAccountsService } from './customer-accounts.service';
import {
  AdminCustomerAccountController,
  AdminDebtorsController,
  AdminTreasuryController,
  AdminWalletController,
  StoreBankAccountsController,
  StoreWalletController,
} from './treasury.controller';
import { TreasuryService } from './treasury.service';
import { WalletService } from './wallet.service';
import { TransfersService } from './transfers.service';
import { GeminiClient } from '../chat/gemini.client';

@Global()
@Module({
  controllers: [AdminTreasuryController, AdminDebtorsController, AdminCustomerAccountController, AdminWalletController, StoreBankAccountsController, StoreWalletController, StoreWalletGiftController],
  providers: [TreasuryService, CustomerAccountsService, WalletService, WalletGiftService, TransfersService, GeminiClient],
  exports: [TreasuryService, CustomerAccountsService, TransfersService],
})
export class TreasuryModule {}
