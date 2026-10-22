import { BadRequestException, ForbiddenException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { CashBoxKind, Prisma } from '../generated/prisma/client';
import { LedgerService, PostingLine, SystemAccount } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, inCurrency, toBase } from '../common/currency.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { Decimal, dec, money, ZERO } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';

/** Something that holds money in one currency: a cash box, or one customer's account in one currency. */
export type Holder = { boxId: string } | { customerId: string; currency: string };

/** Chart-of-accounts groups the treasury opens accounts under (seeded). */
const GROUP_FOR_KIND: Record<CashBoxKind, string> = { CASHIER: '1140', VAULT: '1150', BANK: '1150' };

const boxSelect = {
  id: true,
  name: true,
  kind: true,
  currency: true,
  isActive: true,
  icon: true,
  imageUrl: true,
  createdAt: true,
  user: { select: { id: true, name: true } },
  account: { select: { id: true, code: true } },
} satisfies Prisma.CashBoxSelect;

/**
 * Cash boxes, safes and bank accounts per currency, and the book (SAR) value of the
 * money they hold. Foreign money is carried at what it cost: taking some out relieves
 * its average book value, and the gap to what it is worth in the deal at hand is a
 * realised exchange difference.
 */
@Injectable()
export class TreasuryService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly currencies: CurrencyService,
  ) {}

  // -------------------------------------------------------------- balances ---

  /** Running balance in the holder's currency and its SAR book value. Locks the holder. */
  async balanceOf(tx: Tx, holder: Holder): Promise<{ amount: Decimal; base: Decimal }> {
    if ('boxId' in holder) {
      await tx.$queryRaw`SELECT id FROM cash_boxes WHERE id = ${holder.boxId}::uuid FOR UPDATE`;
      const agg = await tx.cashBoxEntry.aggregate({ where: { cashBoxId: holder.boxId }, _sum: { amount: true, baseAmount: true } });
      return { amount: dec(agg._sum.amount), base: dec(agg._sum.baseAmount) };
    }
    await tx.$queryRaw`SELECT id FROM customers WHERE id = ${holder.customerId}::uuid FOR UPDATE`;
    const agg = await tx.customerAccountEntry.aggregate({
      where: { customerId: holder.customerId, currency: holder.currency },
      _sum: { amount: true, baseAmount: true },
    });
    return { amount: dec(agg._sum.amount), base: dec(agg._sum.baseAmount) };
  }

  /**
   * SAR book value of moving [delta] (signed, in [currency]) through [holder] when the
   * deal values it at [sarAtRate]. Reducing a balance relieves its average book value;
   * anything beyond the balance (or any increase) is booked at the deal's value.
   */
  async bookValue(tx: Tx, holder: Holder, currency: string, delta: Decimal, sarAtRate: Decimal): Promise<Decimal> {
    if (currency === BASE_CURRENCY) return money(delta);
    const bal = await this.balanceOf(tx, holder);
    if (bal.amount.isZero() || bal.amount.gt(0) === delta.gt(0) || delta.isZero()) return money(sarAtRate);
    const portion = Decimal.min(delta.abs(), bal.amount.abs());
    const relieved = bal.base.neg().mul(portion).div(bal.amount.abs());
    const rest = delta.abs().sub(portion);
    const restSar = rest.isZero() ? ZERO : sarAtRate.mul(rest).div(delta.abs());
    return money(relieved.add(restSar));
  }

  /**
   * Journal lines for money moving through [account]: a cash box, the customer
   * accounts ledger, or any other account. [amount] is signed (+ in / customer owes
   * more) in [currency]; [sar] is what the other side of the entry books for it.
   * Adds an exchange-difference line when the holder's book value differs.
   */
  async moneyLines(
    tx: Tx,
    m: { account: SystemAccount | { id: string }; customerId?: string; currency: string; amount: Decimal; sar: Decimal; description?: string },
  ): Promise<PostingLine[]> {
    const accountId =
      typeof m.account === 'string'
        ? (await tx.account.findUniqueOrThrow({ where: { systemKey: m.account }, select: { id: true } })).id
        : m.account.id;
    const [box, account] = await Promise.all([
      tx.cashBox.findUnique({ where: { accountId } }),
      tx.account.findUniqueOrThrow({ where: { id: accountId }, select: { systemKey: true } }),
    ]);
    const isCustomer = account.systemKey === 'CUSTOMER_ACCOUNTS';
    const side = (v: Decimal) => (v.gt(0) ? { debit: v } : { credit: v.neg() });
    if (!box && !isCustomer) return [{ account: { id: accountId }, ...side(m.sar), description: m.description }];
    if (box && box.currency !== m.currency) {
      throw new UnprocessableEntityException(`${box.name} holds ${box.currency}, not ${m.currency}`);
    }
    if (isCustomer && !m.customerId) throw new BadRequestException('A customer is required');

    const holder: Holder = box ? { boxId: box.id } : { customerId: m.customerId!, currency: m.currency };
    const booked = await this.bookValue(tx, holder, m.currency, m.amount, m.sar);
    const lines: PostingLine[] = [
      {
        account: { id: accountId },
        ...side(booked),
        customerId: m.customerId,
        description: m.description,
        holder: { currency: m.currency, amount: m.amount.abs() },
      },
    ];
    const diff = money(m.sar.sub(booked));
    if (!diff.isZero()) lines.push({ account: 'FX_DIFFERENCE', ...side(diff), description: 'Exchange difference' });
    return lines;
  }

  // ----------------------------------------------------------------- boxes ---

  /** The cashier's own drawers, one per active currency (opened on first use). */
  async ensureCashierBoxes(userId: string) {
    const [user, active, existing] = await Promise.all([
      this.prisma.user.findUniqueOrThrow({ where: { id: userId }, select: { name: true } }),
      this.prisma.currency.findMany({ where: { isActive: true }, orderBy: { sortOrder: 'asc' } }),
      this.prisma.cashBox.findMany({ where: { userId }, select: { currency: true } }),
    ]);
    for (const c of active.filter((c) => !existing.some((e) => e.currency === c.code))) {
      await this.prisma.$transaction((tx) =>
        this.openBox(tx, { name: `صندوق ${user.name} - ${c.nameAr}`, kind: CashBoxKind.CASHIER, currency: c.code, userId }),
      );
    }
    return this.listBoxes(this.mineWhere(userId));
  }

  /** The staff member's own drawers plus the boxes they were assigned. */
  mineWhere(userId: string): Prisma.CashBoxWhereInput {
    return { OR: [{ userId }, { access: { some: { userId } } }] };
  }

  /** Safes and banks a staff member may hand money to: their assigned ones, or all when none are assigned. */
  async safesFor(userId: string) {
    const assigned = await this.prisma.cashBoxAccess.count({ where: { userId, cashBox: { kind: { not: CashBoxKind.CASHIER } } } });
    return this.listBoxes({
      kind: { not: CashBoxKind.CASHIER },
      isActive: true,
      ...(assigned ? { access: { some: { userId } } } : {}),
    });
  }

  async createBox(dto: { name: string; kind: CashBoxKind; currency: string; icon?: string | null; imageUrl?: string | null }, userId: string) {
    if (dto.kind === CashBoxKind.CASHIER) throw new BadRequestException('Cashier drawers are opened automatically');
    await this.currencies.requireActive(dto.currency);
    const box = await this.prisma.$transaction(async (tx) => {
      const b = await this.openBox(tx, dto);
      return dto.icon || dto.imageUrl ? tx.cashBox.update({ where: { id: b.id }, data: { icon: dto.icon, imageUrl: dto.imageUrl } }) : b;
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'CashBox', entityId: box.id, after: dto });
    return (await this.listBoxes({ id: box.id }))[0];
  }

  async updateBox(id: string, dto: { name?: string; isActive?: boolean; icon?: string | null; imageUrl?: string | null }, userId: string) {
    const box = await this.prisma.cashBox.update({ where: { id }, data: dto });
    await this.prisma.account.update({ where: { id: box.accountId }, data: { nameAr: box.name, nameEn: box.name } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'CashBox', entityId: id, after: dto });
    return (await this.listBoxes({ id }))[0];
  }

  /** Opens a box with its own GL account under the treasury group of its kind. */
  async openBox(tx: Tx, dto: { name: string; kind: CashBoxKind; currency: string; userId?: string }) {
    const group = await tx.account.findUnique({ where: { code: GROUP_FOR_KIND[dto.kind] } });
    if (!group) throw new UnprocessableEntityException(`Account group ${GROUP_FOR_KIND[dto.kind]} is missing; run the seed`);
    const siblings = await tx.account.findMany({ where: { parentId: group.id }, select: { code: true } });
    const next = siblings.reduce((n, a) => Math.max(n, Number(a.code.slice(group.code.length)) || 0), 0) + 1;
    const account = await tx.account.create({
      data: {
        code: `${group.code}${String(next).padStart(3, '0')}`,
        nameAr: dto.name,
        nameEn: dto.name,
        type: group.type,
        normalBalance: group.normalBalance,
        parentId: group.id,
        level: group.level + 1,
        isPostable: true,
        isCashOrBank: true,
      },
    });
    return tx.cashBox.create({ data: { name: dto.name, kind: dto.kind, currency: dto.currency, userId: dto.userId, accountId: account.id } });
  }

  /** Boxes with their live balances. */
  async listBoxes(where: Prisma.CashBoxWhereInput = {}) {
    const boxes = await this.prisma.cashBox.findMany({
      where,
      select: boxSelect,
      orderBy: [{ kind: 'asc' }, { name: 'asc' }],
    });
    const sums = await this.prisma.cashBoxEntry.groupBy({
      by: ['cashBoxId'],
      where: { cashBoxId: { in: boxes.map((b) => b.id) } },
      _sum: { amount: true, baseAmount: true },
    });
    return boxes.map((b) => {
      const s = sums.find((x) => x.cashBoxId === b.id)?._sum;
      return { ...b, balance: inCurrency(dec(s?.amount), b.currency), bookValue: money(dec(s?.baseAmount)) };
    });
  }

  /** A box a staff member may look at: their own drawer, or any with cashboxes.manage. */
  async visibleBox(id: string, user: { id: string; permissions: string[] }) {
    const box = await this.prisma.cashBox.findUnique({ where: { id } });
    if (!box) throw new NotFoundException('Cash box not found');
    if (box.userId !== user.id && !user.permissions.includes('cashboxes.manage') && !(await this.isAssigned(user.id, id))) {
      throw new ForbiddenException('You can only see your own cash box');
    }
    return box;
  }

  private async isAssigned(userId: string, boxId: string) {
    return (await this.prisma.cashBoxAccess.count({ where: { userId, cashBoxId: boxId } })) > 0;
  }

  async boxEntries(id: string, q: PaginationQueryDto) {
    const where = { cashBoxId: id };
    const [items, total] = await Promise.all([
      this.prisma.cashBoxEntry.findMany({ where, orderBy: { createdAt: 'desc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.cashBoxEntry.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  // ------------------------------------------------------------- transfers ---

  /**
   * Moves money between two boxes. Same currency: a withdrawal or deposit. Different
   * currencies: an exchange at the rate implied by the two amounts. Foreign money keeps
   * its cost; selling it for SAR realises the exchange gain or loss.
   */
  async transfer(
    dto: { fromBoxId: string; toBoxId: string; amount: string; toAmount?: string; note?: string },
    user: { id: string; permissions: string[] },
  ) {
    const id = await this.prisma.$transaction((tx) => this.transferIn(tx, dto, user));
    return this.getTransfer(id);
  }

  private async transferIn(
    tx: Tx,
    dto: { fromBoxId: string; toBoxId: string; amount: string; toAmount?: string; note?: string },
    user: { id: string; permissions: string[] },
  ): Promise<string> {
    if (dto.fromBoxId === dto.toBoxId) throw new BadRequestException('Pick two different boxes');
    {
      const [from, to] = await Promise.all([
        tx.cashBox.findUnique({ where: { id: dto.fromBoxId } }),
        tx.cashBox.findUnique({ where: { id: dto.toBoxId } }),
      ]);
      if (!from || !to) throw new NotFoundException('Cash box not found');
      if (!from.isActive || !to.isActive) throw new UnprocessableEntityException('Both boxes must be active');
      const manager = user.permissions.includes('cashboxes.manage');
      if (!manager) {
        const mayTake = from.userId === user.id || (await this.isAssigned(user.id, from.id));
        const mayGive = to.kind !== CashBoxKind.CASHIER && (await this.safesFor(user.id)).some((b) => b.id === to.id);
        if (!mayTake || !mayGive) throw new ForbiddenException('You can only hand over money from your own boxes to a safe you may use');
      }

      const amount = inCurrency(dto.amount, from.currency);
      if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
      // Lock in a stable order; balanceOf takes the row lock.
      const [first, second] = [from, to].sort((a, b) => a.id.localeCompare(b.id));
      const balances = { [first.id]: await this.balanceOf(tx, { boxId: first.id }), [second.id]: await this.balanceOf(tx, { boxId: second.id }) };
      if (balances[from.id].amount.lt(amount)) {
        throw new UnprocessableEntityException(`Not enough money in ${from.name} (${balances[from.id].amount.toFixed()} ${from.currency})`);
      }

      const sameCurrency = from.currency === to.currency;
      if (!sameCurrency && !dto.toAmount) throw new BadRequestException('Enter the amount received in the other currency');
      const toAmount = sameCurrency ? amount : inCurrency(dto.toAmount!, to.currency);
      if (!toAmount.gt(0)) throw new BadRequestException('Received amount must be greater than zero');

      const going = toBase(amount, from.currency, await this.currencies.rate(from.currency, tx));
      const out = await this.bookValue(tx, { boxId: from.id }, from.currency, amount.neg(), going.neg());
      const baseOut = out.neg();
      // What came in is worth its market value (SAR at face): paying more or less than
      // that for it is an exchange loss or gain, booked to profit and loss right away.
      const baseIn = sameCurrency ? baseOut : toBase(toAmount, to.currency, await this.currencies.rate(to.currency, tx));
      const fx = money(baseIn.sub(baseOut));

      const number = await this.sequences.next(tx, 'TRF');
      const transfer = await tx.cashTransfer.create({
        data: {
          number,
          fromBoxId: from.id,
          toBoxId: to.id,
          fromAmount: amount,
          toAmount,
          baseAmount: baseOut,
          fxDifference: fx,
          note: dto.note,
          createdById: user.id,
        },
      });
      await this.ledger.post(tx, {
        description: sameCurrency
          ? `Transfer ${number}: ${from.name} → ${to.name}`
          : `Exchange ${number}: ${amount.toFixed()} ${from.currency} → ${toAmount.toFixed()} ${to.currency}`,
        sourceType: 'CASH_TRANSFER',
        sourceId: transfer.id,
        userId: user.id,
        lines: [
          { account: { id: to.accountId }, debit: baseIn, holder: { currency: to.currency, amount: toAmount } },
          { account: { id: from.accountId }, credit: baseOut, holder: { currency: from.currency, amount } },
          fx.gt(0) ? { account: 'FX_DIFFERENCE', credit: fx } : { account: 'FX_DIFFERENCE', debit: fx.neg() },
        ],
      });
      await this.audit.log({ userId: user.id, action: 'CREATE', entityType: 'CashTransfer', entityId: transfer.id, after: transfer }, tx);
      return transfer.id;
    }
  }

  /**
   * Cancels a transfer or exchange: its journal entry is reversed, so the money goes back
   * to the source box and any exchange gain or loss is undone. The receiving box must
   * still hold what it received.
   */
  async cancelTransfer(id: string, reason: string | undefined, user: { id: string; permissions: string[] }) {
    await this.prisma.$transaction((tx) => this.cancelIn(tx, id, reason ?? 'Cancelled', user));
    return this.getTransfer(id);
  }

  /** Edits a transfer: cancels it and records the corrected one in the same step. */
  async editTransfer(
    id: string,
    dto: { fromBoxId: string; toBoxId: string; amount: string; toAmount?: string; note?: string },
    user: { id: string; permissions: string[] },
  ) {
    const newId = await this.prisma.$transaction(async (tx) => {
      const old = await this.cancelIn(tx, id, 'Edited', user);
      const created = await this.transferIn(tx, dto, user);
      await tx.cashTransfer.update({ where: { id: old.id }, data: { replacedById: created } });
      return created;
    });
    return this.getTransfer(newId);
  }

  private async cancelIn(tx: Tx, id: string, reason: string, user: { id: string; permissions: string[] }) {
    await tx.$queryRaw`SELECT id FROM cash_transfers WHERE id = ${id}::uuid FOR UPDATE`;
    const t = await tx.cashTransfer.findUnique({ where: { id }, include: { toBox: true } });
    if (!t) throw new NotFoundException('Transfer not found');
    if (t.cancelledAt) throw new UnprocessableEntityException('This transfer was already cancelled');
    const held = await this.balanceOf(tx, { boxId: t.toBoxId });
    if (held.amount.lt(t.toAmount)) {
      throw new UnprocessableEntityException(`${t.toBox.name} no longer holds the ${dec(t.toAmount).toFixed()} ${t.toBox.currency} it received`);
    }
    const entry = await tx.journalEntry.findFirst({ where: { sourceType: 'CASH_TRANSFER', sourceId: id, status: 'POSTED' } });
    if (!entry) throw new UnprocessableEntityException('The journal entry of this transfer is missing or already reversed');
    await this.ledger.reverse(tx, entry.id, user.id, `${reason}: transfer ${t.number}`);
    const cancelled = await tx.cashTransfer.update({ where: { id }, data: { cancelledAt: new Date(), cancelledById: user.id, cancelReason: reason } });
    await this.audit.log({ userId: user.id, action: 'CANCEL', entityType: 'CashTransfer', entityId: id, after: { reason } }, tx);
    return cancelled;
  }

  getTransfer(id: string) {
    return this.prisma.cashTransfer.findUniqueOrThrow({
      where: { id },
      include: {
        fromBox: { select: { id: true, name: true, currency: true } },
        toBox: { select: { id: true, name: true, currency: true } },
      },
    });
  }

  async listTransfers(q: PaginationQueryDto & { boxId?: string; exchangesOnly?: boolean }) {
    const where: Prisma.CashTransferWhereInput = q.boxId ? { OR: [{ fromBoxId: q.boxId }, { toBoxId: q.boxId }] } : {};
    const rows = await this.prisma.cashTransfer.findMany({
      where,
      include: {
        fromBox: { select: { id: true, name: true, currency: true } },
        toBox: { select: { id: true, name: true, currency: true } },
      },
      orderBy: { createdAt: 'desc' },
      skip: q.skip,
      take: q.pageSize,
    });
    const items = q.exchangesOnly ? rows.filter((t) => t.fromBox.currency !== t.toBox.currency) : rows;
    return paginated(items, await this.prisma.cashTransfer.count({ where }), q);
  }

  /** Where money a staff member takes in [currency] goes: their drawer, else the safe of that currency. */
  async defaultBox(tx: Tx, currency: string, opts: { userId?: string; kind?: CashBoxKind } = {}) {
    const bank = opts.kind === CashBoxKind.BANK;
    if (opts.userId) {
      // A box the staff member was assigned wins; then their own drawer (cash only).
      const assigned = await tx.cashBox.findFirst({
        where: {
          currency,
          isActive: true,
          access: { some: { userId: opts.userId } },
          kind: bank ? CashBoxKind.BANK : { not: CashBoxKind.BANK },
        },
        orderBy: [{ kind: 'asc' }, { createdAt: 'asc' }],
      });
      if (assigned) return assigned;
      if (!bank) {
        const own = await tx.cashBox.findFirst({ where: { userId: opts.userId, currency, isActive: true } });
        if (own) return own;
      }
    }
    const box = await tx.cashBox.findFirst({
      where: { kind: opts.kind ?? CashBoxKind.VAULT, currency, isActive: true },
      orderBy: { createdAt: 'asc' },
    });
    if (!box) throw new UnprocessableEntityException(`No active ${(opts.kind ?? 'VAULT').toLowerCase()} box holds ${currency}`);
    return box;
  }
}
