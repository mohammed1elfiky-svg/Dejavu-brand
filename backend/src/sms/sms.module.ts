import { Global, Module } from '@nestjs/common';
import { AdminSmsController, OtpController } from './sms.controller';
import { SmsService } from './sms.service';

@Global()
@Module({
  controllers: [AdminSmsController, OtpController],
  providers: [SmsService],
  exports: [SmsService],
})
export class SmsModule {}
