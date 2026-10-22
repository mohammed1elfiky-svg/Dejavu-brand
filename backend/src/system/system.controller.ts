import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions } from '../common/decorators/auth.decorators';
import { WipeTestDataDto } from './system.dto';
import { SystemService } from './system.service';

@ApiTags('Admin · System')
@Controller('admin/system')
export class SystemController {
  constructor(private readonly system: SystemService) {}

  /** Deletes all test data (see SystemService.wipeTestData). `dryRun` only counts. */
  @RequirePermissions('settings.manage')
  @Post('wipe-test-data')
  @HttpCode(HttpStatus.OK)
  wipe(@Body() dto: WipeTestDataDto, @CurrentUser() u: AuthPrincipal) {
    return this.system.wipeTestData(u.id, dto.dryRun ?? false, dto.scope);
  }
}
