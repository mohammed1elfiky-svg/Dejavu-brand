import { Global, Module } from '@nestjs/common';
import { AuditService } from './audit.service';
import { SequenceService } from './sequence.service';

@Global()
@Module({
  providers: [SequenceService, AuditService],
  exports: [SequenceService, AuditService],
})
export class CommonModule {}
