import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { ShipmentStatus } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { PaginationQueryDto, paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { dec, money, sum } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { CourierSettlementDto, PendingCodQueryDto } from './dto/sales.dto';

/**
 * Couriers collect cash on delivery and pay it over later, minus their fees:
 *   Dr Bank (net) + Dr Shipping expense (fees)  —  Cr COD receivable (collected)
 */
@Injectable()
export class CourierSettlementsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  /** Delivered COD shipments whose cash the courier still holds, grouped by carrier. */
  async pending(q: PendingCodQueryDto) {
    const shipments = await this.prisma.shipment.findMany({
      where: { status: ShipmentStatus.DELIVERED, settlementId: null, codAmount: { gt: 0 }, carrier: q.carrier },
      include: { order: { select: { id: true, number: true, customer: { select: { name: true } } } } },
      orderBy: { deliveredAt: 'asc' },
    });
    const carriers = [...new Set(shipments.map((s) => s.carrier))].map((carrier) => {
      const own = shipments.filter((s) => s.carrier === carrier);
      return { carrier, shipments: own.length, collected: money(sum(own.map((s) => s.codAmount))) };
    });
    return { carriers, shipments };
  }

  async list(q: PaginationQueryDto) {
    const [items, total] = await Promise.all([
      this.prisma.courierSettlement.findMany({
        include: { account: { select: { code: true, nameAr: true } }, _count: { select: { shipments: true } } },
        orderBy: { settledAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.courierSettlement.count(),
    ]);
    return paginated(items, total, q);
  }

  async get(id: string) {
    const s = await this.prisma.courierSettlement.findUnique({
      where: { id },
      include: {
        account: { select: { code: true, nameAr: true } },
        createdBy: { select: { name: true } },
        shipments: { include: { order: { select: { number: true } } } },
      },
    });
    if (!s) throw new NotFoundException('Settlement not found');
    return s;
  }

  async settle(dto: CourierSettlementDto, userId: string) {
    const ids = [...new Set(dto.shipmentIds)];
    const id = await this.prisma.$transaction(async (tx) => {
      const account = await tx.account.findUnique({ where: { id: dto.accountId } });
      if (!account || !account.isActive || !account.isPostable || !account.isCashOrBank) {
        throw new BadRequestException('Choose an active cash, bank or wallet account');
      }
      // Lock the shipments so the same cash cannot be settled twice.
      const locked = await tx.$queryRaw<{ id: string }[]>`
        SELECT id FROM shipments WHERE id = ANY(${ids}::uuid[]) ORDER BY id FOR UPDATE`;
      if (locked.length !== ids.length) throw new NotFoundException('One or more shipments not found');
      const shipments = await tx.shipment.findMany({ where: { id: { in: ids } }, include: { order: { select: { number: true } } } });
      for (const s of shipments) {
        if (s.carrier !== dto.carrier) throw new BadRequestException(`${s.order.number} was shipped with ${s.carrier}`);
        if (s.status !== ShipmentStatus.DELIVERED) throw new UnprocessableEntityException(`${s.order.number} is not delivered yet`);
        if (s.settlementId) throw new UnprocessableEntityException(`${s.order.number} was already settled`);
        if (!dec(s.codAmount).gt(0)) throw new UnprocessableEntityException(`${s.order.number} has no cash to collect`);
      }

      const collected = money(sum(shipments.map((s) => s.codAmount)));
      const fees = money(dto.feesAmount ?? 0);
      if (fees.greaterThan(collected)) throw new BadRequestException('Fees cannot exceed the collected amount');
      const net = collected.sub(fees);

      const settlement = await tx.courierSettlement.create({
        data: {
          number: await this.sequences.next(tx, 'CST'),
          carrier: dto.carrier,
          collectedAmount: collected,
          feesAmount: fees,
          netAmount: net,
          accountId: account.id,
          reference: dto.reference,
          createdById: userId,
        },
      });
      await tx.shipment.updateMany({ where: { id: { in: ids } }, data: { settlementId: settlement.id } });
      await this.ledger.post(tx, {
        description: `COD settlement ${settlement.number} from ${dto.carrier} (${shipments.length} shipments)`,
        sourceType: 'COURIER_SETTLEMENT',
        sourceId: settlement.id,
        userId,
        lines: [
          { account: { id: account.id }, debit: net },
          { account: 'SHIPPING_EXPENSE', debit: fees },
          { account: 'COD_RECEIVABLE', credit: collected },
        ],
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'CourierSettlement', entityId: settlement.id, after: dto }, tx);
      return settlement.id;
    });
    return this.get(id);
  }
}
