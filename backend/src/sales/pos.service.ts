import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import {
  CouponType,
  OrderChannel,
  OrderStatus,
  PaymentMethod,
  PaymentStatus,
  Prisma,
  ProductStatus,
  StockMovementType,
  TransactionStatus,
} from '../generated/prisma/client';
import { LedgerService, PostingLine } from '../accounting/ledger.service';
import { activePromotions, buyerPrice, promotedPrice } from '../catalog/promotions';
import { AuditService } from '../common/audit.service';
import { BASE_CURRENCY, CurrencyService, inCurrency, toBase, toCurrency } from '../common/currency.service';
import { SequenceService } from '../common/sequence.service';
import { Decimal, dec, money, sum, ZERO } from '../common/utils/money';
import { StockService } from '../inventory/stock.service';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { TreasuryService } from '../treasury/treasury.service';
import { PosSaleDto, PosProductsQueryDto } from './dto/pos.dto';
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
  ) {}

  // -------------------------------------------------------------- catalog ---

  /** Products for the cashier grid, in the order the store arranged them. */
  async products(q: PosProductsQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const rows = await this.prisma.product.findMany({
      where: {
        status: ProductStatus.ACTIVE,
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
      where: { isActive: true, product: { status: ProductStatus.ACTIVE }, OR: [{ sku: { equals: term, mode: 'insensitive' } }, { barcode: term }] },
      select: { id: true, productId: true },
    });
    const productId =
      variant?.productId ??
      (/^\d{3,6}$/.test(term)
        ? (await this.prisma.product.findFirst({ where: { skuNumber: Number(term), status: ProductStatus.ACTIVE }, select: { id: true } }))?.id
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

  // ----------------------------------------------------------------- sale ---

  async sell(dto: PosSaleDto, cashierId: string) {
    await this.treasury.ensureCashierBoxes(cashierId);
    const orderId = await this.prisma.$transaction(async (tx) => {
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
          product: { select: { id: true, nameAr: true, basePrice: true, wholesalePrice: true, status: true, taxRate: { select: { rate: true, isActive: true } } } },
        },
        orderBy: { id: 'asc' },
      });
      if (variants.length !== quantities.size) throw new NotFoundException('Some items were not found');
      const inactive = variants.find((v) => !v.isActive || v.product.status !== ProductStatus.ACTIVE);
      if (inactive) throw new UnprocessableEntityException(`${inactive.sku} is not for sale`);
      const promos = await activePromotions(tx, variants.map((v) => v.product.id));
      // Merchants pay wholesale prices less their own discount.
      const buyer = await tx.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true, merchantDiscount: true } });
      const rate = invoiceCurrency.rate;
      const discount = this.discountOf(dto, invoiceCurrency.code, rate);
      const pricing = priceOrder({
        lines: variants.map((v) => ({
          variantId: v.id,
          quantity: quantities.get(v.id)!,
          unitPrice: buyerPrice(v.price ?? v.product.basePrice, v.product.wholesalePrice, promos.get(v.product.id), buyer),
          taxRate: v.product.taxRate?.isActive ? v.product.taxRate.rate : 0,
        })),
        coupon: discount,
        shippingFee: 0,
        pricesIncludeTax: await getSetting(tx, 'tax.pricesIncludeTax', true),
      });
      const total = pricing.grandTotal;

      // ---- the money handed over ----
      const tenders = await this.tenders(tx, dto, cashierId, invoiceCurrency.code, rate, total);
      let paid = sum(tenders.map((t) => t.sar));
      // A remainder smaller than the smallest coin of the last currency used counts as paid.
      const last = tenders[tenders.length - 1];
      const short = total.sub(paid);
      if (last && short.gt(0) && toCurrency(short, last.currency, last.rate).isZero()) {
        last.sar = last.sar.add(short);
        paid = total;
      }
      let change = paid.gt(total) ? money(paid.sub(total)) : ZERO;
      const changeCurrency = dto.changeCurrency ?? invoiceCurrency.code;
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
          subtotal: pricing.subtotal,
          discountTotal: pricing.discountTotal,
          shippingFee: 0,
          taxTotal: pricing.taxTotal,
          grandTotal: total,
          amountPaid: total,
          internalNote: dto.note,
          placedAt: now,
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
        lines.push(...(await this.treasury.moneyLines(tx, { account: { id: t.boxAccountId }, currency: t.currency, amount: t.amount, sar: t.sar })));
        payments.push({
          ...(await this.payment(tx, order.id, t.method, t.boxAccountId, t.currency, t.amount, t.rate, t.sar)),
          ...(t.method === PaymentMethod.BANK_TRANSFER && dto.reference ? { gatewayPayload: { reference: dto.reference } } : {}),
        });
      }
      if (change.gt(0)) {
        const box = await this.treasury.defaultBox(tx, changeCurrency, { userId: cashierId });
        const bal = await this.treasury.balanceOf(tx, { boxId: box.id });
        const comingIn = sum(tenders.filter((t) => t.currency === changeCurrency).map((t) => t.amount));
        if (bal.amount.add(comingIn).lt(changeAmount)) {
          throw new UnprocessableEntityException(`Not enough ${changeCurrency} in the drawer for the change`);
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
      return order.id;
    });
    return this.receipt(orderId);
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
  private async tenders(tx: Tx, dto: PosSaleDto, cashierId: string, invoiceCurrency: string, invoiceRate: Decimal, total: Decimal) {
    const tenders: Tender[] = [];
    for (const p of dto.payments ?? []) {
      const amount = inCurrency(p.amount, p.currency);
      if (amount.isZero()) continue;
      if (!dec(p.amount).eq(amount)) throw new BadRequestException(`${p.currency} amounts cannot have that many decimals`);
      const cur = p.currency === invoiceCurrency ? { rate: invoiceRate } : await this.currencies.requireActive(p.currency, tx);
      const left = total.sub(sum(tenders.map((t) => t.sar)));
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
