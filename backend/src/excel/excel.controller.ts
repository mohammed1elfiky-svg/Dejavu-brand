import { BadRequestException, Body, Controller, ForbiddenException, Get, HttpCode, HttpStatus, Param, Post, Query, StreamableFile, UploadedFile, UseInterceptors } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiConsumes, ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { EXPORT_PERMISSION, ExcelService, ExportKind } from './excel.service';

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const date = (v?: string) => {
  if (!v) return undefined;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? undefined : d;
};

@ApiTags('Admin · Excel')
@StaffOnly()
@Controller('admin')
export class ExcelController {
  constructor(private readonly excel: ExcelService) {}

  /** products | stock | customers | sales | debtors | product-sales as an .xlsx file (from/to for sales). */
  @Get('export/:kind')
  async export(@Param('kind') kind: string, @CurrentUser() u: AuthPrincipal, @Query('from') from?: string, @Query('to') to?: string) {
    const k = kind.replace(/\.xlsx$/, '') as ExportKind;
    const perm = EXPORT_PERMISSION[k];
    if (!perm) throw new BadRequestException('Unknown export');
    if (!u.permissions.includes(perm)) throw new ForbiddenException('Missing permission');
    const file = await this.excel.export(k, date(from), date(to));
    return new StreamableFile(file.data, { type: XLSX, disposition: `attachment; filename="${file.fileName}"`, length: file.data.length });
  }

  @RequirePermissions('products.write')
  @Get('import/products/template')
  async template() {
    const data = await this.excel.template();
    return new StreamableFile(data, { type: XLSX, disposition: 'attachment; filename="dejavu-products-template.xlsx"', length: data.length });
  }

  /** Multipart `file` (.xlsx) and `dryRun` = "true" to only check it. */
  @RequirePermissions('products.write')
  @Post('import/products')
  @HttpCode(HttpStatus.OK)
  @ApiConsumes('multipart/form-data')
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: 10 * 1024 * 1024 } }))
  import(@UploadedFile() file: { buffer: Buffer } | undefined, @Body('dryRun') dryRun: string | undefined, @CurrentUser() u: AuthPrincipal) {
    if (!file) throw new BadRequestException('Attach the Excel file');
    return this.excel.importProducts(file.buffer, u.id, dryRun === 'true' || dryRun === '1');
  }
}
