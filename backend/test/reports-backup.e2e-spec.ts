import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { gunzipSync } from 'node:zlib';
import { PrismaService } from '../src/prisma/prisma.service';
import { API, api, createApp, customerToken, staffToken } from './helpers';

/** Audit log, debtors and reminders, the daily summary, product reports, dollar/yuan purchases, backups. */
describe('Reports, reminders, backups (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/USD', { rate: '0.25' }, admin).expect(200);
    categoryId = (await http.post('/admin/categories', { nameAr: `تقارير ${run}`, nameEn: `Reports ${run}`, slug: `reports-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(() => app.close());

  it('lists who changed what in the audit log', async () => {
    const page = (await http.get('/admin/audit-logs?pageSize=5', admin).expect(200)).body;
    expect(page.items.length).toBeGreaterThan(0);
    expect(page.items[0]).toHaveProperty('entityType');
    const types = (await http.get('/admin/audit-logs/types', admin).expect(200)).body as { entityType: string }[];
    expect(types.some((t) => t.entityType === 'Category')).toBe(true);
    const cats = (await http.get('/admin/audit-logs?entityType=Category&pageSize=100', admin).expect(200)).body;
    expect(cats.items.every((i: { entityType: string }) => i.entityType === 'Category')).toBe(true);
  });

  it('lists customers who owe and texts them a reminder', async () => {
    const c = await customerToken(app, 'عميل مدين');
    await http.post(`/admin/customers/${c.customerId}/account/opening-balance`, { currency: 'YER', amount: '15000', direction: 'OWES' }, admin).expect(201);
    const list = (await http.get('/admin/debtors', admin).expect(200)).body;
    const me = list.items.find((d: { id: string }) => d.id === c.customerId);
    expect(me.balances).toEqual([{ currency: 'YER', balance: '15000' }]);
    expect(Number(list.totalOwed)).toBeGreaterThan(0);
    const sent = (await http.post('/admin/debtors/reminders', { customerIds: [c.customerId] }, admin).expect(200)).body;
    expect(sent.queued).toBe(1);
    const sms = await prisma.outgoingSms.findFirst({ where: { phone: c.phone }, orderBy: { createdAt: 'desc' } });
    expect(sms!.body).toContain('المتبقي عليك');
  });

  it('texts the owner a daily summary', async () => {
    await http.put('/admin/reports/daily-summary/settings', { enabled: true, phone: '777111222', hour: 0 }, admin).expect(200);
    const preview = (await http.get('/admin/reports/daily-summary', admin).expect(200)).body.text as string;
    expect(preview).toContain('ملخص يوم');
    expect(preview).toContain('ديون العملاء');
    await http.post('/admin/reports/daily-summary/send', {}, admin).expect(200);
    expect(await prisma.outgoingSms.count({ where: { phone: '+967777111222' } })).toBeGreaterThan(0);
    await http.put('/admin/reports/daily-summary/settings', { enabled: false }, admin).expect(200);
  });

  it('shows the rates and whether they are out of date on the dashboard', async () => {
    const d = (await http.get('/admin/reports/dashboard', admin).expect(200)).body;
    const usd = d.rates.find((r: { currency: string }) => r.currency === 'USD');
    expect(usd).toMatchObject({ rate: '0.25', stale: false });
  });

  it('buys from a supplier in dollars: SAR cost, dollar account, paid from a dollar box', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مورد دولار ${run}` }, admin).expect(201)).body;
    const usdBox = (await http.post('/admin/cash-boxes', { name: `دولار ${run}`, kind: 'VAULT', currency: 'USD' }, admin).expect(201)).body;
    const c = await customerToken(app, 'ممول دولار');
    await http.post(`/admin/customers/${c.customerId}/account/receipts`, { currency: 'USD', amount: '500', cashBoxId: usdBox.id }, admin).expect(201);
    const p = (await http.post('/admin/products', { categoryId, nameAr: `شنطة دولار ${run}`, status: 'ACTIVE', basePrice: '120', variants: [{ size: 'M' }] }, admin).expect(201)).body;

    const po = (
      await http
        .post(
          '/admin/purchase-orders/quick',
          { supplierId: supplier.id, currency: 'USD', items: [{ variantId: p.variants[0].id, quantity: 5, unitCost: '10', taxRate: 0 }], payMethod: 'CASH', payAmount: '30', payBoxId: usdBox.id },
          admin,
        )
        .expect(201)
    ).body;
    expect(po.foreignCurrency).toBe('USD');
    expect(po.items[0].unitCost).toBe('40');
    expect(Number((await http.get(`/admin/products/${p.id}`, admin).expect(200)).body.costPrice)).toBe(40);
    const account = (await http.get(`/admin/suppliers/${supplier.id}/account`, admin).expect(200)).body;
    expect(account.balances).toEqual([{ currency: 'USD', balance: '20' }]); // 50 received − 30 paid
    expect((await http.get(`/admin/suppliers/${supplier.id}`, admin).expect(200)).body.balance).toBe('80'); // 20 USD × 4

    // In yuan without any rate known yet: refused.
    const other = (await http.post('/admin/suppliers', { name: `مورد كواي ${run}` }, admin).expect(201)).body;
    await http.post('/admin/purchase-orders', { supplierId: other.id, currency: 'CNY', items: [{ variantId: p.variants[0].id, quantity: 1, unitCost: '70' }] }, admin).expect(400);
    const cny = (await http.post('/admin/purchase-orders', { supplierId: other.id, currency: 'CNY', foreignPerUsd: '7', items: [{ variantId: p.variants[0].id, quantity: 1, unitCost: '70', taxRate: 0 }] }, admin).expect(201)).body;
    expect(cny.items[0].unitCost).toBe('40');
  });

  it('reports best sellers, dead stock and what to order again', async () => {
    const p = (await http.post('/admin/products', { categoryId, nameAr: `الأكثر مبيعاً ${run}`, status: 'ACTIVE', basePrice: '50', costPrice: '20', variants: [{ size: '40', initialQuantity: 6 }] }, admin).expect(201)).body;
    await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 5 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '250' }] }, admin).expect(201);
    const report = (await http.get('/admin/reports/products?limit=200', admin).expect(200)).body;
    const row = report.topSellers.find((r: { productId: string }) => r.productId === p.id);
    expect(row).toMatchObject({ units: 5, onHand: 1 });
    expect(Number(row.grossProfit)).toBeCloseTo(Number(row.revenue) - 100, 2);
    expect(Array.isArray(report.deadStock)).toBe(true);
    const reorder = (await http.get('/admin/reports/reorder?days=30&coverDays=30', admin).expect(200)).body;
    const item = reorder.items.find((i: { variantId: string }) => i.variantId === p.variants[0].id);
    expect(item).toMatchObject({ sold: 5, onHand: 1, incoming: 0, suggested: 4 });
  });

  it('keeps a picture per currency and lets coupon limits be removed', async () => {
    const cur = (await http.patch('/admin/currencies/YEQ', { imageUrl: 'https://cdn.example.com/yeq100.png' }, admin).expect(200)).body;
    expect(cur.imageUrl).toBe('https://cdn.example.com/yeq100.png');
    expect((await http.patch('/admin/currencies/YEQ', { imageUrl: '' }, admin).expect(200)).body.imageUrl).toBeNull();
    const coupon = (await http.post('/admin/coupons', { code: `LIM${run}`.toUpperCase().slice(0, 20), type: 'PERCENTAGE', value: '10', minOrderAmount: '100', endsAt: '2027-01-01T00:00:00Z', startsAt: '2026-01-01T00:00:00Z' }, admin).expect(201)).body;
    const cleared = (await http.patch(`/admin/coupons/${coupon.id}`, { minOrderAmount: '', endsAt: '' }, admin).expect(200)).body;
    expect(cleared).toMatchObject({ minOrderAmount: null, endsAt: null });
  });

  it('backs the whole database up and restores it', async () => {
    const res = await request(app.getHttpServer()).get(`${API}/admin/system/backup`).set('Authorization', `Bearer ${admin}`).buffer(true).parse((r, cb) => {
      const chunks: Buffer[] = [];
      r.on('data', (c: Buffer) => chunks.push(c));
      r.on('end', () => cb(null, Buffer.concat(chunks)));
    });
    expect(res.status).toBe(200);
    expect(res.headers['content-disposition']).toContain('dejavu-backup-');
    const file = JSON.parse(gunzipSync(res.body as Buffer).toString('utf8'));
    expect(file.format).toBe('dejavu-backup');
    const products = await prisma.product.count();
    expect(file.tables.products).toHaveLength(products);

    // Something done after the backup is gone once it is restored.
    await http.post('/admin/categories', { nameAr: `بعد النسخة ${run}`, nameEn: `After ${run}`, slug: `after-${run}` }, admin).expect(201);
    const upload = (confirm: string) =>
      request(app.getHttpServer()).post(`${API}/admin/system/restore`).set('Authorization', `Bearer ${admin}`).field('confirm', confirm).attach('file', res.body as Buffer, 'backup.json.gz');
    expect((await upload('نعم')).status).toBe(400);
    const restored = await upload('استعادة النسخة الاحتياطية');
    expect(restored.status).toBe(200);
    expect(restored.body.rows.products).toBe(products);
    expect(await prisma.category.count({ where: { slug: `after-${run}` } })).toBe(0);
    expect(await prisma.product.count()).toBe(products);
    // Ledger rows (append-only) came back too, and the books still balance.
    const [sums] = await prisma.$queryRaw<{ d: string; c: string }[]>`SELECT SUM(debit)::text AS d, SUM(credit)::text AS c FROM journal_lines`;
    expect(sums.d).toBe(sums.c);
  });
});
