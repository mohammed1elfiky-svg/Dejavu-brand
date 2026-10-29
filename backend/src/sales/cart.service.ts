import { activeDeals } from './deals.db';
import { AppliedDeal, applyDeals } from './deals';
import { BadRequestException, Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { Coupon, DeliveryMethod, ProductStatus } from '../generated/prisma/client';

import { checkCoupon, liveCoupons } from './coupons';
import { dec } from '../common/utils/money';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { AddCartItemDto } from './dto/sales.dto';
import { priceOrder, PricingResult } from './pricing';
import { activePromotions, buyerPrice } from '../catalog/promotions';
import { storeVisibility } from '../catalog/catalog.service';
import { getSetting } from './settings';

export interface DeliveryChoice {
  deliveryMethod?: DeliveryMethod;
  carrierOfficeId?: string;
}

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
  /** Added from this customer post (ambassador reward). */
  viaPostId: string | null;
}

export interface Quote {
  lines: QuoteLine[];
  pricing: PricingResult;
  coupon: Coupon | null;
  couponCode: string | null;
  couponProblem?: string;
  shipping: { governorate: string; fee: string; estimatedDays: number } | null;
  shippingProblem?: string;
  /** Quantity offers that applied ("قطعة + قطعة مجاناً"). */
  deals: AppliedDeal[];
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

    // From a post: the post's author earns the ambassador reward (never for their own post).
    let viaPostId: string | undefined;
    if (dto.viaPostId) {
      const post = await this.prisma.post.findUnique({ where: { id: dto.viaPostId }, select: { authorId: true, isHidden: true } });
      if (post && !post.isHidden && post.authorId !== customerId) viaPostId = dto.viaPostId;
    }
    await this.prisma.cartItem.upsert({
      where: { cartId_variantId: { cartId: cart.id, variantId: dto.variantId } },
      update: { quantity, ...(viaPostId && { viaPostId }) },
      create: { cartId: cart.id, variantId: dto.variantId, quantity, viaPostId },
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

  /**
   * [delivery]: PICKUP costs no shipping; OFFICE ships to the office's governorate; HOME (the
   * default) to [addressId].
   */
  async quote(tx: Tx, customerId: string, addressId?: string, delivery: DeliveryChoice = {}): Promise<Quote> {
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
        viaPostId: item.viaPostId,
      };
    });

    // Shipping
    let shipping: Quote['shipping'] = null;
    let shippingProblem: string | undefined;
    let governorate: string | null = null;
    if (delivery.deliveryMethod === DeliveryMethod.PICKUP) {
      shipping = { governorate: '', fee: '0', estimatedDays: 0 };
    } else if (delivery.deliveryMethod === DeliveryMethod.OFFICE && delivery.carrierOfficeId) {
      const office = await tx.carrierOffice.findUnique({ where: { id: delivery.carrierOfficeId }, include: { carrier: { select: { isActive: true } } } });
      if (!office || !office.isActive || !office.carrier.isActive) throw new NotFoundException('Shipping office not found');
      governorate = office.governorate;
    } else if (addressId) {
      const address = await tx.customerAddress.findFirst({ where: { id: addressId, customerId } });
      if (!address) throw new NotFoundException('Address not found');
      governorate = address.governorate;
    }
    if (governorate) {
      // Every governorate is delivered to unless switched off. By default the customer pays the
      // courier for shipping, so the fee is not added to the order (store.chargeShipping).
      const zone = await tx.shippingZone.findUnique({ where: { governorate } });
      const charge = await getSetting(tx, 'store.chargeShipping', false);
      if (zone && !zone.isActive) shippingProblem = `Delivery to ${governorate} is not available yet`;
      else if (charge && !zone) shippingProblem = `Delivery to ${governorate} is not available yet`;
      else shipping = { governorate, fee: charge && zone ? zone.fee.toString() : '0', estimatedDays: zone?.estimatedDays ?? 3 };
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

    // Quantity offers (not for merchants, who already pay wholesale prices).
    const deals = buyer?.isMerchant ? [] : await activeDeals(tx, items.map((i) => i.variant.product.id), 'online', new Date(), customerId);
    const offers = applyDeals(
      items.map((item, i) => ({ productId: item.variant.product.id, quantity: item.quantity, unitPrice: pricingLines[i].unitPrice })),
      deals,
    );
    const lineDiscounts = offers.lineDiscounts;

    // Coupon
    const couponCode = cart?.couponCode ?? null;
    let coupon: Coupon | null = null;
    let couponProblem: string | undefined;
    if (couponCode) {
      const subtotal = priceOrder({ lines: pricingLines, lineDiscounts, shippingFee: 0, pricesIncludeTax }).subtotal;
      const check = await checkCoupon(tx, couponCode, customerId, subtotal.toString());
      if (typeof check === 'string') couponProblem = check;
      else coupon = check;
    }
    if (couponProblem) problems.push(couponProblem);

    const pricing = priceOrder({ lines: pricingLines, lineDiscounts, coupon, shippingFee: shipping?.fee ?? 0, pricesIncludeTax });
    return { lines, pricing, coupon, couponCode, couponProblem, shipping, shippingProblem, problems, deals: offers.applied };
  }

  async view(customerId: string, addressId?: string, delivery: DeliveryChoice = {}) {
    const q = await this.quote(this.prisma, customerId, addressId, delivery);
    return {
      items: q.lines.map(({ avgCost: _cost, ...l }, i) => ({
        ...l,
        unitPrice: q.pricing.lines[i].unitPrice,
        discount: q.pricing.lines[i].discount,
        lineTotal: q.pricing.lines[i].lineTotal,
      })),
      couponCode: q.couponCode,
      couponProblem: q.couponProblem ?? null,
      /** Whether any coupon can be used now: the app shows the code field only then. */
      couponsAvailable: (await liveCoupons(this.prisma, customerId)) > 0,
      deals: q.deals,
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
