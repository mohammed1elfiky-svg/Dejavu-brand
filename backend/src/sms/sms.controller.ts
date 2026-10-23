import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsEnum, IsInt, IsOptional, IsString, Matches, Max, MaxLength, Min } from 'class-validator';
import { Type } from 'class-transformer';
import { SmsStatus } from '../generated/prisma/client';
import { Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { PHONE_REGEX } from '../auth/dto/auth.dto';
import { SmsService } from './sms.service';

class ClaimQueryDto {
  @ApiPropertyOptional({ default: 10 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(50)
  limit?: number;
}

class SmsResultDto {
  @ApiProperty()
  @IsBoolean()
  ok: boolean;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(300)
  error?: string;
}

class SmsSettingsDto {
  @ApiPropertyOptional({ description: 'New customers must confirm their number with a texted code' })
  @IsOptional()
  @IsBoolean()
  requirePhoneOtp?: boolean;
}

class SmsListQueryDto {
  @ApiPropertyOptional({ enum: SmsStatus })
  @IsOptional()
  @IsEnum(SmsStatus)
  status?: SmsStatus;
}

export class OtpRequestDto {
  @ApiProperty({ example: '777123456' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Yemeni, Saudi or Egyptian mobile number' })
  phone: string;
}

/** The store phone that sends texts (admin app with the gateway switched on). */
@ApiTags('Admin · SMS')
@Controller('admin/sms')
export class AdminSmsController {
  constructor(private readonly sms: SmsService) {}

  @RequirePermissions('settings.manage')
  @Post('claim')
  @HttpCode(HttpStatus.OK)
  claim(@Query() q: ClaimQueryDto) {
    return this.sms.claim(q.limit ?? 10);
  }

  @RequirePermissions('settings.manage')
  @Post(':id/result')
  @HttpCode(HttpStatus.OK)
  result(@Param('id', ParseUUIDPipe) id: string, @Body() dto: SmsResultDto) {
    return this.sms.report(id, dto.ok, dto.error);
  }

  @RequirePermissions('settings.manage')
  @Get()
  list(@Query() q: SmsListQueryDto) {
    return this.sms.recent(q.status);
  }

  @RequirePermissions('settings.manage')
  @Get('settings')
  settings() {
    return this.sms.settings();
  }

  @RequirePermissions('settings.manage')
  @Put('settings')
  updateSettings(@Body() dto: SmsSettingsDto) {
    return this.sms.updateSettings(dto);
  }
}

@ApiTags('Auth')
@Public()
@Controller('auth/customer/otp')
export class OtpController {
  constructor(private readonly sms: SmsService) {}

  /** Whether sign-up needs a texted code. */
  @Get()
  async required() {
    return { required: await this.sms.otpRequired() };
  }

  /** Texts a code to the number about to sign up. */
  @Post()
  @HttpCode(HttpStatus.OK)
  send(@Body() dto: OtpRequestDto) {
    return this.sms.sendOtp(dto.phone);
  }

  /** Forgotten password: texts a reset code to a registered number. */
  @Post('reset')
  @HttpCode(HttpStatus.OK)
  sendReset(@Body() dto: OtpRequestDto) {
    return this.sms.sendOtp(dto.phone, 'RESET');
  }
}
