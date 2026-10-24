import { BadRequestException, Injectable, UnprocessableEntityException } from '@nestjs/common';
import { PaymentMethod, Prisma } from '../generated/prisma/client';
import { GOVERNORATES } from '../common/yemen';
import { PrismaService, Tx } from '../prisma/prisma.service';
import { CHECKOUT_METHODS } from './dto/sales.dto';

const PAYMENT_METHODS = 'store.paymentMethods';

/**
 * What the customer app offers: the payment methods switched on by the owner, and the
 * governorates delivered to (Yemen), each with whether cash on delivery is available.
 * Shipping is paid by the customer to the courier, so it is never added to the order.
 */
@Injectable()
export class StoreSettingsService {
  constructor(private readonly prisma: PrismaService) {}

  /** Payment methods switched on for the customer app (all checkout methods by default). */
  async enabledMethods(db: Tx | PrismaService = this.prisma): Promise<PaymentMethod[]> {
    const row = await db.setting.findUnique({ where: { key: PAYMENT_METHODS } });
    const saved = Array.isArray(row?.value) ? (row!.value as string[]) : null;
    return CHECKOUT_METHODS.filter((m) => !saved || saved.includes(m));
  }

  async setEnabledMethods(methods: PaymentMethod[]) {
    const unknown = methods.filter((m) => !(CHECKOUT_METHODS as readonly string[]).includes(m));
    if (unknown.length) throw new BadRequestException(`Not a checkout method: ${unknown.join(', ')}`);
    if (!methods.length) throw new BadRequestException('Keep at least one payment method');
    const value = [...new Set(methods)] as Prisma.InputJsonValue;
    await this.prisma.setting.upsert({ where: { key: PAYMENT_METHODS }, update: { value }, create: { key: PAYMENT_METHODS, value } });
    return this.enabledMethods();
  }

  /**
   * Every governorate with its delivery settings: the saved zone, or the defaults (delivered,
   * cash on delivery available). Zones saved for other places stay listed after them.
   */
  async zones(activeOnly = false) {
    const saved = await this.prisma.shippingZone.findMany({ orderBy: { governorate: 'asc' } });
    const yemen = GOVERNORATES.map((g) => {
      const z = saved.find((s) => s.governorate === g.name);
      return { governorate: g.name, fee: z?.fee.toString() ?? '0', estimatedDays: z?.estimatedDays ?? 3, isActive: z?.isActive ?? true, codEnabled: z?.codEnabled ?? true };
    });
    const others = saved
      .filter((s) => !GOVERNORATES.some((g) => g.name === s.governorate))
      .map((z) => ({ governorate: z.governorate, fee: z.fee.toString(), estimatedDays: z.estimatedDays, isActive: z.isActive, codEnabled: z.codEnabled }));
    return [...yemen, ...others].filter((z) => !activeOnly || z.isActive);
  }

  async zoneOf(db: Tx | PrismaService, governorate: string) {
    const z = await db.shippingZone.findUnique({ where: { governorate } });
    return { isActive: z?.isActive ?? true, codEnabled: z?.codEnabled ?? true, estimatedDays: z?.estimatedDays ?? 3 };
  }

  /** The methods a customer can pick for an address in [governorate] (or in general). */
  async methodsFor(governorate?: string, cardEnabled = true) {
    let methods = (await this.enabledMethods()).filter((m) => m !== PaymentMethod.CARD || cardEnabled);
    if (governorate && !(await this.zoneOf(this.prisma, governorate)).codEnabled) {
      methods = methods.filter((m) => m !== PaymentMethod.CASH_ON_DELIVERY);
    }
    return methods;
  }

  /** Refuses a payment method that is switched off, or cash on delivery where it isn't offered. */
  async assertMethod(tx: Tx, method: PaymentMethod, governorate: string) {
    if (!(await this.enabledMethods(tx)).includes(method)) throw new UnprocessableEntityException('This payment method is not available');
    if (method === PaymentMethod.CASH_ON_DELIVERY && !(await this.zoneOf(tx, governorate)).codEnabled) {
      throw new UnprocessableEntityException(`Cash on delivery is not available in ${governorate}`);
    }
  }
}
