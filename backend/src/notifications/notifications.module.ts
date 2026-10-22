import { Global, Module } from '@nestjs/common';
import { AdminDevicesController, StaffAlertsController, StoreDevicesController, StoreNotificationsController } from './notifications.controller';
import { NotificationsService } from './notifications.service';

@Global()
@Module({
  controllers: [StoreNotificationsController, StoreDevicesController, AdminDevicesController, StaffAlertsController],
  providers: [NotificationsService],
  exports: [NotificationsService],
})
export class NotificationsModule {}
