import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsInt, IsOptional, IsString, IsUUID, Matches, Max, MaxLength, Min } from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { ChatService } from './chat.service';

export class ChatMessageDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(2000)
  text?: string;

  @ApiPropertyOptional({ description: 'A photo uploaded with POST store/me/files' })
  @IsOptional()
  @IsUUID()
  imageFileId?: string;
}

export class ChatSettingsDto {
  @ApiPropertyOptional() @IsOptional() @IsBoolean() enabled?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(8000) instructions?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(1000) welcome?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(30) whatsapp?: string;
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(1) @Max(500) dailyLimit?: number;
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(0) @Max(100) imageLimit?: number;

  @ApiPropertyOptional({ example: 'gemini-2.5-flash' })
  @IsOptional()
  @Matches(/^[a-z0-9.-]{3,60}$/)
  model?: string;
}

@ApiTags('Store · Assistant')
@CustomerOnly()
@Controller('store/chat')
export class StoreChatController {
  constructor(private readonly chat: ChatService) {}

  @Get()
  history(@CurrentUser() u: AuthPrincipal) {
    return this.chat.history(u.id);
  }

  @Post()
  @HttpCode(HttpStatus.OK)
  send(@Body() dto: ChatMessageDto, @CurrentUser() u: AuthPrincipal) {
    return this.chat.send(u.id, dto);
  }

  @Delete()
  @HttpCode(HttpStatus.NO_CONTENT)
  clear(@CurrentUser() u: AuthPrincipal) {
    return this.chat.clear(u.id);
  }
}

@ApiTags('Admin · Assistant')
@RequirePermissions('chat.manage')
@Controller('admin/chat')
export class AdminChatController {
  constructor(private readonly chat: ChatService) {}

  @Get('settings')
  async settings() {
    return { ...(await this.chat.settings()), configured: !!process.env.GEMINI_API_KEY };
  }

  @Put('settings')
  setSettings(@Body() dto: ChatSettingsDto) {
    return this.chat.setSettings(dto);
  }

  /** Tests the Gemini key and model; says what is wrong when it fails. */
  @Post('test')
  @HttpCode(HttpStatus.OK)
  test() {
    return this.chat.testConnection();
  }

  @Get('conversations')
  conversations(@Query('take') take?: string) {
    return this.chat.conversations(take ? Number(take) : 50);
  }

  @Get('conversations/:customerId')
  conversation(@Param('customerId', ParseUUIDPipe) customerId: string) {
    return this.chat.conversation(customerId);
  }
}
