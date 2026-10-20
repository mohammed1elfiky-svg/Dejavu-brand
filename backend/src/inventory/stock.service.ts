import { ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { StockMovementType } from '../generated/prisma/client';
import { Decimal, DecimalLike, dec, money } from '../common/utils/money';
import { Tx } from '../prisma/prisma.service';

interface MovementContext {
  type: StockMovementType;
  sourceType?: string;
  sourceId?: string;
  note?: string;
  userId?: string | null;
}

export interface StockInRequest extends MovementContext {
  variantId: string;
  warehouseId: string;
  quantity: number;
  unitCost: DecimalLike;
}

export interface StockOutRequest extends MovementContext {
  variantId: string;
  warehouseId: string;
  quantity: number;
  /** Consume a reservation made at checkout instead of free stock. */
  fromReserved?: boolean;
}

export interface StockResult {
  unitCost: Decimal;
  /** quantity × unitCost, rounded to currency precision. */
  value: Decimal;
}

/**
 * Low-level inventory operations. All methods must run inside the caller's
 * transaction. Every on-hand change writes an append-only stock movement;
 * stock checks are single conditional UPDATEs so concurrent orders cannot
 * oversell. Callers touching several variants should process them in a stable
 * order (e.g. sorted by variantId) to avoid lock-order deadlocks.
 *
 * Costing: weighted average per variant across all warehouses.
 */
@Injectable()
export class StockService {
  async stockIn(tx: Tx, req: StockInRequest): Promise<StockResult> {
    assertPositive(req.quantity);
    const unitCost = dec(req.unitCost);
    const current = await this.lockVariant(tx, req.variantId);

    const [{ total }] = await tx.$queryRaw<{ total: number }[]>`
      SELECT COALESCE(SUM(on_hand), 0)::int AS total FROM stock_levels WHERE variant_id = ${req.variantId}::uuid`;
    const newQty = total + req.quantity;
    const newAvg = dec(current.avgCost).mul(total).add(unitCost.mul(req.quantity)).div(newQty).toDecimalPlaces(4);

    await tx.productVariant.update({ where: { id: req.variantId }, data: { avgCost: newAvg } });
    await tx.$executeRaw`
      INSERT INTO stock_levels (variant_id, warehouse_id, on_hand, reserved, updated_at)
      VALUES (${req.variantId}::uuid, ${req.warehouseId}::uuid, ${req.quantity}, 0, now())
      ON CONFLICT (variant_id, warehouse_id)
      DO UPDATE SET on_hand = stock_levels.on_hand + EXCLUDED.on_hand, updated_at = now()`;
    await this.recordMovement(tx, req, req.quantity, unitCost);
    return { unitCost, value: money(unitCost.mul(req.quantity)) };
  }

  async stockOut(tx: Tx, req: StockOutRequest): Promise<StockResult> {
    assertPositive(req.quantity);
    const variant = await this.lockVariant(tx, req.variantId);
    const q = req.quantity;

    const updated = req.fromReserved
      ? await tx.$executeRaw`
          UPDATE stock_levels SET on_hand = on_hand - ${q}, reserved = reserved - ${q}, updated_at = now()
          WHERE variant_id = ${req.variantId}::uuid AND warehouse_id = ${req.warehouseId}::uuid
            AND reserved >= ${q} AND on_hand >= ${q}`
      : await tx.$executeRaw`
          UPDATE stock_levels SET on_hand = on_hand - ${q}, updated_at = now()
          WHERE variant_id = ${req.variantId}::uuid AND warehouse_id = ${req.warehouseId}::uuid
            AND on_hand - reserved >= ${q}`;
    if (updated === 0) {
      throw new ConflictException(
        req.fromReserved ? `Reserved stock for ${variant.sku} is missing` : `Insufficient stock for ${variant.sku}`,
      );
    }

    const unitCost = dec(variant.avgCost);
    await this.recordMovement(tx, req, -q, unitCost);
    return { unitCost, value: money(unitCost.mul(q)) };
  }

  async reserve(tx: Tx, variantId: string, warehouseId: string, quantity: number): Promise<void> {
    assertPositive(quantity);
    const updated = await tx.$executeRaw`
      UPDATE stock_levels SET reserved = reserved + ${quantity}, updated_at = now()
      WHERE variant_id = ${variantId}::uuid AND warehouse_id = ${warehouseId}::uuid
        AND on_hand - reserved >= ${quantity}`;
    if (updated === 0) {
      const v = await tx.productVariant.findUnique({ where: { id: variantId }, select: { sku: true } });
      throw new ConflictException(`Insufficient stock for ${v?.sku ?? variantId}`);
    }
  }

  async release(tx: Tx, variantId: string, warehouseId: string, quantity: number): Promise<void> {
    assertPositive(quantity);
    const updated = await tx.$executeRaw`
      UPDATE stock_levels SET reserved = reserved - ${quantity}, updated_at = now()
      WHERE variant_id = ${variantId}::uuid AND warehouse_id = ${warehouseId}::uuid AND reserved >= ${quantity}`;
    if (updated === 0) throw new ConflictException(`No reservation to release for variant ${variantId}`);
  }

  private async lockVariant(tx: Tx, variantId: string) {
    const rows = await tx.$queryRaw<{ sku: string; avg_cost: string }[]>`
      SELECT sku, avg_cost::text FROM product_variants WHERE id = ${variantId}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException(`Variant ${variantId} not found`);
    return { sku: rows[0].sku, avgCost: rows[0].avg_cost };
  }

  private async recordMovement(tx: Tx, ctx: StockInRequest | StockOutRequest, quantity: number, unitCost: Decimal) {
    await tx.stockMovement.create({
      data: {
        variantId: ctx.variantId,
        warehouseId: ctx.warehouseId,
        type: ctx.type,
        quantity,
        unitCost,
        sourceType: ctx.sourceType,
        sourceId: ctx.sourceId,
        note: ctx.note,
        createdById: ctx.userId ?? null,
      },
    });
  }
}

function assertPositive(quantity: number) {
  if (!Number.isInteger(quantity) || quantity <= 0) {
    throw new ConflictException('Quantity must be a positive integer');
  }
}
