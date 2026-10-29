import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsDateString, IsEmail, IsIn, IsNotEmpty, IsOptional, IsString, Matches, MaxLength, MinLength } from 'class-validator';
import { GOVERNORATE_NAMES } from '../../common/yemen';

/**
 * Mobile numbers of Yemen (7xxxxxxxx), Saudi Arabia (05xxxxxxxx) and Egypt (01xxxxxxxxx), local
 * or international, and any other country in international form (+971501234567 or 00971…).
 */
export const PHONE_REGEX = /^(?:(?:\+967|00967)?7[01378]\d{7}|(?:\+966|00966|0)5\d{8}|(?:\+20|0020|0)1[0125]\d{8}|(?:\+|00)(?!967|966|20)[1-9]\d{6,14})$/;

export class StaffLoginDto {
  @ApiPropertyOptional({ example: '777123456', description: 'Mobile number (or username) the employee signs in with' })
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(120)
  phone?: string;

  @ApiPropertyOptional({ description: 'Older apps: email, username or mobile number' })
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(120)
  email?: string;

  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  password: string;
}

export class CustomerRegisterDto {
  @ApiProperty({ example: 'أحمد محمد' })
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name: string;

  @ApiProperty({ example: '01012345678' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid mobile number (international form +country code for other countries)' })
  phone: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsEmail()
  email?: string;

  @ApiProperty({ minLength: 8 })
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  password: string;

  @ApiPropertyOptional({ enum: GOVERNORATE_NAMES, description: 'Decides the rial the customer uses (old or new notes)' })
  @IsOptional()
  @IsIn(GOVERNORATE_NAMES)
  governorate?: string;

  @ApiPropertyOptional({ example: 'الحوبان', description: 'Taiz only: الحوبان or المدينة' })
  @IsOptional()
  @IsIn(['الحوبان', 'المدينة'])
  area?: string;

  @ApiPropertyOptional({ example: 'sara.ali', description: 'Public username: two English letters first, then letters, digits, . or _' })
  @IsOptional()
  @Matches(/^@?[A-Za-z]{2}[A-Za-z0-9._]{1,28}$/, { message: 'اليوزر يبدأ بحرفين إنجليزيين ثم حروف أو أرقام أو . أو _' })
  username?: string;

  @ApiPropertyOptional({ description: 'The username is also the customer\'s Instagram handle (verified with a code sent from Instagram)' })
  @IsOptional()
  @IsBoolean()
  usernameIsInstagram?: boolean;

  @ApiPropertyOptional({ example: '1998-05-14' })
  @IsOptional()
  @IsDateString()
  birthDate?: string;

  @ApiPropertyOptional({ enum: ['SMS', 'WHATSAPP'], default: 'SMS', description: 'How the sign-up code is sent' })
  @IsOptional()
  @IsIn(['SMS', 'WHATSAPP'])
  otpChannel?: 'SMS' | 'WHATSAPP';

  @ApiPropertyOptional({ example: '123456', description: 'Code texted by POST auth/customer/otp, when sign-up requires it' })
  @IsOptional()
  @Matches(/^\d{6}$/, { message: 'otp must be 6 digits' })
  otp?: string;
}

export class CustomerLoginDto {
  @ApiProperty({ example: '01012345678' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid mobile number (international form +country code for other countries)' })
  phone: string;

  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  password: string;
}

export class CustomerVerifyDto {
  @ApiProperty({ example: '777123456' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid mobile number (international form +country code for other countries)' })
  phone: string;

  @ApiProperty({ example: '123456' })
  @Matches(/^\d{6}$/, { message: 'code must be 6 digits' })
  code: string;
}

export class RefreshTokenDto {
  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  refreshToken: string;
}

export class ChangePasswordDto {
  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  currentPassword: string;

  @ApiProperty({ minLength: 8 })
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  newPassword: string;
}

/** Forgotten password: the code texted by POST auth/customer/otp/reset and the new password. */
export class PasswordResetDto {
  @ApiProperty({ example: '777123456' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid mobile number (international form +country code for other countries)' })
  phone: string;

  @ApiProperty({ example: '123456' })
  @Matches(/^\d{6}$/, { message: 'code must be 6 digits' })
  code: string;

  @ApiProperty({ minLength: 8 })
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  newPassword: string;
}

/**
 * Stores a number only one way: Yemen 7xxxxxxxx, Saudi 05xxxxxxxx, Egypt 01xxxxxxxxx, and any
 * other country in full international form (+971501234567).
 */
export const normalizePhone = (phone: string) =>
  phone
    .replace(/[\s-]/g, '')
    .replace(/^00/, '+')
    .replace(/^\+20/, '0')
    .replace(/^\+966/, '0')
    .replace(/^\+967/, '');
