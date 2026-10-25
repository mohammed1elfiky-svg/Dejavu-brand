import { Module } from '@nestjs/common';
import { CampaignsService } from './campaigns.service';
import { AdminCampaignsController } from './marketing.controller';

/** Messages to groups of customers. */
@Module({
  controllers: [AdminCampaignsController],
  providers: [CampaignsService],
})
export class MarketingModule {}
