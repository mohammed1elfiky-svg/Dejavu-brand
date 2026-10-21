import { ConflictException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { NotificationType, Prisma, TopUpStatus } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { CurrencyService, formatIn, inCurrency } from '../common/currency.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { dec } from '../common/utils/money';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService } from '../prisma/prisma.service';
import { CustomerAccountsService } from './customer-accounts.service';

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
  ) {}

  // --------------------------------------------------------- bank accounts ---

  bankAccounts(activeOnly: boolean) {
    return this.prisma.storeBankAccount.findMany({
      where: activeOnly ? { isActive: true, currencyInfo: { isActive: true } } : {},
      orderBy: [{ sortOrder: 'asc' }, { bankName: 'asc' }],
    });
  }

  async createBankAccount(dto: BankAccountInput, userId: string) {
    await this.currencies.requireActive(dto.currency);
    const row = await this.prisma.storeBankAccount.create({ data: dto });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'StoreBankAccount', entityId: row.id, after: row });
    return row;
  }

  async updateBankAccount(id: string, dto: Partial<BankAccountInput>, userId: string) {
    if (dto.currency) await this.currencies.requireActive(dto.currency);
    const row = await this.prisma.storeBankAccount.update({ where: { id }, data: dto });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'StoreBankAccount', entityId: id, after: row });
    return row;
  }

  // ------------------------------------------------------------- customer ---

  async request(
    customerId: string,
    dto: { currency: string; amount: string; receiptFileId: string; bankAccountId?: string; reference?: string; note?: string },
  ) {
    await this.currencies.requireActive(dto.currency, this.prisma, { customerApp: true });
    const amount = inCurrency(dto.amount, dto.currency);
    if (!amount.gt(0)) throw new UnprocessableEntityException('Amount must be greater than zero');
    const file = await this.prisma.storedFile.findUnique({ where: { id: dto.receiptFileId }, select: { customerId: true } });
    if (!file || file.customerId !== customerId) throw new NotFoundException('Upload the deposit receipt first');
    if (dto.bankAccountId) {
      const bank = await this.prisma.storeBankAccount.findUnique({ where: { id: dto.bankAccountId } });
      if (!bank || !bank.isActive) throw new NotFoundException('Bank account not found');
    }
    if (await this.prisma.walletTopUp.count({ where: { customerId, status: TopUpStatus.PENDING } }) >= 5) {
      throw new ConflictException('You already have several requests under review');
    }
    const topUp = await this.prisma.$transaction(async (tx) =>
      tx.walletTopUp.create({
        data: { ...dto, amount, customerId, number: await this.sequences.next(tx, 'TOP') },
        select: topUpSelect,
      }),
    );
    void this.notifications.notifyStaff('wallet.manage', {
      type: NotificationType.WALLET,
      title: 'طلب شحن رصيد جديد',
      body: `${topUp.number} · ${formatIn(amount, dto.currency)}`,
      data: { topUpId: topUp.id },
    });
    return topUp;
  }

  async customerRequests(customerId: string) {
    return this.prisma.walletTopUp.findMany({ where: { customerId }, select: topUpSelect, orderBy: { createdAt: 'desc' }, take: 50 });
  }

  // ---------------------------------------------------------------- staff ---

  async list(q: PaginationQueryDto & { status?: TopUpStatus }) {
    const where: Prisma.WalletTopUpWhereInput = { status: q.status };
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
    const topUp = await this.prisma.$transaction(async (tx) => {
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
    });
    return this.get(topUp.id);
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
