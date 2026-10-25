import { INestApplication } from '@nestjs/common';
import { api, createApp, customerToken, staffToken } from './helpers';

/** The admin home: one search box, badge counters and the sales chart. */
describe('Admin home (e2e)', () => {
  let app: INestApplication;
  let http: ReturnType<typeof api>;
  let admin: string;
  const run = Date.now().toString(36);

  beforeAll(async () => {
    ({ app } = await createApp());
    http = api(app);
    admin = await staffToken(app);
  });
  afterAll(() => app.close());

  it('finds products, customers and invoices from one search box', async () => {
    const cat = (await http.post('/admin/categories', { nameAr: `بحث ${run}`, nameEn: `Search ${run}`, slug: `search-${run}` }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `حقيبة البحث ${run}`, status: 'ACTIVE', basePrice: '70', variants: [{ size: 'M', initialQuantity: 2, barcode: `88${run}` }] }, admin).expect(201)).body;
    const c = await customerToken(app, `نورة ${run}`);
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], customerId: c.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '70' }] }, admin).expect(201)).body;

    const byName = (await http.get(`/admin/home/search?q=${encodeURIComponent(`البحث ${run}`)}`, admin).expect(200)).body;
    expect(byName.products.map((x: { id: string }) => x.id)).toContain(p.id);
    const byBarcode = (await http.get(`/admin/home/search?q=88${run}`, admin).expect(200)).body;
    expect(byBarcode.products.map((x: { id: string }) => x.id)).toContain(p.id);
    const byPhone = (await http.get(`/admin/home/search?q=${c.phone}`, admin).expect(200)).body;
    expect(byPhone.customers.map((x: { id: string }) => x.id)).toContain(c.customerId);
    const byNumber = (await http.get(`/admin/home/search?q=${sale.number}`, admin).expect(200)).body;
    expect(byNumber.orders[0]).toMatchObject({ id: sale.id, number: sale.number });
    await http.get('/admin/home/search?q=', admin).expect(400);
  });

  it('counts what needs attention and charts the last days of sales', async () => {
    const badges = (await http.get('/admin/home/badges', admin).expect(200)).body;
    for (const k of ['newOrders', 'topUps', 'reviews', 'openShifts', 'smsWaiting', 'returns', 'lowStock']) expect(typeof badges[k]).toBe('number');
    const chart = (await http.get('/admin/home/daily-sales?days=7', admin).expect(200)).body;
    expect(chart.days).toHaveLength(7);
    expect(Number(chart.days[6].revenue)).toBeGreaterThan(0);
    expect(Number(chart.total)).toBeCloseTo(chart.days.reduce((t: number, d: { revenue: string }) => t + Number(d.revenue), 0), 2);
  });
});
