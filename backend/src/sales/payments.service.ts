import { BadRequestException, Injectable, UnprocessableEntityException } from '@nestjs/common';
import { Order, OrderStatus, PaymentMethod, PaymentStatus, TransactionStatus } from '../generated/prisma/client';
import { LedgerService, SystemAccount } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { SequenceService } from '../common/sequence.service';
import { Decimal, dec, money } from '../common/utils/money';
import { Tx } from '../prisma/prisma.service';

/** Revenue is recognised on shipping; before that, money received is a customer advance. */
export const INVOICED_STATUSES: OrderStatus[] = [
  OrderStatus.SHIPPED,
  OrderStatus.DELIVERED,
  OrderStatus.COMPLETED,
  OrderStatus.RETURNED,
];

const DEFAULT_ACCOUNT: Record<PaymentMethod, SystemAccount> = {
  CASH: 'CASH',
  CASH_ON_DELIVERY: 'COD_RECEIVABLE',
  CARD: 'GATEWAY_CLEARING',
  WALLET: 'WALLET',
  BANK_TRANSFER: 'BANK',
  INSTAPAY: 'BANK',
};

export interface MoneyMovement {
  method: PaymentMethod;
  amount: string | Decimal;
  accountId?: string;
  reference?: string;
}

@Injectable()
export class PaymentsService {
  constructor(
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  /** Records money received for an order. Caller holds the order row lock. */
  async receive(tx: Tx, order: Order, input: MoneyMovement, userId: string | null) {
    if (order.status === OrderStatus.CANCELLED) throw new UnprocessableEntityException('Order is cancelled');
    const amount = money(input.amount);
    if (!amount.isPositive()) throw new BadRequestException('Amount must be greater than zero');
    const due = dec(order.grandTotal).sub(order.amountPaid);
    if (amount.greaterThan(due)) {
      throw new UnprocessableEntityException(`Amount exceeds the balance due (${money(due)})`);
    }

    const cashAccount = await this.resolveCashAccount(tx, input);
    const invoiced = INVOICED_STATUSES.includes(order.status);
    const number = await this.sequences.next(tx, 'RCPT');
    const payment = await tx.payment.create({
      data: {
        number,
        orderId: order.id,
        method: input.method,
        status: TransactionStatus.SUCCEEDED,
        amount,
        accountId: cashAccount,
        gatewayRef: input.reference,
        paidAt: new Date(),
      },
    });

    await this.ledger.post(tx, {
      description: `Receipt ${number} for order ${order.number}`,
      sourceType: 'CUSTOMER_PAYMENT',
      sourceId: payment.id,
      userId,
      lines: [
        { account: { id: cashAccount }, debit: amount },
        { account: invoiced ? 'AR' : 'CUSTOMER_ADVANCES', credit: amount, customerId: order.customerId },
      ],
    });

    const amountPaid = dec(order.amountPaid).add(amount);
    const updated = await tx.order.update({
      where: { id: order.id },
      data: { amountPaid, paymentStatus: await this.statusOf(tx, order, amountPaid) },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Payment', entityId: payment.id, after: payment }, tx);
    return { payment, order: updated };
  }

  /** Returns money for an order that has not been invoiced (shipped) yet. */
  async refund(tx: Tx, order: Order, input: MoneyMovement & { reason: string }, userId: string) {
    if (INVOICED_STATUSES.includes(order.status)) {
      throw new UnprocessableEntityException('Order was already shipped; refunds go through a sales return');
    }
    const amount = money(input.amount);
    if (!amount.isPositive()) throw new BadRequestException('Amount must be greater than zero');
    if (amount.greaterThan(order.amountPaid)) {
      throw new UnprocessableEntityException(`Refund exceeds the amount paid (${order.amountPaid})`);
    }

    const cashAccount = await this.resolveCashAccount(tx, input);
    const number = await this.sequences.next(tx, 'RFND');
    const payment = await tx.payment.create({
      data: {
        number,
        orderId: order.id,
        method: input.method,
        status: TransactionStatus.SUCCEEDED,
        amount: amount.neg(),
        accountId: cashAccount,
        gatewayRef: input.reference,
        gatewayPayload: { reason: input.reason },
        paidAt: new Date(),
      },
    });

    await this.ledger.post(tx, {
      description: `Refund ${number} for order ${order.number}: ${input.reason}`,
      sourceType: 'CUSTOMER_PAYMENT',
      sourceId: payment.id,
      userId,
      lines: [
        { account: 'CUSTOMER_ADVANCES', debit: amount, customerId: order.customerId },
        { account: { id: cashAccount }, credit: amount },
      ],
    });

    const amountPaid = dec(order.amountPaid).sub(amount);
    const updated = await tx.order.update({
      where: { id: order.id },
      data: { amountPaid, paymentStatus: await this.statusOf(tx, order, amountPaid) },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Refund', entityId: payment.id, after: payment }, tx);
    return { payment, order: updated };
  }

  private async statusOf(tx: Tx, order: Order, paid: Decimal): Promise<PaymentStatus> {
    const refunded = (await tx.payment.count({ where: { orderId: order.id, amount: { lt: 0 } } })) > 0;
    if (paid.isZero()) return refunded ? PaymentStatus.REFUNDED : PaymentStatus.UNPAID;
    if (paid.greaterThanOrEqualTo(order.grandTotal)) return PaymentStatus.PAID;
    return refunded ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.PARTIALLY_PAID;
  }

  private async resolveCashAccount(tx: Tx, input: MoneyMovement): Promise<string> {
    if (input.accountId) {
      const acc = await tx.account.findUnique({ where: { id: input.accountId } });
      const allowed = acc && acc.isActive && acc.isPostable && (acc.isCashOrBank || acc.systemKey === 'COD_RECEIVABLE');
      if (!allowed) throw new BadRequestException('Choose an active cash, bank or wallet account');
      return acc.id;
    }
    const key = DEFAULT_ACCOUNT[input.method];
    const acc = await tx.account.findUnique({ where: { systemKey: key }, select: { id: true } });
    if (!acc) throw new UnprocessableEntityException(`System account ${key} is not configured`);
    return acc.id;
  }
}
