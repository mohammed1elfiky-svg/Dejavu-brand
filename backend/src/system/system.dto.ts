import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsEnum, IsIn, IsOptional } from 'class-validator';

/** Typed by the admin to confirm the wipe. */
export const WIPE_PHRASE = 'حذف البيانات التجريبية';

/** ALL: everything incl. customers and staff; OPERATIONS: catalog and transactions only. */
export enum WipeScope {
  ALL = 'ALL',
  OPERATIONS = 'OPERATIONS',
}

export class WipeTestDataDto {
  @ApiProperty({ example: WIPE_PHRASE, description: 'Must be exactly this phrase' })
  @IsIn([WIPE_PHRASE], { message: `Type "${WIPE_PHRASE}" to confirm` })
  confirm: string;

  @ApiPropertyOptional({ description: 'Only count what would be deleted; nothing changes' })
  @IsOptional()
  @IsBoolean()
  dryRun?: boolean;

  @ApiPropertyOptional({ enum: WipeScope, default: WipeScope.ALL, description: 'OPERATIONS keeps customers, staff, suppliers and their drawers' })
  @IsOptional()
  @IsEnum(WipeScope)
  scope?: WipeScope;
}
