import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { Coupon, ProductStatus } from '../generated/prisma/client';
import { dec } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { AddCartItemDto } from './dto/sales.dto';
import { priceOrder, PricingResult } from './pricing';
import { activePromotions, buyerPrice } from '../catalog/promotions';
import { storeVisibility } from '../catalog/catalog.service';
import { getSetting } from './settings';

const MAX_LINE_QTY = 20;

const cartInclude = {
  items: {
    orderBy: { addedAt: 'asc' },
    include: {
      variant: {
        select: {
          id: true,
          sku: true,
          size: true,
          color: true,
          price: true,
          isActive: true,
          avgCost: true,
          stockLevels: { where: { warehouse: { isDefault: true } }, select: { onHand: true, reserved: true } },
          product: {
            select: {
              id: true,
              slug: true,
              nameAr: true,
              nameEn: true,
              basePrice: true,
              wholesalePrice: true,
              status: true,
              visibility: true,
              taxRate: { select: { rate: true, isActive: true } },
              images: { orderBy: { sortOrder: 'asc' }, select: { url: true, color: true } },
            },
          },
        },
      },
    },
  },
} as const;

export interface QuoteLine {
  variantId: string;
  sku: string;
  productName: string;
  nameAr: string;
  nameEn: string;
  slug: string;
  size: string | null;
  color: string | null;
  imageUrl: string | null;
  quantity: number;
  availableQty: number;
  avgCost: string;
  problem?: string;
}

export interface Quote {
  lines: QuoteLine[];
  pricing: PricingResult;
  coupon: Coupon | null;
  couponCode: string | null;
  couponProblem?: string;
  shipping: { governorate: string; fee: string; estimatedDays: number } | null;
  shippingProblem?: string;
  problems: string[];
}

/**
 * Builds the authoritative price quote for a customer's cart. Used for the cart
 * screen (problems are reported) and for checkout (problems abort the order).
 */
@Injectable()
export class CartService {
  constructor(private readonly prisma: PrismaService) {}

  async addItem(customerId: string, dto: AddCartItemDto) {
    const variant = await this.prisma.productVariant.findUnique({
      where: { id: dto.variantId },
      select: { isActive: true, product: { select: { status: true, visibility: true } } },
    });
    const buyer = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true } });
    if (!variant || !variant.isActive || variant.product.status !== ProductStatus.ACTIVE || !storeVisibility(buyer).includes(variant.product.visibility)) {
      throw new NotFoundException('Product is not available');
    }
    const cart = await this.ensureCart(customerId);
    const existing = await this.prisma.cartItem.findUnique({
      where: { cartId_variantId: { cartId: cart.id, variantId: dto.variantId } },
    });
    const quantity = (existing?.quantity ?? 0) + dto.quantity;
    if (quantity > MAX_LINE_QTY) throw new BadRequestException(`At most ${MAX_LINE_QTY} pieces per item`);

    await this.prisma.cartItem.upsert({
      where: { cartId_variantId: { cartId: cart.id, variantId: dto.variantId } },
      update: { quantity },
      create: { cartId: cart.id, variantId: dto.variantId, quantity },
    });
  }

  async setQuantity(customerId: string, variantId: string, quantity: number) {
    const cart = await this.ensureCart(customerId);
    const { count } = await this.prisma.cartItem.updateMany({ where: { cartId: cart.id, variantId }, data: { quantity } });
    if (!count) throw new NotFoundException('Item is not in the cart');
  }

  async removeItem(customerId: string, variantId: string) {
    const cart = await this.ensureCart(customerId);
    await this.prisma.cartItem.deleteMany({ where: { cartId: cart.id, variantId } });
  }

  async setCoupon(customerId: string, code: string | null) {
    const cart = await this.ensureCart(customerId);
    if (code) {
      const exists = await this.prisma.coupon.findUnique({ where: { code } });
      if (!exists || !exists.isActive) throw new NotFoundException('Coupon code is not valid');
    }
    await this.prisma.cart.update({ where: { id: cart.id }, data: { couponCode: code } });
  }

  async quote(tx: Tx, customerId: string, addressId?: string): Promise<Quote> {
    const cart = await tx.cart.findUnique({ where: { customerId }, include: cartInclude });
    const items = cart?.items ?? [];
    const problems: string[] = [];

    const visible = storeVisibility(await tx.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true } }));
    const lines: QuoteLine[] = items.map((item) => {
      const v = item.variant;
      const availableQty = v.stockLevels.reduce((n, s) => n + s.onHand - s.reserved, 0);
      let problem: string | undefined;
      if (!v.isActive || v.product.status !== ProductStatus.ACTIVE || !visible.includes(v.product.visibility)) problem = 'No longer available';
      else if (availableQty < item.quantity) {
        problem = availableQty > 0 ? `Only ${availableQty} left in stock` : 'Out of stock';
      }
      if (problem) problems.push(`${v.sku}: ${problem}`);
      return {
        variantId: v.id,
        sku: v.sku,
        productName: v.product.nameAr,
        nameAr: v.product.nameAr,
        nameEn: v.product.nameEn,
        slug: v.product.slug,
        size: v.size,
        color: v.color,
        imageUrl: (v.product.images.find((img) => img.color && img.color === v.color) ?? v.product.images[0])?.url ?? null,
        quantity: item.quantity,
        availableQty: Math.max(availableQty, 0),
        avgCost: v.avgCost.toString(),
        problem,
      };
    });

    // Shipping
    let shipping: Quote['shipping'] = null;
    let shippingProblem: string | undefined;
    if (addressId) {
      const address = await tx.customerAddress.findFirst({ where: { id: addressId, customerId } });
      if (!address) throw new NotFoundException('Address not found');
      const zone = await tx.shippingZone.findUnique({ where: { governorate: address.governorate } });
      if (!zone || !zone.isActive) shippingProblem = `Delivery to ${address.governorate} is not available yet`;
      else shipping = { governorate: zone.governorate, fee: zone.fee.toString(), estimatedDays: zone.estimatedDays };
    }
    if (shippingProblem) problems.push(shippingProblem);

    const pricesIncludeTax = await getSetting(tx, 'tax.pricesIncludeTax', true);
    const promos = await activePromotions(tx, items.map((i) => i.variant.product.id));
    const buyer = await tx.customer.findUnique({ where: { id: customerId }, select: { isMerchant: true, merchantDiscount: true } });
    const pricingLines = items.map((item) => ({
      variantId: item.variant.id,
      quantity: item.quantity,
      unitPrice: buyerPrice(item.variant.price ?? item.variant.product.basePrice, item.variant.product.wholesalePrice, promos.get(item.variant.product.id), buyer),
      taxRate: item.variant.product.taxRate?.isActive ? item.variant.product.taxRate.rate : 0,
    }));

    // Coupon
    const couponCode = cart?.couponCode ?? null;
    let coupon: Coupon | null = null;
    let couponProblem: string | undefined;
    if (couponCode) {
      const subtotal = priceOrder({ lines: pricingLines, shippingFee: 0, pricesIncludeTax }).subtotal;
      const check = await this.checkCoupon(tx, couponCode, customerId, subtotal.toString());
      if (typeof check === 'string') couponProblem = check;
      else coupon = check;
    }
    if (couponProblem) problems.push(couponProblem);

    const pricing = priceOrder({ lines: pricingLines, coupon, shippingFee: shipping?.fee ?? 0, pricesIncludeTax });
    return { lines, pricing, coupon, couponCode, couponProblem, shipping, shippingProblem, problems };
  }

  /** Returns the coupon when it can be applied, otherwise a reason. */
  async checkCoupon(tx: Tx, code: string, customerId: string, subtotal: string, perCustomer = true): Promise<Coupon | string> {
    const coupon = await tx.coupon.findUnique({ where: { code } });
    const now = new Date();
    if (!coupon || !coupon.isActive) return 'Coupon code is not valid';
    if (coupon.startsAt > now || (coupon.endsAt && coupon.endsAt < now)) return 'Coupon has expired';
    if (coupon.usageLimit !== null && coupon.usedCount >= coupon.usageLimit) return 'Coupon usage limit reached';
    if (coupon.minOrderAmount && dec(subtotal).lessThan(coupon.minOrderAmount)) {
      return `Minimum order for this coupon is ${coupon.minOrderAmount}`;
    }
    if (perCustomer && coupon.perCustomerLimit !== null) {
      const used = await tx.couponUsage.count({ where: { couponId: coupon.id, customerId } });
      if (used >= coupon.perCustomerLimit) return 'You have already used this coupon';
    }
    return coupon;
  }

  async view(customerId: string, addressId?: string) {
    const q = await this.quote(this.prisma, customerId, addressId);
    return {
      items: q.lines.map(({ avgCost: _cost, ...l }, i) => ({
        ...l,
        unitPrice: q.pricing.lines[i].unitPrice,
        discount: q.pricing.lines[i].discount,
        lineTotal: q.pricing.lines[i].lineTotal,
      })),
      couponCode: q.couponCode,
      couponProblem: q.couponProblem ?? null,
      shipping: q.shipping,
      shippingProblem: q.shippingProblem ?? null,
      totals: {
        subtotal: q.pricing.subtotal,
        discountTotal: q.pricing.discountTotal,
        shippingFee: q.pricing.shippingFee,
        taxTotal: q.pricing.taxTotal,
        grandTotal: q.pricing.grandTotal,
      },
      canCheckout: q.lines.length > 0 && q.problems.length === 0,
      problems: q.problems,
    };
  }

  assertCheckoutable(q: Quote) {
    if (!q.lines.length) throw new UnprocessableEntityException('Your cart is empty');
    if (q.problems.length) throw new UnprocessableEntityException({ message: 'Cart needs attention', problems: q.problems });
  }

  private ensureCart(customerId: string) {
    return this.prisma.cart.upsert({ where: { customerId }, update: {}, create: { customerId } });
  }
}
