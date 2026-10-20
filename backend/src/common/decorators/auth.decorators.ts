import { createParamDecorator, ExecutionContext, SetMetadata, applyDecorators } from '@nestjs/common';
import { ApiBearerAuth } from '@nestjs/swagger';

export const IS_PUBLIC_KEY = 'auth:isPublic';
export const AUDIENCE_KEY = 'auth:audience';
export const PERMISSIONS_KEY = 'auth:permissions';

export type PrincipalType = 'staff' | 'customer';

export interface AuthPrincipal {
  id: string;
  type: PrincipalType;
  permissions: string[];
}

/** Skips authentication. A valid token is still decoded when present. */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);

/** Restricts the route to staff users of the admin app. */
export const StaffOnly = () => applyDecorators(SetMetadata(AUDIENCE_KEY, 'staff'), ApiBearerAuth());

/** Restricts the route to shoppers of the customer app. */
export const CustomerOnly = () => applyDecorators(SetMetadata(AUDIENCE_KEY, 'customer'), ApiBearerAuth());

/** Staff route that requires every listed permission key. */
export const RequirePermissions = (...permissions: string[]) =>
  applyDecorators(SetMetadata(PERMISSIONS_KEY, permissions), StaffOnly());

export const CurrentUser = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): AuthPrincipal => ctx.switchToHttp().getRequest().user,
);
