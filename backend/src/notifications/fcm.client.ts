import { createSign } from 'node:crypto';
import { Logger } from '@nestjs/common';

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export interface PushMessage {
  token: string;
  title: string;
  body: string;
  data?: Record<string, string>;
  /** Android notification channel (created by the app). */
  channel?: string;
  /** Public https image shown expanded in the notification. */
  image?: string;
}

/** Outcome per token: sent, or the token is dead and should be forgotten. */
export type PushOutcome = 'sent' | 'unregistered' | 'failed';

/** Parses FCM_SERVICE_ACCOUNT given as raw JSON or base64-encoded JSON. */
export function parseServiceAccount(raw: string): ServiceAccount {
  const text = raw.trim().startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8');
  const sa = JSON.parse(text) as ServiceAccount;
  if (!sa.project_id || !sa.client_email || !sa.private_key) {
    throw new Error('FCM_SERVICE_ACCOUNT must be a Firebase service-account key (project_id, client_email, private_key)');
  }
  return sa;
}

const b64url = (v: string | Buffer) => Buffer.from(v).toString('base64url');

/** Minimal Firebase Cloud Messaging HTTP v1 client (OAuth via the service-account key). */
export class FcmClient {
  private readonly log = new Logger('FCM');
  private token?: { value: string; expiresAt: number };

  constructor(private readonly sa: ServiceAccount) {}

  async send(msg: PushMessage): Promise<PushOutcome> {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${this.sa.project_id}/messages:send`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${await this.accessToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: {
          token: msg.token,
          notification: { title: msg.title, body: msg.body, ...(msg.image?.startsWith('https://') ? { image: msg.image } : {}) },
          data: msg.data ?? {},
          android: { priority: 'high', notification: { channel_id: msg.channel ?? 'orders' } },
        },
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (res.ok) return 'sent';
    const text = await res.text();
    if (res.status === 404 || text.includes('UNREGISTERED') || (res.status === 400 && text.includes('registration token'))) {
      return 'unregistered';
    }
    if (res.status === 401) this.token = undefined;
    this.log.warn(`Send failed (${res.status}): ${text.slice(0, 300)}`);
    return 'failed';
  }

  private async accessToken(): Promise<string> {
    if (this.token && this.token.expiresAt > Date.now() + 60_000) return this.token.value;
    const now = Math.floor(Date.now() / 1000);
    const tokenUri = this.sa.token_uri ?? 'https://oauth2.googleapis.com/token';
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claims = b64url(
      JSON.stringify({
        iss: this.sa.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging',
        aud: tokenUri,
        iat: now,
        exp: now + 3600,
      }),
    );
    const signature = createSign('RSA-SHA256').update(`${header}.${claims}`).sign(this.sa.private_key);
    const res = await fetch(tokenUri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${header}.${claims}.${b64url(signature)}`,
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`Google OAuth failed (${res.status}): ${(await res.text()).slice(0, 200)}`);
    const json = (await res.json()) as { access_token: string; expires_in: number };
    this.token = { value: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 };
    return json.access_token;
  }
}
