import { INestApplication } from '@nestjs/common';
import { NotificationsService } from '../src/notifications/notifications.service';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, createProduct, customerToken, staffToken } from './helpers';

describe('Notifications (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let shopper: { token: string; customerId: string };
  let addressId: string;
  let variant: string;
  const run = Date.now().toString(36);

  async function placeOrder() {
    await http.post('/store/cart/items', { variantId: variant, quantity: 1 }, shopper.token).expect(201);
    const res = await http.post('/store/orders', { addressId, paymentMethod: 'CASH_ON_DELIVERY' }, shopper.token).expect(201);
    return res.body as { id: string; number: string };
  }

  const inbox = async (token = shopper.token) => (await http.get('/store/notifications', token).expect(200)).body;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const warehouseId = (await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } })).id;
    [variant] = await createProduct(app, admin, `notif-${run}`, 500, ['S']);
    await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'OPENING', items: [{ variantId: variant, quantity: 5, unitCost: 200 }] }, admin)
      .expect(201);
    await http.put('/admin/shipping-zones', { governorate: 'الإسكندرية', fee: 70 }, admin).expect(200);
    shopper = await customerToken(app, 'عميل الإشعارات');
    const addr = await http
      .post('/store/me/addresses', { recipient: 'عميل', phone: '01033334444', governorate: 'الإسكندرية', city: 'سموحة', street: 'شارع فوزي معاذ' }, shopper.token)
      .expect(201);
    addressId = addr.body.id;
  });
  afterAll(() => app.close());

  let orderNumber: string;

  it('tells the customer about each step of their order', async () => {
    const order = await placeOrder();
    orderNumber = order.number;
    expect((await inbox()).items).toHaveLength(0);

    await http.post(`/admin/orders/${order.id}/status`, { status: 'CONFIRMED' }, admin).expect(200);
    await http
      .post(`/admin/orders/${order.id}/status`, { status: 'SHIPPED', carrier: 'Bosta', trackingNumber: `TRK${run}` }, admin)
      .expect(200);

    const box = await inbox();
    expect(box.unread).toBe(2);
    expect(box.items.map((n: { title: string }) => n.title)).toEqual(['طلبك في الطريق 🚚', 'تم تأكيد طلبك']);
    expect(box.items[0].body).toContain(`TRK${run}`);
    expect(box.items[0].data).toEqual({ order: orderNumber, status: 'SHIPPED' });
  });

  it('marks notifications as read', async () => {
    const box = await inbox();
    const read = await http.post(`/store/notifications/${box.items[0].id}/read`, {}, shopper.token).expect(200);
    expect(read.body.unread).toBe(1);

    const stranger = await customerToken(app);
    await http.post(`/store/notifications/${box.items[1].id}/read`, {}, stranger.token).expect(404);
    expect((await inbox(stranger.token)).items).toHaveLength(0);

    await http.post('/store/notifications/read-all', {}, shopper.token).expect(200);
    expect((await http.get('/store/notifications/unread-count', shopper.token).expect(200)).body.unread).toBe(0);
  });

  it('does not notify customers about their own cancellation', async () => {
    const before = (await inbox()).total;
    const order = await placeOrder();
    await http.post(`/store/orders/${order.number}/cancel`, { reason: 'غيرت رأيي' }, shopper.token).expect(200);
    expect((await inbox()).total).toBe(before);
  });

  it('registers devices per app and moves a token to whoever signs in on it', async () => {
    const token = `fcm-token-${run}-abcdefghijklmnopqrstuvwxyz`;
    const res = await http.post('/store/devices', { token, appVersion: '1.0.0' }, shopper.token).expect(200);
    expect(res.body).toEqual({ registered: true, pushEnabled: false });
    let device = await prisma.device.findUniqueOrThrow({ where: { fcmToken: token } });
    expect(device).toMatchObject({ appType: 'CUSTOMER', customerId: shopper.customerId, userId: null });

    // Same phone, now used for the admin app by a staff member.
    await http.post('/admin/devices', { token }, admin).expect(200);
    device = await prisma.device.findUniqueOrThrow({ where: { fcmToken: token } });
    expect(device).toMatchObject({ appType: 'ADMIN', customerId: null });

    await http.post('/admin/devices', { token }, shopper.token).expect(403);
    await http.delete('/admin/devices', admin).send({ token }).expect(204);
    expect(await prisma.device.count({ where: { fcmToken: token } })).toBe(0);
  });

  it('marks everything as handled by the push dispatcher', async () => {
    await app.get(NotificationsService).dispatch();
    expect(await prisma.notification.count({ where: { customerId: shopper.customerId, pushedAt: null } })).toBe(0);
  });
});
