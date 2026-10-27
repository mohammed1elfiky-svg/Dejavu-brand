import { BadRequestException, Body, Controller, Get, Injectable, Put } from '@nestjs/common';
import { ApiProperty, ApiTags } from '@nestjs/swagger';
import { ArrayMaxSize, IsArray, IsUUID } from 'class-validator';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { PrismaService } from '../prisma/prisma.service';

export class StoreOrderDto {
  @ApiProperty({ description: 'Products in the order shoppers should see them (first = top); others follow, newest first' })
  @IsArray()
  @ArrayMaxSize(500)
  @IsUUID('4', { each: true })
  productIds: string[];
}

/** The owner's own order of products in the customer app. */
@Injectable()
export class StoreOrderService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  /** The products placed by hand, in their order. */
  async list() {
    const rows = await this.prisma.product.findMany({
      where: { storePosition: { not: null } },
      orderBy: { storePosition: 'asc' },
      select: {
        id: true,
        nameAr: true,
        slug: true,
        status: true,
        storePosition: true,
        images: { orderBy: { sortOrder: 'asc' }, take: 1, select: { url: true } },
      },
    });
    return rows.map(({ images, ...p }) => ({ ...p, imageUrl: images[0]?.url ?? null }));
  }

  /** Replaces the hand-placed list: these first, in this order; every other product after them. */
  async set(productIds: string[], userId: string) {
    const ids = [...new Set(productIds)];
    const found = await this.prisma.product.count({ where: { id: { in: ids } } });
    if (found !== ids.length) throw new BadRequestException('Some products were not found');
    await this.prisma.$transaction([
      this.prisma.product.updateMany({ where: { storePosition: { not: null }, id: { notIn: ids } }, data: { storePosition: null } }),
      ...ids.map((id, i) => this.prisma.product.update({ where: { id }, data: { storePosition: i + 1 } })),
    ]);
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'StoreOrder', after: { productIds: ids } });
    return this.list();
  }
}

@ApiTags('Admin · Catalog')
@RequirePermissions('products.write')
@Controller('admin/store-order')
export class AdminStoreOrderController {
  constructor(private readonly order: StoreOrderService) {}

  @Get()
  list() {
    return this.order.list();
  }

  @Put()
  set(@Body() dto: StoreOrderDto, @CurrentUser() u: AuthPrincipal) {
    return this.order.set(dto.productIds, u.id);
  }
}
