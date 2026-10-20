import { INestApplication } from '@nestjs/common';
import { ADMIN, api, createApp, customerToken, staffToken, uniquePhone } from './helpers';

describe('Auth & access control (e2e)', () => {
  let app: INestApplication;
  let http: ReturnType<typeof api>;

  beforeAll(async () => {
    ({ app } = await createApp());
    http = api(app);
  });
  afterAll(() => app.close());

  describe('staff', () => {
    it('rejects wrong credentials without revealing which part was wrong', async () => {
      const bad = await http.post('/auth/staff/login', { ...ADMIN, password: 'nope' }).expect(401);
      const unknown = await http.post('/auth/staff/login', { email: 'ghost@dejavu.local', password: 'nope' }).expect(401);
      expect(bad.body.message).toBe(unknown.body.message);
    });

    it('logs in and returns roles and permissions', async () => {
      const res = await http.post('/auth/staff/login', ADMIN).expect(200);
      expect(res.body.accessToken).toBeDefined();
      expect(res.body.refreshToken).toBeDefined();
      expect(res.body.user.roles.map((r: { name: string }) => r.name)).toContain('admin');
      expect(res.body.user.permissions).toEqual(expect.arrayContaining(['journal.post', 'orders.read']));
      expect(res.body.user.passwordHash).toBeUndefined();
    });

    it('rotates refresh tokens and revokes the family when an old token is replayed', async () => {
      const login = await http.post('/auth/staff/login', ADMIN).expect(200);
      const first = login.body.refreshToken;

      const r1 = await http.post('/auth/refresh', { refreshToken: first }).expect(200);
      const second = r1.body.refreshToken;
      expect(second).not.toBe(first);

      // Replaying the consumed token is treated as theft...
      await http.post('/auth/refresh', { refreshToken: first }).expect(401);
      // ...so the legitimately rotated token stops working too.
      await http.post('/auth/refresh', { refreshToken: second }).expect(401);
    });

    it('logout revokes the refresh token', async () => {
      const login = await http.post('/auth/staff/login', ADMIN).expect(200);
      await http.post('/auth/logout', { refreshToken: login.body.refreshToken }).expect(204);
      await http.post('/auth/refresh', { refreshToken: login.body.refreshToken }).expect(401);
    });
  });

  describe('customers', () => {
    it('registers, rejects duplicates and accepts +20 phone format on login', async () => {
      const phone = uniquePhone();
      const reg = await http
        .post('/auth/customer/register', { name: 'سارة', phone, password: 'secret123' })
        .expect(201);
      expect(reg.body.customer.code).toMatch(/^CUS-\d{6}$/);

      await http.post('/auth/customer/register', { name: 'Other', phone, password: 'secret123' }).expect(409);
      const intl = phone.replace(/^0/, '+20');
      const login = await http.post('/auth/customer/login', { phone: intl, password: 'secret123' }).expect(200);
      expect(login.body.customer.phone).toBe(phone);
    });

    it('validates input', async () => {
      const res = await http.post('/auth/customer/register', { name: 'x', phone: '123', password: 'short' }).expect(400);
      expect(res.body.message).toEqual(expect.arrayContaining([expect.stringContaining('phone')]));
      await http.post('/auth/customer/register', { name: 'Valid', phone: uniquePhone(), password: 'secret123', role: 'admin' }).expect(400);
    });
  });

  describe('authorization', () => {
    it('requires a token on protected routes', async () => {
      await http.get('/admin/orders').expect(401);
      await http.get('/admin/orders', 'not-a-jwt').expect(401);
    });

    it('keeps customers out of admin routes and staff out of customer routes', async () => {
      const { token } = await customerToken(app);
      await http.get('/admin/orders', token).expect(403);
      const admin = await staffToken(app);
      await http.get('/store/cart', admin).expect(403);
    });

    it('enforces fine-grained permissions per role', async () => {
      const admin = await staffToken(app);
      const roles = await http.get('/admin/roles', admin).expect(200);
      const storekeeper = roles.body.find((r: { name: string }) => r.name === 'storekeeper');

      const email = `store.${Date.now()}@dejavu.local`;
      await http
        .post('/admin/users', { name: 'Store Keeper', email, password: 'keeper-pass-1', roleIds: [storekeeper.id] }, admin)
        .expect(201);
      const keeper = await staffToken(app, { email, password: 'keeper-pass-1' });

      await http.get('/admin/inventory/levels', keeper).expect(200);
      const denied = await http.get('/admin/orders', keeper).expect(403);
      expect(denied.body.message).toContain('orders.read');
      await http.get('/admin/journal-entries', keeper).expect(403);
    });

    it('signs a user out when an admin deactivates them', async () => {
      const admin = await staffToken(app);
      const roles = await http.get('/admin/roles', admin).expect(200);
      const sales = roles.body.find((r: { name: string }) => r.name === 'sales');
      const email = `sales.${Date.now()}@dejavu.local`;
      const created = await http
        .post('/admin/users', { name: 'Sales Rep', email, password: 'sales-pass-1', roleIds: [sales.id] }, admin)
        .expect(201);
      const login = await http.post('/auth/staff/login', { email, password: 'sales-pass-1' }).expect(200);

      await http.patch(`/admin/users/${created.body.id}`, { status: 'SUSPENDED' }, admin).expect(200);
      await http.post('/auth/refresh', { refreshToken: login.body.refreshToken }).expect(401);
      await http.post('/auth/staff/login', { email, password: 'sales-pass-1' }).expect(401);
    });

    it('creates custom roles only from known permissions', async () => {
      const admin = await staffToken(app);
      await http.post('/admin/roles', { name: 'auditor_x', permissions: ['journal.read', 'made.up'] }, admin).expect(400);
      const role = await http.post('/admin/roles', { name: `auditor_${Date.now() % 100000}`, permissions: ['journal.read'] }, admin).expect(201);
      expect(role.body.permissions).toEqual(['journal.read']);
      await http.delete(`/admin/roles/${role.body.id}`, admin).expect(204);
    });
  });

  it('never lets the last active admin be suspended or demoted', async () => {
    const admin = await staffToken(app);
    const me = await http.get('/auth/me', admin).expect(200);
    const roles = await http.get('/admin/roles', admin).expect(200);
    const sales = roles.body.find((r: { name: string }) => r.name === 'sales');

    // Self-deactivation is blocked outright; demotion is blocked while no other admin exists.
    await http.patch(`/admin/users/${me.body.id}`, { status: 'SUSPENDED' }, admin).expect(400);
    const res = await http.patch(`/admin/users/${me.body.id}`, { roleIds: [sales.id] }, admin).expect(400);
    expect(res.body.message).toBe('At least one active admin must remain');
  });
});
