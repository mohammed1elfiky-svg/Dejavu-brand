import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { CatalogService } from './catalog.service';
import {
  AdminProductsQueryDto,
  CreateCategoryDto,
  CreateProductDto,
  ReplaceImagesDto,
  StoreProductsQueryDto,
  UpdateCategoryDto,
  UpdateProductDto,
  NewVariantDto,
  UpdateVariantDto,
  PromotionDto,
  UpdatePromotionDto,
} from './dto/catalog.dto';
import { PromotionsService } from './promotions.service';

@ApiTags('Admin · Catalog')
@Controller('admin')
export class AdminCatalogController {
  constructor(private readonly catalog: CatalogService) {}

  @RequirePermissions('products.read')
  @Get('categories')
  listCategories() {
    return this.catalog.listCategories();
  }

  @RequirePermissions('categories.write')
  @Post('categories')
  createCategory(@Body() dto: CreateCategoryDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.createCategory(dto, u.id);
  }

  @RequirePermissions('categories.write')
  @Patch('categories/:id')
  updateCategory(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateCategoryDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.updateCategory(id, dto, u.id);
  }

  @RequirePermissions('categories.write')
  @Delete('categories/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteCategory(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.deleteCategory(id, u.id);
  }

  @RequirePermissions('products.read')
  @Get('products')
  listProducts(@Query() q: AdminProductsQueryDto) {
    return this.catalog.listProducts(q);
  }

  @RequirePermissions('products.read')
  @Get('products/:id')
  getProduct(@Param('id', ParseUUIDPipe) id: string) {
    return this.catalog.getProduct(id);
  }

  @RequirePermissions('products.write')
  @Post('products')
  createProduct(@Body() dto: CreateProductDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.createProduct(dto, u.id);
  }

  @RequirePermissions('products.write')
  @Patch('products/:id')
  updateProduct(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateProductDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.updateProduct(id, dto, u.id);
  }

  @RequirePermissions('products.write')
  @Put('products/:id/images')
  replaceImages(@Param('id', ParseUUIDPipe) id: string, @Body() dto: ReplaceImagesDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.replaceImages(id, dto.images, u.id);
  }

  @RequirePermissions('products.write')
  @Post('products/:id/variants')
  addVariant(@Param('id', ParseUUIDPipe) id: string, @Body() dto: NewVariantDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.addVariant(id, dto, u.id);
  }

  @RequirePermissions('products.write')
  @Patch('variants/:id')
  updateVariant(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateVariantDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.updateVariant(id, dto, u.id);
  }
}

@ApiTags('Store · Catalog')
@Public()
@Controller('store')
export class StoreCatalogController {
  constructor(private readonly catalog: CatalogService) {}

  @Get('categories')
  categories() {
    return this.catalog.categoryTree();
  }

  @Get('products')
  products(@Query() q: StoreProductsQueryDto, @CurrentUser() u?: AuthPrincipal) {
    return this.catalog.storeProducts(q, u?.type === 'customer' ? u.id : undefined);
  }

  @Get('products/:slug')
  product(@Param('slug') slug: string, @CurrentUser() u?: AuthPrincipal) {
    return this.catalog.storeProduct(slug, u?.type === 'customer' ? u.id : undefined);
  }
}

@ApiTags('Admin · Promotions')
@Controller('admin/promotions')
export class AdminPromotionsController {
  constructor(private readonly promotions: PromotionsService) {}

  @RequirePermissions('products.read')
  @Get()
  list() {
    return this.promotions.list();
  }

  @RequirePermissions('products.read')
  @Get(':id')
  get(@Param('id', ParseUUIDPipe) id: string) {
    return this.promotions.get(id);
  }

  @RequirePermissions('promotions.manage')
  @Post()
  create(@Body() dto: PromotionDto, @CurrentUser() u: AuthPrincipal) {
    return this.promotions.create(dto, u.id);
  }

  @RequirePermissions('promotions.manage')
  @Patch(':id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdatePromotionDto, @CurrentUser() u: AuthPrincipal) {
    return this.promotions.update(id, dto, u.id);
  }

  @RequirePermissions('promotions.manage')
  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.promotions.remove(id, u.id);
  }
}
