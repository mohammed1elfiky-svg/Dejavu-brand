import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '../generated/prisma/client';
import { normalizePhone } from '../auth/dto/auth.dto';
import { hashPassword } from '../auth/password';
import { TokenService } from '../auth/token.service';
import { AuditService } from '../common/audit.service';
import { PaginationQueryDto, paginated } from '../common/dto/pagination.dto';
import { PrismaService } from '../prisma/prisma.service';
import { CreateRoleDto, CreateUserDto, UpdateRoleDto, UpdateUserDto } from './dto/users.dto';

const userSelect = {
  id: true,
  name: true,
  email: true,
  username: true,
  phone: true,
  saleCurrency: true,
  status: true,
  lastLoginAt: true,
  createdAt: true,
  roles: {
    select: { role: { select: { id: true, name: true, isSystem: true, permissions: { select: { permission: { select: { key: true } } } } } } },
  },
  cashBoxAccess: { select: { cashBoxId: true } },
} satisfies Prisma.UserSelect;

type UserRow = Prisma.UserGetPayload<{ select: typeof userSelect }>;

/** Each employee's own permissions live in a private role named after them. */
const personalRoleName = (userId: string) => `user_${userId.replace(/-/g, '')}`;

const presentUser = (u: UserRow) => {
  const personal = u.roles.find((r) => r.role.name === personalRoleName(u.id));
  const keys = (r: UserRow['roles'][number]) => r.role.permissions.map((p) => p.permission.key);
  return {
    ...u,
    cashBoxAccess: undefined,
    roles: u.roles.filter((r) => r !== personal).map((r) => ({ id: r.role.id, name: r.role.name })),
    /** Permissions granted to this employee directly. */
    customPermissions: personal ? keys(personal).sort() : [],
    /** Everything the employee can do (roles + own permissions). */
    permissions: [...new Set(u.roles.flatMap(keys))].sort(),
    cashBoxIds: u.cashBoxAccess.map((a) => a.cashBoxId),
  };
};

@Injectable()
export class UsersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly tokens: TokenService,
    private readonly audit: AuditService,
  ) {}

  // ---------------------------------------------------------------- users ---

  async listUsers(q: PaginationQueryDto) {
    const [rows, total] = await Promise.all([
      this.prisma.user.findMany({ select: userSelect, orderBy: { createdAt: 'asc' }, skip: q.skip, take: q.pageSize }),
      this.prisma.user.count(),
    ]);
    return paginated(rows.map(presentUser), total, q);
  }

  async getUser(id: string) {
    const user = await this.prisma.user.findUnique({ where: { id }, select: userSelect });
    if (!user) throw new NotFoundException('User not found');
    return presentUser(user);
  }

  async createUser(dto: CreateUserDto, actorId: string) {
    await this.assertRolesExist(dto.roleIds);
    if (!dto.roleIds.length && !dto.permissions?.length) throw new BadRequestException('Give the employee a role or some permissions');
    if (!dto.phone && !dto.email && !dto.username) throw new BadRequestException('Enter the mobile number the employee signs in with');
    await this.assertUsernameFree(dto.username);
    await this.assertPhoneFree(dto.phone);
    const saleCurrency = dto.saleCurrency ?? (await this.defaultSaleCurrency(dto.roleIds));
    const created = await this.prisma.$transaction(async (tx) => {
      const user = await tx.user.create({
        data: {
          name: dto.name.trim(),
          email: dto.email?.toLowerCase() ?? null,
          username: dto.username ?? null,
          phone: dto.phone ? normalizePhone(dto.phone) : null,
          passwordHash: await hashPassword(dto.password),
          saleCurrency,
          roles: { create: dto.roleIds.map((roleId) => ({ roleId })) },
        },
        select: { id: true },
      });
      if (dto.permissions?.length) await this.writePermissions(tx, user.id, dto.name, dto.permissions);
      if (dto.cashBoxIds?.length) await this.writeCashBoxes(tx, user.id, dto.cashBoxIds);
      return user;
    });
    const user = await this.getUser(created.id);
    await this.audit.log({ userId: actorId, action: 'CREATE', entityType: 'User', entityId: user.id, after: user });
    return user;
  }

  /** Replaces the employee's own permissions (their roles stay as they are). */
  async setPermissions(id: string, permissions: string[], actorId: string) {
    const before = await this.getUser(id);
    await this.prisma.$transaction((tx) => this.writePermissions(tx, id, before.name, permissions));
    await this.tokens.revokeAll({ type: 'staff', id });
    const after = await this.getUser(id);
    await this.audit.log({ userId: actorId, action: 'UPDATE', entityType: 'UserPermissions', entityId: id, before: before.customPermissions, after: permissions });
    return after;
  }

  /** Limits the employee to these boxes (empty = back to their own drawers only). */
  async setCashBoxes(id: string, cashBoxIds: string[], actorId: string) {
    const before = await this.getUser(id);
    await this.prisma.$transaction((tx) => this.writeCashBoxes(tx, id, cashBoxIds));
    const after = await this.getUser(id);
    await this.audit.log({ userId: actorId, action: 'UPDATE', entityType: 'UserCashBoxes', entityId: id, before: before.cashBoxIds, after: cashBoxIds });
    return after;
  }

  private async writePermissions(tx: Prisma.TransactionClient, userId: string, name: string, keys: string[]) {
    const perms = await tx.permission.findMany({ where: { key: { in: keys } }, select: { id: true, key: true } });
    const unknown = keys.filter((k) => !perms.some((p) => p.key === k));
    if (unknown.length) throw new BadRequestException(`Unknown permissions: ${unknown.join(', ')}`);
    const roleName = personalRoleName(userId);
    const role = await tx.role.upsert({
      where: { name: roleName },
      update: { description: `صلاحيات خاصة: ${name}` },
      create: { name: roleName, description: `صلاحيات خاصة: ${name}` },
    });
    await tx.rolePermission.deleteMany({ where: { roleId: role.id } });
    if (keys.length) await tx.rolePermission.createMany({ data: perms.map((p) => ({ roleId: role.id, permissionId: p.id })) });
    await tx.userRole.upsert({
      where: { userId_roleId: { userId, roleId: role.id } },
      update: {},
      create: { userId, roleId: role.id },
    });
  }

  private async writeCashBoxes(tx: Prisma.TransactionClient, userId: string, boxIds: string[]) {
    if ((await tx.cashBox.count({ where: { id: { in: boxIds } } })) !== boxIds.length) throw new BadRequestException('Some cash boxes do not exist');
    await tx.cashBoxAccess.deleteMany({ where: { userId } });
    if (boxIds.length) await tx.cashBoxAccess.createMany({ data: boxIds.map((cashBoxId) => ({ userId, cashBoxId })) });
  }

  private async assertPhoneFree(phone: string | undefined, exceptId?: string) {
    if (!phone) return;
    const taken = await this.prisma.user.findFirst({ where: { phone: normalizePhone(phone), id: exceptId ? { not: exceptId } : undefined } });
    if (taken) throw new ConflictException('This mobile number is already used by another employee');
  }

  private async assertUsernameFree(username: string | null | undefined, exceptId?: string) {
    if (!username) return;
    const taken = await this.prisma.user.findFirst({ where: { OR: [{ username }, { email: username }], id: exceptId ? { not: exceptId } : undefined } });
    if (taken) throw new ConflictException('This username is already used');
  }

  async updateUser(id: string, dto: UpdateUserDto, actorId: string) {
    const before = await this.getUser(id);
    if (id === actorId && (dto.status && dto.status !== 'ACTIVE')) {
      throw new BadRequestException('You cannot deactivate your own account');
    }
    if (dto.roleIds) await this.assertRolesExist(dto.roleIds);
    await this.assertUsernameFree(dto.username, id);
    await this.assertPhoneFree(dto.phone, id);

    const user = await this.prisma.$transaction(async (tx) => {
      const losesAdmin =
        before.roles.some((r) => r.name === 'admin') &&
        ((dto.status && dto.status !== 'ACTIVE') ||
          (dto.roleIds && !(await tx.role.count({ where: { id: { in: dto.roleIds }, name: 'admin' } }))));
      if (losesAdmin) {
        const others = await tx.user.count({
          where: { id: { not: id }, status: 'ACTIVE', roles: { some: { role: { name: 'admin' } } } },
        });
        if (others === 0) throw new BadRequestException('At least one active admin must remain');
      }
      if (dto.roleIds) {
        // The employee's own-permissions role is kept.
        await tx.userRole.deleteMany({ where: { userId: id, role: { name: { not: personalRoleName(id) } } } });
        await tx.userRole.createMany({ data: dto.roleIds.map((roleId) => ({ userId: id, roleId })), skipDuplicates: true });
      }
      return tx.user.update({
        where: { id },
        data: {
          name: dto.name?.trim(),
          username: dto.username === undefined ? undefined : dto.username,
          phone: dto.phone ? normalizePhone(dto.phone) : undefined,
          status: dto.status,
          saleCurrency: dto.saleCurrency,
          passwordHash: dto.password ? await hashPassword(dto.password) : undefined,
        },
        select: userSelect,
      });
    });

    // Password resets, deactivation and role changes all invalidate existing sessions.
    if (dto.password || dto.roleIds || (dto.status && dto.status !== 'ACTIVE')) {
      await this.tokens.revokeAll({ type: 'staff', id });
    }
    const after = presentUser(user);
    await this.audit.log({ userId: actorId, action: 'UPDATE', entityType: 'User', entityId: id, before, after });
    return after;
  }

  /** Cashiers sell in Yemeni rials, everyone else (sales staff, managers) in SAR. */
  private async defaultSaleCurrency(roleIds: string[]) {
    const cashier = await this.prisma.role.count({ where: { id: { in: roleIds }, name: 'cashier' } });
    return cashier ? 'YER' : 'SAR';
  }

  private async assertRolesExist(roleIds: string[]) {
    const count = await this.prisma.role.count({ where: { id: { in: roleIds } } });
    if (count !== roleIds.length) throw new BadRequestException('One or more roles do not exist');
  }

  // ---------------------------------------------------------------- roles ---

  listPermissions() {
    return this.prisma.permission.findMany({ orderBy: [{ module: 'asc' }, { key: 'asc' }] });
  }

  async listRoles() {
    const roles = await this.prisma.role.findMany({
      orderBy: { name: 'asc' },
      include: {
        permissions: { select: { permission: { select: { key: true } } } },
        _count: { select: { users: true } },
      },
    });
    return roles.filter((r) => !r.name.startsWith('user_')).map(({ permissions, _count, ...r }) => ({
      ...r,
      userCount: _count.users,
      permissions: permissions.map((p) => p.permission.key).sort(),
    }));
  }

  async createRole(dto: CreateRoleDto, actorId: string) {
    const permissionIds = await this.resolvePermissions(dto.permissions);
    const role = await this.prisma.role.create({
      data: {
        name: dto.name,
        description: dto.description,
        permissions: { create: permissionIds.map((permissionId) => ({ permissionId })) },
      },
    });
    await this.audit.log({ userId: actorId, action: 'CREATE', entityType: 'Role', entityId: role.id, after: dto });
    return this.roleById(role.id);
  }

  async updateRole(id: string, dto: UpdateRoleDto, actorId: string) {
    const role = await this.prisma.role.findUnique({ where: { id } });
    if (!role) throw new NotFoundException('Role not found');
    if (role.name === 'admin' && dto.permissions) {
      throw new BadRequestException('The admin role always has every permission');
    }
    const permissionIds = dto.permissions ? await this.resolvePermissions(dto.permissions) : undefined;

    await this.prisma.$transaction(async (tx) => {
      if (permissionIds) {
        await tx.rolePermission.deleteMany({ where: { roleId: id } });
        await tx.rolePermission.createMany({ data: permissionIds.map((permissionId) => ({ roleId: id, permissionId })) });
      }
      await tx.role.update({ where: { id }, data: { description: dto.description } });
    });

    if (permissionIds) {
      // Access tokens carry permissions; force holders of this role to refresh.
      const holders = await this.prisma.userRole.findMany({ where: { roleId: id }, select: { userId: true } });
      await Promise.all(holders.map((h) => this.tokens.revokeAll({ type: 'staff', id: h.userId })));
    }
    await this.audit.log({ userId: actorId, action: 'UPDATE', entityType: 'Role', entityId: id, after: dto });
    return this.roleById(id);
  }

  async deleteRole(id: string, actorId: string) {
    const role = await this.prisma.role.findUnique({ where: { id }, include: { _count: { select: { users: true } } } });
    if (!role) throw new NotFoundException('Role not found');
    if (role.isSystem) throw new BadRequestException('System roles cannot be deleted');
    if (role._count.users > 0) throw new ConflictException('Role is still assigned to users');
    await this.prisma.role.delete({ where: { id } });
    await this.audit.log({ userId: actorId, action: 'DELETE', entityType: 'Role', entityId: id, before: role });
  }

  private async roleById(id: string) {
    return (await this.listRoles()).find((r) => r.id === id)!;
  }

  private async resolvePermissions(keys: string[]) {
    const rows = await this.prisma.permission.findMany({ where: { key: { in: keys } }, select: { id: true, key: true } });
    const unknown = keys.filter((k) => !rows.some((r) => r.key === k));
    if (unknown.length) throw new BadRequestException(`Unknown permissions: ${unknown.join(', ')}`);
    return rows.map((r) => r.id);
  }
}
