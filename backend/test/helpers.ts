import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AppModule } from '../src/app.module';
import { configureApp } from '../src/app.setup';
import { PrismaService } from '../src/prisma/prisma.service';

export const ADMIN = { email: 'admin@dejavu.local', password: 'ChangeMe!2026' };
export const API = '/api/v1';

export async function createApp() {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile();
  const app = configureApp(moduleRef.createNestApplication());
  await app.init();
  return { app, prisma: app.get(PrismaService) };
}

export function api(app: INestApplication) {
  const server = app.getHttpServer();
  const withAuth = (req: request.Test, token?: string) => (token ? req.set('Authorization', `Bearer ${token}`) : req);
  return {
    get: (path: string, token?: string) => withAuth(request(server).get(API + path), token),
    post: (path: string, body: object = {}, token?: string) => withAuth(request(server).post(API + path), token).send(body),
    patch: (path: string, body: object, token?: string) => withAuth(request(server).patch(API + path), token).send(body),
    put: (path: string, body: object, token?: string) => withAuth(request(server).put(API + path), token).send(body),
    delete: (path: string, token?: string) => withAuth(request(server).delete(API + path), token),
  };
}

export async function staffToken(app: INestApplication, creds = ADMIN): Promise<string> {
  const res = await api(app).post('/auth/staff/login', creds).expect(200);
  return res.body.accessToken;
}

let phoneSeq = 0;
/** Unique, valid Egyptian mobile number per test run. */
export function uniquePhone(): string {
  phoneSeq += 1;
  const tail = String((Date.now() % 1_000_000) * 100 + phoneSeq).padStart(8, '0').slice(-8);
  return `010${tail}`;
}

export async function customerToken(app: INestApplication, name = 'Test Customer') {
  const phone = uniquePhone();
  const res = await api(app).post('/auth/customer/register', { name, phone, password: 'secret123' }).expect(201);
  return { token: res.body.accessToken as string, customerId: res.body.customer.id as string, phone };
}

/** Balance per system account (debit-positive) for posted entries, optionally limited to some sources. */
export async function ledgerBalances(prisma: PrismaService, sourceIds?: string[]) {
  const lines = await prisma.journalLine.findMany({
    where: { journalEntry: { status: { in: ['POSTED', 'REVERSED'] }, ...(sourceIds && { sourceId: { in: sourceIds } }) } },
    include: { account: { select: { systemKey: true, code: true } } },
  });
  const out: Record<string, number> = {};
  for (const l of lines) {
    const key = l.account.systemKey ?? l.account.code;
    out[key] = Math.round(((out[key] ?? 0) + Number(l.debit) - Number(l.credit)) * 100) / 100;
  }
  for (const k of Object.keys(out)) if (out[k] === 0) delete out[k];
  return out;
}

/** Creates an active product with the given sizes and returns its variant ids. */
export async function createProduct(app: INestApplication, token: string, slug: string, price: number, sizes: string[]) {
  const http = api(app);
  const cat = await http.post('/admin/categories', { nameAr: `تصنيف ${slug}`, nameEn: `Cat ${slug}`, slug: `cat-${slug}` }, token).expect(201);
  const product = await http
    .post(
      '/admin/products',
      {
        categoryId: cat.body.id,
        nameAr: `منتج ${slug}`,
        nameEn: `Product ${slug}`,
        slug,
        status: 'ACTIVE',
        basePrice: price,
        variants: sizes.map((s) => ({ sku: `${slug}-${s}`.toUpperCase(), size: s })),
      },
      token,
    )
    .expect(201);
  return product.body.variants.map((v: { id: string }) => v.id) as string[];
}

/** A tiny PNG with random bytes after it, so each upload is a different file (slips are checked for reuse). */
export const uniquePng = () =>
  Buffer.concat([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64'), Buffer.from(Math.random().toString(36))]);
