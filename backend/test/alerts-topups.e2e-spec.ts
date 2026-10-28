import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { API, api, createApp, customerToken, staffToken, uniquePng } from './helpers';

/** Staff alerts (polled by the admin app) and customers changing top-up requests under review. */
describe('Staff alerts & top-up requests (e2e)', () => {
  let app: INestApplication;
  let http: ReturnType<typeof api>;
  let admin: string;
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

  beforeAll(async () => {
    ({ app } = await createApp());
    http = api(app);
    admin = await staffToken(app);
  });
  afterAll(() => app.close());

  const slip = async (token: string) =>
    (
      await request(app.getHttpServer())
        .post(`${API}/store/me/files`)
        .set('Authorization', `Bearer ${token}`)
        .attach('file', uniquePng(), { filename: 'slip.png', contentType: 'image/png' })
        .expect(201)
    ).body.id as string;

  it('alerts staff about new top-up requests, and lets the customer edit or withdraw them while pending', async () => {
    // Staff approve each request themselves (no automatic credit).
    await http.put('/admin/wallet/settings', { autoApprove: false }, admin).expect(200);
    const since = new Date().toISOString();
    const shopper = await customerToken(app, 'عميل الشحن');
    const t = (await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '100', receiptFileId: await slip(shopper.token) }, shopper.token).expect(201)).body;

    const alerts = (await http.get(`/admin/alerts?since=${encodeURIComponent(since)}`, admin).expect(200)).body;
    expect(alerts.alerts.map((a: { id: string }) => a.id)).toContain(`topup:${t.id}`);
    expect(alerts.pendingTopUps).toBeGreaterThan(0);
    const later = (await http.get(`/admin/alerts?since=${encodeURIComponent(alerts.now)}`, admin).expect(200)).body;
    expect(later.alerts.map((a: { id: string }) => a.id)).not.toContain(`topup:${t.id}`);

    const edited = (await http.patch(`/store/me/wallet/top-ups/${t.id}`, { amount: '150', reference: 'TRX-9' }, shopper.token).expect(200)).body;
    expect(edited).toMatchObject({ amount: '150', reference: 'TRX-9', status: 'PENDING' });
    const stranger = await customerToken(app, 'غريب');
    await http.delete(`/store/me/wallet/top-ups/${t.id}`, stranger.token).expect(404);
    await http.delete(`/store/me/wallet/top-ups/${t.id}`, shopper.token).expect(204);
    expect((await http.get('/store/me/wallet/top-ups', shopper.token).expect(200)).body).toHaveLength(0);

    // Reviewed requests can no longer be changed.
    const t2 = (await http.post('/store/me/wallet/top-ups', { currency: 'SAR', amount: '20', receiptFileId: await slip(shopper.token) }, shopper.token).expect(201)).body;
    await http.post(`/admin/wallet/top-ups/${t2.id}/reject`, { reason: 'السند غير واضح' }, admin).expect(200);
    await http.patch(`/store/me/wallet/top-ups/${t2.id}`, { amount: '25' }, shopper.token).expect(409);
    await http.delete(`/store/me/wallet/top-ups/${t2.id}`, shopper.token).expect(409);
    const inbox = (await http.get('/store/notifications', shopper.token).expect(200)).body;
    expect(inbox.items.some((n: { type: string }) => n.type === 'WALLET')).toBe(true);
    await http.put('/admin/wallet/settings', { autoApprove: true }, admin).expect(200);
  });
});
