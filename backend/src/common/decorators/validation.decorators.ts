import { applyDecorators } from '@nestjs/common';
import { Transform } from 'class-transformer';
import { Matches } from 'class-validator';

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/**
 * Non-negative money amount with at most 2 decimals. Accepts a JSON number or
 * numeric string and normalises it to a string so it never passes through
 * floating-point arithmetic.
 */
export const IsAmount = () =>
  applyDecorators(
    Transform(({ value }) => (typeof value === 'number' ? String(value) : value)),
    Matches(AMOUNT, { message: '$property must be a non-negative amount with up to 2 decimals' }),
  );

export const SLUG_REGEX = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
