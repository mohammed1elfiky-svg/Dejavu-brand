import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, staffToken } from './helpers';

/** Products with size × color variants and their opening stock, as the admin app creates them. */
describe('Product variants with initial stock (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);
  const sku = (s: string) => `V${run}-${s}`.toUpperCase();

  const onHand = async (variantId: string) =>
    (await prisma.stockLevel.findMany({ where: { variantId } })).reduce((n, l) => n + l.onHand, 0);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    const cat = await http.post('/admin/categories', { nameAr: 'قمصان', nameEn: 'Shirts', slug: `shirts-${run}` }, admin).expect(201);
    categoryId = cat.body.id;
  });
  afterAll(() => app.close());

  let productId: string;

  it('creates a product with its sizes, colors and opening stock in one step', async () => {
    const res = await http
      .post(
        '/admin/products',
        {
          categoryId,
          nameAr: 'قميص كتان',
          nameEn: 'Linen shirt',
          slug: `linen-${run}`,
          basePrice: '120',
          status: 'ACTIVE',
          variants: [
            { sku: sku('M-BLK'), size: 'M', color: 'أسود', colorHex: '#000000', initialQuantity: 5, unitCost: '40' },
            { sku: sku('L-BLK'), size: 'L', color: 'أسود', colorHex: '#000000', initialQuantity: 2, unitCost: '42', price: '130' },
            { sku: sku('M-WHT'), size: 'M', color: 'أبيض', colorHex: '#FFFFFF' },
          ],
        },
        admin,
      )
      .expect(201);
    productId = res.body.id;
    const bySku = Object.fromEntries(res.body.variants.map((v: { sku: string }) => [v.sku, v]));
    expect(bySku[sku('M-BLK')]).toMatchObject({ size: 'M', color: 'أسود', avgCost: '40' });
    expect(bySku[sku('L-BLK')]).toMatchObject({ price: '130', avgCost: '42' });
    expect(await onHand(bySku[sku('M-BLK')].id)).toBe(5);
    expect(await onHand(bySku[sku('L-BLK')].id)).toBe(2);
    expect(await onHand(bySku[sku('M-WHT')].id)).toBe(0);

    // One opening-balance entry worth 5×40 + 2×42 = 284 SAR.
    const entry = await prisma.journalEntry.findFirst({
      where: { sourceType: 'OPENING_BALANCE', description: { contains: 'OPENING' }, createdAt: { gte: new Date(Date.now() - 60_000) } },
      orderBy: { createdAt: 'desc' },
      include: { lines: { include: { account: true } } },
    });
    const inventory = entry!.lines.find((l) => l.account.systemKey === 'INVENTORY')!;
    expect(inventory.debit.toString()).toBe('284');
  });

  it("values initial stock at the product's cost when a variant has none of its own", async () => {
    const res = await http
      .post(
        '/admin/products',
        {
          categoryId,
          nameAr: 'منتج بتكلفة موحدة',
          nameEn: 'Shared cost',
          slug: `shared-cost-${run}`,
          basePrice: '50',
          costPrice: '20',
          variants: [{ size: 'S', initialQuantity: 3 }, { size: 'M' }],
        },
        admin,
      )
      .expect(201);
    // SKUs are numbered automatically: <product number>-<n>.
    const n = res.body.skuNumber as number;
    expect(n).toBeGreaterThanOrEqual(100);
    expect(res.body.variants.map((v: { sku: string }) => v.sku)).toEqual([`${n}-1`, `${n}-2`]);
    expect(res.body.variants[0].avgCost).toBe('20');
  });

  it('adds a new color later with its own stock, and edits a variant', async () => {
    const added = await http
      .post(`/admin/products/${productId}/variants`, { sku: sku('M-RED'), size: 'M', color: 'أحمر', initialQuantity: 3, unitCost: '41' }, admin)
      .expect(201);
    expect(await onHand(added.body.id)).toBe(3);

    const edited = await http.patch(`/admin/variants/${added.body.id}`, { price: '125', lowStockThreshold: 1, isActive: false }, admin).expect(200);
    expect(edited.body).toMatchObject({ price: '125', lowStockThreshold: 1, isActive: false });

    await http.post(`/admin/products/${productId}/variants`, { sku: sku('M-RED'), size: 'M' }, admin).expect(409);
  });

  it('edits the product details', async () => {
    const res = await http
      .patch(`/admin/products/${productId}`, { nameAr: 'قميص كتان فاخر', basePrice: '135', compareAtPrice: '160', descriptionAr: 'كتان طبيعي' }, admin)
      .expect(200);
    expect(res.body).toMatchObject({ nameAr: 'قميص كتان فاخر', basePrice: '135', compareAtPrice: '160', descriptionAr: 'كتان طبيعي' });
  });
});
