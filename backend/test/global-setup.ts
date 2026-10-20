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
}
