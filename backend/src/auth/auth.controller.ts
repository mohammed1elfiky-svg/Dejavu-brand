import { Body, Controller, Get, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { Throttle } from '@nestjs/throttler';
import { AuthPrincipal, CurrentUser, Public } from '../common/decorators/auth.decorators';
import { GOVERNORATES } from '../common/yemen';
import { AuthService } from './auth.service';
import { ChangePasswordDto, CustomerLoginDto, CustomerRegisterDto, RefreshTokenDto, StaffLoginDto } from './dto/auth.dto';

const authLimit = () => Throttle({ default: { limit: Number(process.env.AUTH_RATE_LIMIT ?? 10), ttl: 60_000 } });

@ApiTags('Auth')
@Controller('auth')
export class AuthController {
  constructor(private readonly auth: AuthService) {}

  /** Governorates for the sign-up form (Taiz has areas), with the rial each uses. */
  @Public()
  @Get('governorates')
  governorates() {
    return GOVERNORATES;
  }

  @Public()
  @authLimit()
  @Post('staff/login')
  @HttpCode(HttpStatus.OK)
  staffLogin(@Body() dto: StaffLoginDto) {
    return this.auth.staffLogin(dto);
  }

  @Public()
  @authLimit()
  @Post('customer/register')
  customerRegister(@Body() dto: CustomerRegisterDto) {
    return this.auth.customerRegister(dto);
  }

  @Public()
  @authLimit()
  @Post('customer/login')
  @HttpCode(HttpStatus.OK)
  customerLogin(@Body() dto: CustomerLoginDto) {
    return this.auth.customerLogin(dto);
  }

  @Public()
  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  refresh(@Body() dto: RefreshTokenDto) {
    return this.auth.refresh(dto.refreshToken);
  }

  @Public()
  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  logout(@Body() dto: RefreshTokenDto) {
    return this.auth.logout(dto.refreshToken);
  }

  @ApiBearerAuth()
  @Get('me')
  me(@CurrentUser() user: AuthPrincipal) {
    return user.type === 'staff' ? this.auth.staffProfile(user.id) : this.auth.customerProfile(user.id);
  }

  @ApiBearerAuth()
  @Post('change-password')
  @HttpCode(HttpStatus.NO_CONTENT)
  changePassword(@CurrentUser() user: AuthPrincipal, @Body() dto: ChangePasswordDto) {
    return this.auth.changePassword(user, dto);
  }
}
