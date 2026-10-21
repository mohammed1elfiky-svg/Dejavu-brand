import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { CustomerAccountsService } from './customer-accounts.service';
import {
  AccountStatementQueryDto,
  ApproveTopUpDto,
  BankAccountDto,
  CashBoxesQueryDto,
  CashTransferDto,
  CreateCashBoxDto,
  CustomerReceiptDto,
  RejectTopUpDto,
  TopUpRequestDto,
  TopUpsQueryDto,
  TransfersQueryDto,
  UpdateBankAccountDto,
  UpdateCashBoxDto,
} from './treasury.dto';
import { TreasuryService } from './treasury.service';
import { WalletService } from './wallet.service';

@ApiTags('Admin · Cash boxes')
@Controller('admin')
export class AdminTreasuryController {
  constructor(private readonly treasury: TreasuryService) {}

  /** The signed-in cashier's own drawers (opened on first call), with live balances. */
  @RequirePermissions('pos.sell')
  @Get('cash-boxes/mine')
  mine(@CurrentUser() u: AuthPrincipal) {
    return this.treasury.ensureCashierBoxes(u.id);
  }

  /** Every drawer, safe and bank account with live balances. */
  @RequirePermissions('cashboxes.manage')
  @Get('cash-boxes')
  list(@Query() q: CashBoxesQueryDto) {
    return this.treasury.listBoxes({ kind: q.kind, currency: q.currency, userId: q.userId });
  }

  /** Safes and bank accounts any staff member may hand money over to. */
  @StaffOnly()
  @Get('cash-boxes/safes')
  safes() {
    return this.treasury.listBoxes({ kind: { not: 'CASHIER' }, isActive: true });
  }

  @RequirePermissions('cashboxes.manage')
  @Post('cash-boxes')
  create(@Body() dto: CreateCashBoxDto, @CurrentUser() u: AuthPrincipal) {
    return this.treasury.createBox(dto, u.id);
  }

  @RequirePermissions('cashboxes.manage')
  @Patch('cash-boxes/:id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateCashBoxDto, @CurrentUser() u: AuthPrincipal) {
    return this.treasury.updateBox(id, dto, u.id);
  }

  @StaffOnly()
  @Get('cash-boxes/:id/entries')
  async entries(@Param('id', ParseUUIDPipe) id: string, @Query() q: PaginationQueryDto, @CurrentUser() u: AuthPrincipal) {
    await this.treasury.visibleBox(id, u);
    return this.treasury.boxEntries(id, q);
  }

  /** Withdrawal / deposit between boxes, or a currency exchange when they hold different currencies. */
  @StaffOnly()
  @Post('cash-transfers')
  transfer(@Body() dto: CashTransferDto, @CurrentUser() u: AuthPrincipal) {
    return this.treasury.transfer(dto, u);
  }

  @RequirePermissions('cashboxes.manage')
  @Get('cash-transfers')
  transfers(@Query() q: TransfersQueryDto) {
    return this.treasury.listTransfers(q);
  }
}

@ApiTags('Admin · Customers')
@Controller('admin/customers/:id/account')
export class AdminCustomerAccountController {
  constructor(private readonly accounts: CustomerAccountsService) {}

  /** Balances per currency: + the customer owes, - wallet credit. */
  @RequirePermissions('customers.read')
  @Get()
  async get(@Param('id', ParseUUIDPipe) id: string, @Query() q: AccountStatementQueryDto) {
    return { balances: await this.accounts.balances(id), entries: await this.accounts.statement(id, q) };
  }

  /** Money taken from the customer at the till: pays off debt, any excess becomes credit. */
  @RequirePermissions('pos.sell')
  @Post('receipts')
  receipt(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CustomerReceiptDto, @CurrentUser() u: AuthPrincipal) {
    return this.accounts.receipt(id, dto, u);
  }
}

@ApiTags('Admin · Wallet top-ups')
@Controller('admin')
export class AdminWalletController {
  constructor(private readonly wallet: WalletService) {}

  @RequirePermissions('wallet.manage')
  @Get('wallet/top-ups')
  list(@Query() q: TopUpsQueryDto) {
    return this.wallet.list(q);
  }

  @RequirePermissions('wallet.manage')
  @Get('wallet/top-ups/:id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.wallet.get(id);
  }

  @RequirePermissions('wallet.manage')
  @Post('wallet/top-ups/:id/approve')
  @HttpCode(HttpStatus.OK)
  approve(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ApproveTopUpDto, @CurrentUser() u: AuthPrincipal) {
    return this.wallet.approve(id, dto, u.id);
  }

  @RequirePermissions('wallet.manage')
  @Post('wallet/top-ups/:id/reject')
  @HttpCode(HttpStatus.OK)
  reject(@Param('id', ParseUUIDPipe) id: string, @Body() dto: RejectTopUpDto, @CurrentUser() u: AuthPrincipal) {
    return this.wallet.reject(id, dto.reason, u.id);
  }

  @StaffOnly()
  @Get('bank-accounts')
  bankAccounts() {
    return this.wallet.bankAccounts(false);
  }

  @RequirePermissions('settings.manage')
  @Post('bank-accounts')
  createBank(@Body() dto: BankAccountDto, @CurrentUser() u: AuthPrincipal) {
    return this.wallet.createBankAccount(dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Patch('bank-accounts/:id')
  updateBank(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateBankAccountDto, @CurrentUser() u: AuthPrincipal) {
    return this.wallet.updateBankAccount(id, dto, u.id);
  }
}

@ApiTags('Store · Wallet')
@Public()
@Controller('store/bank-accounts')
export class StoreBankAccountsController {
  constructor(private readonly wallet: WalletService) {}

  /** Where customers can transfer money to top up their wallet. */
  @Get()
  list() {
    return this.wallet.bankAccounts(true);
  }
}

@ApiTags('Store · Wallet')
@CustomerOnly()
@Controller('store/me/wallet')
export class StoreWalletController {
  constructor(
    private readonly wallet: WalletService,
    private readonly accounts: CustomerAccountsService,
  ) {}

  /** Balances per currency (+ = money available to spend) and recent movements. */
  @Get()
  get(@CurrentUser() u: AuthPrincipal) {
    return this.accounts.wallet(u.id);
  }

  @Get('top-ups')
  topUps(@CurrentUser() u: AuthPrincipal) {
    return this.wallet.customerRequests(u.id);
  }

  @Post('top-ups')
  request(@CurrentUser() u: AuthPrincipal, @Body() dto: TopUpRequestDto) {
    return this.wallet.request(u.id, dto);
  }
}
