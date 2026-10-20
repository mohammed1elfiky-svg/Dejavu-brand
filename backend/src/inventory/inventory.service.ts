import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { Prisma, StockMovementType } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { money, sum } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import {
  AdjustmentReason,
  CreateWarehouseDto,
  INCREASE_REASONS,
  LowStockQueryDto,
  MovementsQueryDto,
  StockAdjustmentDto,
  StockLevelsQueryDto,
  StockTransferDto,
} from './dto/inventory.dto';
import { StockService } from './stock.service';

const variantSummary = {
  select: {
    id: true,
    sku: true,
    size: true,
    color: true,
    avgCost: true,
    lowStockThreshold: true,
    product: { select: { id: true, nameAr: true, nameEn: true } },
  },
} satisfies Prisma.ProductVariantDefaultArgs;

@Injectable()
export class InventoryService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  // ----------------------------------------------------------- warehouses ---

  listWarehouses() {
    return this.prisma.warehouse.findMany({ orderBy: { code: 'asc' } });
  }

  async createWarehouse(dto: CreateWarehouseDto, userId: string) {
    const wh = await this.prisma.warehouse.create({ data: dto });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Warehouse', entityId: wh.id, after: wh });
    return wh;
  }

  // --------------------------------------------------------------- queries ---

  async levels(q: StockLevelsQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const where: Prisma.StockLevelWhereInput = {
      warehouseId: q.warehouseId,
      variantId: q.variantId,
      ...(contains && {
        variant: { OR: [{ sku: contains }, { product: { OR: [{ nameAr: contains }, { nameEn: contains }] } }] },
      }),
    };
    const [rows, total] = await Promise.all([
      this.prisma.stockLevel.findMany({
        where,
        include: { variant: variantSummary, warehouse: { select: { id: true, code: true, name: true } } },
        orderBy: [{ variant: { sku: 'asc' } }, { warehouse: { code: 'asc' } }],
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.stockLevel.count({ where }),
    ]);
    const items = rows.map((r) => ({
      ...r,
      available: r.onHand - r.reserved,
      stockValue: money(r.variant.avgCost.mul(r.onHand)),
    }));
    return paginated(items, total, q);
  }

  async lowStock(q: LowStockQueryDto) {
    return this.prisma.$queryRaw<
      { variant_id: string; sku: string; name_ar: string; name_en: string; on_hand: number; reserved: number; available: number; threshold: number }[]
    >`
      SELECT a.variant_id, a.sku, p.name_ar, p.name_en,
             a.on_hand::int, a.reserved::int, a.available::int, v.low_stock_threshold AS threshold
      FROM v_variant_availability a
      JOIN product_variants v ON v.id = a.variant_id
      JOIN products p ON p.id = v.product_id
      WHERE v.is_active AND p.status = 'ACTIVE' AND a.is_low_stock
        AND (${q.includeOutOfStock} OR a.on_hand > 0)
      ORDER BY a.available ASC, a.sku`;
  }

  async movements(q: MovementsQueryDto) {
    const where: Prisma.StockMovementWhereInput = {
      warehouseId: q.warehouseId,
      variantId: q.variantId,
      type: q.type,
      sourceId: q.sourceId,
    };
    const [items, total] = await Promise.all([
      this.prisma.stockMovement.findMany({
        where,
        include: {
          variant: { select: { sku: true, product: { select: { nameAr: true, nameEn: true } } } },
          warehouse: { select: { code: true } },
          createdBy: { select: { id: true, name: true } },
        },
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.stockMovement.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  // ------------------------------------------------------------- commands ---

  /** Stock adjustment + its journal entry, in one transaction. */
  async adjust(dto: StockAdjustmentDto, userId: string) {
    const increase = INCREASE_REASONS.includes(dto.reason);
    if (dto.reason === AdjustmentReason.OPENING && dto.items.some((i) => i.unitCost === undefined)) {
      throw new BadRequestException('unitCost is required for opening stock');
    }
    assertUniqueVariants(dto.items);
    await this.assertWarehouse(dto.warehouseId);

    return this.prisma.$transaction(async (tx) => {
      const sourceId = randomUUID();
      const number = await this.sequences.next(tx, 'ADJ');
      const note = [number, dto.reason, dto.note].filter(Boolean).join(' · ');
      const ctx = { sourceType: 'STOCK_ADJUSTMENT', sourceId, note, userId };
      const values = [];

      for (const item of [...dto.items].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        if (increase) {
          const unitCost =
            item.unitCost ??
            (await tx.productVariant.findUniqueOrThrow({ where: { id: item.variantId }, select: { avgCost: true } })).avgCost;
          values.push(
            (
              await this.stock.stockIn(tx, {
                ...ctx,
                type: StockMovementType.ADJUSTMENT_IN,
                variantId: item.variantId,
                warehouseId: dto.warehouseId,
                quantity: item.quantity,
                unitCost,
              })
            ).value,
          );
        } else {
          values.push(
            (
              await this.stock.stockOut(tx, {
                ...ctx,
                type: StockMovementType.ADJUSTMENT_OUT,
                variantId: item.variantId,
                warehouseId: dto.warehouseId,
                quantity: item.quantity,
              })
            ).value,
          );
        }
      }

      const total = sum(values);
      let journalEntryId: string | null = null;
      if (!total.isZero()) {
        const counter = dto.reason === AdjustmentReason.OPENING ? 'OPENING_BALANCE' : 'INVENTORY_ADJUSTMENT';
        const entry = await this.ledger.post(tx, {
          description: `Stock adjustment ${number} (${dto.reason})`,
          sourceType: dto.reason === AdjustmentReason.OPENING ? 'OPENING_BALANCE' : 'STOCK_ADJUSTMENT',
          sourceId,
          userId,
          lines: increase
            ? [{ account: 'INVENTORY', debit: total }, { account: counter, credit: total }]
            : [{ account: counter, debit: total }, { account: 'INVENTORY', credit: total }],
        });
        journalEntryId = entry.id;
      }
      await this.audit.log({ userId, action: 'CREATE', entityType: 'StockAdjustment', entityId: sourceId, after: dto }, tx);
      return { number, sourceId, totalValue: total, journalEntryId };
    });
  }

  /** Moves stock between warehouses at average cost; value is unchanged so no journal entry. */
  async transfer(dto: StockTransferDto, userId: string) {
    if (dto.fromWarehouseId === dto.toWarehouseId) throw new BadRequestException('Choose two different warehouses');
    assertUniqueVariants(dto.items);
    await Promise.all([this.assertWarehouse(dto.fromWarehouseId), this.assertWarehouse(dto.toWarehouseId)]);

    return this.prisma.$transaction(async (tx) => {
      const sourceId = randomUUID();
      const number = await this.sequences.next(tx, 'TRF');
      const note = [number, dto.note].filter(Boolean).join(' · ');
      const ctx = { sourceType: 'STOCK_TRANSFER', sourceId, note, userId };

      for (const item of [...dto.items].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        const out = await this.stock.stockOut(tx, {
          ...ctx,
          type: StockMovementType.TRANSFER_OUT,
          variantId: item.variantId,
          warehouseId: dto.fromWarehouseId,
          quantity: item.quantity,
        });
        await this.stock.stockIn(tx, {
          ...ctx,
          type: StockMovementType.TRANSFER_IN,
          variantId: item.variantId,
          warehouseId: dto.toWarehouseId,
          quantity: item.quantity,
          unitCost: out.unitCost,
        });
      }
      await this.audit.log({ userId, action: 'CREATE', entityType: 'StockTransfer', entityId: sourceId, after: dto }, tx);
      return { number, sourceId };
    });
  }

  private async assertWarehouse(id: string) {
    const wh = await this.prisma.warehouse.findUnique({ where: { id } });
    if (!wh || !wh.isActive) throw new NotFoundException('Warehouse not found or inactive');
  }
}

function assertUniqueVariants(items: { variantId: string }[]) {
  if (new Set(items.map((i) => i.variantId)).size !== items.length) {
    throw new BadRequestException('Each variant may appear only once per document');
  }
}
