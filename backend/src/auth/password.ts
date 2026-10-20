import { randomBytes, scrypt as scryptCb, scryptSync, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb) as (pw: string, salt: string, len: number) => Promise<Buffer>;
const KEY_LEN = 64;

/** Format: scrypt$<salt-hex>$<hash-hex> */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16).toString('hex');
  const hash = await scrypt(password, salt, KEY_LEN);
  return `scrypt$${salt}$${hash.toString('hex')}`;
}

export function hashPasswordSync(password: string): string {
  const salt = randomBytes(16).toString('hex');
  return `scrypt$${salt}$${scryptSync(password, salt, KEY_LEN).toString('hex')}`;
}

export async function verifyPassword(password: string, stored: string | null | undefined): Promise<boolean> {
  const [algo, salt, hex] = (stored ?? '').split('$');
  if (algo !== 'scrypt' || !salt || !hex) {
    // Burn comparable time so unknown accounts are not distinguishable by latency.
    await scrypt(password, 'timing-pad', KEY_LEN);
    return false;
  }
  const expected = Buffer.from(hex, 'hex');
  const actual = await scrypt(password, salt, expected.length);
  return timingSafeEqual(actual, expected);
}
