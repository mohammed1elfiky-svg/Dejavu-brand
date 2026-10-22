import { ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { CashBoxKind } from '../generated/prisma/client';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { WALK_IN_CODE } from '../sales/pos.service';

/**
 * Everything the business produces: catalog, stock, sales, purchases, money movements,
 * the ledger and their files. TRUNCATE does not fire the append-only row triggers, and
 * listing every table (no CASCADE) makes Postgres refuse if one referencing them is missed.
 */
const BUSINESS_TABLES = [
  'journal_lines', 'journal_entries',
  'cash_box_entries', 'cash_transfers', 'customer_account_entries',
  'stock_movements', 'stock_levels', 'stock_count_items', 'stock_counts',
  'sales_return_items', 'sales_returns', 'shipments', 'courier_settlements',
  'payments', 'coupon_usages', 'coupons', 'order_status_history', 'order_items', 'orders',
  'product_reviews', 'wishlist_items', 'cart_items', 'carts',
  'promotion_products', 'promotions', 'product_images', 'product_variants', 'products', 'brands', 'categories',
  'purchase_order_items', 'supplier_payments', 'purchase_orders', 'suppliers',
  'expenses', 'wallet_top_ups', 'stored_files', 'notifications', 'audit_logs', 'customer_addresses',
  'document_sequences',
];

class DryRun extends Error {}

export interface WipeSummary {
  dryRun: boolean;
  deleted: Record<string, number>;
  keptUsers: string[];
}

@Injectable()
export class SystemService {
  private readonly log = new Logger('System');

  constructor(private readonly prisma: PrismaService) {}

  /**
   * Clears all test data so the store starts clean: keeps the main admin account (and the
   * admin running this), currencies and rates, the chart of accounts, fiscal years, roles,
   * warehouses, safes and bank boxes, store bank accounts, expense categories and settings.
   */
  async wipeTestData(userId: string, dryRun = false): Promise<WipeSummary> {
    const isAdmin = await this.prisma.userRole.count({ where: { userId, role: { name: 'admin' } } });
    if (!isAdmin) throw new ForbiddenException('Only a system admin can wipe the data');

    let summary: WipeSummary | undefined;
    try {
      await this.prisma.$transaction(
        async (tx) => {
          summary = await this.wipe(tx, userId, dryRun);
          if (dryRun) throw new DryRun();
        },
        { timeout: 120_000, maxWait: 10_000 },
      );
    } catch (e) {
      if (!(e instanceof DryRun)) throw e;
    }
    if (!dryRun) this.log.warn(`Test data wiped by ${userId}: ${JSON.stringify(summary!.deleted)}`);
    return summary!;
  }

  private async wipe(tx: Tx, userId: string, dryRun: boolean): Promise<WipeSummary> {
    const mainEmail = process.env.SEED_ADMIN_EMAIL || 'admin@dejavu.local';
    const kept = await tx.user.findMany({ where: { OR: [{ id: userId }, { email: mainEmail }] }, select: { id: true, email: true } });
    const keepIds = kept.map((u) => u.id);
    const goneUsers = await tx.user.findMany({ where: { id: { notIn: keepIds } }, select: { id: true } });
    const goneIds = goneUsers.map((u) => u.id);
    const drawers = await tx.cashBox.findMany({
      where: { kind: CashBoxKind.CASHIER, OR: [{ userId: null }, { userId: { notIn: keepIds } }] },
      select: { id: true, accountId: true },
    });

    const deleted: Record<string, number> = {
      products: await tx.product.count(),
      variants: await tx.productVariant.count(),
      categories: await tx.category.count(),
      brands: await tx.brand.count(),
      orders: await tx.order.count(),
      payments: await tx.payment.count(),
      journalEntries: await tx.journalEntry.count(),
      purchaseOrders: await tx.purchaseOrder.count(),
      suppliers: await tx.supplier.count(),
      expenses: await tx.expense.count(),
      walletTopUps: await tx.walletTopUp.count(),
      promotions: await tx.promotion.count(),
      files: await tx.storedFile.count(),
      customers: await tx.customer.count({ where: { code: { not: WALK_IN_CODE } } }),
      users: goneIds.length,
      cashierDrawers: drawers.length,
    };

    await tx.$executeRawUnsafe(`TRUNCATE TABLE ${BUSINESS_TABLES.map((t) => `"${t}"`).join(', ')} RESTART IDENTITY`);

    await tx.customer.deleteMany({ where: { code: { not: WALK_IN_CODE } } });
    // Drawers of removed cashiers go with their own ledger accounts (now without lines).
    await tx.cashBox.deleteMany({ where: { id: { in: drawers.map((d) => d.id) } } });
    await tx.account.deleteMany({ where: { id: { in: drawers.map((d) => d.accountId) }, systemKey: null } });
    await tx.user.deleteMany({ where: { id: { in: goneIds } } });
    // Personal permission roles of removed staff.
    await tx.role.deleteMany({ where: { name: { in: goneIds.map((id) => `user_${id.replace(/-/g, "")}`) }, users: { none: {} } } });
    await tx.fiscalPeriod.updateMany({ where: { status: { not: 'OPEN' } }, data: { status: 'OPEN', closedAt: null, closedById: null } });
    // Remembers the wipe, so demo data is never loaded into this database again.
    await tx.setting.upsert({
      where: { key: 'system.wipedAt' },
      update: { value: new Date().toISOString() },
      create: { key: 'system.wipedAt', value: new Date().toISOString() },
    });

    return { dryRun, deleted, keptUsers: kept.map((u) => u.email) };
  }
}
