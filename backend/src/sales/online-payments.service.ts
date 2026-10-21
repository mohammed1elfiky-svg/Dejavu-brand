import { createHmac, timingSafeEqual } from 'node:crypto';
import {
  BadGatewayException,
  Injectable,
  Logger,
  NotFoundException,
  OnModuleDestroy,
  OnModuleInit,
  ServiceUnavailableException,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { TransactionStatus } from '../generated/prisma/client';
import { dec, money } from '../common/utils/money';
import { Env } from '../config/env';
import { PrismaService } from '../prisma/prisma.service';
import { CardGateway, CheckoutRequest, CheckoutSession, GatewayResult } from './gateways/card-gateway';
import { PaymobGateway } from './gateways/paymob.gateway';
import { OrdersService } from './orders.service';

/**
 * Development stand-in for a card gateway: a page on this server with "pay" and
 * "decline" buttons. Links are signed so only the customer who opened the
 * checkout can complete it.
 */
class TestGateway extends CardGateway {
  readonly name = 'test';

  constructor(
    private readonly baseUrl: string,
    private readonly sign: (paymentId: string) => string,
  ) {
    super();
  }

  async createCheckout(req: CheckoutRequest): Promise<CheckoutSession> {
    // The phone must be able to open the link, so use the address it reached us on.
    const base = req.requestBaseUrl ?? this.baseUrl;
    return { checkoutUrl: `${base}/api/v1/payments/test/${req.paymentId}?sig=${this.sign(req.paymentId)}` };
  }
}

@Injectable()
export class OnlinePaymentsService implements OnModuleInit, OnModuleDestroy {
  private readonly log = new Logger('OnlinePayments');
  private readonly gateway: CardGateway | null;
  private readonly secret: string;
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly prisma: PrismaService,
    private readonly orders: OrdersService,
    private readonly config: ConfigService<Env, true>,
  ) {
    this.secret = `card-test:${config.get('JWT_ACCESS_SECRET', { infer: true })}`;
    const base = config.get('PUBLIC_BASE_URL', { infer: true });
    switch (config.get('PAYMENT_GATEWAY', { infer: true })) {
      case 'paymob':
        this.gateway = new PaymobGateway({
          apiUrl: config.get('PAYMOB_API_URL', { infer: true }),
          secretKey: config.get('PAYMOB_SECRET_KEY', { infer: true }),
          publicKey: config.get('PAYMOB_PUBLIC_KEY', { infer: true }),
          integrationId: config.get('PAYMOB_INTEGRATION_ID', { infer: true }),
          hmacSecret: config.get('PAYMOB_HMAC_SECRET', { infer: true }),
          notificationUrl: `${base}/api/v1/payments/paymob/callback`,
          redirectionUrl: `${base}/api/v1/payments/return`,
        });
        break;
      case 'test':
        this.gateway = new TestGateway(base, (id) => this.sign(id));
        break;
      default:
        this.gateway = null;
    }
  }

  onModuleInit() {
    const minutes = this.config.get('CARD_PAYMENT_TIMEOUT_MINUTES', { infer: true });
    if (!minutes || process.env.NODE_ENV === 'test' || process.env.JEST_WORKER_ID) return;
    this.timer = setInterval(() => void this.expire(minutes), 5 * 60_000);
    this.timer.unref();
  }

  onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
  }

  get enabled() {
    return this.gateway !== null;
  }

  /** Starts (or restarts) card payment for the customer's order and returns the hosted page to open. */
  async start(customerId: string, orderNumber: string, requestBaseUrl?: string) {
    if (!this.gateway) throw new ServiceUnavailableException('Card payments are not available at the moment');
    const { payment, order, customer } = await this.orders.openCardPayment(customerId, orderNumber, this.gateway.name);
    const address = (order.shippingAddress ?? {}) as Record<string, string | undefined>;
    let session: CheckoutSession;
    try {
      session = await this.gateway.createCheckout({
        paymentId: payment.id,
        orderNumber: order.number,
        amount: dec(payment.amount),
        customer,
        address,
        requestBaseUrl,
      });
    } catch (e) {
      this.log.error(`Checkout for ${order.number} failed: ${(e as Error).message}`);
      await this.prisma.payment.update({ where: { id: payment.id }, data: { status: TransactionStatus.FAILED } });
      throw new BadGatewayException('The payment provider is not responding. Please try again.');
    }
    if (session.providerRef) {
      await this.prisma.payment.update({ where: { id: payment.id }, data: { gatewayPayload: { checkoutId: session.providerRef } } });
    }
    return { paymentId: payment.id, orderNumber: order.number, amount: money(payment.amount), checkoutUrl: session.checkoutUrl };
  }

  /** Paymob "transaction processed" callback. */
  async paymobCallback(body: Record<string, unknown>, hmac: string | undefined) {
    if (!(this.gateway instanceof PaymobGateway)) throw new NotFoundException();
    const result = this.gateway.parseCallback(body, hmac);
    if (!result) return { received: true };
    await this.apply(result);
    return { received: true };
  }

  /** What the result page shows after the customer leaves the gateway. */
  async attemptSummary(paymentId: string | undefined) {
    if (!paymentId || !/^[0-9a-f-]{36}$/i.test(paymentId)) return null;
    return this.prisma.payment.findUnique({
      where: { id: paymentId },
      select: { status: true, amount: true, order: { select: { number: true } } },
    });
  }

  // ------------------------------------------------------ test gateway ---

  sign(paymentId: string) {
    return createHmac('sha256', this.secret).update(paymentId).digest('hex').slice(0, 32);
  }

  async testAttempt(paymentId: string, sig: string | undefined) {
    if (!(this.gateway instanceof TestGateway)) throw new NotFoundException();
    const expected = Buffer.from(this.sign(paymentId));
    const given = Buffer.from(String(sig ?? ''));
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) throw new UnauthorizedException('Invalid link');
    const payment = await this.prisma.payment.findUnique({
      where: { id: paymentId },
      include: { order: { select: { number: true, grandTotal: true } } },
    });
    if (!payment) throw new NotFoundException('Payment not found');
    return payment;
  }

  async testComplete(paymentId: string, sig: string | undefined, success: boolean) {
    const payment = await this.testAttempt(paymentId, sig);
    await this.apply({
      paymentId,
      success,
      pending: false,
      amount: dec(payment.amount),
      transactionId: success ? `test-${paymentId}` : '',
      payload: { card: '512345xxxxxx2346', message: success ? 'Approved' : 'Declined by the test card page' },
    });
    return this.testAttempt(paymentId, sig);
  }

  // -------------------------------------------------------------- shared ---

  private async apply(result: GatewayResult) {
    if (result.pending) return;
    const applied = await this.orders.applyGatewayResult(result);
    this.log.log(`Payment ${result.paymentId}: ${result.success ? 'approved' : 'declined'}${result.success && !applied ? ' (already recorded)' : ''}`);
  }

  private async expire(minutes: number) {
    try {
      const cancelled = await this.orders.expireUnpaidCardOrders(minutes);
      if (cancelled.length) this.log.log(`Cancelled unpaid card orders: ${cancelled.join(', ')}`);
    } catch (e) {
      this.log.error(`Expiring unpaid card orders failed: ${(e as Error).message}`);
    }
  }
}
