import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, createProduct, customerToken, ledgerBalances, staffToken } from './helpers';

/** SAR base currency, YER sales at a rate locked into each order. */
describe('Currencies & exchange rates (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let shopper: { token: string; customerId: string };
  let addressId: string;
  let variant: string;
  const run = Date.now().toString(36);

  async function placeOrder(paymentMethod: string, currency?: string) {
    await http.post('/store/cart/items', { variantId: variant, quantity: 1 }, shopper.token).expect(201);
    const res = await http.post('/store/orders', { addressId, paymentMethod, currency }, shopper.token).expect(201);
    return res.body;
  }

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const warehouseId = (await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } })).id;
    [variant] = await createProduct(app, admin, `fx-${run}`, 100, ['L']);
    await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'OPENING', items: [{ variantId: variant, quantity: 10, unitCost: 40 }] }, admin)
      .expect(201);
    await http.put('/admin/shipping-zones', { governorate: 'عدن', fee: 20 }, admin).expect(200);
    shopper = await customerToken(app, 'عميل الريال');
    const addr = await http
      .post('/store/me/addresses', { recipient: 'عميل', phone: '01044445555', governorate: 'عدن', city: 'المنصورة', street: 'الشارع الرئيسي' }, shopper.token)
      .expect(201);
    addressId = addr.body.id;
  });
  afterAll(() => app.close());

  it('publishes SAR as the base and YER with its rate; only settings managers change it', async () => {
    await http.put('/admin/exchange-rates/YER', { rate: '0' }, admin).expect(422);
    await http.put('/admin/exchange-rates/SAR', { rate: '2' }, admin).expect(422);
    await http.put('/admin/exchange-rates/YER', { rate: '150' }, shopper.token).expect(403);
    const set = await http.put('/admin/exchange-rates/YER', { rate: '150', note: 'test' }, admin).expect(200);
    expect(set.body.history[0]).toMatchObject({ rate: '150', note: 'test' });

    const res = await http.get('/store/currencies').expect(200);
    expect(res.body.base).toBe('SAR');
    expect(res.body.currencies).toEqual([
      expect.objectContaining({ code: 'SAR', rate: '1', decimals: 2 }),
      expect.objectContaining({ code: 'YER', rate: '150', decimals: 0, symbol: 'ر.ي' }),
    ]);
  });

  let yerOrder: { id: string; number: string };

  it('locks the rate into a YER order and shows its invoice in YER', async () => {
    const order = await placeOrder('BANK_TRANSFER', 'YER');
    yerOrder = order;
    // 100 (VAT incl.) + 20 shipping = 120 SAR = 18,000 YER
    expect(order).toMatchObject({ currency: 'YER', exchangeRate: '150', grandTotal: '120' });
    expect(order.inCurrency).toMatchObject({ currency: 'YER', grandTotal: '18000', shippingFee: '3000', balanceDue: '18000' });

    await http.put('/admin/exchange-rates/YER', { rate: '160' }, admin).expect(200);
    const again = await http.get(`/store/orders/${order.number}`, shopper.token).expect(200);
    expect(again.body.inCurrency.grandTotal).toBe('18000');
    const list = await http.get('/store/orders', shopper.token).expect(200);
    expect(list.body.items.find((o: { number: string }) => o.number === order.number).grandTotalInCurrency).toBe('18000');
  });

  it('takes payments in YER at the order rate, with no rounding remainder', async () => {
    await http.post(`/admin/orders/${yerOrder.id}/payments`, { method: 'CASH', amount: '6000.5', currency: 'YER' }, admin).expect(400);
    const part = await http.post(`/admin/orders/${yerOrder.id}/payments`, { method: 'CASH', amount: '7000', currency: 'YER' }, admin).expect(201);
    // 7000 / 150 = 46.666… → 46.67 SAR
    expect(part.body).toMatchObject({ amountPaid: '46.67', paymentStatus: 'PARTIALLY_PAID' });
    expect(part.body.inCurrency.balanceDue).toBe('11000');

    const over = await http.post(`/admin/orders/${yerOrder.id}/payments`, { method: 'CASH', amount: '11001', currency: 'YER' }, admin).expect(422);
    expect(over.body.message).toContain('11000 YER');

    const rest = await http.post(`/admin/orders/${yerOrder.id}/payments`, { method: 'CASH', amount: '11000', currency: 'YER' }, admin).expect(201);
    expect(rest.body).toMatchObject({ amountPaid: '120', paymentStatus: 'PAID', status: 'CONFIRMED' });
    expect(rest.body.inCurrency.balanceDue).toBe('0');
    const yer = rest.body.payments.map((p: { currency: string; currencyAmount: string; amount: string }) => [p.currency, p.currencyAmount, p.amount]);
    expect(yer).toEqual([
      ['YER', '7000', '46.67'],
      ['YER', '11000', '73.33'],
    ]);
    // The books stay in SAR; the riyals themselves sit in the YER safe.
    const ids = rest.body.payments.map((p: { id: string }) => p.id);
    const safe = await prisma.cashBox.findFirstOrThrow({ where: { kind: 'VAULT', currency: 'YER' }, include: { account: true } });
    expect(await ledgerBalances(prisma, ids)).toEqual({ [safe.account.code]: 120, CUSTOMER_ADVANCES: -120 });
    const held = await prisma.cashBoxEntry.findMany({ where: { cashBoxId: safe.id, sourceId: { in: ids } } });
    expect(held.map((e) => e.amount.toString()).sort()).toEqual(['11000', '7000']);
  });

  it('refunds in YER too', async () => {
    const res = await http
      .post(`/admin/orders/${yerOrder.id}/refunds`, { method: 'CASH', amount: '18000', currency: 'YER', reason: 'إلغاء' }, admin)
      .expect(201);
    expect(res.body).toMatchObject({ amountPaid: '0', paymentStatus: 'REFUNDED' });
    expect(res.body.payments.at(-1)).toMatchObject({ currency: 'YER', currencyAmount: '-18000', amount: '-120' });
  });

  it('rejects a currency that is neither SAR nor the order currency', async () => {
    const sar = await placeOrder('BANK_TRANSFER');
    expect(sar).toMatchObject({ currency: 'SAR', exchangeRate: '1' });
    expect(sar.inCurrency.grandTotal).toBe('120');
    const res = await http.post(`/admin/orders/${sar.id}/payments`, { method: 'CASH', amount: '100', currency: 'YER' }, admin).expect(422);
    expect(res.body.message).toContain("order's currency");
  });

  it('records cash on delivery of a YER order in YER', async () => {
    const order = await placeOrder('CASH_ON_DELIVERY', 'YER');
    expect(order.exchangeRate).toBe('160');
    await http.post(`/admin/orders/${order.id}/status`, { status: 'CONFIRMED' }, admin).expect(200);
    await http.post(`/admin/orders/${order.id}/status`, { status: 'SHIPPED', carrier: 'Local' }, admin).expect(200);
    const delivered = await http.post(`/admin/orders/${order.id}/status`, { status: 'DELIVERED' }, admin).expect(200);
    expect(delivered.body.payments[0]).toMatchObject({ method: 'CASH_ON_DELIVERY', currency: 'YER', currencyAmount: '19200', amount: '120' });
  });
});
