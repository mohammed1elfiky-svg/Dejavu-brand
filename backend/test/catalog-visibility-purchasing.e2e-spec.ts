import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { API, api, createApp, customerToken, staffToken } from './helpers';

/** Product visibility, auto slugs, videos, inventory value by category and one-step purchases. */
describe('Visibility, media, valuation & quick purchases (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  let brandId: string;
  const run = Date.now().toString(36);

  const make = (name: string, visibility: string) =>
    http
      .post(
        '/admin/products',
        { categoryId, nameAr: name, status: 'ACTIVE', basePrice: '50', costPrice: '20', taxRateId: null, visibility, brandId, variants: [{ size: 'M', initialQuantity: 4 }] },
        admin,
      )
      .expect(201);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    categoryId = (await http.post('/admin/categories', { nameAr: `قسم ${run}`, nameEn: `Section ${run}`, slug: `section-${run}` }, admin).expect(201)).body.id;
    brandId = (await http.post('/admin/brands', { name: `Dejavu ${run}` }, admin).expect(201)).body.id;
  });
  afterAll(() => app.close());

  it('names the product URL from the Arabic name and SKU, without an English name', async () => {
    const p = (await make('فستان سهرة', 'ALL')).body;
    expect(p.slug).toBe(`fstan-shra-${p.skuNumber}`);
    expect(p.nameEn).toBe('فستان سهرة');
    expect(p.brand.name).toBe(`Dejavu ${run}`);
  });

  it('shows each product only where its visibility allows', async () => {
    const posOnly = (await make('للكاشير فقط', 'POS_ONLY')).body;
    const merchants = (await make('للتجار', 'MERCHANTS')).body;
    const hidden = (await make('مخفي', 'HIDDEN')).body;
    const shopper = await customerToken(app);
    const merchant = await customerToken(app);
    await http.patch(`/admin/customers/${merchant.customerId}`, { isMerchant: true }, admin).expect(200);

    await http.get(`/store/products/${posOnly.slug}`).expect(404);
    await http.get(`/store/products/${merchants.slug}`, shopper.token).expect(404);
    await http.get(`/store/products/${merchants.slug}`, merchant.token).expect(200);
    await http.post('/store/cart/items', { variantId: merchants.variants[0].id, quantity: 1 }, shopper.token).expect(404);

    const cashier = await http.get(`/admin/pos/products?q=${run}`, admin).expect(200);
    void cashier;
    await http.get(`/admin/pos/lookup?code=${posOnly.skuNumber}`, admin).expect(200);
    await http.get(`/admin/pos/lookup?code=${hidden.skuNumber}`, admin).expect(404);
  });

  it('stores a product video and streams byte ranges', async () => {
    const video = Buffer.alloc(2048, 7);
    const up = await request(app.getHttpServer())
      .post(`${API}/admin/files/video`)
      .set('Authorization', `Bearer ${admin}`)
      .attach('file', video, { filename: 'v.mp4', contentType: 'video/mp4' })
      .expect(201);
    const part = await request(app.getHttpServer()).get(`${API}/files/${up.body.id}`).set('Range', 'bytes=0-99').expect(206);
    expect(part.headers['content-range']).toBe('bytes 0-99/2048');
    const p = (await make('مع فيديو', 'ALL')).body;
    const edited = await http.patch(`/admin/products/${p.id}`, { videoUrl: up.body.url.replace(/^\/\//, 'http://x/'), instagramUrl: 'https://www.instagram.com/p/abc/' }, admin).expect(200);
    expect(edited.body.instagramUrl).toBe('https://www.instagram.com/p/abc/');
  });

  it('values stock at cost and at selling price per category', async () => {
    const report = await http.get('/admin/reports/inventory-valuation', admin).expect(200);
    const mine = report.body.categories.find((c: { categoryId: string }) => c.categoryId === categoryId);
    expect(mine).toMatchObject({ pieces: expect.any(Number) });
    // 4 pieces per product at cost 20 / price 50.
    expect(Number(mine.costValue)).toBe(mine.pieces * 20);
    expect(Number(mine.saleValue)).toBe(mine.pieces * 50);
    expect(Number(report.body.totals.saleValue)).toBeGreaterThanOrEqual(Number(mine.saleValue));
  });

  it('buys, receives and pays the supplier in one step', async () => {
    const p = (await make('صنف مشتريات', 'ALL')).body;
    const supplier = await http.post('/admin/suppliers', { name: `مورد ${run}` }, admin).expect(201);
    const po = await http
      .post(
        '/admin/purchase-orders/quick',
        { supplierId: supplier.body.id, items: [{ variantId: p.variants[0].id, quantity: 10, unitCost: '25' }], payMethod: 'CASH' },
        admin,
      )
      .expect(201);
    expect(po.body).toMatchObject({ status: 'RECEIVED', grandTotal: '250', amountPaid: '250' });
    const level = await prisma.stockLevel.findFirstOrThrow({ where: { variantId: p.variants[0].id } });
    expect(level.onHand).toBe(14);
  });
});
