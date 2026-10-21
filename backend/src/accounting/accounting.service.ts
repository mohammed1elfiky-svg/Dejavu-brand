import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { JournalEntryStatus, NormalBalance, Prisma } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import {
  AccountsQueryDto,
  CreateAccountDto,
  DateRangeQueryDto,
  JournalQueryDto,
  ManualJournalEntryDto,
  UpdateAccountDto,
} from './dto/accounting.dto';
import { LedgerService } from './ledger.service';

const entryInclude = {
  lines: {
    orderBy: { lineNo: 'asc' },
    include: { account: { select: { id: true, code: true, nameAr: true, nameEn: true } } },
  },
  createdBy: { select: { id: true, name: true } },
  postedBy: { select: { id: true, name: true } },
} satisfies Prisma.JournalEntryInclude;

@Injectable()
export class AccountingService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  // ------------------------------------------------------------ accounts ---

  listAccounts(q: AccountsQueryDto) {
    return this.prisma.account.findMany({
      where: { type: q.type, isCashOrBank: q.cashOrBank || undefined },
      orderBy: { code: 'asc' },
    });
  }

  async createAccount(dto: CreateAccountDto, userId: string) {
    const parent = await this.prisma.account.findUnique({ where: { id: dto.parentId } });
    if (!parent) throw new NotFoundException('Parent account not found');
    if (parent.isPostable) {
      throw new BadRequestException('Parent must be a group account (accounts with entries cannot have children)');
    }
    if (!dto.code.startsWith(parent.code)) {
      throw new BadRequestException(`Account code must start with the parent code ${parent.code}`);
    }
    const account = await this.prisma.account.create({
      data: {
        code: dto.code,
        nameAr: dto.nameAr,
        nameEn: dto.nameEn,
        parentId: parent.id,
        type: parent.type,
        normalBalance: parent.normalBalance,
        level: parent.level + 1,
        isPostable: dto.isPostable ?? true,
        isCashOrBank: dto.isCashOrBank ?? false,
        description: dto.description,
      },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Account', entityId: account.id, after: account });
    return account;
  }

  async updateAccount(id: string, dto: UpdateAccountDto, userId: string) {
    const before = await this.prisma.account.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Account not found');
    if (dto.isActive === false && before.systemKey) {
      throw new BadRequestException(`System account ${before.systemKey} cannot be deactivated`);
    }
    const account = await this.prisma.account.update({ where: { id }, data: dto });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Account', entityId: id, before, after: account });
    return account;
  }

  // ------------------------------------------------------ journal entries ---

  async listEntries(q: JournalQueryDto) {
    const where: Prisma.JournalEntryWhereInput = {
      status: q.status,
      sourceType: q.sourceType,
      sourceId: q.sourceId,
      entryDate: { gte: q.from ? parseDateOnly(q.from) : undefined, lte: q.to ? parseDateOnly(q.to) : undefined },
    };
    const [items, total] = await Promise.all([
      this.prisma.journalEntry.findMany({
        where,
        orderBy: [{ entryDate: 'desc' }, { number: 'desc' }],
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.journalEntry.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async getEntry(id: string) {
    const entry = await this.prisma.journalEntry.findUnique({ where: { id }, include: entryInclude });
    if (!entry) throw new NotFoundException('Journal entry not found');
    return entry;
  }

  async createManualEntry(dto: ManualJournalEntryDto, userId: string) {
    const entryDate = parseDateOnly(dto.entryDate);
    const lines = this.normalizeLines(dto);
    await this.assertPostableAccounts(lines.map((l) => l.accountId));
    const id = await this.prisma.$transaction(async (tx) => {
      const period = await this.ledger.openPeriodFor(tx, entryDate);
      const entry = await tx.journalEntry.create({
        data: {
          number: await this.sequences.next(tx, 'JE', { year: entryDate.getUTCFullYear() }),
          entryDate,
          periodId: period.id,
          description: dto.description,
          sourceType: 'MANUAL',
          createdById: userId,
          lines: { create: lines },
        },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'JournalEntry', entityId: entry.id, after: dto }, tx);
      return entry.id;
    });
    return this.getEntry(id);
  }

  async updateDraft(id: string, dto: ManualJournalEntryDto, userId: string) {
    const entryDate = parseDateOnly(dto.entryDate);
    const lines = this.normalizeLines(dto);
    await this.assertPostableAccounts(lines.map((l) => l.accountId));
    await this.prisma.$transaction(async (tx) => {
      const entry = await tx.journalEntry.findUnique({ where: { id } });
      if (!entry) throw new NotFoundException('Journal entry not found');
      if (entry.status !== JournalEntryStatus.DRAFT) throw new BadRequestException('Only draft entries can be edited');
      if (entry.sourceType !== 'MANUAL') throw new BadRequestException('System-generated entries cannot be edited');
      const period = await this.ledger.openPeriodFor(tx, entryDate);
      await tx.journalLine.deleteMany({ where: { journalEntryId: id } });
      await tx.journalEntry.update({
        where: { id },
        data: { entryDate, periodId: period.id, description: dto.description, lines: { create: lines } },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'JournalEntry', entityId: id, after: dto }, tx);
    });
    return this.getEntry(id);
  }

  async deleteDraft(id: string, userId: string) {
    const entry = await this.prisma.journalEntry.findUnique({ where: { id } });
    if (!entry) throw new NotFoundException('Journal entry not found');
    if (entry.status !== JournalEntryStatus.DRAFT) throw new BadRequestException('Only draft entries can be deleted');
    await this.prisma.journalEntry.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'JournalEntry', entityId: id, before: entry });
  }

  async postEntry(id: string, userId: string) {
    await this.prisma.$transaction((tx) => this.ledger.postDraft(tx, id, userId));
    return this.getEntry(id);
  }

  async reverseEntry(id: string, reason: string, userId: string) {
    const reversal = await this.prisma.$transaction((tx) => this.ledger.reverse(tx, id, userId, reason));
    return this.getEntry(reversal.id);
  }

  private async assertPostableAccounts(ids: string[]) {
    const rows = await this.prisma.account.findMany({ where: { id: { in: ids } } });
    for (const id of new Set(ids)) {
      const acc = rows.find((r) => r.id === id);
      if (!acc) throw new NotFoundException(`Account ${id} not found`);
      if (!acc.isPostable || !acc.isActive) {
        throw new UnprocessableEntityException(`Account ${acc.code} (${acc.nameEn}) is a group or inactive account`);
      }
    }
  }

  private normalizeLines(dto: ManualJournalEntryDto) {
    return dto.lines.map((l, i) => {
      const debit = money(l.debit ?? 0);
      const credit = money(l.credit ?? 0);
      if (debit.isZero() === credit.isZero()) {
        throw new BadRequestException(`Line ${i + 1}: enter either a debit or a credit amount`);
      }
      return {
        lineNo: i + 1,
        accountId: l.accountId,
        debit,
        credit,
        description: l.description,
        customerId: l.customerId,
        supplierId: l.supplierId,
      };
    });
  }

  // -------------------------------------------------------------- reports ---

  async trialBalance(q: DateRangeQueryDto) {
    const from = q.from ? parseDateOnly(q.from) : null;
    const to = q.to ? parseDateOnly(q.to) : null;
    const rows = await this.prisma.$queryRaw<
      { id: string; code: string; name_ar: string; name_en: string; type: string; normal_balance: NormalBalance; debit: string; credit: string }[]
    >`
      SELECT a.id, a.code, a.name_ar, a.name_en, a.type::text, a.normal_balance,
             COALESCE(SUM(l.debit), 0)::text AS debit, COALESCE(SUM(l.credit), 0)::text AS credit
      FROM accounts a
      LEFT JOIN (
        journal_lines l
        JOIN journal_entries e ON e.id = l.journal_entry_id
         AND e.status IN ('POSTED', 'REVERSED')
         AND (${from}::date IS NULL OR e.entry_date >= ${from}::date)
         AND (${to}::date IS NULL OR e.entry_date <= ${to}::date)
      ) ON l.account_id = a.id
      WHERE a.is_postable
      GROUP BY a.id
      HAVING COALESCE(SUM(l.debit), 0) <> 0 OR COALESCE(SUM(l.credit), 0) <> 0
      ORDER BY a.code`;

    const accounts = rows.map((r) => {
      const net = dec(r.debit).sub(dec(r.credit));
      return {
        accountId: r.id,
        code: r.code,
        nameAr: r.name_ar,
        nameEn: r.name_en,
        type: r.type,
        totalDebit: money(r.debit),
        totalCredit: money(r.credit),
        // Closing balance shown on its natural side.
        debitBalance: net.gt(0) ? money(net) : money(0),
        creditBalance: net.lt(0) ? money(net.neg()) : money(0),
      };
    });
    const totals = accounts.reduce(
      (t, a) => ({ debit: t.debit.add(a.debitBalance), credit: t.credit.add(a.creditBalance) }),
      { debit: dec(0), credit: dec(0) },
    );
    return {
      from: q.from ?? null,
      to: q.to ?? null,
      accounts,
      totals: { debit: money(totals.debit), credit: money(totals.credit), balanced: totals.debit.equals(totals.credit) },
    };
  }

  /**
   * Statement for one customer (AR + advances, positive = they owe us) or one
   * supplier (AP, positive = we owe them), built from tagged journal lines.
   */
  async partyStatement(party: { customerId: string } | { supplierId: string }, q: DateRangeQueryDto) {
    const isCustomer = 'customerId' in party;
    const keys = isCustomer ? ['AR', 'CUSTOMER_ADVANCES'] : ['AP'];
    const sign = isCustomer ? 1 : -1;
    const from = q.from ? parseDateOnly(q.from) : undefined;
    const to = q.to ? parseDateOnly(q.to) : undefined;
    const base: Prisma.JournalLineWhereInput = {
      ...party,
      account: { systemKey: { in: keys } },
      journalEntry: { status: { in: [JournalEntryStatus.POSTED, JournalEntryStatus.REVERSED] } },
    };

    const opening = from
      ? await this.prisma.journalLine.aggregate({
          where: { ...base, journalEntry: { ...(base.journalEntry as object), entryDate: { lt: from } } },
          _sum: { debit: true, credit: true },
        })
      : null;
    let balance = opening ? dec(opening._sum.debit).sub(dec(opening._sum.credit)).mul(sign) : dec(0);
    const openingBalance = money(balance);

    const lines = await this.prisma.journalLine.findMany({
      where: { ...base, journalEntry: { ...(base.journalEntry as object), entryDate: { gte: from, lte: to } } },
      include: {
        account: { select: { code: true, systemKey: true } },
        journalEntry: { select: { id: true, number: true, entryDate: true, description: true, sourceType: true, sourceId: true } },
      },
      orderBy: [{ journalEntry: { entryDate: 'asc' } }, { journalEntry: { number: 'asc' } }, { lineNo: 'asc' }],
    });
    return {
      openingBalance,
      lines: lines.map((l) => {
        balance = balance.add(dec(l.debit).sub(dec(l.credit)).mul(sign));
        return {
          entryId: l.journalEntry.id,
          number: l.journalEntry.number,
          date: l.journalEntry.entryDate,
          description: l.description ?? l.journalEntry.description,
          account: l.account.systemKey,
          sourceType: l.journalEntry.sourceType,
          sourceId: l.journalEntry.sourceId,
          debit: l.debit,
          credit: l.credit,
          balance: money(balance),
        };
      }),
      closingBalance: money(balance),
    };
  }

  /** Account statement with opening balance and running balance on the normal side. */
  async accountLedger(accountId: string, q: DateRangeQueryDto) {
    const account = await this.prisma.account.findUnique({ where: { id: accountId } });
    if (!account) throw new NotFoundException('Account not found');
    const from = q.from ? parseDateOnly(q.from) : undefined;
    const to = q.to ? parseDateOnly(q.to) : undefined;
    const posted = { status: { in: [JournalEntryStatus.POSTED, JournalEntryStatus.REVERSED] } };
    const sign = account.normalBalance === NormalBalance.DEBIT ? 1 : -1;

    const opening = from
      ? await this.prisma.journalLine.aggregate({
          where: { accountId, journalEntry: { ...posted, entryDate: { lt: from } } },
          _sum: { debit: true, credit: true },
        })
      : null;
    let balance = opening ? dec(opening._sum.debit).sub(dec(opening._sum.credit)).mul(sign) : dec(0);
    const openingBalance = money(balance);

    const lines = await this.prisma.journalLine.findMany({
      where: { accountId, journalEntry: { ...posted, entryDate: { gte: from, lte: to } } },
      include: { journalEntry: { select: { id: true, number: true, entryDate: true, description: true, sourceType: true, sourceId: true } } },
      orderBy: [{ journalEntry: { entryDate: 'asc' } }, { journalEntry: { number: 'asc' } }, { lineNo: 'asc' }],
    });

    return {
      account,
      openingBalance,
      lines: lines.map((l) => {
        balance = balance.add(dec(l.debit).sub(dec(l.credit)).mul(sign));
        return {
          entryId: l.journalEntry.id,
          number: l.journalEntry.number,
          date: l.journalEntry.entryDate,
          description: l.description ?? l.journalEntry.description,
          sourceType: l.journalEntry.sourceType,
          sourceId: l.journalEntry.sourceId,
          debit: l.debit,
          credit: l.credit,
          balance: money(balance),
        };
      }),
      closingBalance: money(balance),
    };
  }
}
