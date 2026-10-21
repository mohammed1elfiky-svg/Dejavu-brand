import { Global, Module } from '@nestjs/common';
import { AuditService } from './audit.service';
import { CurrencyService } from './currency.service';
import { SequenceService } from './sequence.service';

@Global()
@Module({
  providers: [SequenceService, AuditService, CurrencyService],
  exports: [SequenceService, AuditService, CurrencyService],
})
export class CommonModule {}
