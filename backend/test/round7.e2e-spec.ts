import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, createProduct, customerToken, staffToken } from './helpers';

/** Round 7: unfinished sign-ups, accounts made by the owner, wallet gifts, the owner's product order. */
describe('Round 7 (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  const run = Date.now().toString(36);
  let n = 0;
  const yemeni = () => `77${String(Date.now() + ++n * 1000).slice(-7)}`;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
  });
  afterAll(async () => {
    await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin);
    await app.close();
  });

  it('keeps a sign-up without its code as pending; the code or the owner lets it in', async () => {
    await http.put('/admin/sms/settings', { requirePhoneOtp: true }, admin).expect(200);
    const phone = yemeni();
    const form = { name: 'تسجيل ناقص', phone, password: 'secret123' };
    const pending = (await http.post('/auth/customer/register', form).expect(201)).body;
    expect(pending).toMatchObject({ pending: true, sent: true, channel: 'SMS' });
    expect(pending.accessToken).toBeUndefined();
    // Sign-in waits for the code.
    const blocked = await http.post('/auth/customer/login', { phone, password: 'secret123' }).expect(403);
    expect(blocked.body.code).toBe('PENDING');
    // Signing up again takes over the unfinished account.
    await http.post('/auth/customer/register', { ...form, name: 'تسجيل ناقص ٢' }).expect(201);

    const listed = (await http.get('/admin/customers?status=PENDING&pageSize=100', admin).expect(200)).body.items as { id: string; phone: string; name: string }[];
    const row = listed.find((c) => c.phone === phone)!;
    expect(row.name).toBe('تسجيل ناقص ٢');

    // The owner hands a code over (e.g. on WhatsApp) and the shopper finishes with it.
    const issued = (await http.post(`/admin/customers/${row.id}/code`, {}, admin).expect(200)).body;
    expect(issued.code).toMatch(/^\d{6}$/);
    await http.post('/auth/customer/verify', { phone, code: issued.code === '000000' ? '111111' : '000000' }).expect(400);
    const done = (await http.post('/auth/customer/verify', { phone, code: issued.code }).expect(200)).body;
    expect(done.accessToken).toBeDefined();
    await http.post('/auth/customer/login', { phone, password: 'secret123' }).expect(200);

    // Or the owner simply approves.
    const other = yemeni();
    await http.post('/auth/customer/register', { name: 'بدون كود', phone: other, password: 'secret123' }).expect(201);
    const id = (await prisma.customer.findUniqueOrThrow({ where: { phone: other } })).id;
    await http.post(`/admin/customers/${id}/approve`, {}, admin).expect(200);
    await http.post('/auth/customer/login', { phone: other, password: 'secret123' }).expect(200);
    await http.post(`/admin/customers/${id}/approve`, {}, admin).expect(400);

    // A non-Yemeni number gets no text: ask on WhatsApp.
    const abroad = (await http.post('/auth/customer/register', { name: 'سعودي', phone: `05${String(Date.now()).slice(-8)}`, password: 'secret123' }).expect(201)).body;
    expect(abroad).toMatchObject({ pending: true, sent: false, channel: 'WHATSAPP' });
    await http.put('/admin/sms/settings', { requirePhoneOtp: false }, admin).expect(200);
  });

  it('gives a customer a random password, texted to Yemeni numbers', async () => {
    const phone = yemeni();
    const added = (await http.post('/admin/pos/customers', { name: 'عميل من المحل', phone }, admin).expect(201)).body;
    const sent = (await http.post(`/admin/customers/${added.id}/password`, {}, admin).expect(200)).body;
    expect(sent.password).toMatch(/^[a-z2-9]{8}$/);
    expect(sent.smsQueued).toBe(true);
    const text = await prisma.outgoingSms.findFirstOrThrow({ where: { phone: `+967${phone}` }, orderBy: { createdAt: 'desc' } });
    expect(text.body).toContain(sent.password);
    const session = (await http.post('/auth/customer/login', { phone, password: sent.password }).expect(200)).body;
    await http.post('/auth/change-password', { currentPassword: sent.password, newPassword: 'mynewpass1' }, session.accessToken).expect(204);
    await http.post('/auth/customer/login', { phone, password: 'mynewpass1' }).expect(200);
  });

  it('moves wallet money to another customer at once, and the books still balance', async () => {
    const giver = await customerToken(app, 'أحمد علي');
    const taker = await customerToken(app, 'منى سالم');
    await http.post(`/admin/customers/${giver.customerId}/account/opening-balance`, { currency: 'YER', amount: '10000', direction: 'CREDIT' }, admin).expect(201);

    expect((await http.get(`/store/me/wallet/gift/recipient?phone=${taker.phone}`, giver.token).expect(200)).body.name).toBe('منى س.');
    await http.get(`/store/me/wallet/gift/recipient?phone=${giver.phone}`, giver.token).expect(400);
    const gift = { phone: taker.phone, currency: 'YER', amount: '4000', password: 'secret123', note: 'مبروك' };
    await http.post('/store/me/wallet/gift', { ...gift, password: 'wrong-one' }, giver.token).expect(401);
    await http.post('/store/me/wallet/gift', { ...gift, amount: '20000' }, giver.token).expect(422);
    const sent = (await http.post('/store/me/wallet/gift', gift, giver.token).expect(200)).body;
    expect(sent.to).toBe('منى س.');

    const bal = async (token: string) =>
      ((await http.get('/store/me/wallet', token).expect(200)).body.balances as { currency: string; balance: string }[]).find((b) => b.currency === 'YER')?.balance;
    expect(await bal(giver.token)).toBe('6000');
    expect(await bal(taker.token)).toBe('4000');
    const takerWallet = (await http.get('/store/me/wallet', taker.token).expect(200)).body;
    expect(takerWallet.entries[0].note).toContain('إهداء رصيد من أحمد علي');
    expect(await prisma.notification.count({ where: { customerId: taker.customerId, title: 'وصلتك هدية رصيد 🎁' } })).toBe(1);
    const entry = await prisma.journalEntry.findFirstOrThrow({ where: { sourceType: 'WALLET_GIFT', sourceId: giver.customerId } });
    // Both sides sit on the customer-accounts ledger account: it nets to zero.
    const lines = await prisma.journalLine.findMany({ where: { journalEntryId: entry.id } });
    const net = lines.reduce((sum, l) => sum + Number(l.debit) - Number(l.credit), 0);
    expect(Math.round(net * 100)).toBe(0);
    expect(lines.filter((l) => l.customerId === giver.customerId)).toHaveLength(1);
    expect(lines.filter((l) => l.customerId === taker.customerId)).toHaveLength(1);
  });

  it("shows products in the owner's order, then the newest", async () => {
    const token = admin;
    const productOf = async (variantIds: string[]) => ({ productId: (await prisma.productVariant.findUniqueOrThrow({ where: { id: variantIds[0] } })).productId });
    const first = await productOf(await createProduct(app, token, `r7a-${run}`, 100, ['38']));
    const second = await productOf(await createProduct(app, token, `r7b-${run}`, 100, ['38']));
    const ids = async (sort?: string) =>
      ((await http.get(`/store/products?pageSize=100${sort ? `&sort=${sort}` : ''}`).expect(200)).body.items as { id: string }[]).map((p) => p.id);
    // Newest first by default while nothing is placed by hand.
    expect((await ids()).indexOf(second.productId)).toBeLessThan((await ids()).indexOf(first.productId));
    const placed = (await http.put('/admin/store-order', { productIds: [first.productId] }, admin).expect(200)).body;
    expect(placed.map((p: { id: string }) => p.id)).toEqual([first.productId]);
    expect((await ids())[0]).toBe(first.productId);
    // "New arrivals" still asks for the newest.
    expect((await ids('newest')).indexOf(second.productId)).toBeLessThan((await ids('newest')).indexOf(first.productId));
    await http.put('/admin/store-order', { productIds: [] }, admin).expect(200);
  });
});
