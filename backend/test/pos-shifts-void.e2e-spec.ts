import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import request from 'supertest';
import { API, api, createApp, ledgerBalances, staffToken } from './helpers';

/** Cashier shifts, deleting / correcting today's sales, offline re-sends, slip photos, the employee's till layout. */
describe('POS shifts, void & edit (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let cashier: string;
  let variant: string;
  const run = Date.now().toString(36);
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

  const stock = async () => (await prisma.stockLevel.aggregate({ where: { variantId: variant }, _sum: { onHand: true } }))._sum.onHand ?? 0;
  const drawer = async (token: string, currency: string) =>
    ((await http.get('/admin/cash-boxes/mine', token).expect(200)).body as { currency: string; balance: string; id: string }[]).find((b) => b.currency === currency)!;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const roles = (await http.get('/admin/roles', admin).expect(200)).body as { id: string; name: string }[];
    const phone = `77${String(Date.now()).slice(-7)}`;
    await http.post('/admin/users', { name: 'كاشير الوردية', phone, password: 'Cashier!2026', roleIds: [roles.find((r) => r.name === 'cashier')!.id] }, admin).expect(201);
    cashier = await staffToken(app, { phone, password: 'Cashier!2026' } as never);
    const cat = (await http.post('/admin/categories', { nameAr: `وردية ${run}`, nameEn: `Shift ${run}`, slug: `shift-${run}` }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId: cat.id, nameAr: `حذاء وردية ${run}`, status: 'ACTIVE', basePrice: '100', costPrice: '40', variants: [{ size: '39', initialQuantity: 20 }] }, admin).expect(201)).body;
    variant = p.variants[0].id;
  });
  afterAll(() => app.close());

  it('opens a shift with a float, counts the drawer at the close and books the difference', async () => {
    await http.get('/admin/cash-boxes/mine', cashier).expect(200); // opens the drawers
    const safe = (await http.post('/admin/cash-boxes', { name: `خزنة الوردية ${run}`, kind: 'VAULT', currency: 'SAR' }, admin).expect(201)).body as { id: string };
    // Put money in the safe for the float.
    const c = (await http.post('/auth/customer/register', { name: 'ممول الخزنة', phone: `77${String(Date.now() + 7).slice(-7)}`, password: 'secret123' }).expect(201)).body;
    await http.post(`/admin/customers/${c.customer.id}/account/receipts`, { currency: 'SAR', amount: '500', cashBoxId: safe.id }, admin).expect(201);

    expect((await http.get('/admin/pos/shifts/current', cashier).expect(200)).body).toEqual({});
    const before = Number((await drawer(cashier, 'SAR')).balance);
    const shift = (await http.post('/admin/pos/shifts/open', { floats: [{ currency: 'SAR', amount: '200', fromBoxId: safe.id }] }, cashier).expect(201)).body;
    expect(shift.status).toBe('OPEN');
    expect(Number(shift.lines.find((l: { currency: string }) => l.currency === 'SAR').opening)).toBe(before + 200);
    await http.post('/admin/pos/shifts/open', {}, cashier).expect(422);

    await http.post('/admin/pos/sales', { items: [{ variantId: variant, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '100' }] }, cashier).expect(201);
    const live = (await http.get('/admin/pos/shifts/current', cashier).expect(200)).body;
    expect(live.salesCount).toBe(1);
    expect(Number(live.lines.find((l: { currency: string }) => l.currency === 'SAR').expected)).toBe(before + 300);

    // 10 SAR short in the drawer; the rest goes to the safe.
    const closed = (
      await http.post('/admin/pos/shifts/close', { counts: [{ currency: 'SAR', counted: String(before + 290) }], handOver: [{ currency: 'SAR', toBoxId: safe.id }] }, cashier).expect(200)
    ).body;
    const line = closed.lines.find((l: { currency: string }) => l.currency === 'SAR');
    expect(line).toMatchObject({ difference: '-10' });
    expect(closed.status).toBe('CLOSED');
    expect((await drawer(cashier, 'SAR')).balance).toBe('0');
    const books = await ledgerBalances(prisma, [closed.id]);
    expect(books.CASH_OVER_SHORT).toBe(10);
  });

  it("deletes and corrects today's sales, putting stock and money back", async () => {
    const start = await stock();
    const sale = (await http.post('/admin/pos/sales', { items: [{ variantId: variant, quantity: 2 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '200' }] }, cashier).expect(201)).body;
    expect(await stock()).toBe(start - 2);
    const cash = Number((await drawer(cashier, 'SAR')).balance);

    const today = (await http.get('/admin/pos/sales', cashier).expect(200)).body as { id: string; isVoided: boolean }[];
    expect(today.some((s) => s.id === sale.id)).toBe(true);

    // Corrected to one piece.
    const fixed = (await http.put(`/admin/pos/sales/${sale.id}`, { items: [{ variantId: variant, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '100' }] }, cashier).expect(200)).body;
    expect(fixed.grandTotal).toBe('100');
    expect(fixed.internalNote).toContain(sale.number);
    expect(await stock()).toBe(start - 1);
    expect(Number((await drawer(cashier, 'SAR')).balance)).toBeCloseTo(cash - 100, 2);

    const voided = (await http.post(`/admin/pos/sales/${fixed.id}/void`, { reason: 'خطأ' }, cashier).expect(200)).body;
    expect(voided.status).toBe('CANCELLED');
    expect(await stock()).toBe(start);
    expect(Number((await drawer(cashier, 'SAR')).balance)).toBeCloseTo(cash - 200, 2);
    await http.post(`/admin/pos/sales/${fixed.id}/void`, {}, cashier).expect(422);
    // Both deleted sales leave nothing in the books.
    const books = await ledgerBalances(prisma, [sale.id, fixed.id]);
    expect(books).toEqual({});
  });

  it('books a sale sent twice from the offline queue only once, and keeps the slip photo', async () => {
    const slip = (await request(app.getHttpServer()).post(`${API}/admin/files/private`).set('Authorization', `Bearer ${cashier}`).attach('file', PNG, { filename: 'slip.png', contentType: 'image/png' }).expect(201)).body;
    const body = {
      items: [{ variantId: variant, quantity: 1 }],
      saleType: 'CASH',
      paymentMethod: 'BANK_TRANSFER',
      reference: 'DEP-1',
      slipFileId: slip.id,
      payments: [{ currency: 'SAR', amount: '100' }],
      clientRef: `offline-${run}`,
      soldAt: new Date(Date.now() - 3600_000).toISOString(),
    };
    const first = (await http.post('/admin/pos/sales', body, cashier).expect(201)).body;
    const again = (await http.post('/admin/pos/sales', body, cashier).expect(201)).body;
    expect(again.id).toBe(first.id);
    expect(first.payments[0].gatewayPayload).toEqual({ reference: 'DEP-1', slipFileId: slip.id });
    expect(new Date(first.placedAt).getTime()).toBeLessThan(Date.now() - 3000_000);
    await http.get(`/files/${slip.id}`).expect(404);
    await http.get(`/admin/files/${slip.id}`, admin).expect(200);
  });

  it("saves the employee's own checkout layout and sale currency", async () => {
    expect((await http.get('/admin/pos/settings', cashier).expect(200)).body.saleCurrency).toBe('YER');
    const saved = (await http.put('/admin/pos/settings', { checkoutButtons: ['cash', 'transfer'], hiddenButtons: ['deposit'], saleCurrency: 'YEQ' }, cashier).expect(200)).body;
    expect(saved).toEqual({ checkoutButtons: ['cash', 'transfer'], hiddenButtons: ['deposit'], saleCurrency: 'YEQ' });
    expect((await http.get('/auth/me', cashier).expect(200)).body.posSettings.checkoutButtons).toEqual(['cash', 'transfer']);
    await http.put('/admin/pos/settings', { saleCurrency: 'XXX' }, cashier).expect(400);
  });
});
