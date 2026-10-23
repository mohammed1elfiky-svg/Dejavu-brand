import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform } from 'class-transformer';
import {
  ArrayUnique,
  IsArray,
  IsEmail,
  IsEnum,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  MaxLength,
  MinLength,
  ValidateIf,
} from 'class-validator';
import { UserStatus } from '../../generated/prisma/client';
import { PHONE_REGEX } from '../../auth/dto/auth.dto';
import { IsCurrencyCode } from '../../common/decorators/validation.decorators';

export class CreateUserDto {
  @ApiProperty()
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name: string;

  @ApiPropertyOptional({ description: 'Optional; employees sign in with their mobile number' })
  @IsOptional()
  @Transform(({ value }) => (value === '' ? undefined : value))
  @IsEmail()
  email?: string;

  @ApiPropertyOptional({ example: '777123456', description: 'Mobile number the employee signs in with' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.replace(/[\s-]/g, '') : value))
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Yemeni, Saudi or Egyptian mobile number' })
  phone?: string;

  @ApiPropertyOptional({ example: 'ahmed', description: 'Short login name (lowercase letters, digits, . _ -)' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toLowerCase() || null : value))
  @ValidateIf((_, v) => v !== null)
  @Matches(/^[a-z0-9][a-z0-9._-]{2,39}$/, { message: 'username must be 3-40 lowercase letters, digits, dots, dashes or underscores' })
  username?: string | null;

  @ApiProperty({ minLength: 8 })
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  password: string;

  @ApiProperty({ type: [String] })
  @IsArray()
  @ArrayUnique()
  @IsUUID('all', { each: true })
  roleIds: string[];

  @ApiPropertyOptional({ type: [String], description: 'Extra permissions for this employee only' })
  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  permissions?: string[];

  @ApiPropertyOptional({ type: [String], description: 'Boxes this employee may use' })
  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsUUID('all', { each: true })
  cashBoxIds?: string[];

  @ApiPropertyOptional({ example: 'YER', description: 'Currency the till opens with; defaults to YER for cashiers, SAR otherwise' })
  @IsOptional()
  @IsCurrencyCode()
  saleCurrency?: string;
}

export class UpdateUserDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MinLength(2)
  @MaxLength(100)
  name?: string;

  @ApiPropertyOptional({ example: '777123456' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.replace(/[\s-]/g, '') : value))
  @Matches(PHONE_REGEX, { message: 'phone must be a valid Yemeni, Saudi or Egyptian mobile number' })
  phone?: string;

  @ApiPropertyOptional({ example: 'ahmed', description: 'Short login name (lowercase letters, digits, . _ -)' })
  @IsOptional()
  @Transform(({ value }) => (typeof value === 'string' ? value.trim().toLowerCase() || null : value))
  @ValidateIf((_, v) => v !== null)
  @Matches(/^[a-z0-9][a-z0-9._-]{2,39}$/, { message: 'username must be 3-40 lowercase letters, digits, dots, dashes or underscores' })
  username?: string | null;

  @ApiPropertyOptional({ enum: UserStatus })
  @IsOptional()
  @IsEnum(UserStatus)
  status?: UserStatus;

  @ApiPropertyOptional({ minLength: 8, description: 'Resets the password and signs the user out everywhere' })
  @IsOptional()
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  password?: string;

  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsUUID('all', { each: true })
  roleIds?: string[];

  @ApiPropertyOptional({ example: 'YER', description: 'Currency the till opens with; defaults to YER for cashiers, SAR otherwise' })
  @IsOptional()
  @IsCurrencyCode()
  saleCurrency?: string;
}

export class UserPermissionsDto {
  @ApiProperty({ type: [String], example: ['pos.sell', 'stock.read'], description: "This employee's own permissions (on top of their roles)" })
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  permissions: string[];
}

export class UserCashBoxesDto {
  @ApiProperty({ type: [String], description: 'Boxes this employee may use; empty = only their own drawers' })
  @IsArray()
  @ArrayUnique()
  @IsUUID('all', { each: true })
  cashBoxIds: string[];
}

export class CreateRoleDto {
  @ApiProperty({ example: 'cashier' })
  @Matches(/^[a-z][a-z0-9_]{1,49}$/, { message: 'name must be lowercase letters, digits or underscores' })
  name: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  description?: string;

  @ApiProperty({ type: [String], example: ['orders.read'] })
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  permissions: string[];
}

export class UpdateRoleDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(255)
  description?: string;

  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsArray()
  @ArrayUnique()
  @IsString({ each: true })
  permissions?: string[];
}
