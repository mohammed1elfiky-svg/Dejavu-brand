import { BadRequestException, Injectable, UnauthorizedException } from '@nestjs/common';
import { normalizePhone, PHONE_REGEX } from '../auth/dto/auth.dto';
import { verifyPassword } from '../auth/password';
import { CustomerStatus, Gender } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';

/**
 * A customer deleting their own account (from the app, or from the web page Google Play
 * links to). Personal data goes; orders, payments and the customer ledger stay, now under
 * "عميل محذوف", because the books must keep them.
 */
@Injectable()
export class AccountDeletionService {
  constructor(private readonly prisma: PrismaService) {}

  /** Deletes the signed-in customer's account after checking their password. */
  async deleteOwn(customerId: string, password: string) {
    const c = await this.prisma.customer.findUnique({ where: { id: customerId }, select: { passwordHash: true, status: true } });
    if (!c || c.status === CustomerStatus.DELETED) throw new UnauthorizedException('الحساب غير موجود');
    if (!(await verifyPassword(password, c.passwordHash))) throw new UnauthorizedException('كلمة المرور غير صحيحة');
    await this.erase(customerId);
  }

  /** The web form: phone and password instead of a session. */
  async deleteByCredentials(phone: string, password: string) {
    const raw = phone.replace(/[\s-]/g, '');
    if (!PHONE_REGEX.test(raw)) throw new BadRequestException('رقم الجوال غير صحيح');
    const c = await this.prisma.customer.findUnique({ where: { phone: normalizePhone(raw) }, select: { id: true, passwordHash: true, status: true } });
    if (!c || c.status === CustomerStatus.DELETED || !(await verifyPassword(password, c.passwordHash))) {
      throw new UnauthorizedException('رقم الجوال أو كلمة المرور غير صحيحة');
    }
    await this.erase(c.id);
  }

  private async erase(customerId: string) {
    const c = await this.prisma.customer.findUniqueOrThrow({ where: { id: customerId }, select: { code: true, notes: true } });
    const when = new Date().toISOString().slice(0, 10);
    await this.prisma.$transaction(async (tx) => {
      const own = { where: { customerId } };
      await tx.refreshToken.deleteMany(own);
      await tx.device.deleteMany(own);
      await tx.customerAddress.deleteMany(own);
      await tx.cart.deleteMany(own);
      await tx.wishlistItem.deleteMany(own);
      await tx.stockAlert.deleteMany(own);
      await tx.productReview.deleteMany(own);
      await tx.notification.deleteMany(own);
      await tx.chatMessage.deleteMany(own);
      await tx.preOrder.deleteMany({ where: { customerId, status: { not: 'CONVERTED' } } });
      // Usage statistics stay, no longer tied to the person.
      await tx.appSession.updateMany({ where: { customerId }, data: { customerId: null } });
      await tx.productView.updateMany({ where: { customerId }, data: { customerId: null } });
      await tx.searchLog.updateMany({ where: { customerId }, data: { customerId: null } });
      await tx.customer.update({
        where: { id: customerId },
        data: {
          name: 'عميل محذوف',
          phone: `deleted-${c.code}`,
          email: null,
          passwordHash: null,
          phoneVerified: false,
          gender: Gender.UNSPECIFIED,
          birthDate: null,
          area: null,
          status: CustomerStatus.DELETED,
          notes: [c.notes, `حذف العميل حسابه بنفسه في ${when}`].filter(Boolean).join('\n'),
        },
      });
    });
  }
}
