import { Tx } from '../prisma/prisma.service';

export async function getSetting<T>(tx: Tx, key: string, fallback: T): Promise<T> {
  const row = await tx.setting.findUnique({ where: { key } });
  return row ? (row.value as T) : fallback;
}
