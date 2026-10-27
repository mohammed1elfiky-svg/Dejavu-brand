import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, staffToken } from './helpers';

/** The customer app's list of server addresses, and its copy on GitHub. */
describe('App servers (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await prisma.setting.deleteMany({ where: { key: 'app.servers' } });
  });
  afterAll(async () => {
    delete process.env.DIRECTORY_GITHUB_TOKEN;
    jest.restoreAllMocks();
    await prisma.setting.deleteMany({ where: { key: 'app.servers' } });
    await app.close();
  });

  it('keeps an ordered list of https addresses that anyone can read', async () => {
    await http.put('/admin/app-servers', { servers: ['http://insecure.example.com'] }, admin).expect(400);
    await http.put('/admin/app-servers', { servers: [] }, admin).expect(400);
    const saved = (await http.put('/admin/app-servers', { servers: ['dejavu.zapto.org', 'https://shop.example.com/api/v1', 'dejavu.zapto.org'] }, admin).expect(200)).body;
    expect(saved.servers).toEqual(['https://dejavu.zapto.org/api/v1/', 'https://shop.example.com/api/v1/']);
    expect(saved.directory).toMatchObject({ configured: false, url: expect.stringContaining('raw.githubusercontent.com') });
    const pub = (await http.get('/store/servers').expect(200)).body;
    expect(pub.servers).toEqual(saved.servers);
    expect(pub.directory).toBeUndefined();
  });

  it('writes the list to the GitHub file, creating the branch the first time', async () => {
    process.env.DIRECTORY_GITHUB_TOKEN = 'test-token';
    const calls: { url: string; method: string; body?: string }[] = [];
    const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    jest.spyOn(global, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body as string | undefined });
      if (url.includes('/contents/') && method === 'GET') return json(404, { message: 'Not Found' });
      if (url.endsWith('/git/ref/heads/app-directory')) return json(404, { message: 'Not Found' });
      if (url.endsWith('/repos/mohammed1elfiky-svg/Dejavu-brand')) return json(200, { default_branch: 'main' });
      if (url.endsWith('/git/ref/heads/main')) return json(200, { object: { sha: 'abc123' } });
      if (url.endsWith('/git/refs') && method === 'POST') return json(201, {});
      if (url.includes('/contents/') && method === 'PUT') return json(201, {});
      return json(500, { message: 'unexpected' });
    });

    const saved = (await http.put('/admin/app-servers', { servers: ['https://new.example.com'] }, admin).expect(200)).body;
    expect(saved.directory.configured).toBe(true);
    expect(saved.directory.error).toBeNull();
    expect(saved.directory.syncedAt).toBeTruthy();
    const put = calls.find((c) => c.method === 'PUT')!;
    const body = JSON.parse(put.body!) as { content: string; branch: string };
    expect(body.branch).toBe('app-directory');
    expect(JSON.parse(Buffer.from(body.content, 'base64').toString()).servers).toEqual(['https://new.example.com/api/v1/']);
    expect(calls.some((c) => c.url.endsWith('/git/refs') && c.method === 'POST')).toBe(true);

    // GitHub refusing the token is shown in the admin app, the list is still saved.
    (global.fetch as jest.Mock).mockImplementation(async () => json(401, { message: 'Bad credentials' }));
    const failed = (await http.post('/admin/app-servers/sync', {}, admin).expect(201)).body;
    expect(failed.directory.error).toContain('401');
    expect(failed.servers).toEqual(['https://new.example.com/api/v1/']);
  });
});
