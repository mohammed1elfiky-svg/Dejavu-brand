import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { Expense, ExpenseStatus, Prisma } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { dec, money } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import {
  ApproveExpenseDto,
  CreateExpenseCategoryDto,
  ExpenseDto,
  ExpensesQueryDto,
  PayExpenseDto,
} from './dto/expenses.dto';

const expenseInclude = {
  category: { include: { account: { select: { id: true, code: true, nameAr: true, nameEn: true } } } },
  paidFromAccount: { select: { id: true, code: true, nameAr: true, nameEn: true } },
  createdBy: { select: { id: true, name: true } },
  approvedBy: { select: { id: true, name: true } },
} satisfies Prisma.ExpenseInclude;

/**
 * Expense lifecycle:
 *   DRAFT ──approve──▶ PAID      Dr Expense + Dr VAT input — Cr Cash/Bank          (paid on the spot)
 *         ──approve──▶ APPROVED  Dr Expense + Dr VAT input — Cr Accrued expenses   (bill to pay later)
 *   APPROVED ──pay──▶ PAID       Dr Accrued expenses — Cr Cash/Bank
 *   DRAFT/APPROVED ──cancel──▶ CANCELLED (an approved one has its entry reversed)
 */
@Injectable()
export class ExpensesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  // ----------------------------------------------------------- categories ---

  listCategories() {
    return this.prisma.expenseCategory.findMany({
      include: { account: { select: { id: true, code: true, nameAr: true, nameEn: true } } },
      orderBy: { name: 'asc' },
    });
  }

  async createCategory(dto: CreateExpenseCategoryDto, userId: string) {
    const account = await this.prisma.account.findUnique({ where: { id: dto.accountId } });
    if (!account || account.type !== 'EXPENSE' || !account.isPostable || !account.isActive) {
      throw new BadRequestException('Choose an active, postable expense account');
    }
    const category = await this.prisma.expenseCategory.create({ data: dto });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'ExpenseCategory', entityId: category.id, after: category });
    return category;
  }

  // ------------------------------------------------------------- expenses ---

  async list(q: ExpensesQueryDto) {
    const where: Prisma.ExpenseWhereInput = {
      status: q.status,
      categoryId: q.categoryId,
      expenseDate: { gte: q.from ? parseDateOnly(q.from) : undefined, lte: q.to ? parseDateOnly(q.to) : undefined },
    };
    const [items, total, sums] = await Promise.all([
      this.prisma.expense.findMany({
        where,
        include: { category: { select: { name: true } } },
        orderBy: [{ expenseDate: 'desc' }, { number: 'desc' }],
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.expense.count({ where }),
      this.prisma.expense.aggregate({ where: { ...where, status: { in: q.status ? [q.status] : ['APPROVED', 'PAID'] } }, _sum: { amount: true, taxAmount: true } }),
    ]);
    return {
      ...paginated(items, total, q),
      totals: { amount: money(sums._sum.amount ?? 0), taxAmount: money(sums._sum.taxAmount ?? 0) },
    };
  }

  async get(id: string) {
    const expense = await this.prisma.expense.findUnique({ where: { id }, include: expenseInclude });
    if (!expense) throw new NotFoundException('Expense not found');
    const journalEntries = await this.prisma.journalEntry.findMany({
      where: { sourceType: 'EXPENSE', sourceId: id },
      include: { lines: { orderBy: { lineNo: 'asc' }, include: { account: { select: { code: true, nameAr: true } } } } },
      orderBy: { createdAt: 'asc' },
    });
    return { ...expense, total: money(dec(expense.amount).add(expense.taxAmount)), journalEntries };
  }

  async create(dto: ExpenseDto, userId: string) {
    await this.assertCategory(dto.categoryId);
    const id = await this.prisma.$transaction(async (tx) => {
      const expense = await tx.expense.create({
        data: { ...this.fields(dto), number: await this.sequences.next(tx, 'EXP'), createdById: userId },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'Expense', entityId: expense.id, after: dto }, tx);
      return expense.id;
    });
    return this.get(id);
  }

  async update(id: string, dto: ExpenseDto, userId: string) {
    await this.assertCategory(dto.categoryId);
    await this.prisma.$transaction(async (tx) => {
      const expense = await this.lock(tx, id);
      if (expense.status !== ExpenseStatus.DRAFT) throw new BadRequestException('Only draft expenses can be edited');
      await tx.expense.update({ where: { id }, data: this.fields(dto) });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'Expense', entityId: id, after: dto }, tx);
    });
    return this.get(id);
  }

  async approve(id: string, dto: ApproveExpenseDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const expense = await this.lock(tx, id);
      if (expense.status !== ExpenseStatus.DRAFT) throw new BadRequestException('Only draft expenses can be approved');
      const category = await tx.expenseCategory.findUniqueOrThrow({ where: { id: expense.categoryId } });
      const cashAccount = dto.paidFromAccountId ? await this.cashAccount(tx, dto.paidFromAccountId) : null;
      const total = dec(expense.amount).add(expense.taxAmount);

      await this.ledger.post(tx, {
        description: `Expense ${expense.number}: ${expense.description}`,
        sourceType: 'EXPENSE',
        sourceId: expense.id,
        entryDate: expense.expenseDate,
        userId,
        lines: [
          { account: { id: category.accountId }, debit: expense.amount },
          { account: 'VAT_INPUT', debit: expense.taxAmount },
          cashAccount ? { account: { id: cashAccount }, credit: total } : { account: 'ACCRUED_EXPENSES', credit: total },
        ],
      });
      await tx.expense.update({
        where: { id },
        data: {
          status: cashAccount ? ExpenseStatus.PAID : ExpenseStatus.APPROVED,
          approvedById: userId,
          approvedAt: new Date(),
          paidFromAccountId: cashAccount,
          paidAt: cashAccount ? new Date() : null,
        },
      });
      await this.audit.log({ userId, action: 'APPROVE', entityType: 'Expense', entityId: id, after: dto }, tx);
    });
    return this.get(id);
  }

  async pay(id: string, dto: PayExpenseDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const expense = await this.lock(tx, id);
      if (expense.status !== ExpenseStatus.APPROVED) throw new BadRequestException('Only approved, unpaid expenses can be paid');
      const cashAccount = await this.cashAccount(tx, dto.paidFromAccountId);
      const total = dec(expense.amount).add(expense.taxAmount);
      await this.ledger.post(tx, {
        description: `Payment of expense ${expense.number}`,
        sourceType: 'EXPENSE',
        sourceId: expense.id,
        userId,
        lines: [
          { account: 'ACCRUED_EXPENSES', debit: total },
          { account: { id: cashAccount }, credit: total },
        ],
      });
      await tx.expense.update({
        where: { id },
        data: { status: ExpenseStatus.PAID, paidFromAccountId: cashAccount, paidAt: new Date() },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'Expense', entityId: id, after: { paid: true } }, tx);
    });
    return this.get(id);
  }

  async cancel(id: string, reason: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const expense = await this.lock(tx, id);
      if (expense.status === ExpenseStatus.PAID) {
        throw new UnprocessableEntityException('Paid expenses cannot be cancelled; record a correcting journal entry');
      }
      if (expense.status === ExpenseStatus.CANCELLED) throw new BadRequestException('Expense is already cancelled');
      if (expense.status === ExpenseStatus.APPROVED) {
        const entry = await tx.journalEntry.findFirstOrThrow({
          where: { sourceType: 'EXPENSE', sourceId: id, status: 'POSTED' },
        });
        await this.ledger.reverse(tx, entry.id, userId, `Expense ${expense.number} cancelled: ${reason}`);
      }
      await tx.expense.update({ where: { id }, data: { status: ExpenseStatus.CANCELLED } });
      await this.audit.log({ userId, action: 'CANCEL', entityType: 'Expense', entityId: id, after: { reason } }, tx);
    });
    return this.get(id);
  }

  // -------------------------------------------------------------- helpers ---

  private fields(dto: ExpenseDto) {
    if (dec(dto.amount).lte(0)) throw new BadRequestException('Amount must be greater than zero');
    return {
      categoryId: dto.categoryId,
      amount: dto.amount,
      taxAmount: dto.taxAmount ?? 0,
      expenseDate: parseDateOnly(dto.expenseDate),
      description: dto.description,
      payee: dto.payee,
      attachmentUrl: dto.attachmentUrl,
    };
  }

  private async assertCategory(id: string) {
    const category = await this.prisma.expenseCategory.findUnique({ where: { id } });
    if (!category || !category.isActive) throw new NotFoundException('Expense category not found or inactive');
  }

  private async cashAccount(tx: Tx, id: string) {
    const acc = await tx.account.findUnique({ where: { id } });
    if (!acc || !acc.isActive || !acc.isPostable || !acc.isCashOrBank) {
      throw new BadRequestException('Choose an active cash, bank or wallet account');
    }
    return acc.id;
  }

  private async lock(tx: Tx, id: string): Promise<Expense> {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM expenses WHERE id = ${id}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException('Expense not found');
    return tx.expense.findUniqueOrThrow({ where: { id } });
  }
}
