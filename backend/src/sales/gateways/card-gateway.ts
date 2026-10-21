import { Decimal } from '../../common/utils/money';

export interface CheckoutRequest {
  /** Our payment attempt id; the gateway echoes it back in its callback. */
  paymentId: string;
  orderNumber: string;
  amount: Decimal;
  customer: { name: string; phone: string; email?: string | null };
  address: { city?: string; street?: string; building?: string; floor?: string; apartment?: string };
}

export interface CheckoutSession {
  /** Hosted page the customer opens to enter card details. */
  checkoutUrl: string;
  /** Gateway-side id of the checkout (for support look-ups). */
  providerRef?: string;
}

/** A verified transaction outcome reported by the gateway. */
export interface GatewayResult {
  paymentId: string;
  success: boolean;
  /** Still processing (e.g. awaiting 3-D Secure); a final callback follows. */
  pending: boolean;
  amount: Decimal;
  transactionId: string;
  payload: Record<string, string>;
}

export abstract class CardGateway {
  abstract readonly name: string;
  abstract createCheckout(req: CheckoutRequest): Promise<CheckoutSession>;
}

export const CARD_GATEWAY = Symbol('CARD_GATEWAY');
