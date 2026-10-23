import { BadRequestException, ConflictException, Injectable, UnauthorizedException } from '@nestjs/common';
import { SmsService } from '../sms/sms.service';
import { currencyOfRegion } from '../common/yemen';
import { CustomerStatus, UserStatus } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal } from '../common/decorators/auth.decorators';
import { SequenceService } from '../common/sequence.service';
import { PrismaService } from '../prisma/prisma.service';
import { ChangePasswordDto, CustomerLoginDto, CustomerRegisterDto, normalizePhone, PasswordResetDto, PHONE_REGEX, StaffLoginDto } from './dto/auth.dto';
import { hashPassword, verifyPassword } from './password';
import { TokenService } from './token.service';

const INVALID_CREDENTIALS = 'Invalid credentials';

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokenService,
    private readonly sequences: SequenceService,
    private readonly audit: AuditService,
    private readonly sms: SmsService,
  ) {}

  // ---------------------------------------------------------------- staff ---

  async staffLogin(dto: StaffLoginDto) {
    const login = (dto.phone ?? dto.email ?? '').trim().toLowerCase();
    if (!login) throw new UnauthorizedException(INVALID_CREDENTIALS);
    const phone = PHONE_REGEX.test(login.replace(/[\s-]/g, '')) ? normalizePhone(login.replace(/[\s-]/g, '')) : null;
    const user = await this.prisma.user.findFirst({
      where: { OR: [...(phone ? [{ phone }] : []), { email: login }, { username: login }] },
    });
    const ok = await verifyPassword(dto.password, user?.passwordHash);
    if (!user || !ok) throw new UnauthorizedException(INVALID_CREDENTIALS);
    if (user.status !== UserStatus.ACTIVE) throw new UnauthorizedException('Account is not active');

    await this.prisma.user.update({ where: { id: user.id }, data: { lastLoginAt: new Date() } });
    await this.audit.log({ userId: user.id, action: 'LOGIN', entityType: 'User', entityId: user.id });
    return this.staffSession(user.id);
  }

  async staffProfile(userId: string) {
    const user = await this.prisma.user.findUniqueOrThrow({
      where: { id: userId },
      select: {
        id: true,
        name: true,
        email: true,
        phone: true,
        status: true,
        saleCurrency: true,
        posSettings: true,
        roles: { select: { role: { select: { id: true, name: true } } } },
      },
    });
    return {
      ...user,
      roles: user.roles.map((r) => r.role).filter((r) => !r.name.startsWith('user_')),
      permissions: await this.permissionsOf(userId),
    };
  }

  async permissionsOf(userId: string): Promise<string[]> {
    const rows = await this.prisma.permission.findMany({
      where: { roles: { some: { role: { users: { some: { userId } } } } } },
      select: { key: true },
      orderBy: { key: 'asc' },
    });
    return rows.map((r) => r.key);
  }

  private async staffSession(userId: string) {
    const profile = await this.staffProfile(userId);
    const tokens = await this.tokens.issue({ type: 'staff', id: userId }, profile.permissions);
    return { ...tokens, user: profile };
  }

  // ------------------------------------------------------------- customer ---

  async customerRegister(dto: CustomerRegisterDto) {
    const phone = normalizePhone(dto.phone);
    const email = dto.email?.toLowerCase();
    const clash = await this.prisma.customer.findFirst({
      where: { OR: [{ phone }, ...(email ? [{ email }] : [])] },
      select: { phone: true },
    });
    if (clash) {
      throw new ConflictException(clash.phone === phone ? 'Phone number already registered' : 'Email already registered');
    }

    if (dto.governorate === 'تعز' && !dto.area) throw new BadRequestException('Choose الحوبان or المدينة for Taiz');
    const otpRequired = await this.sms.otpRequired();
    const passwordHash = await hashPassword(dto.password);
    const customer = await this.prisma.$transaction(async (tx) => {
      if (otpRequired) await this.sms.verifyOtp(tx, phone, dto.otp);
      return tx.customer.create({
        data: {
          code: await this.sequences.next(tx, 'CUS', { year: 0 }),
          name: dto.name.trim(),
          phone,
          email,
          passwordHash,
          phoneVerified: otpRequired,
          governorate: dto.governorate,
          area: dto.governorate === 'تعز' ? dto.area : null,
        },
      });
    });
    return this.customerSession(customer.id);
  }

  async customerLogin(dto: CustomerLoginDto) {
    const customer = await this.prisma.customer.findUnique({ where: { phone: normalizePhone(dto.phone) } });
    const ok = await verifyPassword(dto.password, customer?.passwordHash);
    if (!customer || !ok) throw new UnauthorizedException(INVALID_CREDENTIALS);
    if (customer.status !== CustomerStatus.ACTIVE) throw new UnauthorizedException('Account is not active');
    return this.customerSession(customer.id);
  }

  async customerProfile(customerId: string) {
    const c = await this.prisma.customer.findUniqueOrThrow({
      where: { id: customerId },
      select: {
        id: true,
        code: true,
        name: true,
        phone: true,
        email: true,
        gender: true,
        birthDate: true,
        loyaltyPoints: true,
        isMerchant: true,
        merchantDiscount: true,
        creditLimit: true,
        phoneVerified: true,
        governorate: true,
        area: true,
      },
    });
    /** The rial the customer's region uses (old notes YER or new notes YEQ). */
    return { ...c, preferredCurrency: currencyOfRegion(c.governorate, c.area) };
  }

  private async customerSession(customerId: string) {
    const tokens = await this.tokens.issue({ type: 'customer', id: customerId });
    return { ...tokens, customer: await this.customerProfile(customerId) };
  }

  // --------------------------------------------------------------- shared ---

  async refresh(refreshToken: string) {
    const principal = await this.tokens.consume(refreshToken);
    if (principal.type === 'staff') {
      const user = await this.prisma.user.findUnique({ where: { id: principal.id }, select: { status: true } });
      if (user?.status !== UserStatus.ACTIVE) throw new UnauthorizedException('Account is not active');
      return this.staffSession(principal.id);
    }
    const customer = await this.prisma.customer.findUnique({ where: { id: principal.id }, select: { status: true } });
    if (customer?.status !== CustomerStatus.ACTIVE) throw new UnauthorizedException('Account is not active');
    return this.customerSession(principal.id);
  }

  async logout(refreshToken: string) {
    await this.tokens.revoke(refreshToken);
  }

  /** Sets a new password after the texted reset code checks out, and signs the customer in. */
  async resetPassword(dto: PasswordResetDto) {
    const phone = normalizePhone(dto.phone);
    const customer = await this.prisma.customer.findUnique({ where: { phone }, select: { id: true, status: true } });
    if (!customer) throw new BadRequestException('No account uses this phone number');
    if (customer.status !== CustomerStatus.ACTIVE) throw new UnauthorizedException('Account is not active');
    const passwordHash = await hashPassword(dto.newPassword);
    await this.prisma.$transaction(async (tx) => {
      await this.sms.verifyOtp(tx, phone, dto.code, 'RESET');
      await tx.customer.update({ where: { id: customer.id }, data: { passwordHash, phoneVerified: true } });
    });
    await this.tokens.revokeAll({ type: 'customer', id: customer.id });
    await this.audit.log({ action: 'UPDATE', entityType: 'CustomerPasswordReset', entityId: customer.id });
    return this.customerSession(customer.id);
  }

  async changePassword(principal: AuthPrincipal, dto: ChangePasswordDto) {
    const stored =
      principal.type === 'staff'
        ? (await this.prisma.user.findUniqueOrThrow({ where: { id: principal.id } })).passwordHash
        : (await this.prisma.customer.findUniqueOrThrow({ where: { id: principal.id } })).passwordHash;
    if (!(await verifyPassword(dto.currentPassword, stored))) {
      throw new UnauthorizedException('Current password is incorrect');
    }
    const passwordHash = await hashPassword(dto.newPassword);
    if (principal.type === 'staff') {
      await this.prisma.user.update({ where: { id: principal.id }, data: { passwordHash } });
    } else {
      await this.prisma.customer.update({ where: { id: principal.id }, data: { passwordHash } });
    }
    // Sign out every other device.
    await this.tokens.revokeAll(principal);
  }
}
