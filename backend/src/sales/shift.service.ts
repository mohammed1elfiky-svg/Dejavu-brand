import { OwnerAlertsService } from '../notifications/owner-alerts.service';
import { BadRequestException, ForbiddenException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { CashBoxKind, OrderChannel, OrderStatus, ShiftStatus } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { CurrencyService, formatAmount, inCurrency, toBase } from '../common/currency.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { dec, money, sum } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from '../treasury/treasury.service';

type User = { id: string; permissions: string[] };

export interface OpenShiftInput {
  /** Float handed over now, moved from [fromBoxId] (a safe) into the cashier's drawer of that currency. */
  floats?: { currency: string; amount: string; fromBoxId: string }[];
  note?: string;
}

export interface CloseShiftInput {
  /** Cash counted in each drawer. Drawers left out are taken as matching the books. */
  counts: { currency: string; counted: string }[];
  /** Hand the counted cash of each currency over to this box (a safe or bank), optional. */
  handOver?: { currency: string; toBoxId: string }[];
  note?: string;
}

/**
 * Cashier shifts: opened with the float in each drawer, closed with the cash counted.
 * A count that differs from the books posts the difference to cash over & short
 * (and corrects the drawer), so the drawer always matches what is really in it.
 */
@Injectable()
export class ShiftService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly treasury: TreasuryService,
    private readonly ledger: LedgerService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly ownerAlerts: OwnerAlertsService,
  ) {}

  /** The employee's open shift (with live figures), or null. */
  async current(userId: string) {
    const shift = await this.prisma.cashierShift.findFirst({ where: { userId, status: ShiftStatus.OPEN }, select: { id: true } });
    return shift ? this.get(shift.id) : null;
  }

  async open(dto: OpenShiftInput, user: User) {
    await this.treasury.ensureCashierBoxes(user.id);
    const id = await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM users WHERE id = ${user.id}::uuid FOR UPDATE`;
      if (await tx.cashierShift.count({ where: { userId: user.id, status: ShiftStatus.OPEN } })) {
        throw new UnprocessableEntityException('A shift is already open; close it first');
      }
      const drawers = await tx.cashBox.findMany({ where: { userId: user.id, kind: CashBoxKind.CASHIER, isActive: true } });
      const floats = dto.floats ?? [];
      for (const f of floats) {
        const drawer = drawers.find((d) => d.currency === f.currency);
        if (!drawer) throw new BadRequestException(`No ${f.currency} drawer`);
        if (inCurrency(f.amount, f.currency).gt(0)) {
          // The float comes from a safe the cashier works with (the one they hand cash over to).
          const allowed = user.permissions.includes('cashboxes.manage') || (await this.treasury.safesFor(user.id)).some((b) => b.id === f.fromBoxId);
          if (!allowed) throw new ForbiddenException('You may not take money from that box');
          const asManager = { ...user, permissions: [...user.permissions, 'cashboxes.manage'] };
          await this.treasury.transferIn(tx, { fromBoxId: f.fromBoxId, toBoxId: drawer.id, amount: f.amount, note: 'رصيد بداية الوردية' }, asManager);
        }
      }
      const shift = await tx.cashierShift.create({
        data: { number: await this.sequences.next(tx, 'SH'), userId: user.id, note: dto.note },
      });
      for (const d of drawers) {
        const bal = await this.treasury.balanceOf(tx, { boxId: d.id });
        await tx.cashierShiftLine.create({ data: { shiftId: shift.id, cashBoxId: d.id, currency: d.currency, opening: money(bal.amount) } });
      }
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'CashierShift', entityId: shift.id, after: dto }, tx);
      return shift.id;
    });
    return this.get(id);
  }

  async close(dto: CloseShiftInput, user: User, shiftId?: string) {
    let shortSar = dec(0);
    const id = await this.prisma.$transaction(async (tx) => {
      const shift = shiftId
        ? await tx.cashierShift.findUnique({ where: { id: shiftId }, include: { lines: true } })
        : await tx.cashierShift.findFirst({ where: { userId: user.id, status: ShiftStatus.OPEN }, include: { lines: true } });
      if (!shift) throw new NotFoundException('No open shift');
      if (shift.userId !== user.id && !user.permissions.includes('cashboxes.manage')) throw new ForbiddenException('You can only close your own shift');
      await tx.$queryRaw`SELECT id FROM cashier_shifts WHERE id = ${shift.id}::uuid FOR UPDATE`;
      if (shift.status !== ShiftStatus.OPEN) throw new UnprocessableEntityException('This shift is already closed');
      const cashier = await tx.user.findUniqueOrThrow({ where: { id: shift.userId }, select: { name: true } });

      for (const line of shift.lines) {
        const bal = await this.treasury.balanceOf(tx, { boxId: line.cashBoxId });
        const expected = money(bal.amount);
        const given = dto.counts.find((c) => c.currency === line.currency);
        const counted = given ? inCurrency(given.counted, line.currency) : expected;
        if (counted.lt(0)) throw new BadRequestException('Counted cash cannot be negative');
        const difference = money(counted.sub(expected));
        if (!difference.isZero()) {
          const box = await tx.cashBox.findUniqueOrThrow({ where: { id: line.cashBoxId } });
          const sar = toBase(difference.abs(), line.currency, await this.currencies.rate(line.currency, tx));
          const over = difference.gt(0);
          if (!over) shortSar = shortSar.add(sar);
          const moved = await this.treasury.moneyLines(tx, {
            account: { id: box.accountId },
            currency: line.currency,
            amount: difference,
            sar: over ? sar : sar.neg(),
            description: over ? 'زيادة في الصندوق' : 'عجز في الصندوق',
          });
          const booked = moved.reduce((t, l) => t.add(dec(l.debit ?? 0)).sub(dec(l.credit ?? 0)), dec(0));
          await this.ledger.post(tx, {
            description: `Shift ${shift.number} of ${cashier.name}: ${over ? 'over' : 'short'} by ${difference.abs().toFixed()} ${line.currency}`,
            sourceType: 'CASH_TRANSFER',
            sourceId: shift.id,
            userId: user.id,
            lines: [...moved, booked.gt(0) ? { account: 'CASH_OVER_SHORT', credit: money(booked) } : { account: 'CASH_OVER_SHORT', debit: money(booked.neg()) }],
          });
        }
        await tx.cashierShiftLine.update({ where: { id: line.id }, data: { expected, counted, difference } });
        const hand = dto.handOver?.find((h) => h.currency === line.currency);
        if (hand && counted.gt(0)) {
          await this.treasury.transferIn(tx, { fromBoxId: line.cashBoxId, toBoxId: hand.toBoxId, amount: counted.toFixed(), note: `إقفال الوردية ${shift.number}` }, user);
        }
      }
      await tx.cashierShift.update({ where: { id: shift.id }, data: { status: ShiftStatus.CLOSED, closedAt: new Date(), closedById: user.id, note: dto.note ?? shift.note } });
      await this.audit.log({ userId: user.id, action: 'UPDATE', entityType: 'CashierShift', entityId: shift.id, after: { closed: dto } }, tx);
      return shift.id;
    });
    const closed = await this.get(id);
    if (shortSar.gt(0)) {
      const lines = closed.lines.filter((l) => dec(l.difference ?? 0).lt(0)).map((l) => `${dec(l.difference ?? 0).abs().toFixed()} ${l.currency}`);
      void this.ownerAlerts.raise('SHORTAGE', 'عجز في الصندوق ⚠️', `وردية ${closed.number} لـ ${closed.user.name}: عجز ${lines.join(' + ')} (≈ ${formatAmount(shortSar)})`, { shiftId: id }, {
        shortage: shortSar.toNumber(),
      });
    }
    return closed;
  }

  /** Shifts of everyone (managers) or of one employee, newest first. */
  async list(q: PaginationQueryDto & { userId?: string }) {
    const where = q.userId ? { userId: q.userId } : {};
    const [rows, total] = await Promise.all([
      this.prisma.cashierShift.findMany({ where, orderBy: { openedAt: 'desc' }, skip: q.skip, take: q.pageSize, select: { id: true } }),
      this.prisma.cashierShift.count({ where }),
    ]);
    return paginated(await Promise.all(rows.map((r) => this.get(r.id))), total, q);
  }

  /** A shift with its drawers and the sales rung up during it. */
  async get(id: string) {
    const shift = await this.prisma.cashierShift.findUnique({
      where: { id },
      include: { lines: { orderBy: { currency: 'asc' } }, user: { select: { id: true, name: true } } },
    });
    if (!shift) throw new NotFoundException('Shift not found');
    const sales = await this.prisma.order.findMany({
      where: {
        channel: OrderChannel.POS,
        cashierId: shift.userId,
        status: { not: OrderStatus.CANCELLED },
        placedAt: { gte: shift.openedAt, ...(shift.closedAt && { lte: shift.closedAt }) },
      },
      select: { grandTotal: true },
    });
    const lines = await Promise.all(
      shift.lines.map(async (l) => {
        if (shift.status === ShiftStatus.CLOSED) return l;
        const bal = await this.treasury.balanceOf(this.prisma as unknown as Tx, { boxId: l.cashBoxId });
        return { ...l, expected: money(bal.amount) };
      }),
    );
    return { ...shift, lines, salesCount: sales.length, salesTotal: money(sum(sales.map((s) => s.grandTotal))) };
  }

  /** True when the sale (by [cashierId] at [at]) belongs to a shift already closed. */
  async inClosedShift(db: Tx | PrismaService, cashierId: string, at: Date) {
    return (
      (await db.cashierShift.count({ where: { userId: cashierId, status: ShiftStatus.CLOSED, openedAt: { lte: at }, closedAt: { gte: at } } })) > 0
    );
  }
}
