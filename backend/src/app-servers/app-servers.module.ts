import { Module } from '@nestjs/common';
import { AdminServersController, StoreServersController } from './app-servers.controller';
import { AppServersService } from './app-servers.service';

@Module({
  controllers: [StoreServersController, AdminServersController],
  providers: [AppServersService],
})
export class AppServersModule {}
