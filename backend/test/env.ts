import 'dotenv/config';
import { testDatabaseUrl } from './test-db';

process.env.DATABASE_URL = testDatabaseUrl();
if ((process.env.JWT_ACCESS_SECRET ?? '').length < 32) {
  process.env.JWT_ACCESS_SECRET = 'test-secret-that-is-definitely-long-enough-123';
}
process.env.AUTH_RATE_LIMIT = '1000';
