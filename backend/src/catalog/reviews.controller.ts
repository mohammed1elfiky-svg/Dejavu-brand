import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Post, Put, Query, Req, UploadedFile, UseInterceptors } from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiConsumes, ApiProperty, ApiPropertyOptional, ApiTags } from '@nestjs/swagger';
import { ArrayMaxSize, IsBoolean, IsIn, IsInt, IsOptional, IsString, Max, MaxLength, Min } from 'class-validator';
import type { Request } from 'express';
import { AuthPrincipal, CurrentUser, CustomerOnly, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { PaginationQueryDto } from '../common/dto/pagination.dto';
import { requestBaseUrl } from '../common/utils/request-url';
import { fileUrl, FilesService, MAX_FILE_BYTES, UploadedPart } from '../files/files.service';
import { ReviewsService } from './reviews.service';

class WriteReviewDto {
  @ApiProperty({ minimum: 1, maximum: 5 })
  @IsInt()
  @Min(1)
  @Max(5)
  rating: number;

  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  @MaxLength(1000)
  comment?: string;

  @ApiPropertyOptional({ type: [String], description: 'Photo URLs from POST store/me/review-photos' })
  @IsOptional()
  @IsString({ each: true })
  @MaxLength(500, { each: true })
  @ArrayMaxSize(5)
  images?: string[];
}

class ReviewsQueryDto extends PaginationQueryDto {
  @ApiPropertyOptional({ enum: ['pending', 'approved'] })
  @IsOptional()
  @IsIn(['pending', 'approved'])
  status?: 'pending' | 'approved';
}

class ApproveReviewDto {
  @ApiProperty()
  @IsBoolean()
  approved: boolean;
}

@ApiTags('Store · Reviews')
@Controller('store')
export class StoreReviewsController {
  constructor(
    private readonly reviews: ReviewsService,
    private readonly files: FilesService,
  ) {}

  @Public()
  @Get('products/:slug/reviews')
  list(@Param('slug') slug: string, @Query() q: PaginationQueryDto) {
    return this.reviews.forProduct(slug, q);
  }

  /** The customer's own review of the product and whether they may write one. */
  @CustomerOnly()
  @Get('products/:slug/my-review')
  mine(@Param('slug') slug: string, @CurrentUser() u: AuthPrincipal) {
    return this.reviews.mine(u.id, slug);
  }

  @CustomerOnly()
  @Put('products/:slug/my-review')
  write(@Param('slug') slug: string, @Body() dto: WriteReviewDto, @CurrentUser() u: AuthPrincipal) {
    return this.reviews.write(u.id, slug, dto);
  }

  /** A photo for a review (public once the review is shown). */
  @CustomerOnly()
  @Post('me/review-photos')
  @UseInterceptors(FileInterceptor('file', { limits: { fileSize: MAX_FILE_BYTES + 1 } }))
  @ApiConsumes('multipart/form-data')
  async photo(@UploadedFile() file: UploadedPart | undefined, @CurrentUser() u: AuthPrincipal, @Req() req: Request) {
    const saved = await this.files.saveImage(file, { customerId: u.id, isPublic: true });
    return { ...saved, url: fileUrl(requestBaseUrl(req), saved.id) };
  }
}

@ApiTags('Admin · Reviews')
@Controller('admin/reviews')
export class AdminReviewsController {
  constructor(private readonly reviews: ReviewsService) {}

  @RequirePermissions('products.read')
  @Get()
  list(@Query() q: ReviewsQueryDto) {
    return this.reviews.list(q);
  }

  /** Shows (approved) or hides a review in the store. */
  @RequirePermissions('products.write')
  @Post(':id/approve')
  @HttpCode(HttpStatus.OK)
  approve(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ApproveReviewDto, @CurrentUser() u: AuthPrincipal) {
    return this.reviews.approve(id, dto.approved, u.id);
  }

  @RequirePermissions('products.write')
  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.reviews.remove(id, u.id);
  }
}
