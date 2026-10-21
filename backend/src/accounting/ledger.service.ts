import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JournalEntryStatus, JournalSourceType } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { SequenceService } from '../common/sequence.service';
import { businessDate } from '../common/utils/dates';
import { DecimalLike, money, sum, ZERO } from '../common/utils/money';
import { Tx } from '../prisma/prisma.service';

/** System accounts referenced by posting rules (accounts.system_key). */
export type SystemAccount =
  | 'CASH'
  | 'BANK'
  | 'WALLET'
  | 'GATEWAY_CLEARING'
  | 'COD_RECEIVABLE'
  | 'AR'
  | 'INVENTORY'
  | 'VAT_INPUT'
  | 'AP'
  | 'VAT_OUTPUT'
  | 'CUSTOMER_ADVANCES'
  | 'ACCRUED_EXPENSES'
  | 'CAPITAL'
  | 'RETAINED_EARNINGS'
  | 'OPENING_BALANCE'
  | 'SALES'
  | 'SALES_RETURNS'
  | 'SALES_DISCOUNTS'
  | 'SHIPPING_INCOME'
  | 'COGS'
  | 'INVENTORY_ADJUSTMENT'
  | 'SHIPPING_EXPENSE'
  | 'GATEWAY_FEES';

export interface PostingLine {
  /** Either a system key or a concrete account id. */
  account: SystemAccount | { id: string };
  debit?: DecimalLike;
  credit?: DecimalLike;
  description?: string;
  customerId?: string;
  supplierId?: string;
}

export interface PostingRequest {
  description: string;
  sourceType: JournalSourceType;
  sourceId?: string;
  entryDate?: Date;
  userId?: string | null;
  reversalOfId?: string;
  lines: PostingLine[];
}

/**
 * The only way business modules write to the general ledger.
 * Entries are created as DRAFT and then posted inside the caller's transaction;
 * the database triggers re-check balance, period and account rules on posting.
 */
@Injectable()
export class LedgerService {
  constructor(
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly config: ConfigService,
  ) {}

  today(): Date {
    return businessDate(this.config.getOrThrow<string>('STORE_TIMEZONE'));
  }

  async post(tx: Tx, req: PostingRequest) {
    const lines = req.lines
      .map((l) => ({ ...l, debit: money(l.debit ?? 0), credit: money(l.credit ?? 0) }))
      .filter((l) => !l.debit.isZero() || !l.credit.isZero());

    for (const l of lines) {
      if (l.debit.lt(0) || l.credit.lt(0) || (!l.debit.isZero() && !l.credit.isZero())) {
        throw new BadRequestException('Each journal line must have either a positive debit or a positive credit');
      }
    }
    const totalDebit = sum(lines.map((l) => l.debit));
    const totalCredit = sum(lines.map((l) => l.credit));
    if (lines.length < 2 || totalDebit.isZero() || !totalDebit.equals(totalCredit)) {
      throw new UnprocessableEntityException(
        `Unbalanced journal entry "${req.description}" (debit ${totalDebit}, credit ${totalCredit})`,
      );
    }

    const accountIds = await this.resolveAccounts(tx, lines.map((l) => l.account));
    const entryDate = req.entryDate ?? this.today();
    const period = await this.openPeriodFor(tx, entryDate);

    const entry = await tx.journalEntry.create({
      data: {
        number: await this.sequences.next(tx, 'JE', { year: entryDate.getUTCFullYear() }),
        entryDate,
        periodId: period.id,
        description: req.description,
        sourceType: req.sourceType,
        sourceId: req.sourceId,
        reversalOfId: req.reversalOfId,
        createdById: req.userId ?? null,
        lines: {
          create: lines.map((l, i) => ({
            lineNo: i + 1,
            accountId: accountIds[i],
            debit: l.debit,
            credit: l.credit,
            description: l.description,
            customerId: l.customerId,
            supplierId: l.supplierId,
          })),
        },
      },
    });
    return this.markPosted(tx, entry.id, req.userId ?? null);
  }

  /** Posts an existing DRAFT entry (manual journal entries). */
  async postDraft(tx: Tx, entryId: string, userId: string) {
    const entry = await tx.journalEntry.findUnique({ where: { id: entryId }, include: { lines: true } });
    if (!entry) throw new NotFoundException('Journal entry not found');
    if (entry.status !== JournalEntryStatus.DRAFT) throw new BadRequestException('Only draft entries can be posted');
    const d = sum(entry.lines.map((l) => l.debit));
    const c = sum(entry.lines.map((l) => l.credit));
    if (entry.lines.length < 2 || d.isZero() || !d.equals(c)) {
      throw new UnprocessableEntityException(`Entry ${entry.number} is unbalanced (debit ${d}, credit ${c})`);
    }
    await this.openPeriodFor(tx, entry.entryDate);
    return this.markPosted(tx, entryId, userId);
  }

  /** Creates and posts a mirror entry, then marks the original REVERSED. */
  async reverse(tx: Tx, entryId: string, userId: string | null, reason: string) {
    const original = await tx.journalEntry.findUnique({ where: { id: entryId }, include: { lines: true } });
    if (!original) throw new NotFoundException('Journal entry not found');
    if (original.status !== JournalEntryStatus.POSTED) {
      throw new BadRequestException('Only posted entries can be reversed');
    }

    const reversal = await this.post(tx, {
      description: `Reversal of ${original.number}: ${reason}`,
      sourceType: original.sourceType,
      sourceId: original.sourceId ?? undefined,
      userId,
      reversalOfId: original.id,
      lines: original.lines
        .sort((a, b) => a.lineNo - b.lineNo)
        .map((l) => ({
          account: { id: l.accountId },
          debit: l.credit,
          credit: l.debit,
          description: l.description ?? undefined,
          customerId: l.customerId ?? undefined,
          supplierId: l.supplierId ?? undefined,
        })),
    });
    await tx.journalEntry.update({ where: { id: original.id }, data: { status: JournalEntryStatus.REVERSED } });
    await this.audit.log(
      { userId, action: 'REVERSE', entityType: 'JournalEntry', entityId: original.id, after: { reversalId: reversal.id, reason } },
      tx,
    );
    return reversal;
  }

  async openPeriodFor(tx: Tx, date: Date) {
    const period = await tx.fiscalPeriod.findFirst({
      where: { startDate: { lte: date }, endDate: { gte: date } },
    });
    if (!period) {
      throw new UnprocessableEntityException(`No fiscal period covers ${date.toISOString().slice(0, 10)}`);
    }
    if (period.status !== 'OPEN') {
      throw new UnprocessableEntityException(`Fiscal period ${period.name} is ${period.status.toLowerCase()}`);
    }
    return period;
  }

  private async markPosted(tx: Tx, entryId: string, userId: string | null) {
    // The status-guard trigger recomputes totals and validates the entry here.
    const posted = await tx.journalEntry.update({
      where: { id: entryId },
      data: { status: JournalEntryStatus.POSTED, postedById: userId, postedAt: new Date(), totalDebit: ZERO, totalCredit: ZERO },
      include: { lines: true },
    });
    await this.audit.log({ userId, action: 'POST', entityType: 'JournalEntry', entityId: entryId }, tx);
    return posted;
  }

  private async resolveAccounts(tx: Tx, refs: PostingLine['account'][]): Promise<string[]> {
    const keys = [...new Set(refs.filter((r): r is SystemAccount => typeof r === 'string'))];
    const ids = [...new Set(refs.filter((r): r is { id: string } => typeof r !== 'string').map((r) => r.id))];
    const rows = await tx.account.findMany({
      where: { OR: [{ systemKey: { in: keys } }, { id: { in: ids } }] },
      select: { id: true, systemKey: true, isPostable: true, isActive: true, code: true },
    });

    return refs.map((ref) => {
      const row = typeof ref === 'string' ? rows.find((r) => r.systemKey === ref) : rows.find((r) => r.id === ref.id);
      if (!row) {
        throw new UnprocessableEntityException(
          typeof ref === 'string' ? `System account ${ref} is not configured` : `Account ${ref.id} not found`,
        );
      }
      if (!row.isPostable || !row.isActive) {
        throw new UnprocessableEntityException(`Account ${row.code} is a group or inactive account`);
      }
      return row.id;
    });
  }
}
