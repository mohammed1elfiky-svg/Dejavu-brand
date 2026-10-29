import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { DealType, Prisma } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { dec } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { dealLabel } from './deals';

export interface DealInput {
  name: string;
  type: DealType;
  buyQty?: number;
  freeQty?: number;
  bundlePrice?: string | null;
  startsAt?: string;
  endsAt?: string | null;
  isActive?: boolean;
  atPos?: boolean;
  online?: boolean;
  maxPieces?: number | null;
  maxCustomers?: number | null;
  productIds: string[];
}

const include = {
  products: { select: { product: { select: { id: true, nameAr: true, slug: true, basePrice: true, images: { take: 1, orderBy: { sortOrder: 'asc' }, select: { url: true } } } } } },
} satisfies Prisma.DealInclude;

/** Quantity offers: buy X get Y free on chosen products, and sets (طقم) at one price. */
@Injectable()
export class DealsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  private check(dto: Partial<DealInput>, type: DealType) {
    if (dto.productIds && !dto.productIds.length) throw new BadRequestException('Pick the products of the offer');
    if (type === DealType.BUY_X_GET_Y && dto.productIds && dto.productIds.length < 1) throw new BadRequestException('Pick at least one product');
    if (type === DealType.BUNDLE) {
      if (dto.productIds && dto.productIds.length < 2) throw new BadRequestException('A set needs at least two products');
      if (dto.bundlePrice !== undefined && (dto.bundlePrice === null || !dec(dto.bundlePrice).gt(0))) throw new BadRequestException('Give the price of the set');
    }
  }

  async list(q: { active?: boolean }) {
    const now = new Date();
    const rows = await this.prisma.deal.findMany({
      where: q.active ? { isActive: true, startsAt: { lte: now }, OR: [{ endsAt: null }, { endsAt: { gt: now } }] } : {},
      orderBy: { createdAt: 'desc' },
      include,
    });
    return rows.map((d) => this.present(d));
  }

  async get(id: string) {
    const d = await this.prisma.deal.findUnique({ where: { id }, include });
    if (!d) throw new NotFoundException('Offer not found');
    return this.present(d);
  }

  async create(dto: DealInput, userId: string) {
    this.check(dto, dto.type);
    if (dto.type === DealType.BUNDLE && !dto.bundlePrice) throw new BadRequestException('Give the price of the set');
    await this.assertProducts(dto.productIds);
    const d = await this.prisma.deal.create({
      data: {
        name: dto.name,
        type: dto.type,
        buyQty: dto.buyQty ?? 1,
        freeQty: dto.freeQty ?? 1,
        bundlePrice: dto.type === DealType.BUNDLE ? dto.bundlePrice : null,
        startsAt: dto.startsAt ? new Date(dto.startsAt) : new Date(),
        endsAt: dto.endsAt ? new Date(dto.endsAt) : null,
        isActive: dto.isActive ?? true,
        atPos: dto.atPos ?? true,
        online: dto.online ?? true,
        maxPieces: dto.maxPieces || null,
        maxCustomers: dto.maxCustomers || null,
        products: { create: [...new Set(dto.productIds)].map((productId) => ({ productId })) },
      },
    });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Deal', entityId: d.id, after: dto });
    return this.get(d.id);
  }

  async update(id: string, dto: Partial<DealInput>, userId: string) {
    const before = await this.prisma.deal.findUnique({ where: { id } });
    if (!before) throw new NotFoundException('Offer not found');
    const type = dto.type ?? before.type;
    this.check(dto, type);
    if (dto.productIds) await this.assertProducts(dto.productIds);
    await this.prisma.$transaction(async (tx) => {
      await tx.deal.update({
        where: { id },
        data: {
          name: dto.name,
          type: dto.type,
          buyQty: dto.buyQty,
          freeQty: dto.freeQty,
          bundlePrice: dto.bundlePrice === undefined ? undefined : dto.bundlePrice || null,
          startsAt: dto.startsAt ? new Date(dto.startsAt) : undefined,
          endsAt: dto.endsAt === undefined ? undefined : dto.endsAt ? new Date(dto.endsAt) : null,
          isActive: dto.isActive,
          atPos: dto.atPos,
          online: dto.online,
          maxPieces: dto.maxPieces === undefined ? undefined : dto.maxPieces || null,
          maxCustomers: dto.maxCustomers === undefined ? undefined : dto.maxCustomers || null,
        },
      });
      if (dto.productIds) {
        await tx.dealProduct.deleteMany({ where: { dealId: id } });
        await tx.dealProduct.createMany({ data: [...new Set(dto.productIds)].map((productId) => ({ dealId: id, productId })) });
      }
    });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Deal', entityId: id, before, after: dto });
    return this.get(id);
  }

  async remove(id: string, userId: string) {
    const d = await this.prisma.deal.findUnique({ where: { id } });
    if (!d) throw new NotFoundException('Offer not found');
    await this.prisma.deal.delete({ where: { id } });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'Deal', entityId: id, before: d });
  }

  private async assertProducts(ids: string[]) {
    const n = await this.prisma.product.count({ where: { id: { in: [...new Set(ids)] } } });
    if (n !== new Set(ids).size) throw new NotFoundException('Some products were not found');
  }

  private present(d: Prisma.DealGetPayload<{ include: typeof include }>) {
    const now = new Date();
    return {
      id: d.id,
      name: d.name,
      type: d.type,
      buyQty: d.buyQty,
      freeQty: d.freeQty,
      bundlePrice: d.bundlePrice,
      startsAt: d.startsAt,
      endsAt: d.endsAt,
      isActive: d.isActive,
      atPos: d.atPos,
      online: d.online,
      maxPieces: d.maxPieces,
      maxCustomers: d.maxCustomers,
      usedPieces: d.usedPieces,
      usedCustomers: d.usedCustomers,
      /** Sold out: the pieces or customers it was limited to are taken. */
      exhausted: (d.maxPieces !== null && d.usedPieces >= d.maxPieces) || (d.maxCustomers !== null && d.usedCustomers >= d.maxCustomers),
      label: dealLabel(d),
      running:
        d.isActive && d.startsAt <= now && (!d.endsAt || d.endsAt > now) &&
        !((d.maxPieces !== null && d.usedPieces >= d.maxPieces) || (d.maxCustomers !== null && d.usedCustomers >= d.maxCustomers)),
      products: d.products.map((p) => ({ id: p.product.id, nameAr: p.product.nameAr, slug: p.product.slug, basePrice: p.product.basePrice, imageUrl: p.product.images[0]?.url ?? null })),
    };
  }
}
