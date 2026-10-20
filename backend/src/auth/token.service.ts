import { Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { createHash, randomBytes } from 'node:crypto';
import { PrincipalType } from '../common/decorators/auth.decorators';
import { AccessTokenPayload } from '../common/guards/jwt-auth.guard';
import { PrismaService } from '../prisma/prisma.service';

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: string;
}

interface Principal {
  type: PrincipalType;
  id: string;
}

const sha256 = (v: string) => createHash('sha256').update(v).digest('hex');

/**
 * Short-lived JWT access tokens plus opaque, rotating refresh tokens.
 * Only a SHA-256 of each refresh token is stored. Presenting an already-rotated
 * token is treated as theft: every session of that principal is revoked.
 */
@Injectable()
export class TokenService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly jwt: JwtService,
    private readonly config: ConfigService,
  ) {}

  async issue(principal: Principal, permissions: string[] = []): Promise<TokenPair> {
    const payload: AccessTokenPayload = { sub: principal.id, typ: principal.type };
    if (principal.type === 'staff') payload.perms = permissions;

    const refreshToken = randomBytes(48).toString('base64url');
    const days = this.config.getOrThrow<number>('JWT_REFRESH_TTL_DAYS');
    await this.prisma.refreshToken.create({
      data: {
        tokenHash: sha256(refreshToken),
        expiresAt: new Date(Date.now() + days * 86_400_000),
        ...this.owner(principal),
      },
    });

    return {
      accessToken: this.jwt.sign(payload),
      refreshToken,
      expiresIn: this.config.getOrThrow<string>('JWT_ACCESS_TTL'),
    };
  }

  /** Consumes a refresh token and returns its owner; the caller issues a new pair. */
  async consume(refreshToken: string): Promise<Principal> {
    const tokenHash = sha256(refreshToken);
    const row = await this.prisma.refreshToken.findUnique({ where: { tokenHash } });
    if (!row) throw new UnauthorizedException('Invalid refresh token');

    const principal: Principal = row.userId
      ? { type: 'staff', id: row.userId }
      : { type: 'customer', id: row.customerId! };

    // Conditional update: two concurrent refreshes with the same token cannot both win.
    const { count } = await this.prisma.refreshToken.updateMany({
      where: { id: row.id, revokedAt: null, expiresAt: { gt: new Date() } },
      data: { revokedAt: new Date() },
    });
    if (count === 0) {
      if (row.revokedAt) await this.revokeAll(principal);
      throw new UnauthorizedException('Refresh token expired or already used');
    }
    return principal;
  }

  async revoke(refreshToken: string): Promise<void> {
    await this.prisma.refreshToken.updateMany({
      where: { tokenHash: sha256(refreshToken), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }

  async revokeAll(principal: Principal): Promise<void> {
    await this.prisma.refreshToken.updateMany({
      where: { ...this.owner(principal), revokedAt: null },
      data: { revokedAt: new Date() },
    });
  }

  private owner(p: Principal) {
    return p.type === 'staff' ? { userId: p.id } : { customerId: p.id };
  }
}
