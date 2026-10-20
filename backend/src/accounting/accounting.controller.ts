import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { AccountingService } from './accounting.service';
import {
  AccountsQueryDto,
  CreateAccountDto,
  DateRangeQueryDto,
  JournalQueryDto,
  ManualJournalEntryDto,
  ReverseEntryDto,
  UpdateAccountDto,
} from './dto/accounting.dto';

@ApiTags('Admin · Accounting')
@Controller('admin')
export class AccountingController {
  constructor(private readonly accounting: AccountingService) {}

  @RequirePermissions('journal.read')
  @Get('accounts')
  listAccounts(@Query() q: AccountsQueryDto) {
    return this.accounting.listAccounts(q);
  }

  @RequirePermissions('accounts.manage')
  @Post('accounts')
  createAccount(@Body() dto: CreateAccountDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.createAccount(dto, u.id);
  }

  @RequirePermissions('accounts.manage')
  @Patch('accounts/:id')
  updateAccount(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateAccountDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.updateAccount(id, dto, u.id);
  }

  @RequirePermissions('journal.read')
  @Get('accounts/:id/ledger')
  accountLedger(@Param('id', ParseUUIDPipe) id: string, @Query() q: DateRangeQueryDto) {
    return this.accounting.accountLedger(id, q);
  }

  @RequirePermissions('journal.read')
  @Get('journal-entries')
  listEntries(@Query() q: JournalQueryDto) {
    return this.accounting.listEntries(q);
  }

  @RequirePermissions('journal.read')
  @Get('journal-entries/:id')
  getEntry(@Param('id', ParseUUIDPipe) id: string) {
    return this.accounting.getEntry(id);
  }

  @RequirePermissions('journal.write')
  @Post('journal-entries')
  createEntry(@Body() dto: ManualJournalEntryDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.createManualEntry(dto, u.id);
  }

  @RequirePermissions('journal.write')
  @Put('journal-entries/:id')
  updateEntry(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ManualJournalEntryDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.updateDraft(id, dto, u.id);
  }

  @RequirePermissions('journal.write')
  @Delete('journal-entries/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteEntry(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.deleteDraft(id, u.id);
  }

  @RequirePermissions('journal.post')
  @Post('journal-entries/:id/post')
  @HttpCode(HttpStatus.OK)
  postEntry(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.postEntry(id, u.id);
  }

  @RequirePermissions('journal.reverse')
  @Post('journal-entries/:id/reverse')
  reverseEntry(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ReverseEntryDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounting.reverseEntry(id, dto.reason, u.id);
  }

  @RequirePermissions('reports.financial')
  @Get('reports/trial-balance')
  trialBalance(@Query() q: DateRangeQueryDto) {
    return this.accounting.trialBalance(q);
  }
}
