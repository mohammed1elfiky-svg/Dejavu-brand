import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { API, api, createApp, customerToken, staffToken } from './helpers';

/** What Google Play asks for: account deletion (app and web), a privacy policy, reporting AI answers. */
describe('Play Store requirements (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
  });
  afterAll(() => app.close());

  it('serves the privacy policy and the deletion page as web pages', async () => {
    const privacy = await http.get('/privacy').expect(200);
    expect(privacy.headers['content-type']).toContain('text/html');
    expect(privacy.text).toContain('سياسة الخصوصية');
    expect(privacy.text).toContain('Privacy policy');
    const page = await http.get('/delete-account').expect(200);
    expect(page.text).toContain('<form method="post"');
  });

  it('lets a customer delete their own account; orders stay without their personal data', async () => {
    const c = await customerToken(app, 'عميلة ستحذف حسابها');
    await prisma.customerAddress.create({ data: { customerId: c.customerId, recipient: 'x', phone: c.phone, governorate: 'صنعاء', city: 'صنعاء', street: 'شارع' } });
    await prisma.chatMessage.create({ data: { customerId: c.customerId, role: 'USER', text: 'مرحبا' } });

    await http.post('/store/me/delete-account', { password: 'wrong-pass' }, c.token).expect(401);
    await http.post('/store/me/delete-account', { password: 'secret123' }, c.token).expect(204);

    const after = await prisma.customer.findUniqueOrThrow({ where: { id: c.customerId } });
    expect(after).toMatchObject({ name: 'عميل محذوف', status: 'DELETED', passwordHash: null, email: null });
    expect(after.phone).not.toBe(c.phone);
    expect(await prisma.customerAddress.count({ where: { customerId: c.customerId } })).toBe(0);
    expect(await prisma.chatMessage.count({ where: { customerId: c.customerId } })).toBe(0);
    expect(await prisma.refreshToken.count({ where: { customerId: c.customerId } })).toBe(0);
    await http.post('/auth/customer/login', { phone: c.phone, password: 'secret123' }).expect(401);
    // The phone number is free to sign up again.
    await http.post('/auth/customer/register', { name: 'عادت', phone: c.phone, password: 'secret123' }).expect(201);
  });

  it('deletes an account from the web form with phone and password', async () => {
    const c = await customerToken(app, 'حذف من الموقع');
    const form = (body: Record<string, string>) => request(app.getHttpServer()).post(`${API}/delete-account`).type('form').send(body).expect(200);
    expect((await form({ phone: c.phone, password: 'secret123' })).text).toContain('مربع التأكيد');
    expect((await form({ phone: c.phone, password: 'nope-nope', confirm: 'yes' })).text).toContain('غير صحيحة');
    expect((await prisma.customer.findUniqueOrThrow({ where: { id: c.customerId } })).status).toBe('ACTIVE');
    expect((await form({ phone: c.phone, password: 'secret123', confirm: 'yes' })).text).toContain('تم حذف الحساب');
    expect((await prisma.customer.findUniqueOrThrow({ where: { id: c.customerId } })).status).toBe('DELETED');
  });

  it('lets the customer report an assistant answer, and tells the owner', async () => {
    const c = await customerToken(app, 'مبلّغة');
    const mine = await prisma.chatMessage.create({ data: { customerId: c.customerId, role: 'USER', text: 'سؤال' } });
    const bot = await prisma.chatMessage.create({ data: { customerId: c.customerId, role: 'BOT', text: 'رد غير لائق' } });
    await http.post(`/store/chat/${mine.id}/report`, {}, c.token).expect(400);
    await http.post(`/store/chat/${bot.id}/report`, { reason: 'كلام مسيء' }, c.token).expect(200);
    expect(await prisma.chatMessage.findUniqueOrThrow({ where: { id: bot.id } })).toMatchObject({ reportReason: 'كلام مسيء' });
    const alert = await prisma.ownerAlert.findFirstOrThrow({ where: { kind: 'CHAT_REPORT' }, orderBy: { createdAt: 'desc' } });
    expect(alert.body).toContain('رد غير لائق');

    const other = await customerToken(app, 'غيرها');
    await http.post(`/store/chat/${bot.id}/report`, {}, other.token).expect(404);

    const admin = await staffToken(app);
    const list = (await http.get('/admin/chat/conversations', admin).expect(200)).body as { customer: { id: string }; reports: number }[];
    expect(list.find((x) => x.customer.id === c.customerId)?.reports).toBe(1);
  });
});
