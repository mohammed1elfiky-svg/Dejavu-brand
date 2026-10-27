import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, customerToken, staffToken } from './helpers';

/** Till change currency, discount codes at the till, revenue by channel, supplier opening balances, password reset, staff sale currency. */
describe('Coupons, revenue, opening balances, password reset (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let variant: string;
  const run = Date.now().toString(36);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/YEQ', { rate: '425' }, admin).expect(200);
    const cat = (await http.post('/admin/categories', { nameAr: `جولة ${run}`, nameEn: `Round ${run}`, slug: `round-${run}` }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `حذاء جولة ${run}`, status: 'ACTIVE', basePrice: '100', variants: [{ size: '38', initialQuantity: 50 }] }, admin).expect(201)).body;
    variant = p.variants[0].id;
  });
  afterAll(() => app.close());

  it('gives the change in the currency the customer paid with', async () => {
    // Invoice in SAR, customer hands over qu'aiti rials: change comes back in YEQ from what was handed over.
    const sale = (
      await http.post('/admin/pos/sales', { items: [{ variantId: variant, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'YEQ', amount: '50000' }] }, admin).expect(201)
    ).body;
    const change = sale.payments.find((p: { isChange: boolean }) => p.isChange);
    expect(change.currency).toBe('YEQ');
    expect(Number(change.currencyAmount)).toBeCloseTo(-7500, -1);
  });

  it('reports revenue of the till and the app, retail and merchants', async () => {
    const before = (await http.get('/admin/reports/revenue', admin).expect(200)).body;
    const merchant = await customerToken(app, 'تاجرة');
    await http.patch(`/admin/customers/${merchant.customerId}`, { isMerchant: true }, admin).expect(200);
    await http.post('/admin/pos/sales', { items: [{ variantId: variant, quantity: 2 }], customerId: merchant.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '200' }] }, admin).expect(201);
    const after = (await http.get('/admin/reports/revenue', admin).expect(200)).body;
    expect(after.pos.merchants.orders).toBe(before.pos.merchants.orders + 1);
    expect(Number(after.merchants.revenue)).toBeGreaterThan(Number(before.merchants.revenue));
    expect(Number(after.total.revenue)).toBeCloseTo(Number(after.pos.total.revenue) + Number(after.app.total.revenue), 2);
    expect(Number(after.total.revenue)).toBeCloseTo(Number(after.retail.revenue) + Number(after.merchants.revenue), 2);
  });

  it('records a supplier opening balance in yuan', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مورد قديم ${run}` }, admin).expect(201)).body;
    await http.post(`/admin/suppliers/${supplier.id}/opening-balance`, { currency: 'SAR', amount: '50', direction: 'WE_OWE' }, admin).expect(400);
    // No payment voucher yet: the yuan rate must be given.
    await http.post(`/admin/suppliers/${supplier.id}/opening-balance`, { currency: 'CNY', amount: '7000', direction: 'WE_OWE' }, admin).expect(400);
    const acc = (await http.post(`/admin/suppliers/${supplier.id}/opening-balance`, { currency: 'CNY', amount: '7000', sarAmount: '3650', direction: 'WE_OWE', note: 'قبل النظام' }, admin).expect(201)).body;
    expect(acc.balances).toEqual([{ currency: 'CNY', balance: '7000' }]);
    await http.post(`/admin/suppliers/${supplier.id}/opening-balance`, { currency: 'CNY', amount: '700', foreignPerUsd: '7', direction: 'SUPPLIER_OWES' }, admin).expect(201);
    const usdRate = Number((await prisma.exchangeRate.findFirst({ where: { currency: 'USD' }, orderBy: { effectiveAt: 'desc' } }))!.rate);
    expect(Number((await http.get(`/admin/suppliers/${supplier.id}`, admin).expect(200)).body.balance)).toBeCloseTo(3650 - 100 / usdRate, 1);
  });

  it('resets a forgotten password with a texted code', async () => {
    // Non-Yemeni numbers get no text: the app asks for the code on WhatsApp instead.
    const abroad = await customerToken(app, 'خارج اليمن');
    expect((await http.post('/auth/customer/otp/reset', { phone: abroad.phone }).expect(200)).body).toMatchObject({ sent: false, channel: 'WHATSAPP' });
    const phone = `77${String(Date.now()).slice(-7)}`;
    await http.post('/auth/customer/register', { name: 'نسيت كلمة السر', phone, password: 'secret123' }).expect(201);
    const c = { phone };
    await http.post('/auth/customer/otp/reset', { phone: '777000999' }).expect(404);
    expect((await http.post('/auth/customer/otp/reset', { phone: c.phone }).expect(200)).body).toMatchObject({ sent: true, channel: 'SMS' });
    const text = (await prisma.outgoingSms.findFirst({ where: { phone: `+967${c.phone}` }, orderBy: { createdAt: 'desc' } }))!;
    expect(text.body).toContain('استعادة كلمة المرور');
    const code = text.body.match(/\d{6}/)![0];
    await http.post('/auth/customer/password-reset', { phone: c.phone, code: code === '000000' ? '111111' : '000000', newPassword: 'newsecret1' }).expect(400);
    const res = await http.post('/auth/customer/password-reset', { phone: c.phone, code, newPassword: 'newsecret1' }).expect(200);
    expect(res.body.accessToken).toBeDefined();
    await http.post('/auth/customer/login', { phone: c.phone, password: 'secret123' }).expect(401);
    await http.post('/auth/customer/login', { phone: c.phone, password: 'newsecret1' }).expect(200);
  });

  it('opens the till in YER for cashiers and SAR for sales staff', async () => {
    const roles = (await http.get('/admin/roles', admin).expect(200)).body as { id: string; name: string }[];
    const role = (n: string) => roles.find((r) => r.name === n)!.id;
    const stamp = String(Date.now()).slice(-6);
    const phone = (n: number) => `77${stamp}${n}`;
    const cashier = (await http.post('/admin/users', { name: 'كاشير', phone: phone(1), password: 'Cashier!2026', roleIds: [role('cashier')] }, admin).expect(201)).body;
    const sales = (await http.post('/admin/users', { name: 'مبيعات', phone: phone(2), password: 'Sales!2026', roleIds: [role('sales')] }, admin).expect(201)).body;
    expect(cashier.saleCurrency).toBe('YER');
    expect(sales.saleCurrency).toBe('SAR');
    expect((await http.patch(`/admin/users/${cashier.id}`, { saleCurrency: 'YEQ' }, admin).expect(200)).body.saleCurrency).toBe('YEQ');
    const token = await staffToken(app, { phone: phone(1), password: 'Cashier!2026' } as never);
    expect((await http.get('/auth/me', token).expect(200)).body.saleCurrency).toBe('YEQ');
  });
});
