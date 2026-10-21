import type { Request } from 'express';

/** Public origin the client used to reach us (honours a reverse proxy's forwarded headers). */
export function requestBaseUrl(req: Request): string | undefined {
  const host = (req.headers['x-forwarded-host'] as string | undefined)?.split(',')[0] ?? req.headers.host;
  const proto = (req.headers['x-forwarded-proto'] as string | undefined)?.split(',')[0] ?? req.protocol;
  return host ? `${proto}://${host}` : undefined;
}
