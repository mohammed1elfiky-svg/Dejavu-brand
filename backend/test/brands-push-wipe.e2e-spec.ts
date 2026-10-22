import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, customerToken, staffToken } from './helpers';

/** Brands, "new product" / offer pushes and the test-data wipe (dry run only: tests share one database). */
describe('Brands, product announcements & data wipe (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);

  const product = (name: string, extra: object) =>
    http.post('/admin/products', { categoryId, nameAr: name, basePrice: '80', variants: [{ size: 'M', initialQuantity: 2 }], ...extra }, admin).expect(201);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    categoryId = (await http.post('/admin/categories', { nameAr: `ماركات ${run}`, nameEn: `Brands ${run}`, slug: `brands-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(() => app.close());

  it('manages brands and browses products by brand', async () => {
    const brand = (await http.post('/admin/brands', { name: `Zara ${run}`, logoUrl: 'https://cdn.example.com/zara.png' }, admin).expect(201)).body;
    expect(brand.slug).toBe(`zara-${run}`);
    await http.post('/admin/brands', { name: `Zara ${run}` }, admin).expect(409);
    const arabic = (await http.post('/admin/brands', { name: `مانجو` + run }, admin).expect(201)).body;
    expect(arabic.slug).toMatch(/^manjw/);

    const p = (await product(`قميص ${run}`, { brandId: brand.id, status: 'ACTIVE' })).body;
    expect(p.brand).toMatchObject({ id: brand.id, name: `Zara ${run}` });
    await product(`بنطال ${run}`, { status: 'ACTIVE' });

    const list = (await http.get(`/admin/products?brandId=${brand.id}`, admin).expect(200)).body;
    expect(list.items.map((x: { id: string }) => x.id)).toEqual([p.id]);
    const brands = (await http.get('/admin/brands', admin).expect(200)).body;
    expect(brands.find((b: { id: string }) => b.id === brand.id).productCount).toBe(1);

    const store = (await http.get('/store/brands').expect(200)).body;
    expect(store.map((b: { slug: string }) => b.slug)).toContain(brand.slug);
    expect(store.map((b: { slug: string }) => b.slug)).not.toContain(arabic.slug); // nothing on sale
    const shop = (await http.get(`/store/products?brand=${brand.slug}`).expect(200)).body;
    expect(shop.items.map((x: { id: string }) => x.id)).toEqual([p.id]);
    expect(shop.items[0].brand.logoUrl).toBe('https://cdn.example.com/zara.png');

    // Clearing and deleting: the product just loses its brand.
    await http.patch(`/admin/brands/${brand.id}`, { logoUrl: '' }, admin).expect(200);
    await http.delete(`/admin/brands/${brand.id}`, admin).expect(204);
    expect((await http.get(`/admin/products/${p.id}`, admin).expect(200)).body.brand).toBeNull();
  });

  it('announces a published product to customers with its photo', async () => {
    const shopper = await customerToken(app);
    const image = 'https://cdn.example.com/dress.jpg';
    const draft = (await product(`مسودة ${run}`, { notifyCustomers: true, images: [{ url: image }] })).body;
    expect(draft.notifiedCustomers).toBe(0);

    const live = (await product(`فستان جديد ${run}`, { status: 'ACTIVE', notifyCustomers: true, images: [{ url: image }] })).body;
    expect(live.notifiedCustomers).toBeGreaterThan(0);
    const inbox = (await http.get('/store/notifications', shopper.token).expect(200)).body;
    const note = inbox.items.find((n: { data: { product?: string } }) => n.data?.product === live.slug);
    expect(note).toMatchObject({ type: 'PROMOTION', data: { product: live.slug, image } });
    expect(note.body).toContain(`فستان جديد ${run}`);

    // Publishing the draft later can announce it too.
    const published = (await http.patch(`/admin/products/${draft.id}`, { status: 'ACTIVE', notifyCustomers: true }, admin).expect(200)).body;
    expect(published.notifiedCustomers).toBeGreaterThan(0);
    const walkIn = await prisma.customer.findUniqueOrThrow({ where: { code: 'CUS-WALKIN' } });
    expect(await prisma.notification.count({ where: { customerId: walkIn.id, data: { path: ['product'], equals: live.slug } } })).toBe(0);
  });

  it('announces a running offer', async () => {
    await customerToken(app);
    const p = (await product(`عرض ${run}`, { status: 'ACTIVE' })).body;
    const promo = (await http.post('/admin/promotions', { name: `تخفيضات ${run}`, percent: '25', productIds: [p.id], notifyCustomers: true }, admin).expect(201)).body;
    expect(promo.notifiedCustomers).toBeGreaterThan(0);
    const sent = await prisma.notification.findFirstOrThrow({ where: { data: { path: ['promotion'], equals: promo.id } } });
    expect(sent.body).toContain('خصم 25%');
  });

  it('wipes test data only with the typed confirmation, and a dry run changes nothing', async () => {
    await http.post('/admin/system/wipe-test-data', { confirm: 'نعم' }, admin).expect(400);
    const before = await prisma.product.count();
    const preview = (await http.post('/admin/system/wipe-test-data', { confirm: 'حذف البيانات التجريبية', dryRun: true }, admin).expect(200)).body;
    expect(preview.dryRun).toBe(true);
    expect(preview.deleted.products).toBe(before);
    expect(preview.keptUsers).toEqual(['admin@dejavu.local']);
    expect(await prisma.product.count()).toBe(before);
    expect(await prisma.setting.findUnique({ where: { key: 'system.wipedAt' } })).toBeNull();

    // Operations only: customers, staff and suppliers are not touched.
    const ops = (await http.post('/admin/system/wipe-test-data', { confirm: 'حذف البيانات التجريبية', dryRun: true, scope: 'OPERATIONS' }, admin).expect(200)).body;
    expect(ops).toMatchObject({ scope: 'OPERATIONS', keptUsers: [] });
    expect(ops.deleted).toMatchObject({ products: before, customers: 0, users: 0, suppliers: 0, cashierDrawers: 0 });
    await http.post('/admin/system/wipe-test-data', { confirm: 'حذف البيانات التجريبية', dryRun: true, scope: 'SOME' }, admin).expect(400);
  });
});
