import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { SupplierVoucherKind } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { CurrencyService, inCurrency, toBase } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from '../treasury/treasury.service';
import { SupplierCommissionDto, SupplierOpeningBalanceDto, SupplierPaymentVoucherDto } from './dto/purchasing.dto';

type User = { id: string; permissions: string[] };

/**
 * The supplier's own account, in the currencies the business is done in: import orders
 * add what was ordered (e.g. yuan), payment vouchers take off dollars at the yuan rate of
 * the day, and the agent's commission is charged in dollars for a period. This is what is
 * matched against the supplier's statement; the ledger keeps the SAR side (payables).
 */
@Injectable()
export class SupplierLedgerService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly treasury: TreasuryService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  /** Balances per currency (+ = owed to the supplier) and the movements, newest first. */
  async account(supplierId: string) {
    const supplier = await this.prisma.supplier.findUnique({ where: { id: supplierId }, select: { id: true, name: true } });
    if (!supplier) throw new NotFoundException('Supplier not found');
    const [sums, entries, vouchers] = await Promise.all([
      this.prisma.supplierAccountEntry.groupBy({ by: ['currency'], where: { supplierId }, _sum: { amount: true } }),
      this.prisma.supplierAccountEntry.findMany({ where: { supplierId }, orderBy: { createdAt: 'desc' }, take: 300 }),
      this.prisma.supplierVoucher.findMany({ where: { supplierId }, orderBy: { createdAt: 'desc' }, take: 100 }),
    ]);
    return {
      balances: sums.map((s) => ({ currency: s.currency, balance: money(dec(s._sum.amount)) })).filter((b) => !b.balance.isZero()),
      entries,
      vouchers,
      /** Yuan per dollar of the last payment, used to value goods on arrival. */
      lastRate: await this.lastRate(this.prisma, supplierId),
    };
  }

  /** Foreign currency per USD of the supplier's latest payment voucher, if any. */
  async lastRate(db: Tx | PrismaService, supplierId: string) {
    const v = await db.supplierVoucher.findFirst({
      where: { supplierId, kind: SupplierVoucherKind.PAYMENT, cancelledAt: null, foreignPerUsd: { not: null } },
      orderBy: { createdAt: 'desc' },
      select: { foreignPerUsd: true },
    });
    return v?.foreignPerUsd ?? null;
  }

  async pay(dto: SupplierPaymentVoucherDto, user: User) {
    const id = await this.prisma.$transaction((tx) => this.payIn(tx, dto, user));
    return this.voucher(id);
  }

  async commission(dto: SupplierCommissionDto, user: User) {
    const id = await this.prisma.$transaction((tx) => this.commissionIn(tx, dto, user));
    return this.voucher(id);
  }

  /** Deletes a voucher: its journal entry is reversed and it leaves the supplier account. */
  async cancel(id: string, user: User) {
    await this.prisma.$transaction((tx) => this.cancelIn(tx, id, user));
    return this.voucher(id);
  }

  /** Corrects a voucher: cancelled and recorded again with the new figures. */
  async edit(id: string, dto: SupplierPaymentVoucherDto | SupplierCommissionDto, user: User) {
    const newId = await this.prisma.$transaction(async (tx) => {
      const old = await this.cancelIn(tx, id, user);
      if (dto.supplierId !== old.supplierId) throw new BadRequestException('A voucher stays with its supplier');
      return old.kind === SupplierVoucherKind.PAYMENT
        ? this.payIn(tx, dto as SupplierPaymentVoucherDto, user)
        : this.commissionIn(tx, dto as SupplierCommissionDto, user);
    });
    return this.voucher(newId);
  }

  /**
   * A balance from before the system: what we owed the supplier (or what they owed us),
   * in the currency it was kept in. Yuan and other currencies without a rate need its SAR value.
   */
  async openingBalance(supplierId: string, dto: SupplierOpeningBalanceDto, user: User) {
    await this.prisma.$transaction(async (tx) => {
      const supplier = await tx.supplier.findUnique({ where: { id: supplierId }, select: { id: true, name: true } });
      if (!supplier) throw new NotFoundException('Supplier not found');
      const currency = dto.currency.toUpperCase();
      const amount = money(dto.amount);
      if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
      const known = await tx.currency.findUnique({ where: { code: currency }, select: { code: true } });
      const sar = dto.sarAmount
        ? money(dto.sarAmount)
        : known
          ? toBase(amount, currency, await this.currencies.rate(currency, tx))
          : null;
      if (!sar || !sar.gt(0)) throw new BadRequestException(`Enter what ${currency} ${amount.toFixed()} is worth in SAR`);
      const weOwe = dto.direction === 'WE_OWE';
      const entry = await this.ledger.post(tx, {
        description: `Opening balance of ${supplier.name}: ${amount.toFixed()} ${currency} ${weOwe ? 'owed to the supplier' : 'owed by the supplier'}${dto.note ? ` — ${dto.note}` : ''}`,
        sourceType: 'OPENING_BALANCE',
        sourceId: supplier.id,
        userId: user.id,
        lines: weOwe
          ? [
              { account: 'OPENING_BALANCE', debit: sar },
              { account: 'AP', credit: sar, supplierId: supplier.id },
            ]
          : [
              { account: 'AP', debit: sar, supplierId: supplier.id },
              { account: 'OPENING_BALANCE', credit: sar },
            ],
      });
      await tx.supplierAccountEntry.create({
        data: {
          supplierId: supplier.id,
          currency,
          amount: weOwe ? amount : amount.neg(),
          sourceType: 'OPENING',
          sourceId: entry.id,
          note: ['رصيد افتتاحي', dto.note].filter(Boolean).join(' — '),
        },
      });
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'SupplierOpeningBalance', entityId: entry.id, after: { supplierId, ...dto } }, tx);
    });
    return this.account(supplierId);
  }

  voucher(id: string) {
    return this.prisma.supplierVoucher.findUniqueOrThrow({ where: { id }, include: { supplier: { select: { id: true, name: true } } } });
  }

  // -------------------------------------------------------------- inside ---

  private async payIn(tx: Tx, dto: SupplierPaymentVoucherDto, user: User) {
    const supplier = await tx.supplier.findUnique({ where: { id: dto.supplierId } });
    if (!supplier) throw new NotFoundException('Supplier not found');
    const box = await tx.cashBox.findUnique({ where: { id: dto.cashBoxId } });
    if (!box || !box.isActive) throw new NotFoundException('Cash box not found');
    if (box.currency !== 'USD') throw new UnprocessableEntityException(`${box.name} holds ${box.currency}; pay the supplier from a dollar box`);
    const usd = inCurrency(dto.usdAmount, 'USD');
    if (!usd.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const goods = (dto.applyTo ?? 'GOODS') === 'GOODS';
    const rate = dto.foreignPerUsd ? dec(dto.foreignPerUsd) : null;
    if (goods && !rate?.gt(0)) throw new BadRequestException('Enter the yuan rate of the dollar for this payment');
    const held = await this.treasury.balanceOf(tx, { boxId: box.id });
    if (held.amount.lt(usd)) throw new UnprocessableEntityException(`Not enough money in ${box.name} (${held.amount.toFixed()} USD)`);
    const currency = goods ? (dto.foreignCurrency ?? 'CNY') : 'USD';
    const credited = goods ? money(usd.mul(rate!)) : usd;

    const number = await this.sequences.next(tx, 'SV');
    const voucher = await tx.supplierVoucher.create({
      data: {
        number,
        supplierId: supplier.id,
        kind: SupplierVoucherKind.PAYMENT,
        usdAmount: usd,
        foreignCurrency: goods ? currency : null,
        foreignPerUsd: goods ? rate : null,
        foreignAmount: goods ? credited : null,
        cashBoxId: box.id,
        note: dto.note,
        createdById: user.id,
      },
    });
    const sar = toBase(usd, 'USD', await this.currencies.rate('USD', tx));
    const out = await this.treasury.moneyLines(tx, { account: { id: box.accountId }, currency: 'USD', amount: usd.neg(), sar: sar.neg() });
    // What left the box (at its book value) is what the payable goes down by.
    const booked = out.reduce((t, l) => t.add(dec(l.credit ?? 0)).sub(dec(l.debit ?? 0)), dec(0));
    const entry = await this.ledger.post(tx, {
      description: `Payment voucher ${number} to ${supplier.name}: ${usd.toFixed()} USD${goods ? ` at ${rate!.toFixed()} ${currency}` : ' (commission)'}`,
      sourceType: 'SUPPLIER_PAYMENT',
      sourceId: voucher.id,
      userId: user.id,
      lines: [...out, { account: 'AP', debit: money(booked), supplierId: supplier.id }],
    });
    await tx.supplierVoucher.update({ where: { id: voucher.id }, data: { journalEntryId: entry.id } });
    await tx.supplierAccountEntry.create({
      data: {
        supplierId: supplier.id,
        currency,
        amount: credited.neg(),
        sourceType: 'VOUCHER',
        sourceId: voucher.id,
        note: `سند صرف ${number}: ${usd.toFixed()} $${goods ? ` × ${rate!.toFixed()}` : ' (عمولة)'}${dto.note ? ` — ${dto.note}` : ''}`,
      },
    });
    await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'SupplierVoucher', entityId: voucher.id, after: dto }, tx);
    return voucher.id;
  }

  private async commissionIn(tx: Tx, dto: SupplierCommissionDto, user: User) {
    const supplier = await tx.supplier.findUnique({ where: { id: dto.supplierId } });
    if (!supplier) throw new NotFoundException('Supplier not found');
    const usd = inCurrency(dto.usdAmount, 'USD');
    if (!usd.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    const from = parseDateOnly(dto.periodFrom);
    const to = parseDateOnly(dto.periodTo);
    if (to < from) throw new BadRequestException('The period ends before it starts');
    const number = await this.sequences.next(tx, 'SV');
    const voucher = await tx.supplierVoucher.create({
      data: { number, supplierId: supplier.id, kind: SupplierVoucherKind.COMMISSION, usdAmount: usd, periodFrom: from, periodTo: to, note: dto.note, createdById: user.id },
    });
    const sar = toBase(usd, 'USD', await this.currencies.rate('USD', tx));
    const span = `${dto.periodFrom.slice(0, 10)} → ${dto.periodTo.slice(0, 10)}`;
    const entry = await this.ledger.post(tx, {
      description: `Commission ${number} of ${supplier.name}: ${usd.toFixed()} USD for ${span}`,
      sourceType: 'SUPPLIER_PAYMENT',
      sourceId: voucher.id,
      userId: user.id,
      lines: [
        { account: 'SUPPLIER_COMMISSION', debit: sar },
        { account: 'AP', credit: sar, supplierId: supplier.id },
      ],
    });
    await tx.supplierVoucher.update({ where: { id: voucher.id }, data: { journalEntryId: entry.id } });
    await tx.supplierAccountEntry.create({
      data: { supplierId: supplier.id, currency: 'USD', amount: usd, sourceType: 'VOUCHER', sourceId: voucher.id, note: `عمولة ${number} من ${span}${dto.note ? ` — ${dto.note}` : ''}` },
    });
    await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'SupplierVoucher', entityId: voucher.id, after: dto }, tx);
    return voucher.id;
  }

  private async cancelIn(tx: Tx, id: string, user: User) {
    await tx.$queryRaw`SELECT id FROM supplier_vouchers WHERE id = ${id}::uuid FOR UPDATE`;
    const v = await tx.supplierVoucher.findUnique({ where: { id } });
    if (!v) throw new NotFoundException('Voucher not found');
    if (v.cancelledAt) throw new UnprocessableEntityException('This voucher was already deleted');
    if (v.journalEntryId) await this.ledger.reverse(tx, v.journalEntryId, user.id, `Voucher ${v.number} deleted`);
    await tx.supplierAccountEntry.deleteMany({ where: { sourceId: id } });
    const cancelled = await tx.supplierVoucher.update({ where: { id }, data: { cancelledAt: new Date() } });
    await this.audit.log({ userId: user.id, action: 'CANCEL', entityType: 'SupplierVoucher', entityId: id }, tx);
    return cancelled;
  }
}
