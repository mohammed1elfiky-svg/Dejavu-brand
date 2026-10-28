import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { PreOrdersService } from '../src/sales/preorders.service';
import { API, api, createApp, customerToken, staffToken, uniquePng } from './helpers';

/**
 * Round 8: numbers from any country, the code by WhatsApp on request, wallets paying in any
 * currency, the transfer register, reservations with the orders, pre-orders confirmed by the
 * customer or taken by staff, merchants' orders waiting for approval, live coupons.
 */
describe('Round 8 (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);
  let made = 0;

  const product = async (name: string, price: number, qty: number, status = 'ACTIVE') =>
    (
      await http
        .post(
          '/admin/products',
          { categoryId, nameAr: `${name} ${run}`, slug: `r8-${run}-${++made}`, status, basePrice: String(price), costPrice: String(price / 2), variants: [{ size: '38', initialQuantity: qty }] },
          admin,
        )
        .expect(201)
    ).body as { id: string; slug: string; variants: { id: string }[] };

  const address = async (token: string) =>
    (await http.post('/store/me/addresses', { recipient: 'عميلة', phone: '771234567', governorate: 'تعز', city: 'تعز', street: 'شارع جمال' }, token).expect(201)).body.id as string;

  const slip = async (token: string) =>
    (
      await request(app.getHttpServer())
        .post(`${API}/store/me/files`)
        .set('Authorization', `Bearer ${token}`)
        .attach('file', uniquePng(), { filename: 'slip.png', contentType: 'image/png' })
        .expect(201)
    ).body.id as string;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/YER', { rate: '140' }, admin).expect(200);
    await http.put('/admin/shipping-zones', { governorate: 'تعز', fee: 0 }, admin).expect(200);
    categoryId = (await http.post('/admin/categories', { nameAr: `جولة ٨ ${run}`, nameEn: `Round8 ${run}`, slug: `round8-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(async () => {
    await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin);
    await app.close();
  });

  it('signs up numbers from any country, starts everyone in SAR, and sends the code the way the customer chose', async () => {
    const uae = `+9715${String(Date.now()).slice(-8)}`;
    const res = await http.post('/auth/customer/register', { name: 'عميلة إماراتية', phone: uae, password: 'secret123' }).expect(201);
    expect(res.body.customer).toMatchObject({ phone: uae, preferredCurrency: 'SAR' });
    await http.post('/auth/customer/login', { phone: `00${uae.slice(1)}`, password: 'secret123' }).expect(200);
    await http.post('/auth/customer/register', { name: 'رقم ناقص', phone: '+97150', password: 'secret123' }).expect(400);

    await http.put('/admin/sms/settings', { requirePhoneOtp: true }, admin).expect(200);
    try {
      const yemeni = `77${String(Date.now()).slice(-7)}`;
      const byWhatsapp = (await http.post('/auth/customer/register', { name: 'تفضل واتساب', phone: yemeni, password: 'secret123', otpChannel: 'WHATSAPP' }).expect(201)).body;
      expect(byWhatsapp).toMatchObject({ pending: true, sent: false, channel: 'WHATSAPP' });
      expect(await prisma.outgoingSms.count({ where: { phone: { contains: yemeni } } })).toBe(0);
      const bySms = (await http.post('/auth/customer/otp', { phone: yemeni, channel: 'SMS' }).expect(200)).body;
      expect(bySms).toMatchObject({ sent: true, channel: 'SMS' });
    } finally {
      await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin).expect(200);
    }
  });

  it('pays a YER order from SAR credit, changing just what is needed', async () => {
    const p = await product('كعب بالريال', 50, 5);
    const c = await customerToken(app, 'رصيد سعودي');
    await http.post(`/admin/customers/${c.customerId}/account/opening-balance`, { currency: 'SAR', amount: '100', direction: 'CREDIT' }, admin).expect(201);
    const addr = await address(c.token);
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const order = (await http.post('/store/orders', { addressId: addr, paymentMethod: 'CUSTOMER_ACCOUNT', currency: 'YER' }, c.token).expect(201)).body;
    expect(order).toMatchObject({ paymentStatus: 'PAID', status: 'CONFIRMED' });
    const balances = (await http.get('/store/me/wallet', c.token).expect(200)).body.balances as { currency: string; balance: string }[];
    expect(balances.find((b) => b.currency === 'SAR')?.balance).toBe('50');
    expect(balances.find((b) => b.currency === 'YER')).toBeUndefined();
    // 50 SAR left: not enough for 2 more.
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 2 }, c.token).expect(201);
    await http.post('/store/orders', { addressId: addr, paymentMethod: 'CUSTOMER_ACCOUNT', currency: 'YER' }, c.token).expect(422);
  });

  it('keeps every transfer on the register: app top-ups, reversals and till sales', async () => {
    const c = await customerToken(app, 'شحن تلقائي');
    const t = (await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '80', receiptFileId: await slip(c.token), reference: `REF ${run}` }, c.token).expect(201)).body;
    expect(t).toMatchObject({ status: 'APPROVED', autoApproved: true });
    const listed = (await http.get(`/admin/bank-transfers?q=${run}`, admin).expect(200)).body.items;
    expect(listed[0]).toMatchObject({ reference: `REF${run.toUpperCase()}`, source: 'TOP_UP', customer: { id: c.customerId } });
    // The money never came: taken back out of the wallet; the reference stays used.
    await http.post(`/admin/wallet/top-ups/${t.id}/reverse`, { reason: 'لم يصل المبلغ' }, admin).expect(200);
    const wallet = (await http.get('/store/me/wallet', c.token).expect(200)).body.balances as { currency: string; balance: string }[];
    expect(wallet.find((b) => b.currency === 'SAR')?.balance ?? '0').toBe('0');
    await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '80', receiptFileId: await slip(c.token), reference: `ref-${run}` }, c.token).expect(409);

    // The same photo twice is refused even without a number.
    const photo = await slip(c.token);
    await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '10', receiptFileId: photo }, c.token).expect(201);
    const scan = (await http.post('/store/me/wallet/top-ups/scan', { receiptFileId: photo }, c.token).expect(200)).body;
    expect(scan.duplicate).toContain('مستخدمة');

    // Till: a transfer reference used in the app is refused at the till too.
    const p = await product('شنطة تحويل', 40, 3);
    const items = [{ variantId: p.variants[0].id, quantity: 1 }];
    await http.post('/admin/pos/sales', { items, saleType: 'CASH', paymentMethod: 'BANK_TRANSFER', reference: `REF-${run}`, payments: [{ currency: 'SAR', amount: '40' }] }, admin).expect(409);
    const sale = (await http.post('/admin/pos/sales', { items, saleType: 'CASH', paymentMethod: 'BANK_TRANSFER', reference: `POS-${run}`, payments: [{ currency: 'SAR', amount: '40' }] }, admin).expect(201)).body;
    await http.post('/admin/pos/sales', { items, saleType: 'CASH', paymentMethod: 'BANK_TRANSFER', reference: `pos ${run}`, payments: [{ currency: 'SAR', amount: '40' }] }, admin).expect(409);
    // Deleting the sale frees its reference.
    await http.post(`/admin/pos/sales/${sale.id}/void`, { reason: 'خطأ' }, admin).expect(200);
    await http.post('/admin/pos/sales', { items, saleType: 'CASH', paymentMethod: 'BANK_TRANSFER', reference: `POS-${run}`, payments: [{ currency: 'SAR', amount: '40' }] }, admin).expect(201);
  });

  it('asks the customer to confirm an arrived pre-order, then shows it with the orders to put aside', async () => {
    const p = await product('كعب قادم', 120, 0);
    const v = p.variants[0].id;
    const supplier = (await http.post('/admin/suppliers', { name: `مصنع ٨ ${run}` }, admin).expect(201)).body;
    const po = (await http.post('/admin/purchase-orders/import', { supplierId: supplier.id, foreignPerUsd: '7', items: [{ variantId: v, quantity: 2, unitPriceForeign: '70' }] }, admin).expect(201)).body;
    const c = await customerToken(app, 'طلب مسبق');
    const mine = (await http.post('/store/pre-orders', { variantId: v, quantity: 1 }, c.token).expect(201)).body;
    await http.post(`/store/pre-orders/${mine.id}/confirm`, {}, c.token).expect(422); // not here yet

    await http.post(`/admin/purchase-orders/${po.id}/import-receive`, { items: [{ itemId: po.items[0].id, quantity: 2 }], totalCbm: '1', cbmRateUsd: '10' }, admin).expect(200);
    await app.get(PreOrdersService).checkArrivals();
    const arrived = (await http.get('/store/pre-orders', c.token).expect(200)).body.find((x: { id: string }) => x.id === mine.id);
    expect(arrived).toMatchObject({ status: 'NOTIFIED', awaitingConfirmation: true });

    const done = (await http.post(`/store/pre-orders/${mine.id}/confirm`, {}, c.token).expect(200)).body;
    expect(done.preOrder.status).toBe('CONVERTED');
    const toHold = (await http.get('/admin/layaways?toHold=true&pageSize=100', admin).expect(200)).body.items as { id: string }[];
    expect(toHold.map((l) => l.id)).toContain(done.layaway.id);
    const held = (await http.post(`/admin/layaways/${done.layaway.id}/hold`, {}, admin).expect(200)).body;
    expect(held.heldAt).toBeTruthy();
    await http.post(`/admin/layaways/${done.layaway.id}/hold`, {}, admin).expect(422);
    const after = (await http.get('/admin/layaways?toHold=true&pageSize=100', admin).expect(200)).body.items as { id: string }[];
    expect(after.map((l) => l.id)).not.toContain(done.layaway.id);
  });

  it('lets staff pre-order a piece not in the app yet, edit it, and put it aside when it arrives', async () => {
    const c = await customerToken(app, 'طلب من المحل');
    const pre = (await http.post('/admin/pre-orders', { customerId: c.customerId, itemName: 'كعب لامع', itemSize: '39', quantity: 1, unitPrice: '150' }, admin).expect(201)).body;
    expect(pre).toMatchObject({ byStaff: true, name: 'كعب لامع', size: '39', variantId: null });
    await http.post(`/admin/pre-orders/${pre.id}/arrived`, {}, admin).expect(422); // not linked yet

    // The piece is added (hidden from the app) and the pre-order linked to it.
    const p = await product('كعب لامع', 180, 1, 'DRAFT');
    const linked = (await http.patch(`/admin/pre-orders/${pre.id}`, { variantId: p.variants[0].id, quantity: 1 }, admin).expect(200)).body;
    expect(linked).toMatchObject({ variantId: p.variants[0].id, unitPrice: '150' });
    const res = (await http.post(`/admin/pre-orders/${pre.id}/arrived`, {}, admin).expect(200)).body;
    expect(res.status).toBe('CONVERTED');
    const lay = (await http.get(`/admin/layaways/${res.layawayId}`, admin).expect(200)).body;
    expect(lay).toMatchObject({ total: '150', status: 'OPEN' });
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: 'طلبك قيد التجهيز 🛍️' } })).toBe(1);
  });

  it("holds a merchant's order on credit for approval: quantities can be changed, or it is rejected", async () => {
    const p = await product('صندل جملة', 30, 10);
    const c = await customerToken(app, 'تاجر بالآجل');
    await http.patch(`/admin/customers/${c.customerId}`, { isMerchant: true, creditLimit: '1000' }, admin).expect(200);
    const addr = await address(c.token);
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 4 }, c.token).expect(201);
    const order = (await http.post('/store/orders', { addressId: addr, paymentMethod: 'CUSTOMER_ACCOUNT' }, c.token).expect(201)).body;
    expect(order).toMatchObject({ status: 'PENDING', needsApproval: true });
    const full = (await http.get(`/admin/orders/${order.id}`, admin).expect(200)).body;
    const edited = (await http.put(`/admin/orders/${order.id}/items`, { lines: [{ itemId: full.items[0].id, quantity: 2 }] }, admin).expect(200)).body;
    expect(edited.items[0].quantity).toBe(2);
    expect(Number(edited.grandTotal)).toBe(Number(full.grandTotal) / 2);
    const reserved = async () => (await prisma.stockLevel.aggregate({ where: { variantId: p.variants[0].id }, _sum: { reserved: true } }))._sum.reserved;
    expect(await reserved()).toBe(2);
    const approved = (await http.post(`/admin/orders/${order.id}/approve`, {}, admin).expect(200)).body;
    expect(approved).toMatchObject({ status: 'CONFIRMED', paymentStatus: 'PAID' });
    await http.post(`/admin/orders/${order.id}/approve`, {}, admin).expect(422);

    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const second = (await http.post('/store/orders', { addressId: addr, paymentMethod: 'CUSTOMER_ACCOUNT' }, c.token).expect(201)).body;
    const rejected = (await http.post(`/admin/orders/${second.id}/reject`, { reason: 'الكمية غير متوفرة' }, admin).expect(200)).body;
    expect(rejected.status).toBe('CANCELLED');
  });

  it('shows the coupon field only while a coupon can be used, and takes coupons at the till', async () => {
    await prisma.coupon.updateMany({ data: { isActive: false } });
    const c = await customerToken(app, 'كوبون');
    expect((await http.get('/store/cart', c.token).expect(200)).body.couponsAvailable).toBe(false);
    expect((await http.get('/admin/pos/coupons-available', admin).expect(200)).body.available).toBe(false);
    const code = `R8${run}`.toUpperCase().slice(0, 20);
    await http.post('/admin/coupons', { code, type: 'PERCENTAGE', value: '10', startsAt: new Date(Date.now() - 60_000).toISOString(), perCustomerLimit: null }, admin).expect(201);
    expect((await http.get('/store/cart', c.token).expect(200)).body.couponsAvailable).toBe(true);
    expect((await http.get('/admin/pos/coupons-available', admin).expect(200)).body.available).toBe(true);

    expect((await http.get(`/admin/pos/coupons/check?code=${code.toLowerCase()}`, admin).expect(200)).body).toMatchObject({ type: 'PERCENTAGE', value: '10' });
    await http.get('/admin/pos/coupons/check?code=NOPE', admin).expect(422);
    const p = await product('كوبون الكاشير', 100, 3);
    const items = [{ variantId: p.variants[0].id, quantity: 1 }];
    const sale = (await http.post('/admin/pos/sales', { items, saleType: 'CASH', couponCode: code.toLowerCase(), payments: [{ currency: 'SAR', amount: '90' }] }, admin).expect(201)).body;
    expect(sale.grandTotal).toBe('90');
    await http.post('/admin/pos/sales', { items, saleType: 'CASH', couponCode: 'NOPE', payments: [{ currency: 'SAR', amount: '90' }] }, admin).expect(422);
  });

  it('lists the most visited products first after the owner’s own order', async () => {
    const a = await product('قليل الزيارة', 10, 1);
    const b = await product('كثير الزيارة', 10, 1);
    await prisma.product.update({ where: { id: b.id }, data: { viewCount: 1_000_000 } });
    const list = (await http.get(`/store/products?category=round8-${run}&pageSize=100`).expect(200)).body.items as { id: string }[];
    const ids = list.map((x) => x.id);
    expect(ids.indexOf(b.id)).toBeLessThan(ids.indexOf(a.id));
  });
});
