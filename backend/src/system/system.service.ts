import { ForbiddenException, Injectable, Logger } from '@nestjs/common';
import { CashBoxKind } from '../generated/prisma/client';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { WALK_IN_CODE } from '../sales/pos.service';
import { WipeScope } from './system.dto';

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
  'purchase_order_items', 'supplier_payments', 'supplier_account_entries', 'supplier_vouchers', 'purchase_orders', 'suppliers',
  'expenses', 'wallet_top_ups', 'stored_files', 'notifications', 'audit_logs', 'customer_addresses',
  'stock_alerts', 'cashier_shift_lines', 'cashier_shifts', 'outgoing_sms', 'phone_otps', 'document_sequences',
];

/** People and their numbering stay when only operations are wiped. */
const PEOPLE_TABLES = new Set(['customer_addresses', 'suppliers', 'document_sequences']);

class DryRun extends Error {}

export interface WipeSummary {
  dryRun: boolean;
  scope: WipeScope;
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
  async wipeTestData(userId: string, dryRun = false, scope: WipeScope = WipeScope.ALL): Promise<WipeSummary> {
    const isAdmin = await this.prisma.userRole.count({ where: { userId, role: { name: 'admin' } } });
    if (!isAdmin) throw new ForbiddenException('Only a system admin can wipe the data');

    let summary: WipeSummary | undefined;
    try {
      await this.prisma.$transaction(
        async (tx) => {
          summary = await this.wipe(tx, userId, dryRun, scope);
          if (dryRun) throw new DryRun();
        },
        { timeout: 120_000, maxWait: 10_000 },
      );
    } catch (e) {
      if (!(e instanceof DryRun)) throw e;
    }
    if (!dryRun) this.log.warn(`Test data (${scope}) wiped by ${userId}: ${JSON.stringify(summary!.deleted)}`);
    return summary!;
  }

  private async wipe(tx: Tx, userId: string, dryRun: boolean, scope: WipeScope): Promise<WipeSummary> {
    const people = scope === WipeScope.ALL;
    const mainEmail = process.env.SEED_ADMIN_EMAIL || 'admin@dejavu.local';
    const kept = await tx.user.findMany({
      where: people ? { OR: [{ id: userId }, { email: mainEmail }] } : {},
      select: { id: true, email: true, phone: true, name: true },
    });
    const keepIds = kept.map((u) => u.id);
    const goneUsers = await tx.user.findMany({ where: { id: { notIn: keepIds } }, select: { id: true } });
    const goneIds = goneUsers.map((u) => u.id);
    const drawers = people
      ? await tx.cashBox.findMany({
          where: { kind: CashBoxKind.CASHIER, OR: [{ userId: null }, { userId: { notIn: keepIds } }] },
          select: { id: true, accountId: true },
        })
      : [];

    const deleted: Record<string, number> = {
      products: await tx.product.count(),
      variants: await tx.productVariant.count(),
      categories: await tx.category.count(),
      brands: await tx.brand.count(),
      orders: await tx.order.count(),
      payments: await tx.payment.count(),
      journalEntries: await tx.journalEntry.count(),
      purchaseOrders: await tx.purchaseOrder.count(),
      suppliers: people ? await tx.supplier.count() : 0,
      expenses: await tx.expense.count(),
      walletTopUps: await tx.walletTopUp.count(),
      promotions: await tx.promotion.count(),
      files: await tx.storedFile.count(),
      customers: people ? await tx.customer.count({ where: { code: { not: WALK_IN_CODE } } }) : 0,
      users: goneIds.length,
      cashierDrawers: drawers.length,
    };

    const tables = BUSINESS_TABLES.filter((t) => people || !PEOPLE_TABLES.has(t));
    await tx.$executeRawUnsafe(`TRUNCATE TABLE ${tables.map((t) => `"${t}"`).join(', ')} RESTART IDENTITY`);

    if (people) {
      await tx.customer.deleteMany({ where: { code: { not: WALK_IN_CODE } } });
      // Drawers of removed cashiers go with their own ledger accounts (now without lines).
      await tx.cashBox.deleteMany({ where: { id: { in: drawers.map((d) => d.id) } } });
      await tx.account.deleteMany({ where: { id: { in: drawers.map((d) => d.accountId) }, systemKey: null } });
      await tx.user.deleteMany({ where: { id: { in: goneIds } } });
      // Personal permission roles of removed staff.
      await tx.role.deleteMany({ where: { name: { in: goneIds.map((id) => `user_${id.replace(/-/g, '')}`) }, users: { none: {} } } });
    } else {
      // Customers stay with a fresh start: balances are gone with their entries, points reset.
      await tx.customer.updateMany({ data: { loyaltyPoints: 0 } });
    }
    await tx.fiscalPeriod.updateMany({ where: { status: { not: 'OPEN' } }, data: { status: 'OPEN', closedAt: null, closedById: null } });
    // Remembers the wipe, so demo data is never loaded into this database again.
    await tx.setting.upsert({
      where: { key: 'system.wipedAt' },
      update: { value: new Date().toISOString() },
      create: { key: 'system.wipedAt', value: new Date().toISOString() },
    });

    return { dryRun, scope, deleted, keptUsers: people ? kept.map((u) => u.email ?? u.phone ?? u.name) : [] };
  }
}
