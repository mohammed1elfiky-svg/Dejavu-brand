import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { API, api, createApp, customerToken, staffToken, uniquePng } from './helpers';

/** Payment methods and cash on delivery per governorate, Yemeni addresses, shipping paid to the courier, bank account delete, reviews with photos. */
describe('Store settings and reviews (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  const run = Date.now().toString(36);
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
  });
  afterAll(async () => {
    await http.put('/admin/store-payment-methods', { methods: ['CASH_ON_DELIVERY', 'CARD', 'WALLET', 'BANK_TRANSFER', 'CUSTOMER_ACCOUNT'] }, admin);
    await prisma.setting.update({ where: { key: 'store.chargeShipping' }, data: { value: true } });
    await app.close();
  });

  it('lists the Yemeni governorates and lets the owner choose payment methods and cash on delivery per governorate', async () => {
    const zones = (await http.get('/store/shipping-zones').expect(200)).body as { governorate: string; codEnabled: boolean }[];
    expect(zones.map((z) => z.governorate)).toEqual(expect.arrayContaining(['أمانة العاصمة', 'تعز', 'عدن', 'حضرموت']));
    expect((await http.get('/store/payment-methods').expect(200)).body).not.toContain('INSTAPAY');

    await http.put('/admin/store-payment-methods', { methods: ['CASH_ON_DELIVERY', 'INSTAPAY'] }, admin).expect(400);
    expect((await http.put('/admin/store-payment-methods', { methods: ['CASH_ON_DELIVERY', 'BANK_TRANSFER'] }, admin).expect(200)).body).toEqual(['CASH_ON_DELIVERY', 'BANK_TRANSFER']);
    await http.put('/admin/shipping-zones', { governorate: 'حضرموت', fee: '0', codEnabled: false }, admin).expect(200);
    expect((await http.get(`/store/payment-methods?governorate=${encodeURIComponent('حضرموت')}`).expect(200)).body).toEqual(['BANK_TRANSFER']);
    expect((await http.get(`/store/payment-methods?governorate=${encodeURIComponent('عدن')}`).expect(200)).body).toEqual(['CASH_ON_DELIVERY', 'BANK_TRANSFER']);
    const admins = (await http.get('/admin/shipping-zones', admin).expect(200)).body as { governorate: string; codEnabled: boolean }[];
    expect(admins.find((z) => z.governorate === 'حضرموت')!.codEnabled).toBe(false);
  });

  it('adds no shipping fee to the order (the customer pays the courier) and refuses cash on delivery where it is off', async () => {
    await prisma.setting.update({ where: { key: 'store.chargeShipping' }, data: { value: false } });
    const cat = (await http.post('/admin/categories', { nameAr: `متجر ${run}`, nameEn: `Store ${run}`, slug: `store-${run}` }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `صندل ${run}`, slug: `sandal-${run}`, status: 'ACTIVE', basePrice: '60', variants: [{ size: '39', initialQuantity: 5 }] }, admin).expect(201)).body;
    const c = await customerToken(app, 'عميلة حضرموت');
    const mukalla = (await http.post('/store/me/addresses', { recipient: 'عميلة', phone: c.phone, governorate: 'حضرموت', city: 'المكلا', street: 'الشارع العام' }, c.token).expect(201)).body;
    const aden = (await http.post('/store/me/addresses', { recipient: 'عميلة', phone: c.phone, governorate: 'عدن', city: 'المنصورة', street: 'الشارع' }, c.token).expect(201)).body;
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const cart = (await http.get(`/store/cart?addressId=${aden.id}`, c.token).expect(200)).body;
    expect(cart.totals.shippingFee).toBe('0');
    expect(cart.shippingProblem).toBeNull();

    await http.post('/store/orders', { addressId: mukalla.id, paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(422);
    await http.post('/store/orders', { addressId: aden.id, paymentMethod: 'WALLET' }, c.token).expect(422);
    const order = (await http.post('/store/orders', { addressId: aden.id, paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(201)).body;
    expect(order.shippingFee).toBe('0');
  });

  it('deletes a store bank account, or hides it when top-ups used it', async () => {
    const unused = (await http.post('/admin/bank-accounts', { bankName: 'بنك التضامن', accountName: 'ديجافو', accountNumber: `1${run}`, currency: 'YER' }, admin).expect(201)).body;
    expect((await http.delete(`/admin/bank-accounts/${unused.id}`, admin).expect(200)).body).toEqual({ deleted: true, hidden: false });
    expect(await prisma.storeBankAccount.count({ where: { id: unused.id } })).toBe(0);

    const used = (await http.post('/admin/bank-accounts', { bankName: 'الكريمي', accountName: 'ديجافو', accountNumber: `2${run}`, currency: 'SAR' }, admin).expect(201)).body;
    const c = await customerToken(app, 'شاحنة');
    const slip = (await request(app.getHttpServer()).post(`${API}/store/me/files`).set('Authorization', `Bearer ${c.token}`).attach('file', uniquePng(), 'slip.png').expect(201)).body;
    await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '50', receiptFileId: slip.id, bankAccountId: used.id }, c.token).expect(201);
    expect((await http.delete(`/admin/bank-accounts/${used.id}`, admin).expect(200)).body).toEqual({ deleted: false, hidden: true });
    expect((await http.get('/store/bank-accounts').expect(200)).body.some((b: { id: string }) => b.id === used.id)).toBe(false);
  });

  it('takes reviews with photos from customers who received the product, shown once approved', async () => {
    const cat = (await http.post('/admin/categories', { nameAr: `تقييم ${run}`, nameEn: `Review ${run}`, slug: `review-${run}` }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `كعب ${run}`, slug: `heel-rev-${run}`, status: 'ACTIVE', basePrice: '90', variants: [{ size: '38', initialQuantity: 3 }] }, admin).expect(201)).body;
    const c = await customerToken(app, 'سارة أحمد');
    await http.put(`/store/products/heel-rev-${run}/my-review`, { rating: 5 }, c.token).expect(422);
    expect((await http.get(`/store/products/heel-rev-${run}/my-review`, c.token).expect(200)).body.canReview).toBe(false);

    // Bought at the till by this customer (completed = received).
    await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], customerId: c.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '90' }] }, admin).expect(201);
    const photo = (await request(app.getHttpServer()).post(`${API}/store/me/review-photos`).set('Authorization', `Bearer ${c.token}`).attach('file', uniquePng(), 'me.png').expect(201)).body;
    const review = (await http.put(`/store/products/heel-rev-${run}/my-review`, { rating: 4, comment: 'مريح وجميل', images: [photo.url] }, c.token).expect(200)).body;
    expect(review.isApproved).toBe(false);
    expect((await http.get(`/store/products/heel-rev-${run}/reviews`).expect(200)).body.total).toBe(0);

    const pending = (await http.get('/admin/reviews?status=pending', admin).expect(200)).body.items as { id: string }[];
    expect(pending.some((r) => r.id === review.id)).toBe(true);
    await http.post(`/admin/reviews/${review.id}/approve`, { approved: true }, admin).expect(200);
    const shown = (await http.get(`/store/products/heel-rev-${run}/reviews`).expect(200)).body;
    expect(shown).toMatchObject({ count: 1, total: 1 });
    expect(Number(shown.average)).toBe(4);
    expect(shown.items[0]).toMatchObject({ rating: 4, comment: 'مريح وجميل', customerName: 'سارة', images: [photo.url] });
    expect((await http.get(`/store/products/heel-rev-${run}`).expect(200)).body.ratingCount).toBe(1);

    await http.delete(`/admin/reviews/${review.id}`, admin).expect(204);
    expect((await http.get(`/store/products/heel-rev-${run}/reviews`).expect(200)).body.count).toBe(0);
  });
});
