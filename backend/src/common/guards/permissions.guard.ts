import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { AUDIENCE_KEY, AuthPrincipal, PERMISSIONS_KEY, PrincipalType } from '../decorators/auth.decorators';

@Injectable()
export class PermissionsGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(ctx: ExecutionContext): boolean {
    const targets = [ctx.getHandler(), ctx.getClass()];
    const audience = this.reflector.getAllAndOverride<PrincipalType | undefined>(AUDIENCE_KEY, targets);
    const required = this.reflector.getAllAndOverride<string[] | undefined>(PERMISSIONS_KEY, targets) ?? [];
    const user: AuthPrincipal | undefined = ctx.switchToHttp().getRequest().user;

    if (audience && user?.type !== audience) {
      throw new ForbiddenException(`This endpoint is only available to ${audience} accounts`);
    }
    const missing = required.filter((p) => !user?.permissions.includes(p));
    if (missing.length) {
      throw new ForbiddenException(`Missing permission: ${missing.join(', ')}`);
    }
    return true;
  }
}
