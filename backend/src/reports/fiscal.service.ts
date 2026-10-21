import { BadRequestException, ConflictException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { FiscalPeriodStatus } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { PrismaService } from '../prisma/prisma.service';

/**
 * Monthly periods: OPEN → CLOSED (reversible) → LOCKED (permanent).
 * Posting into a non-open period is rejected by LedgerService and by the
 * database trigger.
 */
@Injectable()
export class FiscalService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  list() {
    return this.prisma.fiscalYear.findMany({
      orderBy: { startDate: 'desc' },
      include: {
        periods: {
          orderBy: { startDate: 'asc' },
          include: { closedBy: { select: { name: true } }, _count: { select: { journalEntries: true } } },
        },
      },
    });
  }

  async createYear(year: number, userId: string) {
    const start = new Date(Date.UTC(year, 0, 1));
    const end = new Date(Date.UTC(year, 11, 31));
    const overlap = await this.prisma.fiscalYear.findFirst({ where: { startDate: { lte: end }, endDate: { gte: start } } });
    if (overlap) throw new ConflictException(`Fiscal year ${overlap.name} already covers ${year}`);
    const fy = await this.prisma.fiscalYear.create({
      data: {
        name: `FY${year}`,
        startDate: start,
        endDate: end,
        periods: {
          create: Array.from({ length: 12 }, (_, m) => ({
            name: `${year}-${String(m + 1).padStart(2, '0')}`,
            startDate: new Date(Date.UTC(year, m, 1)),
            endDate: new Date(Date.UTC(year, m + 1, 0)),
          })),
        },
      },
      include: { periods: { orderBy: { startDate: 'asc' } } },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'FiscalYear', entityId: fy.id, after: { year } });
    return fy;
  }

  async close(id: string, userId: string) {
    const period = await this.find(id);
    if (period.status !== FiscalPeriodStatus.OPEN) throw new BadRequestException(`Period ${period.name} is already ${period.status.toLowerCase()}`);
    const drafts = await this.prisma.journalEntry.findMany({
      where: { periodId: id, status: 'DRAFT' },
      select: { number: true },
      take: 10,
    });
    if (drafts.length) {
      throw new UnprocessableEntityException(`Post or delete the draft entries first: ${drafts.map((d) => d.number).join(', ')}`);
    }
    return this.setStatus(id, FiscalPeriodStatus.CLOSED, userId, { closedById: userId, closedAt: new Date() });
  }

  async reopen(id: string, userId: string) {
    const period = await this.find(id);
    if (period.status === FiscalPeriodStatus.LOCKED) throw new UnprocessableEntityException(`Period ${period.name} is locked permanently`);
    if (period.status === FiscalPeriodStatus.OPEN) throw new BadRequestException(`Period ${period.name} is already open`);
    return this.setStatus(id, FiscalPeriodStatus.OPEN, userId, { closedById: null, closedAt: null });
  }

  /** Permanent: for periods whose figures were filed or audited. */
  async lock(id: string, userId: string) {
    const period = await this.find(id);
    if (period.status !== FiscalPeriodStatus.CLOSED) throw new BadRequestException('Close the period before locking it');
    return this.setStatus(id, FiscalPeriodStatus.LOCKED, userId, {});
  }

  private async find(id: string) {
    const period = await this.prisma.fiscalPeriod.findUnique({ where: { id } });
    if (!period) throw new NotFoundException('Fiscal period not found');
    return period;
  }

  private async setStatus(id: string, status: FiscalPeriodStatus, userId: string, extra: { closedById?: string | null; closedAt?: Date | null }) {
    const period = await this.prisma.fiscalPeriod.update({ where: { id }, data: { status, ...extra } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'FiscalPeriod', entityId: id, after: { status } });
    return period;
  }
}
