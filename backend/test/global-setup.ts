import 'dotenv/config';
import { execSync } from 'node:child_process';
import { Client } from 'pg';
import { testDatabaseUrl } from './test-db';

/** Recreates the test database from migrations + seed before the e2e run. */
export default async function globalSetup() {
  const url = new URL(testDatabaseUrl());
  const dbName = url.pathname.slice(1);
  if (!dbName.endsWith('_test')) throw new Error(`Refusing to reset non-test database "${dbName}"`);

  const admin = new URL(url);
  admin.pathname = '/postgres';
  admin.search = '';
  const client = new Client({ connectionString: admin.toString() });
  await client.connect();
  await client.query(`DROP DATABASE IF EXISTS "${dbName}" WITH (FORCE)`);
  await client.query(`CREATE DATABASE "${dbName}"`);
  await client.end();

  const env = { ...process.env, DATABASE_URL: url.toString() };
  execSync('npx prisma migrate deploy', { env, stdio: 'pipe' });
  execSync('npx prisma db seed', { env, stdio: 'pipe' });
  // The store charges no VAT, but the tax engine is still covered by the older suites:
  // give the test database the 14% default rate they were written against.
  const db = new Client({ connectionString: url.toString().replace(/\?schema=.*$/, '') });
  await db.connect();
  await db.query(`
    INSERT INTO tax_rates (id, name, rate, is_default, is_active, account_id, created_at)
    SELECT gen_random_uuid(), 'VAT 14%', 14, true, true, id, now() FROM accounts WHERE code = '2102'`);
  // The store leaves shipping to the courier; the older suites cover charging it in the order.
  await db.query(`INSERT INTO settings (key, value, updated_at) VALUES ('store.chargeShipping', 'true', now())`);
  await db.end();
}
