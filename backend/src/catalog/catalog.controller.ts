import { Body, Controller, Delete, Get, Headers, HttpCode, HttpStatus, Param, ParseUUIDPipe, Patch, Post, Put, Query } from '@nestjs/common';
import { ApiTags } from '@nestjs/swagger';
import { AuthPrincipal, CurrentUser, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { CatalogService } from './catalog.service';
import {
  AdminProductsQueryDto,
  CreateBrandDto,
  UpdateBrandDto,
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
  @Get('brands')
  listBrands() {
    return this.catalog.listBrands();
  }

  @RequirePermissions('categories.write')
  @Post('brands')
  createBrand(@Body() dto: CreateBrandDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.createBrand(dto, u.id);
  }

  @RequirePermissions('categories.write')
  @Patch('brands/:id')
  updateBrand(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateBrandDto, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.updateBrand(id, dto, u.id);
  }

  @RequirePermissions('categories.write')
  @Delete('brands/:id')
  @HttpCode(HttpStatus.NO_CONTENT)
  deleteBrand(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.catalog.deleteBrand(id, u.id);
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

const customerOf = (u?: AuthPrincipal) => (u?.type === 'customer' ? u.id : undefined);

@ApiTags('Store · Catalog')
@Public()
@Controller('store')
export class StoreCatalogController {
  constructor(private readonly catalog: CatalogService) {}

  @Get('categories')
  async categories(@CurrentUser() u?: AuthPrincipal) {
    await this.catalog.storeGate(customerOf(u));
    return this.catalog.categoryTree();
  }

  @Get('brands')
  async brands(@CurrentUser() u?: AuthPrincipal) {
    await this.catalog.storeGate(customerOf(u));
    return this.catalog.storeBrands();
  }

  @Get('products')
  async products(@Query() q: StoreProductsQueryDto, @CurrentUser() u?: AuthPrincipal, @Headers('x-device-id') deviceId?: string) {
    await this.catalog.storeGate(customerOf(u));
    return this.catalog.storeProducts(q, customerOf(u), deviceId);
  }

  @Get('products/:slug')
  async product(@Param('slug') slug: string, @CurrentUser() u?: AuthPrincipal) {
    await this.catalog.storeGate(customerOf(u));
    return this.catalog.storeProduct(slug, customerOf(u));
  }

  /** "يناسبه أيضاً" and "اشترت العميلات أيضاً" for a product page. */
  @Get('products/:slug/related')
  async related(@Param('slug') slug: string, @CurrentUser() u?: AuthPrincipal) {
    await this.catalog.storeGate(customerOf(u));
    return this.catalog.relatedProducts(slug, customerOf(u));
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
