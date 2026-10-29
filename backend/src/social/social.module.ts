import { Module } from '@nestjs/common';
import { SalesModule } from '../sales/sales.module';
import { ProfileService, StoreFindController, StoreProfileController, StoreUsernamesController } from './profile';
import { AdminSocialController, StoreSocialController, StoreSocialPublicController } from './social.controller';
import { SocialService } from './social.service';
import { AdminGiftsController, StoreGiftsController } from './gifts.controller';
import { GiftsService } from './gifts.service';

/** Usernames and profiles, posts, follows, likes, comments, birthdays, ambassador rewards and gifts. */
@Module({
  imports: [SalesModule],
  controllers: [StoreProfileController, StoreFindController, StoreUsernamesController, StoreSocialPublicController, StoreSocialController, AdminSocialController, AdminGiftsController, StoreGiftsController],
  providers: [ProfileService, SocialService, GiftsService],
  exports: [ProfileService, SocialService, GiftsService],
})
export class SocialModule {}
