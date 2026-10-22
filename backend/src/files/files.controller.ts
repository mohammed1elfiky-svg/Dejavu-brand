import { Controller, Get, Param, ParseUUIDPipe, Post, Req, Res, UploadedFile, UseInterceptors } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiBody, ApiConsumes, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public, RequirePermissions, StaffOnly } from '../common/decorators/auth.decorators';
import { requestBaseUrl } from '../common/utils/request-url';
import { fileUrl, FilesService, MAX_FILE_BYTES, MAX_VIDEO_BYTES, UploadedPart } from './files.service';

const upload = FileInterceptor('file', { limits: { fileSize: MAX_FILE_BYTES + 1 } });
const uploadVideo = FileInterceptor('file', { limits: { fileSize: MAX_VIDEO_BYTES + 1 } });
const uploadDoc = { schema: { type: 'object', properties: { file: { type: 'string', format: 'binary' } } } };

/** Sends a stored file, honouring a byte Range so video players can seek. */
function send(res: Response, file: { mimeType: string; data: Uint8Array }, cache: string, range?: string) {
  const buf = Buffer.from(file.data);
  res.setHeader('Content-Type', file.mimeType);
  res.setHeader('Cache-Control', cache);
  res.setHeader('Accept-Ranges', 'bytes');
  const m = range ? /^bytes=(\d*)-(\d*)$/.exec(range) : null;
  if (m && (m[1] || m[2])) {
    const start = m[1] ? Number(m[1]) : Math.max(0, buf.length - Number(m[2]));
    const end = m[1] && m[2] ? Math.min(Number(m[2]), buf.length - 1) : buf.length - 1;
    if (start >= buf.length || start > end) {
      res.status(416).setHeader('Content-Range', `bytes */${buf.length}`);
      res.end();
      return;
    }
    res.status(206).setHeader('Content-Range', `bytes ${start}-${end}/${buf.length}`);
    res.end(buf.subarray(start, end + 1));
    return;
  }
  res.end(buf);
}

@ApiTags('Files')
@Controller()
export class FilesController {
  constructor(private readonly files: FilesService) {}

  /** Product photo upload (public once uploaded). Send multipart/form-data with `file`. */
  @RequirePermissions('products.write')
  @Post('admin/files')
  @UseInterceptors(upload)
  @ApiConsumes('multipart/form-data')
  @ApiBody(uploadDoc)
  async staffUpload(@UploadedFile() file: UploadedPart, @CurrentUser() u: AuthPrincipal, @Req() req: Request) {
    const saved = await this.files.saveImage(file, { userId: u.id, isPublic: true });
    return { ...saved, url: fileUrl(requestBaseUrl(req), saved.id) };
  }

  /** Product video upload (public), up to 30 MB. */
  @RequirePermissions('products.write')
  @Post('admin/files/video')
  @UseInterceptors(uploadVideo)
  @ApiConsumes('multipart/form-data')
  @ApiBody(uploadDoc)
  async videoUpload(@UploadedFile() file: UploadedPart, @CurrentUser() u: AuthPrincipal, @Req() req: Request) {
    const saved = await this.files.saveVideo(file, u.id);
    return { ...saved, url: fileUrl(requestBaseUrl(req), saved.id) };
  }

  /** Deposit receipt photo from the customer app (private to the customer and staff). */
  @CustomerOnly()
  @Post('store/me/files')
  @UseInterceptors(upload)
  @ApiConsumes('multipart/form-data')
  @ApiBody(uploadDoc)
  async customerUpload(@UploadedFile() file: UploadedPart, @CurrentUser() u: AuthPrincipal, @Req() req: Request) {
    const saved = await this.files.saveImage(file, { customerId: u.id, isPublic: false });
    return { ...saved, url: fileUrl(requestBaseUrl(req), saved.id) };
  }

  @Public()
  @Get('files/:id')
  async publicFile(@Param('id', ParseUUIDPipe) id: string, @Req() req: Request, @Res() res: Response) {
    send(res, await this.files.load(id, (f) => f.isPublic), 'public, max-age=31536000, immutable', req.headers.range);
  }

  @StaffOnly()
  @Get('admin/files/:id')
  async staffFile(@Param('id', ParseUUIDPipe) id: string, @Res() res: Response) {
    send(res, await this.files.load(id, () => true), 'private, max-age=3600');
  }

  @CustomerOnly()
  @Get('store/me/files/:id')
  async customerFile(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal, @Res() res: Response) {
    send(res, await this.files.load(id, (f) => f.isPublic || f.customerId === u.id), 'private, max-age=3600');
  }
}
