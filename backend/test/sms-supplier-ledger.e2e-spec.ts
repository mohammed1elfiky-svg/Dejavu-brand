import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, createProduct, customerToken, staffToken } from './helpers';

/** Texts from the store phone, sign-up codes and regions, customer exchanges, and the supplier's own account. */
describe('SMS, governorates and supplier account (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let usdBox: { id: string };
  let oldUsdRate: string | null = null;
  const run = Date.now().toString(36);
  const yemeni = () => `77${String(Date.now() + Math.floor(Math.random() * 1000)).slice(-7)}`;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const usd = await prisma.exchangeRate.findFirst({ where: { currency: 'USD' }, orderBy: { effectiveAt: 'desc' } });
    oldUsdRate = usd ? usd.rate.toString() : null;
    await http.put('/admin/exchange-rates/USD', { rate: '0.25' }, admin).expect(200);
    usdBox = (await http.post('/admin/cash-boxes', { name: `صندوق دولار ${run}`, kind: 'VAULT', currency: 'USD' }, admin).expect(201)).body;
  });
  afterAll(async () => {
    await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin);
    if (oldUsdRate) await http.put('/admin/exchange-rates/USD', { rate: oldUsdRate }, admin);
    await app.close();
  });

  const textsTo = async (phone: string) =>
    ((await http.get('/admin/sms', admin).expect(200)).body as { phone: string; body: string; purpose: string }[]).filter((m) => m.phone.endsWith(phone));

  it('asks for a texted code at sign-up and records the governorate', async () => {
    await http.put('/admin/sms/settings', { requirePhoneOtp: true }, admin).expect(200);
    expect((await http.get('/auth/customer/otp').expect(200)).body.required).toBe(true);
    const phone = yemeni();
    const form = { name: 'عميل تعز', phone, password: 'secret123', governorate: 'تعز' };
    await http.post('/auth/customer/register', form).expect(400);

    await http.post('/auth/customer/otp', { phone }).expect(200);
    // The store phone picks the text up and sends it.
    const claimed = (await http.post('/admin/sms/claim?limit=50', {}, admin).expect(200)).body as { id: string; phone: string; body: string }[];
    const otp = claimed.find((m) => m.phone === `+967${phone}`)!;
    expect(otp).toBeDefined();
    const code = otp.body.match(/\d{6}/)![0];
    await http.post(`/admin/sms/${otp.id}/result`, { ok: true }, admin).expect(200);
    expect((await textsTo(phone))[0]).toMatchObject({ purpose: 'OTP' });

    await http.post('/auth/customer/register', { ...form, otp: code === '000000' ? '111111' : '000000' }).expect(400);
    await http.post('/auth/customer/register', { ...form, otp: code }).expect(400); // Taiz needs the area
    const res = await http.post('/auth/customer/register', { ...form, otp: code, area: 'الحوبان' }).expect(201);
    const me = (await http.get('/auth/me', res.body.accessToken).expect(200)).body;
    expect(me).toMatchObject({ governorate: 'تعز', area: 'الحوبان', preferredCurrency: 'SAR', phoneVerified: true });

    await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin).expect(200);
    const aden = await http.post('/auth/customer/register', { name: 'عميل عدن', phone: yemeni(), password: 'secret123', governorate: 'عدن' }).expect(201);
    expect((await http.get('/auth/me', aden.body.accessToken).expect(200)).body.preferredCurrency).toBe('SAR') // every new account starts in Saudi riyals;
    expect((await http.get('/auth/governorates').expect(200)).body.find((g: { name: string }) => g.name === 'تعز').areas).toHaveLength(2);
  });

  it('texts the customer a receipt and exchanges currencies inside the account', async () => {
    const c = await customerToken(app, 'عميل مصارفة');
    await http.post(`/admin/customers/${c.customerId}/account/receipts`, { currency: 'USD', amount: '1000', cashBoxId: usdBox.id, sendSms: true }, admin).expect(201);
    const receipt = (await textsTo(c.phone)).find((m) => m.purpose === 'RECEIPT')!;
    expect(receipt.body).toContain('تم إضافة');
    expect(receipt.body).toContain('رصيدك لدينا');

    // 100 USD of credit become 56,000 YER of credit (100 × 4 SAR × 140).
    await http.post(`/admin/customers/${c.customerId}/account/exchange`, { fromCurrency: 'USD', amount: '100', toCurrency: 'USD', toAmount: '1' }, admin).expect(400);
    const ex = (await http.post(`/admin/customers/${c.customerId}/account/exchange`, { fromCurrency: 'USD', amount: '100', toCurrency: 'YER', toAmount: '56000', sendSms: true }, admin).expect(201)).body;
    const bal = (cur: string) => ex.balances.find((b: { currency: string }) => b.currency === cur)?.balance;
    expect(bal('USD')).toBe('-900');
    expect(bal('YER')).toBe('-56000');
    expect((await textsTo(c.phone)).some((m) => m.purpose === 'EXCHANGE')).toBe(true);
  });

  it('refunds a return from the bank account picked', async () => {
    const p = await createProduct(app, admin, `bank-ret-${run}`, 100, ['M']);
    const bank = (await http.get('/admin/cash-boxes?kind=BANK&currency=SAR', admin).expect(200)).body[0];
    const before = Number(bank.balance);
    await http.post('/admin/pos/returns/without-invoice', { items: [{ variantId: p[0], quantity: 1 }], refundBoxId: usdBox.id }, admin).expect(422);
    await http.post('/admin/pos/returns/without-invoice', { items: [{ variantId: p[0], quantity: 1 }], refundBoxId: bank.id }, admin).expect(201);
    const after = (await http.get('/admin/cash-boxes?kind=BANK&currency=SAR', admin).expect(200)).body.find((b: { id: string }) => b.id === bank.id);
    expect(Number(after.balance)).toBe(before - 100);
  });

  it('shows bag size and heel height to shoppers', async () => {
    const cat = (await http.post('/admin/categories', { nameAr: `أحذية ${run}`, nameEn: `Shoes ${run}`, slug: `shoes-${run}` }, admin).expect(201)).body;
    const p = (
      await http
        .post('/admin/products', { categoryId: cat.id, nameAr: `كعب ${run}`, slug: `heel-${run}`, status: 'ACTIVE', basePrice: '150', heelHeightCm: '7.5', bagSize: '30 × 20', variants: [{ size: '38', initialQuantity: 2 }] }, admin)
        .expect(201)
    ).body;
    const shown = (await http.get(`/store/products/heel-${run}`).expect(200)).body;
    expect(shown).toMatchObject({ heelHeightCm: '7.5', bagSize: '30 × 20' });
    await http.patch(`/admin/products/${p.id}`, { bagSize: '' }, admin).expect(200);
    expect((await http.get(`/store/products/heel-${run}`).expect(200)).body.bagSize).toBeNull();
  });

  it("keeps the supplier's account in yuan and dollars: orders, payment vouchers, commission", async () => {
    // Money in the dollar box to pay with.
    const c = await customerToken(app, 'ممول');
    await http.post(`/admin/customers/${c.customerId}/account/receipts`, { currency: 'USD', amount: '2000', cashBoxId: usdBox.id }, admin).expect(201);

    const supplier = (await http.post('/admin/suppliers', { name: `وكيل قوانغتشو ${run}` }, admin).expect(201)).body;
    const cat = (await http.post('/admin/categories', { nameAr: `واردات ${run}`, nameEn: `Imports ${run}`, slug: `imp2-${run}` }, admin).expect(201)).body;
    const old = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `حذاء ${run}`, status: 'ACTIVE', basePrice: '90', costPrice: '20', variants: [{ size: '40' }] }, admin).expect(201)).body;
    const account = async () => (await http.get(`/admin/suppliers/${supplier.id}/account`, admin).expect(200)).body;
    const balance = async (cur: string) => (await account()).balances.find((b: { currency: string }) => b.currency === cur)?.balance;

    // No rate needed to order; the factory name goes on the statement.
    const po = (
      await http
        .post('/admin/purchase-orders/import', { supplierId: supplier.id, items: [{ variantId: old.variants[0].id, quantity: 100, unitPriceForeign: '10', note: 'مصنع النور' }] }, admin)
        .expect(201)
    ).body;
    expect(await balance('CNY')).toBe('1000');
    expect((await account()).entries[0].note).toContain('مصنع النور');

    // Paying: dollars from a dollar box at the yuan rate of the day.
    const sarBox = (await http.get('/admin/cash-boxes?kind=VAULT&currency=SAR', admin).expect(200)).body[0];
    await http.post('/admin/supplier-vouchers/payment', { supplierId: supplier.id, usdAmount: '100', cashBoxId: sarBox.id, foreignPerUsd: '7' }, admin).expect(422);
    await http.post('/admin/supplier-vouchers/payment', { supplierId: supplier.id, usdAmount: '100', cashBoxId: usdBox.id }, admin).expect(400);
    const pay = (await http.post('/admin/supplier-vouchers/payment', { supplierId: supplier.id, usdAmount: '100', cashBoxId: usdBox.id, foreignPerUsd: '7' }, admin).expect(201)).body;
    expect(await balance('CNY')).toBe('300');
    expect((await account()).lastRate).toBe('7');

    // Corrected to 120 USD: 840 yuan off.
    const edited = (await http.put(`/admin/supplier-vouchers/${pay.id}/payment`, { supplierId: supplier.id, usdAmount: '120', cashBoxId: usdBox.id, foreignPerUsd: '7' }, admin).expect(200)).body;
    expect(await balance('CNY')).toBe('160');

    // The agent's commission for a period, in dollars.
    const com = (await http.post('/admin/supplier-vouchers/commission', { supplierId: supplier.id, usdAmount: '25', periodFrom: '2026-10-01', periodTo: '2026-10-31' }, admin).expect(201)).body;
    expect(await balance('USD')).toBe('25');
    await http.post(`/admin/supplier-vouchers/${com.id}/cancel`, {}, admin).expect(200);
    await http.post(`/admin/supplier-vouchers/${com.id}/cancel`, {}, admin).expect(422);
    expect(await balance('USD')).toBeUndefined();

    // The order changes before it ships.
    await http.put(`/admin/purchase-orders/${po.id}/import`, { items: [{ variantId: old.variants[0].id, quantity: 90, unitPriceForeign: '10', note: 'مصنع النور' }] }, admin).expect(200);
    expect(await balance('CNY')).toBe('60');

    // Arrives 10 short, valued at the last payment's rate; the shortfall comes off the account.
    const item = (await http.get(`/admin/purchase-orders/${po.id}`, admin).expect(200)).body.items[0];
    const got = (await http.post(`/admin/purchase-orders/${po.id}/import-receive`, { items: [{ itemId: item.id, quantity: 80 }], totalCbm: '1', cbmRateUsd: '0' }, admin).expect(200)).body;
    expect(got.foreignPerUsd).toBe('7');
    expect(await balance('CNY')).toBe('-40');
    const cost = Number((await http.get(`/admin/products/${old.id}`, admin).expect(200)).body.costPrice);
    expect(cost).toBeCloseTo((10 / 7) * 4, 2);
    await http.post(`/admin/supplier-vouchers/${edited.id}/cancel`, {}, admin).expect(200);
    expect(await balance('CNY')).toBe('800');

    // A cancelled order leaves the account and archives the items made for it.
    const second = (
      await http
        .post('/admin/purchase-orders/import', { supplierId: supplier.id, newProducts: [{ categoryId: cat.id, nameAr: `شنطة ${run}`, basePrice: '80', unitPriceForeign: '30', variants: [{ color: 'أسود', quantity: 5 }, { color: 'بني', quantity: 5 }] }] }, admin)
        .expect(201)
    ).body;
    expect(await balance('CNY')).toBe('1100');
    await http.post(`/admin/purchase-orders/${second.id}/cancel`, {}, admin).expect(200);
    expect(await balance('CNY')).toBe('800');
    expect((await http.get(`/admin/products/${second.items[0].variant.product.id}`, admin).expect(200)).body.status).toBe('ARCHIVED');
  });

  it('takes the latest purchase price as the cost of an existing item', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مورد محلي ${run}` }, admin).expect(201)).body;
    const p = await createProduct(app, admin, `recost-${run}`, 100, ['M']);
    const order = (await http.post('/admin/purchase-orders', { supplierId: supplier.id, items: [{ variantId: p[0], quantity: 4, unitCost: '35' }] }, admin).expect(201)).body;
    await http.post(`/admin/purchase-orders/${order.id}/place`, {}, admin).expect(200);
    await http.post(`/admin/purchase-orders/${order.id}/receive`, { items: [{ itemId: order.items[0].id, quantity: 4 }] }, admin).expect(200);
    const { productId } = await prisma.productVariant.findUniqueOrThrow({ where: { id: p[0] } });
    expect(Number((await http.get(`/admin/products/${productId}`, admin).expect(200)).body.costPrice)).toBe(35);
  });
});
