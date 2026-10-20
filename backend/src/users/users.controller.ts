import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { CreateRoleDto, CreateUserDto, UpdateRoleDto, UpdateUserDto } from './dto/users.dto';
import { UsersService } from './users.service';

@ApiTags('Admin · Users & Roles')
@Controller('admin')
export class UsersController {
  constructor(private readonly users: UsersService) {}

  @RequirePermissions('users.manage')
  @Get('users')
  listUsers(@Query() q: PaginationQueryDto) {
    return this.users.listUsers(q);
  }

  @RequirePermissions('users.manage')
  @Get('users/:id')
  getUser(@Param('id', ParseUUIDPipe) id: string) {
    return this.users.getUser(id);
  }

  @RequirePermissions('users.manage')
  @Post('users')
  createUser(@Body() dto: CreateUserDto, @CurrentUser() actor: AuthPrincipal) {
    return this.users.createUser(dto, actor.id);
  }

  @RequirePermissions('users.manage')
  @Patch('users/:id')
  updateUser(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateUserDto, @CurrentUser() actor: AuthPrincipal) {
    return this.users.updateUser(id, dto, actor.id);
  }

  @RequirePermissions('roles.manage')
  @Get('permissions')
  listPermissions() {
    return this.users.listPermissions();
  }

  @RequirePermissions('roles.manage')
  @Get('roles')
  listRoles() {
    return this.users.listRoles();
  }

  @RequirePermissions('roles.manage')
  @Post('roles')
  createRole(@Body() dto: CreateRoleDto, @CurrentUser() actor: AuthPrincipal) {
    return this.users.createRole(dto, actor.id);
  }

  @RequirePermissions('roles.manage')
  @Patch('roles/:id')
  updateRole(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateRoleDto, @CurrentUser() actor: AuthPrincipal) {
    return this.users.updateRole(id, dto, actor.id);
  }

  @RequirePermissions('roles.manage')
  @Delete('roles/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteRole(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() actor: AuthPrincipal) {
    return this.users.deleteRole(id, actor.id);
  }
}
