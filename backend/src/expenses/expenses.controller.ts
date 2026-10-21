import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import {
  ApproveExpenseDto,
  CancelExpenseDto,
  CreateExpenseCategoryDto,
  ExpenseDto,
  ExpensesQueryDto,
  PayExpenseDto,
} from './dto/expenses.dto';
import { ExpensesService } from './expenses.service';

@ApiTags('Admin · Expenses')
@Controller('admin')
export class ExpensesController {
  constructor(private readonly expenses: ExpensesService) {}

  @RequirePermissions('expenses.read')
  @Get('expense-categories')
  listCategories() {
    return this.expenses.listCategories();
  }

  @RequirePermissions('expenses.write', 'accounts.manage')
  @Post('expense-categories')
  createCategory(@Body() dto: CreateExpenseCategoryDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.createCategory(dto, u.id);
  }

  @RequirePermissions('expenses.read')
  @Get('expenses')
  list(@Query() q: ExpensesQueryDto) {
    return this.expenses.list(q);
  }

  @RequirePermissions('expenses.read')
  @Get('expenses/:id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.expenses.get(id);
  }

  @RequirePermissions('expenses.write')
  @Post('expenses')
  create(@Body() dto: ExpenseDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.create(dto, u.id);
  }

  @RequirePermissions('expenses.write')
  @Put('expenses/:id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ExpenseDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.update(id, dto, u.id);
  }

  @RequirePermissions('expenses.approve')
  @Post('expenses/:id/approve')
  @HttpCode(HttpStatus.OK)
  approve(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ApproveExpenseDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.approve(id, dto, u.id);
  }

  @RequirePermissions('expenses.write', 'payments.record')
  @Post('expenses/:id/pay')
  @HttpCode(HttpStatus.OK)
  pay(@Param('id', ParseUUIDPipe) id: string, @Body() dto: PayExpenseDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.pay(id, dto, u.id);
  }

  @RequirePermissions('expenses.approve')
  @Post('expenses/:id/cancel')
  @HttpCode(HttpStatus.OK)
  cancel(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CancelExpenseDto, @CurrentUser() u: AuthPrincipal) {
    return this.expenses.cancel(id, dto.reason, u.id);
  }
}
