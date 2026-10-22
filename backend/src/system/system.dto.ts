import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsBoolean, IsIn, IsOptional } from 'class-validator';

/** Typed by the admin to confirm the wipe. */
export const WIPE_PHRASE = 'حذف البيانات التجريبية';

export class WipeTestDataDto {
  @ApiProperty({ example: WIPE_PHRASE, description: 'Must be exactly this phrase' })
  @IsIn([WIPE_PHRASE], { message: `Type "${WIPE_PHRASE}" to confirm` })
  confirm: string;

  @ApiPropertyOptional({ description: 'Only count what would be deleted; nothing changes' })
  @IsOptional()
  @IsBoolean()
  dryRun?: boolean;
}
