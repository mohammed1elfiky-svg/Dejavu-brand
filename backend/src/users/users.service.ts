import { BadRequestException, ConflictException, Injectable, NotFoundException } from '@nestjs/common';
import { Prisma } from '../generated/prisma/client';
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
  phone: true,
  status: true,
  lastLoginAt: true,
  createdAt: true,
  roles: { select: { role: { select: { id: true, name: true } } } },
} satisfies Prisma.UserSelect;

type UserRow = Prisma.UserGetPayload<{ select: typeof userSelect }>;
const presentUser = (u: UserRow) => ({ ...u, roles: u.roles.map((r) => r.role) });

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
    const user = await this.prisma.user.create({
      data: {
        name: dto.name.trim(),
        email: dto.email.toLowerCase(),
        phone: dto.phone,
        passwordHash: await hashPassword(dto.password),
        roles: { create: dto.roleIds.map((roleId) => ({ roleId })) },
      },
      select: userSelect,
    });
    await this.audit.log({ userId: actorId, action: 'CREATE', entityType: 'User', entityId: user.id, after: user });
    return presentUser(user);
  }

  async updateUser(id: string, dto: UpdateUserDto, actorId: string) {
    const before = await this.getUser(id);
    if (id === actorId && (dto.status && dto.status !== 'ACTIVE')) {
      throw new BadRequestException('You cannot deactivate your own account');
    }
    if (dto.roleIds) await this.assertRolesExist(dto.roleIds);

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
        await tx.userRole.deleteMany({ where: { userId: id } });
        await tx.userRole.createMany({ data: dto.roleIds.map((roleId) => ({ userId: id, roleId })) });
      }
      return tx.user.update({
        where: { id },
        data: {
          name: dto.name?.trim(),
          phone: dto.phone,
          status: dto.status,
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
    return roles.map(({ permissions, _count, ...r }) => ({
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
