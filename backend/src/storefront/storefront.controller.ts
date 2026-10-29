import { Body, Controller, Delete, Get, Header, HttpCode, HttpStatus, NotFoundException, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  IsArray,
  IsBoolean,
  IsDateString,
  IsInt,
  IsNumber,
  IsOptional,
  IsString,
  IsUUID,
  Matches,
  Max,
  MaxLength,
  Min,
  ValidateNested,
} from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { formatAmount } from '../common/currency.service';
import { Env } from '../config/env';
import { CatalogService } from '../catalog/catalog.service';
import { AnalyticsService } from './analytics.service';
import { StorefrontService } from './storefront.service';

// ------------------------------------------------------------------- dtos ---

export class AdsSettingsDto {
  @ApiPropertyOptional() @IsOptional() @IsBoolean() enabled?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(60) bannerUnitId?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(60) interstitialUnitId?: string;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() homeBanner?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() productBanner?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsInt() @Min(0) @Max(100) interstitialEvery?: number;
}

export class StoreAppSettingsDto {
  @ApiPropertyOptional({ description: 'Customers must sign in before they can browse' })
  @IsOptional()
  @IsBoolean()
  requireLogin?: boolean;

  @ApiPropertyOptional({ description: 'Show the community (customers\' posts) in the customer app' })
  @IsOptional()
  @IsBoolean()
  socialEnabled?: boolean;

  @ApiPropertyOptional({ type: AdsSettingsDto })
  @IsOptional()
  @ValidateNested()
  @Type(() => AdsSettingsDto)
  ads?: AdsSettingsDto;
}

export class StoreAboutDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(100) name?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(4000) about?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) address?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) mapUrl?: string;
  @ApiPropertyOptional({ nullable: true }) @IsOptional() @IsNumber() latitude?: number | null;
  @ApiPropertyOptional({ nullable: true }) @IsOptional() @IsNumber() longitude?: number | null;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(1000) hours?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(4000) returnPolicy?: string;
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @ArrayMaxSize(5) @IsString({ each: true }) phones?: string[];
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(30) whatsapp?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) instagram?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) facebook?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(200) tiktok?: string;
}

export class SessionDto {
  @ApiProperty() @IsUUID() id: string;
  @ApiProperty() @IsDateString() startedAt: string;
  @ApiProperty() @IsInt() @Min(0) seconds: number;
}

export class ViewDto {
  @ApiProperty() @IsUUID() productId: string;
  @ApiProperty() @IsInt() @Min(0) seconds: number;
  @ApiProperty() @IsDateString() at: string;
}

export class AnalyticsBatchDto {
  @ApiProperty({ description: 'Random id the app keeps for this phone' })
  @Matches(/^[A-Za-z0-9_-]{8,100}$/)
  deviceId: string;

  @ApiPropertyOptional({ type: [SessionDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => SessionDto)
  @ArrayMaxSize(200)
  sessions?: SessionDto[];

  @ApiPropertyOptional({ type: [ViewDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => ViewDto)
  @ArrayMaxSize(500)
  views?: ViewDto[];
}

const customerOf = (u?: AuthPrincipal) => (u?.type === 'customer' ? u.id : undefined);
const days = (iso: string | undefined, fallbackDaysAgo: number) => {
  const d = iso ? new Date(iso) : new Date(Date.now() - fallbackDaysAgo * 24 * 3600_000);
  return Number.isNaN(d.getTime()) ? new Date(Date.now() - fallbackDaysAgo * 24 * 3600_000) : d;
};

// ------------------------------------------------------------------ store ---

@ApiTags('Store · App')
@Public()
@Controller('store')
export class StoreAppController {
  constructor(
    private readonly storefront: StorefrontService,
    private readonly analytics: AnalyticsService,
  ) {}

  /** Read at start: sign-in rule, ads, assistant. */
  @Get('config')
  config() {
    return this.storefront.config();
  }

  @Get('about')
  about() {
    return this.storefront.about();
  }

  @Get('search/popular')
  popular() {
    return this.storefront.popularSearches();
  }

  /** Time in the app and product views, sent by the app in batches. */
  @Post('analytics')
  @HttpCode(HttpStatus.OK)
  ingest(@Body() dto: AnalyticsBatchDto, @CurrentUser() u?: AuthPrincipal) {
    return this.analytics.ingest(dto, customerOf(u));
  }
}

@ApiTags('Store · Wishlist')
@CustomerOnly()
@Controller('store')
export class StoreWishlistController {
  constructor(private readonly storefront: StorefrontService) {}

  @Get('wishlist')
  list(@CurrentUser() u: AuthPrincipal) {
    return this.storefront.wishlist(u.id);
  }

  /** Just the ids, to draw the hearts. */
  @Get('wishlist/ids')
  ids(@CurrentUser() u: AuthPrincipal) {
    return this.storefront.wishlistIds(u.id);
  }

  @Post('wishlist/:productId')
  @HttpCode(HttpStatus.OK)
  add(@Param('productId', ParseUUIDPipe) productId: string, @CurrentUser() u: AuthPrincipal) {
    return this.storefront.addToWishlist(u.id, productId);
  }

  @Delete('wishlist/:productId')
  remove(@Param('productId', ParseUUIDPipe) productId: string, @CurrentUser() u: AuthPrincipal) {
    return this.storefront.removeFromWishlist(u.id, productId);
  }

  @Get('cart/suggestions')
  suggestions(@CurrentUser() u: AuthPrincipal) {
    return this.storefront.cartSuggestions(u.id);
  }
}

/** A public page for a shared product link: preview card in WhatsApp, and "open in the app". */
@ApiTags('Store · Share')
@Public()
@Controller('share')
export class ShareController {
  constructor(
    private readonly catalog: CatalogService,
    private readonly config: ConfigService<Env, true>,
  ) {}

  @Get('p/:slug')
  @Header('Content-Type', 'text/html; charset=utf-8')
  async product(@Param('slug') slug: string) {
    const p = await this.catalog.storeProduct(slug).catch(() => {
      throw new NotFoundException('Product not found');
    });
    const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
    const base = this.config.getOrThrow<string>('PUBLIC_BASE_URL');
    const img = p.images[0]?.url ? (p.images[0].url.startsWith('http') ? p.images[0].url : `${base}/${p.images[0].url.replace(/^\//, '')}`) : '';
    const price = formatAmount(p.basePrice);
    const was = p.compareAtPrice ? formatAmount(p.compareAtPrice) : '';
    const title = esc(p.nameAr);
    const app = `dejavu://product/${encodeURIComponent(p.slug)}`;
    return `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title} — Dejavu</title>
<meta property="og:title" content="${title}"><meta property="og:description" content="${esc(price)}${was ? ` بدل ${esc(was)}` : ''}">
${img ? `<meta property="og:image" content="${esc(img)}">` : ''}<meta property="og:type" content="product">
<style>body{font-family:system-ui,sans-serif;background:#f8f9fa;margin:0;color:#1a1a1a}main{max-width:480px;margin:0 auto;padding:16px}
img{width:100%;border-radius:18px;background:#fff}h1{font-size:22px;margin:16px 0 4px}.p{font-size:20px;font-weight:700;color:#aa771c}
.w{text-decoration:line-through;color:#6c757d;margin-inline-start:8px;font-weight:400}a.b{display:block;text-align:center;margin-top:20px;padding:14px;border-radius:16px;
background:linear-gradient(90deg,#f3e5ab,#d4af37,#aa771c);color:#fff;font-weight:700;text-decoration:none}</style></head>
<body><main>${img ? `<img src="${esc(img)}" alt="${title}">` : ''}<h1>${title}</h1><div class="p">${esc(price)}${was ? `<span class="w">${esc(was)}</span>` : ''}</div>
${p.descriptionAr ? `<p>${esc(p.descriptionAr)}</p>` : ''}<a class="b" href="${app}">افتح في تطبيق Dejavu</a></main></body></html>`;
  }
}

// ------------------------------------------------------------------ admin ---

@ApiTags('Admin · Customer app')
@Controller('admin')
export class AdminStorefrontController {
  constructor(
    private readonly storefront: StorefrontService,
    private readonly analytics: AnalyticsService,
  ) {}

  @RequirePermissions('settings.manage')
  @Get('store-app')
  appSettings() {
    return this.storefront.appSettings();
  }

  @RequirePermissions('settings.manage')
  @Put('store-app')
  setAppSettings(@Body() dto: StoreAppSettingsDto) {
    return this.storefront.setAppSettings(dto);
  }

  @RequirePermissions('settings.manage')
  @Get('store-about')
  about() {
    return this.storefront.about();
  }

  @RequirePermissions('settings.manage')
  @Put('store-about')
  setAbout(@Body() dto: StoreAboutDto) {
    return this.storefront.setAbout(dto);
  }

  /** "سلوك العملاء": time in the app, most viewed products, searches. */
  @RequirePermissions('reports.sales')
  @Get('reports/behavior')
  behavior(@Query('from') from?: string, @Query('to') to?: string) {
    return this.analytics.behavior(days(from, 30), to ? days(to, 0) : new Date(Date.now() + 60_000));
  }

  @RequirePermissions('customers.read')
  @Get('customers/:id/usage')
  usage(@Param('id', ParseUUIDPipe) id: string) {
    return this.analytics.customerUsage(id);
  }
}
