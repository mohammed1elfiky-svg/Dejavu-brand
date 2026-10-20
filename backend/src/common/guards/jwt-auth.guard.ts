import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { JwtService } from '@nestjs/jwt';
import { AuthPrincipal, IS_PUBLIC_KEY } from '../decorators/auth.decorators';

export interface AccessTokenPayload {
  sub: string;
  typ: AuthPrincipal['type'];
  perms?: string[];
}

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly jwt: JwtService,
    private readonly reflector: Reflector,
  ) {}

  canActivate(ctx: ExecutionContext): boolean {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [ctx.getHandler(), ctx.getClass()]);
    const req = ctx.switchToHttp().getRequest();
    const [scheme, token] = (req.headers.authorization ?? '').split(' ');

    if (scheme !== 'Bearer' || !token) {
      if (isPublic) return true;
      throw new UnauthorizedException('Missing access token');
    }

    try {
      const payload = this.jwt.verify<AccessTokenPayload>(token);
      req.user = { id: payload.sub, type: payload.typ, permissions: payload.perms ?? [] } satisfies AuthPrincipal;
      return true;
    } catch {
      if (isPublic) return true;
      throw new UnauthorizedException('Invalid or expired access token');
    }
  }
}
