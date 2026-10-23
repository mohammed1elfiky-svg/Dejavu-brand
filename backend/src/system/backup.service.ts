import { BadRequestException, ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { gunzipSync, gzipSync } from 'node:zlib';
import { PrismaService } from '../prisma/prisma.service';

const FORMAT = 'dejavu-backup';
const SKIP = new Set(['_prisma_migrations']);

interface BackupFile {
  format: string;
  version: number;
  createdAt: string;
  /** Last migration applied when the backup was taken; a restore needs the same schema. */
  migration: string | null;
  tables: Record<string, unknown[]>;
}

/**
 * Whole-database backups as one gzipped JSON file (every table, files included), and
 * restoring such a file over the current data. Rows go out with row_to_json and come
 * back with json_populate_recordset, so every column type round-trips as Postgres
 * itself prints and reads it.
 */
@Injectable()
export class BackupService {
  private readonly log = new Logger('Backup');

  constructor(private readonly prisma: PrismaService) {}

  async export(userId: string): Promise<{ fileName: string; data: Buffer }> {
    await this.assertAdmin(userId);
    const tables = await this.tables();
    const file: BackupFile = { format: FORMAT, version: 1, createdAt: new Date().toISOString(), migration: await this.migration(), tables: {} };
    for (const t of tables) {
      const [row] = await this.prisma.$queryRawUnsafe<{ rows: unknown[] }[]>(`SELECT COALESCE(json_agg(t), '[]'::json) AS rows FROM "${t}" t`);
      file.tables[t] = row.rows;
    }
    const stamp = file.createdAt.slice(0, 16).replace(/[:T]/g, '-');
    await this.prisma.auditLog.create({ data: { userId, action: 'CREATE', entityType: 'Backup', after: { tables: tables.length } } });
    return { fileName: `dejavu-backup-${stamp}.json.gz`, data: gzipSync(Buffer.from(JSON.stringify(file))) };
  }

  /** Replaces all data with the backup's. Everything since the backup is lost. */
  async restore(userId: string, gz: Buffer) {
    await this.assertAdmin(userId);
    let file: BackupFile;
    try {
      file = JSON.parse(gunzipSync(gz).toString('utf8')) as BackupFile;
    } catch {
      throw new BadRequestException('This is not a backup file');
    }
    if (file.format !== FORMAT || !file.tables) throw new BadRequestException('This is not a backup file');
    const migration = await this.migration();
    if (file.migration !== migration) {
      throw new BadRequestException(`The backup was taken on another version of the system (${file.migration ?? 'unknown'}); update the server first`);
    }
    const order = await this.insertOrder();
    const unknown = Object.keys(file.tables).filter((t) => !order.includes(t));
    if (unknown.length) throw new BadRequestException(`Unknown tables in the backup: ${unknown.join(', ')}`);

    const counts: Record<string, number> = {};
    await this.prisma.$transaction(
      async (tx) => {
        await tx.$executeRawUnsafe(`TRUNCATE TABLE ${order.map((t) => `"${t}"`).join(', ')} RESTART IDENTITY`);
        // The ledger's guard triggers (append-only, drafts first) would refuse rows copied back as they were.
        for (const t of order) await tx.$executeRawUnsafe(`ALTER TABLE "${t}" DISABLE TRIGGER USER`);
        for (const t of order) {
          const rows = file.tables[t] ?? [];
          counts[t] = rows.length;
          // In chunks, so one statement never carries a huge parameter.
          for (let i = 0; i < rows.length; i += 500) {
            await tx.$executeRawUnsafe(`INSERT INTO "${t}" SELECT * FROM json_populate_recordset(NULL::"${t}", $1::json)`, JSON.stringify(rows.slice(i, i + 500)));
          }
        }
        for (const t of order) await tx.$executeRawUnsafe(`ALTER TABLE "${t}" ENABLE TRIGGER USER`);
      },
      { timeout: 10 * 60_000, maxWait: 30_000 },
    );
    this.log.warn(`Database restored from the backup of ${file.createdAt} by ${userId}`);
    await this.prisma.auditLog.create({ data: { userId, action: 'UPDATE', entityType: 'BackupRestore', after: { createdAt: file.createdAt } } });
    return { restored: true, backupCreatedAt: file.createdAt, rows: counts };
  }

  private async assertAdmin(userId: string) {
    const isAdmin = await this.prisma.userRole.count({ where: { userId, role: { name: 'admin' } } });
    if (!isAdmin) throw new ForbiddenException('Only a system admin can back up or restore the data');
  }

  private async tables() {
    const rows = await this.prisma.$queryRaw<{ table_name: string }[]>`
      SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name`;
    return rows.map((r) => r.table_name).filter((t) => !SKIP.has(t));
  }

  /** Tables ordered so every table comes after the ones it references. */
  private async insertOrder() {
    const tables = await this.tables();
    const edges = await this.prisma.$queryRaw<{ child: string; parent: string }[]>`
      SELECT tc.table_name AS child, ccu.table_name AS parent
      FROM information_schema.table_constraints tc
      JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
      WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' AND tc.table_name <> ccu.table_name`;
    const parents = new Map(tables.map((t) => [t, new Set<string>()]));
    for (const e of edges) parents.get(e.child)?.add(e.parent);
    const done: string[] = [];
    const left = new Set(tables);
    while (left.size) {
      const ready = [...left].filter((t) => [...parents.get(t)!].every((p) => !left.has(p) || p === t));
      if (!ready.length) throw new Error(`Circular references between ${[...left].join(', ')}`);
      for (const t of ready.sort()) {
        done.push(t);
        left.delete(t);
      }
    }
    return done;
  }

  private async migration() {
    const rows = await this.prisma.$queryRaw<{ migration_name: string }[]>`
      SELECT migration_name FROM _prisma_migrations WHERE finished_at IS NOT NULL ORDER BY migration_name DESC LIMIT 1`;
    return rows[0]?.migration_name ?? null;
  }
}
