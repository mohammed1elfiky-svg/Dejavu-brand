import { BadRequestException, Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Query } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { ArrayMaxSize, ArrayMinSize, IsDateString, IsEnum, IsIn, IsOptional, IsString, IsUUID, MaxLength, MinLength, ValidateNested } from 'class-validator';
import { GiftCardStatus, LayawayStatus } from '../generated/prisma/client';
import { AuthPrincipal, CurrentUser, CustomerOnly, RequirePermissions } from '../common/decorators/auth.decorators';
import { IsAmount, IsCurrencyCode } from '../common/decorators/validation.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { PosItemDto, PosTenderDto } from './dto/pos.dto';
import { GiftCardsService } from './gift-cards.service';
import { LayawayService } from './layaway.service';

export class DepositDto {
  @ApiProperty({ example: 'YER' }) @IsCurrencyCode() currency: string;
  @ApiProperty({ example: '20000' }) @IsAmount() amount: string;
  @ApiPropertyOptional() @IsOptional() @IsUUID() cashBoxId?: string;
}

export class CreateLayawayDto {
  @ApiProperty() @IsUUID() customerId: string;

  @ApiProperty({ type: [PosItemDto] })
  @ValidateNested({ each: true })
  @Type(() => PosItemDto)
  @ArrayMinSize(1)
  @ArrayMaxSize(50)
  items: PosItemDto[];

  @ApiPropertyOptional({ type: DepositDto })
  @IsOptional()
  @ValidateNested()
  @Type(() => DepositDto)
  deposit?: DepositDto;

  @ApiPropertyOptional() @IsOptional() @IsDateString() expiresAt?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) note?: string;
}

export class CompleteLayawayDto {
  @ApiPropertyOptional({ type: [PosTenderDto], description: 'Money for what the deposits do not cover' })
  @IsOptional()
  @ValidateNested({ each: true })
  @Type(() => PosTenderDto)
  @ArrayMaxSize(10)
  payments?: PosTenderDto[];

  @ApiPropertyOptional({ enum: ['CASH', 'BANK_TRANSFER'] }) @IsOptional() @IsIn(['CASH', 'BANK_TRANSFER']) paymentMethod?: 'CASH' | 'BANK_TRANSFER';
  @ApiPropertyOptional() @IsOptional() @IsCurrencyCode() changeCurrency?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) note?: string;
}

export class CancelDto {
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(300) reason?: string;
}

export class LayawaysQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: LayawayStatus }) @IsOptional() @IsEnum(LayawayStatus) status?: LayawayStatus;
  @ApiPropertyOptional() @IsOptional() @IsUUID() customerId?: string;
}

export class IssueGiftCardDto {
  @ApiProperty({ example: 'SAR' }) @IsCurrencyCode() currency: string;
  @ApiProperty({ example: '200' }) @IsAmount() amount: string;
  @ApiPropertyOptional() @IsOptional() @IsUUID() cashBoxId?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(100) buyerName?: string;
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(300) note?: string;
  @ApiPropertyOptional() @IsOptional() @IsDateString() expiresAt?: string;
}

export class RedeemGiftCardDto {
  @ApiProperty({ example: 'GC-ABCD-EFGH' }) @IsString() @MinLength(4) @MaxLength(30) code: string;
  @ApiPropertyOptional({ description: 'Staff: the customer whose account gets the value' }) @IsOptional() @IsUUID() customerId?: string;
}

export class GiftCardsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: GiftCardStatus }) @IsOptional() @IsEnum(GiftCardStatus) status?: GiftCardStatus;
  @ApiPropertyOptional() @IsOptional() @IsString() q?: string;
}

@ApiTags('Admin · Layaways')
@RequirePermissions('layaways.manage')
@Controller('admin/layaways')
export class AdminLayawaysController {
  constructor(private readonly layaways: LayawayService) {}

  @Get()
  list(@Query() q: LayawaysQueryDto) {
    return this.layaways.list(q);
  }

  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.layaways.get(id);
  }

  @Post()
  create(@Body() dto: CreateLayawayDto, @CurrentUser() u: AuthPrincipal) {
    return this.layaways.create(dto, u);
  }

  @Post(':id/payments')
  pay(@Param('id', ParseUUIDPipe) id: string, @Body() dto: DepositDto, @CurrentUser() u: AuthPrincipal) {
    return this.layaways.addPayment(id, dto, u);
  }

  @Post(':id/complete')
  @HttpCode(HttpStatus.OK)
  complete(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CompleteLayawayDto, @CurrentUser() u: AuthPrincipal) {
    return this.layaways.complete(id, dto, u);
  }

  @Post(':id/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CancelDto, @CurrentUser() u: AuthPrincipal) {
    return this.layaways.cancel(id, dto.reason, u);
  }
}

@ApiTags('Admin · Gift cards')
@RequirePermissions('giftcards.manage')
@Controller('admin/gift-cards')
export class AdminGiftCardsController {
  constructor(private readonly cards: GiftCardsService) {}

  @Get()
  list(@Query() q: GiftCardsQueryDto) {
    return this.cards.list(q);
  }

  @Get('check/:code')
  check(@Param('code') code: string) {
    return this.cards.check(code);
  }

  @Post()
  issue(@Body() dto: IssueGiftCardDto, @CurrentUser() u: AuthPrincipal) {
    return this.cards.issue(dto, u);
  }

  /** Put a card's value on a customer's account at the till. */
  @Post('redeem')
  @HttpCode(HttpStatus.OK)
  redeem(@Body() dto: RedeemGiftCardDto, @CurrentUser() u: AuthPrincipal) {
    if (!dto.customerId) throw new BadRequestException('Pick the customer');
    return this.cards.redeem(dto.code, dto.customerId, u.id);
  }

  @Post(':id/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.cards.cancel(id, u);
  }
}

@ApiTags('Store · Gift cards')
@CustomerOnly()
@Controller('store/me/gift-cards')
export class StoreGiftCardsController {
  constructor(private readonly cards: GiftCardsService) {}

  /** The customer types the code: the card's value goes to their wallet. */
  @Post('redeem')
  @HttpCode(HttpStatus.OK)
  async redeem(@Body() dto: RedeemGiftCardDto, @CurrentUser() u: AuthPrincipal) {
    const card = await this.cards.redeem(dto.code, u.id);
    return { code: card.code, currency: card.currency, amount: card.amount };
  }
}

