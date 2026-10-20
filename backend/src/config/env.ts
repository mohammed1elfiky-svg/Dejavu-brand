/** Validates environment variables at startup so misconfiguration fails fast. */
export function validateEnv(raw: Record<string, unknown>) {
  const errors: string[] = [];
  const str = (key: string, fallback?: string) => {
    const v = raw[key];
    if (typeof v === 'string' && v.length > 0) return v;
    if (fallback !== undefined) return fallback;
    errors.push(`${key} is required`);
    return '';
  };
  const int = (key: string, fallback: number) => {
    const v = raw[key];
    if (v === undefined || v === '') return fallback;
    const n = Number(v);
    if (!Number.isInteger(n) || n <= 0) errors.push(`${key} must be a positive integer`);
    return n;
  };

  const env = {
    DATABASE_URL: str('DATABASE_URL'),
    PORT: int('PORT', 3000),
    JWT_ACCESS_SECRET: str('JWT_ACCESS_SECRET'),
    JWT_ACCESS_TTL: str('JWT_ACCESS_TTL', '15m'),
    JWT_REFRESH_TTL_DAYS: int('JWT_REFRESH_TTL_DAYS', 30),
    AUTH_RATE_LIMIT: int('AUTH_RATE_LIMIT', 10),
    STORE_TIMEZONE: str('STORE_TIMEZONE', 'Africa/Cairo'),
  };

  if (env.JWT_ACCESS_SECRET && env.JWT_ACCESS_SECRET.length < 32) {
    errors.push('JWT_ACCESS_SECRET must be at least 32 characters');
  }
  if (raw.NODE_ENV === 'production' && env.JWT_ACCESS_SECRET.startsWith('change-me')) {
    errors.push('JWT_ACCESS_SECRET still has the example value');
  }
  if (errors.length) throw new Error(`Invalid environment:\n  - ${errors.join('\n  - ')}`);
  return env;
}

export type Env = ReturnType<typeof validateEnv>;
