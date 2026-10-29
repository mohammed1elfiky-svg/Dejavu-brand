import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { OrdersService } from '../src/sales/orders.service';
import { GiftsService } from '../src/social/gifts.service';
import { api, createApp, customerToken, staffToken, uniquePhone } from './helpers';

/**
 * Round 9: shipping companies and their offices, store pickup, offers for the first N, loyalty
 * points, the full statement, usernames with Instagram verification, blocked numbers, the social
 * side (posts, follows, likes, comments, ambassadors), gifts at the till and group gifts, today's
 * tasks and dormant customers.
 */
describe('Round 9 (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);
  const tag = run.replace(/[^a-z0-9]/g, '').slice(-6);
  let made = 0;

  const product = async (name: string, price: number, qty: number) =>
    (
      await http
        .post(
          '/admin/products',
          { categoryId, nameAr: `${name} ${run}`, slug: `r9-${run}-${++made}`, status: 'ACTIVE', basePrice: String(price), costPrice: String(price / 2), variants: [{ size: '38', initialQuantity: qty }] },
          admin,
        )
        .expect(201)
    ).body as { id: string; slug: string; variants: { id: string }[] };

  const address = async (token: string) =>
    (await http.post('/store/me/addresses', { recipient: 'عميلة', phone: '771234567', governorate: 'تعز', city: 'تعز', street: 'شارع جمال' }, token).expect(201)).body.id as string;

  /** A customer with a username. */
  const member = async (prefix: string, extra: object = {}) => {
    const phone = uniquePhone();
    const username = `${prefix}${tag}${++made}`;
    const res = await http.post('/auth/customer/register', { name: `${prefix} عميلة`, phone, password: 'secret123', username, ...extra }).expect(201);
    return { token: res.body.accessToken as string, id: res.body.customer.id as string, username, phone, customer: res.body.customer };
  };

  const credit = (customerId: string, amount: string, currency = 'SAR') =>
    http.post(`/admin/customers/${customerId}/account/opening-balance`, { currency, amount, direction: 'CREDIT' }, admin).expect(201);

  const sar = async (token: string) =>
    ((await http.get('/store/me/wallet', token).expect(200)).body.balances as { currency: string; balance: string }[]).find((b) => b.currency === 'SAR')?.balance ?? '0';

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/shipping-zones', { governorate: 'تعز', fee: 20 }, admin).expect(200);
    categoryId = (await http.post('/admin/categories', { nameAr: `جولة ٩ ${run}`, nameEn: `Round9 ${run}`, slug: `round9-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(async () => {
    await http.put('/admin/loyalty/settings', { enabled: false }, admin);
    await app.close();
  });

  it('ships to a carrier office or lets the customer pick up from the store for free', async () => {
    const carrier = (await http.post('/admin/carriers', { name: `الجوال القدسي ${run}`, phones: ['777000111'] }, admin).expect(201)).body;
    const office = (await http.post(`/admin/carriers/${carrier.id}/offices`, { name: 'فرع جمال', governorate: 'تعز', address: 'شارع جمال جوار البنك', phones: ['777000222'] }, admin).expect(201)).body.offices[0];
    const listed = (await http.get('/store/carriers').expect(200)).body as { id: string; offices: { id: string }[] }[];
    expect(listed.find((c) => c.id === carrier.id)?.offices.map((o) => o.id)).toContain(office.id);

    const p = await product('كعب مكتب', 100, 5);
    const c = await customerToken(app, 'استلام من المكتب');
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const pickupCart = (await http.get('/store/cart?deliveryMethod=PICKUP', c.token).expect(200)).body;
    expect(pickupCart.shipping.fee).toBe('0');
    // Home delivery needs an address.
    await http.post('/store/orders', { deliveryMethod: 'HOME', paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(400);

    const order = (await http.post('/store/orders', { deliveryMethod: 'OFFICE', carrierOfficeId: office.id, paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(201)).body;
    expect(order).toMatchObject({ deliveryMethod: 'OFFICE', carrier: { id: carrier.id }, carrierOffice: { id: office.id }, shippingFee: '20' });

    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 1 }, c.token).expect(201);
    const pickup = (await http.post('/store/orders', { deliveryMethod: 'PICKUP', paymentMethod: 'CASH_ON_DELIVERY' }, c.token).expect(201)).body;
    expect(pickup).toMatchObject({ deliveryMethod: 'PICKUP', shippingFee: '0' });

    // Shipped with the carrier's receipt photo; the customer is asked 3 days later and confirms it arrived.
    await http.post(`/admin/orders/${order.id}/status`, { status: 'CONFIRMED' }, admin).expect(200);
    await http
      .post(`/admin/orders/${order.id}/status`, { status: 'SHIPPED', carrier: carrier.name, trackingNumber: 'JQ-77', receiptImageUrl: 'https://example.com/r.jpg', photoUrls: ['https://example.com/p.jpg'] }, admin)
      .expect(200);
    const mineShipped = (await http.get(`/store/orders/${order.number}`, c.token).expect(200)).body;
    expect(mineShipped.shipments[0]).toMatchObject({ trackingNumber: 'JQ-77', receiptImageUrl: 'https://example.com/r.jpg', photoUrls: ['https://example.com/p.jpg'] });
    await http.post(`/store/orders/${pickup.number}/received`, {}, c.token).expect(422); // not shipped
    expect(await app.get(OrdersService).remindReceived(new Date(Date.now() + 4 * 86_400_000))).toBeGreaterThanOrEqual(1);
    expect(await prisma.notification.count({ where: { customerId: c.customerId, title: { contains: 'هل وصلك' } } })).toBe(1);
    expect((await http.post(`/store/orders/${order.number}/received`, {}, c.token).expect(200)).body.status).toBe('DELIVERED');

    // A carrier already used is hidden, not deleted.
    await http.delete(`/admin/carriers/${carrier.id}`, admin).expect((r) => expect([200, 204]).toContain(r.status));
    expect(((await http.get('/store/carriers').expect(200)).body as { id: string }[]).map((x) => x.id)).not.toContain(carrier.id);
  });

  it('gives an offer to the first N customers only, and frees the place when the sale is deleted', async () => {
    const p = await product('عرض محدود', 50, 20);
    const deal = (await http.post('/admin/deals', { name: 'لأول عميلة', type: 'BUY_X_GET_Y', buyQty: 1, freeQty: 1, productIds: [p.id], maxCustomers: 1 }, admin).expect(201)).body;
    const a = await customerToken(app, 'الأولى');
    const b = await customerToken(app, 'الثانية');
    const items = [{ variantId: p.variants[0].id, quantity: 2 }];
    const first = (await http.post('/admin/pos/sales', { items, customerId: a.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '50' }] }, admin).expect(201)).body;
    expect(first.grandTotal).toBe('50');
    const used = ((await http.get('/admin/deals', admin).expect(200)).body as { id: string; usedCustomers: number; exhausted: boolean }[]).find((d) => d.id === deal.id);
    expect(used).toMatchObject({ usedCustomers: 1, exhausted: true });
    // The second customer pays full price.
    const quote = (await http.post('/admin/pos/quote', { items, customerId: b.customerId }, admin).expect(200)).body;
    expect(quote.total).toBe('100');

    await http.post(`/admin/pos/sales/${first.id}/void`, { reason: 'خطأ' }, admin).expect(200);
    const again = (await http.post('/admin/pos/quote', { items, customerId: b.customerId }, admin).expect(200)).body;
    expect(again.total).toBe('50');
  });

  it('earns loyalty points on purchases and turns them into wallet credit', async () => {
    await http.put('/admin/loyalty/settings', { enabled: true, scope: 'ALL', pointsPerSar: 1, sarPer100Points: 5, minRedeemPoints: 100, startsAt: null }, admin).expect(200);
    const p = await product('نقاط', 200, 5);
    const c = await customerToken(app, 'نقاط الولاء');
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], customerId: c.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '200' }] }, admin).expect(201)).body;
    expect((await http.get('/store/me/loyalty', c.token).expect(200)).body).toMatchObject({ points: 200, value: '10', earning: true });

    await http.post('/store/me/loyalty/redeem', { points: 50 }, c.token).expect(422); // below the minimum
    const after = (await http.post('/store/me/loyalty/redeem', { points: 100 }, c.token).expect(200)).body;
    expect(after.points).toBe(100);
    expect(await sar(c.token)).toBe('5');

    // Switched off for this customer only.
    await http.patch(`/admin/customers/${c.customerId}/loyalty`, { enabled: false }, admin).expect(200);
    expect((await http.get(`/admin/customers/${c.customerId}/loyalty`, admin).expect(200)).body.earning).toBe(false);
    await http.post(`/admin/customers/${c.customerId}/loyalty/adjust`, { points: 25, note: 'هدية' }, admin).expect(201);
    // Deleting the sale takes back what it earned.
    await http.post(`/admin/pos/sales/${sale.id}/void`, { reason: 'خطأ' }, admin).expect(200);
    expect((await http.get('/store/me/loyalty', c.token).expect(200)).body.points).toBe(-75);
  });

  it('prints the statement per currency with invoice numbers and pieces', async () => {
    const p = await product('كشف', 60, 5);
    const c = await customerToken(app, 'كشف حساب');
    await credit(c.customerId, '1000', 'YER');
    await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 2 }], customerId: c.customerId, saleType: 'CREDIT' }, admin).expect(201);
    const st = (await http.get(`/admin/customers/${c.customerId}/account/statement`, admin).expect(200)).body;
    expect(st.currencies.map((x: { currency: string }) => x.currency).sort()).toEqual(['SAR', 'YER']);
    const sarPage = st.currencies.find((x: { currency: string }) => x.currency === 'SAR');
    expect(sarPage.rows[0]).toMatchObject({ pieces: 2, owes: '120', credit: '0', balance: '120' });
    expect(sarPage.rows[0].reference).toMatch(/\d/);
    expect(sarPage.rows[0].orderId).toBeTruthy();
    expect((await http.get('/store/me/wallet/statement', c.token).expect(200)).body.currencies).toHaveLength(2);
  });

  it('takes Instagram-style usernames and verifies them with the code sent from Instagram', async () => {
    await http.post('/auth/customer/register', { name: 'يوزر خطأ', phone: uniquePhone(), password: 'secret123', username: '1abc' }).expect(400);
    const m = await member('ig', { usernameIsInstagram: true, birthDate: '1999-05-14' });
    expect(m.customer).toMatchObject({ username: m.username, igStatus: 'PENDING' });
    expect(m.customer.igCode).toMatch(/^\d{4}$/);
    await http.post('/auth/customer/register', { name: 'مكرر', phone: uniquePhone(), password: 'secret123', username: m.username.toUpperCase() }).expect(409);
    expect((await http.get(`/store/usernames/check?u=${m.username}`).expect(200)).body).toMatchObject({ available: false, reason: 'اليوزر مستخدم' });
    expect((await http.get(`/store/usernames/check?u=${m.username}zz`).expect(200)).body.available).toBe(true);

    const pending = (await http.get('/admin/instagram-verifications', admin).expect(200)).body as { id: string }[];
    expect(pending.map((x) => x.id)).toContain(m.id);
    await http.post(`/admin/customers/${m.id}/instagram/verify`, { code: m.customer.igCode === '0000' ? '1111' : '0000' }, admin).expect(400);
    await http.post(`/admin/customers/${m.id}/instagram/verify`, { code: m.customer.igCode }, admin).expect(200);
    expect((await http.get('/store/me/profile', m.token).expect(200)).body.igStatus).toBe('VERIFIED');

    // Changing the username drops the badge.
    const renamed = `${m.username}x`;
    const prof = (await http.patch('/store/me/profile', { username: renamed }, m.token).expect(200)).body;
    expect(prof).toMatchObject({ username: renamed, igStatus: 'NONE' });
  });

  it('blocks a customer and their number for good, and shows what people have in their carts', async () => {
    const c = await customerToken(app, 'محظورة');
    const p = await product('في السلة', 30, 5);
    await http.post('/store/cart/items', { variantId: p.variants[0].id, quantity: 2 }, c.token).expect(201);
    const carts = (await http.get('/admin/carts', admin).expect(200)).body as { customer: { id: string }; items: { quantity: number }[] }[];
    expect(carts.find((x) => x.customer.id === c.customerId)?.items[0].quantity).toBe(2);

    await http.post(`/admin/customers/${c.customerId}/block`, { reason: 'احتيال' }, admin).expect(200);
    await http.post('/auth/customer/login', { phone: c.phone, password: 'secret123' }).expect(403);
    const loose = uniquePhone();
    await http.post('/admin/blocked-phones', { phone: loose }, admin).expect(201);
    await http.post('/auth/customer/register', { name: 'رقم محظور', phone: loose, password: 'secret123' }).expect(403);

    await http.post(`/admin/customers/${c.customerId}/unblock`, {}, admin).expect(200);
    await http.post('/auth/customer/login', { phone: c.phone, password: 'secret123' }).expect(200);
  });

  it('runs the social side and pays the ambassador once the order is received', async () => {
    await http.put('/admin/social/ambassador-settings', { enabled: true, percent: 10, kind: 'WALLET' }, admin).expect(200);
    const author = await member('st');
    const fan = await member('fn');
    const p = await product('إطلالة', 100, 5);
    const post = (await http.post('/store/social/posts', { caption: `إطلالتي @${fan.username}`, images: ['https://example.com/a.jpg'], tags: [{ productId: p.id, variantId: p.variants[0].id, x: 0.5, y: 0.5 }] }, author.token).expect(201)).body;

    await http.post(`/store/social/users/${author.username}/follow`, {}, fan.token).expect(200);
    await http.post(`/store/social/posts/${post.id}/like`, {}, fan.token).expect(200);
    await http.post(`/store/social/posts/${post.id}/comments`, { body: 'تجنن 😍' }, fan.token).expect(201);
    const feed = (await http.get('/store/social/feed?tab=FOLLOWING', fan.token).expect(200)).body;
    const seen = (feed.items ?? feed).find((x: { id: string }) => x.id === post.id);
    expect(seen).toMatchObject({ likeCount: 1, commentCount: 1, liked: true });
    const profile = (await http.get(`/store/social/users/${author.username}`, fan.token).expect(200)).body;
    expect(profile).toMatchObject({ counts: { followers: 1 }, isFollowing: true });
    const told = await prisma.notification.findMany({ where: { customerId: author.id } });
    expect(told.length).toBeGreaterThanOrEqual(3); // follow, like, comment
    expect(await prisma.notification.count({ where: { customerId: fan.id, title: { contains: 'أشار إليك' } } })).toBe(1);

    // "Shop the look" from the post, then the order is delivered: the author gets 10%.
    await http.post(`/store/social/posts/${post.id}/shop`, {}, fan.token).expect(200);
    const order = (await http.post('/store/orders', { deliveryMethod: 'HOME', addressId: await address(fan.token), paymentMethod: 'CASH_ON_DELIVERY' }, fan.token).expect(201)).body;
    expect((await http.get('/store/social/me/rewards', author.token).expect(200)).body).toMatchObject({ pending: '10', paid: '0' });
    for (const status of ['CONFIRMED', 'SHIPPED', 'DELIVERED']) await http.post(`/admin/orders/${order.id}/status`, { status, ...(status === 'SHIPPED' ? { carrier: 'محلي' } : {}) }, admin).expect(200);
    expect((await http.get('/store/social/me/rewards', author.token).expect(200)).body).toMatchObject({ pending: '0', paid: '10' });
    expect(await sar(author.token)).toBe('10');

    // Moderation: hidden posts leave the feed.
    await http.put(`/admin/social/posts/${post.id}/hidden`, { hidden: true }, admin).expect(200);
    const after = (await http.get('/store/social/feed?tab=FOLLOWING', fan.token).expect(200)).body;
    expect((after.items ?? after).map((x: { id: string }) => x.id)).not.toContain(post.id);
  });

  it('marks a till sale as a gift: the receiver chooses pickup and the staff hand it over', async () => {
    const p = await product('هدية', 80, 5);
    const to = await member('gf');
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '80' }] }, admin).expect(201)).body;
    const gift = (await http.post('/admin/gifts', { saleOrderId: sale.id, to: `@${to.username}`, giverName: 'سارة', occasion: 'عيد ميلادك' }, admin).expect(201)).body;
    await http.post('/admin/gifts', { saleOrderId: sale.id, to: to.username, giverName: 'سارة' }, admin).expect(422);
    const note = await prisma.notification.findFirst({ where: { customerId: to.id, title: { contains: 'هدية' } } });
    expect(note?.body).toContain('سارة');
    expect(note?.body).toContain('عيد ميلادك');

    const mine = (await http.get('/store/me/gifts', to.token).expect(200)).body as { id: string }[];
    expect(mine.map((g) => g.id)).toContain(gift.id);
    await http.post(`/store/me/gifts/${gift.id}/choose`, { deliveryMethod: 'PICKUP' }, to.token).expect(200);
    const today = (await http.get('/admin/today', admin).expect(200)).body;
    expect(today.gifts.map((g: { id: string }) => g.id)).toContain(gift.id);
    expect(today.counts.gifts).toBeGreaterThan(0);
    expect((await http.post(`/admin/gifts/${gift.id}/done`, {}, admin).expect(200)).body.status).toBe('DONE');
  });

  it('collects a group gift from friends, buys it once complete and keeps it secret until then', async () => {
    const p = await product('هدية جماعية', 150, 5);
    const to = await member('bd');
    const a = await member('ga');
    const b = await member('gb');
    await credit(a.id, '100');
    await credit(b.id, '100');
    const g = (await http.post('/store/group-gifts', { to: to.username, variantId: p.variants[0].id, occasion: 'تخرجك' }, a.token).expect(201)).body;
    expect(g).toMatchObject({ target: '150', raised: '0', status: 'OPEN' });
    await http.get(`/store/group-gifts/${g.id}`, to.token).expect(404);
    expect(((await http.get(`/store/group-gifts/for/${to.username}`, b.token).expect(200)).body as { id: string }[]).map((x) => x.id)).toContain(g.id);

    await http.post(`/store/group-gifts/${g.id}/contribute`, { amount: '90' }, a.token).expect(200);
    await http.post(`/store/group-gifts/${g.id}/contribute`, { amount: '70' }, b.token).expect(422); // more than what is left
    const done = (await http.post(`/store/group-gifts/${g.id}/contribute`, { amount: '60' }, b.token).expect(200)).body;
    expect(done.funded).toBe(true);
    expect(await sar(a.token)).toBe('10');
    expect(await sar(b.token)).toBe('40');
    // The receiver's wallet is untouched; the gift waits for their choice.
    expect(await sar(to.token)).toBe('0');
    const gifts = (await http.get('/store/me/gifts', to.token).expect(200)).body as { source: string; status: string }[];
    expect(gifts[0]).toMatchObject({ source: 'GROUP', status: 'WAITING' });
    expect((await http.get(`/store/group-gifts/${g.id}`, to.token).expect(200)).body.status).toBe('FUNDED');

    // A collection that runs out of time is refunded.
    const c = await member('gc');
    await credit(c.id, '50');
    const late = (await http.post('/store/group-gifts', { to: to.username, variantId: p.variants[0].id }, c.token).expect(201)).body;
    await http.post(`/store/group-gifts/${late.id}/contribute`, { amount: '50' }, c.token).expect(200);
    expect(await sar(c.token)).toBe('0');
    await app.get(GiftsService).expireGroupGifts(new Date(Date.now() + 40 * 86_400_000));
    expect(await sar(c.token)).toBe('50');
  });

  it('lists dormant customers and sends them personal coupons only they can use', async () => {
    const p = await product('خامل', 40, 5);
    const away = await customerToken(app, 'غائبة');
    const other = await customerToken(app, 'غيرها');
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: p.variants[0].id, quantity: 1 }], customerId: away.customerId, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '40' }] }, admin).expect(201)).body;
    await prisma.order.update({ where: { id: sale.id }, data: { placedAt: new Date(Date.now() - 90 * 86_400_000) } });
    const dormant = (await http.get('/admin/dormant-customers?days=60', admin).expect(200)).body as { id: string; daysAway: number }[];
    expect(dormant.find((d) => d.id === away.customerId)?.daysAway).toBeGreaterThanOrEqual(89);

    const sent = (await http.post('/admin/dormant-customers/coupon', { customerIds: [away.customerId], type: 'PERCENTAGE', value: '15', validDays: 7 }, admin).expect(201)).body;
    expect(sent.sent).toBe(1);
    const code = sent.coupons[0].code as string;
    await http.get(`/admin/pos/coupons/check?code=${code}&customerId=${other.customerId}`, admin).expect(422);
    expect((await http.get(`/admin/pos/coupons/check?code=${code}&customerId=${away.customerId}`, admin).expect(200)).body.code).toBe(code);
    expect((await http.get('/admin/dormant-customers?days=60', admin).expect(200)).body.find((d: { id: string }) => d.id === away.customerId).openCoupon).toBe(code);
  });

  it('keeps an editable prompt for drafting products from photos', async () => {
    const def = (await http.get('/admin/ai/product-prompt', admin).expect(200)).body;
    expect(def.isDefault).toBe(true);
    const saved = (await http.put('/admin/ai/product-prompt', { prompt: 'اكتب الاسم بأسلوب فخم' }, admin).expect(200)).body;
    expect(saved).toMatchObject({ prompt: 'اكتب الاسم بأسلوب فخم', isDefault: false });
    expect((await http.put('/admin/ai/product-prompt', { prompt: '' }, admin).expect(200)).body.prompt).toBe(def.defaultPrompt);
  });
});
