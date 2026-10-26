import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiTags } from '@nestjs/swagger';
import { IsIn, IsString, IsUUID, MaxLength, MinLength } from 'class-validator';
import { RequirePermissions } from '../common/decorators/auth.decorators';
import { AiService } from './ai.service';

export class ProductDraftDto {
  @ApiProperty({ description: 'The photo, uploaded with POST admin/files (or admin/files/private)' })
  @IsUUID()
  imageFileId: string;
}

export class MarketingTextDto {
  @ApiProperty({ example: 'خصم 20% على الكعب العالي حتى الخميس' }) @IsString() @MinLength(3) @MaxLength(500) idea: string;
  @ApiProperty({ enum: ['PUSH', 'SMS'] }) @IsIn(['PUSH', 'SMS']) channel: 'PUSH' | 'SMS';
}

@ApiTags('Admin · AI')
@Controller('admin/ai')
export class AdminAiController {
  constructor(private readonly ai: AiService) {}

  /** Fill a new product from its photo (name, description, category, colour…). */
  @RequirePermissions('products.write')
  @Post('product-draft')
  @HttpCode(HttpStatus.OK)
  productDraft(@Body() dto: ProductDraftDto) {
    return this.ai.productDraft(dto.imageFileId);
  }

  /** Three wordings for a notification or SMS. */
  @RequirePermissions('notifications.send')
  @Post('marketing-text')
  @HttpCode(HttpStatus.OK)
  marketingText(@Body() dto: MarketingTextDto) {
    return this.ai.marketingText(dto);
  }

  /** The weekly summary (refresh=true writes a new one now). */
  @RequirePermissions('dashboard.view')
  @Get('weekly-summary')
  weekly(@Query('refresh') refresh?: string) {
    return this.ai.weekly(refresh === 'true' || refresh === '1');
  }
}
