import { Global, Module } from '@nestjs/common';
import { AccountingController } from './accounting.controller';
import { AccountingService } from './accounting.service';
import { LedgerService } from './ledger.service';

@Global()
@Module({
  controllers: [AccountingController],
  providers: [AccountingService, LedgerService],
  exports: [LedgerService, AccountingService],
})
export class AccountingModule {}
