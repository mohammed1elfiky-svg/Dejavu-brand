import { Injectable, NotFoundException, UnprocessableEntityException } from '@nestjs/common';
import { NotificationType, OrderStatus, ProductStatus } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { paginated, PaginationQueryDto } from '../common/dto/pagination.dto';
import { NotificationsService } from '../notifications/notifications.service';
import { PrismaService, Tx } from '../prisma/prisma.service';

const RECEIVED: OrderStatus[] = [OrderStatus.DELIVERED, OrderStatus.COMPLETED];

/**
 * Customer reviews: a rating with a comment and photos, from customers who received the
 * product. Shown in the store once the store approves them; the product's average rating
 * counts approved reviews only.
 */
@Injectable()
export class ReviewsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly notifications: NotificationsService,
    private readonly audit: AuditService,
  ) {}

  /** Approved reviews of a product, newest first, with the average. */
  async forProduct(slug: string, q: PaginationQueryDto) {
    const product = await this.prisma.product.findFirst({ where: { slug, status: ProductStatus.ACTIVE }, select: { id: true, ratingAvg: true, ratingCount: true } });
    if (!product) throw new NotFoundException('Product is not available');
    const where = { productId: product.id, isApproved: true };
    const [items, total] = await Promise.all([
      this.prisma.productReview.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
        select: { id: true, rating: true, comment: true, images: true, createdAt: true, customer: { select: { name: true } } },
      }),
      this.prisma.productReview.count({ where }),
    ]);
    return {
      average: product.ratingAvg,
      count: product.ratingCount,
      ...paginated(
        // Only the first name is shown.
        items.map(({ customer, ...r }) => ({ ...r, customerName: customer.name.trim().split(/\s+/)[0] })),
        total,
        q,
      ),
    };
  }

  /** The customer's own review of a product, if any (to edit it). */
  async mine(customerId: string, slug: string) {
    const product = await this.prisma.product.findUnique({ where: { slug }, select: { id: true } });
    if (!product) throw new NotFoundException('Product is not available');
    const review = await this.prisma.productReview.findUnique({ where: { productId_customerId: { productId: product.id, customerId } } });
    const received = await this.receivedOrder(this.prisma, customerId, product.id);
    return { review, canReview: !!received };
  }

  /** Adds or replaces the customer's review; it waits for approval again. */
  async write(customerId: string, slug: string, dto: { rating: number; comment?: string; images?: string[] }) {
    const product = await this.prisma.product.findUnique({ where: { slug }, select: { id: true, nameAr: true } });
    if (!product) throw new NotFoundException('Product is not available');
    const order = await this.receivedOrder(this.prisma, customerId, product.id);
    if (!order) throw new UnprocessableEntityException('You can review products you have received');
    const data = { rating: dto.rating, comment: dto.comment?.trim() || null, images: dto.images ?? [], orderId: order.id, isApproved: false, createdAt: new Date() };
    const review = await this.prisma.$transaction(async (tx) => {
      const r = await tx.productReview.upsert({
        where: { productId_customerId: { productId: product.id, customerId } },
        update: data,
        create: { ...data, productId: product.id, customerId },
      });
      await this.refreshRating(tx, product.id);
      return r;
    });
    void this.notifications.notifyStaff('products.write', {
      type: NotificationType.SYSTEM,
      title: 'تقييم جديد بانتظار الموافقة',
      body: `${'★'.repeat(dto.rating)} — ${product.nameAr}`,
      data: { reviewId: review.id },
    });
    return review;
  }

  // ------------------------------------------------------------- admin ---

  async list(q: PaginationQueryDto & { status?: 'pending' | 'approved' }) {
    const where = q.status ? { isApproved: q.status === 'approved' } : {};
    const [items, total] = await Promise.all([
      this.prisma.productReview.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: q.skip,
        take: q.pageSize,
        include: {
          customer: { select: { id: true, name: true, phone: true } },
          product: { select: { id: true, nameAr: true, slug: true, images: { orderBy: { sortOrder: 'asc' }, take: 1, select: { url: true } } } },
        },
      }),
      this.prisma.productReview.count({ where }),
    ]);
    return paginated(items, total, q);
  }

  async approve(id: string, approved: boolean, userId: string) {
    const review = await this.prisma.$transaction(async (tx) => {
      const r = await tx.productReview.update({ where: { id }, data: { isApproved: approved } }).catch(() => null);
      if (!r) throw new NotFoundException('Review not found');
      await this.refreshRating(tx, r.productId);
      return r;
    });
    await this.audit.log({ userId, action: approved ? 'APPROVE' : 'UPDATE', entityType: 'ProductReview', entityId: id, after: { isApproved: approved } });
    return review;
  }

  async remove(id: string, userId: string) {
    await this.prisma.$transaction(async (tx) => {
      const r = await tx.productReview.findUnique({ where: { id } });
      if (!r) throw new NotFoundException('Review not found');
      await tx.productReview.delete({ where: { id } });
      await this.refreshRating(tx, r.productId);
    });
    await this.audit.log({ userId, action: 'DELETE', entityType: 'ProductReview', entityId: id });
  }

  private receivedOrder(db: Tx | PrismaService, customerId: string, productId: string) {
    return db.order.findFirst({
      where: { customerId, status: { in: RECEIVED }, items: { some: { variant: { productId } } } },
      orderBy: { placedAt: 'desc' },
      select: { id: true },
    });
  }

  private async refreshRating(tx: Tx, productId: string) {
    const agg = await tx.productReview.aggregate({ where: { productId, isApproved: true }, _avg: { rating: true }, _count: true });
    await tx.product.update({ where: { id: productId }, data: { ratingAvg: (agg._avg.rating ?? 0).toFixed(2), ratingCount: agg._count } });
  }
}
