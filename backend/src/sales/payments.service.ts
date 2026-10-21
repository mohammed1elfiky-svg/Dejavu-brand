import { BadRequestException, Injectable, UnprocessableEntityException } from '@nestjs/common';
import { Currency, Order, OrderStatus, Payment, PaymentMethod, Prisma, PaymentStatus, TransactionStatus } from '../generated/prisma/client';
import { LedgerService, SystemAccount } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { CURRENCY_INFO, toBase, toCurrency } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { Decimal, dec, money } from '../common/utils/money';
import { paymentReceivedMessage, refundMessage } from '../notifications/messages';
import { NotificationsService } from '../notifications/notifications.service';
import { Tx } from '../prisma/prisma.service';

/**
 * Revenue is recognised on shipping; before that, money received is a customer
 * advance. A refused delivery (RETURNED) reverses the invoice, so money held for
 * it is an advance again.
 */
export const INVOICED_STATUSES: OrderStatus[] = [OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED];

/** What the customer still owes after payments and received returns. */
export const balanceDue = (o: Pick<Order, 'grandTotal' | 'returnedTotal' | 'amountPaid'>) =>
  money(dec(o.grandTotal).sub(o.returnedTotal).sub(o.amountPaid));

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
  /** In [currency]; SAR when omitted. */
  amount: string | Decimal;
  /** SAR, or the order's own currency (converted at the rate locked into the order). */
  currency?: Currency;
  accountId?: string;
  reference?: string;
}

/** A movement's SAR value plus what was actually handed over. */
interface Converted {
  amount: Decimal;
  currency: Currency;
  currencyAmount: Decimal;
  exchangeRate: Prisma.Decimal | Decimal;
}

/**
 * Turns an amount given in SAR or in the order's currency into SAR at the order's
 * locked rate. Paying (or refunding) exactly the converted [limit] maps to the exact
 * SAR [limit], so a full payment in YER never leaves a rounding remainder.
 */
function convert(order: Order, input: MoneyMovement, limit: Decimal, overLimit: (shown: string) => string): Converted {
  const currency = input.currency ?? Currency.SAR;
  if (currency === Currency.SAR) {
    const amount = money(input.amount);
    if (!amount.gt(0)) throw new BadRequestException('Amount must be greater than zero');
    if (amount.greaterThan(limit)) throw new UnprocessableEntityException(overLimit(money(limit).toFixed(2) + ' SAR'));
    // Cash the courier collected on a YER order was collected in YER.
    if (input.method === PaymentMethod.CASH_ON_DELIVERY && order.currency !== Currency.SAR) {
      return { amount, currency: order.currency, currencyAmount: toCurrency(amount, order.currency, order.exchangeRate), exchangeRate: order.exchangeRate };
    }
    return { amount, currency, currencyAmount: amount, exchangeRate: dec(1) };
  }
  if (currency !== order.currency) {
    throw new UnprocessableEntityException(`Use SAR or the order's currency (${order.currency})`);
  }
  const decimals = CURRENCY_INFO[currency].decimals;
  const given = dec(input.amount);
  if (given.decimalPlaces() > decimals) {
    throw new BadRequestException(`${currency} amounts have at most ${decimals} decimals`);
  }
  if (!given.gt(0)) throw new BadRequestException('Amount must be greater than zero');
  const limitShown = toCurrency(limit, currency, order.exchangeRate);
  if (given.greaterThan(limitShown)) throw new UnprocessableEntityException(overLimit(`${limitShown.toFixed(decimals)} ${currency}`));
  const amount = given.eq(limitShown) ? money(limit) : toBase(given, currency, order.exchangeRate);
  if (!amount.gt(0)) throw new BadRequestException('Amount is too small');
  return { amount, currency, currencyAmount: given, exchangeRate: order.exchangeRate };
}

@Injectable()
export class PaymentsService {
  constructor(
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly notifications: NotificationsService,
  ) {}

  /** Records money received for an order. Caller holds the order row lock. */
  async receive(tx: Tx, order: Order, input: MoneyMovement, userId: string | null) {
    if (order.status === OrderStatus.CANCELLED || order.status === OrderStatus.RETURNED) {
      throw new UnprocessableEntityException(`Order is ${order.status.toLowerCase()}`);
    }
    const c = convert(order, input, dec(balanceDue(order)), (shown) => `Amount exceeds the balance due (${shown})`);

    const cashAccount = await this.resolveCashAccount(tx, input);
    const number = await this.sequences.next(tx, 'RCPT');
    const payment = await tx.payment.create({
      data: {
        number,
        orderId: order.id,
        method: input.method,
        status: TransactionStatus.SUCCEEDED,
        amount: c.amount,
        currency: c.currency,
        currencyAmount: c.currencyAmount,
        exchangeRate: c.exchangeRate,
        accountId: cashAccount,
        gatewayRef: input.reference,
        paidAt: new Date(),
      },
    });
    const updated = await this.postReceipt(tx, order, payment, userId);
    return { payment, order: updated };
  }

  /**
   * Opens a card payment attempt for what the customer still owes. It reaches the
   * ledger only when the gateway confirms it. Caller holds the order row lock.
   */
  async openGatewayAttempt(tx: Tx, order: Order, gateway: string) {
    if (order.paymentMethod !== PaymentMethod.CARD) {
      throw new UnprocessableEntityException('This order is not paid by card');
    }
    if (order.status === OrderStatus.CANCELLED || order.status === OrderStatus.RETURNED) {
      throw new UnprocessableEntityException(`Order is ${order.status.toLowerCase()}`);
    }
    const due = balanceDue(order);
    if (!due.gt(0)) throw new UnprocessableEntityException('This order is already paid');
    // Only the newest attempt stays open; an older tab that still succeeds is recorded anyway.
    await tx.payment.updateMany({
      where: { orderId: order.id, status: TransactionStatus.PENDING, gateway: { not: null } },
      data: { status: TransactionStatus.CANCELLED },
    });
    return tx.payment.create({
      data: {
        number: await this.sequences.next(tx, 'PAY'),
        orderId: order.id,
        method: PaymentMethod.CARD,
        status: TransactionStatus.PENDING,
        amount: due,
        currencyAmount: due,
        accountId: await this.resolveCashAccount(tx, { method: PaymentMethod.CARD, amount: due }),
        gateway,
      },
    });
  }

  /**
   * Books a gateway-confirmed attempt. Money the gateway actually took is always
   * recorded, even for an order cancelled meanwhile: it then sits as a customer
   * advance until refunded. Returns null if the attempt was already settled.
   */
  async confirmGatewayAttempt(
    tx: Tx,
    order: Order,
    paymentId: string,
    result: { success: boolean; amount: Decimal; transactionId: string; payload: Record<string, string> },
  ) {
    const payment = await tx.payment.findFirst({ where: { id: paymentId, orderId: order.id } });
    if (!payment || !payment.gateway) return null;
    const open: TransactionStatus[] = [TransactionStatus.PENDING, TransactionStatus.CANCELLED];
    if (!open.includes(payment.status)) return null;
    const payload = { ...((payment.gatewayPayload as object) ?? {}), ...result.payload } as Prisma.InputJsonObject;

    if (!result.success) {
      // A superseded attempt stays cancelled; only the live one is shown as declined.
      if (payment.status === TransactionStatus.PENDING) {
        await tx.payment.update({ where: { id: payment.id }, data: { status: TransactionStatus.FAILED, gatewayPayload: payload } });
      }
      return null;
    }

    const amount = money(result.amount);
    if (!amount.gt(0)) return null;
    const confirmed = await tx.payment.update({
      where: { id: payment.id },
      data: {
        status: TransactionStatus.SUCCEEDED,
        amount,
        currencyAmount: amount,
        gatewayRef: result.transactionId || null,
        gatewayPayload: payload,
        paidAt: new Date(),
      },
    });
    const updated = await this.postReceipt(tx, order, confirmed, null);
    return { payment: confirmed, order: updated };
  }

  /** Dr cash/bank/clearing — Cr AR (invoiced) or customer advances (not shipped yet). */
  private async postReceipt(tx: Tx, order: Order, payment: Payment, userId: string | null) {
    const amount = dec(payment.amount);
    const invoiced = INVOICED_STATUSES.includes(order.status);
    await this.ledger.post(tx, {
      description: `Receipt ${payment.number} for order ${order.number}`,
      sourceType: 'CUSTOMER_PAYMENT',
      sourceId: payment.id,
      userId,
      lines: [
        { account: { id: payment.accountId }, debit: amount },
        { account: invoiced ? 'AR' : 'CUSTOMER_ADVANCES', credit: amount, customerId: order.customerId },
      ],
    });

    const amountPaid = dec(order.amountPaid).add(amount);
    const updated = await tx.order.update({
      where: { id: order.id },
      data: { amountPaid, paymentStatus: await this.statusOf(tx, order, amountPaid) },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Payment', entityId: payment.id, after: payment }, tx);
    const note = paymentReceivedMessage(order, amount, payment.method);
    if (note) await this.notifications.notifyCustomer(tx, order.customerId, note);
    return updated;
  }

  /** Returns money held as an advance: unshipped, cancelled or refused-delivery orders. */
  async refund(tx: Tx, order: Order, input: MoneyMovement & { reason: string }, userId: string) {
    if (INVOICED_STATUSES.includes(order.status)) {
      throw new UnprocessableEntityException('Order was already shipped; refunds go through a sales return');
    }
    return this.payOut(tx, order, input, userId, 'CUSTOMER_ADVANCES');
  }

  /** Pays a customer back for a received sales return (credited to AR at receipt). */
  async refundReturn(tx: Tx, order: Order, input: MoneyMovement & { reason: string }, userId: string) {
    return this.payOut(tx, order, input, userId, 'AR');
  }

  private async payOut(
    tx: Tx,
    order: Order,
    input: MoneyMovement & { reason: string },
    userId: string,
    counterAccount: 'CUSTOMER_ADVANCES' | 'AR',
  ) {
    if (input.method === PaymentMethod.CASH_ON_DELIVERY) {
      throw new BadRequestException('Refunds are paid in cash, by card reversal, wallet or bank transfer');
    }
    const c = convert(order, input, dec(order.amountPaid), (shown) => `Refund exceeds the amount paid (${shown})`);
    const amount = c.amount;

    const cashAccount = await this.resolveCashAccount(tx, input);
    const number = await this.sequences.next(tx, 'RFND');
    const payment = await tx.payment.create({
      data: {
        number,
        orderId: order.id,
        method: input.method,
        status: TransactionStatus.SUCCEEDED,
        amount: amount.neg(),
        currency: c.currency,
        currencyAmount: c.currencyAmount.neg(),
        exchangeRate: c.exchangeRate,
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
        { account: counterAccount, debit: amount, customerId: order.customerId },
        { account: { id: cashAccount }, credit: amount },
      ],
    });

    const amountPaid = dec(order.amountPaid).sub(amount);
    const updated = await tx.order.update({
      where: { id: order.id },
      data: { amountPaid, paymentStatus: await this.statusOf(tx, order, amountPaid) },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Refund', entityId: payment.id, after: payment }, tx);
    await this.notifications.notifyCustomer(tx, order.customerId, refundMessage(order, amount));
    return { payment, order: updated };
  }

  private async statusOf(tx: Tx, order: Order, paid: Decimal): Promise<PaymentStatus> {
    const refunded = (await tx.payment.count({ where: { orderId: order.id, amount: { lt: 0 } } })) > 0;
    if (paid.isZero()) return refunded ? PaymentStatus.REFUNDED : PaymentStatus.UNPAID;
    if (paid.greaterThanOrEqualTo(dec(order.grandTotal).sub(order.returnedTotal))) {
      return refunded ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.PAID;
    }
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
