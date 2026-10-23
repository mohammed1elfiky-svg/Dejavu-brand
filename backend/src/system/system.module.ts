import { Module } from '@nestjs/common';
import { AuditController } from './audit.controller';
import { SystemController } from './system.controller';
import { SystemService } from './system.service';
import { BackupService } from './backup.service';

@Module({
  controllers: [SystemController, AuditController],
  providers: [SystemService, BackupService],
})
export class SystemModule {}
