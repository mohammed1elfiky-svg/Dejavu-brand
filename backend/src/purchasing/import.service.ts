import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { ProductStatus, PurchaseKind, PurchaseOrderStatus, StockMovementType } from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { CatalogService } from '../catalog/catalog.service';
import { AuditService } from '../common/audit.service';
import { CurrencyService } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { Decimal, dec, money, sum } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { ImportOrderDto, ImportReceiveDto } from './dto/purchasing.dto';
import { importLandedCosts } from './purchase-math';
import { PurchasingService } from './purchasing.service';

/** Orders still waiting for goods. */
const OPEN = [PurchaseOrderStatus.ORDERED, PurchaseOrderStatus.PARTIALLY_RECEIVED];

/**
 * Buying abroad (e.g. from China): the order is priced in the supplier's currency and the
 * goods stay with the supplier ("pending delivery") until the shipment arrives. On arrival
 * the counted pieces enter stock at their landed cost: price converted to SAR, plus the
 * supplier commission, inland shipping and the CBM share of freight & customs.
 *
 *   Dr Inventory (landed)  —  Cr Accounts payable (goods + commission + inland shipping)
 *                          —  Cr Accrued expenses (freight & customs, owed to the forwarder)
 */
@Injectable()
export class ImportService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly purchasing: PurchasingService,
    private readonly catalog: CatalogService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
  ) {}

  /** SAR per 1 USD from the currency settings (they store USD per 1 SAR). */
  private async sarPerUsd(given?: string) {
    if (given) return dec(given);
    const usdPerSar = await this.currencies.rate('USD').catch(() => null);
    if (!usdPerSar || usdPerSar.isZero()) throw new UnprocessableEntityException('Set the USD rate (or send sarPerUsd)');
    return dec(1).div(usdPerSar).toDecimalPlaces(6);
  }

  async createOrder(dto: ImportOrderDto, userId: string) {
    const items = dto.items ?? [];
    const fresh = dto.newProducts ?? [];
    if (!items.length && !fresh.length) throw new BadRequestException('Add at least one item');
    if (new Set(items.map((i) => i.variantId)).size !== items.length) throw new BadRequestException('Each size/colour may appear only once');
    const supplier = await this.prisma.supplier.findUnique({ where: { id: dto.supplierId } });
    if (!supplier || !supplier.isActive) throw new NotFoundException('Supplier not found or inactive');
    const warehouse = dto.warehouseId
      ? await this.prisma.warehouse.findUnique({ where: { id: dto.warehouseId } })
      : await this.prisma.warehouse.findFirst({ where: { isDefault: true } });
    if (!warehouse || !warehouse.isActive) throw new NotFoundException('Warehouse not found or inactive');
    if (items.length && (await this.prisma.productVariant.count({ where: { id: { in: items.map((i) => i.variantId) } } })) !== items.length) {
      throw new BadRequestException('One or more variants do not exist');
    }
    const foreignPerUsd = dec(dto.foreignPerUsd);
    if (!foreignPerUsd.gt(0)) throw new BadRequestException('The exchange rate must be greater than zero');
    const sarPerUsd = await this.sarPerUsd(dto.sarPerUsd);
    const commissionPercent = dto.commissionPercent != null ? dec(dto.commissionPercent) : dec(supplier.commissionPercent);
    if (commissionPercent.gte(100)) throw new BadRequestException('Commission must be below 100%');
    const toSar = sarPerUsd.div(foreignPerUsd);

    // New items become draft products (no stock, not on sale) held for this order.
    const created: { variantId: string; quantity: number; unitPriceForeign: string }[] = [];
    for (const p of fresh) {
      const estimate = dec(p.unitPriceForeign).mul(toSar).mul(dec(1).add(commissionPercent.div(100))).toDecimalPlaces(4);
      const product = await this.catalog.createProduct(
        {
          categoryId: p.categoryId,
          brandId: p.brandId,
          nameAr: p.nameAr,
          basePrice: p.basePrice,
          costPrice: estimate.toString(),
          status: ProductStatus.DRAFT,
          images: p.imageUrl ? [{ url: p.imageUrl }] : undefined,
          variants: p.variants.map((v) => ({ size: v.size, color: v.color, colorHex: v.colorHex })),
        },
        userId,
      );
      p.variants.forEach((v, i) => created.push({ variantId: product.variants[i].id, quantity: v.quantity, unitPriceForeign: p.unitPriceForeign }));
    }

    const lines = [...items.map((i) => ({ ...i, createdProduct: false })), ...created.map((c) => ({ ...c, createdProduct: true }))];
    const goods = lines.map((l) => money(dec(l.unitPriceForeign).mul(l.quantity).mul(toSar)));
    const subtotal = sum(goods);
    const extras = money(subtotal.mul(commissionPercent).div(100).add(dec(dto.domesticShipping ?? 0).mul(toSar)));

    const id = await this.prisma.$transaction(async (tx) => {
      const po = await tx.purchaseOrder.create({
        data: {
          number: await this.sequences.next(tx, 'PO'),
          kind: PurchaseKind.IMPORT,
          status: PurchaseOrderStatus.ORDERED,
          supplierId: supplier.id,
          warehouseId: warehouse.id,
          supplierInvoiceNo: dto.supplierInvoiceNo,
          expectedDate: dto.expectedDate ? parseDateOnly(dto.expectedDate) : null,
          note: dto.note,
          foreignCurrency: dto.foreignCurrency ?? 'CNY',
          foreignPerUsd,
          sarPerUsd,
          commissionPercent,
          domesticShipping: money(dto.domesticShipping ?? 0),
          // Estimates until the shipment arrives.
          subtotal,
          shippingCost: extras,
          grandTotal: subtotal.add(extras),
          createdById: userId,
          items: {
            create: lines.map((l, i) => ({
              variantId: l.variantId,
              quantity: l.quantity,
              unitPriceForeign: dec(l.unitPriceForeign),
              unitCost: dec(l.unitPriceForeign).mul(toSar).toDecimalPlaces(4),
              lineTotal: goods[i],
              createdProduct: l.createdProduct,
            })),
          },
        },
      });
      await this.audit.log({ userId, action: 'CREATE', entityType: 'PurchaseOrder', entityId: po.id, after: dto }, tx);
      return po.id;
    });
    return this.purchasing.getOrder(id);
  }

  /** The costs a receipt would book, without saving anything. */
  async preview(id: string, dto: ImportReceiveDto) {
    const po = await this.openImport(this.prisma, id);
    return this.costs(po, dto);
  }

  async receive(id: string, dto: ImportReceiveDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      await tx.$queryRaw`SELECT id FROM purchase_orders WHERE id = ${id}::uuid FOR UPDATE`;
      const po = await this.openImport(tx, id);
      const c = this.costs(po, dto);
      if (!c.lines.some((l) => l.quantity > 0)) throw new BadRequestException('No pieces were counted');
      const owed = money(c.totals.goods.add(c.totals.commission).add(c.totals.domestic));
      if (owed.lt(po.amountPaid)) throw new UnprocessableEntityException('The supplier was paid more than the goods received; settle that first');

      const note = [po.number, dto.note].filter(Boolean).join(' · ');
      const productCost = new Map<string, { qty: number; value: Decimal }>();
      for (const l of [...c.lines].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        if (l.quantity > 0) {
          await this.stock.stockIn(tx, {
            type: StockMovementType.PURCHASE_RECEIPT,
            variantId: l.variantId,
            warehouseId: po.warehouseId,
            quantity: l.quantity,
            unitCost: l.unitCost,
            sourceType: 'PURCHASE_ORDER',
            sourceId: po.id,
            note,
            userId,
          });
          const p = productCost.get(l.productId) ?? { qty: 0, value: dec(0) };
          productCost.set(l.productId, { qty: p.qty + l.quantity, value: p.value.add(l.total) });
        }
        await tx.purchaseOrderItem.update({
          where: { id: l.itemId },
          data: {
            receivedQty: l.quantity,
            cbm: l.cbm,
            landedUnitCost: l.quantity > 0 ? l.unitCost : null,
            unitCost: l.quantity > 0 ? l.goods.div(l.quantity).toDecimalPlaces(4) : undefined,
            lineTotal: l.goods,
          },
        });
      }
      // The products now carry the new cost; drafts created for this order go on sale.
      for (const [productId, v] of productCost) {
        const created = c.lines.some((l) => l.productId === productId && l.createdProduct);
        await tx.product.update({
          where: { id: productId },
          data: { costPrice: v.value.div(v.qty).toDecimalPlaces(4), ...(created && { status: ProductStatus.ACTIVE }) },
        });
      }

      await this.ledger.post(tx, {
        description: `Import shipment received for ${po.number}${po.supplierInvoiceNo ? ` (invoice ${po.supplierInvoiceNo})` : ''}`,
        sourceType: 'PURCHASE_ORDER',
        sourceId: po.id,
        userId,
        lines: [
          { account: 'INVENTORY', debit: c.totals.total },
          { account: 'AP', credit: owed, supplierId: po.supplierId },
          { account: 'ACCRUED_EXPENSES', credit: c.totals.freight, description: `Freight & customs ${c.totals.cbm} m³` },
        ],
      });
      await tx.purchaseOrder.update({
        where: { id },
        data: {
          status: PurchaseOrderStatus.RECEIVED,
          receivedAt: new Date(),
          foreignPerUsd: c.foreignPerUsd,
          sarPerUsd: c.sarPerUsd,
          commissionPercent: c.commissionPercent,
          domesticShipping: c.domesticShipping,
          totalCbm: c.totals.cbm,
          cbmRateUsd: c.cbmRateUsd,
          subtotal: c.totals.goods,
          shippingCost: money(c.totals.commission.add(c.totals.domestic)),
          freightCost: c.totals.freight,
          grandTotal: owed,
          note: [po.note, dto.note].filter(Boolean).join(' · ') || null,
        },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'PurchaseOrder', entityId: id, after: { importReceived: dto } }, tx);
    });
    return this.purchasing.getOrder(id);
  }

  /** What each supplier still has to deliver: ordered pieces not received yet. */
  async pendingItems(supplierId: string) {
    const supplier = await this.prisma.supplier.findUnique({ where: { id: supplierId }, select: { id: true } });
    if (!supplier) throw new NotFoundException('Supplier not found');
    const items = await this.prisma.purchaseOrderItem.findMany({
      where: { purchaseOrder: { supplierId, status: { in: OPEN } } },
      orderBy: [{ purchaseOrder: { orderDate: 'asc' } }, { id: 'asc' }],
      select: {
        id: true,
        quantity: true,
        receivedQty: true,
        unitCost: true,
        unitPriceForeign: true,
        createdProduct: true,
        purchaseOrder: { select: { id: true, number: true, orderDate: true, expectedDate: true, kind: true, foreignCurrency: true } },
        variant: {
          select: {
            id: true,
            sku: true,
            size: true,
            color: true,
            product: { select: { id: true, nameAr: true, images: { orderBy: { sortOrder: 'asc' }, take: 1, select: { url: true } } } },
          },
        },
      },
    });
    const pending = items
      .filter((i) => i.receivedQty < i.quantity)
      .map(({ variant, ...i }) => ({
        ...i,
        pendingQty: i.quantity - i.receivedQty,
        variant: { id: variant.id, sku: variant.sku, size: variant.size, color: variant.color },
        product: { id: variant.product.id, nameAr: variant.product.nameAr, imageUrl: variant.product.images[0]?.url ?? null },
      }));
    return { items: pending, totalPieces: pending.reduce((n, i) => n + i.pendingQty, 0) };
  }

  // -------------------------------------------------------------- helpers ---

  private async openImport(db: Tx | PrismaService, id: string) {
    const po = await db.purchaseOrder.findUnique({
      where: { id },
      include: { items: { orderBy: { id: 'asc' }, include: { variant: { select: { productId: true } } } } },
    });
    if (!po) throw new NotFoundException('Purchase order not found');
    if (po.kind !== PurchaseKind.IMPORT) throw new BadRequestException('Not an import order');
    if (!OPEN.includes(po.status as (typeof OPEN)[number])) throw new UnprocessableEntityException(`Cannot receive a ${po.status} order`);
    return po;
  }

  private costs(po: Awaited<ReturnType<ImportService['openImport']>>, dto: ImportReceiveDto) {
    if (new Set(dto.items.map((i) => i.itemId)).size !== dto.items.length) throw new BadRequestException('Each line may appear only once');
    if (dto.items.some((r) => !po.items.some((i) => i.id === r.itemId))) throw new BadRequestException('Item does not belong to this purchase order');
    const foreignPerUsd = dec(dto.foreignPerUsd ?? po.foreignPerUsd ?? 0);
    const sarPerUsd = dec(dto.sarPerUsd ?? po.sarPerUsd ?? 0);
    if (!foreignPerUsd.gt(0) || !sarPerUsd.gt(0)) throw new BadRequestException('Exchange rates must be greater than zero');
    const commissionPercent = dec(dto.commissionPercent ?? po.commissionPercent);
    if (commissionPercent.gte(100)) throw new BadRequestException('Commission must be below 100%');
    const domesticShipping = dec(dto.domesticShipping ?? po.domesticShipping);
    const cbmRateUsd = dec(dto.cbmRateUsd);

    const rows = po.items.map((i) => {
      const r = dto.items.find((x) => x.itemId === i.id);
      const quantity = r?.quantity ?? 0;
      if (quantity > i.quantity) throw new UnprocessableEntityException(`Counted more than ordered on a line (${i.quantity} ordered)`);
      return { item: i, quantity, cbm: r?.cbm != null ? dec(r.cbm) : null };
    });
    const result = importLandedCosts({
      lines: rows.map((r) => ({ quantity: r.quantity, priceForeign: r.item.unitPriceForeign ?? 0, cbm: r.cbm })),
      foreignPerUsd,
      sarPerUsd,
      commissionPercent,
      domesticShipping,
      totalCbm: dto.totalCbm ?? null,
      cbmRateUsd,
    });
    return {
      foreignCurrency: po.foreignCurrency,
      foreignPerUsd,
      sarPerUsd,
      commissionPercent,
      domesticShipping: money(domesticShipping),
      cbmRateUsd,
      lines: rows.map((r, i) => ({
        itemId: r.item.id,
        variantId: r.item.variantId,
        productId: r.item.variant.productId,
        createdProduct: r.item.createdProduct,
        ordered: r.item.quantity,
        quantity: r.quantity,
        unitPriceForeign: r.item.unitPriceForeign,
        ...result.lines[i],
      })),
      totals: result.totals,
    };
  }
}
