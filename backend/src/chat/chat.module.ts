import { Module } from '@nestjs/common';
import { CatalogModule } from '../catalog/catalog.module';
import { AdminChatController, StoreChatController } from './chat.controller';
import { ChatService } from './chat.service';
import { GeminiClient } from './gemini.client';

/** The shopping assistant (Gemini) in the customer app. */
@Module({
  imports: [CatalogModule],
  controllers: [StoreChatController, AdminChatController],
  providers: [ChatService, GeminiClient],
})
export class ChatModule {}
