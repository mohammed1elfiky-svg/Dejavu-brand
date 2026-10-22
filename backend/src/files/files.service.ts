import { BadRequestException, Injectable, NotFoundException, PayloadTooLargeException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

export const MAX_FILE_BYTES = 5 * 1024 * 1024;
export const MAX_VIDEO_BYTES = 30 * 1024 * 1024;
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const VIDEO_TYPES = ['video/mp4', 'video/webm', 'video/3gpp', 'video/quicktime'];

/** What multer hands over for one uploaded part. */
export interface UploadedPart {
  buffer: Buffer;
  mimetype: string;
  size: number;
}

/**
 * Images stored in PostgreSQL. Free hosts wipe their disk on every deploy, and the
 * database is backed up anyway, so product photos and deposit receipts live there.
 */
@Injectable()
export class FilesService {
  constructor(private readonly prisma: PrismaService) {}

  async saveImage(file: UploadedPart | undefined, owner: { userId?: string; customerId?: string; isPublic: boolean }) {
    if (!file) throw new BadRequestException('Attach the image as the "file" field');
    if (!IMAGE_TYPES.includes(file.mimetype)) throw new BadRequestException('Only JPEG, PNG or WebP images are accepted');
    if (file.size > MAX_FILE_BYTES) throw new PayloadTooLargeException('Images must be 5 MB or smaller');
    const row = await this.prisma.storedFile.create({
      data: { mimeType: file.mimetype, size: file.size, data: new Uint8Array(file.buffer), ...owner },
      select: { id: true, mimeType: true, size: true, createdAt: true },
    });
    return row;
  }

  /** A short product video (public), kept small enough for the database. */
  async saveVideo(file: UploadedPart | undefined, userId: string) {
    if (!file) throw new BadRequestException('Attach the video as the "file" field');
    if (!VIDEO_TYPES.includes(file.mimetype)) throw new BadRequestException('Only MP4, WebM, 3GP or MOV videos are accepted');
    if (file.size > MAX_VIDEO_BYTES) throw new PayloadTooLargeException('Videos must be 30 MB or smaller');
    return this.prisma.storedFile.create({
      data: { mimeType: file.mimetype, size: file.size, data: new Uint8Array(file.buffer), isPublic: true, userId },
      select: { id: true, mimeType: true, size: true, createdAt: true },
    });
  }

  /** The file if [canSee] allows it; private files are invisible (404) otherwise. */
  async load(id: string, canSee: (f: { isPublic: boolean; customerId: string | null }) => boolean) {
    const file = await this.prisma.storedFile.findUnique({ where: { id } });
    if (!file || !canSee(file)) throw new NotFoundException('File not found');
    return file;
  }
}

export const fileUrl = (base: string | undefined, id: string) => `${base ?? ''}/api/v1/files/${id}`;
