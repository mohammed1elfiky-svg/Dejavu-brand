import { Controller, Get, Injectable } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, StaffOnly } from '../common/decorators/auth.decorators';
import { GiftStatus, InstagramStatus, LayawayStatus, OrderChannel, OrderStatus, PreOrderStatus, ReturnStatus, TopUpStatus } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

const TAKE = 30;

/**
 * "مهام اليوم": everything waiting on the staff in one list, by what each user may handle —
 * top-ups to check, merchants' orders to approve, orders to ship, reservations to put aside,
 * gifts to prepare, arrived pre-orders, returns, Instagram codes and low stock.
 */
@Injectable()
export class TodayService {
  constructor(private readonly prisma: PrismaService) {}

  async tasks(p: AuthPrincipal) {
    const can = (perm: string) => p.permissions.includes(perm);
    const none = Promise.resolve([] as never[]);
    const customer = { select: { id: true, name: true, phone: true } };
    const [topUps, approvals, toShip, layaways, gifts, preOrders, returns, instagram, lowStock] = await Promise.all([
      can('wallet.manage')
        ? this.prisma.walletTopUp.findMany({
            where: { OR: [{ status: TopUpStatus.PENDING }, { status: TopUpStatus.APPROVED, autoApproved: true, verifiedAt: null }] },
            orderBy: { createdAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, amount: true, currency: true, status: true, autoApproved: true, createdAt: true, customer },
          })
        : none,
      can('orders.update_status')
        ? this.prisma.order.findMany({
            where: { needsApproval: true, approvedAt: null, status: OrderStatus.PENDING },
            orderBy: { placedAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, grandTotal: true, currency: true, placedAt: true, customer },
          })
        : none,
      can('orders.update_status')
        ? this.prisma.order.findMany({
            where: { channel: OrderChannel.ONLINE, status: { in: [OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PROCESSING] }, OR: [{ needsApproval: false }, { approvedAt: { not: null } }] },
            orderBy: { placedAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, status: true, deliveryMethod: true, grandTotal: true, currency: true, placedAt: true, customer, carrier: { select: { name: true } } },
          })
        : none,
      can('layaways.manage')
        ? this.prisma.layaway.findMany({
            where: { status: LayawayStatus.OPEN, heldAt: null },
            orderBy: { createdAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, createdAt: true, customer },
          })
        : none,
      can('orders.update_status')
        ? this.prisma.gift.findMany({
            where: { status: { in: [GiftStatus.PICKUP, GiftStatus.DELIVERY] } },
            orderBy: { chosenAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, status: true, giverName: true, chosenAt: true, recipient: customer },
          })
        : none,
      can('layaways.manage')
        ? this.prisma.preOrder.findMany({
            where: { status: PreOrderStatus.NOTIFIED },
            orderBy: { notifiedAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, quantity: true, itemName: true, confirmedAt: true, notifiedAt: true, customer },
          })
        : none,
      can('returns.manage')
        ? this.prisma.salesReturn.findMany({
            where: { status: { in: [ReturnStatus.REQUESTED, ReturnStatus.APPROVED] } },
            orderBy: { requestedAt: 'asc' },
            take: TAKE,
            select: { id: true, number: true, status: true, requestedAt: true, customer },
          })
        : none,
      can('customers.write')
        ? this.prisma.customer.findMany({
            where: { igStatus: InstagramStatus.PENDING },
            orderBy: { updatedAt: 'asc' },
            take: TAKE,
            select: { id: true, name: true, phone: true, username: true },
          })
        : none,
      can('products.read')
        ? this.prisma.$queryRaw<{ variant_id: string; sku: string; name_ar: string; available: number; threshold: number }[]>`
            SELECT a.variant_id, a.sku, p.name_ar, a.available::int, v.low_stock_threshold AS threshold
            FROM v_variant_availability a
            JOIN product_variants v ON v.id = a.variant_id
            JOIN products p ON p.id = v.product_id
            WHERE v.is_active AND p.status = 'ACTIVE' AND a.is_low_stock
            ORDER BY a.available ASC, a.sku
            LIMIT ${TAKE}`
        : none,
    ]);
    const sections = {
      topUps,
      approvals,
      toShip,
      layaways,
      gifts,
      preOrders,
      returns,
      instagram,
      lowStock: lowStock.map((r) => ({ variantId: r.variant_id, sku: r.sku, name: r.name_ar, available: r.available, threshold: r.threshold })),
    };
    const counts = Object.fromEntries(Object.entries(sections).map(([k, v]) => [k, v.length])) as Record<keyof typeof sections, number>;
    return { counts, total: Object.values(counts).reduce((a, b) => a + b, 0), ...sections };
  }
}

@ApiTags('Admin · Today')
@StaffOnly()
@Controller('admin/today')
export class AdminTodayController {
  constructor(private readonly today: TodayService) {}

  @Get()
  tasks(@CurrentUser() u: AuthPrincipal) {
    return this.today.tasks(u);
  }
}
