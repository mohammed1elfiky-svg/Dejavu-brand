import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { Prisma, StockMovementType } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { DecimalLike, money, sum } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import {
  AdjustmentReason,
  CreateStockCountDto,
  CreateWarehouseDto,
  INCREASE_REASONS,
  LowStockQueryDto,
  MovementsQueryDto,
  StockAdjustmentDto,
  StockCountsQueryDto,
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
    return this.prisma.$transaction((tx) => this.adjustIn(tx, dto, userId));
  }

  /** [adjust] inside a caller's transaction (e.g. opening stock of a new product). */
  async adjustIn(tx: Tx, dto: StockAdjustmentDto, userId: string) {
    const increase = INCREASE_REASONS.includes(dto.reason);
    {
      const sourceId = randomUUID();
      const number = await this.sequences.next(tx, 'ADJ');
      const opening = dto.reason === AdjustmentReason.OPENING;
      const result = await this.applyAdjustment(tx, {
        warehouseId: dto.warehouseId,
        increases: increase ? dto.items : [],
        decreases: increase ? [] : dto.items,
        sourceType: opening ? 'OPENING_BALANCE' : 'STOCK_ADJUSTMENT',
        sourceId,
        description: `Stock adjustment ${number} (${dto.reason})`,
        note: [number, dto.reason, dto.note].filter(Boolean).join(' · '),
        counterAccount: opening ? 'OPENING_BALANCE' : 'INVENTORY_ADJUSTMENT',
        userId,
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'StockAdjustment', entityId: sourceId, after: dto }, tx);
      return { number, sourceId, totalValue: result.increaseValue.add(result.decreaseValue), journalEntryId: result.journalEntryId };
    }
  }

  /**
   * Books stock increases (at the given or current average cost) and decreases
   * (at average cost) plus one journal entry against `counterAccount`.
   * Shared by manual adjustments and stock counts.
   */
  async applyAdjustment(
    tx: Tx,
    p: {
      warehouseId: string;
      increases: { variantId: string; quantity: number; unitCost?: DecimalLike }[];
      decreases: { variantId: string; quantity: number }[];
      sourceType: 'STOCK_ADJUSTMENT' | 'OPENING_BALANCE';
      sourceId: string;
      description: string;
      note: string;
      counterAccount: 'OPENING_BALANCE' | 'INVENTORY_ADJUSTMENT';
      userId: string;
    },
  ) {
    const ctx = { sourceType: 'STOCK_ADJUSTMENT', sourceId: p.sourceId, note: p.note, userId: p.userId };
    const byVariant = <T extends { variantId: string }>(a: T, b: T) => a.variantId.localeCompare(b.variantId);
    const inValues = [];
    const outValues = [];

    for (const item of [...p.increases].sort(byVariant)) {
      const unitCost =
        item.unitCost ??
        (await tx.productVariant.findUniqueOrThrow({ where: { id: item.variantId }, select: { avgCost: true } })).avgCost;
      const r = await this.stock.stockIn(tx, {
        ...ctx,
        type: StockMovementType.ADJUSTMENT_IN,
        variantId: item.variantId,
        warehouseId: p.warehouseId,
        quantity: item.quantity,
        unitCost,
      });
      inValues.push(r.value);
    }
    for (const item of [...p.decreases].sort(byVariant)) {
      const r = await this.stock.stockOut(tx, {
        ...ctx,
        type: StockMovementType.ADJUSTMENT_OUT,
        variantId: item.variantId,
        warehouseId: p.warehouseId,
        quantity: item.quantity,
      });
      outValues.push(r.value);
    }

    const increaseValue = sum(inValues);
    const decreaseValue = sum(outValues);
    let journalEntryId: string | null = null;
    if (!increaseValue.isZero() || !decreaseValue.isZero()) {
      const entry = await this.ledger.post(tx, {
        description: p.description,
        sourceType: p.sourceType,
        sourceId: p.sourceId,
        userId: p.userId,
        lines: [
          { account: 'INVENTORY', debit: increaseValue },
          { account: p.counterAccount, credit: increaseValue },
          { account: p.counterAccount, debit: decreaseValue },
          { account: 'INVENTORY', credit: decreaseValue },
        ],
      });
      journalEntryId = entry.id;
    }
    return { increaseValue, decreaseValue, journalEntryId };
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

  // --------------------------------------------------------- stock counts ---

  async createCount(dto: CreateStockCountDto, userId: string) {
    assertUniqueVariants(dto.items);
    await this.assertWarehouse(dto.warehouseId);
    const id = await this.prisma.$transaction(async (tx) => {
      const levels = await tx.stockLevel.findMany({
        where: { warehouseId: dto.warehouseId, variantId: { in: dto.items.map((i) => i.variantId) } },
      });
      const count = await tx.stockCount.create({
        data: {
          number: await this.sequences.next(tx, 'SC'),
          warehouseId: dto.warehouseId,
          countedById: userId,
          note: dto.note,
          items: {
            create: dto.items.map((i) => ({
              variantId: i.variantId,
              countedQty: i.countedQty,
              systemQty: levels.find((l) => l.variantId === i.variantId)?.onHand ?? 0,
            })),
          },
        },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'StockCount', entityId: count.id, after: dto }, tx);
      return count.id;
    });
    return this.getCount(id);
  }

  async listCounts(q: StockCountsQueryDto) {
    const where: Prisma.StockCountWhereInput = { warehouseId: q.warehouseId, isPosted: q.posted };
    const [items, total] = await Promise.all([
      this.prisma.stockCount.findMany({
        where,
        include: { warehouse: { select: { code: true, name: true } }, countedBy: { select: { name: true } }, _count: { select: { items: true } } },
        orderBy: { countedAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.stockCount.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async getCount(id: string) {
    const count = await this.prisma.stockCount.findUnique({
      where: { id },
      include: {
        warehouse: { select: { id: true, code: true, name: true } },
        countedBy: { select: { id: true, name: true } },
        items: { include: { variant: variantSummary }, orderBy: { variant: { sku: 'asc' } } },
      },
    });
    if (!count) throw new NotFoundException('Stock count not found');
    return {
      ...count,
      items: count.items.map((i) => ({
        ...i,
        difference: i.countedQty - i.systemQty,
        differenceValue: money(i.variant.avgCost.mul(i.countedQty - i.systemQty)),
      })),
    };
  }

  /**
   * Books the differences between counted and on-hand quantities. On-hand is
   * re-read at posting time, so movements made after the count was entered
   * are taken into account.
   */
  async postCount(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const locked = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM stock_counts WHERE id = ${id}::uuid FOR UPDATE`;
      if (!locked.length) throw new NotFoundException('Stock count not found');
      const count = await tx.stockCount.findUniqueOrThrow({ where: { id }, include: { items: true } });
      if (count.isPosted) throw new BadRequestException('Stock count is already posted');

      const levels = await tx.stockLevel.findMany({
        where: { warehouseId: count.warehouseId, variantId: { in: count.items.map((i) => i.variantId) } },
      });
      const increases = [];
      const decreases = [];
      for (const item of count.items) {
        const onHand = levels.find((l) => l.variantId === item.variantId)?.onHand ?? 0;
        await tx.stockCountItem.update({ where: { id: item.id }, data: { systemQty: onHand } });
        const diff = item.countedQty - onHand;
        if (diff > 0) increases.push({ variantId: item.variantId, quantity: diff });
        if (diff < 0) decreases.push({ variantId: item.variantId, quantity: -diff });
      }

      await this.applyAdjustment(tx, {
        warehouseId: count.warehouseId,
        increases,
        decreases,
        sourceType: 'STOCK_ADJUSTMENT',
        sourceId: count.id,
        description: `Stock count ${count.number}`,
        note: [count.number, count.note].filter(Boolean).join(' · '),
        counterAccount: 'INVENTORY_ADJUSTMENT',
        userId,
      });
      await tx.stockCount.update({ where: { id }, data: { isPosted: true, postedAt: new Date() } });
      await this.audit.log({ userId, action: 'POST', entityType: 'StockCount', entityId: id }, tx);
    });
    return this.getCount(id);
  }

  async deleteCount(id: string, userId: string) {
    const count = await this.prisma.stockCount.findUnique({ where: { id } });
    if (!count) throw new NotFoundException('Stock count not found');
    if (count.isPosted) throw new BadRequestException('Posted stock counts cannot be deleted');
    await this.prisma.stockCount.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'StockCount', entityId: id, before: count });
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
