import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsEmail, IsNotEmpty, IsOptional, IsString, Matches, MaxLength, MinLength } from 'class-validator';

/** Egyptian mobile numbers in local (01xxxxxxxxx) or international (+201xxxxxxxxx) form. */
/** Mobile numbers of Yemen (7xxxxxxxx), Saudi Arabia (05xxxxxxxx) and Egypt (01xxxxxxxxx). */
export const PHONE_REGEX = /^(?:(?:\+967|00967)?7[01378]\d{7}|(?:\+966|0)5\d{8}|(?:\+20|0)1[0125]\d{8})$/;

export class StaffLoginDto {
  @ApiProperty({ example: 'admin@dejavu.local' })
  @IsEmail()
  email: string;

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
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Yemeni, Saudi or Egyptian mobile number' })
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
}

export class CustomerLoginDto {
  @ApiProperty({ example: '01012345678' })
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Yemeni, Saudi or Egyptian mobile number' })
  phone: string;

  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  password: string;
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

/** Normalises to the local 01xxxxxxxxx form so a number is stored only one way. */
export const normalizePhone = (phone: string) =>
  phone.replace(/^\+20/, '0').replace(/^\+966/, '0').replace(/^(\+967|00967)/, '');
