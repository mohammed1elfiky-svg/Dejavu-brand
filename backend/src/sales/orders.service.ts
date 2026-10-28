import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
  UnprocessableEntityException,
} from '@nestjs/common';
import {
  NotificationType,
  Order,
  OrderChannel,
  OrderStatus,
  PaymentMethod,
  PaymentStatus,
  Prisma,
  ShipmentStatus,
  StockMovementType,
  TransactionStatus,
} from '../generated/prisma/client';
import { LedgerService } from '../accounting/ledger.service';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, toCurrency } from '../common/currency.service';
import { paginated } from '../common/dto/pagination.dto';
import { SequenceService } from '../common/sequence.service';
import { parseDateOnly } from '../common/utils/dates';
import { dec, money, sum } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { orderAmount, orderStatusMessage } from '../notifications/messages';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { FilesService, UploadedPart } from '../files/files.service';
import { StoreSettingsService } from './store-settings.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';
import { CartService } from './cart.service';
import { AdminOrdersQueryDto, ChangeOrderStatusDto, CheckoutDto, RecordPaymentDto, RefundDto } from './dto/sales.dto';
import { balanceDue, PaymentsService } from './payments.service';
import { GatewayResult } from './gateways/card-gateway';
import { revenueSplit } from './pricing';
import { getSetting } from './settings';

const TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  PENDING: [OrderStatus.CONFIRMED, OrderStatus.CANCELLED],
  CONFIRMED: [OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.CANCELLED],
  PROCESSING: [OrderStatus.SHIPPED, OrderStatus.CANCELLED],
  // RETURNED here means the customer refused the delivery.
  SHIPPED: [OrderStatus.DELIVERED, OrderStatus.RETURNED],
  DELIVERED: [OrderStatus.COMPLETED],
  COMPLETED: [],
  CANCELLED: [],
  // Returns after delivery go through sales returns; the order itself stays DELIVERED.
  RETURNED: [],
};

const CUSTOMER_CANCELLABLE: OrderStatus[] = [OrderStatus.PENDING, OrderStatus.CONFIRMED];

/** What the customer app may see: no costs, no internal notes. */
const customerOrderSelect = {
  id: true,
  number: true,
  status: true,
  paymentStatus: true,
  paymentMethod: true,
  currency: true,
  exchangeRate: true,
  shippingAddress: true,
  subtotal: true,
  discountTotal: true,
  shippingFee: true,
  taxTotal: true,
  grandTotal: true,
  amountPaid: true,
  returnedTotal: true,
  customerNote: true,
  shippingReceiptFileId: true,
  /** A merchant's order on credit waiting for the owner. */
  needsApproval: true,
  approvedAt: true,
  placedAt: true,
  confirmedAt: true,
  deliveredAt: true,
  cancelledAt: true,
  cancelReason: true,
  coupon: { select: { code: true } },
  items: {
    select: {
      id: true,
      variantId: true,
      productName: true,
      sku: true,
      size: true,
      color: true,
      quantity: true,
      unitPrice: true,
      discount: true,
      taxAmount: true,
      lineTotal: true,
      returnedQty: true,
    },
  },
  statusHistory: { orderBy: { createdAt: 'asc' }, select: { toStatus: true, createdAt: true } },
  shipments: { select: { carrier: true, trackingNumber: true, status: true, shippedAt: true, deliveredAt: true } },
} satisfies Prisma.OrderSelect;

const adminOrderInclude = {
  customer: { select: { id: true, code: true, name: true, phone: true } },
  coupon: { select: { id: true, code: true } },
  items: true,
  payments: { orderBy: { createdAt: 'asc' } },
  shipments: true,
  statusHistory: { orderBy: { createdAt: 'asc' }, include: { changedBy: { select: { id: true, name: true } } } },
} satisfies Prisma.OrderInclude;

@Injectable()
export class OrdersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly cart: CartService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly payments: PaymentsService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly notifications: NotificationsService,
    private readonly currencies: CurrencyService,
    private readonly files: FilesService,
    private readonly storeSettings: StoreSettingsService,
    private readonly accounts: CustomerAccountsService,
  ) {}

  // ------------------------------------------------------------- checkout ---

  async checkout(customerId: string, dto: CheckoutDto) {
    const placed = await this.prisma.$transaction(async (tx) => {
      // Serialise checkouts of the same cart (e.g. a double-tapped "Place order").
      await tx.$queryRaw`SELECT id FROM carts WHERE customer_id = ${customerId}::uuid FOR UPDATE`;
      const quote = await this.cart.quote(tx, customerId, dto.addressId);
      this.cart.assertCheckoutable(quote);

      const warehouse = await tx.warehouse.findFirst({ where: { isDefault: true, isActive: true } });
      if (!warehouse) throw new UnprocessableEntityException('No default warehouse is configured');
      const address = await tx.customerAddress.findUniqueOrThrow({ where: { id: dto.addressId } });
      await this.storeSettings.assertMethod(tx, dto.paymentMethod, address.governorate);

      // Reserve in a stable order; the conditional UPDATE is the real oversell guard.
      for (const line of [...quote.lines].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        await this.stock.reserve(tx, line.variantId, warehouse.id, line.quantity);
      }

      if (quote.coupon) {
        const claimed = await tx.$executeRaw`
          UPDATE coupons SET used_count = used_count + 1
          WHERE id = ${quote.coupon.id}::uuid AND (usage_limit IS NULL OR used_count < usage_limit)`;
        if (!claimed) throw new UnprocessableEntityException('Coupon usage limit reached');
      }

      const p = quote.pricing;
      const currency = dto.currency ?? BASE_CURRENCY;
      const buyer = await tx.customer.findUniqueOrThrow({ where: { id: customerId }, select: { isMerchant: true, creditLimit: true } });
      // A merchant allowed to buy on credit: the owner approves (or edits) each order first.
      const needsApproval = buyer.isMerchant && buyer.creditLimit !== null;
      const { rate: exchangeRate } = await this.currencies.requireActive(currency, tx, { customerApp: true });
      const order = await tx.order.create({
        data: {
          number: await this.sequences.next(tx, 'SO'),
          customerId,
          addressId: address.id,
          warehouseId: warehouse.id,
          couponId: quote.coupon?.id,
          paymentMethod: dto.paymentMethod,
          currency,
          exchangeRate,
          shippingAddress: snapshotAddress(address),
          subtotal: p.subtotal,
          discountTotal: p.discountTotal,
          shippingFee: p.shippingFee,
          taxTotal: p.taxTotal,
          grandTotal: p.grandTotal,
          customerNote: dto.customerNote,
          needsApproval,
          items: {
            create: quote.lines.map((l, i) => ({
              variantId: l.variantId,
              productName: l.productName,
              sku: l.sku,
              size: l.size,
              color: l.color,
              quantity: l.quantity,
              unitPrice: p.lines[i].unitPrice,
              discount: p.lines[i].discount,
              taxRate: p.lines[i].taxRate,
              taxAmount: p.lines[i].taxAmount,
              lineTotal: p.lines[i].lineTotal,
              // Provisional; replaced with the actual issue cost when shipped.
              unitCost: l.avgCost,
            })),
          },
          statusHistory: { create: { toStatus: OrderStatus.PENDING } },
        },
      });

      if (quote.coupon) {
        await tx.couponUsage.create({ data: { couponId: quote.coupon.id, customerId, orderId: order.id } });
      }
      const cart = await tx.cart.findUniqueOrThrow({ where: { customerId } });
      await tx.cartItem.deleteMany({ where: { cartId: cart.id } });
      await tx.cart.update({ where: { id: cart.id }, data: { couponCode: null } });
      if (needsApproval) {
        const owed = await tx.customerAccountEntry.aggregate({ where: { customerId }, _sum: { baseAmount: true } });
        // Orders still waiting for approval count against the limit too.
        const waiting = await tx.order.aggregate({
          where: { customerId, needsApproval: true, approvedAt: null, status: OrderStatus.PENDING, id: { not: order.id } },
          _sum: { grandTotal: true },
        });
        if (dec(owed._sum.baseAmount).add(dec(waiting._sum.grandTotal)).add(order.grandTotal).gt(buyer.creditLimit!)) {
          throw new UnprocessableEntityException('This order exceeds your credit limit');
        }
      } else if (dto.paymentMethod === PaymentMethod.CUSTOMER_ACCOUNT) {
        // Paid from the wallet; credit in other currencies is changed into the order's currency.
        const due = toCurrency(order.grandTotal, currency, exchangeRate);
        if (!buyer.isMerchant) await this.accounts.cover(tx, customerId, currency, due, `دفع الطلب ${order.number}`);
        const { order: paid } = await this.payments.receive(
          tx,
          order,
          { method: PaymentMethod.CUSTOMER_ACCOUNT, currency, amount: due },
          null,
          { allowCredit: buyer.isMerchant },
        );
        await this.autoConfirm(tx, paid, null);
      }
      return order;
    });
    void this.notifications.notifyStaff('orders.update_status', {
      type: 'ORDER_STATUS',
      title: placed.needsApproval ? 'طلب تاجر بالآجل بانتظار موافقتك' : 'طلب جديد 🛍️',
      body: `${placed.number} · ${orderAmount(placed, placed.grandTotal)}`,
      data: { orderId: placed.id, order: placed.number },
    });
    return this.customerOrderById(customerId, placed.id);
  }

  // -------------------------------------------------------- customer side ---

  async customerOrders(customerId: string, q: { page: number; pageSize: number; skip: number }) {
    const where = { customerId };
    const [items, total] = await Promise.all([
      this.prisma.order.findMany({
        where,
        select: {
          id: true,
          number: true,
          status: true,
          paymentStatus: true,
          grandTotal: true,
          currency: true,
          exchangeRate: true,
          placedAt: true,
          _count: { select: { items: true } },
        },
        orderBy: { placedAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.order.count({ where }),
    ]);
    return paginated(
      items.map((o) => ({ ...o, grandTotalInCurrency: toCurrency(o.grandTotal, o.currency, o.exchangeRate) })),
      total,
      q,
    );
  }

  async customerOrder(customerId: string, number: string) {
    const order = await this.prisma.order.findFirst({ where: { number, customerId }, select: customerOrderSelect });
    if (!order) throw new NotFoundException('Order not found');
    return withCurrencyTotals(order);
  }

  private async customerOrderById(customerId: string, id: string) {
    return withCurrencyTotals(await this.prisma.order.findFirstOrThrow({ where: { id, customerId }, select: customerOrderSelect }));
  }

  async customerCancel(customerId: string, number: string, reason: string) {
    const found = await this.prisma.order.findFirst({ where: { number, customerId }, select: { id: true } });
    if (!found) throw new NotFoundException('Order not found');
    await this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, found.id);
      if (!CUSTOMER_CANCELLABLE.includes(order.status)) {
        throw new ForbiddenException('This order can no longer be cancelled; please contact support');
      }
      await this.cancel(tx, order, reason, null, { notify: false });
    });
    return this.customerOrder(customerId, number);
  }

  // ----------------------------------------------------------- admin side ---

  async adminList(q: AdminOrdersQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const where: Prisma.OrderWhereInput = {
      status: q.status,
      paymentStatus: q.paymentStatus,
      channel: q.channel,
      cashierId: q.cashierId,
      ...(q.awaitingApproval && { needsApproval: true, approvedAt: null, status: OrderStatus.PENDING }),
      placedAt: {
        gte: q.from ? parseDateOnly(q.from) : undefined,
        lt: q.to ? new Date(parseDateOnly(q.to).getTime() + 86_400_000) : undefined,
      },
      ...(contains && {
        OR: [{ number: contains }, { customer: { OR: [{ name: contains }, { phone: contains }] } }],
      }),
    };
    const [items, total] = await Promise.all([
      this.prisma.order.findMany({
        where,
        include: { customer: { select: { id: true, name: true, phone: true } }, _count: { select: { items: true } } },
        orderBy: { placedAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.order.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  /**
   * The photo of the shipping receipt (سند الإرسال) for an app order: saved privately for
   * the order's customer, who is notified and sees it on the order in the app.
   */
  async attachShippingReceipt(id: string, file: UploadedPart | undefined, userId: string) {
    const order = await this.prisma.order.findUnique({ where: { id }, select: { id: true, number: true, customerId: true, channel: true } });
    if (!order) throw new NotFoundException('Order not found');
    if (order.channel !== OrderChannel.ONLINE) throw new BadRequestException('Only app orders are shipped');
    const saved = await this.files.saveImage(file, { customerId: order.customerId, isPublic: false });
    await this.prisma.$transaction(async (tx) => {
      await tx.order.update({ where: { id }, data: { shippingReceiptFileId: saved.id } });
      await this.notifications.notifyCustomer(tx, order.customerId, {
        type: NotificationType.ORDER_STATUS,
        title: 'تم شحن طلبك',
        body: `أرفقنا صورة سند الإرسال لطلبك ${order.number}`,
        data: { orderNumber: order.number, shippingReceiptFileId: saved.id },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'Order', entityId: id, after: { shippingReceiptFileId: saved.id } }, tx);
    });
    return this.adminGet(id);
  }

  async adminGet(id: string) {
    const order = await this.prisma.order.findUnique({ where: { id }, include: adminOrderInclude });
    if (!order) throw new NotFoundException('Order not found');
    const returns = await this.prisma.salesReturn.findMany({
      where: { orderId: id },
      select: { id: true, number: true, status: true, refundAmount: true, requestedAt: true },
      orderBy: { requestedAt: 'asc' },
    });
    const journalEntries = await this.prisma.journalEntry.findMany({
      where: { sourceId: { in: [order.id, ...order.payments.map((p) => p.id), ...returns.map((r) => r.id)] } },
      include: { lines: { orderBy: { lineNo: 'asc' }, include: { account: { select: { code: true, nameAr: true, nameEn: true } } } } },
      orderBy: { createdAt: 'asc' },
    });
    const costOfGoods = money(sum(order.items.map((i) => dec(i.unitCost).mul(i.quantity))));
    const netRevenue = dec(order.grandTotal).sub(order.taxTotal);
    return {
      ...withCurrencyTotals(order),
      balanceDue: balanceDue(order),
      returns,
      profit: { netRevenue, costOfGoods, grossProfit: netRevenue.sub(costOfGoods) },
      journalEntries,
    };
  }

  async changeStatus(id: string, dto: ChangeOrderStatusDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, id);
      if (order.needsApproval && !order.approvedAt && dto.status !== OrderStatus.CANCELLED) {
        throw new UnprocessableEntityException('وافق على طلب التاجر أولاً');
      }
      if (!TRANSITIONS[order.status].includes(dto.status)) {
        throw new UnprocessableEntityException(`Cannot change order from ${order.status} to ${dto.status}`);
      }
      switch (dto.status) {
        case OrderStatus.CANCELLED:
          return this.cancel(tx, order, dto.note ?? 'Cancelled by staff', userId);
        case OrderStatus.SHIPPED:
          return this.ship(tx, order, dto, userId);
        case OrderStatus.DELIVERED:
          return this.deliver(tx, order, dto.note, userId);
        case OrderStatus.RETURNED:
          return this.refuseDelivery(tx, order, dto.note ?? 'Delivery refused', userId);
        default:
          return this.transition(tx, order, dto.status, dto.note, userId, {
            confirmedAt: dto.status === OrderStatus.CONFIRMED ? new Date() : undefined,
          });
      }
    });
    return this.adminGet(id);
  }

  async recordPayment(id: string, dto: RecordPaymentDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, id);
      const { order: updated } = await this.payments.receive(tx, order, dto, userId);
      await this.autoConfirm(tx, updated, userId);
    });
    return this.adminGet(id);
  }

  async refund(id: string, dto: RefundDto, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, id);
      await this.payments.refund(tx, order, dto, userId);
    });
    return this.adminGet(id);
  }

  // ------------------------------------------------ merchant approvals ---

  /**
   * The owner approves a merchant's order on credit: it is charged to the merchant's account
   * (paid from their wallet first, the rest owed) and confirmed.
   */
  async approve(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.pendingApproval(tx, id);
      await tx.order.update({ where: { id }, data: { approvedAt: new Date(), approvedById: userId } });
      let current = await tx.order.findUniqueOrThrow({ where: { id } });
      if (order.paymentMethod === PaymentMethod.CUSTOMER_ACCOUNT) {
        current = (
          await this.payments.receive(
            tx,
            current,
            { method: PaymentMethod.CUSTOMER_ACCOUNT, currency: order.currency, amount: toCurrency(balanceDue(order), order.currency, order.exchangeRate) },
            userId,
            { allowCredit: true },
          )
        ).order;
      }
      await this.transition(tx, current, OrderStatus.CONFIRMED, 'وافق المالك على طلب التاجر', userId, { confirmedAt: new Date() });
      await this.audit.log({ userId, action: 'APPROVE', entityType: 'Order', entityId: id }, tx);
    });
    return this.adminGet(id);
  }

  async reject(id: string, reason: string | undefined, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.pendingApproval(tx, id);
      await this.cancel(tx, order, reason?.trim() || 'رفض المالك الطلب', userId);
    });
    return this.adminGet(id);
  }

  /**
   * Changes quantities (0 removes a line) before approving. Stock reserved follows; each line
   * keeps its price and its per-piece discount, and the totals are worked out again.
   */
  async editItems(id: string, lines: { itemId: string; quantity: number }[], userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const order = await this.pendingApproval(tx, id);
      const items = await tx.orderItem.findMany({ where: { orderId: id }, orderBy: { variantId: 'asc' } });
      const wanted = new Map(lines.map((l) => [l.itemId, l.quantity]));
      for (const l of lines) if (!items.some((i) => i.id === l.itemId)) throw new NotFoundException('Order line not found');
      if (items.every((i) => (wanted.get(i.id) ?? i.quantity) === 0)) throw new UnprocessableEntityException('Keep at least one piece, or reject the order');
      for (const i of items) {
        const q = wanted.get(i.id);
        if (q === undefined || q === i.quantity) continue;
        if (q > i.quantity) await this.stock.reserve(tx, i.variantId, order.warehouseId, q - i.quantity);
        else await this.stock.release(tx, i.variantId, order.warehouseId, i.quantity - q);
        if (q === 0) {
          await tx.orderItem.delete({ where: { id: i.id } });
          continue;
        }
        const per = (v: Prisma.Decimal) => dec(v).div(i.quantity).mul(q);
        await tx.orderItem.update({
          where: { id: i.id },
          data: { quantity: q, discount: money(per(i.discount)), taxAmount: money(per(i.taxAmount)), lineTotal: money(per(i.lineTotal)) },
        });
      }
      const now = await tx.orderItem.findMany({ where: { orderId: id } });
      const subtotal = money(now.reduce((t, i) => t.add(dec(i.unitPrice).mul(i.quantity)), dec(0)));
      const lineDiscounts = now.reduce((t, i) => t.add(dec(i.discount)), dec(0));
      const taxTotal = money(now.reduce((t, i) => t.add(dec(i.taxAmount)), dec(0)));
      const linesTotal = now.reduce((t, i) => t.add(dec(i.lineTotal)), dec(0));
      const before = items.reduce((t, i) => t.add(dec(i.lineTotal)), dec(0));
      // An order-wide discount (coupon) shrinks with the order.
      const orderDiscount = dec(order.discountTotal).sub(items.reduce((t, i) => t.add(dec(i.discount)), dec(0)));
      const scaledOrderDiscount = before.gt(0) && orderDiscount.gt(0) ? money(orderDiscount.mul(linesTotal).div(before)) : dec(0);
      const grandTotal = money(linesTotal.sub(scaledOrderDiscount).add(order.shippingFee));
      await tx.order.update({
        where: { id },
        data: { subtotal, discountTotal: money(lineDiscounts.add(scaledOrderDiscount)), taxTotal, grandTotal },
      });
      await this.audit.log({ userId, action: 'UPDATE', entityType: 'Order', entityId: id, before: { grandTotal: order.grandTotal }, after: { lines, grandTotal } }, tx);
    });
    return this.adminGet(id);
  }

  private async pendingApproval(tx: Tx, id: string) {
    const order = await this.lockOrder(tx, id);
    if (!order.needsApproval || order.approvedAt) throw new UnprocessableEntityException('This order is not waiting for approval');
    if (order.status !== OrderStatus.PENDING) throw new UnprocessableEntityException(`Order is ${order.status.toLowerCase()}`);
    return order;
  }

  // -------------------------------------------------------- card payments ---

  /** Opens a card payment attempt on the customer's own order. */
  async openCardPayment(customerId: string, number: string, gateway: string) {
    const found = await this.prisma.order.findFirst({ where: { number, customerId }, select: { id: true } });
    if (!found) throw new NotFoundException('Order not found');
    return this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, found.id);
      const payment = await this.payments.openGatewayAttempt(tx, order, gateway);
      const customer = await tx.customer.findUniqueOrThrow({ where: { id: customerId }, select: { name: true, phone: true, email: true } });
      return { payment, order, customer };
    });
  }

  /** Applies a verified gateway result. Safe to call repeatedly for the same attempt. */
  async applyGatewayResult(result: GatewayResult): Promise<boolean> {
    const attempt = await this.prisma.payment.findUnique({ where: { id: result.paymentId }, select: { orderId: true } });
    if (!attempt) return false;
    return this.prisma.$transaction(async (tx) => {
      const order = await this.lockOrder(tx, attempt.orderId);
      const done = await this.payments.confirmGatewayAttempt(tx, order, result.paymentId, result);
      if (done) await this.autoConfirm(tx, done.order, null);
      return done !== null;
    });
  }

  /** Cancels card orders nobody paid for within [minutes], so their stock goes back on sale. */
  async expireUnpaidCardOrders(minutes: number, now = new Date()): Promise<string[]> {
    const cutoff = new Date(now.getTime() - minutes * 60_000);
    const stale = await this.prisma.order.findMany({
      where: {
        status: OrderStatus.PENDING,
        paymentMethod: PaymentMethod.CARD,
        paymentStatus: PaymentStatus.UNPAID,
        placedAt: { lt: cutoff },
        // A checkout opened recently gets its full window too.
        payments: { none: { status: TransactionStatus.PENDING, createdAt: { gte: cutoff } } },
      },
      select: { id: true },
      take: 100,
    });
    const cancelled: string[] = [];
    for (const { id } of stale) {
      await this.prisma.$transaction(async (tx) => {
        const order = await this.lockOrder(tx, id);
        if (order.status !== OrderStatus.PENDING || order.paymentStatus !== PaymentStatus.UNPAID) return;
        await tx.payment.updateMany({
          where: { orderId: id, status: TransactionStatus.PENDING },
          data: { status: TransactionStatus.CANCELLED },
        });
        await this.cancel(tx, order, 'Card payment not completed in time', null);
        cancelled.push(order.number);
      });
    }
    return cancelled;
  }

  // ------------------------------------------------------ state handlers ---

  private async transition(
    tx: Tx,
    order: Order,
    to: OrderStatus,
    note: string | undefined,
    userId: string | null,
    extra: Prisma.OrderUpdateInput = {},
    message: { notify?: boolean; carrier?: string | null; trackingNumber?: string | null; reason?: string | null } = {},
  ) {
    await tx.order.update({ where: { id: order.id }, data: { status: to, ...extra } });
    const update = message.notify === false ? null : orderStatusMessage(order, to, message);
    if (update) await this.notifications.notifyCustomer(tx, order.customerId, update);
    await tx.orderStatusHistory.create({
      data: { orderId: order.id, fromStatus: order.status, toStatus: to, note, changedById: userId },
    });
    await this.audit.log(
      { userId, action: 'UPDATE', entityType: 'Order', entityId: order.id, before: { status: order.status }, after: { status: to, note } },
      tx,
    );
  }

  /** Releases reserved stock and gives the coupon use back. Money stays as a customer advance until refunded. */
  private async cancel(tx: Tx, order: Order, reason: string, userId: string | null, opts: { notify?: boolean } = {}) {
    const items = await tx.orderItem.findMany({ where: { orderId: order.id }, orderBy: { variantId: 'asc' } });
    for (const item of items) {
      await this.stock.release(tx, item.variantId, order.warehouseId, item.quantity);
    }
    if (order.couponId) {
      await tx.couponUsage.deleteMany({ where: { orderId: order.id } });
      await tx.coupon.update({ where: { id: order.couponId }, data: { usedCount: { decrement: 1 } } });
    }
    await this.transition(
      tx,
      order,
      OrderStatus.CANCELLED,
      reason,
      userId,
      { cancelledAt: new Date(), cancelReason: reason },
      { notify: opts.notify, reason },
    );
  }

  /**
   * Issues stock and recognises the sale:
   *   Dr AR / Dr Sales discounts  —  Cr Sales / Cr VAT output / Cr Shipping income
   *   Dr COGS                     —  Cr Inventory
   *   Dr Customer advances        —  Cr AR          (for anything prepaid)
   */
  private async ship(tx: Tx, order: Order, dto: ChangeOrderStatusDto, userId: string) {
    const items = await tx.orderItem.findMany({ where: { orderId: order.id }, orderBy: { variantId: 'asc' } });
    const costs = [];
    for (const item of items) {
      const issued = await this.stock.stockOut(tx, {
        type: StockMovementType.SALE,
        variantId: item.variantId,
        warehouseId: order.warehouseId,
        quantity: item.quantity,
        fromReserved: true,
        sourceType: 'ORDER',
        sourceId: order.id,
        note: order.number,
        userId,
      });
      await tx.orderItem.update({ where: { id: item.id }, data: { unitCost: issued.unitCost } });
      costs.push(issued.value);
    }
    const cogs = sum(costs);
    const r = revenueSplit({
      grandTotal: dec(order.grandTotal),
      discountTotal: dec(order.discountTotal),
      shippingFee: dec(order.shippingFee),
      taxTotal: dec(order.taxTotal),
    });
    const prepaid = dec(order.amountPaid);
    const customerId = order.customerId;

    await this.ledger.post(tx, {
      description: `Sales invoice for order ${order.number}`,
      sourceType: 'SALES_ORDER',
      sourceId: order.id,
      userId,
      lines: [
        { account: 'AR', debit: r.receivable, customerId },
        { account: 'SALES_DISCOUNTS', debit: r.discounts },
        { account: 'SALES', credit: r.sales },
        { account: 'VAT_OUTPUT', credit: r.tax },
        { account: 'SHIPPING_INCOME', credit: r.shipping },
        { account: 'COGS', debit: cogs },
        { account: 'INVENTORY', credit: cogs },
        { account: 'CUSTOMER_ADVANCES', debit: prepaid, customerId },
        { account: 'AR', credit: prepaid, customerId },
      ],
    });

    const isCod = order.paymentMethod === PaymentMethod.CASH_ON_DELIVERY;
    await tx.shipment.create({
      data: {
        orderId: order.id,
        carrier: dto.carrier ?? 'in-house',
        trackingNumber: dto.trackingNumber,
        status: ShipmentStatus.IN_TRANSIT,
        codAmount: isCod ? money(dec(order.grandTotal).sub(prepaid)) : 0,
        shippedAt: new Date(),
      },
    });
    await this.transition(tx, order, OrderStatus.SHIPPED, dto.note, userId, {}, {
      carrier: dto.carrier,
      trackingNumber: dto.trackingNumber,
    });
  }

  /** On delivery, cash collected by the courier becomes a receivable from the courier. */
  private async deliver(tx: Tx, order: Order, note: string | undefined, userId: string) {
    await tx.shipment.updateMany({
      where: { orderId: order.id, status: { not: ShipmentStatus.DELIVERED } },
      data: { status: ShipmentStatus.DELIVERED, deliveredAt: new Date() },
    });
    const due = dec(order.grandTotal).sub(order.amountPaid);
    let current = order;
    if (order.paymentMethod === PaymentMethod.CASH_ON_DELIVERY && due.gt(0)) {
      current = (await this.payments.receive(tx, order, { method: PaymentMethod.CASH_ON_DELIVERY, amount: due }, userId)).order;
    }
    await this.transition(tx, current, OrderStatus.DELIVERED, note, userId, { deliveredAt: new Date() });
  }

  /**
   * The courier brings the parcel back undelivered: the sales invoice is reversed
   * (revenue, VAT, AR and COGS) and the goods go back on the shelf at their issue
   * cost. Anything prepaid becomes a customer advance again and can be refunded.
   */
  private async refuseDelivery(tx: Tx, order: Order, reason: string, userId: string) {
    const invoice = await tx.journalEntry.findFirst({
      where: { sourceType: 'SALES_ORDER', sourceId: order.id, status: 'POSTED' },
    });
    if (!invoice) throw new UnprocessableEntityException(`No posted sales invoice found for ${order.number}`);
    await this.ledger.reverse(tx, invoice.id, userId, `Delivery refused: ${reason}`);

    const items = await tx.orderItem.findMany({ where: { orderId: order.id }, orderBy: { variantId: 'asc' } });
    for (const item of items) {
      await this.stock.stockIn(tx, {
        type: StockMovementType.SALES_RETURN,
        variantId: item.variantId,
        warehouseId: order.warehouseId,
        quantity: item.quantity,
        unitCost: item.unitCost,
        sourceType: 'ORDER',
        sourceId: order.id,
        note: `${order.number} refused`,
        userId,
      });
    }
    await tx.shipment.updateMany({
      where: { orderId: order.id },
      data: { status: ShipmentStatus.RETURNED_TO_SENDER, codAmount: 0 },
    });
    await this.transition(tx, order, OrderStatus.RETURNED, reason, userId);
  }

  private async autoConfirm(tx: Tx, order: Order, userId: string | null) {
    if (order.status !== OrderStatus.PENDING || order.paymentStatus !== PaymentStatus.PAID) return;
    if (!(await getSetting(tx, 'orders.autoConfirmPaid', true))) return;
    await this.transition(tx, order, OrderStatus.CONFIRMED, 'Auto-confirmed after full payment', userId, {
      confirmedAt: new Date(),
    });
  }

  /** Serialises all state changes of one order. */
  private async lockOrder(tx: Tx, id: string): Promise<Order> {
    const rows = await tx.$queryRaw<{ id: string }[]>`SELECT id FROM orders WHERE id = ${id}::uuid FOR UPDATE`;
    if (!rows.length) throw new NotFoundException('Order not found');
    return tx.order.findUniqueOrThrow({ where: { id } });
  }
}

type Amounts = Pick<
  Order,
  'currency' | 'exchangeRate' | 'subtotal' | 'discountTotal' | 'shippingFee' | 'taxTotal' | 'grandTotal' | 'amountPaid' | 'returnedTotal'
>;

/**
 * Adds the invoice in the order's own currency, at the rate locked at checkout.
 * Each figure is converted on its own (see toCurrency), exactly as the apps do.
 */
function withCurrencyTotals<T extends Amounts>(order: T) {
  const c = (v: Prisma.Decimal) => toCurrency(v, order.currency, order.exchangeRate);
  return {
    ...order,
    inCurrency: {
      currency: order.currency,
      exchangeRate: order.exchangeRate,
      subtotal: c(order.subtotal),
      discountTotal: c(order.discountTotal),
      shippingFee: c(order.shippingFee),
      taxTotal: c(order.taxTotal),
      grandTotal: c(order.grandTotal),
      amountPaid: c(order.amountPaid),
      balanceDue: c(balanceDue(order)),
    },
  };
}

function snapshotAddress(a: Prisma.CustomerAddressGetPayload<object>) {
  const { id: _id, customerId: _c, createdAt: _cr, updatedAt: _u, isDefault: _d, latitude, longitude, ...rest } = a;
  return { ...rest, latitude: latitude?.toString() ?? null, longitude: longitude?.toString() ?? null };
}

