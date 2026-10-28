import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { EngagementService } from '../src/sales/engagement.service';
import { API, api, createApp, customerToken, ledgerBalances, staffToken, uniquePng } from './helpers';

/** Bank accounts in several currencies, shipping companies on imports, shipping receipts, cart reminders, back-in-stock alerts, staff report. */
describe('Shipping companies, alerts and staff report (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/USD', { rate: '0.25' }, admin).expect(200);
    categoryId = (await http.post('/admin/categories', { nameAr: `شحن ${run}`, nameEn: `Ship ${run}`, slug: `ship-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(() => app.close());

  it('keeps one bank account in several currencies, with a picture', async () => {
    const acc = (
      await http.post('/admin/bank-accounts', { bankName: 'الكريمي', accountName: 'ديجافو', accountNumber: `30${run}`, currency: 'YER', currencies: ['YER', 'SAR', 'YEQ'], imageUrl: 'https://cdn.example.com/k.png' }, admin).expect(201)
    ).body;
    expect(acc).toMatchObject({ currency: 'YER', currencies: ['YER', 'SAR', 'YEQ'], imageUrl: 'https://cdn.example.com/k.png' });
    const c = await customerToken(app, 'شاحن رصيد');
    const slip = (await request(app.getHttpServer()).post(`${API}/store/me/files`).set('Authorization', `Bearer ${c.token}`).attach('file', uniquePng(), 'slip.png').expect(201)).body;
    await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '100', receiptFileId: slip.id, bankAccountId: acc.id }, c.token).expect(201);
    await http.post('/store/me/wallet/top-ups', { currency: 'USD', amount: '10', receiptFileId: slip.id, bankAccountId: acc.id }, c.token).expect(422);
  });

  it('owes the goods to the supplier in yuan and the freight to the shipping company in dollars', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مصنع ${run}` }, admin).expect(201)).body;
    const shipper = (await http.post('/admin/suppliers', { name: `شركة شحن ${run}`, kind: 'SHIPPING_COMPANY' }, admin).expect(201)).body;
    const shippers = (await http.get('/admin/suppliers?kind=SHIPPING_COMPANY&pageSize=100', admin).expect(200)).body.items as { id: string }[];
    expect(shippers.some((s) => s.id === shipper.id)).toBe(true);
    expect(shippers.some((s) => s.id === supplier.id)).toBe(false);
    await http.post('/admin/purchase-orders/import', { supplierId: supplier.id, shippingCompanyId: supplier.id, items: [] }, admin).expect(400);

    const p = (await http.post('/admin/products', { categoryId, nameAr: `حذاء مستورد ${run}`, status: 'ACTIVE', basePrice: '100', variants: [{ size: '38' }] }, admin).expect(201)).body;
    const po = (
      await http
        .post(
          '/admin/purchase-orders/import',
          { supplierId: supplier.id, shippingCompanyId: shipper.id, foreignPerUsd: '7', domesticShipping: '70', invoiceImageUrl: 'admin/files/abc', items: [{ variantId: p.variants[0].id, quantity: 10, unitPriceForeign: '70' }] },
          admin,
        )
        .expect(201)
    ).body;
    expect(po.invoiceImageUrl).toBe('admin/files/abc');
    const balance = async (id: string, cur: string) =>
      ((await http.get(`/admin/suppliers/${id}/account`, admin).expect(200)).body.balances as { currency: string; balance: string }[]).find((b) => b.currency === cur)?.balance;
    expect(await balance(supplier.id, 'CNY')).toBe('770'); // 10 × 70 + 70 inland

    // Arrives: 2 m³ at 100 USD → 200 USD to the shipping company, nothing more to the supplier.
    const item = po.items[0];
    await http.post(`/admin/purchase-orders/${po.id}/import-receive`, { items: [{ itemId: item.id, quantity: 10 }], totalCbm: '2', cbmRateUsd: '100' }, admin).expect(200);
    expect(await balance(shipper.id, 'USD')).toBe('200');
    expect(await balance(supplier.id, 'CNY')).toBe('770');
    expect((await http.get(`/admin/suppliers/${shipper.id}`, admin).expect(200)).body.balance).toBe('800'); // 200 USD × 4

    // The supplier's invoice comes out different after arrival: 72 a piece.
    const before = await ledgerBalances(prisma, [po.id]);
    await http.put(`/admin/purchase-orders/${po.id}/import`, { items: [{ variantId: p.variants[0].id, quantity: 10, unitPriceForeign: '72' }] }, admin).expect(200);
    expect(await balance(supplier.id, 'CNY')).toBe('790');
    const after = await ledgerBalances(prisma, [po.id]);
    // 20 yuan more = 20 / 7 × 4 SAR owed, booked as an inventory adjustment.
    expect((after.INVENTORY_ADJUSTMENT ?? 0) - (before.INVENTORY_ADJUSTMENT ?? 0)).toBeCloseTo((20 / 7) * 4, 1);
    await http.put(`/admin/purchase-orders/${po.id}/import`, { items: [{ variantId: p.variants[0].id, quantity: 9, unitPriceForeign: '72' }] }, admin).expect(422);
  });

  it('sends the customer the photo of the shipping receipt', async () => {
    const c = await customerToken(app, 'عميل شحن');
    const p = (await http.post('/admin/products', { categoryId, nameAr: `شنطة شحن ${run}`, slug: `bag-ship-${run}`, status: 'ACTIVE', basePrice: '50', variants: [{ size: 'M', initialQuantity: 5 }] }, admin).expect(201)).body;
    await http.put('/admin/shipping-zones', { governorate: 'عدن', fee: '5' }, admin).expect(200);
    const addr = (await http.post('/store/me/addresses', { recipient: 'عميل شحن', phone: c.phone, governorate: 'عدن', city: 'عدن', street: 'الشارع' }, c.token).expect(201)).body;
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const order = (await http.post('/store/orders', { addressId: addr.id, paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(201)).body;

    const res = await request(app.getHttpServer()).post(`${API}/admin/orders/${order.id}/shipping-receipt`).set('Authorization', `Bearer ${admin}`).attach('file', uniquePng(), 'receipt.png');
    expect(res.status).toBe(200);
    const seen = (await http.get(`/store/orders/${order.number}`, c.token).expect(200)).body;
    expect(seen.shippingReceiptFileId).toBeTruthy();
    await http.get(`/store/me/files/${seen.shippingReceiptFileId}`, c.token).expect(200);
    const other = await customerToken(app, 'متطفل');
    await http.get(`/store/me/files/${seen.shippingReceiptFileId}`, other.token).expect(404);
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: 'تم شحن طلبك' } })).toBe(1);
  });

  it('reminds of a forgotten cart once, and tells waiting customers a size is back', async () => {
    const c = await customerToken(app, 'سلة منسية');
    const p = (await http.post('/admin/products', { categoryId, nameAr: `فستان ${run}`, slug: `dress-${run}`, status: 'ACTIVE', basePrice: '80', variants: [{ size: 'S', initialQuantity: 3 }, { size: 'L' }] }, admin).expect(201)).body;
    const [inStock, soldOut] = p.variants as { id: string }[];
    await http.post('/store/cart/items', { variantId: inStock.id, quantity: 1 }, c.token).expect(201);
    const engagement = app.get(EngagementService);
    const later = new Date(Date.now() + 7 * 3600_000);
    expect(await engagement.remindCarts(new Date())).toBe(0);
    await engagement.remindCarts(later);
    const reminders = () => prisma.notification.count({ where: { customerId: c.customerId, title: { startsWith: 'منتجاتك ما زالت' } } });
    expect(await reminders()).toBe(1);
    await engagement.remindCarts(later);
    expect(await reminders()).toBe(1);

    await http.post(`/store/stock-alerts/${inStock.id}`, {}, c.token).expect(400);
    const mine = (await http.post(`/store/stock-alerts/${soldOut.id}`, {}, c.token).expect(200)).body;
    expect(mine).toHaveLength(1);
    await engagement.notifyBackInStock();
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: 'عاد للمخزون ✨' } })).toBe(0);
    const wh = await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } });
    await http.post('/admin/inventory/adjustments', { warehouseId: wh.id, reason: 'OPENING', items: [{ variantId: soldOut.id, quantity: 2, unitCost: '30' }] }, admin).expect(201);
    await engagement.notifyBackInStock();
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: 'عاد للمخزون ✨' } })).toBe(1);
    expect((await http.get('/store/stock-alerts', c.token).expect(200)).body).toHaveLength(0);
  });

  it("reports each cashier's sales, discounts and deleted invoices", async () => {
    const p = (await http.post('/admin/products', { categoryId, nameAr: `قميص ${run}`, status: 'ACTIVE', basePrice: '100', variants: [{ size: 'M', initialQuantity: 5 }] }, admin).expect(201)).body;
    await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], saleType: 'CASH', discountType: 'AMOUNT', discountValue: '10', payments: [{ currency: 'SAR', amount: '90' }] }, admin).expect(201);
    const gone = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '100' }] }, admin).expect(201)).body;
    await http.post(`/admin/pos/sales/${gone.id}/void`, {}, admin).expect(200);
    const me = (await http.get('/auth/me', admin).expect(200)).body;
    const report = (await http.get('/admin/reports/staff', admin).expect(200)).body;
    const row = report.staff.find((s: { user: { id: string } }) => s.user.id === me.id);
    expect(row.invoices).toBeGreaterThanOrEqual(1);
    expect(row.voided).toBeGreaterThanOrEqual(1);
    expect(Number(row.discounts)).toBeGreaterThanOrEqual(10);
  });
});
