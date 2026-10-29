import { BadRequestException, Body, ConflictException, Controller, Get, Injectable, NotFoundException, Patch, Query } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsDateString, IsOptional, IsString, Matches, MaxLength, MinLength, ValidateIf } from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public } from '../common/decorators/auth.decorators';
import { instagramCode, normalizeUsername, USERNAME_REGEX } from '../common/username';
import { CustomerStatus, InstagramStatus } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

export class UpdateProfileDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MinLength(2) @MaxLength(100) name?: string;

  @ApiPropertyOptional({ example: 'sara.ali', description: '"" removes the username' })
  @IsOptional()
  @ValidateIf((_, v) => v !== '')
  @Matches(/^@?[A-Za-z]{2}[A-Za-z0-9._]{1,28}$/, { message: 'اليوزر يبدأ بحرفين إنجليزيين ثم حروف أو أرقام أو . أو _' })
  username?: string;

  @ApiPropertyOptional({ description: 'The username is also your Instagram handle' }) @IsOptional() @IsBoolean() usernameIsInstagram?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(160) bio?: string;
  @ApiPropertyOptional({ description: 'Public image URL (POST store/me/images); "" removes it' }) @IsOptional() @IsString() @MaxLength(500) avatarUrl?: string;
  @ApiPropertyOptional({ example: '1998-05-14' }) @IsOptional() @IsDateString() birthDate?: string;
}

/** Public card of a customer (what other customers may see). */
export const publicSelect = {
  id: true,
  username: true,
  name: true,
  avatarUrl: true,
  bio: true,
  igStatus: true,
} as const;

export const firstName = (name: string) => name.trim().split(/\s+/)[0] ?? name;

/** What others see: first name only, and the Instagram badge once staff verified it. */
export function publicCard(c: { id: string; username: string | null; name: string; avatarUrl: string | null; bio?: string | null; igStatus: InstagramStatus }) {
  return { id: c.id, username: c.username, name: firstName(c.name), avatarUrl: c.avatarUrl, bio: c.bio ?? null, instagramVerified: c.igStatus === InstagramStatus.VERIFIED };
}

/** The customer's own profile: name, username (and its Instagram check), photo, bio, birthday. */
@Injectable()
export class ProfileService {
  constructor(private readonly prisma: PrismaService) {}

  async mine(customerId: string) {
    const c = await this.prisma.customer.findUniqueOrThrow({
      where: { id: customerId },
      select: { id: true, name: true, phone: true, username: true, avatarUrl: true, bio: true, birthDate: true, igStatus: true, igCode: true, loyaltyPoints: true },
    });
    const about = await this.prisma.setting.findUnique({ where: { key: 'store.about' } });
    const storeInstagram = ((about?.value ?? {}) as { instagram?: string }).instagram || null;
    const [followers, following, posts] = await Promise.all([
      this.prisma.follow.count({ where: { followingId: customerId } }),
      this.prisma.follow.count({ where: { followerId: customerId } }),
      this.prisma.post.count({ where: { authorId: customerId, isHidden: false } }),
    ]);
    return {
      ...c,
      instagramVerified: c.igStatus === InstagramStatus.VERIFIED,
      // The code to send from Instagram while the check is pending.
      igCode: c.igStatus === InstagramStatus.PENDING ? c.igCode : null,
      storeInstagram,
      counts: { followers, following, posts },
    };
  }

  async update(customerId: string, dto: UpdateProfileDto) {
    const current = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { username: true, igStatus: true } });
    const data: Record<string, unknown> = {};
    if (dto.name !== undefined) data.name = dto.name.trim();
    if (dto.bio !== undefined) data.bio = dto.bio.trim() || null;
    if (dto.avatarUrl !== undefined) data.avatarUrl = dto.avatarUrl.trim() || null;
    if (dto.birthDate !== undefined) data.birthDate = new Date(dto.birthDate);
    if (dto.username !== undefined) {
      const username = dto.username ? normalizeUsername(dto.username) : null;
      if (username && !USERNAME_REGEX.test(username)) throw new BadRequestException('اليوزر يبدأ بحرفين إنجليزيين ثم حروف أو أرقام أو . أو _');
      if (username !== current.username) {
        if (username && (await this.prisma.customer.findFirst({ where: { username, id: { not: customerId } }, select: { id: true } }))) {
          throw new ConflictException({ statusCode: 409, code: 'USERNAME_TAKEN', message: 'اليوزر مستخدم، اختر يوزراً آخر' });
        }
        data.username = username;
        // A new username needs its own Instagram check.
        data.igStatus = InstagramStatus.NONE;
        data.igCode = null;
        data.igVerifiedAt = null;
      }
    }
    const username = (data.username as string | null | undefined) ?? (dto.username === undefined ? current.username : null);
    if (dto.usernameIsInstagram !== undefined) {
      if (dto.usernameIsInstagram) {
        if (!username) throw new BadRequestException('اختر اليوزر أولاً');
        const stays = data.username === undefined && current.igStatus !== InstagramStatus.NONE;
        if (!stays) {
          data.igStatus = InstagramStatus.PENDING;
          data.igCode = instagramCode();
        }
      } else {
        data.igStatus = InstagramStatus.NONE;
        data.igCode = null;
        data.igVerifiedAt = null;
      }
    }
    await this.prisma.customer.update({ where: { id: customerId }, data });
    return this.mine(customerId);
  }

  async usernameAvailable(raw: string, customerId?: string) {
    const username = normalizeUsername(raw ?? '');
    if (!USERNAME_REGEX.test(username)) return { username, available: false, reason: 'اليوزر يبدأ بحرفين إنجليزيين ثم حروف أو أرقام أو . أو _' };
    const taken = await this.prisma.customer.findFirst({ where: { username, id: customerId ? { not: customerId } : undefined }, select: { id: true } });
    return { username, available: !taken, reason: taken ? 'اليوزر مستخدم' : null };
  }

  /** Customers by username (for mentions, follows and gifts). */
  async search(q: string) {
    const term = normalizeUsername(q ?? '');
    if (term.length < 2) return [];
    const rows = await this.prisma.customer.findMany({
      where: { status: CustomerStatus.ACTIVE, username: { startsWith: term } },
      select: publicSelect,
      orderBy: { username: 'asc' },
      take: 20,
    });
    return rows.map(publicCard);
  }

  async byUsername(raw: string) {
    const c = await this.prisma.customer.findFirst({ where: { username: normalizeUsername(raw), status: CustomerStatus.ACTIVE }, select: publicSelect });
    if (!c) throw new NotFoundException('لا يوجد حساب بهذا اليوزر');
    return c;
  }
}

@ApiTags('Store · Profile')
@CustomerOnly()
@Controller('store/me/profile')
export class StoreProfileController {
  constructor(private readonly profiles: ProfileService) {}

  @Get()
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.profiles.mine(u.id);
  }

  /** Name, username (and whether it is your Instagram), bio, photo, birthday. */
  @Patch()
  update(@CurrentUser() u: AuthPrincipal, @Body() dto: UpdateProfileDto) {
    return this.profiles.update(u.id, dto);
  }

  @Get('username-check')
  check(@CurrentUser() u: AuthPrincipal, @Query('u') q: string) {
    return this.profiles.usernameAvailable(q, u.id);
  }
}

@ApiTags('Store · Profile')
@Public()
@Controller('store/usernames')
export class StoreUsernamesController {
  constructor(private readonly profiles: ProfileService) {}

  /** Before signing up: is this username free? */
  @Get('check')
  check(@Query('u') q: string) {
    return this.profiles.usernameAvailable(q);
  }

  @Get('search')
  search(@Query('q') q: string) {
    return this.profiles.search(q);
  }
}

