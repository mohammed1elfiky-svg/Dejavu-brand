import { ConflictException, Injectable, Logger, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { CashBoxKind, NotificationType, Prisma, TopUpStatus } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { CurrencyService, formatIn, inCurrency } from '../common/currency.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { dec } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';
import { CustomerAccountsService } from './customer-accounts.service';
import { TransfersService } from './transfers.service';

const AUTO_KEY = 'wallet.autoApproveTopUps';

const topUpSelect = {
  id: true,
  number: true,
  currency: true,
  amount: true,
  reference: true,
  note: true,
  status: true,
  receiptFileId: true,
  rejectReason: true,
  autoApproved: true,
  verifiedAt: true,
  createdAt: true,
  reviewedAt: true,
  bankAccount: { select: { id: true, bankName: true, accountNumber: true, currency: true } },
} satisfies Prisma.WalletTopUpSelect;

export interface BankAccountInput {
  bankName: string;
  accountName: string;
  accountNumber: string;
  iban?: string;
  currency: string;
  currencies?: string[];
  imageUrl?: string | null;
  instructions?: string;
  isActive?: boolean;
  sortOrder?: number;
}

/**
 * Manual top-ups: the customer transfers money to one of the store's bank accounts,
 * uploads the deposit slip, and staff approve it into the customer's wallet.
 */
@Injectable()
export class WalletService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly accounts: CustomerAccountsService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly notifications: NotificationsService,
    private readonly audit: AuditService,
    private readonly transfers: TransfersService,
    private readonly ledger: LedgerService,
  ) {}

  private readonly log = new Logger('Wallet');

  // --------------------------------------------------------- bank accounts ---

  bankAccounts(activeOnly: boolean) {
    return this.prisma.storeBankAccount.findMany({
      where: activeOnly ? { isActive: true, currencyInfo: { isActive: true } } : {},
      orderBy: [{ sortOrder: 'asc' }, { bankName: 'asc' }],
    });
  }

  async createBankAccount(dto: BankAccountInput, userId: string) {
    const currencies = await this.accountCurrencies(dto.currency, dto.currencies);
    const row = await this.prisma.storeBankAccount.create({ data: { ...dto, currency: currencies[0], currencies } });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'StoreBankAccount', entityId: row.id, after: row });
    return row;
  }

  async updateBankAccount(id: string, dto: Partial<BankAccountInput>, userId: string) {
    const before = await this.prisma.storeBankAccount.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Bank account not found');
    const data = { ...dto };
    if (dto.currency || dto.currencies) {
      const currencies = await this.accountCurrencies(dto.currency ?? before.currency, dto.currencies ?? (dto.currency ? undefined : before.currencies));
      Object.assign(data, { currency: currencies[0], currencies });
    }
    const row = await this.prisma.storeBankAccount.update({ where: { id }, data });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'StoreBankAccount', entityId: id, after: row });
    return row;
  }

  async deleteBankAccount(id: string, userId: string) {
    const row = await this.prisma.storeBankAccount.findUnique({ where: { id }, include: { _count: { select: { topUps: true } } } });
    if (!row) throw new NotFoundException('Bank account not found');
    if (row._count.topUps) {
      await this.prisma.storeBankAccount.update({ where: { id }, data: { isActive: false } });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'StoreBankAccount', entityId: id, after: { hidden: true } });
      return { deleted: false, hidden: true };
    }
    await this.prisma.storeBankAccount.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'StoreBankAccount', entityId: id, before: row });
    return { deleted: true, hidden: false };
  }

  /** The account's currencies: the main one first, each active, no repeats. */
  private async accountCurrencies(main: string, list?: string[]) {
    const all = [...new Set([main, ...(list ?? [])])];
    for (const c of all) await this.currencies.requireActive(c);
    return all;
  }

  // ------------------------------------------------------------- customer ---

  /** Whether top-ups are credited at once (the owner can switch it off: then staff approve each). */
  async autoApproves(db: Prisma.TransactionClient | PrismaService = this.prisma) {
    const row = await db.setting.findUnique({ where: { key: AUTO_KEY } });
    return row ? row.value === true : true;
  }

  async settings() {
    return { autoApprove: await this.autoApproves() };
  }

  async setSettings(dto: { autoApprove: boolean }, userId: string) {
    await this.prisma.setting.upsert({ where: { key: AUTO_KEY }, update: { value: dto.autoApprove }, create: { key: AUTO_KEY, value: dto.autoApprove } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Setting', entityId: AUTO_KEY, after: dto });
    return this.settings();
  }

  /** What the slip photo says (reference number, amount), to fill the form before sending. */
  async scanSlip(customerId: string, fileId: string) {
    const file = await this.prisma.storedFile.findUnique({ where: { id: fileId }, select: { customerId: true } });
    if (!file || file.customerId !== customerId) throw new NotFoundException('Upload the deposit receipt first');
    const { reference, reading } = await this.transfers.referenceOf(fileId, null);
    let duplicate: string | null = null;
    try {
      await this.transfers.assertNew(this.prisma, reference, fileId);
    } catch (e) {
      duplicate = ((e as ConflictException).getResponse() as { message?: string }).message ?? 'هذه الحوالة مستخدمة سابقاً';
    }
    return { ...reading, reference: reading.reference, duplicate };
  }

  /**
   * A top-up request. The slip's reference number goes on the transfer register (the same
   * transfer is refused a second time), and the money is credited at once into the bank box
   * of that currency; staff then check it arrived (list "للتحقق") and can reverse it.
   */
  async request(
    customerId: string,
    dto: { currency: string; amount: string; receiptFileId: string; bankAccountId?: string; reference?: string; note?: string },
  ) {
    await this.currencies.requireActive(dto.currency, this.prisma, { customerApp: true });
    const amount = inCurrency(dto.amount, dto.currency);
    if (!amount.gt(0)) throw new UnprocessableEntityException('Amount must be greater than zero');
    const file = await this.prisma.storedFile.findUnique({ where: { id: dto.receiptFileId }, select: { customerId: true } });
    if (!file || file.customerId !== customerId) throw new NotFoundException('Upload the deposit receipt first');
    let bankName: string | null = null;
    if (dto.bankAccountId) {
      const bank = await this.prisma.storeBankAccount.findUnique({ where: { id: dto.bankAccountId } });
      if (!bank || !bank.isActive) throw new NotFoundException('Bank account not found');
      const takes = bank.currencies.length ? bank.currencies : [bank.currency];
      if (!takes.includes(dto.currency)) throw new UnprocessableEntityException(`${bank.bankName} does not take ${dto.currency}`);
      bankName = bank.bankName;
    }
    if (await this.prisma.walletTopUp.count({ where: { customerId, status: TopUpStatus.PENDING } }) >= 5) {
      throw new ConflictException('You already have several requests under review');
    }
    const { reference, extracted, reading } = await this.transfers.referenceOf(dto.receiptFileId, dto.reference);
    // The slip shows less than asked for: a person looks before anything is credited.
    const slipAmount = reading.amount && (!reading.currency || reading.currency === dto.currency) ? inCurrency(reading.amount, dto.currency) : null;
    const amountDoubtful = !!slipAmount && amount.gt(slipAmount);
    const topUp = await this.prisma.$transaction(async (tx) => {
      const created = await tx.walletTopUp.create({
        data: { ...dto, reference: reference ?? dto.reference ?? null, amount, customerId, number: await this.sequences.next(tx, 'TOP') },
        select: topUpSelect,
      });
      await this.transfers.register(tx, {
        reference,
        extracted,
        source: 'TOP_UP',
        customerId,
        currency: dto.currency,
        amount,
        bankName: reading.bankName ?? bankName,
        fileId: dto.receiptFileId,
        topUpId: created.id,
      });
      if (amountDoubtful || !(await this.autoApproves(tx))) return created;
      const box =
        (await tx.cashBox.findFirst({ where: { kind: CashBoxKind.BANK, currency: dto.currency, isActive: true }, orderBy: { createdAt: 'asc' } })) ??
        (await tx.cashBox.findFirst({ where: { kind: CashBoxKind.VAULT, currency: dto.currency, isActive: true }, orderBy: { createdAt: 'asc' } }));
      if (!box) return created;
      await this.approveIn(tx, created.id, { cashBoxId: box.id }, null);
      return created;
    });
    const row = await this.prisma.walletTopUp.findUniqueOrThrow({ where: { id: topUp.id }, select: topUpSelect });
    void this.notifications.notifyStaff('wallet.manage', {
      type: NotificationType.WALLET,
      title: row.status === TopUpStatus.APPROVED ? 'شحن رصيد تلقائي — للتحقق' : 'طلب شحن رصيد جديد',
      body: `${row.number} · ${formatIn(amount, dto.currency)}${reference ? ` · مرجع ${reference}` : ''}${amountDoubtful ? ' · المبلغ أكبر من الإشعار' : ''}`,
      data: { topUpId: row.id },
    });
    return row;
  }

  /** The customer corrects a request still under review (amount, slip, bank…). */
  async updateRequest(
    customerId: string,
    id: string,
    dto: Partial<{ currency: string; amount: string; receiptFileId: string; bankAccountId?: string; reference?: string; note?: string }>,
  ) {
    const t = await this.pendingOf(customerId, id);
    const currency = dto.currency ?? t.currency;
    if (dto.currency) await this.currencies.requireActive(currency, this.prisma, { customerApp: true });
    const amount = inCurrency(dto.amount ?? t.amount, currency);
    if (!amount.gt(0)) throw new UnprocessableEntityException('Amount must be greater than zero');
    if (dto.receiptFileId) {
      const file = await this.prisma.storedFile.findUnique({ where: { id: dto.receiptFileId }, select: { customerId: true } });
      if (!file || file.customerId !== customerId) throw new NotFoundException('Upload the deposit receipt first');
    }
    if (dto.bankAccountId) {
      const bank = await this.prisma.storeBankAccount.findUnique({ where: { id: dto.bankAccountId } });
      if (!bank || !bank.isActive) throw new NotFoundException('Bank account not found');
      const takes = bank.currencies.length ? bank.currencies : [bank.currency];
      if (!takes.includes(currency)) throw new UnprocessableEntityException(`${bank.bankName} does not take ${currency}`);
    }
    // A new slip replaces the old one on the transfer register.
    const slip = dto.receiptFileId && dto.receiptFileId !== t.receiptFileId ? await this.transfers.referenceOf(dto.receiptFileId, dto.reference) : null;
    await this.prisma.$transaction(async (tx) => {
      // Still pending at write time (staff may be reviewing it right now).
      const res = await tx.walletTopUp.updateMany({
        where: { id, customerId, status: TopUpStatus.PENDING },
        data: { ...dto, currency, amount, ...(slip && { reference: slip.reference ?? dto.reference ?? null }) },
      });
      if (!res.count) throw new ConflictException('This request was already reviewed');
      if (slip) {
        await this.transfers.release(tx, { topUpId: id });
        await this.transfers.register(tx, {
          reference: slip.reference,
          extracted: slip.extracted,
          source: 'TOP_UP',
          customerId,
          currency,
          amount,
          bankName: slip.reading.bankName,
          fileId: dto.receiptFileId,
          topUpId: id,
        });
      }
    });
    return this.prisma.walletTopUp.findUniqueOrThrow({ where: { id }, select: topUpSelect });
  }

  /** The customer withdraws a request still under review. */
  async deleteRequest(customerId: string, id: string) {
    await this.pendingOf(customerId, id);
    await this.prisma.$transaction(async (tx) => {
      const res = await tx.walletTopUp.deleteMany({ where: { id, customerId, status: TopUpStatus.PENDING } });
      if (!res.count) throw new ConflictException('This request was already reviewed');
      await this.transfers.release(tx, { topUpId: id });
    });
  }

  private async pendingOf(customerId: string, id: string) {
    const t = await this.prisma.walletTopUp.findFirst({ where: { id, customerId } });
    if (!t) throw new NotFoundException('Request not found');
    if (t.status !== TopUpStatus.PENDING) throw new ConflictException('Only requests under review can be changed');
    return t;
  }

  async customerRequests(customerId: string) {
    return this.prisma.walletTopUp.findMany({ where: { customerId }, select: topUpSelect, orderBy: { createdAt: 'desc' }, take: 50 });
  }

  // ---------------------------------------------------------------- staff ---

  async list(q: PaginationQueryDto & { status?: TopUpStatus; toVerify?: boolean }) {
    const where: Prisma.WalletTopUpWhereInput = q.toVerify ? { status: TopUpStatus.APPROVED, autoApproved: true, verifiedAt: null } : { status: q.status };
    const [items, total] = await Promise.all([
      this.prisma.walletTopUp.findMany({
        where,
        select: { ...topUpSelect, customer: { select: { id: true, code: true, name: true, phone: true } } },
        orderBy: { createdAt: q.status === TopUpStatus.PENDING ? 'asc' : 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.walletTopUp.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async get(id: string) {
    const row = await this.prisma.walletTopUp.findUnique({
      where: { id },
      select: {
        ...topUpSelect,
        baseAmount: true,
        exchangeRate: true,
        customer: { select: { id: true, code: true, name: true, phone: true } },
        cashBox: { select: { id: true, name: true, currency: true } },
        reviewedBy: { select: { id: true, name: true } },
      },
    });
    if (!row) throw new NotFoundException('Top-up request not found');
    return { ...row, balances: await this.accounts.balances(row.customer.id) };
  }

  /**
   * Credits the customer's wallet with the deposited amount and books the money into
   * [cashBoxId] (the bank or safe it landed in):  Dr box  —  Cr customer accounts.
   */
  async approve(id: string, dto: { cashBoxId: string; amount?: string }, userId: string) {
    const topUp = await this.prisma.$transaction((tx) => this.approveIn(tx, id, dto, userId));
    return this.get(topUp.id);
  }

  /** [userId] null: credited automatically on request (still to be verified by staff). */
  private async approveIn(tx: Prisma.TransactionClient, id: string, dto: { cashBoxId: string; amount?: string }, userId: string | null) {
    {
      await tx.$queryRaw`SELECT id FROM wallet_top_ups WHERE id = ${id}::uuid FOR UPDATE`;
      const t = await tx.walletTopUp.findUnique({ where: { id } });
      if (!t) throw new NotFoundException('Top-up request not found');
      if (t.status !== TopUpStatus.PENDING) throw new ConflictException(`Request is already ${t.status.toLowerCase()}`);
      // Staff may correct the amount to what actually arrived.
      const amount = dto.amount ? inCurrency(dto.amount, t.currency) : dec(t.amount);
      const rate = await this.currencies.rate(t.currency, tx);
      const { booked } = await this.accounts.credit(tx, {
        customerId: t.customerId,
        currency: t.currency,
        amount,
        boxId: dto.cashBoxId,
        sourceType: 'WALLET_TOPUP',
        sourceId: t.id,
        description: `Wallet top-up ${t.number}`,
        userId,
      });
      const updated = await tx.walletTopUp.update({
        where: { id },
        data: {
          status: TopUpStatus.APPROVED,
          amount,
          cashBoxId: dto.cashBoxId,
          baseAmount: booked,
          exchangeRate: rate,
          reviewedById: userId,
          reviewedAt: new Date(),
          autoApproved: userId === null,
          // Approved by a person = checked by a person.
          verifiedAt: userId ? new Date() : null,
          verifiedById: userId,
        },
      });
      await this.notifications.notifyCustomer(tx, t.customerId, {
        type: NotificationType.WALLET,
        title: 'تم شحن رصيدك ✅',
        body: `أُضيف ${formatIn(amount, t.currency)} إلى رصيدك (طلب ${t.number}).`,
        data: { topUp: t.number },
      });
      await this.audit.log({ userId, action: 'APPROVE', entityType: 'WalletTopUp', entityId: id, after: updated }, tx);
      return updated;
    }
  }

  /** Staff saw the money in the bank: the automatic top-up leaves the "للتحقق" list. */
  async verify(id: string, userId: string) {
    const res = await this.prisma.walletTopUp.updateMany({ where: { id, status: TopUpStatus.APPROVED, verifiedAt: null }, data: { verifiedAt: new Date(), verifiedById: userId } });
    if (!res.count) throw new ConflictException('Already checked, or not credited');
    await this.audit.log({ userId, action: 'APPROVE', entityType: 'WalletTopUp', entityId: id, after: { verified: true } });
    return this.get(id);
  }

  /**
   * The money never arrived: takes the credit back out of the customer's wallet (it may go
   * below zero if they already spent it) and out of the box. The reference stays on the
   * register, so the same slip cannot be sent again.
   */
  async reverse(id: string, reason: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM wallet_top_ups WHERE id = ${id}::uuid FOR UPDATE`;
      const t = await tx.walletTopUp.findUnique({ where: { id } });
      if (!t) throw new NotFoundException('Top-up request not found');
      if (t.status !== TopUpStatus.APPROVED) throw new ConflictException('Only credited top-ups can be reversed');
      const entry = await tx.journalEntry.findFirst({ where: { sourceType: 'WALLET_TOPUP', sourceId: id, status: 'POSTED' } });
      if (entry) await this.ledger.reverse(tx, entry.id, userId, `Top-up ${t.number} reversed: ${reason}`);
      await tx.walletTopUp.update({
        where: { id },
        data: { status: TopUpStatus.REJECTED, rejectReason: reason, reviewedById: userId, reviewedAt: new Date(), verifiedAt: new Date(), verifiedById: userId },
      });
      await this.notifications.notifyCustomer(tx, t.customerId, {
        type: NotificationType.WALLET,
        title: 'أُلغي شحن الرصيد',
        body: `الطلب ${t.number}: ${reason}. خُصم ${formatIn(dec(t.amount), t.currency)} من رصيدك.`,
        data: { topUp: t.number },
      });
      await this.audit.log({ userId, action: 'REVERSE', entityType: 'WalletTopUp', entityId: id, after: { reason } }, tx);
    });
    return this.get(id);
  }

  async reject(id: string, reason: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM wallet_top_ups WHERE id = ${id}::uuid FOR UPDATE`;
      const t = await tx.walletTopUp.findUnique({ where: { id } });
      if (!t) throw new NotFoundException('Top-up request not found');
      if (t.status !== TopUpStatus.PENDING) throw new ConflictException(`Request is already ${t.status.toLowerCase()}`);
      await tx.walletTopUp.update({
        where: { id },
        data: { status: TopUpStatus.REJECTED, rejectReason: reason, reviewedById: userId, reviewedAt: new Date() },
      });
      // The customer may send the same transfer again with the right details.
      await this.transfers.release(tx, { topUpId: id });
      await this.notifications.notifyCustomer(tx, t.customerId, {
        type: NotificationType.WALLET,
        title: 'تعذّر قبول طلب الشحن',
        body: `الطلب ${t.number}: ${reason}`,
        data: { topUp: t.number },
      });
      await this.audit.log({ userId, action: 'CANCEL', entityType: 'WalletTopUp', entityId: id, after: { reason } }, tx);
    });
    return this.get(id);
  }
}
