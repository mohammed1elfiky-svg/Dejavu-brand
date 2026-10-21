import { Global, Module } from '@nestjs/common';
import { CustomerAccountsService } from './customer-accounts.service';
import {
  AdminCustomerAccountController,
  AdminTreasuryController,
  AdminWalletController,
  StoreBankAccountsController,
  StoreWalletController,
} from './treasury.controller';
import { TreasuryService } from './treasury.service';
import { WalletService } from './wallet.service';

@Global()
@Module({
  controllers: [AdminTreasuryController, AdminCustomerAccountController, AdminWalletController, StoreBankAccountsController, StoreWalletController],
  providers: [TreasuryService, CustomerAccountsService, WalletService],
  exports: [TreasuryService, CustomerAccountsService],
})
export class TreasuryModule {}
