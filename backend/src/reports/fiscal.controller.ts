import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post } from '@nestjs/common';
import { ApiProperty, ApiTags } from '@nestjs/swagger';
import { IsInt, Max, Min } from 'class-validator';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { FiscalService } from './fiscal.service';

class CreateFiscalYearDto {
  @ApiProperty({ example: 2027 })
  @IsInt()
  @Min(2000)
  @Max(2100)
  year: number;
}

@ApiTags('Admin · Accounting')
@Controller('admin')
export class FiscalController {
  constructor(private readonly fiscal: FiscalService) {}

  @RequirePermissions('journal.read')
  @Get('fiscal-years')
  list() {
    return this.fiscal.list();
  }

  @RequirePermissions('periods.close')
  @Post('fiscal-years')
  createYear(@Body() dto: CreateFiscalYearDto, @CurrentUser() u: AuthPrincipal) {
    return this.fiscal.createYear(dto.year, u.id);
  }

  @RequirePermissions('periods.close')
  @Post('fiscal-periods/:id/close')
  @HttpCode(HttpStatus.OK)
  close(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.fiscal.close(id, u.id);
  }

  @RequirePermissions('periods.close')
  @Post('fiscal-periods/:id/reopen')
  @HttpCode(HttpStatus.OK)
  reopen(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.fiscal.reopen(id, u.id);
  }

  @RequirePermissions('periods.close')
  @Post('fiscal-periods/:id/lock')
  @HttpCode(HttpStatus.OK)
  lock(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.fiscal.lock(id, u.id);
  }
}
