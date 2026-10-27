import { Body, Controller, Get, Header, HttpCode, HttpException, HttpStatus, Post } from '@nestjs/common';
import { ApiProperty, ApiTags } from '@nestjs/swagger';
import { SkipThrottle, Throttle } from '@nestjs/throttler';
import { IsNotEmpty, IsOptional, IsString, MaxLength } from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public } from '../common/decorators/auth.decorators';
import { PrismaService } from '../prisma/prisma.service';
import { AccountDeletionService } from './account-deletion.service';
import { deletePage, privacyPage } from './pages';

export class DeleteOwnAccountDto {
  @ApiProperty()
  @IsString()
  @IsNotEmpty()
  password: string;
}

class DeleteAccountFormDto {
  @IsOptional() @IsString() @MaxLength(30) phone?: string;
  @IsOptional() @IsString() @MaxLength(200) password?: string;
  @IsOptional() @IsString() @MaxLength(5) confirm?: string;
}

const limit = () => Throttle({ default: { limit: Number(process.env.AUTH_RATE_LIMIT ?? 10), ttl: 60_000 } });

@ApiTags('Store · Account')
@CustomerOnly()
@Controller('store/me')
export class StoreAccountController {
  constructor(private readonly deletion: AccountDeletionService) {}

  /** Deletes the customer's own account (password asked again). */
  @limit()
  @Post('delete-account')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteOwn(@CurrentUser() u: AuthPrincipal, @Body() dto: DeleteOwnAccountDto) {
    return this.deletion.deleteOwn(u.id, dto.password);
  }
}

/** Public web pages for the store listing: privacy policy and account deletion. */
@ApiTags('Public pages')
@Public()
@Controller()
export class PublicPagesController {
  constructor(
    private readonly deletion: AccountDeletionService,
    private readonly prisma: PrismaService,
  ) {}

  private async storeName() {
    const row = await this.prisma.setting.findUnique({ where: { key: 'store.about' } });
    const v = (row?.value ?? {}) as { name?: string; phones?: string[]; whatsapp?: string };
    return { name: v.name?.trim() || 'Dejavu', contact: v.whatsapp?.trim() || v.phones?.[0] || '' };
  }

  @SkipThrottle()
  @Get('privacy')
  @Header('Content-Type', 'text/html; charset=utf-8')
  async privacy() {
    return privacyPage(await this.storeName());
  }

  @SkipThrottle()
  @Get('delete-account')
  @Header('Content-Type', 'text/html; charset=utf-8')
  async deleteForm() {
    return deletePage(await this.storeName());
  }

  @limit()
  @Post('delete-account')
  @HttpCode(HttpStatus.OK)
  @Header('Content-Type', 'text/html; charset=utf-8')
  async deleteSubmit(@Body() dto: DeleteAccountFormDto) {
    const store = await this.storeName();
    if (dto.confirm !== 'yes') return deletePage(store, { error: 'ضع علامة على مربع التأكيد' });
    try {
      await this.deletion.deleteByCredentials(dto.phone ?? '', dto.password ?? '');
      return deletePage(store, { done: true });
    } catch (e) {
      const msg = e instanceof HttpException ? String((e.getResponse() as { message?: string }).message ?? e.message) : 'حدث خطأ، حاول مرة أخرى';
      return deletePage(store, { error: msg });
    }
  }
}
