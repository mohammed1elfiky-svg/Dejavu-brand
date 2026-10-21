import { createHmac, timingSafeEqual } from 'node:crypto';
import { Logger, UnauthorizedException } from '@nestjs/common';
import { dec } from '../../common/utils/money';
import { CardGateway, CheckoutRequest, CheckoutSession, GatewayResult } from './card-gateway';

export interface PaymobConfig {
  apiUrl: string;
  secretKey: string;
  publicKey: string;
  integrationId: string;
  hmacSecret: string;
  /** Account currency, e.g. SAR (Paymob KSA). Orders are charged their SAR (base) amount. */
  currency: string;
  /** Where Paymob POSTs transaction results. */
  notificationUrl: string;
  /** Where the customer's browser lands after paying. */
  redirectionUrl: string;
}

/**
 * Fields of a "transaction processed" callback that Paymob signs, in this exact
 * order (lexicographic by key). Values are concatenated without separators and
 * signed with HMAC-SHA512 using the account's HMAC secret.
 */
export const PAYMOB_HMAC_FIELDS = [
  'amount_cents',
  'created_at',
  'currency',
  'error_occured',
  'has_parent_transaction',
  'id',
  'integration_id',
  'is_3d_secure',
  'is_auth',
  'is_capture',
  'is_refunded',
  'is_standalone_payment',
  'is_voided',
  'order.id',
  'owner',
  'pending',
  'source_data.pan',
  'source_data.sub_type',
  'source_data.type',
  'success',
] as const;

type Json = Record<string, unknown>;

function pick(obj: Json, path: string): string {
  const v = path.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Json)[k] : undefined), obj);
  return v === undefined || v === null ? '' : String(v);
}

export function paymobHmac(obj: Json, secret: string): string {
  const data = PAYMOB_HMAC_FIELDS.map((f) => pick(obj, f)).join('');
  return createHmac('sha512', secret).update(data).digest('hex');
}

/** Paymob (Intention API + Unified Checkout). Use the KSA endpoint for SAR accounts. */
export class PaymobGateway extends CardGateway {
  readonly name = 'paymob';
  private readonly log = new Logger('Paymob');

  constructor(private readonly cfg: PaymobConfig) {
    super();
  }

  async createCheckout(req: CheckoutRequest): Promise<CheckoutSession> {
    const cents = Number(req.amount.mul(100).toFixed(0));
    const [first, ...rest] = req.customer.name.trim().split(/\s+/);
    const na = (v?: string) => (v && v.trim() ? v.trim() : 'NA');
    const body = {
      amount: cents,
      currency: this.cfg.currency,
      payment_methods: [Number(this.cfg.integrationId)],
      items: [{ name: `Order ${req.orderNumber}`, amount: cents, description: `Dejavu order ${req.orderNumber}`, quantity: 1 }],
      billing_data: {
        first_name: first || 'Customer',
        last_name: rest.join(' ') || 'Dejavu',
        phone_number: req.customer.phone,
        email: req.customer.email || 'customer@dejavu.store',
        city: na(req.address.city),
        street: na(req.address.street),
        building: na(req.address.building),
        floor: na(req.address.floor),
        apartment: na(req.address.apartment),
        state: na(req.address.city),
        country: 'EG',
      },
      special_reference: req.paymentId,
      notification_url: this.cfg.notificationUrl,
      redirection_url: this.cfg.redirectionUrl,
    };
    const res = await fetch(`${this.cfg.apiUrl}/v1/intention/`, {
      method: 'POST',
      headers: { Authorization: `Token ${this.cfg.secretKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    });
    const text = await res.text();
    if (!res.ok) {
      this.log.error(`Intention failed (${res.status}): ${text.slice(0, 500)}`);
      throw new Error(`Paymob rejected the payment request (${res.status})`);
    }
    const json = JSON.parse(text) as { id?: string | number; client_secret?: string };
    if (!json.client_secret) throw new Error('Paymob response has no client_secret');
    const url = new URL(`${this.cfg.apiUrl}/unifiedcheckout/`);
    url.searchParams.set('publicKey', this.cfg.publicKey);
    url.searchParams.set('clientSecret', json.client_secret);
    return { checkoutUrl: url.toString(), providerRef: json.id === undefined ? undefined : String(json.id) };
  }

  /** Verifies and decodes a transaction-processed callback; null for other callback types. */
  parseCallback(body: Json, hmac: string | undefined): GatewayResult | null {
    if (body?.type !== 'TRANSACTION' || !body.obj || typeof body.obj !== 'object') return null;
    const obj = body.obj as Json;
    const expected = Buffer.from(paymobHmac(obj, this.cfg.hmacSecret), 'hex');
    const given = Buffer.from(String(hmac ?? ''), 'hex');
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) {
      throw new UnauthorizedException('Invalid HMAC');
    }
    const paymentId = pick(obj, 'order.merchant_order_id') || pick(obj, 'special_reference');
    return {
      paymentId,
      success: obj.success === true,
      pending: obj.pending === true,
      amount: dec(pick(obj, 'amount_cents') || 0).div(100),
      transactionId: pick(obj, 'id'),
      payload: {
        transactionId: pick(obj, 'id'),
        paymobOrderId: pick(obj, 'order.id'),
        card: pick(obj, 'source_data.pan'),
        cardType: pick(obj, 'source_data.sub_type'),
        message: pick(obj, 'data.message'),
      },
    };
  }
}
