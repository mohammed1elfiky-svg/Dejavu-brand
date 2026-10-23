import { BadRequestException, Body, Controller, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { parseDateOnly, startOfBusinessDay } from '../common/utils/dates';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { PrismaService } from '../prisma/prisma.service';
import { ShiftService } from './shift.service';
import { CustomersService } from './customers.service';
import {
  CloseShiftDto,
  OpenShiftDto,
  PosArrangeDto,
  PosFreeReturnDto,
  PosLookupQueryDto,
  PosProductsQueryDto,
  PosReturnDto,
  PosSaleDto,
  PosSalesQueryDto,
  PosSettingsDto,
  PosVoidDto,
  QuickCustomerDto,
  ShiftsQueryDto,
} from './dto/pos.dto';
import { PosService } from './pos.service';

@ApiTags('Admin · POS')
@Controller('admin/pos')
export class AdminPosController {
  constructor(
    private readonly pos: PosService,
    private readonly customers: CustomersService,
    private readonly config: ConfigService,
    private readonly shifts: ShiftService,
    private readonly prisma: PrismaService,
  ) {}

  /** Products for the cashier grid, in the arranged order, with promotion prices and stock. */
  @RequirePermissions('pos.sell')
  @Get('products')
  products(@Query() q: PosProductsQueryDto) {
    return this.pos.products(q);
  }

  /** Resolves a scanned barcode: variant SKU (100-2), factory barcode, or product SKU (100). */
  @RequirePermissions('pos.sell')
  @Get('lookup')
  lookup(@Query() q: PosLookupQueryDto) {
    return this.pos.lookup(q.code);
  }

  /** Saves the grid order (drag and drop). */
  @RequirePermissions('products.write')
  @Put('product-order')
  arrange(@Body() dto: PosArrangeDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.arrange(dto.productIds, u.id);
  }

  /** Rings up a sale: cash (any mix of currencies, with change) or credit to the customer's account. */
  @RequirePermissions('pos.sell')
  @Post('sales')
  sell(@Body() dto: PosSaleDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.sell(dto, u.id);
  }

  /** Till sales of a day (today by default): your own, or everyone's with all=true for managers. */
  @RequirePermissions('pos.sell')
  @Get('sales')
  sales(@Query() q: PosSalesQueryDto, @CurrentUser() u: AuthPrincipal) {
    const tz = this.config.getOrThrow<string>('STORE_TIMEZONE');
    const from = q.date ? startOfBusinessDay(tz, new Date(parseDateOnly(q.date).getTime() + 12 * 3600_000)) : startOfBusinessDay(tz);
    return this.pos.salesOf({ from, to: new Date(from.getTime() + 24 * 3600_000) }, u, q.all);
  }

  /** Deletes a sale of today (managers: any day): money, stock and accounts are put back. */
  @RequirePermissions('pos.edit_sales')
  @Post('sales/:id/void')
  @HttpCode(HttpStatus.OK)
  voidSale(@Param('id', ParseUUIDPipe) id: string, @Body() dto: PosVoidDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.voidSale(id, dto.reason, u);
  }

  /** Corrects a sale: deleted and rung up again with the new items and payments. */
  @RequirePermissions('pos.edit_sales', 'pos.sell')
  @Put('sales/:id')
  editSale(@Param('id', ParseUUIDPipe) id: string, @Body() dto: PosSaleDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.editSale(id, dto, u);
  }

  /** Finds a sale by its number (receipt), with what can still be returned of each line. */
  @RequirePermissions('pos.sell')
  @Get('sales/by-number/:number')
  findSale(@Param('number') number: string) {
    return this.pos.findSale(number);
  }

  /** Return, or exchange for other pieces, settling the difference. */
  /** Return or exchange without the invoice: returned pieces picked by hand. */
  @RequirePermissions('pos.sell')
  @Post('returns/without-invoice')
  returnWithoutInvoice(@Body() dto: PosFreeReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.returnWithoutInvoice(dto, u.id);
  }

  @RequirePermissions('pos.sell')
  @Post('returns')
  returnOrExchange(@Body() dto: PosReturnDto, @CurrentUser() u: AuthPrincipal) {
    return this.pos.returnOrExchange(dto, u.id);
  }

  @RequirePermissions('pos.sell')
  @Get('sales/:id')
  receipt(@Param('id', ParseUUIDPipe) id: string) {
    return this.pos.receipt(id);
  }

  /** The cashier's sales today and their drawers. */
  @RequirePermissions('pos.sell')
  @Get('shift')
  shift(@CurrentUser() u: AuthPrincipal) {
    return this.pos.shift(u.id, startOfBusinessDay(this.config.getOrThrow<string>('STORE_TIMEZONE')));
  }

  // shifts

  /** Your open shift with the float, live drawer balances and sales ({ shift: null } when none is open). */
  @RequirePermissions('pos.sell')
  @Get('shifts/current')
  async currentShift(@CurrentUser() u: AuthPrincipal) {
    return { shift: await this.shifts.current(u.id) };
  }

  /** Opens a shift; the float can be taken from a safe into your drawers. */
  @RequirePermissions('pos.sell')
  @Post('shifts/open')
  openShift(@Body() dto: OpenShiftDto, @CurrentUser() u: AuthPrincipal) {
    return this.shifts.open(dto, u);
  }

  /** Closes your shift with the cash counted; differences go to cash over & short. */
  @RequirePermissions('pos.sell')
  @Post('shifts/close')
  @HttpCode(HttpStatus.OK)
  closeShift(@Body() dto: CloseShiftDto, @CurrentUser() u: AuthPrincipal) {
    return this.shifts.close(dto, u);
  }

  /** Managers close someone else's shift. */
  @RequirePermissions('cashboxes.manage')
  @Post('shifts/:id/close')
  @HttpCode(HttpStatus.OK)
  closeShiftOf(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CloseShiftDto, @CurrentUser() u: AuthPrincipal) {
    return this.shifts.close(dto, u, id);
  }

  /** Shift history: your own, or everyone's for managers (filter by userId). */
  @RequirePermissions('pos.sell')
  @Get('shifts')
  listShifts(@Query() q: ShiftsQueryDto, @CurrentUser() u: AuthPrincipal) {
    const manager = u.permissions.includes('cashboxes.manage');
    const page = Object.assign(new PaginationQueryDto(), { page: q.page ?? 1, pageSize: 20 });
    return this.shifts.list(Object.assign(page, { userId: manager ? q.userId : u.id }));
  }

  // the employee's own till layout

  @RequirePermissions('pos.sell')
  @Get('settings')
  async settings(@CurrentUser() u: AuthPrincipal) {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: u.id }, select: { posSettings: true, saleCurrency: true } });
    return { ...((user.posSettings as object | null) ?? {}), saleCurrency: user.saleCurrency ?? 'SAR' };
  }

  @RequirePermissions('pos.sell')
  @Put('settings')
  async saveSettings(@Body() dto: PosSettingsDto, @CurrentUser() u: AuthPrincipal) {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: u.id }, select: { posSettings: true } });
    const before = (user.posSettings as { checkoutButtons?: string[]; hiddenButtons?: string[] } | null) ?? {};
    const posSettings = {
      checkoutButtons: dto.checkoutButtons ?? before.checkoutButtons ?? [],
      hiddenButtons: dto.hiddenButtons ?? before.hiddenButtons ?? [],
    };
    if (dto.saleCurrency && !(await this.prisma.currency.count({ where: { code: dto.saleCurrency, isActive: true } }))) {
      throw new BadRequestException(`Currency ${dto.saleCurrency} is not available`);
    }
    await this.prisma.user.update({ where: { id: u.id }, data: { posSettings, ...(dto.saleCurrency && { saleCurrency: dto.saleCurrency }) } });
    return this.settings(u);
  }

  /** Adds a customer from the till (name + phone), e.g. for a credit sale. */
  @RequirePermissions('pos.sell')
  @Post('customers')
  quickCustomer(@Body() dto: QuickCustomerDto, @CurrentUser() u: AuthPrincipal) {
    return this.customers.quickCreate(dto, u.id);
  }
}
