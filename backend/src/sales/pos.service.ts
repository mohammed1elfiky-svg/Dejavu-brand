import { activeDeals, recordDealUsage, releaseDealUsage } from './deals.db';
import { LoyaltyService } from './loyalty';
import { applyDeals } from './deals';
import { OwnerAlertsService } from '../notifications/owner-alerts.service';
import { BadRequestException, ConflictException, ForbiddenException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import {
  Coupon,
  CouponType,
  OrderChannel,
  OrderStatus,
  PaymentMethod,
  PaymentStatus,
  Prisma,
  ProductStatus,
  ProductVisibility,
  StockMovementType,
  TransactionStatus,
} from '../generated/prisma/client';
import { LedgerService, PostingLine } from '../accounting/ledger.service';
import { activePromotions, buyerPrice, promotedPrice } from '../catalog/promotions';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, formatAmount, inCurrency, toBase, toCurrency } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { Decimal, dec, money, sum, ZERO } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from '../treasury/treasury.service';
import { normalizeReference, TransfersService } from '../treasury/transfers.service';
import { checkCoupon, liveCoupons } from './coupons';
import { PosFreeReturnDto, PosReturnDto, PosSaleDto, PosProductsQueryDto } from './dto/pos.dto';
import { ReturnsService } from './returns.service';
import { ShiftService } from './shift.service';
import { startOfBusinessDay } from '../common/utils/dates';
import { priceOrder, revenueSplit } from './pricing';
import { getSetting } from './settings';

/** Customer code of the anonymous walk-in buyer that cash sales without a customer go to. */
export const WALK_IN_CODE = 'CUS-WALKIN';

const posProductSelect = {
  id: true,
  nameAr: true,
  nameEn: true,
  skuNumber: true,
  basePrice: true,
  wholesalePrice: true,
  posSortOrder: true,
  category: { select: { id: true, nameAr: true } },
  images: { orderBy: { sortOrder: 'asc' }, select: { url: true, color: true } },
  variants: {
    where: { isActive: true },
    orderBy: [{ variantNo: 'asc' }, { sku: 'asc' }],
    select: {
      id: true,
      sku: true,
      barcode: true,
      size: true,
      color: true,
      colorHex: true,
      price: true,
      stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } },
    },
  },
} satisfies Prisma.ProductSelect;

type PosProductRow = Prisma.ProductGetPayload<{ select: typeof posProductSelect }>;

interface Tender {
  currency: string;
  amount: Decimal;
  rate: Decimal;
  sar: Decimal;
  boxAccountId: string;
  method: PaymentMethod;
}

/**
 * In-store sales at the till. A sale is invoiced, paid and handed over at once:
 * stock leaves the default warehouse, the invoice is posted, and the money lands in
 * the cashier's own drawers, in whatever mix of currencies the customer paid in.
 * What is not paid on a credit sale goes on the customer's account.
 */
@Injectable()
export class PosService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly stock: StockService,
    private readonly ledger: LedgerService,
    private readonly treasury: TreasuryService,
    private readonly currencies: CurrencyService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly returns: ReturnsService,
    private readonly shifts: ShiftService,
    private readonly ownerAlerts: OwnerAlertsService,
    private readonly transfers: TransfersService,
    private readonly loyalty: LoyaltyService,
  ) {}

  // -------------------------------------------------------------- catalog ---

  /** Products for the cashier grid, in the order the store arranged them. */
  async products(q: PosProductsQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const rows = await this.prisma.product.findMany({
      where: {
        status: ProductStatus.ACTIVE,
        visibility: { not: ProductVisibility.HIDDEN },
        categoryId: q.categoryId,
        ...(contains && {
          OR: [
            { nameAr: contains },
            { nameEn: contains },
            { variants: { some: { OR: [{ sku: contains }, { barcode: q.q }] } } },
          ],
        }),
      },
      select: posProductSelect,
      orderBy: [{ posSortOrder: 'asc' }, { createdAt: 'desc' }],
      take: 500,
    });
    const promos = await activePromotions(this.prisma, rows.map((r) => r.id));
    return rows.map((r) => presentPosProduct(r, promos));
  }

  /** Finds what a scanned code belongs to: a variant SKU, a factory barcode, or a product SKU number. */
  async lookup(code: string) {
    const term = code.trim();
    const variant = await this.prisma.productVariant.findFirst({
      where: { isActive: true, product: { status: ProductStatus.ACTIVE, visibility: { not: ProductVisibility.HIDDEN } }, OR: [{ sku: { equals: term, mode: 'insensitive' } }, { barcode: term }] },
      select: { id: true, productId: true },
    });
    const productId =
      variant?.productId ??
      (/^\d{3,6}$/.test(term)
        ? (await this.prisma.product.findFirst({ where: { skuNumber: Number(term), status: ProductStatus.ACTIVE, visibility: { not: ProductVisibility.HIDDEN } }, select: { id: true } }))?.id
        : undefined);
    if (!productId) throw new NotFoundException(`No product matches ${term}`);
    const row = await this.prisma.product.findUniqueOrThrow({ where: { id: productId }, select: posProductSelect });
    const product = presentPosProduct(row, await activePromotions(this.prisma, [productId]));
    return { product, variantId: variant?.id ?? (product.variants.length === 1 ? product.variants[0].id : null) };
  }

  /** Saves the cashier grid order: products listed first show first. */
  async arrange(productIds: string[], userId: string) {
    await this.prisma.$transaction([
      ...productIds.map((id, i) => this.prisma.product.update({ where: { id }, data: { posSortOrder: i + 1 }, select: { id: true } })),
      // Products left out (e.g. not yet shown on the till) go after the arranged ones.
      this.prisma.product.updateMany({ where: { id: { notIn: productIds } }, data: { posSortOrder: productIds.length + 1 } }),
    ]);
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'PosProductOrder', after: { count: productIds.length } });
    return { arranged: productIds.length };
  }

  async couponsAvailable() {
    return { available: (await liveCoupons(this.prisma)) > 0 };
  }

  async checkCouponCode(code: string, customerId?: string) {
    if (!code.trim()) throw new BadRequestException('Type the coupon code');
    const walkIn = !customerId || customerId === (await this.walkInId(this.prisma as unknown as Tx));
    // The minimum order is checked on the sale itself.
    const c = await checkCoupon(this.prisma, code.trim(), walkIn ? null : customerId!, '999999999');
    if (typeof c === 'string') throw new UnprocessableEntityException(c);
    return { code: c.code, type: c.type, value: c.value, maxDiscount: c.maxDiscount, minOrderAmount: c.minOrderAmount };
  }

  // ----------------------------------------------------------------- sale ---

  async sell(dto: PosSaleDto, cashierId: string) {
    // A sale queued offline and sent again is booked once.
    if (dto.clientRef) {
      const seen = await this.prisma.order.findUnique({ where: { clientRef: dto.clientRef }, select: { id: true, cashierId: true } });
      if (seen) {
        if (seen.cashierId !== cashierId) throw new ConflictException('This sale was already sent by another cashier');
        return this.receipt(seen.id);
      }
    }
    await this.treasury.ensureCashierBoxes(cashierId);
    const slip = await this.readSlip(dto);
    const orderId = await this.prisma.$transaction((tx) => this.sellIn(tx, slip, cashierId));
    return this.receipt(orderId);
  }

  /** A transfer paid with a slip photo: its reference number is read off the photo (Gemini). */
  private async readSlip<T extends PosSaleDto>(dto: T): Promise<T & { referenceExtracted?: boolean }> {
    const byTransfer = dto.paymentMethod === 'BANK_TRANSFER' || (dto.payments ?? []).some((p) => p.method === 'BANK_TRANSFER');
    if (!byTransfer || (!dto.slipFileId && !dto.reference)) return dto;
    const r = await this.transfers.referenceOf(dto.slipFileId, dto.reference);
    return { ...dto, reference: r.extracted ? r.reference! : dto.reference, referenceExtracted: r.extracted };
  }

  /**
   * The offers a basket gets at the till ("قطعة + قطعة مجاناً", sets), so the cashier sees the
   * discount before taking the money. SAR amounts; the sale itself applies the same offers.
   */
  async quote(dto: { items: { variantId: string; quantity: number }[]; customerId?: string }) {
    const quantities = new Map<string, number>();
    for (const i of dto.items) quantities.set(i.variantId, (quantities.get(i.variantId) ?? 0) + i.quantity);
    const variants = await this.prisma.productVariant.findMany({
      where: { id: { in: [...quantities.keys()] } },
      select: { id: true, price: true, product: { select: { id: true, basePrice: true, wholesalePrice: true } } },
    });
    const buyer = dto.customerId ? await this.prisma.customer.findUnique({ where: { id: dto.customerId }, select: { isMerchant: true, merchantDiscount: true } }) : null;
    const promos = await activePromotions(this.prisma, variants.map((v) => v.product.id));
    const lines = variants.map((v) => ({
      variantId: v.id,
      productId: v.product.id,
      quantity: quantities.get(v.id)!,
      unitPrice: buyerPrice(v.price ?? v.product.basePrice, v.product.wholesalePrice, promos.get(v.product.id), buyer),
    }));
    const offers = buyer?.isMerchant ? null : applyDeals(lines, await activeDeals(this.prisma, lines.map((l) => l.productId), 'pos', new Date(), dto.customerId));
    const subtotal = money(sum(lines.map((l) => dec(l.unitPrice).mul(l.quantity))));
    const dealDiscount = offers?.total ?? ZERO;
    return {
      subtotal,
      dealDiscount,
      total: money(subtotal.sub(dealDiscount)),
      deals: offers?.applied ?? [],
      lines: lines.map((l, i) => ({ variantId: l.variantId, unitPrice: money(l.unitPrice), quantity: l.quantity, discount: offers?.lineDiscounts[i] ?? ZERO })),
    };
  }

  /** Rings up a sale inside the caller's transaction; returns the new order's id. */
  async sellIn(tx: Tx, dto: PosSaleDto & { fromAccountMax?: Decimal; referenceExtracted?: boolean }, cashierId: string): Promise<string> {
    {
      const invoiceCurrency = await this.currencies.requireActive(dto.currency ?? BASE_CURRENCY, tx);
      const customerId = await this.customerFor(tx, dto);
      const warehouse = await tx.warehouse.findFirst({ where: { isDefault: true, isActive: true } });
      if (!warehouse) throw new UnprocessableEntityException('No default warehouse is configured');

      // ---- price the basket (promotions, then the invoice discount) ----
      const quantities = new Map<string, number>();
      for (const i of dto.items) quantities.set(i.variantId, (quantities.get(i.variantId) ?? 0) + i.quantity);
      const variants = await tx.productVariant.findMany({
        where: { id: { in: [...quantities.keys()] } },
        include: {
          product: { select: { id: true, nameAr: true, basePrice: true, wholesalePrice: true, status: true, visibility: true, taxRate: { select: { rate: true, isActive: true } } } },
        },
        orderBy: { id: 'asc' },
      });
      if (variants.length !== quantities.size) throw new NotFoundException('Some items were not found');
      const inactive = variants.find((v) => !v.isActive || v.product.status !== ProductStatus.ACTIVE || v.product.visibility === ProductVisibility.HIDDEN);
      if (inactive) throw new UnprocessableEntityException(`${inactive.sku} is not for sale`);
      const promos = await activePromotions(tx, variants.map((v) => v.product.id));
      // Merchants pay wholesale prices less their own discount.
      const buyer = await tx.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true, merchantDiscount: true } });
      const rate = invoiceCurrency.rate;
      const basket = variants.map((v) => ({
        variantId: v.id,
        quantity: quantities.get(v.id)!,
        unitPrice: buyerPrice(v.price ?? v.product.basePrice, v.product.wholesalePrice, promos.get(v.product.id), buyer),
        taxRate: v.product.taxRate?.isActive ? v.product.taxRate.rate : 0,
      }));
      const pricesIncludeTax = await getSetting(tx, 'tax.pricesIncludeTax', true);
      const discount = this.discountOf(dto, invoiceCurrency.code, rate);
      // Quantity offers ("قطعة + قطعة مجاناً"); not for merchants, nor for sales rung up offline
      // without them (the device could not see the offer, so the customer paid the full price).
      const offers =
        buyer?.isMerchant || dto.applyDeals === false
          ? null
          : applyDeals(
              basket.map((b) => ({ productId: variants.find((v) => v.id === b.variantId)!.product.id, quantity: b.quantity, unitPrice: b.unitPrice })),
              await activeDeals(tx, variants.map((v) => v.product.id), 'pos', new Date(), customerId),
            );
      // A coupon code (only when the store has live coupons) instead of a typed discount.
      let coupon: Coupon | null = null;
      if (dto.couponCode?.trim()) {
        if (discount) throw new BadRequestException('Use either a coupon or a manual discount');
        const walkIn = customerId === (await this.walkInId(tx));
        const subtotalSar = money(sum(basket.map((b) => dec(b.unitPrice).mul(b.quantity))));
        const check = await checkCoupon(tx, dto.couponCode.trim(), walkIn ? null : customerId, subtotalSar.toString());
        if (typeof check === 'string') throw new UnprocessableEntityException(check);
        coupon = check;
      }
      const pricing = priceOrder({ lines: basket, lineDiscounts: offers?.lineDiscounts, coupon: coupon ?? discount, shippingFee: 0, pricesIncludeTax });
      const total = pricing.grandTotal;

      // ---- the money handed over (first any credit the customer holds, e.g. from a return) ----
      const credit = await this.accountTender(tx, dto, customerId, invoiceCurrency.code, rate, total);
      const tenders = await this.tenders(tx, dto, cashierId, invoiceCurrency.code, rate, total, credit?.sar ?? ZERO);
      if (credit) tenders.unshift(credit);
      let paid = sum(tenders.map((t) => t.sar));
      // A remainder smaller than the smallest coin of the last currency used counts as paid.
      const last = tenders[tenders.length - 1];
      const short = total.sub(paid);
      if (last && short.gt(0) && toCurrency(short, last.currency, last.rate).isZero()) {
        last.sar = last.sar.add(short);
        paid = total;
      }
      let change = paid.gt(total) ? money(paid.sub(total)) : ZERO;
      // Change is handed back in the currency the customer paid with, unless the cashier picks one.
      const handed = [...new Set(tenders.filter((t) => t.method === PaymentMethod.CASH).map((t) => t.currency))];
      const changeCurrency = dto.changeCurrency ?? (handed.length === 1 ? handed[0] : invoiceCurrency.code);
      const changeRate = changeCurrency === invoiceCurrency.code ? rate : await this.currencies.rate(changeCurrency, tx);
      let changeAmount = change.gt(0) ? toCurrency(change, changeCurrency, changeRate) : ZERO;
      if (change.gt(0) && changeAmount.isZero() && last) {
        last.sar = last.sar.sub(change);
        change = ZERO;
      }
      const owed = paid.lt(total) ? money(total.sub(paid)) : ZERO;
      if (owed.gt(0) && dto.saleType !== 'CREDIT') {
        const shown = toCurrency(owed, invoiceCurrency.code, rate);
        throw new UnprocessableEntityException(`Paid amount is short by ${shown.toFixed()} ${invoiceCurrency.code}`);
      }
      if (owed.gt(0) && customerId === (await this.walkInId(tx))) {
        throw new UnprocessableEntityException('Pick the customer to sell on credit');
      }

      // ---- the invoice, then the stock it hands over ----
      const number = await this.sequences.next(tx, 'POS');
      const now = new Date();
      let placedAt = now;
      if (dto.soldAt) {
        placedAt = new Date(dto.soldAt);
        if (placedAt > new Date(now.getTime() + 5 * 60_000) || placedAt < new Date(now.getTime() - 7 * 24 * 3600_000)) {
          throw new BadRequestException('soldAt must be within the last 7 days');
        }
      }
      const order = await tx.order.create({
        data: {
          number,
          customerId,
          warehouseId: warehouse.id,
          channel: OrderChannel.POS,
          cashierId,
          status: OrderStatus.COMPLETED,
          paymentStatus: PaymentStatus.PAID,
          paymentMethod:
            owed.gt(0) && paid.isZero()
              ? PaymentMethod.CUSTOMER_ACCOUNT
              : tenders.every((t) => t.method === PaymentMethod.BANK_TRANSFER)
                ? PaymentMethod.BANK_TRANSFER
                : PaymentMethod.CASH,
          currency: invoiceCurrency.code,
          exchangeRate: rate,
          shippingAddress: { channel: 'POS' },
          couponId: coupon?.id,
          subtotal: pricing.subtotal,
          discountTotal: pricing.discountTotal,
          shippingFee: 0,
          taxTotal: pricing.taxTotal,
          grandTotal: total,
          amountPaid: total,
          internalNote: [dto.note, ...(offers?.applied ?? []).map((a) => `عرض: ${a.name}`)].filter(Boolean).join(' — ') || null,
          placedAt,
          clientRef: dto.clientRef,
          confirmedAt: now,
          deliveredAt: now,
          items: {
            create: variants.map((v, i) => ({
              variantId: v.id,
              productName: v.product.nameAr,
              sku: v.sku,
              size: v.size,
              color: v.color,
              quantity: pricing.lines[i].quantity,
              unitPrice: pricing.lines[i].unitPrice,
              discount: pricing.lines[i].discount,
              taxRate: pricing.lines[i].taxRate,
              taxAmount: pricing.lines[i].taxAmount,
              lineTotal: pricing.lines[i].lineTotal,
              // Replaced with the actual issue cost below.
              unitCost: v.avgCost,
            })),
          },
          statusHistory: { create: { toStatus: OrderStatus.COMPLETED, note: 'POS sale', changedById: cashierId } },
        },
        include: { items: true },
      });
      const costs: Decimal[] = [];
      for (const item of [...order.items].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        const issued = await this.stock.stockOut(tx, {
          type: StockMovementType.SALE,
          variantId: item.variantId,
          warehouseId: warehouse.id,
          quantity: item.quantity,
          sourceType: 'ORDER',
          sourceId: order.id,
          note: number,
          userId: cashierId,
        });
        await tx.orderItem.update({ where: { id: item.id }, data: { unitCost: issued.unitCost } });
        costs.push(issued.value);
      }

      const r = revenueSplit(pricing);
      const cogs = sum(costs);
      await this.ledger.post(tx, {
        description: `POS sale ${number}`,
        sourceType: 'SALES_ORDER',
        sourceId: order.id,
        userId: cashierId,
        lines: [
          { account: 'AR', debit: r.receivable, customerId },
          { account: 'SALES_DISCOUNTS', debit: r.discounts },
          { account: 'SALES', credit: r.sales },
          { account: 'VAT_OUTPUT', credit: r.tax },
          { account: 'COGS', debit: cogs },
          { account: 'INVENTORY', credit: cogs },
        ],
      });

      // ---- payments: tenders, change, and the rest on the customer's account ----
      const lines: PostingLine[] = [];
      const payments: Prisma.PaymentCreateManyInput[] = [];
      for (const t of tenders) {
        lines.push(
          ...(await this.treasury.moneyLines(tx, {
            account: { id: t.boxAccountId },
            customerId: t.method === PaymentMethod.CUSTOMER_ACCOUNT ? customerId : undefined,
            currency: t.currency,
            amount: t.amount,
            sar: t.sar,
          })),
        );
        payments.push({
          ...(await this.payment(tx, order.id, t.method, t.boxAccountId, t.currency, t.amount, t.rate, t.sar)),
          ...(t.method === PaymentMethod.BANK_TRANSFER && (dto.reference || dto.slipFileId)
            ? { gatewayPayload: { ...(dto.reference && { reference: dto.reference }), ...(dto.slipFileId && { slipFileId: dto.slipFileId }) } }
            : {}),
        });
      }
      if (offers?.applied.length) {
        const walkIn = customerId === (await this.walkInId(tx));
        await recordDealUsage(tx, offers.applied, walkIn ? null : customerId, order.id);
      }
      if (coupon) {
        const claimed = await tx.$executeRaw`
          UPDATE coupons SET used_count = used_count + 1
          WHERE id = ${coupon.id}::uuid AND (usage_limit IS NULL OR used_count < usage_limit)`;
        if (!claimed) throw new UnprocessableEntityException('Coupon usage limit reached');
        await tx.couponUsage.create({ data: { couponId: coupon.id, customerId, orderId: order.id } });
      }
      // A transfer goes on the register: the same reference (or slip photo) is refused next time.
      const byTransfer = tenders.filter((t) => t.method === PaymentMethod.BANK_TRANSFER);
      if (byTransfer.length && (dto.reference || dto.slipFileId)) {
        await this.transfers.register(tx, {
          reference: normalizeReference(dto.reference),
          extracted: dto.referenceExtracted,
          source: 'POS',
          customerId,
          currency: byTransfer[0].currency,
          amount: byTransfer.length === 1 ? byTransfer[0].amount : null,
          fileId: dto.slipFileId,
          orderId: order.id,
          userId: cashierId,
        });
      }
      if (change.gt(0)) {
        const box = await this.treasury.defaultBox(tx, changeCurrency, { userId: cashierId });
        const bal = await this.treasury.balanceOf(tx, { boxId: box.id });
        const comingIn = sum(tenders.filter((t) => t.currency === changeCurrency).map((t) => t.amount));
        if (bal.amount.add(comingIn).lt(changeAmount)) {
          throw new UnprocessableEntityException(
            `Not enough ${changeCurrency} in the drawer for the change: ${bal.amount.add(comingIn).toFixed()} available, ${changeAmount.toFixed()} needed`,
          );
        }
        lines.push(...(await this.treasury.moneyLines(tx, { account: { id: box.accountId }, currency: changeCurrency, amount: changeAmount.neg(), sar: change.neg() })));
        payments.push({
          ...(await this.payment(tx, order.id, PaymentMethod.CASH, box.accountId, changeCurrency, changeAmount.neg(), changeRate, change.neg())),
          isChange: true,
        });
      }
      if (owed.gt(0)) {
        const owedInCurrency = toCurrency(owed, invoiceCurrency.code, rate);
        const acc = await tx.account.findUniqueOrThrow({ where: { systemKey: 'CUSTOMER_ACCOUNTS' }, select: { id: true } });
        lines.push(...(await this.treasury.moneyLines(tx, { account: { id: acc.id }, customerId, currency: invoiceCurrency.code, amount: owedInCurrency, sar: owed })));
        payments.push(await this.payment(tx, order.id, PaymentMethod.CUSTOMER_ACCOUNT, acc.id, invoiceCurrency.code, owedInCurrency, rate, owed));
      }
      await tx.payment.createMany({ data: payments });
      await this.ledger.post(tx, {
        description: `Payment for POS sale ${number}`,
        sourceType: 'CUSTOMER_PAYMENT',
        sourceId: order.id,
        userId: cashierId,
        lines: [...lines, { account: 'AR', credit: total, customerId }],
      });
      await this.audit.log({ userId: cashierId, action: 'CREATE', entityType: 'PosSale', entityId: order.id, after: dto }, tx);
      await this.loyalty.earnForOrder(tx, order.id);
      return order.id;
    }
  }

  /**
   * Pays first from the customer's own credit in the invoice currency (wallet, or the
   * value of goods just returned): up to [fromAccount] typed by the cashier, or up to
   * [fromAccountMax] for an exchange.
   */
  private async accountTender(tx: Tx, dto: PosSaleDto & { fromAccountMax?: Decimal }, customerId: string, currency: string, rate: Decimal, total: Decimal) {
    const wanted = dto.fromAccountMax ?? (dto.fromAccount ? inCurrency(dto.fromAccount, currency) : null);
    if (!wanted || !wanted.gt(0)) return null;
    const bal = await this.treasury.balanceOf(tx, { customerId, currency });
    const available = bal.amount.lt(0) ? bal.amount.neg() : ZERO;
    if (dto.fromAccount && wanted.gt(available)) {
      throw new UnprocessableEntityException(`The customer has only ${available.toFixed()} ${currency} of credit`);
    }
    const totalShown = toCurrency(total, currency, rate);
    const amount = Decimal.min(wanted, available, totalShown);
    if (!amount.gt(0)) return null;
    const acc = await tx.account.findUniqueOrThrow({ where: { systemKey: 'CUSTOMER_ACCOUNTS' }, select: { id: true } });
    const sar = amount.eq(totalShown) ? money(total) : toBase(amount, currency, rate);
    return { currency, amount, rate, sar, boxAccountId: acc.id, method: PaymentMethod.CUSTOMER_ACCOUNT } satisfies Tender;
  }

  // ------------------------------------------------------ returns at the till ---

  /** A sale found by its number, with what can still be returned of each line. */
  async findSale(number: string) {
    const order = await this.prisma.order.findUnique({ where: { number: number.trim().toUpperCase() }, select: { id: true } });
    if (!order) throw new NotFoundException('Sale not found');
    const r = await this.receipt(order.id);
    const pending = await this.prisma.salesReturnItem.groupBy({
      by: ['orderItemId'],
      where: { salesReturn: { orderId: order.id, status: { in: ['REQUESTED', 'APPROVED'] } } },
      _sum: { quantity: true },
    });
    return {
      ...r,
      items: r.items.map((i) => ({ ...i, returnableQty: i.quantity - i.returnedQty - (pending.find((p) => p.orderItemId === i.id)?._sum.quantity ?? 0) })),
    };
  }

  /**
   * Return (and optionally exchange) at the till, all in one transaction:
   *  1. the returned pieces go back on the shelf and the sale is credited;
   *  2. without new items, the money goes back to the cashier's drawer or the customer's account;
   *  3. with new items, their sale is paid first from the returned value, the customer pays any
   *     difference with the usual tenders, and anything left over is paid out (or kept as credit).
   */
  async returnOrExchange(dto: PosReturnDto, cashierId: string) {
    await this.treasury.ensureCashierBoxes(cashierId);
    const result = await this.prisma.$transaction(async (tx) => {
      const order = await tx.order.findUnique({ where: { id: dto.orderId } });
      if (!order) throw new NotFoundException('Sale not found');
      const walkIn = order.customerId === (await this.walkInId(tx));
      if (dto.refundTo === 'ACCOUNT' && walkIn) throw new BadRequestException('Pick a named customer to keep the refund as credit');

      const returnId = await this.returns.createIn(
        tx,
        order.id,
        { reason: dto.reason ?? (dto.newItems?.length ? 'استبدال من الكاشير' : 'مرتجع من الكاشير'), items: dto.items.map((i) => ({ orderItemId: i.orderItemId, quantity: i.quantity })) },
        'APPROVED',
        cashierId,
      );
      const lines = await tx.salesReturnItem.findMany({ where: { salesReturnId: returnId }, select: { id: true, orderItemId: true } });
      await this.returns.receiveIn(
        tx,
        returnId,
        { items: lines.map((l) => ({ itemId: l.id, restock: dto.items.find((i) => i.orderItemId === l.orderItemId)?.restock ?? true })) },
        cashierId,
      );

      const exchange = !!dto.newItems?.length;
      const refundCurrency = dto.refundCurrency ?? order.currency;
      // A sale bought on credit is credited back to the account, never paid out in cash.
      const keepOnAccount = exchange || dto.refundTo === 'ACCOUNT' || order.paymentMethod === PaymentMethod.CUSTOMER_ACCOUNT;
      // A credit sale's return just lowers what the customer owes; otherwise money is handed back.
      const refunded = keepOnAccount
        ? await this.returns.refundIn(tx, returnId, { method: PaymentMethod.CUSTOMER_ACCOUNT, currency: order.currency }, cashierId, { allowNothing: true })
        : await this.returns.refundIn(
            tx,
            returnId,
            await (async () => {
              const box = await this.refundBox(tx, dto.refundBoxId, refundCurrency, cashierId);
              return { method: box.kind === 'BANK' ? PaymentMethod.BANK_TRANSFER : PaymentMethod.CASH, currency: refundCurrency, accountId: box.accountId };
            })(),
            cashierId,
            { allowNothing: true },
          );

      let saleId: string | null = null;
      let leftover = refunded.currencyAmount;
      if (exchange) {
        saleId = await this.sellIn(
          tx,
          {
            items: dto.newItems!,
            currency: order.currency,
            saleType: 'CASH',
            customerId: order.customerId,
            payments: dto.payments,
            paymentMethod: dto.paymentMethod,
            reference: dto.reference,
            changeCurrency: dto.changeCurrency,
            note: `Exchange for ${order.number}`,
            fromAccountMax: refunded.currencyAmount,
          },
          cashierId,
        );
        const used = await tx.payment.aggregate({ where: { orderId: saleId, method: PaymentMethod.CUSTOMER_ACCOUNT }, _sum: { currencyAmount: true } });
        leftover = refunded.currencyAmount.sub(dec(used._sum.currencyAmount));
        // What the new pieces did not use is handed back, unless the customer keeps it as credit.
        if (leftover.gt(0) && dto.refundTo !== 'ACCOUNT' && order.paymentMethod !== PaymentMethod.CUSTOMER_ACCOUNT) await this.payOutCredit(tx, order.customerId, order.currency, leftover, refundCurrency, cashierId, order.number, dto.refundBoxId);
      }
      return { returnId, saleId, refunded, leftover };
    });
    return {
      return: await this.returns.get(result.returnId),
      sale: result.saleId ? await this.receipt(result.saleId) : null,
      /** Value of the returned goods paid back / credited (SAR and in the sale's currency). */
      refunded: result.refunded,
      /** For an exchange: returned value not used by the new items (in the sale's currency). */
      leftover: result.leftover,
    };
  }

  /**
   * Return or exchange without the invoice: the pieces are picked by hand and valued at
   * their shelf price (or the price typed). Their value is credited to the customer
   * (Dr sales returns), restocked pieces go back at their average cost (Dr inventory /
   * Cr COGS), then the value pays for new pieces, is handed back as cash, or stays as credit.
   */
  async returnWithoutInvoice(dto: PosFreeReturnDto, cashierId: string) {
    await this.treasury.ensureCashierBoxes(cashierId);
    const result = await this.prisma.$transaction(async (tx) => {
      const walkInId = await this.walkInId(tx);
      const customerId = dto.customerId ?? walkInId;
      if (dto.customerId && !(await tx.customer.findUnique({ where: { id: dto.customerId }, select: { id: true } }))) {
        throw new NotFoundException('Customer not found');
      }
      if (dto.refundTo === 'ACCOUNT' && customerId === walkInId) throw new BadRequestException('Pick a named customer to keep the refund as credit');
      const cur = await this.currencies.requireActive(dto.currency ?? BASE_CURRENCY, tx);
      const warehouse = await tx.warehouse.findFirst({ where: { isDefault: true, isActive: true } });
      if (!warehouse) throw new UnprocessableEntityException('No default warehouse is configured');

      const variants = await tx.productVariant.findMany({
        where: { id: { in: [...new Set(dto.items.map((i) => i.variantId))] } },
        include: { product: { select: { nameAr: true, basePrice: true, costPrice: true } } },
      });
      if (variants.length !== new Set(dto.items.map((i) => i.variantId)).size) throw new NotFoundException('Some items were not found');
      const lines = dto.items.map((i) => {
        const v = variants.find((x) => x.id === i.variantId)!;
        const unit = i.unitPrice != null ? inCurrency(i.unitPrice, cur.code) : toCurrency(v.price ?? v.product.basePrice, cur.code, cur.rate);
        return { ...i, variant: v, value: inCurrency(unit.mul(i.quantity), cur.code) };
      });
      const value = inCurrency(lines.reduce((t, l) => t.add(l.value), dec(0)), cur.code);
      if (!value.gt(0)) throw new BadRequestException('The returned pieces have no value');
      const valueSar = toBase(value, cur.code, cur.rate);
      const number = await this.sequences.next(tx, 'RTN');

      // Restocked pieces return to the shelf at their average cost.
      let restocked = dec(0);
      for (const l of [...lines].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
        if (l.restock === false) continue;
        const cost = dec(l.variant.avgCost).gt(0) ? dec(l.variant.avgCost) : dec(l.variant.product.costPrice);
        const res = await this.stock.stockIn(tx, {
          type: StockMovementType.SALES_RETURN,
          variantId: l.variantId,
          warehouseId: warehouse.id,
          quantity: l.quantity,
          unitCost: cost,
          sourceType: 'SALES_RETURN',
          note: `${number} (return without invoice)`,
          userId: cashierId,
        });
        restocked = restocked.add(res.value);
      }
      await this.ledger.post(tx, {
        description: `Return without invoice ${number}${dto.reason ? `: ${dto.reason}` : ''}`,
        sourceType: 'SALES_RETURN',
        userId: cashierId,
        lines: [
          { account: 'SALES_RETURNS', debit: valueSar },
          ...(await this.treasury.moneyLines(tx, { account: 'CUSTOMER_ACCOUNTS', customerId, currency: cur.code, amount: value.neg(), sar: valueSar.neg() })),
          { account: 'INVENTORY', debit: money(restocked) },
          { account: 'COGS', credit: money(restocked) },
        ],
      });

      let saleId: string | null = null;
      let leftover = value;
      if (dto.newItems?.length) {
        saleId = await this.sellIn(
          tx,
          {
            items: dto.newItems,
            currency: cur.code,
            saleType: 'CASH',
            customerId,
            payments: dto.payments,
            paymentMethod: dto.paymentMethod,
            reference: dto.reference,
            changeCurrency: dto.changeCurrency,
            note: `Exchange for ${number}`,
            fromAccountMax: value,
          },
          cashierId,
        );
        const used = await tx.payment.aggregate({ where: { orderId: saleId, method: PaymentMethod.CUSTOMER_ACCOUNT }, _sum: { currencyAmount: true } });
        leftover = value.sub(dec(used._sum.currencyAmount));
      }
      if (leftover.gt(0) && dto.refundTo !== 'ACCOUNT') {
        await this.payOutCredit(tx, customerId, cur.code, leftover, dto.refundCurrency ?? cur.code, cashierId, number, dto.refundBoxId);
      }
      await this.audit.log({ userId: cashierId, action: 'CREATE', entityType: 'PosFreeReturn', entityId: customerId, after: { number, ...dto } }, tx);
      return { number, value, valueSar, saleId, leftover };
    });
    return {
      number: result.number,
      refunded: { amount: result.valueSar, currencyAmount: result.value },
      sale: result.saleId ? await this.receipt(result.saleId) : null,
      leftover: result.leftover,
      /** Credit kept on the customer's account. */
      keptAsCredit: dto.refundTo === 'ACCOUNT' ? result.leftover : dec(0),
    };
  }

  /** The box a refund is paid from: the one picked (same currency), else the cashier's drawer. */
  private async refundBox(tx: Tx, boxId: string | undefined, currency: string, cashierId: string) {
    if (!boxId) return this.treasury.defaultBox(tx, currency, { userId: cashierId });
    const box = await tx.cashBox.findUnique({ where: { id: boxId } });
    if (!box || !box.isActive) throw new NotFoundException('Cash box not found');
    if (box.currency !== currency) throw new UnprocessableEntityException(`${box.name} holds ${box.currency}, not ${currency}`);
    return box;
  }

  /** Hands credit held on the customer's account back as cash (or a bank transfer) from a box. */
  private async payOutCredit(tx: Tx, customerId: string, currency: string, amount: Decimal, cashCurrency: string, cashierId: string, ref: string, boxId?: string) {
    const rate = await this.currencies.rate(currency, tx);
    const sar = toBase(amount, currency, rate);
    const cashRate = cashCurrency === currency ? rate : await this.currencies.rate(cashCurrency, tx);
    const cash = toCurrency(sar, cashCurrency, cashRate);
    const box = await this.refundBox(tx, boxId, cashCurrency, cashierId);
    const acc = await tx.account.findUniqueOrThrow({ where: { systemKey: 'CUSTOMER_ACCOUNTS' }, select: { id: true } });
    await this.ledger.post(tx, {
      description: `Exchange difference paid back for ${ref}`,
      sourceType: 'CUSTOMER_PAYMENT',
      userId: cashierId,
      lines: [
        ...(await this.treasury.moneyLines(tx, { account: { id: acc.id }, customerId, currency, amount, sar })),
        ...(await this.treasury.moneyLines(tx, { account: { id: box.accountId }, currency: cashCurrency, amount: cash.neg(), sar: sar.neg() })),
      ],
    });
  }

  // ------------------------------------------------- today's invoices ---

  /** Till sales of one business day: the cashier's own, or everyone's for managers. */
  async salesOf(day: { from: Date; to: Date }, user: { id: string; permissions: string[] }, all = false) {
    const everyone = all && user.permissions.includes('cashboxes.manage');
    const rows = await this.prisma.order.findMany({
      where: { channel: OrderChannel.POS, placedAt: { gte: day.from, lt: day.to }, ...(everyone ? {} : { cashierId: user.id }) },
      orderBy: { placedAt: 'desc' },
      select: {
        id: true,
        number: true,
        status: true,
        grandTotal: true,
        currency: true,
        exchangeRate: true,
        paymentMethod: true,
        placedAt: true,
        cancelledAt: true,
        cancelReason: true,
        internalNote: true,
        customer: { select: { id: true, name: true, code: true } },
        cashier: { select: { id: true, name: true } },
        _count: { select: { items: true } },
      },
    });
    return rows.map(({ _count, ...o }) => ({
      ...o,
      itemCount: _count.items,
      grandTotalInCurrency: toCurrency(o.grandTotal, o.currency, o.exchangeRate),
      isVoided: o.status === OrderStatus.CANCELLED,
    }));
  }

  /**
   * Deletes a till sale: its journal entries are reversed (sales, cost, the money in the
   * drawers and on the customer's account) and the pieces go back on the shelf.
   */
  async voidSale(id: string, reason: string | undefined, user: { id: string; permissions: string[] }) {
    await this.prisma.$transaction((tx) => this.voidIn(tx, id, reason, user));
    const sale = await this.receipt(id);
    const who = await this.prisma.user.findUnique({ where: { id: user.id }, select: { name: true } });
    void this.ownerAlerts.raise(
      'VOID',
      'حذف فاتورة 🗑️',
      `${who?.name ?? 'موظف'} حذف الفاتورة ${sale.number} بقيمة ${formatAmount(sale.grandTotal)}${reason ? ` — السبب: ${reason}` : ''}`,
      { orderId: id },
    );
    return sale;
  }

  /** Corrects a till sale: the old invoice is deleted and the corrected one rung up in its place. */
  async editSale(id: string, dto: PosSaleDto, user: { id: string; permissions: string[] }) {
    await this.treasury.ensureCashierBoxes(user.id);
    const slip = await this.readSlip(dto);
    const newId = await this.prisma.$transaction(async (tx) => {
      const old = await this.voidIn(tx, id, 'تعديل الفاتورة', user);
      const created = await this.sellIn(tx, { ...slip, clientRef: undefined, soldAt: undefined }, old.cashierId ?? user.id);
      const replacement = await tx.order.findUniqueOrThrow({ where: { id: created }, select: { number: true } });
      await tx.order.update({ where: { id }, data: { cancelReason: `عُدّلت بالفاتورة ${replacement.number}` } });
      await tx.order.update({ where: { id: created }, data: { internalNote: [`بدل الفاتورة ${old.number}`, dto.note].filter(Boolean).join(' — ') } });
      return created;
    });
    return this.receipt(newId);
  }

  private async voidIn(tx: Tx, id: string, reason: string | undefined, user: { id: string; permissions: string[] }) {
    await tx.$queryRaw`SELECT id FROM orders WHERE id = ${id}::uuid FOR UPDATE`;
    const order = await tx.order.findUnique({ where: { id }, include: { items: true } });
    if (!order || order.channel !== OrderChannel.POS) throw new NotFoundException('Sale not found');
    if (order.status === OrderStatus.CANCELLED) throw new UnprocessableEntityException('This sale was already deleted');
    const manager = user.permissions.includes('cashboxes.manage');
    if (!manager) {
      if (order.cashierId !== user.id) throw new ForbiddenException('You can only change your own sales');
      const today = startOfBusinessDay(process.env.STORE_TIMEZONE ?? 'Asia/Aden');
      if (order.placedAt < today) throw new UnprocessableEntityException("Only today's sales can be changed");
      if (await this.shifts.inClosedShift(tx, order.cashierId, order.placedAt)) throw new UnprocessableEntityException('The shift of this sale is closed');
    }
    if (order.items.some((i) => i.returnedQty > 0) || (await tx.salesReturn.count({ where: { orderId: id } }))) {
      throw new UnprocessableEntityException('Pieces of this sale were returned; it cannot be changed');
    }
    const entries = await tx.journalEntry.findMany({ where: { sourceId: id, status: 'POSTED' }, orderBy: { createdAt: 'desc' }, select: { id: true } });
    for (const e of entries) await this.ledger.reverse(tx, e.id, user.id, `Sale ${order.number} deleted`);
    for (const item of [...order.items].sort((a, b) => a.variantId.localeCompare(b.variantId))) {
      await this.stock.stockIn(tx, {
        type: StockMovementType.SALES_RETURN,
        variantId: item.variantId,
        warehouseId: order.warehouseId!,
        quantity: item.quantity,
        unitCost: item.unitCost,
        sourceType: 'ORDER',
        sourceId: order.id,
        note: `حذف الفاتورة ${order.number}`,
        userId: user.id,
      });
    }
    await tx.payment.updateMany({ where: { orderId: id }, data: { status: TransactionStatus.CANCELLED } });
    await this.transfers.release(tx, { orderId: id });
    await releaseDealUsage(tx, id);
    await this.loyalty.reverseForOrder(tx, id);
    if (order.couponId) {
      await tx.couponUsage.deleteMany({ where: { orderId: id } });
      await tx.coupon.update({ where: { id: order.couponId }, data: { usedCount: { decrement: 1 } } });
    }
    await tx.order.update({
      where: { id },
      data: {
        status: OrderStatus.CANCELLED,
        cancelledAt: new Date(),
        cancelReason: reason ?? 'حذف من الكاشير',
        statusHistory: { create: { fromStatus: order.status, toStatus: OrderStatus.CANCELLED, note: reason ?? 'POS sale deleted', changedById: user.id } },
      },
    });
    await this.audit.log({ userId: user.id, action: 'CANCEL', entityType: 'PosSale', entityId: id, before: { number: order.number, grandTotal: order.grandTotal }, after: { reason } }, tx);
    return order;
  }

  /** What the till prints: lines, totals in the invoice currency, tenders and change. */
  async receipt(orderId: string) {
    const o = await this.prisma.order.findUniqueOrThrow({
      where: { id: orderId },
      include: {
        items: true,
        payments: { orderBy: { createdAt: 'asc' } },
        customer: { select: { id: true, code: true, name: true, phone: true } },
        cashier: { select: { id: true, name: true } },
      },
    });
    const c = (v: Prisma.Decimal) => toCurrency(v, o.currency, o.exchangeRate);
    return {
      ...o,
      inCurrency: {
        currency: o.currency,
        exchangeRate: o.exchangeRate,
        subtotal: c(o.subtotal),
        discountTotal: c(o.discountTotal),
        taxTotal: c(o.taxTotal),
        grandTotal: c(o.grandTotal),
        lines: o.items.map((i) => ({ id: i.id, unitPrice: c(i.unitPrice), lineTotal: c(i.lineTotal) })),
      },
      onAccount: money(sum(o.payments.filter((p) => p.method === PaymentMethod.CUSTOMER_ACCOUNT).map((p) => p.amount))),
    };
  }

  /** Today's sales of one cashier and the drawers' balances, for the end-of-shift view. */
  async shift(cashierId: string, timezoneDate: Date) {
    const from = timezoneDate;
    const sales = await this.prisma.order.findMany({
      where: { channel: OrderChannel.POS, cashierId, placedAt: { gte: from }, status: { not: OrderStatus.CANCELLED } },
      select: { id: true, number: true, grandTotal: true, currency: true, exchangeRate: true, placedAt: true, customer: { select: { name: true } } },
      orderBy: { placedAt: 'desc' },
    });
    return {
      count: sales.length,
      total: money(sum(sales.map((s) => s.grandTotal))),
      sales: sales.map((s) => ({ ...s, grandTotalInCurrency: toCurrency(s.grandTotal, s.currency, s.exchangeRate) })),
      boxes: await this.treasury.listBoxes(this.treasury.mineWhere(cashierId)),
    };
  }

  // -------------------------------------------------------------- helpers ---

  private discountOf(dto: PosSaleDto, currency: string, rate: Decimal) {
    if (!dto.discountType || !dto.discountValue || dec(dto.discountValue).isZero()) return null;
    if (dto.discountType === 'PERCENT') {
      if (dec(dto.discountValue).gt(100)) throw new BadRequestException('Discount cannot exceed 100%');
      return { type: CouponType.PERCENTAGE, value: dto.discountValue };
    }
    // A fixed discount is typed in the invoice currency.
    return { type: CouponType.FIXED_AMOUNT, value: toBase(dto.discountValue, currency, rate) };
  }

  /** Converts each handed-over amount to SAR; an exact settlement of what is left maps to it exactly. */
  private async tenders(tx: Tx, dto: PosSaleDto, cashierId: string, invoiceCurrency: string, invoiceRate: Decimal, total: Decimal, alreadyPaid: Decimal = ZERO) {
    const tenders: Tender[] = [];
    for (const p of dto.payments ?? []) {
      const amount = inCurrency(p.amount, p.currency);
      if (amount.isZero()) continue;
      if (!dec(p.amount).eq(amount)) throw new BadRequestException(`${p.currency} amounts cannot have that many decimals`);
      const cur = p.currency === invoiceCurrency ? { rate: invoiceRate } : await this.currencies.requireActive(p.currency, tx);
      const left = total.sub(alreadyPaid).sub(sum(tenders.map((t) => t.sar)));
      const sar = left.gt(0) && amount.eq(toCurrency(left, p.currency, cur.rate)) ? money(left) : toBase(amount, p.currency, cur.rate);
      // Cash lands in the cashier's drawer; a transfer / deposit slip in the bank box of that currency.
      const method = (p.method ?? dto.paymentMethod) === 'BANK_TRANSFER' ? PaymentMethod.BANK_TRANSFER : PaymentMethod.CASH;
      const box =
        method === PaymentMethod.BANK_TRANSFER
          ? p.bankBoxId
            ? await this.bankBox(tx, p.bankBoxId, p.currency)
            : await this.treasury.defaultBox(tx, p.currency, { kind: 'BANK', userId: cashierId })
          : await this.treasury.defaultBox(tx, p.currency, { userId: cashierId });
      tenders.push({ currency: p.currency, amount, rate: cur.rate, sar, boxAccountId: box.accountId, method });
    }
    return tenders;
  }

  private async bankBox(tx: Tx, id: string, currency: string) {
    const box = await tx.cashBox.findUnique({ where: { id } });
    if (!box || !box.isActive || box.kind === 'CASHIER') throw new NotFoundException('Bank account not found');
    if (box.currency !== currency) throw new UnprocessableEntityException(`${box.name} holds ${box.currency}, not ${currency}`);
    return box;
  }

  private async payment(
    tx: Tx,
    orderId: string,
    method: PaymentMethod,
    accountId: string,
    currency: string,
    amount: Decimal,
    rate: Decimal,
    sar: Decimal,
  ): Promise<Prisma.PaymentCreateManyInput> {
    return {
      number: await this.sequences.next(tx, 'RCPT'),
      orderId,
      method,
      status: TransactionStatus.SUCCEEDED,
      amount: sar,
      currency,
      currencyAmount: amount,
      exchangeRate: currency === BASE_CURRENCY ? 1 : rate,
      accountId,
      paidAt: new Date(),
    };
  }

  private async customerFor(tx: Tx, dto: PosSaleDto) {
    if (dto.customerId) {
      const c = await tx.customer.findUnique({ where: { id: dto.customerId }, select: { id: true, status: true } });
      if (!c || c.status !== 'ACTIVE') throw new NotFoundException('Customer not found');
      return c.id;
    }
    if (dto.saleType === 'CREDIT') throw new BadRequestException('Pick the customer to sell on credit');
    return this.walkInId(tx);
  }

  private async walkInId(tx: Tx) {
    const walkIn = await tx.customer.findUnique({ where: { code: WALK_IN_CODE }, select: { id: true } });
    if (!walkIn) throw new UnprocessableEntityException('The walk-in customer is missing; run the seed');
    return walkIn.id;
  }
}

function presentPosProduct(p: PosProductRow, promos: Awaited<ReturnType<typeof activePromotions>>) {
  const promo = promos.get(p.id);
  const { images, variants, ...rest } = p;
  return {
    ...rest,
    imageUrl: images[0]?.url ?? null,
    price: promotedPrice(p.basePrice, promo),
    promotion: promo ? { name: promo.name, percent: promo.percent } : null,
    variants: variants.map(({ stockLevels, price, ...v }) => ({
      ...v,
      price: promotedPrice(price ?? p.basePrice, promo),
      regularPrice: price ?? p.basePrice,
      available: stockLevels.reduce((n, s) => n + s.onHand - s.reserved, 0),
      imageUrl: (images.find((i) => i.color && i.color === v.color) ?? images[0])?.url ?? null,
    })),
  };
}
