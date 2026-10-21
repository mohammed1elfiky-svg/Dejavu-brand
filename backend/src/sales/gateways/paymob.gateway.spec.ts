import { createHmac } from 'node:crypto';
import { UnauthorizedException } from '@nestjs/common';
import { PAYMOB_HMAC_FIELDS, PaymobGateway, paymobHmac } from './paymob.gateway';

const SECRET = 'hmac-secret';

const gateway = new PaymobGateway({
  apiUrl: 'https://accept.paymob.com',
  secretKey: 'sk',
  publicKey: 'pk',
  integrationId: '123',
  hmacSecret: SECRET,
  notificationUrl: 'https://api.example/cb',
  redirectionUrl: 'https://api.example/return',
});

const transaction = () => ({
  id: 192036465,
  pending: false,
  amount_cents: 106000,
  success: true,
  is_auth: false,
  is_capture: false,
  is_standalone_payment: true,
  is_voided: false,
  is_refunded: false,
  is_3d_secure: true,
  integration_id: 123,
  has_parent_transaction: false,
  order: { id: 217503754, merchant_order_id: '0b5a0f1e-1111-4222-8333-944445555666' },
  created_at: '2026-10-21T03:12:40.149437',
  currency: 'EGP',
  source_data: { pan: '2346', type: 'card', sub_type: 'MasterCard' },
  error_occured: false,
  owner: 302852,
  data: { message: 'Approved' },
});

describe('Paymob callback verification', () => {
  it('signs the 20 documented fields in order, booleans as true/false', () => {
    const t = transaction();
    const concatenated =
      '106000' + '2026-10-21T03:12:40.149437' + 'EGP' + 'false' + 'false' + '192036465' + '123' + 'true' + 'false' + 'false' +
      'false' + 'true' + 'false' + '217503754' + '302852' + 'false' + '2346' + 'MasterCard' + 'card' + 'true';
    expect(PAYMOB_HMAC_FIELDS).toHaveLength(20);
    expect(paymobHmac(t, SECRET)).toBe(createHmac('sha512', SECRET).update(concatenated).digest('hex'));
  });

  it('accepts a correctly signed transaction and maps it to our attempt', () => {
    const t = transaction();
    const result = gateway.parseCallback({ type: 'TRANSACTION', obj: t }, paymobHmac(t, SECRET));
    expect(result).toMatchObject({
      paymentId: '0b5a0f1e-1111-4222-8333-944445555666',
      success: true,
      pending: false,
      transactionId: '192036465',
    });
    expect(result!.amount.toString()).toBe('1060');
  });

  it('rejects tampered or unsigned callbacks', () => {
    const t = transaction();
    const hmac = paymobHmac(t, SECRET);
    t.amount_cents = 1;
    expect(() => gateway.parseCallback({ type: 'TRANSACTION', obj: t }, hmac)).toThrow(UnauthorizedException);
    expect(() => gateway.parseCallback({ type: 'TRANSACTION', obj: t }, undefined)).toThrow(UnauthorizedException);
  });

  it('ignores callbacks that are not transactions', () => {
    expect(gateway.parseCallback({ type: 'TOKEN', obj: {} }, 'x')).toBeNull();
  });
});
