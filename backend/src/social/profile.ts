import { BadRequestException, Body, ConflictException, Controller, Get, HttpException, Injectable, NotFoundException, Patch, Query } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsBoolean, IsDateString, IsIn, IsOptional, IsString, Matches, MaxLength, MinLength, ValidateIf } from 'class-validator';
import { normalizePhone } from '../auth/dto/auth.dto';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public } from '../common/decorators/auth.decorators';
import { instagramCode, looksLikePhone, normalizeUsername, USERNAME_MESSAGE, USERNAME_REGEX } from '../common/username';
import { CustomerStatus, InstagramStatus } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

export class UpdateProfileDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MinLength(2) @MaxLength(100) name?: string;

  @ApiPropertyOptional({ example: 'sara.ali', description: '"" removes the username' })
  @IsOptional()
  @ValidateIf((_, v) => v !== '')
  @Matches(/^@?(?!.*\.\.)(?!.*\.$)[A-Za-z0-9._]{1,30}$/, { message: USERNAME_MESSAGE })
  username?: string;

  /** Older apps sent this; every username is an Instagram handle now. */
  @ApiPropertyOptional({ deprecated: true }) @IsOptional() @IsBoolean() usernameIsInstagram?: boolean;
  @ApiPropertyOptional({ enum: ['MALE', 'FEMALE'] }) @IsOptional() @IsIn(['MALE', 'FEMALE']) gender?: 'MALE' | 'FEMALE';
  @ApiPropertyOptional({ description: 'Let others find me by my mobile number' }) @IsOptional() @IsBoolean() findableByPhone?: boolean;
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
      select: { id: true, name: true, phone: true, username: true, pendingUsername: true, gender: true, findableByPhone: true, avatarUrl: true, bio: true, birthDate: true, igStatus: true, igCode: true, loyaltyPoints: true },
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
    const current = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { username: true, pendingUsername: true } });
    const data: Record<string, unknown> = {};
    if (dto.name !== undefined) data.name = dto.name.trim();
    if (dto.bio !== undefined) data.bio = dto.bio.trim() || null;
    if (dto.avatarUrl !== undefined) data.avatarUrl = dto.avatarUrl.trim() || null;
    if (dto.birthDate !== undefined) data.birthDate = new Date(dto.birthDate);
    if (dto.gender !== undefined) data.gender = dto.gender;
    if (dto.findableByPhone !== undefined) data.findableByPhone = dto.findableByPhone;
    if (dto.username !== undefined) {
      const wanted = dto.username ? normalizeUsername(dto.username) : null;
      if (wanted && !USERNAME_REGEX.test(wanted)) throw new BadRequestException(USERNAME_MESSAGE);
      if (wanted === null) {
        // Removed: no username and no request.
        Object.assign(data, { username: null, pendingUsername: null, igStatus: InstagramStatus.NONE, igCode: null, igVerifiedAt: null });
      } else if (wanted !== current.username && wanted !== current.pendingUsername) {
        if (await this.prisma.customer.findFirst({ where: { username: wanted, id: { not: customerId } }, select: { id: true } })) {
          throw new ConflictException({ statusCode: 409, code: 'USERNAME_TAKEN', message: 'اليوزر مستخدم، اختر يوزراً آخر' });
        }
        // The old one stops showing now; the new one shows once the store checks the Instagram code.
        Object.assign(data, { username: null, pendingUsername: wanted, igStatus: InstagramStatus.PENDING, igCode: instagramCode(), igVerifiedAt: null });
      }
    }
    await this.prisma.customer.update({ where: { id: customerId }, data });
    return this.mine(customerId);
  }

  async usernameAvailable(raw: string, customerId?: string) {
    const username = normalizeUsername(raw ?? '');
    if (!USERNAME_REGEX.test(username)) return { username, available: false, reason: USERNAME_MESSAGE };
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

  private readonly finds = new Map<string, number[]>();

  /**
   * Finds a customer by username (verified ones, by prefix) or by their full mobile number
   * (only if they allow it). A few tries a minute, so numbers cannot be guessed in bulk.
   */
  async find(viewerId: string, q: string) {
    const now = Date.now();
    const hits = (this.finds.get(viewerId) ?? []).filter((t) => now - t < 60_000);
    if (hits.length >= 20) throw new HttpException('محاولات بحث كثيرة، حاول بعد دقيقة', 429);
    hits.push(now);
    if (this.finds.size > 5000) this.finds.clear();
    this.finds.set(viewerId, hits);

    const raw = (q ?? '').trim();
    if (raw.replace(/^@/, '').length < 2) return [];
    const card = (c: { id: string; username: string | null; name: string; avatarUrl: string | null; igStatus: InstagramStatus }, viaPhone: boolean) => ({
      id: c.id,
      username: c.username,
      name: firstName(c.name),
      avatarUrl: c.avatarUrl,
      instagramVerified: c.igStatus === InstagramStatus.VERIFIED,
      viaPhone,
    });
    if (looksLikePhone(raw)) {
      const phone = normalizePhone(raw.replace(/[\s-]/g, ''));
      const c = await this.prisma.customer.findFirst({
        where: { phone, status: CustomerStatus.ACTIVE, findableByPhone: true, id: { not: viewerId }, code: { notIn: ['CUS-WALKIN', 'CUS-GIFTPOOL'] } },
        select: publicSelect,
      });
      return c ? [card(c, true)] : [];
    }
    const term = normalizeUsername(raw);
    const rows = await this.prisma.customer.findMany({
      where: { status: CustomerStatus.ACTIVE, username: { startsWith: term }, id: { not: viewerId } },
      select: publicSelect,
      orderBy: { username: 'asc' },
      take: 20,
    });
    return rows.map((c) => card(c, false));
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
@CustomerOnly()
@Controller('store/me/find')
export class StoreFindController {
  constructor(private readonly profiles: ProfileService) {}

  /** Search friends by username or by their full mobile number. */
  @Get()
  find(@CurrentUser() u: AuthPrincipal, @Query('q') q: string) {
    return this.profiles.find(u.id, q);
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

