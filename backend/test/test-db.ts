/** The e2e suite runs against `<database>_test` (or TEST_DATABASE_URL) so it never touches dev data. */
export function testDatabaseUrl(): string {
  if (process.env.TEST_DATABASE_URL) return process.env.TEST_DATABASE_URL;
  const base = process.env.DATABASE_URL;
  if (!base) throw new Error('Set DATABASE_URL or TEST_DATABASE_URL to run e2e tests');
  const url = new URL(base);
  const name = url.pathname.slice(1);
  url.pathname = `/${name.endsWith('_test') ? name : `${name}_test`}`;
  return url.toString();
}
