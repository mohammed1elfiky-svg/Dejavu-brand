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
