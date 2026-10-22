import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, ledgerBalances, staffToken } from './helpers';

/** Import orders from a foreign supplier: pending goods, landed cost with CBM, receipt & matching. */
describe('Import procurement (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let categoryId: string;
  const run = Date.now().toString(36);

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    categoryId = (await http.post('/admin/categories', { nameAr: `استيراد ${run}`, nameEn: `Import ${run}`, slug: `import-${run}` }, admin).expect(201)).body.id;
  });
  afterAll(() => app.close());

  it('holds ordered goods with the supplier and books the landed cost on arrival', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مورد صيني ${run}`, commissionPercent: '5' }, admin).expect(201)).body;
    expect(supplier.commissionPercent).toBe('5');

    const existing = (
      await http
        .post('/admin/products', { categoryId, nameAr: `قميص قديم ${run}`, status: 'ACTIVE', basePrice: '90', costPrice: '20', variants: [{ size: 'M', initialQuantity: 3 }] }, admin)
        .expect(201)
    ).body;
    const oldVariant = existing.variants[0].id;

    const po = (
      await http
        .post(
          '/admin/purchase-orders/import',
          {
            supplierId: supplier.id,
            foreignPerUsd: '7.2',
            sarPerUsd: '3.75',
            domesticShipping: '360',
            items: [{ variantId: oldVariant, quantity: 100, unitPriceForeign: '10' }],
            newProducts: [
              { categoryId, nameAr: `فستان جديد ${run}`, basePrice: '250', unitPriceForeign: '20', imageUrl: 'https://cdn.example.com/d.jpg', variants: [{ size: 'M', quantity: 30 }, { size: 'L', quantity: 20 }] },
            ],
          },
          admin,
        )
        .expect(201)
    ).body;
    expect(po).toMatchObject({ kind: 'IMPORT', status: 'ORDERED', foreignCurrency: 'CNY', commissionPercent: '5' });
    expect(po.items).toHaveLength(3);
    const newItems = po.items.filter((i: { createdProduct: boolean }) => i.createdProduct);
    const newProductId = newItems[0].variant.product.id;
    expect(newItems[0].variant.product.images[0].url).toBe('https://cdn.example.com/d.jpg');

    // New items are drafts: not on sale anywhere, no stock yet.
    const draft = (await http.get(`/admin/products/${newProductId}`, admin).expect(200)).body;
    expect(draft.status).toBe('DRAFT');
    await http.get(`/admin/pos/lookup?code=${draft.skuNumber}`, admin).expect(404);

    const pending = (await http.get(`/admin/suppliers/${supplier.id}/pending-items`, admin).expect(200)).body;
    expect(pending.totalPieces).toBe(150);
    await http.post(`/admin/purchase-orders/${po.id}/receive`, { items: [{ itemId: po.items[0].id, quantity: 1 }] }, admin).expect(400);

    type Item = { id: string; variantId: string; variant: { size: string } };
    const oldItem = po.items.find((i: Item) => i.variantId === oldVariant);
    const mItem = newItems.find((i: Item) => i.variant.size === 'M');
    const lItem = newItems.find((i: Item) => i.variant.size === 'L');
    const counted = {
      items: [
        { itemId: oldItem.id, quantity: 100, cbm: '0.5' },
        { itemId: mItem.id, quantity: 30 },
        { itemId: lItem.id, quantity: 18 }, // two short
      ],
      totalCbm: '2',
      cbmRateUsd: '200',
    };
    const preview = (await http.post(`/admin/purchase-orders/${po.id}/import-costs`, counted, admin).expect(200)).body;
    expect(preview.lines.find((l: { itemId: string }) => l.itemId === oldItem.id).freight).toBe('375');
    await http.post(`/admin/purchase-orders/${po.id}/import-costs`, { ...counted, items: [{ itemId: mItem.id, quantity: 31 }] }, admin).expect(422);

    const received = (await http.post(`/admin/purchase-orders/${po.id}/import-receive`, counted, admin).expect(200)).body;
    expect(received.status).toBe('RECEIVED');
    expect(received.freightCost).toBe(preview.totals.freight);
    const line = (id: string) => received.items.find((i: { id: string }) => i.id === id);
    expect(line(lItem.id).receivedQty).toBe(18);
    expect(line(oldItem.id).landedUnitCost).toBe(preview.lines.find((l: { itemId: string }) => l.itemId === oldItem.id).unitCost);

    // On sale now, with stock and the landed cost.
    const live = (await http.get(`/admin/products/${newProductId}`, admin).expect(200)).body;
    expect(live.status).toBe('ACTIVE');
    const onHand = (v: { stockLevels: { onHand: number }[] }) => v.stockLevels.reduce((n, s) => n + s.onHand, 0);
    expect(live.variants.map(onHand).sort()).toEqual([18, 30]);
    expect(Number(live.costPrice)).toBeGreaterThan(0);
    await http.get(`/admin/pos/lookup?code=${live.skuNumber}`, admin).expect(200);
    const oldNow = (await http.get(`/admin/products/${existing.id}`, admin).expect(200)).body;
    expect(onHand(oldNow.variants[0])).toBe(103);

    // Inventory = what the supplier is owed + freight & customs owed to the forwarder.
    const ledger = await ledgerBalances(prisma, [po.id]);
    expect(ledger.INVENTORY).toBe(Number(preview.totals.total));
    expect(ledger.ACCRUED_EXPENSES).toBe(-Number(preview.totals.freight));
    expect(ledger.AP).toBe(-Number(received.grandTotal));

    expect((await http.get(`/admin/suppliers/${supplier.id}/pending-items`, admin).expect(200)).body.totalPieces).toBe(0);
    await http.post(`/admin/purchase-orders/${po.id}/import-receive`, counted, admin).expect(422);
  });

  it('buys products that are not in the app yet: numbered now, on sale once received', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `مورد محلي ${run}` }, admin).expect(201)).body;
    const newProduct = { categoryId, nameAr: `بلوزة جديدة ${run}`, basePrice: '120', unitCost: '50', variants: [{ size: 'S', quantity: 4, barcode: `69${run}` }, { size: 'M', quantity: 6 }] };
    const later = (await http.post('/admin/purchase-orders', { supplierId: supplier.id, items: [], newProducts: [newProduct] }, admin).expect(201)).body;
    const item = later.items[0];
    expect(item.createdProduct).toBe(true);
    const draft = (await http.get(`/admin/products/${item.variant.product.id}`, admin).expect(200)).body;
    expect(draft.status).toBe('DRAFT');
    expect(draft.skuNumber).toBeGreaterThanOrEqual(100);
    expect(draft.variants.map((v: { sku: string }) => v.sku).sort()).toEqual([`${draft.skuNumber}-1`, `${draft.skuNumber}-2`]);
    expect(draft.variants.find((v: { size: string }) => v.size === 'S').barcode).toBe(`69${run}`);
    await http.post(`/admin/purchase-orders/${later.id}/place`, {}, admin).expect(200);
    await http.post(`/admin/purchase-orders/${later.id}/receive`, { items: later.items.map((i: { id: string; quantity: number }) => ({ itemId: i.id, quantity: i.quantity })) }, admin).expect(200);
    expect((await http.get(`/admin/products/${draft.id}`, admin).expect(200)).body.status).toBe('ACTIVE');

    // The till-style purchase receives right away.
    const quick = (await http.post('/admin/purchase-orders/quick', { supplierId: supplier.id, items: [], newProducts: [{ ...newProduct, nameAr: `قميص فوري ${run}`, variants: [{ quantity: 3 }] }] }, admin).expect(201)).body;
    const p = (await http.get(`/admin/products/${quick.items[0].variant.product.id}`, admin).expect(200)).body;
    expect(p.status).toBe('ACTIVE');
    expect(p.variants[0].stockLevels[0].onHand).toBe(3);
    await http.post('/admin/purchase-orders', { supplierId: supplier.id, items: [] }, admin).expect(400);
  });

  it('takes the commission from the supplier and the USD rate from the settings', async () => {
    const supplier = (await http.post('/admin/suppliers', { name: `وكيل ${run}`, commissionPercent: 3 }, admin).expect(201)).body;
    const p = (await http.post('/admin/products', { categoryId, nameAr: `حقيبة ${run}`, basePrice: '60', variants: [{}] }, admin).expect(201)).body;
    const usd = await prisma.exchangeRate.findFirst({ where: { currency: 'USD' }, orderBy: { effectiveAt: 'desc' } });
    const res = await http.post('/admin/purchase-orders/import', { supplierId: supplier.id, foreignPerUsd: '7', items: [{ variantId: p.variants[0].id, quantity: 5, unitPriceForeign: '7' }] }, admin);
    if (!usd) {
      expect(res.status).toBe(422);
      return;
    }
    expect(res.status).toBe(201);
    expect(res.body.commissionPercent).toBe('3');
    expect(Number(res.body.sarPerUsd)).toBeCloseTo(1 / Number(usd.rate), 4);
    await http.post('/admin/purchase-orders/import', { supplierId: supplier.id, foreignPerUsd: '7' }, admin).expect(400);
  });
});
