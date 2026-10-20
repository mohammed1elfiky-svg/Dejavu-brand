import { ArgumentsHost, Catch, ExceptionFilter, HttpStatus, Logger } from '@nestjs/common';
import { Response } from 'express';
import { Prisma } from '../../generated/prisma/client';

/** Messages raised by the database integrity triggers (see the db_integrity_rules migration). */
const TRIGGER_MESSAGES = [
  'is not postable or inactive',
  'is posted; create a reversal',
  'is immutable',
  'Cannot delete posted journal entry',
  'is not inside an open fiscal period',
  'is unbalanced',
  'needs at least two lines',
  'Insert journal entries as DRAFT',
  'append-only',
];

/** Maps database errors to HTTP responses instead of leaking 500s. */
@Catch(Prisma.PrismaClientKnownRequestError)
export class PrismaExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(PrismaExceptionFilter.name);

  catch(err: Prisma.PrismaClientKnownRequestError, host: ArgumentsHost) {
    const res = host.switchToHttp().getResponse<Response>();
    const [status, message] = this.map(err);
    if (status >= 500) this.logger.error(err.message, err.stack);
    res.status(status).json({ statusCode: status, message, code: err.code });
  }

  private map(err: Prisma.PrismaClientKnownRequestError): [number, string] {
    switch (err.code) {
      case 'P2002': {
        const target = uniqueTarget(err);
        return [HttpStatus.CONFLICT, target ? `A record with this ${target} already exists` : 'Duplicate value'];
      }
      case 'P2003':
        return [HttpStatus.CONFLICT, 'Operation violates a reference to another record'];
      case 'P2025':
        return [HttpStatus.NOT_FOUND, 'Record not found'];
    }
    const detail = dbMessage(err);
    if (TRIGGER_MESSAGES.some((m) => detail.includes(m)) || detail.includes('violates check constraint')) {
      return [HttpStatus.UNPROCESSABLE_ENTITY, detail];
    }
    return [HttpStatus.INTERNAL_SERVER_ERROR, 'Internal server error'];
  }
}

function uniqueTarget(err: Prisma.PrismaClientKnownRequestError): string | undefined {
  const meta = err.meta as Record<string, unknown> | undefined;
  if (meta?.target) return ([] as string[]).concat(meta.target as string[] | string).join(', ');

  // Driver adapters report the index name instead, e.g. "products_slug_key" on table "products".
  const cause = (meta?.driverAdapterError as { cause?: { constraint?: { index?: string }; table?: string } })?.cause;
  const index = cause?.constraint?.index;
  if (!index) return undefined;
  const prefix = cause.table ? `${cause.table}_` : '';
  return index.replace(prefix, '').replace(/_key$/, '').replace(/_/g, ' ');
}

/** The original PostgreSQL message, without Prisma's wrapping. */
function dbMessage(err: Prisma.PrismaClientKnownRequestError): string {
  const cause = (err.meta?.driverAdapterError as { cause?: { originalMessage?: string } } | undefined)?.cause;
  if (cause?.originalMessage) return cause.originalMessage;
  return /Message: `([^`]*)`/.exec(err.message)?.[1] ?? err.message;
}
