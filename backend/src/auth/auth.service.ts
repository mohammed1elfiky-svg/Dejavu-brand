import { ConflictException, Injectable, UnauthorizedException } from '@nestjs/common';
import { CustomerStatus, UserStatus } from '../generated/prisma/client';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal } from '../common/decorators/auth.decorators';
import { SequenceService } from '../common/sequence.service';
import { PrismaService } from '../prisma/prisma.service';
import { ChangePasswordDto, CustomerLoginDto, CustomerRegisterDto, normalizePhone, StaffLoginDto } from './dto/auth.dto';
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
  ) {}

  // ---------------------------------------------------------------- staff ---

  async staffLogin(dto: StaffLoginDto) {
    const user = await this.prisma.user.findUnique({ where: { email: dto.email.toLowerCase() } });
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
        roles: { select: { role: { select: { id: true, name: true } } } },
      },
    });
    return {
      ...user,
      roles: user.roles.map((r) => r.role),
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

    const passwordHash = await hashPassword(dto.password);
    const customer = await this.prisma.$transaction(async (tx) =>
      tx.customer.create({
        data: {
          code: await this.sequences.next(tx, 'CUS', { year: 0 }),
          name: dto.name.trim(),
          phone,
          email,
          passwordHash,
        },
      }),
    );
    return this.customerSession(customer.id);
  }

  async customerLogin(dto: CustomerLoginDto) {
    const customer = await this.prisma.customer.findUnique({ where: { phone: normalizePhone(dto.phone) } });
    const ok = await verifyPassword(dto.password, customer?.passwordHash);
    if (!customer || !ok) throw new UnauthorizedException(INVALID_CREDENTIALS);
    if (customer.status !== CustomerStatus.ACTIVE) throw new UnauthorizedException('Account is not active');
    return this.customerSession(customer.id);
  }

  customerProfile(customerId: string) {
    return this.prisma.customer.findUniqueOrThrow({
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
        phoneVerified: true,
      },
    });
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
