import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsArray, IsBoolean, IsEnum, IsIn, IsInt, IsNumber, IsOptional, IsString, IsUUID, Max, MaxLength, Min, MinLength, ValidateNested } from 'class-validator';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { PostKind } from '../generated/prisma/client';
import { SocialService } from './social.service';

export class PostTagDto {
  @ApiProperty() @IsUUID() productId: string;
  @ApiPropertyOptional({ description: 'The exact size / colour, if known' }) @IsOptional() @IsUUID() variantId?: string;
  @ApiPropertyOptional({ default: 0 }) @IsOptional() @IsInt() @Min(0) @Max(9) imageIndex?: number;
  @ApiPropertyOptional({ description: '0..1 from the left' }) @IsOptional() @IsNumber() @Min(0) @Max(1) x?: number;
  @ApiPropertyOptional({ description: '0..1 from the top' }) @IsOptional() @IsNumber() @Min(0) @Max(1) y?: number;
}

export class CreatePostDto {
  @ApiPropertyOptional({ enum: PostKind, default: PostKind.LOOK }) @IsOptional() @IsEnum(PostKind) kind?: PostKind;
  @ApiPropertyOptional({ description: 'Text; @username mentions notify them' }) @IsOptional() @IsString() @MaxLength(1000) caption?: string;
  @ApiProperty({ type: [String], description: 'Public image URLs (POST store/me/images)' }) @IsArray() @ArrayMinSize(1) @ArrayMaxSize(10) @IsString({ each: true }) images: string[];

  @ApiPropertyOptional({ type: [PostTagDto] })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PostTagDto)
  @ArrayMaxSize(20)
  tags?: PostTagDto[];
}

export class CommentDto {
  @ApiProperty() @IsString() @MinLength(1) @MaxLength(500) body: string;
}

export class AmbassadorSettingsDto {
  @ApiPropertyOptional() @IsOptional() @IsBoolean() enabled?: boolean;
  @ApiPropertyOptional({ example: 3 }) @IsOptional() @IsNumber() @Min(0) @Max(50) percent?: number;
  @ApiPropertyOptional({ enum: ['POINTS', 'WALLET'] }) @IsOptional() @IsIn(['POINTS', 'WALLET']) kind?: 'POINTS' | 'WALLET';
}

export class HideDto {
  @ApiProperty() @IsBoolean() hidden: boolean;
}

@ApiTags('Store · Social')
@Public()
@Controller('store/social')
export class StoreSocialPublicController {
  constructor(private readonly social: SocialService) {}

  /** FOLLOWING (people I follow) or EXPLORE (most liked lately). */
  @Get('feed')
  feed(@CurrentUser() u: AuthPrincipal | undefined, @Query('tab') tab?: string, @Query('page') page?: string, @Query('pageSize') pageSize?: string) {
    return this.social.feed(u?.type === 'customer' ? u.id : undefined, tab === 'FOLLOWING' ? 'FOLLOWING' : 'EXPLORE', Number(page) || 1, Number(pageSize) || 20);
  }

  @Get('posts/:id')
  post(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u?: AuthPrincipal) {
    return this.social.getPost(id, u?.type === 'customer' ? u.id : undefined);
  }

  @Get('posts/:id/comments')
  comments(@Param('id', ParseUUIDPipe) id: string, @Query('page') page?: string) {
    return this.social.comments(id, Number(page) || 1);
  }

  @Get('users/:username')
  profile(@Param('username') username: string, @CurrentUser() u?: AuthPrincipal) {
    return this.social.profile(username, u?.type === 'customer' ? u.id : undefined);
  }

  @Get('users/:username/posts')
  posts(@Param('username') username: string, @CurrentUser() u?: AuthPrincipal, @Query('page') page?: string) {
    return this.social.userPosts(username, u?.type === 'customer' ? u.id : undefined, Number(page) || 1);
  }

  @Get('users/:username/followers')
  followers(@Param('username') username: string) {
    return this.social.followList(username, 'followers');
  }

  @Get('users/:username/following')
  following(@Param('username') username: string) {
    return this.social.followList(username, 'following');
  }

  /** What they wish for: friends can gift any of it. */
  @Get('users/:username/wishlist')
  wishlist(@Param('username') username: string) {
    return this.social.wishlistOf(username);
  }

  /** Customers (with a username) whose birthday is today. */
  @Get('birthdays/today')
  birthdays(@CurrentUser() u?: AuthPrincipal) {
    return this.social.birthdaysToday(u?.type === 'customer' ? u.id : undefined);
  }
}

@ApiTags('Store · Social')
@CustomerOnly()
@Controller('store/social')
export class StoreSocialController {
  constructor(private readonly social: SocialService) {}

  @Post('posts')
  create(@CurrentUser() u: AuthPrincipal, @Body() dto: CreatePostDto) {
    return this.social.createPost(u.id, dto);
  }

  @Delete('posts/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.social.deletePost(u.id, id);
  }

  @Post('posts/:id/like')
  @HttpCode(HttpStatus.OK)
  like(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.social.like(u.id, id);
  }

  @Delete('posts/:id/like')
  unlike(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.social.unlike(u.id, id);
  }

  @Post('posts/:id/comments')
  comment(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string, @Body() dto: CommentDto) {
    return this.social.comment(u.id, id, dto.body);
  }

  @Delete('comments/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  removeComment(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.social.deleteComment(u.id, id);
  }

  /** "اشترِ الإطلالة": every tagged product into the cart (the author earns the ambassador reward). */
  @Post('posts/:id/shop')
  @HttpCode(HttpStatus.OK)
  shop(@CurrentUser() u: AuthPrincipal, @Param('id', ParseUUIDPipe) id: string) {
    return this.social.shopTheLook(u.id, id);
  }

  @Post('users/:username/follow')
  @HttpCode(HttpStatus.OK)
  follow(@CurrentUser() u: AuthPrincipal, @Param('username') username: string) {
    return this.social.follow(u.id, username);
  }

  @Delete('users/:username/follow')
  unfollow(@CurrentUser() u: AuthPrincipal, @Param('username') username: string) {
    return this.social.unfollow(u.id, username);
  }

  /** Ambassador rewards earned from my posts. */
  @Get('me/rewards')
  rewards(@CurrentUser() u: AuthPrincipal) {
    return this.social.myRewards(u.id);
  }
}

@ApiTags('Admin · Social')
@Controller('admin/social')
export class AdminSocialController {
  constructor(private readonly social: SocialService) {}

  @RequirePermissions('customers.read')
  @Get('posts')
  posts(@Query('page') page?: string) {
    return this.social.adminPosts(Number(page) || 1);
  }

  @RequirePermissions('customers.write')
  @Put('posts/:id/hidden')
  hidePost(@Param('id', ParseUUIDPipe) id: string, @Body() dto: HideDto) {
    return this.social.hidePost(id, dto.hidden);
  }

  @RequirePermissions('customers.write')
  @Post('comments/:id/hide')
  @HttpCode(HttpStatus.OK)
  hideComment(@Param('id', ParseUUIDPipe) id: string) {
    return this.social.hideComment(id);
  }

  @RequirePermissions('customers.read')
  @Get('ambassador-settings')
  ambassador() {
    return this.social.ambassadorSettings();
  }

  @RequirePermissions('settings.manage')
  @Put('ambassador-settings')
  setAmbassador(@Body() dto: AmbassadorSettingsDto) {
    return this.social.setAmbassadorSettings(dto);
  }

  /** Runs today's birthday notifications now (they run on their own once a day). */
  @RequirePermissions('settings.manage')
  @Post('birthdays/run')
  @HttpCode(HttpStatus.OK)
  birthdays() {
    return this.social.birthdays();
  }
}
