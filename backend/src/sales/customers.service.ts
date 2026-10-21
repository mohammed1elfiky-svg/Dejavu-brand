import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '../generated/prisma/client';
import { normalizePhone, PHONE_REGEX } from '../auth/dto/auth.dto';
import { SequenceService } from '../common/sequence.service';
import { AuditService } from '../common/audit.service';
import { paginated } from '../common/dto/pagination.dto';
import { dec, money } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import {
  AddressDto,
  CreateCouponDto,
  CustomersQueryDto,
  ShippingZoneDto,
  UpdateAddressDto,
  UpdateCouponDto,
} from './dto/sales.dto';

/** Customer addresses, customer admin, coupons and shipping zones. */
@Injectable()
export class CustomersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
    private readonly sequences: SequenceService,
  ) {}

  // ------------------------------------------------------------ addresses ---

  listAddresses(customerId: string) {
    return this.prisma.customerAddress.findMany({
      where: { customerId },
      orderBy: [{ isDefault: 'desc' }, { createdAt: 'asc' }],
    });
  }

  async createAddress(customerId: string, dto: AddressDto) {
    return this.prisma.$transaction(async (tx) => {
      const count = await tx.customerAddress.count({ where: { customerId } });
      if (count >= 10) throw new BadRequestException('You can save up to 10 addresses');
      const isDefault = dto.isDefault || count === 0;
      if (isDefault) await tx.customerAddress.updateMany({ where: { customerId }, data: { isDefault: false } });
      return tx.customerAddress.create({ data: { ...dto, phone: normalizePhone(dto.phone), customerId, isDefault } });
    });
  }

  async updateAddress(customerId: string, id: string, dto: UpdateAddressDto) {
    return this.prisma.$transaction(async (tx) => {
      const existing = await tx.customerAddress.findFirst({ where: { id, customerId } });
      if (!existing) throw new NotFoundException('Address not found');
      if (dto.isDefault) await tx.customerAddress.updateMany({ where: { customerId }, data: { isDefault: false } });
      return tx.customerAddress.update({
        where: { id },
        data: { ...dto, phone: dto.phone ? normalizePhone(dto.phone) : undefined },
      });
    });
  }

  async deleteAddress(customerId: string, id: string) {
    const { count } = await this.prisma.customerAddress.deleteMany({ where: { id, customerId } });
    if (!count) throw new NotFoundException('Address not found');
  }

  // -------------------------------------------------------- admin: people ---

  async listCustomers(q: CustomersQueryDto) {
    const contains = q.q ? { contains: q.q, mode: 'insensitive' as const } : undefined;
    const where: Prisma.CustomerWhereInput = contains
      ? { OR: [{ name: contains }, { phone: contains }, { email: contains }, { code: contains }] }
      : {};
    const [items, total] = await Promise.all([
      this.prisma.customer.findMany({
        where,
        select: { id: true, code: true, name: true, phone: true, email: true, status: true, createdAt: true, _count: { select: { orders: true } } },
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
      }),
      this.prisma.customer.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  /** A customer added by staff (no app login yet; they can register later with the same phone). */
  async quickCreate(dto: { name: string; phone: string }, userId: string) {
    if (!PHONE_REGEX.test(dto.phone)) throw new BadRequestException('Enter a valid Yemeni, Saudi or Egyptian mobile number');
    const phone = normalizePhone(dto.phone);
    if (await this.prisma.customer.findUnique({ where: { phone } })) throw new ConflictException('A customer with this phone already exists');
    const customer = await this.prisma.$transaction(async (tx) =>
      tx.customer.create({
        data: { name: dto.name, phone, code: await this.sequences.next(tx, 'CUS', { year: 0 }) },
        select: { id: true, code: true, name: true, phone: true, status: true, createdAt: true },
      }),
    );
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Customer', entityId: customer.id, after: customer });
    return customer;
  }

  async getCustomer(id: string) {
    const customer = await this.prisma.customer.findUnique({
      where: { id },
      select: {
        id: true,
        code: true,
        name: true,
        phone: true,
        email: true,
        status: true,
        gender: true,
        birthDate: true,
        loyaltyPoints: true,
        notes: true,
        createdAt: true,
        addresses: true,
        orders: {
          orderBy: { placedAt: 'desc' },
          take: 20,
          select: { id: true, number: true, status: true, paymentStatus: true, grandTotal: true, placedAt: true },
        },
      },
    });
    if (!customer) throw new NotFoundException('Customer not found');

    // Balance from the ledger: positive = customer owes us, negative = we hold their money.
    const rows = await this.prisma.journalLine.groupBy({
      by: ['accountId'],
      where: {
        customerId: id,
        account: { systemKey: { in: ['AR', 'CUSTOMER_ADVANCES'] } },
        journalEntry: { status: { in: ['POSTED', 'REVERSED'] } },
      },
      _sum: { debit: true, credit: true },
    });
    const balance = rows.reduce((b, r) => b.add(dec(r._sum.debit)).sub(dec(r._sum.credit)), dec(0));
    const totals = await this.prisma.order.aggregate({
      where: { customerId: id, status: { notIn: ['CANCELLED'] } },
      _sum: { grandTotal: true },
      _count: true,
    });
    return {
      ...customer,
      ledgerBalance: money(balance),
      lifetime: { orders: totals._count, spent: money(totals._sum.grandTotal ?? 0) },
    };
  }

  // ------------------------------------------------------------- coupons ---

  listCoupons() {
    return this.prisma.coupon.findMany({ orderBy: { startsAt: 'desc' } });
  }

  async createCoupon(dto: CreateCouponDto, userId: string) {
    this.validateCoupon(dto);
    const coupon = await this.prisma.coupon.create({ data: dto });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Coupon', entityId: coupon.id, after: coupon });
    return coupon;
  }

  async updateCoupon(id: string, dto: UpdateCouponDto, userId: string) {
    const before = await this.prisma.coupon.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Coupon not found');
    this.validateCoupon({ ...before, value: before.value.toString(), ...dto } as CreateCouponDto);
    const coupon = await this.prisma.coupon.update({ where: { id }, data: dto });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Coupon', entityId: id, before, after: coupon });
    return coupon;
  }

  private validateCoupon(dto: Pick<CreateCouponDto, 'type' | 'value'>) {
    if (dto.type === 'PERCENTAGE' && (dec(dto.value).lte(0) || dec(dto.value).gt(100))) {
      throw new BadRequestException('Percentage must be between 0 and 100');
    }
    if (dto.type === 'FIXED_AMOUNT' && dec(dto.value).lte(0)) {
      throw new BadRequestException('Fixed discount must be greater than zero');
    }
  }

  // ------------------------------------------------------ shipping zones ---

  listZones(activeOnly = false) {
    return this.prisma.shippingZone.findMany({
      where: activeOnly ? { isActive: true } : undefined,
      orderBy: { governorate: 'asc' },
    });
  }

  async upsertZone(dto: ShippingZoneDto, userId: string) {
    const zone = await this.prisma.shippingZone.upsert({
      where: { governorate: dto.governorate },
      update: dto,
      create: dto,
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'ShippingZone', entityId: zone.id, after: zone });
    return zone;
  }
}
