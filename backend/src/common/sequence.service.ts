import { Injectable } from '@nestjs/common';
import { Tx } from '../prisma/prisma.service';

/**
 * Gap-free document numbers (SO-2026-000123). The row lock taken by the upsert
 * is held until the surrounding transaction commits, so numbers never repeat
 * and a rolled-back transaction does not burn a number.
 */
@Injectable()
export class SequenceService {
  async next(tx: Tx, prefix: string, opts: { year?: number; pad?: number } = {}): Promise<string> {
    const year = opts.year ?? new Date().getUTCFullYear();
    const rows = await tx.$queryRaw<{ last_no: number }[]>`
      INSERT INTO document_sequences (prefix, year, last_no) VALUES (${prefix}, ${year}, 1)
      ON CONFLICT (prefix, year) DO UPDATE SET last_no = document_sequences.last_no + 1
      RETURNING last_no`;
    const no = String(rows[0].last_no).padStart(opts.pad ?? 6, '0');
    return year === 0 ? `${prefix}-${no}` : `${prefix}-${year}-${no}`;
  }
}
