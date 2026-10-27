import { Module } from '@nestjs/common';
import { AccountDeletionService } from './account-deletion.service';
import { PublicPagesController, StoreAccountController } from './account.controller';

@Module({
  controllers: [StoreAccountController, PublicPagesController],
  providers: [AccountDeletionService],
})
export class AccountModule {}
