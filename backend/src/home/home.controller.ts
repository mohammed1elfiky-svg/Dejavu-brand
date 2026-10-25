import { Controller, Get, Query } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { IsInt, IsOptional, IsString, Max, MaxLength, Min, MinLength } from 'class-validator';
import { AuthPrincipal, CurrentUser, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { HomeService } from './home.service';

class SearchQueryDto {
  @ApiPropertyOptional({ example: 'سارة' })
  @IsString()
  @MinLength(1)
  @MaxLength(80)
  q: string;
}

class DailySalesQueryDto {
  @ApiPropertyOptional({ default: 7 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(90)
  days?: number;
}

@ApiTags('Admin · Home')
@StaffOnly()
@Controller('admin/home')
export class HomeController {
  constructor(private readonly home: HomeService) {}

  /** One search box for products, customers, invoices, suppliers and purchase orders. */
  @Get('search')
  search(@Query() q: SearchQueryDto, @CurrentUser() u: AuthPrincipal) {
    return this.home.search(q.q, u);
  }

  /** Counts for the badges: new app orders, top-ups, reviews, open shifts, texts waiting, returns, low stock. */
  @Get('badges')
  badges(@CurrentUser() u: AuthPrincipal) {
    return this.home.badges(u);
  }

  /** Sales per day for the home chart, and the total of the period before. */
  @RequirePermissions('dashboard.view')
  @Get('daily-sales')
  dailySales(@Query() q: DailySalesQueryDto) {
    return this.home.dailySales(q.days ?? 7);
  }
}
