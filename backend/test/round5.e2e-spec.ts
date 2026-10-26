import { INestApplication } from '@nestjs/common';
import ExcelJS from 'exceljs';
import request from 'supertest';
import { GeminiClient } from '../src/chat/gemini.client';
import { PrismaService } from '../src/prisma/prisma.service';
import { StorefrontService } from '../src/storefront/storefront.service';
import { API, api, createApp, customerToken, ledgerBalances, staffToken } from './helpers';

const binary = (r: request.Response, cb: (err: Error | null, body: Buffer) => void) => {
  const chunks: Buffer[] = [];
  r.on('data', (c: Buffer) => chunks.push(c));
  r.on('end', () => cb(null, Buffer.concat(chunks)));
};

/**
 * Round 5: owner alerts, bulk prices and offers, wish list and its alerts, suggestions,
 * app settings and sign-in gate, usage analytics, review prompts, layaways, gift cards,
 * damaged stock, campaigns, Excel, product/category profit and the shopping assistant.
 */
describe('Round 5 (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  let categorySlug: string;
  const run = Date.now().toString(36);
  let made = 0;

  const product = async (name: string, price: number, sizes: { size: string; qty: number }[]) =>
    (
      await http
        .post(
          '/admin/products',
          { categoryId, nameAr: `${name} ${run}`, slug: `r5-${run}-${++made}`, status: 'ACTIVE', basePrice: String(price), costPrice: String(price / 2), variants: sizes.map((s) => ({ size: s.size, initialQuantity: s.qty })) },
          admin,
        )
        .expect(201)
    ).body as { id: string; slug: string; nameAr: string; variants: { id: string }[] };
  const onHand = async (variantId: string) => (await prisma.stockLevel.aggregate({ where: { variantId }, _sum: { onHand: true, reserved: true } }))._sum;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const cat = (await http.post('/admin/categories', { nameAr: `جولة ٥ ${run}`, nameEn: `Round5 ${run}`, slug: `round5-${run}` }, admin).expect(201)).body;
    categoryId = cat.id;
    categorySlug = cat.slug;
  });
  afterAll(async () => {
    await http.put('/admin/store-app', { requireLogin: false }, admin);
    delete process.env.GEMINI_API_KEY;
    await app.close();
  });

  it('alerts the owner about deleted invoices and price changes, as they choose', async () => {
    const p = await product('حذاء تنبيه', 100, [{ size: '38', qty: 5 }]);
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '100' }] }, admin).expect(201)).body;
    await http.post(`/admin/pos/sales/${sale.id}/void`, { reason: 'تجربة' }, admin).expect(200);
    await http.patch(`/admin/products/${p.id}`, { basePrice: '90' }, admin).expect(200);
    await new Promise((r) => setTimeout(r, 300));
    const alerts = (await http.get('/admin/owner-alerts', admin).expect(200)).body as { kind: string; body: string }[];
    expect(alerts.some((a) => a.kind === 'VOID' && a.body.includes(sale.number) && a.body.includes('تجربة'))).toBe(true);
    expect(alerts.some((a) => a.kind === 'PRICE' && a.body.includes(p.nameAr))).toBe(true);
    // Shown in the admin app's alerts too (works without Firebase).
    const staff = (await http.get('/admin/alerts', admin).expect(200)).body.alerts as { kind: string }[];
    expect(staff.some((a) => a.kind === 'OWNER')).toBe(true);

    await http.put('/admin/owner-alerts/settings', { priceChanges: false, smsPhone: '777123456' }, admin).expect(200);
    const before = await prisma.ownerAlert.count({ where: { kind: 'PRICE' } });
    await http.patch(`/admin/products/${p.id}`, { basePrice: '95' }, admin).expect(200);
    await new Promise((r) => setTimeout(r, 300));
    expect(await prisma.ownerAlert.count({ where: { kind: 'PRICE' } })).toBe(before);
    await http.put('/admin/owner-alerts/settings', { priceChanges: true, smsPhone: '' }, admin).expect(200);
  });

  it('changes many prices at once (preview first) and lists the products on offer', async () => {
    const a = await product('شنطة جماعي', 100, [{ size: 'M', qty: 2 }]);
    const b = await product('صندل جماعي', 47, [{ size: '39', qty: 2 }]);
    const preview = (await http.post('/admin/pricing/bulk', { productIds: [a.id, b.id], mode: 'PERCENT', value: '-10', roundTo: 5, preview: true }, admin).expect(200)).body;
    expect(preview).toMatchObject({ preview: true, count: 2 });
    expect(preview.items.find((i: { productId: string }) => i.productId === b.id).newPrice).toBe('40'); // 42.3 → 40
    expect((await http.get(`/admin/products/${a.id}`, admin).expect(200)).body.basePrice).toBe('100');

    await http.post('/admin/pricing/bulk', { productIds: [a.id], mode: 'PERCENT', value: '-10', roundTo: 5, keepOldAsCompareAt: true }, admin).expect(200);
    const after = (await http.get(`/admin/products/${a.id}`, admin).expect(200)).body;
    expect(after).toMatchObject({ basePrice: '90', compareAtPrice: '100' });
    const offers = (await http.get('/admin/pricing/on-offer', admin).expect(200)).body as { productId: string; percentOff: string }[];
    expect(offers.find((o) => o.productId === a.id)).toMatchObject({ percentOff: '10' });
    await http.post('/admin/pricing/bulk', { categoryIds: [categoryId], mode: 'AMOUNT', value: '5', preview: true }, admin).expect(200);
    await http.post('/admin/pricing/bulk', { mode: 'AMOUNT', value: '5' }, admin).expect(400);
  });

  it('keeps a wish list and tells the customer when the price drops or few pieces are left', async () => {
    const p = await product('كعب مفضلة', 200, [{ size: '37', qty: 2 }, { size: '38', qty: 1 }]);
    const c = await customerToken(app, 'محبة المفضلة');
    expect((await http.post(`/store/wishlist/${p.id}`, {}, c.token).expect(200)).body).toEqual([p.id]);
    expect((await http.get('/store/wishlist', c.token).expect(200)).body[0]).toMatchObject({ id: p.id, isNew: true, fewLeft: 3 });
    const storefront = app.get(StorefrontService);
    await storefront.checkWishlist();
    const titles = async () => (await prisma.notification.findMany({ where: { customerId: c.customerId }, select: { title: true } })).map((n) => n.title);
    expect(await titles()).toContain('قربت تخلص ⏳');
    expect(await titles()).not.toContain('نزل السعر ❤️');

    await http.patch(`/admin/products/${p.id}`, { basePrice: '150' }, admin).expect(200);
    await storefront.checkWishlist();
    expect((await titles()).filter((t) => t === 'نزل السعر ❤️')).toHaveLength(1);
    await storefront.checkWishlist();
    expect((await titles()).filter((t) => t === 'نزل السعر ❤️')).toHaveLength(1);
    expect((await titles()).filter((t) => t === 'قربت تخلص ⏳')).toHaveLength(1);

    expect((await http.delete(`/store/wishlist/${p.id}`, c.token).expect(200)).body).toEqual([]);
  });

  it('suggests products: similar ones, bought together, and for the cart', async () => {
    const shoe = await product('حذاء مقترح', 120, [{ size: '38', qty: 5 }]);
    const bag = await product('شنطة مقترحة', 130, [{ size: 'M', qty: 5 }]);
    const c = await customerToken(app, 'مشترية مقترحات');
    await http.post('/admin/pos/sales', { items: [{ variantId: shoe.variants[0].id, quantity: 1 }, { variantId: bag.variants[0].id, quantity: 1 }], saleType: 'CASH', customerId: c.customerId, payments: [{ currency: 'SAR', amount: '250' }] }, admin).expect(201);
    const related = (await http.get(`/store/products/${shoe.slug}/related`).expect(200)).body;
    expect(related.alsoBought.map((p: { id: string }) => p.id)).toContain(bag.id);
    await http.post('/store/cart/items', { variantId: shoe.variants[0].id, quantity: 1 }, c.token).expect(201);
    const suggested = (await http.get('/store/cart/suggestions', c.token).expect(200)).body as { id: string }[];
    expect(suggested.map((p) => p.id)).toContain(bag.id);
  });

  it('lets the owner set the store page, ads and sign-in before browsing', async () => {
    await http.put('/admin/store-about', { about: 'متجر أحذية وشنط', phones: ['777000111', ' '], whatsapp: '967777000111', hours: '9 ص - 10 م' }, admin).expect(200);
    expect((await http.get('/store/about').expect(200)).body).toMatchObject({ about: 'متجر أحذية وشنط', phones: ['777000111'] });
    await http.put('/admin/store-app', { ads: { enabled: true, bannerUnitId: 'wrong' } }, admin).expect(400);
    await http.put('/admin/store-app', { ads: { enabled: true, bannerUnitId: 'ca-app-pub-1234567890123456/1234567890', interstitialEvery: 5 } }, admin).expect(200);
    expect((await http.get('/store/config').expect(200)).body).toMatchObject({ requireLogin: false, ads: { enabled: true, interstitialEvery: 5 }, chat: { enabled: false } });

    await http.put('/admin/store-app', { requireLogin: true }, admin).expect(200);
    await http.get('/store/products').expect(401);
    const c = await customerToken(app, 'مسجلة');
    await http.get('/store/products', c.token).expect(200);
    await http.put('/admin/store-app', { requireLogin: false, ads: { enabled: false } }, admin).expect(200);
    await http.get('/store/products').expect(200);
  });

  it('records time in the app, product views and searches, and reports them', async () => {
    const p = await product('شنطة مشاهدة', 90, [{ size: 'S', qty: 0 }]);
    const device = `dev-${run}`;
    const sessionId = '6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b';
    await http.post('/store/analytics', { deviceId: device, sessions: [{ id: sessionId, startedAt: new Date(Date.now() - 600_000).toISOString(), seconds: 120 }], views: [{ productId: p.id, seconds: 40, at: new Date().toISOString() }] }).expect(200);
    // The same session sent again later, longer; then the shopper signs in on that phone.
    const c = await customerToken(app, 'متصفحة');
    await http.post('/store/analytics', { deviceId: device, sessions: [{ id: sessionId, startedAt: new Date(Date.now() - 600_000).toISOString(), seconds: 300 }], views: [{ productId: p.id, seconds: 20, at: new Date().toISOString() }] }, c.token).expect(200);
    await request(app.getHttpServer()).get(`${API}/store/products?q=${encodeURIComponent(`شنطة مشاهدة ${run}`)}`).set('x-device-id', device).expect(200);
    await request(app.getHttpServer()).get(`${API}/store/products?q=zzznothing${run}`).set('x-device-id', device).expect(200);

    const report = (await http.get('/admin/reports/behavior', admin).expect(200)).body;
    expect(report.totals.seconds).toBeGreaterThanOrEqual(300);
    expect(report.topCustomers.find((x: { customer: { id: string } }) => x.customer.id === c.customerId)).toMatchObject({ seconds: 300, sessions: 1 });
    expect(report.topViewed.find((x: { productId: string }) => x.productId === p.id)).toMatchObject({ views: 2, viewers: 1, avgSeconds: 30, sold: 0 });
    expect(report.viewedOutOfStock.some((x: { productId: string }) => x.productId === p.id)).toBe(true);
    expect(report.noResultSearches.some((s: { query: string }) => s.query === `zzznothing${run}`)).toBe(true);
    expect(report.hours).toHaveLength(24);
    const usage = (await http.get(`/admin/customers/${c.customerId}/usage`, admin).expect(200)).body;
    expect(usage).toMatchObject({ seconds: 300, sessions: 1 });
    expect(usage.mostViewed[0]).toMatchObject({ productId: p.id, views: 2 });
    expect((await http.get('/store/search/popular').expect(200)).body).toContain(`شنطة مشاهدة ${run}`.toLowerCase());
  });

  it('asks for a review a day after the purchase, once', async () => {
    const p = await product('صندل تقييم', 80, [{ size: '40', qty: 3 }]);
    const c = await customerToken(app, 'مقيّمة');
    await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], saleType: 'CASH', customerId: c.customerId, payments: [{ currency: 'SAR', amount: '80' }] }, admin).expect(201);
    const storefront = app.get(StorefrontService);
    await storefront.promptReviews();
    const later = new Date(Date.now() + 25 * 3600_000);
    await storefront.promptReviews(later);
    await storefront.promptReviews(later);
    const n = await prisma.notification.findMany({ where: { customerId: c.customerId, title: 'كيف كانت مشترياتك؟ ⭐' } });
    expect(n).toHaveLength(1);
    expect(n[0].data).toMatchObject({ product: p.slug });
  });

  it('puts pieces aside against a deposit and sells them when the rest is paid', async () => {
    const p = await product('فستان حجز', 100, [{ size: 'M', qty: 2 }]);
    const v = p.variants[0].id;
    const c = await customerToken(app, 'حاجزة');
    const lay = (await http.post('/admin/layaways', { customerId: c.customerId, items: [{ variantId: v, quantity: 1 }], deposit: { currency: 'SAR', amount: '30' }, expiresAt: new Date(Date.now() + 7 * 864e5).toISOString() }, admin).expect(201)).body;
    expect(lay).toMatchObject({ status: 'OPEN', total: '100', remaining: '70' });
    expect(await onHand(v)).toMatchObject({ onHand: 2, reserved: 1 });
    await http.post(`/admin/layaways/${lay.id}/payments`, { currency: 'YER', amount: '1000' }, admin).expect(422);
    expect((await http.post(`/admin/layaways/${lay.id}/payments`, { currency: 'SAR', amount: '20' }, admin).expect(201)).body.remaining).toBe('50');

    const done = (await http.post(`/admin/layaways/${lay.id}/complete`, { payments: [{ currency: 'SAR', amount: '50' }] }, admin).expect(200)).body;
    expect(done.layaway.status).toBe('COMPLETED');
    expect(done.sale.grandTotal).toBe('100');
    expect(await onHand(v)).toMatchObject({ onHand: 1, reserved: 0 });
    // The deposits paid the sale: nothing left on the customer's account.
    const account = (await http.get(`/admin/customers/${c.customerId}/account`, admin).expect(200)).body;
    expect((account.balances ?? []).filter((b: { balance: string }) => Number(b.balance) !== 0)).toHaveLength(0);
    await http.post(`/admin/layaways/${lay.id}/cancel`, {}, admin).expect(422);

    const other = (await http.post('/admin/layaways', { customerId: c.customerId, items: [{ variantId: v, quantity: 1 }] }, admin).expect(201)).body;
    await http.post('/admin/layaways', { customerId: c.customerId, items: [{ variantId: v, quantity: 1 }] }, admin).expect(409);
    await http.post(`/admin/layaways/${other.id}/cancel`, { reason: 'غيرت رأيها' }, admin).expect(200);
    expect(await onHand(v)).toMatchObject({ onHand: 1, reserved: 0 });
    expect((await http.get('/admin/layaways?status=CANCELLED', admin).expect(200)).body.items.some((l: { id: string }) => l.id === other.id)).toBe(true);
  });

  it('sells gift cards that customers redeem into their wallet', async () => {
    const card = (await http.post('/admin/gift-cards', { currency: 'SAR', amount: '150', buyerName: 'أم سارة' }, admin).expect(201)).body;
    expect(card.code).toMatch(/^GC-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
    expect(await ledgerBalances(prisma, [card.id])).toMatchObject({ GIFT_CARDS: -150 });
    expect((await http.get(`/admin/gift-cards/check/${card.code.toLowerCase()}`, admin).expect(200)).body).toMatchObject({ usable: true, amount: '150' });

    const c = await customerToken(app, 'صاحبة الهدية');
    expect((await http.post('/store/me/gift-cards/redeem', { code: card.code }, c.token).expect(200)).body).toMatchObject({ amount: '150', currency: 'SAR' });
    await http.post('/store/me/gift-cards/redeem', { code: card.code }, c.token).expect(422);
    const books = await ledgerBalances(prisma, [card.id]);
    expect(books.GIFT_CARDS ?? 0).toBe(0);
    expect(books.CUSTOMER_ACCOUNTS).toBe(-150);
    const wallet = (await http.get('/store/me/wallet', c.token).expect(200)).body;
    expect(wallet.balances.find((b: { currency: string }) => b.currency === 'SAR').balance).toBe('150');

    const unused = (await http.post('/admin/gift-cards', { currency: 'SAR', amount: '50' }, admin).expect(201)).body;
    expect((await http.post(`/admin/gift-cards/${unused.id}/cancel`, {}, admin).expect(200)).body.status).toBe('CANCELLED');
    expect((await ledgerBalances(prisma, [unused.id])).GIFT_CARDS ?? 0).toBe(0);
    const list = (await http.get('/admin/gift-cards?status=REDEEMED', admin).expect(200)).body;
    expect(list.items.find((x: { id: string }) => x.id === card.id).redeemedBy.name).toBe('صاحبة الهدية');
  });

  it('writes off damaged pieces with photos', async () => {
    const p = await product('شنطة تالفة', 60, [{ size: 'L', qty: 4 }]);
    const d = (await http.post('/admin/inventory/damages', { items: [{ variantId: p.variants[0].id, quantity: 2 }], note: 'تمزق في الجلد', photoUrls: ['admin/files/x'] }, admin).expect(201)).body;
    expect(d.number).toMatch(/^DMG/);
    expect(Number(d.totalValue)).toBe(60);
    expect((await onHand(p.variants[0].id)).onHand).toBe(2);
    expect(await ledgerBalances(prisma, [d.id])).toMatchObject({ INVENTORY: -60, INVENTORY_ADJUSTMENT: 60 });
    const list = (await http.get('/admin/inventory/damages', admin).expect(200)).body;
    expect(list.items[0]).toMatchObject({ id: d.id, photoUrls: ['admin/files/x'] });
  });

  it('messages a chosen group of customers, with a picture and a link to a category', async () => {
    const a = await customerToken(app, 'عدنية');
    const b = await customerToken(app, 'تعزية');
    await prisma.customer.update({ where: { id: a.customerId }, data: { governorate: `عدن-${run}` } });
    await prisma.customer.update({ where: { id: b.customerId }, data: { governorate: `تعز-${run}` } });
    expect((await http.post('/admin/campaigns/preview', { segment: { governorates: [`عدن-${run}`] } }, admin).expect(200)).body.count).toBe(1);
    const sent = (
      await http
        .post(
          '/admin/campaigns',
          { title: 'عرض عدن', body: 'خصم 20% على الأحذية', imageUrl: 'https://cdn.example.com/x.png', target: { type: 'category', value: categorySlug }, segment: { governorates: [`عدن-${run}`] }, channels: ['PUSH', 'SMS'] },
          admin,
        )
        .expect(201)
    ).body;
    expect(sent).toMatchObject({ recipients: 1, smsCount: 1 });
    const got = await prisma.notification.findFirstOrThrow({ where: { customerId: a.customerId, title: 'عرض عدن' } });
    expect(got.data).toMatchObject({ category: categorySlug, image: 'https://cdn.example.com/x.png' });
    expect(await prisma.notification.count({ where: { customerId: b.customerId, title: 'عرض عدن' } })).toBe(0);
    await http.post('/admin/campaigns', { title: 'x', body: 'رابط', target: { type: 'url', value: 'ftp://x' }, segment: {}, channels: ['PUSH'] }, admin).expect(400);
    expect((await http.get('/admin/campaigns', admin).expect(200)).body[0].title).toBe('عرض عدن');
  });

  it('exports lists to Excel and adds products from a sheet', async () => {
    const res = await request(app.getHttpServer()).get(`${API}/admin/export/products.xlsx`).set('Authorization', `Bearer ${admin}`).buffer(true).parse(binary);
    expect(res.status).toBe(200);
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(res.body as never);
    expect(wb.worksheets[0].getRow(1).getCell(1).value).toBe('المنتج');
    for (const k of ['stock', 'customers', 'sales', 'debtors', 'product-sales']) {
      expect((await request(app.getHttpServer()).get(`${API}/admin/export/${k}`).set('Authorization', `Bearer ${admin}`).buffer(true).parse(binary)).status).toBe(200);
    }
    await http.get('/admin/export/secrets', admin).expect(400);

    const tpl = await request(app.getHttpServer()).get(`${API}/admin/import/products/template`).set('Authorization', `Bearer ${admin}`).buffer(true).parse(binary);
    const template = new ExcelJS.Workbook();
    await template.xlsx.load(tpl.body as never);
    const headers = (template.worksheets[0].getRow(1).values as unknown[]).slice(1);
    const sheetOf = async (rows: unknown[][]) => {
      const wb2 = new ExcelJS.Workbook();
      const ws = wb2.addWorksheet('المنتجات');
      ws.addRow(headers);
      for (const r of rows) ws.addRow(r);
      return Buffer.from(await wb2.xlsx.writeBuffer());
    };
    const rowA = [`حذاء مستورد اكسل ${run}`, `قسم اكسل ${run}`, '', 150, '', 70, '38', 'أسود', '', 2, ''];
    const rowB = [`حذاء مستورد اكسل ${run}`, `قسم اكسل ${run}`, '', 150, '', 70, '39', 'أسود', '', 1, ''];
    const bad = await sheetOf([rowA, rowB, ['', 'بدون اسم', '', 'abc', '', '', '', '', '', '', '']]);
    const check = await request(app.getHttpServer()).post(`${API}/admin/import/products`).set('Authorization', `Bearer ${admin}`).field('dryRun', 'true').attach('file', bad, 'p.xlsx');
    expect(check.status).toBe(200);
    expect(check.body.errors.map((e: { row: number }) => e.row)).toEqual([4, 4]);
    expect(check.body.saved).toBe(false);
    const good = await sheetOf([rowA, rowB]);
    const saved = await request(app.getHttpServer()).post(`${API}/admin/import/products`).set('Authorization', `Bearer ${admin}`).attach('file', good, 'p.xlsx');
    expect(saved.body).toMatchObject({ saved: true, created: 1, variantsAdded: 2, errors: [] });
    const created = await prisma.product.findFirstOrThrow({ where: { nameAr: `حذاء مستورد اكسل ${run}` }, include: { variants: { include: { stockLevels: true } }, category: true } });
    expect(created.category.nameAr).toBe(`قسم اكسل ${run}`);
    expect(created.variants.map((v) => v.stockLevels.reduce((n, s) => n + s.onHand, 0)).sort()).toEqual([1, 2]);
  });

  it('ranks products and categories by pieces sold and by profit', async () => {
    const report = (await http.get('/admin/reports/products?limit=200', admin).expect(200)).body;
    expect(Array.isArray(report.topProfit)).toBe(true);
    const mine = report.topCategories.find((c: { categoryId: string }) => c.categoryId === categoryId);
    expect(mine.units).toBeGreaterThan(0);
    expect(Number(mine.grossProfit)).toBeCloseTo(Number(mine.revenue) - Number(mine.cost), 1);
    expect(report.profitableCategories.length).toBe(report.topCategories.length);
  });

  it('answers shoppers with the store data through the assistant, photos included', async () => {
    process.env.GEMINI_API_KEY = 'test-key';
    const p = await product('كعب مساعد', 175, [{ size: '38', qty: 2 }]);
    const c = await customerToken(app, 'سائلة');
    await http.post('/store/chat', { text: 'مرحبا' }, c.token).expect(503);
    await http.put('/admin/chat/settings', { enabled: true, whatsapp: '967777000111', dailyLimit: 3 }, admin).expect(200);
    expect((await http.get('/store/config').expect(200)).body.chat.enabled).toBe(true);

    const gemini = app.get(GeminiClient);
    const calls: { contents: unknown[] }[] = [];
    const spy = jest.spyOn(gemini, 'generate').mockImplementation(async (req) => {
      calls.push({ contents: [...req.contents] });
      const last = req.contents[req.contents.length - 1];
      if (last.parts.some((x) => 'functionResponse' in x)) return [{ text: `عندنا ${p.nameAr} بـ 175 ريال ومقاس 38 متوفر` }];
      return [{ functionCall: { name: 'search_products', args: { query: `كعب مساعد ${run}`, size: '38' } } }];
    });
    const photo = (await request(app.getHttpServer()).post(`${API}/store/me/files`).set('Authorization', `Bearer ${c.token}`).attach('file', Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64'), 'shoe.png').expect(201)).body;
    const answer = (await http.post('/store/chat', { text: 'عندكم مثل هذا؟', imageFileId: photo.id }, c.token).expect(200)).body;
    expect(answer.text).toContain('175');
    expect(answer.products.map((x: { id: string }) => x.id)).toEqual([p.id]);
    const firstCall = calls[0].contents as { parts: Record<string, unknown>[] }[];
    expect(firstCall[firstCall.length - 1].parts.some((x) => 'inline_data' in x)).toBe(true);

    const history = (await http.get('/store/chat', c.token).expect(200)).body;
    expect(history.messages.map((m: { role: string }) => m.role)).toEqual(['USER', 'BOT']);
    expect(history.messages[1].products[0].id).toBe(p.id);
    await http.post('/store/chat', { text: '2' }, c.token).expect(200);
    await http.post('/store/chat', { text: '3' }, c.token).expect(200);
    await http.post('/store/chat', { text: '4' }, c.token).expect(429);
    const convs = (await http.get('/admin/chat/conversations', admin).expect(200)).body;
    expect(convs.find((x: { customer: { id: string } }) => x.customer.id === c.customerId)).toMatchObject({ messages: 6, images: 1 });
    // The connection test says what happened.
    spy.mockRestore();
    const failing = jest.spyOn(gemini, 'generate').mockRejectedValue(Object.assign(new Error('API key not valid'), { status: 400 }));
    expect((await http.post('/admin/chat/test', {}, admin).expect(200)).body).toMatchObject({ ok: false, status: 400 });
    failing.mockRestore();
    await http.put('/admin/chat/settings', { enabled: false }, admin).expect(200);
  });
});
