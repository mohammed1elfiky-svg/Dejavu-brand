import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, customerToken, staffToken } from './helpers';

/** Employees with their own permissions and boxes; merchant customers with wholesale prices and credit. */
describe('Staff access & merchants (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  const run = Date.now().toString(36);
  let product: { id: string; variants: { id: string }[] };

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/YER', { rate: '140' }, admin).expect(200);
    const cat = await http.post('/admin/categories', { nameAr: 'جملة', nameEn: 'Wholesale', slug: `wholesale-${run}` }, admin).expect(201);
    product = (
      await http
        .post(
          '/admin/products',
          {
            categoryId: cat.body.id, nameAr: 'قميص', nameEn: 'Shirt', slug: `shirt-${run}`, status: 'ACTIVE',
            basePrice: '100', costPrice: '40', taxRateId: null,
            variants: [{ size: 'M', initialQuantity: 30 }],
          },
          admin,
        )
        .expect(201)
    ).body;
    await http.patch(`/admin/products/${product.id}`, { wholesalePrice: '70' }, admin).expect(200);
  });
  afterAll(() => app.close());

  it('adds an employee with a username, own permissions and only the boxes they may use', async () => {
    const all = await http.get('/admin/cash-boxes', admin).expect(200);
    const yerSafe = all.body.find((b: { kind: string; currency: string }) => b.kind === 'VAULT' && b.currency === 'YER');
    const sarBank = all.body.find((b: { kind: string; currency: string }) => b.kind === 'BANK' && b.currency === 'SAR');
    const created = await http
      .post(
        '/admin/users',
        {
          name: 'موظف مخصص', email: `custom-${run}@dejavu.local`, username: `staff${run}`, password: 'Custom!2026',
          roleIds: [], permissions: ['pos.sell', 'stock.read'], cashBoxIds: [yerSafe.id],
        },
        admin,
      )
      .expect(201);
    expect(created.body).toMatchObject({ username: `staff${run}`, customPermissions: ['pos.sell', 'stock.read'], cashBoxIds: [yerSafe.id] });
    expect(created.body.roles).toEqual([]);

    // Signs in with the username; can sell, cannot read reports.
    const token = await staffToken(app, { email: `staff${run}`, password: 'Custom!2026' });
    await http.get('/admin/pos/products', token).expect(200);
    await http.get('/admin/reports/dashboard', token).expect(403);
    const mine = await http.get('/admin/cash-boxes/mine', token).expect(200);
    expect(mine.body.map((b: { id: string }) => b.id)).toContain(yerSafe.id);
    const safes = await http.get('/admin/cash-boxes/safes', token).expect(200);
    expect(safes.body.map((b: { id: string }) => b.id)).toEqual([yerSafe.id]);
    await http.get(`/admin/cash-boxes/${sarBank.id}/entries`, token).expect(403);
    await http.get(`/admin/cash-boxes/${yerSafe.id}/entries`, token).expect(200);

    // YER cash goes to the assigned safe instead of an own drawer.
    const sale = await http
      .post('/admin/pos/sales', { items: [{ variantId: product.variants[0].id, quantity: 1 }], currency: 'YER', saleType: 'CASH', payments: [{ currency: 'YER', amount: '14000' }] }, token)
      .expect(201);
    expect(sale.body.payments[0].accountId).toBe(yerSafe.account.id);

    // Changing permissions takes effect at the next sign-in.
    const updated = await http.put(`/admin/users/${created.body.id}/permissions`, { permissions: ['pos.sell', 'reports.sales'] }, admin).expect(200);
    expect(updated.body.customPermissions).toEqual(['pos.sell', 'reports.sales']);
    await http.put(`/admin/users/${created.body.id}/permissions`, { permissions: ['nope.nothing'] }, admin).expect(400);
    const roles = await http.get('/admin/roles', admin).expect(200);
    expect(roles.body.some((r: { name: string }) => r.name.startsWith('user_'))).toBe(false);
  });

  it('adds an employee with only a mobile number, who signs in with it', async () => {
    const phone = `77${String(Date.now()).slice(-7)}`;
    const roles = (await http.get('/admin/roles', admin).expect(200)).body;
    const cashierRole = roles.find((r: { name: string }) => r.name === 'cashier');
    await http.post('/admin/users', { name: 'بدون هاتف', password: 'Phone!2026', roleIds: [cashierRole.id] }, admin).expect(400);
    await http.post('/admin/users', { name: 'هاتف خطأ', phone: '12345', password: 'Phone!2026', roleIds: [cashierRole.id] }, admin).expect(400);
    const created = (await http.post('/admin/users', { name: 'كاشير بالجوال', phone: `+967${phone}`, password: 'Phone!2026', roleIds: [cashierRole.id] }, admin).expect(201)).body;
    expect(created).toMatchObject({ phone, email: null });
    await http.post('/admin/users', { name: 'مكرر', phone, password: 'Phone!2026', roleIds: [cashierRole.id] }, admin).expect(409);

    const login = await http.post('/auth/staff/login', { phone, password: 'Phone!2026' }).expect(200);
    await http.get('/admin/pos/products', login.body.accessToken).expect(200);
    await http.post('/auth/staff/login', { phone: `00967${phone}`, password: 'Phone!2026' }).expect(200);
    await http.post('/auth/staff/login', { email: phone, password: 'Phone!2026' }).expect(200); // older apps
    await http.post('/auth/staff/login', { phone, password: 'wrong-pass' }).expect(401);
  });

  it('gives merchants wholesale prices less their discount, everywhere', async () => {
    const shopper = await customerToken(app, 'تاجر الجملة');
    await http.patch(`/admin/customers/${shopper.customerId}`, { isMerchant: true, merchantDiscount: '10', creditLimit: '100' }, admin).expect(200);

    const pub = await http.get(`/store/products/shirt-${run}`).expect(200);
    expect(pub.body.basePrice).toBe('100');
    const asMerchant = await http.get(`/store/products/shirt-${run}`, shopper.token).expect(200);
    expect(asMerchant.body).toMatchObject({ basePrice: '63', compareAtPrice: '100', merchantPrice: true }); // 70 − 10%

    await http.post('/store/cart/items', { variantId: product.variants[0].id, quantity: 1 }, shopper.token).expect(201);
    const cart = await http.get('/store/cart', shopper.token).expect(200);
    expect(cart.body.items[0].unitPrice).toBe('63');

    const pos = await http
      .post('/admin/pos/sales', { items: [{ variantId: product.variants[0].id, quantity: 2 }], saleType: 'CREDIT', customerId: shopper.customerId, payments: [] }, admin)
      .expect(201);
    expect(pos.body.grandTotal).toBe('126');
    const me = await http.get('/auth/me', shopper.token).expect(200);
    expect(me.body).toMatchObject({ isMerchant: true });
  });

  it('lets merchants order on credit from the app, up to their limit', async () => {
    const shopper = await customerToken(app, 'تاجر آجل');
    await http.put('/admin/shipping-zones', { governorate: 'تعز', fee: 0 }, admin).expect(200);
    const addr = await http
      .post('/store/me/addresses', { recipient: 'تاجر', phone: '771112223', governorate: 'تعز', city: 'تعز', street: 'شارع جمال' }, shopper.token)
      .expect(201);
    const order = async () => {
      await http.post('/store/cart/items', { variantId: product.variants[0].id, quantity: 1 }, shopper.token).expect(201);
      return http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CUSTOMER_ACCOUNT' }, shopper.token);
    };
    expect((await order()).status).toBe(422); // not a merchant, no balance
    await http.delete(`/store/cart/items/${product.variants[0].id}`, shopper.token).expect(200);

    await http.patch(`/admin/customers/${shopper.customerId}`, { isMerchant: true, creditLimit: '100' }, admin).expect(200);
    const first = await order();
    expect(first.status).toBe(201);
    // The owner approves each order on credit first.
    expect(first.body).toMatchObject({ status: 'PENDING', paymentStatus: 'UNPAID', grandTotal: '70', needsApproval: true });
    const waiting = await http.get('/admin/orders?awaitingApproval=true', admin).expect(200);
    expect(waiting.body.items.map((o: { id: string }) => o.id)).toContain(first.body.id);
    await http.post(`/admin/orders/${first.body.id}/status`, { status: 'CONFIRMED' }, admin).expect(422);
    const approved = await http.post(`/admin/orders/${first.body.id}/approve`, {}, admin).expect(200);
    expect(approved.body).toMatchObject({ status: 'CONFIRMED', paymentStatus: 'PAID' });
    const wallet = await http.get('/store/me/wallet', shopper.token).expect(200);
    expect(wallet.body.merchant).toMatchObject({ creditLimit: '100' });
    expect(wallet.body.balances[0]).toMatchObject({ currency: 'SAR', owed: '70' });
    // 70 + 70 > 100
    const second = await order();
    expect(second.status).toBe(422);
    expect(second.body.message).toContain('credit limit');
  });
});
