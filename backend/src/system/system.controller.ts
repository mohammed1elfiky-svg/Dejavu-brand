import { BadRequestException, Body, Controller, Get, HttpCode, HttpStatus, Post, StreamableFile, UploadedFile, UseInterceptors } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiConsumes, ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { WipeTestDataDto } from './system.dto';
import { SystemService } from './system.service';
import { BackupService } from './backup.service';

/** Typed in the app before a restore, which replaces all data. */
export const RESTORE_PHRASE = 'استعادة النسخة الاحتياطية';

@ApiTags('Admin · System')
@Controller('admin/system')
export class SystemController {
  constructor(
    private readonly system: SystemService,
    private readonly backups: BackupService,
  ) {}

  /** Deletes all test data (see SystemService.wipeTestData). `dryRun` only counts. */
  @RequirePermissions('settings.manage')
  @Post('wipe-test-data')
  @HttpCode(HttpStatus.OK)
  wipe(@Body() dto: WipeTestDataDto, @CurrentUser() u: AuthPrincipal) {
    return this.system.wipeTestData(u.id, dto.dryRun ?? false, dto.scope);
  }

  /** The whole database as one gzipped JSON file (admins only). */
  @RequirePermissions('system.backup')
  @Get('backup')
  async backup(@CurrentUser() u: AuthPrincipal) {
    const file = await this.backups.export(u.id);
    return new StreamableFile(file.data, { type: 'application/gzip', disposition: `attachment; filename="${file.fileName}"`, length: file.data.length });
  }

  /** Replaces all data with a backup file (multipart `file`, plus `confirm` = the restore phrase). */
  @RequirePermissions('system.backup')
  @Post('restore')
  @HttpCode(HttpStatus.OK)
  @ApiConsumes('multipart/form-data')
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: 300 * 1024 * 1024 } }))
  restore(@UploadedFile() file: { buffer: Buffer } | undefined, @Body('confirm') confirm: string, @CurrentUser() u: AuthPrincipal) {
    if (confirm?.trim() !== RESTORE_PHRASE) throw new BadRequestException(`Type "${RESTORE_PHRASE}" to confirm`);
    if (!file) throw new BadRequestException('Attach the backup file');
    return this.backups.restore(u.id, file.buffer);
  }
}
