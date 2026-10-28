import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AiService } from '../src/chat/ai.service';
import { GeminiClient } from '../src/chat/gemini.client';
import { PrismaService } from '../src/prisma/prisma.service';
import { PreOrdersService } from '../src/sales/preorders.service';
import { API, api, createApp, customerToken, staffToken } from './helpers';

/** Round 6: quantity offers (buy X get Y, sets), pre-orders on incoming stock, the AI helpers. */
describe('Round 6 (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);
  let made = 0;

  const product = async (name: string, price: number, sizes: { size: string; qty: number }[]) =>
    (
      await http
        .post(
          '/admin/products',
          { categoryId, nameAr: `${name} ${run}`, slug: `r6-${run}-${++made}`, status: 'ACTIVE', basePrice: String(price), costPrice: String(price / 2), variants: sizes.map((s) => ({ size: s.size, initialQuantity: s.qty })) },
          admin,
        )
        .expect(201)
    ).body as { id: string; slug: string; nameAr: string; variants: { id: string }[] };

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/USD', { rate: '0.25' }, admin).expect(200);
    categoryId = (await http.post('/admin/categories', { nameAr: `جولة ٦ ${run}`, nameEn: `Round6 ${run}`, slug: `round6-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(async () => {
    delete process.env.GEMINI_API_KEY;
    await app.close();
  });

  it('gives "buy one get one free" at the till and in the app, on the chosen products only', async () => {
    const a = await product('كعب عرض', 100, [{ size: '38', qty: 5 }]);
    const b = await product('صندل عرض', 60, [{ size: '39', qty: 5 }]);
    const other = await product('شنطة بدون عرض', 50, [{ size: 'M', qty: 5 }]);
    const deal = (await http.post('/admin/deals', { name: 'قطعة وقطعة مجاناً', type: 'BUY_X_GET_Y', buyQty: 1, freeQty: 1, productIds: [a.id, b.id] }, admin).expect(201)).body;
    expect(deal).toMatchObject({ label: 'قطعة + قطعة مجاناً', running: true });
    await http.post('/admin/deals', { name: 'طقم ناقص', type: 'BUNDLE', bundlePrice: '100', productIds: [a.id] }, admin).expect(400);

    const items = [{ variantId: a.variants[0].id, quantity: 1 }, { variantId: b.variants[0].id, quantity: 1 }, { variantId: other.variants[0].id, quantity: 1 }];
    const quote = (await http.post('/admin/pos/quote', { items }, admin).expect(200)).body;
    expect(quote).toMatchObject({ subtotal: '210', dealDiscount: '60', total: '150' });
    expect(quote.deals[0]).toMatchObject({ name: 'قطعة وقطعة مجاناً', times: 1 });

    const sale = (await http.post('/admin/pos/sales', { items, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '150' }] }, admin).expect(201)).body;
    expect(sale.grandTotal).toBe('150');
    expect(sale.discountTotal).toBe('60');
    // Rung up offline without the offer: full price.
    const offline = (await http.post('/admin/pos/sales', { items, saleType: 'CASH', applyDeals: false, payments: [{ currency: 'SAR', amount: '210' }] }, admin).expect(201)).body;
    expect(offline.grandTotal).toBe('210');

    const shown = (await http.get(`/store/products/${a.slug}`).expect(200)).body;
    expect(shown.deals[0]).toMatchObject({ label: 'قطعة + قطعة مجاناً' });
    const c = await customerToken(app, 'مستفيدة العرض');
    await http.post('/store/cart/items', { variantId: a.variants[0].id, quantity: 1 }, c.token).expect(201);
    await http.post('/store/cart/items', { variantId: b.variants[0].id, quantity: 1 }, c.token).expect(201);
    const cart = (await http.get('/store/cart', c.token).expect(200)).body;
    expect(cart.totals).toMatchObject({ subtotal: '160', discountTotal: '60', grandTotal: '100' });
    expect(cart.deals[0].name).toBe('قطعة وقطعة مجاناً');

    await http.patch(`/admin/deals/${deal.id}`, { isActive: false }, admin).expect(200);
    expect((await http.post('/admin/pos/quote', { items }, admin).expect(200)).body.dealDiscount).toBe('0');
    await http.delete(`/admin/deals/${deal.id}`, admin).expect(204);
  });

  it('sells a set (طقم) at one price and two-plus-one', async () => {
    const shoe = await product('حذاء طقم', 150, [{ size: '38', qty: 5 }]);
    const bag = await product('شنطة طقم', 100, [{ size: 'M', qty: 5 }]);
    await http.post('/admin/deals', { name: 'طقم الحذاء والشنطة', type: 'BUNDLE', bundlePrice: '200', productIds: [shoe.id, bag.id] }, admin).expect(201);
    const q = (await http.post('/admin/pos/quote', { items: [{ variantId: shoe.variants[0].id, quantity: 1 }, { variantId: bag.variants[0].id, quantity: 1 }] }, admin).expect(200)).body;
    expect(q).toMatchObject({ dealDiscount: '50', total: '200' });
    const byIds = (await http.get(`/store/products/by-ids?ids=${bag.id},${shoe.id},nope`).expect(200)).body as { id: string; deals: { type: string }[] }[];
    expect(byIds.map((x) => x.id)).toEqual([bag.id, shoe.id]);
    expect(byIds[0].deals[0].type).toBe('BUNDLE');

    const tee = await product('بلوزة ٢+١', 40, [{ size: 'S', qty: 9 }]);
    await http.post('/admin/deals', { name: 'قطعتين والثالثة مجاناً', type: 'BUY_X_GET_Y', buyQty: 2, freeQty: 1, productIds: [tee.id], online: false }, admin).expect(201);
    const t = (await http.post('/admin/pos/quote', { items: [{ variantId: tee.variants[0].id, quantity: 4 }] }, admin).expect(200)).body;
    expect(t).toMatchObject({ dealDiscount: '40', total: '120' });
    // Till only: not shown in the app.
    expect((await http.get(`/store/products/${tee.slug}`).expect(200)).body.deals).toEqual([]);
  });

  it('takes pre-orders on pieces still on their way and tells the customer when they arrive', async () => {
    const p = await product('كعب قادم', 120, [{ size: '37', qty: 0 }]);
    const v = p.variants[0].id;
    const c = await customerToken(app, 'منتظرة الشحنة');
    await http.post('/store/pre-orders', { variantId: v, quantity: 1 }, c.token).expect(422);

    const supplier = (await http.post('/admin/suppliers', { name: `مصنع مسبق ${run}` }, admin).expect(201)).body;
    const po = (await http.post('/admin/purchase-orders/import', { supplierId: supplier.id, foreignPerUsd: '7', items: [{ variantId: v, quantity: 3, unitPriceForeign: '70' }] }, admin).expect(201)).body;
    const shown = (await http.get(`/store/products/${p.slug}`).expect(200)).body;
    expect(shown.variants[0]).toMatchObject({ inStock: false, incomingQty: 3 });

    const mine = (await http.post('/store/pre-orders', { variantId: v, quantity: 2 }, c.token).expect(201)).body;
    expect(mine).toMatchObject({ status: 'WAITING', quantity: 2 });
    await http.post('/store/pre-orders', { variantId: v, quantity: 2 }, c.token).expect(422); // only 1 left on the way
    expect((await http.get('/admin/pre-orders/incoming', admin).expect(200)).body.find((i: { variantId: string }) => i.variantId === v)).toMatchObject({ incoming: 3, claimed: 2, open: 1 });

    const preOrders = app.get(PreOrdersService);
    expect(await preOrders.checkArrivals()).toBe(0);
    await http.post(`/admin/purchase-orders/${po.id}/import-receive`, { items: [{ itemId: po.items[0].id, quantity: 3 }], totalCbm: '1', cbmRateUsd: '10' }, admin).expect(200);
    expect(await preOrders.checkArrivals()).toBe(1);
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: 'وصل طلبك المسبق 🎉' } })).toBe(1);
    expect(await preOrders.checkArrivals()).toBe(0);

    const done = (await http.post(`/admin/pre-orders/${mine.id}/convert`, {}, admin).expect(200)).body;
    expect(done.preOrder.status).toBe('CONVERTED');
    expect(done.layaway).toMatchObject({ status: 'OPEN', total: '240' });
    expect((await prisma.stockLevel.aggregate({ where: { variantId: v }, _sum: { reserved: true } }))._sum.reserved).toBe(2);
    expect((await http.get('/store/pre-orders', c.token).expect(200)).body[0].status).toBe('CONVERTED');
  });

  it('drafts a product from a photo, writes campaign texts and a weekly summary', async () => {
    await http.post('/admin/ai/marketing-text', { idea: 'خصم الكعب', channel: 'PUSH' }, admin).expect(503);
    // Without Gemini the weekly summary is put together from the figures.
    const plain = (await http.get('/admin/ai/weekly-summary?refresh=true', admin).expect(200)).body;
    expect(plain.byAi).toBe(false);
    expect(plain.text).toContain('المبيعات');
    expect(Number(plain.stats.revenue)).toBeGreaterThan(0);

    process.env.GEMINI_API_KEY = 'test-key';
    const gemini = app.get(GeminiClient);
    const spy = jest.spyOn(gemini, 'generate').mockImplementation(async (req) => {
      const ask = JSON.stringify(req.contents);
      if (ask.includes('inline_data')) return [{ text: JSON.stringify({ nameAr: 'كعب لامع أسود', descriptionAr: 'كعب أنيق للسهرات.', categoryId, brandId: 'nope', color: 'أسود', colorHex: '#000000', heelHeightCm: 9, tags: ['كعب', 'سهرة'] }) }];
      if (ask.includes('3 صيغ')) return [{ text: JSON.stringify({ suggestions: [{ title: 'خصم الكعب 👠', body: '20% على كل الكعوب حتى الخميس' }, { title: 'لا تفوتك', body: 'كعبك الجديد بسعر أقل' }] }) }];
      return [{ text: 'المبيعات ارتفعت هذا الأسبوع.\n1. اطلب مقاس 38.' }];
    });
    const photo = (
      await request(app.getHttpServer())
        .post(`${API}/admin/files`)
        .set('Authorization', `Bearer ${admin}`)
        .attach('file', Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64'), 'p.png')
        .expect(201)
    ).body;
    const draft = (await http.post('/admin/ai/product-draft', { imageFileId: photo.id }, admin).expect(200)).body;
    expect(draft).toMatchObject({ nameAr: 'كعب لامع أسود', categoryId, brandId: null, colorHex: '#000000', heelHeightCm: 9 });
    const texts = (await http.post('/admin/ai/marketing-text', { idea: 'خصم الكعب', channel: 'PUSH' }, admin).expect(200)).body;
    expect(texts.suggestions).toHaveLength(2);

    const ai = app.get(AiService);
    const saturday = new Date('2026-11-07T08:00:00Z'); // 11:00 in Aden
    expect(await ai.weeklyIfDue(new Date('2026-11-06T08:00:00Z'))).toBe(false);
    await prisma.setting.deleteMany({ where: { key: 'ai.weekly' } });
    expect(await ai.weeklyIfDue(saturday)).toBe(true);
    expect(await ai.weeklyIfDue(saturday)).toBe(false);
    const alert = await prisma.ownerAlert.findFirstOrThrow({ where: { kind: 'WEEKLY' }, orderBy: { createdAt: 'desc' } });
    expect(alert.body).toContain('ارتفعت');
    spy.mockRestore();
  });
});
