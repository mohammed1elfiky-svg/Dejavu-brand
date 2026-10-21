/** Hosts that announce their public URL (Render, Codespaces); elsewhere assume localhost. */
function defaultPublicUrl(raw: Record<string, unknown>) {
  if (typeof raw.RENDER_EXTERNAL_URL === 'string' && raw.RENDER_EXTERNAL_URL) return raw.RENDER_EXTERNAL_URL;
  const port = raw.PORT ?? 3000;
  if (raw.CODESPACE_NAME && raw.GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN) {
    return `https://${raw.CODESPACE_NAME}-${port}.${raw.GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}`;
  }
  return `http://localhost:${port}`;
}

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
    /** Address the outside world (browsers, Paymob) uses to reach this server, without /api/v1. */
    PUBLIC_BASE_URL: str('PUBLIC_BASE_URL', defaultPublicUrl(raw)).replace(/\/+$/, ''),
    /** test = built-in fake card page (development), paymob = real payments, none = card payments off. */
    PAYMENT_GATEWAY: str('PAYMENT_GATEWAY', raw.NODE_ENV === 'production' ? 'none' : 'test'),
    PAYMOB_API_URL: str('PAYMOB_API_URL', 'https://accept.paymob.com').replace(/\/+$/, ''),
    PAYMOB_SECRET_KEY: str('PAYMOB_SECRET_KEY', ''),
    PAYMOB_PUBLIC_KEY: str('PAYMOB_PUBLIC_KEY', ''),
    PAYMOB_INTEGRATION_ID: str('PAYMOB_INTEGRATION_ID', ''),
    PAYMOB_HMAC_SECRET: str('PAYMOB_HMAC_SECRET', ''),
    /** Currency of the Paymob account; card payments are charged in it (the base SAR amount). */
    PAYMOB_CURRENCY: str('PAYMOB_CURRENCY', 'SAR'),
    /** Unpaid card orders are cancelled (and their stock released) after this many minutes. 0 = never. */
    /** Firebase service-account key (JSON or base64 JSON). Empty = in-app notifications only, no push. */
    FCM_SERVICE_ACCOUNT: str('FCM_SERVICE_ACCOUNT', ''),
    CARD_PAYMENT_TIMEOUT_MINUTES: raw.CARD_PAYMENT_TIMEOUT_MINUTES === '0' ? 0 : int('CARD_PAYMENT_TIMEOUT_MINUTES', 60),
  };

  if (!['test', 'paymob', 'none'].includes(env.PAYMENT_GATEWAY)) {
    errors.push('PAYMENT_GATEWAY must be one of: test, paymob, none');
  }
  if (env.PAYMENT_GATEWAY === 'paymob') {
    for (const key of ['PAYMOB_SECRET_KEY', 'PAYMOB_PUBLIC_KEY', 'PAYMOB_INTEGRATION_ID', 'PAYMOB_HMAC_SECRET'] as const) {
      if (!env[key]) errors.push(`${key} is required when PAYMENT_GATEWAY=paymob`);
    }
    if (!env.PUBLIC_BASE_URL.startsWith('https://')) errors.push('PUBLIC_BASE_URL must be https:// for Paymob callbacks');
  }
  if (raw.NODE_ENV === 'production' && env.PAYMENT_GATEWAY === 'test') {
    errors.push('PAYMENT_GATEWAY=test is not allowed in production');
  }

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
