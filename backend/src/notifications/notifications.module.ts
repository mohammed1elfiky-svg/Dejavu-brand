import { OwnerAlertsService } from './owner-alerts.service';
import { Global, Module } from '@nestjs/common';
import { AdminDevicesController, OwnerAlertsController, StaffAlertsController, StoreDevicesController, StoreNotificationsController } from './notifications.controller';
import { NotificationsService } from './notifications.service';

@Global()
@Module({
  controllers: [StoreNotificationsController, StoreDevicesController, AdminDevicesController, StaffAlertsController, OwnerAlertsController],
  providers: [NotificationsService, OwnerAlertsService],
  exports: [NotificationsService, OwnerAlertsService],
})
export class NotificationsModule {}
