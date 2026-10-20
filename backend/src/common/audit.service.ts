import { Injectable } from '@nestjs/common';
import { AuditAction, Prisma } from '../generated/prisma/client';
import { PrismaService, Tx } from '../prisma/prisma.service';

export interface AuditEntry {
  userId?: string | null;
  action: AuditAction;
  entityType: string;
  entityId?: string | null;
  before?: unknown;
  after?: unknown;
}

@Injectable()
export class AuditService {
  constructor(private readonly prisma: PrismaService) {}

  async log(entry: AuditEntry, tx: Tx = this.prisma): Promise<void> {
    await tx.auditLog.create({
      data: {
        userId: entry.userId ?? null,
        action: entry.action,
        entityType: entry.entityType,
        entityId: entry.entityId ?? null,
        before: toJson(entry.before),
        after: toJson(entry.after),
      },
    });
  }
}

const toJson = (v: unknown) =>
  v === undefined ? Prisma.DbNull : (JSON.parse(JSON.stringify(v)) as Prisma.InputJsonValue);
