import { BadRequestException, Body, Controller, HttpCode, HttpStatus, Injectable, NotFoundException, Param, ParseUUIDPipe, Post } from '@nestjs/common';
import { ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { randomInt } from 'node:crypto';
import { IsBoolean, IsIn, IsOptional } from 'class-validator';
import { hashPassword } from '../auth/password';
import { TokenService } from '../auth/token.service';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { CustomerStatus } from '../generated/prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { dialable, isYemeniMobile, SmsService } from '../sms/sms.service';

export class SendPasswordDto {
  @ApiPropertyOptional({ default: true, description: 'Text it to a Yemeni number from the store phone' })
  @IsOptional()
  @IsBoolean()
  sendSms?: boolean;
}

export class IssueCodeDto {
  @ApiPropertyOptional({ enum: ['REGISTER', 'RESET'], default: 'REGISTER' })
  @IsOptional()
  @IsIn(['REGISTER', 'RESET'])
  purpose?: 'REGISTER' | 'RESET';
}

/** Letters and digits that cannot be mixed up when read out (no 0/o, 1/l/i). */
const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
const randomPassword = () => Array.from({ length: 8 }, () => ALPHABET[randomInt(0, ALPHABET.length)]).join('');

/** The owner lets customers into the app: approves sign-ups, sends passwords and codes. */
@Injectable()
export class CustomerAccessService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly sms: SmsService,
    private readonly tokens: TokenService,
    private readonly audit: AuditService,
  ) {}

  private async customer(id: string) {
    const c = await this.prisma.customer.findUnique({ where: { id }, select: { id: true, name: true, phone: true, status: true, code: true } });
    if (!c) throw new NotFoundException('Customer not found');
    if (c.code === 'CUS-WALKIN' || c.status === CustomerStatus.DELETED) throw new BadRequestException('لا يمكن تعديل هذا العميل');
    return c;
  }

  /** A sign-up that never entered its code may sign in with phone and password from now on. */
  async approve(id: string, userId: string) {
    const c = await this.customer(id);
    if (c.status !== CustomerStatus.PENDING) throw new BadRequestException('العميل ليس بانتظار التحقق');
    const updated = await this.prisma.customer.update({ where: { id }, data: { status: CustomerStatus.ACTIVE }, select: { id: true, status: true } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'CustomerApproval', entityId: id });
    return updated;
  }

  /**
   * Gives the customer a new random password (also a customer added at the till, who has
   * none yet) and texts it to Yemeni numbers. Returned once so the owner can pass it on
   * otherwise (e.g. WhatsApp). The customer can change it in the app.
   */
  async sendPassword(id: string, dto: SendPasswordDto, userId: string) {
    const c = await this.customer(id);
    const password = randomPassword();
    await this.prisma.customer.update({
      where: { id },
      data: { passwordHash: await hashPassword(password), ...(c.status === CustomerStatus.PENDING ? { status: CustomerStatus.ACTIVE } : {}) },
    });
    await this.tokens.revokeAll({ type: 'customer', id });
    const message = `مرحباً ${c.name}، حسابك في تطبيق Dejavu جاهز.\nرقم الدخول: ${c.phone}\nكلمة المرور: ${password}\nيمكنك تغييرها من صفحة حسابي.`;
    const sms = dto.sendSms !== false && isYemeniMobile(c.phone);
    if (sms) await this.sms.enqueue(c.phone, message, 'OTHER');
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'CustomerPassword', entityId: id, after: { smsQueued: sms } });
    return { password, smsQueued: sms, phone: dialable(c.phone), message };
  }

  /** A sign-up or reset code to hand over yourself, e.g. to a non-Yemeni number on WhatsApp. */
  async issueCode(id: string, dto: IssueCodeDto, userId: string) {
    const c = await this.customer(id);
    const issued = await this.sms.issueCode(c.phone, dto.purpose ?? (c.status === CustomerStatus.PENDING ? 'REGISTER' : 'RESET'));
    await this.audit.log({ userId, action: 'CREATE', entityType: 'CustomerCode', entityId: id });
    return { ...issued, phone: dialable(c.phone) };
  }
}

@ApiTags('Admin · Customers')
@RequirePermissions('customers.write')
@Controller('admin/customers/:id')
export class AdminCustomerAccessController {
  constructor(private readonly access: CustomerAccessService) {}

  @Post('approve')
  @HttpCode(HttpStatus.OK)
  approve(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.access.approve(id, u.id);
  }

  @Post('password')
  @HttpCode(HttpStatus.OK)
  password(@Param('id', ParseUUIDPipe) id: string, @Body() dto: SendPasswordDto, @CurrentUser() u: AuthPrincipal) {
    return this.access.sendPassword(id, dto, u.id);
  }

  @Post('code')
  @HttpCode(HttpStatus.OK)
  code(@Param('id', ParseUUIDPipe) id: string, @Body() dto: IssueCodeDto, @CurrentUser() u: AuthPrincipal) {
    return this.access.issueCode(id, dto, u.id);
  }
}
