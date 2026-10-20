import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, customerToken, staffToken } from './helpers';

/**
 * End-to-end business flow: catalog → opening stock → checkout → ship → deliver,
 * asserting the stock ledger and every journal entry along the way.
 */
describe('Catalog, inventory, sales & automatic journal entries (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;

  const run = Date.now().toString(36);
  let warehouseId: string;
  let productSlug: string;
  let variantA: string; // M / Black
  let variantB: string; // L / Black

  /** Balance per system account for posted entries, debit-positive. */
  async function balances(sourceIds?: string[]) {
    const lines = await prisma.journalLine.findMany({
      where: { journalEntry: { status: { in: ['POSTED', 'REVERSED'] }, ...(sourceIds && { sourceId: { in: sourceIds } }) } },
      include: { account: { select: { systemKey: true } } },
    });
    const out: Record<string, number> = {};
    for (const l of lines) {
      const key = l.account.systemKey ?? l.accountId;
      out[key] = Math.round(((out[key] ?? 0) + Number(l.debit) - Number(l.credit)) * 100) / 100;
    }
    return out;
  }

  async function level(variantId: string) {
    return prisma.stockLevel.findUniqueOrThrow({ where: { variantId_warehouseId: { variantId, warehouseId } } });
  }

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    warehouseId = (await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } })).id;
  });
  afterAll(() => app.close());

  // ------------------------------------------------------------- catalog ---

  it('creates a category and a product with variants using the default VAT rate', async () => {
    const cat = await http
      .post('/admin/categories', { nameAr: 'فساتين', nameEn: 'Dresses', slug: `dresses-${run}` }, admin)
      .expect(201);

    productSlug = `evening-dress-${run}`;
    const product = await http
      .post(
        '/admin/products',
        {
          categoryId: cat.body.id,
          nameAr: 'فستان سهرة',
          nameEn: 'Evening Dress',
          slug: productSlug,
          status: 'ACTIVE',
          basePrice: 1140,
          variants: [
            { sku: `DRS-${run}-M`.toUpperCase(), size: 'M', color: 'أسود' },
            { sku: `DRS-${run}-L`.toUpperCase(), size: 'L', color: 'أسود' },
          ],
          images: [{ url: 'https://cdn.example.com/dress.jpg' }],
        },
        admin,
      )
      .expect(201);

    expect(product.body.taxRate.name).toBe('VAT 14%');
    expect(product.body.variants).toHaveLength(2);
    [variantA, variantB] = product.body.variants.map((v: { id: string }) => v.id);

    await http.post('/admin/products', { ...product.body, variants: [{ sku: 'X-1' }] }, admin).expect(400);
  });

  it('rejects duplicate slugs with 409', async () => {
    const cat = await prisma.category.findFirstOrThrow({ where: { slug: `dresses-${run}` } });
    const res = await http
      .post(
        '/admin/products',
        { categoryId: cat.id, nameAr: 'نسخة', nameEn: 'Copy', slug: productSlug, basePrice: 10, variants: [{ sku: `CPY-${run}`.toUpperCase() }] },
        admin,
      )
      .expect(409);
    expect(res.body.message).toContain('slug');
  });

  // ----------------------------------------------------------- inventory ---

  it('books opening stock with a journal entry and weighted-average cost', async () => {
    const opening = await http
      .post(
        '/admin/inventory/adjustments',
        {
          warehouseId,
          reason: 'OPENING',
          items: [
            { variantId: variantA, quantity: 10, unitCost: 500 },
            { variantId: variantB, quantity: 5, unitCost: 600 },
          ],
        },
        admin,
      )
      .expect(201);
    expect(opening.body.totalValue).toBe('8000');
    expect(await balances([opening.body.sourceId])).toEqual({ INVENTORY: 8000, OPENING_BALANCE: -8000 });

    // Another 10 pieces at 700 → average (10×500 + 10×700) / 20 = 600
    await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'OTHER_IN', items: [{ variantId: variantA, quantity: 10, unitCost: 700 }] }, admin)
      .expect(201);
    const v = await prisma.productVariant.findUniqueOrThrow({ where: { id: variantA } });
    expect(v.avgCost.toString()).toBe('600');
    expect((await level(variantA)).onHand).toBe(20);

    await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'OPENING', items: [{ variantId: variantA, quantity: 1 }] }, admin)
      .expect(400);
  });

  it('refuses to take out more stock than available', async () => {
    const res = await http
      .post('/admin/inventory/adjustments', { warehouseId, reason: 'DAMAGE', items: [{ variantId: variantB, quantity: 99 }] }, admin)
      .expect(409);
    expect(res.body.message).toContain('Insufficient stock');
    expect((await level(variantB)).onHand).toBe(5);
  });

  it('shows availability but never cost in the public catalog', async () => {
    const res = await http.get(`/store/products/${productSlug}`).expect(200);
    const a = res.body.variants.find((v: { id: string }) => v.id === variantA);
    expect(a).toMatchObject({ availableQty: 20, inStock: true, price: '1140' });
    expect(JSON.stringify(res.body)).not.toContain('avgCost');
  });

  // --------------------------------------------------------------- sales ---

  let customer: { token: string; customerId: string };
  let addressId: string;
  let codOrderId: string;
  let codOrderNumber: string;

  it('sets up shipping, a coupon and a customer address', async () => {
    await http.put('/admin/shipping-zones', { governorate: 'القاهرة', fee: 50 }, admin).expect(200);
    await http
      .post('/admin/coupons', { code: `SAVE10${run}`, type: 'PERCENTAGE', value: 10, startsAt: '2020-01-01T00:00:00Z' }, admin)
      .expect(201);

    customer = await customerToken(app, 'عميل تجريبي');
    const addr = await http
      .post(
        '/store/me/addresses',
        { recipient: 'عميل تجريبي', phone: '01011112222', governorate: 'القاهرة', city: 'مدينة نصر', street: 'شارع عباس العقاد' },
        customer.token,
      )
      .expect(201);
    addressId = addr.body.id;
    expect(addr.body.isDefault).toBe(true);
  });

  it('prices the cart with VAT-inclusive prices, coupon and shipping', async () => {
    await http.post('/store/cart/items', { variantId: variantA, quantity: 2 }, customer.token).expect(201);
    let cart = await http.get(`/store/cart?addressId=${addressId}`, customer.token).expect(200);
    expect(cart.body.totals).toMatchObject({ subtotal: '2280', taxTotal: '280', shippingFee: '50', grandTotal: '2330' });

    cart = await http.put('/store/cart/coupon', { code: `save10${run}` }, customer.token).expect(200);
    cart = await http.get(`/store/cart?addressId=${addressId}`, customer.token).expect(200);
    // 10% off 2280 = 228 → 2052 incl. VAT (252) + 50 shipping
    expect(cart.body.totals).toMatchObject({ discountTotal: '228', taxTotal: '252', grandTotal: '2102' });
    expect(cart.body.canCheckout).toBe(true);
  });

  it('checks out: reserves stock and records the coupon use', async () => {
    const res = await http
      .post('/store/orders', { addressId, paymentMethod: 'CASH_ON_DELIVERY' }, customer.token)
      .expect(201);
    codOrderNumber = res.body.number;
    codOrderId = res.body.id;
    expect(codOrderNumber).toMatch(/^SO-\d{4}-\d{6}$/);
    expect(res.body).toMatchObject({ status: 'PENDING', paymentStatus: 'UNPAID', grandTotal: '2102' });
    expect(JSON.stringify(res.body)).not.toContain('unitCost');

    expect(await level(variantA)).toMatchObject({ onHand: 20, reserved: 2 });
    const cart = await http.get('/store/cart', customer.token).expect(200);
    expect(cart.body.items).toHaveLength(0);
    // Reservations post nothing to the ledger.
    expect(await balances([codOrderId])).toEqual({});
  });

  it('blocks checkout when the cart asks for more than is available', async () => {
    const other = await customerToken(app);
    const addr = await http
      .post('/store/me/addresses', { recipient: 'Other Shopper', phone: '01011113333', governorate: 'القاهرة', city: 'المعادي', street: 'شارع 9' }, other.token)
      .expect(201);
    await http.post('/store/cart/items', { variantId: variantB, quantity: 6 }, other.token).expect(201);
    const res = await http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CASH_ON_DELIVERY' }, other.token).expect(422);
    expect(res.body.problems[0]).toContain('Only 5 left');
  });

  it('rejects invalid status transitions', async () => {
    await http.post(`/admin/orders/${codOrderId}/status`, { status: 'DELIVERED' }, admin).expect(422);
  });

  it('ships: issues stock and posts revenue, VAT, shipping income and COGS', async () => {
    await http.post(`/admin/orders/${codOrderId}/status`, { status: 'CONFIRMED' }, admin).expect(200);
    const shipped = await http
      .post(`/admin/orders/${codOrderId}/status`, { status: 'SHIPPED', carrier: 'Bosta', trackingNumber: 'BST-1' }, admin)
      .expect(200);

    expect(await level(variantA)).toMatchObject({ onHand: 18, reserved: 0 });
    expect(await balances([codOrderId])).toEqual({
      AR: 2102,
      SALES_DISCOUNTS: 228,
      SALES: -2028, // 2102 − 50 shipping − 252 VAT + 228 discount
      VAT_OUTPUT: -252,
      SHIPPING_INCOME: -50,
      COGS: 1200, // 2 × 600 average cost
      INVENTORY: -1200,
    });
    expect(shipped.body.shipments[0]).toMatchObject({ carrier: 'Bosta', codAmount: '2102' });
    expect(shipped.body.profit.costOfGoods).toBe('1200');
  });

  it('delivers COD orders and moves the receivable to the courier', async () => {
    const res = await http.post(`/admin/orders/${codOrderId}/status`, { status: 'DELIVERED' }, admin).expect(200);
    expect(res.body).toMatchObject({ status: 'DELIVERED', paymentStatus: 'PAID', amountPaid: '2102', balanceDue: '0' });

    const ids = [codOrderId, ...res.body.payments.map((p: { id: string }) => p.id)];
    const b = await balances(ids);
    expect(b.AR).toBe(0);
    expect(b.COD_RECEIVABLE).toBe(2102);

    const cust = await http.get(`/admin/customers/${customer.customerId}`, admin).expect(200);
    expect(cust.body.ledgerBalance).toBe('0');
  });

  it('lets the customer track the order', async () => {
    const res = await http.get(`/store/orders/${codOrderNumber}`, customer.token).expect(200);
    expect(res.body.statusHistory.map((h: { toStatus: string }) => h.toStatus)).toEqual([
      'PENDING',
      'CONFIRMED',
      'SHIPPED',
      'DELIVERED',
    ]);
    const stranger = await customerToken(app);
    await http.get(`/store/orders/${codOrderNumber}`, stranger.token).expect(404);
  });

  // ---------------------------------------------------- prepaid & cancel ---

  it('treats prepayment as a customer advance, then refunds it after cancellation', async () => {
    await http.post('/store/cart/items', { variantId: variantB, quantity: 1 }, customer.token).expect(201);
    const order = await http.post('/store/orders', { addressId, paymentMethod: 'CARD' }, customer.token).expect(201);
    expect(order.body.grandTotal).toBe('1190');
    expect(await level(variantB)).toMatchObject({ reserved: 1 });

    const paid = await http
      .post(`/admin/orders/${order.body.id}/payments`, { method: 'CARD', amount: 1190, reference: `gw-${run}` }, admin)
      .expect(201);
    expect(paid.body).toMatchObject({ paymentStatus: 'PAID', status: 'CONFIRMED' }); // auto-confirmed

    await http.post(`/admin/orders/${order.body.id}/payments`, { method: 'CARD', amount: 1 }, admin).expect(422);

    const cancelled = await http
      .post(`/admin/orders/${order.body.id}/status`, { status: 'CANCELLED', note: 'Customer changed mind' }, admin)
      .expect(200);
    expect(cancelled.body.status).toBe('CANCELLED');
    expect(await level(variantB)).toMatchObject({ onHand: 5, reserved: 0 });

    const refunded = await http
      .post(`/admin/orders/${order.body.id}/refunds`, { method: 'CARD', amount: 1190, reason: 'Order cancelled' }, admin)
      .expect(201);
    expect(refunded.body).toMatchObject({ paymentStatus: 'REFUNDED', amountPaid: '0' });

    const ids = refunded.body.payments.map((p: { id: string }) => p.id);
    expect(await balances(ids)).toEqual({ GATEWAY_CLEARING: 0, CUSTOMER_ADVANCES: 0 });
  });

  it('lets customers cancel their own pending orders and gives the coupon back', async () => {
    const coupon = await prisma.coupon.findUniqueOrThrow({ where: { code: `SAVE10${run}`.toUpperCase() } });
    const shopper = await customerToken(app);
    const addr = await http
      .post('/store/me/addresses', { recipient: 'Third Shopper', phone: '01011114444', governorate: 'القاهرة', city: 'الزمالك', street: 'شارع 26 يوليو' }, shopper.token)
      .expect(201);
    await http.post('/store/cart/items', { variantId: variantA, quantity: 1 }, shopper.token).expect(201);
    await http.put('/store/cart/coupon', { code: coupon.code }, shopper.token).expect(200);
    const order = await http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CASH_ON_DELIVERY' }, shopper.token).expect(201);
    expect((await prisma.coupon.findUniqueOrThrow({ where: { id: coupon.id } })).usedCount).toBe(coupon.usedCount + 1);

    const res = await http.post(`/store/orders/${order.body.number}/cancel`, { reason: 'Ordered by mistake' }, shopper.token).expect(200);
    expect(res.body.status).toBe('CANCELLED');
    expect((await prisma.coupon.findUniqueOrThrow({ where: { id: coupon.id } })).usedCount).toBe(coupon.usedCount);
    expect(await level(variantA)).toMatchObject({ onHand: 18, reserved: 0 });

    await http.post(`/store/orders/${codOrderNumber}/cancel`, { reason: 'too late' }, customer.token).expect(403);
  });

  it('never oversells when two customers check out the last pieces at the same time', async () => {
    const shoppers = await Promise.all([customerToken(app), customerToken(app)]);
    const addresses: string[] = [];
    for (const [i, s] of shoppers.entries()) {
      const addr = await http
        .post('/store/me/addresses', { recipient: `Racer ${i}`, phone: '01011115555', governorate: 'القاهرة', city: 'الدقي', street: 'شارع التحرير' }, s.token)
        .expect(201);
      addresses.push(addr.body.id);
      await http.post('/store/cart/items', { variantId: variantB, quantity: 3 }, s.token).expect(201);
    }
    // Both carts look fine (5 available ≥ 3) — only one can win the reservation.
    const results = await Promise.all(
      shoppers.map((s, i) => http.post('/store/orders', { addressId: addresses[i], paymentMethod: 'CASH_ON_DELIVERY' }, s.token)),
    );
    expect(results.map((r) => r.status).sort()).toEqual([201, 409]);
    expect(await level(variantB)).toMatchObject({ onHand: 5, reserved: 3 });

    const winner = results.find((r) => r.status === 201)!;
    await http.post(`/admin/orders/${winner.body.id}/status`, { status: 'CANCELLED' }, admin).expect(200);
    expect(await level(variantB)).toMatchObject({ onHand: 5, reserved: 0 });
  });

  it('creates only one order when the same cart is submitted twice at once', async () => {
    const s = await customerToken(app);
    const addr = await http
      .post('/store/me/addresses', { recipient: 'Double Tap', phone: '01011116666', governorate: 'القاهرة', city: 'العباسية', street: 'شارع رمسيس' }, s.token)
      .expect(201);
    await http.post('/store/cart/items', { variantId: variantA, quantity: 1 }, s.token).expect(201);
    const results = await Promise.all(
      [1, 2].map(() => http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CASH_ON_DELIVERY' }, s.token)),
    );
    expect(results.map((r) => r.status).sort()).toEqual([201, 422]);
    const winner = results.find((r) => r.status === 201)!;
    await http.post(`/store/orders/${winner.body.number}/cancel`, { reason: 'test cleanup' }, s.token).expect(200);
  });

  // ----------------------------------------------------------- accounting ---

  it('keeps manual journal entries balanced, immutable once posted, and reversible', async () => {
    const accounts = await http.get('/admin/accounts', admin).expect(200);
    const id = (code: string) => accounts.body.find((a: { code: string }) => a.code === code).id;
    const today = new Date().toISOString().slice(0, 10);

    const unbalanced = await http
      .post(
        '/admin/journal-entries',
        { entryDate: today, description: 'Rent', lines: [{ accountId: id('5202'), debit: 1000 }, { accountId: id('1101'), credit: 900 }] },
        admin,
      )
      .expect(201);
    await http.post(`/admin/journal-entries/${unbalanced.body.id}/post`, {}, admin).expect(422);
    await http.delete(`/admin/journal-entries/${unbalanced.body.id}`, admin).expect(204);

    const group = await http
      .post('/admin/journal-entries', { entryDate: today, description: 'Group', lines: [{ accountId: id('11'), debit: 5 }, { accountId: id('1101'), credit: 5 }] }, admin)
      .expect(422);
    expect(group.body.message).toBe('Account 11 (Current Assets) is a group or inactive account');

    const draft = await http
      .post(
        '/admin/journal-entries',
        { entryDate: today, description: 'Capital injection', lines: [{ accountId: id('1102'), debit: 50000 }, { accountId: id('3101'), credit: 50000 }] },
        admin,
      )
      .expect(201);
    const posted = await http.post(`/admin/journal-entries/${draft.body.id}/post`, {}, admin).expect(200);
    expect(posted.body).toMatchObject({ status: 'POSTED', totalDebit: '50000', totalCredit: '50000' });

    await http.delete(`/admin/journal-entries/${draft.body.id}`, admin).expect(400);

    const reversal = await http.post(`/admin/journal-entries/${draft.body.id}/reverse`, { reason: 'Entered twice' }, admin).expect(201);
    expect(reversal.body.reversalOfId).toBe(draft.body.id);
    const original = await http.get(`/admin/journal-entries/${draft.body.id}`, admin).expect(200);
    expect(original.body.status).toBe('REVERSED');

    const ledger = await http.get(`/admin/accounts/${id('3101')}/ledger`, admin).expect(200);
    expect(ledger.body.closingBalance).toBe('0');
  });

  it('refuses to post into a closed period', async () => {
    const accounts = await http.get('/admin/accounts', admin).expect(200);
    const id = (code: string) => accounts.body.find((a: { code: string }) => a.code === code).id;
    const year = new Date().getUTCFullYear();
    const period = await prisma.fiscalPeriod.findFirstOrThrow({ where: { name: `${year}-01` } });
    await prisma.fiscalPeriod.update({ where: { id: period.id }, data: { status: 'CLOSED' } });
    try {
      const res = await http
        .post(
          '/admin/journal-entries',
          { entryDate: `${year}-01-15`, description: 'Late', lines: [{ accountId: id('5202'), debit: 1 }, { accountId: id('1101'), credit: 1 }] },
          admin,
        )
        .expect(422);
      expect(res.body.message).toContain('closed');
    } finally {
      await prisma.fiscalPeriod.update({ where: { id: period.id }, data: { status: 'OPEN' } });
    }
  });

  it('produces a balanced trial balance where inventory equals stock on hand', async () => {
    const tb = await http.get('/admin/reports/trial-balance', admin).expect(200);
    expect(tb.body.totals.balanced).toBe(true);

    const inventory = tb.body.accounts.find((a: { code: string }) => a.code === '1120');
    // On hand: A 18 × 600 + B 5 × 600 = 13,800
    expect(inventory.debitBalance).toBe('13800');
    const lvl = await http.get(`/admin/inventory/levels?q=${run}`, admin).expect(200);
    const value = lvl.body.items.reduce((s: number, r: { stockValue: string }) => s + Number(r.stockValue), 0);
    expect(value).toBe(13800);
  });

  it('lists stock movements for an order', async () => {
    const res = await http.get(`/admin/inventory/movements?sourceId=${codOrderId}`, admin).expect(200);
    expect(res.body.items).toHaveLength(1);
    expect(res.body.items[0]).toMatchObject({ type: 'SALE', quantity: -2, unitCost: '600' });
  });

  it('transfers stock between warehouses at cost without touching the ledger', async () => {
    const wh = await http.post('/admin/warehouses', { code: `W${run}`.toUpperCase().slice(0, 20), name: 'فرع الإسكندرية' }, admin).expect(201);
    const before = await balances();
    const trf = await http
      .post('/admin/inventory/transfers', { fromWarehouseId: warehouseId, toWarehouseId: wh.body.id, items: [{ variantId: variantB, quantity: 4 }] }, admin)
      .expect(201);
    expect(trf.body.number).toMatch(/^TRF-/);
    expect(await level(variantB)).toMatchObject({ onHand: 1 });
    expect(await balances()).toEqual(before);
    const v = await prisma.productVariant.findUniqueOrThrow({ where: { id: variantB } });
    expect(v.avgCost.toString()).toBe('600');

    // Only the default warehouse feeds the online store, so B is now nearly sold out there.
    const store = await http.get(`/store/products/${productSlug}`).expect(200);
    expect(store.body.variants.find((x: { id: string }) => x.id === variantB).availableQty).toBe(1);

    // Total B is 5 ≤ threshold 5 → flagged as low stock.
    const low = await http.get('/admin/inventory/low-stock', admin).expect(200);
    expect(low.body.map((r: { variant_id: string }) => r.variant_id)).toContain(variantB);
    expect(low.body.map((r: { variant_id: string }) => r.variant_id)).not.toContain(variantA);
  });
});
