import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Injectable, NotFoundException, Param, ParseUUIDPipe, Patch, Post } from '@nestjs/common';
import { ApiProperty, ApiPropertyOptional, ApiTags, PartialType } from '@nestjs/swagger';
import { ArrayMaxSize, IsArray, IsBoolean, IsInt, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';
import { AuditService } from '../common/audit.service';
import { AuthPrincipal, CurrentUser, Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { PrismaService } from '../prisma/prisma.service';

export class CarrierDto {
  @ApiProperty({ example: 'الجوال القدسي' }) @IsString() @MinLength(2) @MaxLength(80) name: string;
  @ApiPropertyOptional({ type: [String], example: ['777000111'] }) @IsOptional() @IsArray() @ArrayMaxSize(10) @IsString({ each: true }) @MaxLength(30, { each: true }) phones?: string[];
  @ApiPropertyOptional() @IsOptional() @IsString() @MaxLength(500) notes?: string;
  @ApiPropertyOptional() @IsOptional() @IsBoolean() isActive?: boolean;
  @ApiPropertyOptional() @IsOptional() @IsInt() sortOrder?: number;
}
export class UpdateCarrierDto extends PartialType(CarrierDto) {}

export class CarrierOfficeDto {
  @ApiProperty({ example: 'فرع شارع جمال' }) @IsString() @MinLength(2) @MaxLength(80) name: string;
  @ApiProperty({ example: 'تعز' }) @IsString() @MinLength(2) @MaxLength(40) governorate: string;
  @ApiPropertyOptional({ example: 'تعز' }) @IsOptional() @IsString() @MaxLength(60) city?: string;
  @ApiProperty({ example: 'شارع جمال جوار البنك' }) @IsString() @MinLength(3) @MaxLength(200) address: string;
  @ApiPropertyOptional({ type: [String] }) @IsOptional() @IsArray() @ArrayMaxSize(10) @IsString({ each: true }) @MaxLength(30, { each: true }) phones?: string[];
  @ApiPropertyOptional() @IsOptional() @IsBoolean() isActive?: boolean;
}
export class UpdateCarrierOfficeDto extends PartialType(CarrierOfficeDto) {}

const phonesOf = (list?: string[]) => list?.map((p) => p.trim()).filter(Boolean);

/** Local shipping companies (الجوال القدسي…) and their offices, for deliveries to an office. */
@Injectable()
export class CarriersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly audit: AuditService,
  ) {}

  list(activeOnly: boolean) {
    return this.prisma.carrier.findMany({
      where: activeOnly ? { isActive: true } : {},
      orderBy: [{ sortOrder: 'asc' }, { name: 'asc' }],
      include: { offices: { where: activeOnly ? { isActive: true } : {}, orderBy: [{ governorate: 'asc' }, { name: 'asc' }] } },
    });
  }

  async create(dto: CarrierDto, userId: string) {
    const row = await this.prisma.carrier.create({ data: { ...dto, name: dto.name.trim(), phones: phonesOf(dto.phones) ?? [] } });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'Carrier', entityId: row.id, after: row });
    return this.get(row.id);
  }

  async update(id: string, dto: UpdateCarrierDto, userId: string) {
    await this.get(id);
    await this.prisma.carrier.update({ where: { id }, data: { ...dto, name: dto.name?.trim(), phones: phonesOf(dto.phones) } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'Carrier', entityId: id, after: dto });
    return this.get(id);
  }

  /** A carrier already used by orders is hidden instead of deleted. */
  async remove(id: string, userId: string) {
    await this.get(id);
    const used = await this.prisma.order.count({ where: { carrierId: id } });
    if (used) await this.prisma.carrier.update({ where: { id }, data: { isActive: false } });
    else await this.prisma.carrier.delete({ where: { id } });
    await this.audit.log({ userId, action: used ? 'UPDATE' : 'DELETE', entityType: 'Carrier', entityId: id, after: { hidden: !!used } });
    return { deleted: !used, hidden: !!used };
  }

  async addOffice(carrierId: string, dto: CarrierOfficeDto, userId: string) {
    await this.get(carrierId);
    const row = await this.prisma.carrierOffice.create({ data: { ...dto, carrierId, phones: phonesOf(dto.phones) ?? [] } });
    await this.audit.log({ userId, action: 'CREATE', entityType: 'CarrierOffice', entityId: row.id, after: row });
    return this.get(carrierId);
  }

  async updateOffice(id: string, dto: UpdateCarrierOfficeDto, userId: string) {
    const office = await this.prisma.carrierOffice.findUnique({ where: { id } });
    if (!office) throw new NotFoundException('Office not found');
    await this.prisma.carrierOffice.update({ where: { id }, data: { ...dto, phones: phonesOf(dto.phones) } });
    await this.audit.log({ userId, action: 'UPDATE', entityType: 'CarrierOffice', entityId: id, after: dto });
    return this.get(office.carrierId);
  }

  async removeOffice(id: string, userId: string) {
    const office = await this.prisma.carrierOffice.findUnique({ where: { id } });
    if (!office) throw new NotFoundException('Office not found');
    const used = await this.prisma.order.count({ where: { carrierOfficeId: id } });
    if (used) await this.prisma.carrierOffice.update({ where: { id }, data: { isActive: false } });
    else await this.prisma.carrierOffice.delete({ where: { id } });
    await this.audit.log({ userId, action: used ? 'UPDATE' : 'DELETE', entityType: 'CarrierOffice', entityId: id, after: { hidden: !!used } });
    return this.get(office.carrierId);
  }

  async get(id: string) {
    const row = await this.prisma.carrier.findUnique({ where: { id }, include: { offices: { orderBy: [{ governorate: 'asc' }, { name: 'asc' }] } } });
    if (!row) throw new NotFoundException('Shipping company not found');
    return row;
  }
}

@ApiTags('Admin · Shipping companies')
@Controller('admin/carriers')
export class AdminCarriersController {
  constructor(private readonly carriers: CarriersService) {}

  @RequirePermissions('orders.read')
  @Get()
  list() {
    return this.carriers.list(false);
  }

  @RequirePermissions('settings.manage')
  @Post()
  create(@Body() dto: CarrierDto, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.create(dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Patch(':id')
  update(@Param('id', ParseUUIDPipe) id: string, @Body() dto: UpdateCarrierDto, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.update(id, dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Delete(':id')
  @HttpCode(HttpStatus.OK)
  remove(@Param('id', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.remove(id, u.id);
  }

  @RequirePermissions('settings.manage')
  @Post(':id/offices')
  addOffice(@Param('id', ParseUUIDPipe) id: string, @Body() dto: CarrierOfficeDto, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.addOffice(id, dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Patch('offices/:officeId')
  updateOffice(@Param('officeId', ParseUUIDPipe) id: string, @Body() dto: UpdateCarrierOfficeDto, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.updateOffice(id, dto, u.id);
  }

  @RequirePermissions('settings.manage')
  @Delete('offices/:officeId')
  @HttpCode(HttpStatus.OK)
  removeOffice(@Param('officeId', ParseUUIDPipe) id: string, @CurrentUser() u: AuthPrincipal) {
    return this.carriers.removeOffice(id, u.id);
  }
}

@ApiTags('Store · Shipping companies')
@Public()
@Controller('store/carriers')
export class StoreCarriersController {
  constructor(private readonly carriers: CarriersService) {}

  /** Shipping companies with their offices, for "deliver to an office" at checkout. */
  @Get()
  list() {
    return this.carriers.list(true);
  }
}
