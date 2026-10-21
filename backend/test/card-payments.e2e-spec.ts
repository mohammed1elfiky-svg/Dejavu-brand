import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { OrdersService } from '../src/sales/orders.service';
import { API, api, createApp, createProduct, customerToken, ledgerBalances, staffToken } from './helpers';

/** Card payments through the built-in test gateway (PAYMENT_GATEWAY=test). */
describe('Card payments (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let shopper: { token: string; customerId: string };
  let addressId: string;
  let variant: string;
  let warehouseId: string;
  const run = Date.now().toString(36);

  async function placeOrder(paymentMethod = 'CARD') {
    await http.post('/store/cart/items', { variantId: variant, quantity: 1 }, shopper.token).expect(201);
    const res = await http.post('/store/orders', { addressId, paymentMethod }, shopper.token).expect(201);
    return res.body as { id: string; number: string; grandTotal: string };
  }

  async function startPayment(number: string) {
    const res = await http.post(`/store/orders/${number}/pay`, {}, shopper.token).expect(200);
    const url = new URL(res.body.checkoutUrl);
    return { ...res.body, path: url.pathname, sig: url.searchParams.get('sig')! } as {
      paymentId: string;
      amount: string;
      path: string;
      sig: string;
    };
  }

  const submit = (path: string, sig: string, outcome: string) =>
    request(app.getHttpServer()).post(path).type('form').send({ sig, outcome });

  async function reserved() {
    return (await prisma.stockLevel.findUniqueOrThrow({ where: { variantId_warehouseId: { variantId: variant, warehouseId } } })).reserved;
  }

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    warehouseId = (await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } })).id;
    [variant] = await createProduct(app, admin, `card-${run}`, 1000, ['M']);
    await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'OPENING', items: [{ variantId: variant, quantity: 10, unitCost: 400 }] }, admin)
      .expect(201);
    await http.put('/admin/shipping-zones', { governorate: 'الجيزة', fee: 60 }, admin).expect(200);
    shopper = await customerToken(app, 'عميل البطاقة');
    const addr = await http
      .post('/store/me/addresses', { recipient: 'عميل البطاقة', phone: '01022223333', governorate: 'الجيزة', city: 'الدقي', street: 'شارع التحرير' }, shopper.token)
      .expect(201);
    addressId = addr.body.id;
  });
  afterAll(() => app.close());

  it('offers card payment at checkout', async () => {
    const res = await http.get('/store/payment-methods').expect(200);
    expect(res.body).toEqual(expect.arrayContaining(['CARD', 'CASH_ON_DELIVERY']));
  });

  let order: { id: string; number: string; grandTotal: string };

  it('a declined card leaves the order unpaid and posts nothing', async () => {
    order = await placeOrder();
    expect(order.grandTotal).toBe('1060');
    const attempt = await startPayment(order.number);
    expect(attempt.amount).toBe('1060');

    await request(app.getHttpServer()).get(`${attempt.path}?sig=wrong`).expect(401);
    const pageRes = await request(app.getHttpServer()).get(`${attempt.path}?sig=${attempt.sig}`).expect(200);
    expect(pageRes.text).toContain(order.number);

    await submit(attempt.path, 'nope', 'failure').expect(401);
    const declined = await submit(attempt.path, attempt.sig, 'failure').expect(200);
    expect(declined.text).toContain('لم تتم عملية الدفع');

    const payment = await prisma.payment.findUniqueOrThrow({ where: { id: attempt.paymentId } });
    expect(payment.status).toBe('FAILED');
    const o = await http.get(`/store/orders/${order.number}`, shopper.token).expect(200);
    expect(o.body).toMatchObject({ status: 'PENDING', paymentStatus: 'UNPAID', amountPaid: '0' });
    expect(await ledgerBalances(prisma, [attempt.paymentId])).toEqual({});
  });

  it('a successful retry books the money once and confirms the order', async () => {
    const attempt = await startPayment(order.number);
    const ok = await submit(attempt.path, attempt.sig, 'success').expect(200);
    expect(ok.text).toContain('تم الدفع بنجاح');
    // The gateway may report the same transaction again.
    await submit(attempt.path, attempt.sig, 'success').expect(200);

    const o = await http.get(`/store/orders/${order.number}`, shopper.token).expect(200);
    expect(o.body).toMatchObject({ status: 'CONFIRMED', paymentStatus: 'PAID', amountPaid: '1060' });
    // Not shipped yet: the money is a customer advance sitting with the gateway.
    expect(await ledgerBalances(prisma, [attempt.paymentId])).toEqual({ GATEWAY_CLEARING: 1060, CUSTOMER_ADVANCES: -1060 });

    const adminView = await http.get(`/admin/orders/${order.id}`, admin).expect(200);
    const statuses = adminView.body.payments.map((p: { status: string }) => p.status);
    expect(statuses).toEqual(['FAILED', 'SUCCEEDED']);

    await http.post(`/store/orders/${order.number}/pay`, {}, shopper.token).expect(422);
  });

  it('shipping then moves the advance against the invoice', async () => {
    await http.post(`/admin/orders/${order.id}/status`, { status: 'SHIPPED', carrier: 'Bosta' }, admin).expect(200);
    const shipment = await prisma.shipment.findFirstOrThrow({ where: { orderId: order.id } });
    expect(shipment.codAmount.toString()).toBe('0');
    const b = await ledgerBalances(prisma, [order.id]);
    expect(b.AR ?? 0).toBe(0);
    expect(b.CUSTOMER_ADVANCES).toBe(1060);
  });

  it('only card orders can be paid online', async () => {
    const cod = await placeOrder('CASH_ON_DELIVERY');
    const res = await http.post(`/store/orders/${cod.number}/pay`, {}, shopper.token).expect(422);
    expect(res.body.message).toContain('not paid by card');
    const other = await customerToken(app);
    await http.post(`/store/orders/${order.number}/pay`, {}, other.token).expect(404);
  });

  it('cancels unpaid card orders after the timeout and releases their stock', async () => {
    const before = await reserved();
    const stale = await placeOrder();
    const fresh = await placeOrder();
    expect(await reserved()).toBe(before + 2);
    const twoHoursAgo = new Date(Date.now() - 2 * 3_600_000);
    await prisma.order.updateMany({ where: { id: { in: [stale.id, fresh.id] } }, data: { placedAt: twoHoursAgo } });
    // The customer is on the card page right now for this one.
    await startPayment(fresh.number);

    const cancelled = await app.get(OrdersService).expireUnpaidCardOrders(60);
    expect(cancelled).toContain(stale.number);
    expect(cancelled).not.toContain(fresh.number);
    const s = await http.get(`/store/orders/${stale.number}`, shopper.token).expect(200);
    expect(s.body.status).toBe('CANCELLED');
    expect(await reserved()).toBe(before + 1);
  });

  it('returns a result page for the browser after the gateway', async () => {
    const res = await request(app.getHttpServer()).get(`${API}/payments/return?merchant_order_id=not-a-uuid`).expect(200);
    expect(res.text).toContain('dejavu://payment');
  });
});
