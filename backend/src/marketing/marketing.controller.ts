import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayMinSize,
  IsArray,
  IsBoolean,
  IsIn,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  MaxLength,
  Min,
  MinLength,
  ValidateNested,
} from 'class-validator';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { CampaignsService } from './campaigns.service';

export class SegmentDto {
  @ApiPropertyOptional() @IsOptional() @IsBoolean() merchantsOnly?: boolean;
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @IsString({ each: true }) governorates?: string[];
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @IsUUID('all', { each: true }) boughtCategoryIds?: string[];
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(1) boughtWithinDays?: number;
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(1) notBoughtForDays?: number;
  @ApiPropertyOptional() @IsOptional() @IsNumber() @Min(0) minSpent?: number;
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(1) joinedWithinDays?: number;
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @IsUUID('all', { each: true }) wishlistProductIds?: string[];
  @ApiPropertyOptional() @IsOptional() @IsBoolean() hasCart?: boolean;
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @ArrayMaxSize(5000) @IsUUID('all', { each: true }) customerIds?: string[];
}

export class TargetDto {
  @ApiProperty({ enum: ['none', 'product', 'category', 'url'] })
  @IsIn(['none', 'product', 'category', 'url'])
  type: 'none' | 'product' | 'category' | 'url';

  @ApiPropertyOptional({ description: 'Product slug, category slug or the link' })
  @IsOptional()
  @IsString()
  @MaxLength(500)
  value?: string;
}

export class PreviewDto {
  @ApiProperty({ type: SegmentDto }) @ValidateNested() @Type(() => SegmentDto) segment: SegmentDto;
}

export class CampaignDto {
  @ApiProperty() @IsString() @MinLength(2) @MaxLength(100) title: string;
  @ApiProperty() @IsString() @MinLength(2) @MaxLength(500) body: string;
  @ApiPropertyOptional({ description: 'Picture shown big in the notification (https)' }) @IsOptional() @IsString() @MaxLength(500) imageUrl?: string;
  @ApiPropertyOptional({ type: TargetDto }) @IsOptional() @ValidateNested() @Type(() => TargetDto) target?: TargetDto;
  @ApiProperty({ type: SegmentDto }) @ValidateNested() @Type(() => SegmentDto) segment: SegmentDto;

  @ApiProperty({ enum: ['PUSH', 'SMS'], isArray: true })
  @IsArray()
  @ArrayMinSize(1)
  @IsIn(['PUSH', 'SMS'], { each: true })
  channels: ('PUSH' | 'SMS')[];
}

@ApiTags('Admin · Marketing')
@RequirePermissions('notifications.send')
@Controller('admin/campaigns')
export class AdminCampaignsController {
  constructor(private readonly campaigns: CampaignsService) {}

  @Get()
  list(@Query('take') take?: string) {
    return this.campaigns.list(take ? Number(take) : 50);
  }

  /** How many customers these filters reach. */
  @Post('preview')
  @HttpCode(HttpStatus.OK)
  preview(@Body() dto: PreviewDto) {
    return this.campaigns.preview(dto.segment);
  }

  @Post()
  send(@Body() dto: CampaignDto, @CurrentUser() u: AuthPrincipal) {
    return this.campaigns.send(dto, u.id);
  }
}
