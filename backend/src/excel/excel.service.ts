import { BadRequestException, Injectable } from '@nestjs/common';
import ExcelJS from 'exceljs';
import { ProductStatus } from '../generated/prisma/client';
import { CatalogService } from '../catalog/catalog.service';
import { CreateProductDto } from '../catalog/dto/catalog.dto';
import { dec } from '../common/utils/money';
import { PrismaService } from '../prisma/prisma.service';
import { CustomerAccountsService } from '../treasury/customer-accounts.service';

export type ExportKind = 'products' | 'stock' | 'customers' | 'sales' | 'debtors' | 'product-sales';

/** Which permission each export needs. */
export const EXPORT_PERMISSION: Record<ExportKind, string> = {
  products: 'products.read',
  stock: 'stock.read',
  customers: 'customers.read',
  sales: 'reports.sales',
  debtors: 'customers.read',
  'product-sales': 'reports.sales',
};

const TITLES: Record<ExportKind, string> = {
  products: 'المنتجات',
  stock: 'المخزون',
  customers: 'العملاء',
  sales: 'المبيعات',
  debtors: 'المدينون',
  'product-sales': 'مبيعات وأرباح المنتجات',
};

/** Columns of the product import sheet (Arabic headers, as in the template). */
const IMPORT_COLUMNS = [
  { key: 'name', header: 'اسم المنتج *', width: 30 },
  { key: 'category', header: 'القسم *', width: 18 },
  { key: 'brand', header: 'الماركة', width: 16 },
  { key: 'price', header: 'سعر البيع *', width: 12 },
  { key: 'wholesale', header: 'سعر الجملة', width: 12 },
  { key: 'cost', header: 'سعر التكلفة', width: 12 },
  { key: 'size', header: 'المقاس', width: 10 },
  { key: 'color', header: 'اللون', width: 12 },
  { key: 'barcode', header: 'الباركود', width: 18 },
  { key: 'quantity', header: 'الكمية', width: 10 },
  { key: 'description', header: 'الوصف', width: 40 },
] as const;

type ImportRow = { row: number } & Partial<Record<(typeof IMPORT_COLUMNS)[number]['key'], string>>;

const STATUS_AR: Record<string, string> = {
  PENDING: 'بانتظار التأكيد',
  CONFIRMED: 'مؤكد',
  PROCESSING: 'قيد التجهيز',
  SHIPPED: 'تم الشحن',
  DELIVERED: 'تم التسليم',
  COMPLETED: 'مكتمل',
  CANCELLED: 'ملغي',
  RETURNED: 'لم يستلم',
};

/** Excel (.xlsx) exports of lists and reports, and adding many products from a sheet. */
@Injectable()
export class ExcelService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly catalog: CatalogService,
    private readonly accounts: CustomerAccountsService,
  ) {}

  private book(title: string, columns: { header: string; key: string; width?: number }[], rows: Record<string, unknown>[]) {
    const wb = new ExcelJS.Workbook();
    wb.creator = 'Dejavu';
    const ws = wb.addWorksheet(title, { views: [{ rightToLeft: true, state: 'frozen', ySplit: 1 }] });
    ws.columns = columns.map((c) => ({ header: c.header, key: c.key, width: c.width ?? 16 }));
    ws.getRow(1).font = { bold: true, color: { argb: 'FFFFFFFF' } };
    ws.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFAA771C' } };
    for (const r of rows) ws.addRow(r);
    return wb;
  }

  async export(kind: ExportKind, from?: Date, to?: Date): Promise<{ fileName: string; data: Buffer }> {
    const wb = await this.build(kind, from ?? new Date(Date.now() - 30 * 24 * 3600_000), to ?? new Date(Date.now() + 60_000));
    const data = Buffer.from(await wb.xlsx.writeBuffer());
    return { fileName: `dejavu-${kind}-${new Date().toISOString().slice(0, 10)}.xlsx`, data };
  }

  private async build(kind: ExportKind, from: Date, to: Date) {
    const title = TITLES[kind];
    if (kind === 'products' || kind === 'stock') {
      const variants = await this.prisma.productVariant.findMany({
        orderBy: [{ product: { nameAr: 'asc' } }, { sku: 'asc' }],
        select: {
          sku: true,
          barcode: true,
          size: true,
          color: true,
          price: true,
          avgCost: true,
          isActive: true,
          stockLevels: { select: { onHand: true, reserved: true } },
          product: { select: { nameAr: true, status: true, basePrice: true, wholesalePrice: true, category: { select: { nameAr: true } }, brand: { select: { name: true } } } },
        },
      });
      const rows = variants.map((v) => {
        const onHand = v.stockLevels.reduce((n, s) => n + s.onHand, 0);
        const reserved = v.stockLevels.reduce((n, s) => n + s.reserved, 0);
        const price = Number(v.price ?? v.product.basePrice);
        return {
          name: v.product.nameAr,
          category: v.product.category.nameAr,
          brand: v.product.brand?.name ?? '',
          sku: v.sku,
          barcode: v.barcode ?? '',
          size: v.size ?? '',
          color: v.color ?? '',
          price,
          wholesale: v.product.wholesalePrice ? Number(v.product.wholesalePrice) : '',
          cost: Number(v.avgCost),
          onHand,
          reserved,
          value: Math.round(onHand * Number(v.avgCost) * 100) / 100,
          status: v.product.status === ProductStatus.ACTIVE && v.isActive ? 'منشور' : v.product.status === ProductStatus.DRAFT ? 'مسودة' : 'موقوف',
        };
      });
      const cols =
        kind === 'products'
          ? [
              { header: 'المنتج', key: 'name', width: 30 },
              { header: 'القسم', key: 'category' },
              { header: 'الماركة', key: 'brand' },
              { header: 'الرمز', key: 'sku', width: 12 },
              { header: 'الباركود', key: 'barcode', width: 18 },
              { header: 'المقاس', key: 'size', width: 10 },
              { header: 'اللون', key: 'color', width: 12 },
              { header: 'سعر البيع', key: 'price', width: 12 },
              { header: 'سعر الجملة', key: 'wholesale', width: 12 },
              { header: 'التكلفة', key: 'cost', width: 12 },
              { header: 'الكمية', key: 'onHand', width: 10 },
              { header: 'الحالة', key: 'status', width: 10 },
            ]
          : [
              { header: 'المنتج', key: 'name', width: 30 },
              { header: 'الرمز', key: 'sku', width: 12 },
              { header: 'المقاس', key: 'size', width: 10 },
              { header: 'اللون', key: 'color', width: 12 },
              { header: 'الكمية', key: 'onHand', width: 10 },
              { header: 'محجوز', key: 'reserved', width: 10 },
              { header: 'متوسط التكلفة', key: 'cost', width: 14 },
              { header: 'قيمة المخزون', key: 'value', width: 14 },
            ];
      return this.book(title, cols, rows);
    }
    if (kind === 'customers') {
      const customers = await this.prisma.customer.findMany({
        where: { code: { not: 'CUS-WALKIN' } },
        orderBy: { createdAt: 'desc' },
        select: { id: true, code: true, name: true, phone: true, governorate: true, isMerchant: true, createdAt: true },
      });
      const spent = new Map(
        (
          await this.prisma.order.groupBy({ by: ['customerId'], where: { status: { not: 'CANCELLED' } }, _sum: { grandTotal: true }, _count: { _all: true } })
        ).map((s) => [s.customerId, s]),
      );
      return this.book(
        title,
        [
          { header: 'الرقم', key: 'code', width: 14 },
          { header: 'الاسم', key: 'name', width: 24 },
          { header: 'الجوال', key: 'phone', width: 16 },
          { header: 'المحافظة', key: 'governorate' },
          { header: 'تاجر', key: 'merchant', width: 8 },
          { header: 'عدد الطلبات', key: 'orders', width: 12 },
          { header: 'إجمالي المشتريات (ر.س)', key: 'spent', width: 18 },
          { header: 'تاريخ التسجيل', key: 'joined', width: 14 },
        ],
        customers.map((c) => ({
          code: c.code,
          name: c.name,
          phone: c.phone,
          governorate: c.governorate ?? '',
          merchant: c.isMerchant ? 'نعم' : '',
          orders: spent.get(c.id)?._count._all ?? 0,
          spent: Number(spent.get(c.id)?._sum.grandTotal ?? 0),
          joined: c.createdAt.toISOString().slice(0, 10),
        })),
      );
    }
    if (kind === 'sales') {
      const orders = await this.prisma.order.findMany({
        where: { placedAt: { gte: from, lt: to } },
        orderBy: { placedAt: 'desc' },
        select: {
          number: true,
          placedAt: true,
          channel: true,
          status: true,
          paymentMethod: true,
          currency: true,
          exchangeRate: true,
          grandTotal: true,
          discountTotal: true,
          amountPaid: true,
          customer: { select: { name: true, phone: true } },
          cashier: { select: { name: true } },
          _count: { select: { items: true } },
        },
      });
      return this.book(
        title,
        [
          { header: 'رقم الفاتورة', key: 'number', width: 18 },
          { header: 'التاريخ', key: 'date', width: 18 },
          { header: 'المصدر', key: 'channel', width: 10 },
          { header: 'العميل', key: 'customer', width: 22 },
          { header: 'الجوال', key: 'phone', width: 14 },
          { header: 'الموظف', key: 'cashier', width: 16 },
          { header: 'الحالة', key: 'status', width: 12 },
          { header: 'القطع', key: 'items', width: 8 },
          { header: 'الخصم (ر.س)', key: 'discount', width: 12 },
          { header: 'الإجمالي (ر.س)', key: 'total', width: 14 },
          { header: 'العملة', key: 'currency', width: 8 },
          { header: 'الإجمالي بالعملة', key: 'totalIn', width: 16 },
        ],
        orders.map((o) => ({
          number: o.number,
          date: o.placedAt.toISOString().replace('T', ' ').slice(0, 16),
          channel: o.channel === 'POS' ? 'المحل' : 'التطبيق',
          customer: o.customer.name,
          phone: o.customer.phone,
          cashier: o.cashier?.name ?? '',
          status: STATUS_AR[o.status] ?? o.status,
          items: o._count.items,
          discount: Number(o.discountTotal),
          total: Number(o.grandTotal),
          currency: o.currency,
          totalIn: Math.round(Number(o.grandTotal) * Number(o.exchangeRate) * 100) / 100,
        })),
      );
    }
    if (kind === 'debtors') {
      const { items } = await this.accounts.debtors(0);
      return this.book(
        title,
        [
          { header: 'العميل', key: 'name', width: 24 },
          { header: 'الجوال', key: 'phone', width: 16 },
          { header: 'المتبقي', key: 'balances', width: 40 },
        ],
        items.map((d) => ({ name: d.name, phone: d.phone, balances: d.balances.map((b) => `${b.balance.toFixed()} ${b.currency}`).join(' + ') })),
      );
    }
    // product-sales: units, revenue, cost and profit per product in the period.
    const rows = await this.prisma.$queryRaw<{ name: string; category: string; units: bigint; revenue: string; cost: string }[]>`
      SELECT p.name_ar AS name, c.name_ar AS category, SUM(oi.quantity - oi.returned_qty) AS units,
             SUM(oi.line_total * (oi.quantity - oi.returned_qty) / NULLIF(oi.quantity, 0))::text AS revenue,
             SUM(oi.unit_cost * (oi.quantity - oi.returned_qty))::text AS cost
      FROM order_items oi
      JOIN orders o ON o.id = oi.order_id AND o.status <> 'CANCELLED' AND o.placed_at >= ${from} AND o.placed_at < ${to}
      JOIN product_variants v ON v.id = oi.variant_id
      JOIN products p ON p.id = v.product_id
      JOIN categories c ON c.id = p.category_id
      GROUP BY p.id, p.name_ar, c.name_ar
      ORDER BY SUM(oi.line_total) DESC`;
    return this.book(
      title,
      [
        { header: 'المنتج', key: 'name', width: 30 },
        { header: 'القسم', key: 'category' },
        { header: 'القطع المباعة', key: 'units', width: 12 },
        { header: 'الإيراد (ر.س)', key: 'revenue', width: 14 },
        { header: 'التكلفة (ر.س)', key: 'cost', width: 14 },
        { header: 'الربح (ر.س)', key: 'profit', width: 14 },
      ],
      rows.map((r) => ({
        name: r.name,
        category: r.category,
        units: Number(r.units),
        revenue: Number(r.revenue ?? 0),
        cost: Number(r.cost ?? 0),
        profit: Math.round((Number(r.revenue ?? 0) - Number(r.cost ?? 0)) * 100) / 100,
      })),
    );
  }

  // --------------------------------------------------------------- import ---

  /** An empty sheet with the right columns and one example row. */
  async template() {
    const wb = this.book('المنتجات', [...IMPORT_COLUMNS], [
      { name: 'كعب سهرة لامع', category: 'أحذية', brand: '', price: 120, wholesale: 95, cost: 60, size: '38', color: 'أسود', barcode: '', quantity: 3, description: '' },
      { name: 'كعب سهرة لامع', category: 'أحذية', brand: '', price: 120, wholesale: 95, cost: 60, size: '39', color: 'أسود', barcode: '', quantity: 2, description: '' },
    ]);
    return Buffer.from(await wb.xlsx.writeBuffer());
  }

  /**
   * Adds products from a sheet: rows with the same name become one product with a variant per
   * size / colour. Missing categories and brands are created. A product that already exists
   * (same name) only gets its new sizes / colours. [dryRun] checks the sheet without saving.
   */
  async importProducts(file: Buffer, userId: string, dryRun: boolean) {
    const wb = new ExcelJS.Workbook();
    try {
      await wb.xlsx.load(file as unknown as ArrayBuffer);
    } catch {
      throw new BadRequestException('الملف ليس ملف Excel صالحاً (.xlsx)');
    }
    const ws = wb.worksheets[0];
    if (!ws) throw new BadRequestException('الملف فارغ');
    // Columns by their header text, so the order in the sheet does not matter.
    const header = new Map<string, number>();
    ws.getRow(1).eachCell((cell, col) => header.set(String(cell.value ?? '').replace('*', '').trim(), col));
    const colOf = (h: string) => header.get(h.replace('*', '').trim());
    const text = (v: ExcelJS.CellValue): string => {
      if (v === null || v === undefined) return '';
      if (typeof v === 'object' && 'richText' in v) return v.richText.map((t) => t.text).join('').trim();
      if (typeof v === 'object' && 'result' in v) return String(v.result ?? '').trim();
      if (typeof v === 'object' && 'text' in v) return String(v.text).trim();
      return String(v).trim();
    };
    const rows: ImportRow[] = [];
    ws.eachRow((r, n) => {
      if (n === 1) return;
      const row: ImportRow = { row: n };
      for (const c of IMPORT_COLUMNS) {
        const idx = colOf(c.header);
        if (idx) row[c.key] = text(r.getCell(idx).value);
      }
      if (Object.entries(row).some(([k, v]) => k !== 'row' && v)) rows.push(row);
    });
    if (!rows.length) throw new BadRequestException('لا توجد صفوف في الملف');
    if (rows.length > 3000) throw new BadRequestException('الحد الأقصى 3000 صف في الملف الواحد');

    const errors: { row: number; message: string }[] = [];
    const num = (v: string | undefined) => (v ? Number(v.replace(/,/g, '')) : NaN);
    for (const r of rows) {
      if (!r.name) errors.push({ row: r.row, message: 'اسم المنتج مطلوب' });
      if (!r.category) errors.push({ row: r.row, message: 'القسم مطلوب' });
      if (!(num(r.price) > 0)) errors.push({ row: r.row, message: 'سعر البيع غير صحيح' });
      if (r.cost && !(num(r.cost) >= 0)) errors.push({ row: r.row, message: 'سعر التكلفة غير صحيح' });
      if (r.wholesale && !(num(r.wholesale) > 0)) errors.push({ row: r.row, message: 'سعر الجملة غير صحيح' });
      if (r.quantity && !(Number.isInteger(num(r.quantity)) && num(r.quantity) >= 0)) errors.push({ row: r.row, message: 'الكمية يجب أن تكون رقماً صحيحاً' });
    }
    const groups = new Map<string, ImportRow[]>();
    for (const r of rows) if (r.name) groups.set(r.name, [...(groups.get(r.name) ?? []), r]);
    // Two rows of one product with the same size and colour.
    for (const [name, list] of groups) {
      const seen = new Set<string>();
      for (const r of list) {
        const k = `${r.size ?? ''}|${r.color ?? ''}`;
        if (seen.has(k)) errors.push({ row: r.row, message: `${name}: المقاس واللون مكرران` });
        seen.add(k);
      }
    }
    const barcodes = rows.map((r) => r.barcode).filter((b): b is string => !!b);
    if (barcodes.length) {
      const taken = await this.prisma.productVariant.findMany({ where: { barcode: { in: barcodes } }, select: { barcode: true } });
      for (const t of taken) for (const r of rows.filter((x) => x.barcode === t.barcode)) errors.push({ row: r.row, message: `الباركود ${t.barcode} مستخدم لمنتج آخر` });
    }
    const existing = new Map(
      (await this.prisma.product.findMany({ where: { nameAr: { in: [...groups.keys()] } }, select: { id: true, nameAr: true, variants: { select: { size: true, color: true } } } })).map((p) => [p.nameAr, p]),
    );
    const summary = {
      rows: rows.length,
      newProducts: [...groups.keys()].filter((n) => !existing.has(n)).length,
      existingProducts: [...groups.keys()].filter((n) => existing.has(n)).length,
      errors: errors.sort((a, b) => a.row - b.row),
    };
    if (dryRun || errors.length) return { ...summary, saved: false, created: 0, variantsAdded: 0 };

    const categories = new Map((await this.prisma.category.findMany({ select: { id: true, nameAr: true } })).map((c) => [c.nameAr.trim(), c.id]));
    const brands = new Map((await this.prisma.brand.findMany({ select: { id: true, name: true } })).map((b) => [b.name.trim(), b.id]));
    let created = 0;
    let variantsAdded = 0;
    let n = 0;
    for (const [name, list] of groups) {
      const first = list[0];
      let categoryId = categories.get(first.category!);
      if (!categoryId) {
        const c = await this.catalog.createCategory({ nameAr: first.category!, nameEn: first.category!, slug: `cat-${Date.now().toString(36)}-${n++}` }, userId);
        categoryId = c.id;
        categories.set(first.category!, c.id);
      }
      let brandId: string | undefined;
      if (first.brand) {
        brandId = brands.get(first.brand);
        if (!brandId) {
          const b = await this.catalog.createBrand({ name: first.brand }, userId);
          brandId = b.id;
          brands.set(first.brand, b.id);
        }
      }
      const variant = (r: ImportRow) => ({
        size: r.size || undefined,
        color: r.color || undefined,
        barcode: r.barcode || undefined,
        initialQuantity: r.quantity ? num(r.quantity) : 0,
        unitCost: r.cost ? dec(num(r.cost)).toFixed(2) : undefined,
        price: r.price && num(r.price) !== num(first.price) ? dec(num(r.price)).toFixed(2) : undefined,
      });
      const have = existing.get(name);
      if (have) {
        for (const r of list) {
          if (have.variants.some((v) => (v.size ?? '') === (r.size ?? '') && (v.color ?? '') === (r.color ?? ''))) continue;
          await this.catalog.addVariant(have.id, variant(r), userId);
          variantsAdded++;
        }
        continue;
      }
      const dto: CreateProductDto = {
        categoryId,
        nameAr: name,
        brandId,
        status: ProductStatus.ACTIVE,
        basePrice: dec(num(first.price)).toFixed(2),
        wholesalePrice: first.wholesale ? dec(num(first.wholesale)).toFixed(2) : undefined,
        costPrice: first.cost ? dec(num(first.cost)).toFixed(2) : undefined,
        descriptionAr: first.description || undefined,
        variants: list.map(variant),
      } as CreateProductDto;
      await this.catalog.createProduct(dto, userId);
      created++;
      variantsAdded += list.length;
    }
    return { ...summary, saved: true, created, variantsAdded };
  }
}
