import { Controller, Get, Query } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { IsDateString, IsEnum, IsOptional, IsString, IsUUID, MaxLength } from 'class-validator';
import { AuditAction, Prisma } from '../generated/prisma/client';
import { RequirePermissions } from '../common/decorators/auth.decorators';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { parseDateOnly } from '../common/utils/dates';
import { PrismaService } from '../prisma/prisma.service';

class AuditQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ example: 'PosSale' })
  @IsOptional()
  @IsString()
  @MaxLength(60)
  entityType?: string;

  @ApiPropertyOptional()
  @IsOptional()
  @IsUUID()
  userId?: string;

  @ApiPropertyOptional({ enum: AuditAction })
  @IsOptional()
  @IsEnum(AuditAction)
  action?: AuditAction;

  @ApiPropertyOptional({ example: '2026-10-01' })
  @IsOptional()
  @IsDateString({ strict: true })
  from?: string;

  @ApiPropertyOptional({ example: '2026-10-31' })
  @IsOptional()
  @IsDateString({ strict: true })
  to?: string;
}

/** Who changed what and when (edits, deletions, logins…), newest first. */
@ApiTags('Admin · Audit')
@Controller('admin/audit-logs')
export class AuditController {
  constructor(private readonly prisma: PrismaService) {}

  @RequirePermissions('audit.read')
  @Get()
  async list(@Query() q: AuditQueryDto) {
    const where: Prisma.AuditLogWhereInput = {
      entityType: q.entityType,
      userId: q.userId,
      action: q.action,
      ...((q.from || q.to) && {
        createdAt: {
          ...(q.from && { gte: parseDateOnly(q.from) }),
          ...(q.to && { lt: new Date(parseDateOnly(q.to).getTime() + 24 * 3600_000) }),
        },
      }),
    };
    const [items, total] = await Promise.all([
      this.prisma.auditLog.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
        include: { user: { select: { id: true, name: true } } },
      }),
      this.prisma.auditLog.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  /** The kinds of records seen in the log, for the filter. */
  @RequirePermissions('audit.read')
  @Get('types')
  async types() {
    const rows = await this.prisma.auditLog.groupBy({ by: ['entityType'], _count: true, orderBy: { entityType: 'asc' } });
    return rows.map((r) => ({ entityType: r.entityType, count: r._count }));
  }
}
