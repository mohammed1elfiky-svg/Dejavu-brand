import { INestApplication } from '@nestjs/common';
import { PrismaService } from '../src/prisma/prisma.service';
import { api, createApp, createProduct, customerToken, ledgerBalances, staffToken } from './helpers';

/**
 * Phase 2(b): purchasing, expenses, sales returns, refused deliveries, courier
 * settlements, stock counts, financial reports and fiscal periods.
 */
describe('Back office: purchasing, expenses, returns, settlements, reports (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let warehouseId: string;
  let accounts: Record<string, string>;

  const run = Date.now().toString(36);
  let shirtS: string;
  let shirtM: string;

  const level = async (variantId: string) =>
    prisma.stockLevel.findUniqueOrThrow({ where: { variantId_warehouseId: { variantId, warehouseId } } });

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    warehouseId = (await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } })).id;
    const list = await http.get('/admin/accounts', admin).expect(200);
    accounts = Object.fromEntries(list.body.map((a: { code: string; id: string }) => [a.code, a.id]));
    [shirtS, shirtM] = await createProduct(app, admin, `shirt-${run}`, 570, ['S', 'M']);
  });
  afterAll(() => app.close());

  // ----------------------------------------------------------- purchasing ---

  describe('purchasing', () => {
    let supplierId: string;
    let poId: string;
    let items: { id: string; variantId: string }[];

    it('creates a supplier and a purchase order with VAT and freight on top', async () => {
      const sup = await http.post('/admin/suppliers', { name: 'مصنع النيل', phone: '0225550000' }, admin).expect(201);
      expect(sup.body.code).toMatch(/^SUP-\d{4}$/);
      supplierId = sup.body.id;

      const po = await http
        .post(
          '/admin/purchase-orders',
          {
            supplierId,
            supplierInvoiceNo: 'INV-77',
            shippingCost: 100,
            items: [
              { variantId: shirtS, quantity: 10, unitCost: 200, taxRate: 14 },
              { variantId: shirtM, quantity: 10, unitCost: 300, taxRate: 14 },
            ],
          },
          admin,
        )
        .expect(201);
      // goods 5000 + VAT 14% 700 + freight 100
      expect(po.body).toMatchObject({ status: 'DRAFT', subtotal: '5000', taxTotal: '700', grandTotal: '5800' });
      poId = po.body.id;
      items = po.body.items;

      // Nothing can be received before the order is placed.
      await http.post(`/admin/purchase-orders/${poId}/receive`, { items: [{ itemId: items[0].id, quantity: 1 }] }, admin).expect(422);
      await http.post(`/admin/purchase-orders/${poId}/place`, {}, admin).expect(200);
    });

    it('receives in parts at landed cost and books exactly the grand total to AP', async () => {
      const s = items.find((i) => i.variantId === shirtS)!;
      const m = items.find((i) => i.variantId === shirtM)!;

      const first = await http
        .post(`/admin/purchase-orders/${poId}/receive`, { items: [{ itemId: s.id, quantity: 4 }] }, admin)
        .expect(200);
      expect(first.body.status).toBe('PARTIALLY_RECEIVED');
      // 4 × 200 = 800 goods + 16 freight share (100 × 800/5000) + 112 VAT
      expect(await ledgerBalances(prisma, [poId])).toEqual({ INVENTORY: 816, VAT_INPUT: 112, AP: -928 });
      // Landed cost = 200 × (1 + 100/5000) = 204
      expect((await prisma.productVariant.findUniqueOrThrow({ where: { id: shirtS } })).avgCost.toString()).toBe('204');
      expect((await level(shirtS)).onHand).toBe(4);

      await http
        .post(`/admin/purchase-orders/${poId}/receive`, { items: [{ itemId: s.id, quantity: 7 }] }, admin)
        .expect(422);

      const done = await http
        .post(`/admin/purchase-orders/${poId}/receive`, { items: [{ itemId: s.id, quantity: 6 }, { itemId: m.id, quantity: 10 }] }, admin)
        .expect(200);
      expect(done.body.status).toBe('RECEIVED');
      expect(await ledgerBalances(prisma, [poId])).toEqual({ INVENTORY: 5100, VAT_INPUT: 700, AP: -5800 });

      const sup = await http.get(`/admin/suppliers/${supplierId}`, admin).expect(200);
      expect(sup.body.balance).toBe('5800');
    });

    it('pays the supplier against the order and keeps the statement in sync', async () => {
      await http
        .post('/admin/supplier-payments', { supplierId, purchaseOrderId: poId, method: 'BANK_TRANSFER', amount: 6000 }, admin)
        .expect(422);
      const pay = await http
        .post('/admin/supplier-payments', { supplierId, purchaseOrderId: poId, method: 'BANK_TRANSFER', amount: 3000, reference: 'TRX-1' }, admin)
        .expect(201);
      expect(pay.body.number).toMatch(/^PAY-/);
      expect(await ledgerBalances(prisma, [pay.body.id])).toEqual({ AP: 3000, BANK: -3000 });

      const po = await http.get(`/admin/purchase-orders/${poId}`, admin).expect(200);
      expect(po.body).toMatchObject({ amountPaid: '3000', balanceDue: '2800' });
      const statement = await http.get(`/admin/suppliers/${supplierId}/statement`, admin).expect(200);
      expect(statement.body.closingBalance).toBe('2800');
      expect(statement.body.lines).toHaveLength(3);
    });

    it('closes an order short and cancels one that never arrived', async () => {
      const po = await http
        .post('/admin/purchase-orders', { supplierId, items: [{ variantId: shirtM, quantity: 10, unitCost: 100, taxRate: 0 }] }, admin)
        .expect(201);
      await http.post(`/admin/purchase-orders/${po.body.id}/place`, {}, admin).expect(200);
      await http.post(`/admin/purchase-orders/${po.body.id}/receive`, { items: [{ itemId: po.body.items[0].id, quantity: 3 }] }, admin).expect(200);
      const closed = await http.post(`/admin/purchase-orders/${po.body.id}/close`, {}, admin).expect(200);
      expect(closed.body).toMatchObject({ status: 'RECEIVED', grandTotal: '300' });
      expect(await ledgerBalances(prisma, [po.body.id])).toEqual({ INVENTORY: 300, AP: -300 });

      const other = await http
        .post('/admin/purchase-orders', { supplierId, items: [{ variantId: shirtS, quantity: 1, unitCost: 50, taxRate: 14 }] }, admin)
        .expect(201);
      const cancelled = await http.post(`/admin/purchase-orders/${other.body.id}/cancel`, {}, admin).expect(200);
      expect(cancelled.body.status).toBe('CANCELLED');
      expect(await ledgerBalances(prisma, [other.body.id])).toEqual({});
    });
  });

  // ------------------------------------------------------------- expenses ---

  describe('expenses', () => {
    let utilitiesId: string;

    beforeAll(async () => {
      const cats = await http.get('/admin/expense-categories', admin).expect(200);
      utilitiesId = cats.body.find((c: { name: string }) => c.name === 'المرافق').id;
    });

    const today = () => new Date().toISOString().slice(0, 10);

    it('accrues an unpaid bill on approval and clears it when paid', async () => {
      const draft = await http
        .post('/admin/expenses', { categoryId: utilitiesId, amount: 1000, taxAmount: 140, expenseDate: today(), description: 'فاتورة كهرباء' }, admin)
        .expect(201);
      expect(draft.body).toMatchObject({ status: 'DRAFT', total: '1140' });

      const approved = await http.post(`/admin/expenses/${draft.body.id}/approve`, {}, admin).expect(200);
      expect(approved.body.status).toBe('APPROVED');
      expect(await ledgerBalances(prisma, [draft.body.id])).toEqual({ '5203': 1000, VAT_INPUT: 140, ACCRUED_EXPENSES: -1140 });

      const paid = await http.post(`/admin/expenses/${draft.body.id}/pay`, { paidFromAccountId: accounts['1101'] }, admin).expect(200);
      expect(paid.body.status).toBe('PAID');
      expect(await ledgerBalances(prisma, [draft.body.id])).toEqual({ '5203': 1000, VAT_INPUT: 140, CASH: -1140 });

      await http.post(`/admin/expenses/${draft.body.id}/cancel`, { reason: 'oops' }, admin).expect(422);
    });

    it('books a bill paid on the spot directly against cash', async () => {
      const draft = await http
        .post('/admin/expenses', { categoryId: utilitiesId, amount: 250, expenseDate: today(), description: 'إنترنت' }, admin)
        .expect(201);
      const res = await http.post(`/admin/expenses/${draft.body.id}/approve`, { paidFromAccountId: accounts['1101'] }, admin).expect(200);
      expect(res.body.status).toBe('PAID');
      expect(await ledgerBalances(prisma, [draft.body.id])).toEqual({ '5203': 250, CASH: -250 });
    });

    it('reverses the entry when an approved bill is cancelled', async () => {
      const draft = await http
        .post('/admin/expenses', { categoryId: utilitiesId, amount: 400, expenseDate: today(), description: 'مكرر' }, admin)
        .expect(201);
      await http.post(`/admin/expenses/${draft.body.id}/approve`, {}, admin).expect(200);
      const res = await http.post(`/admin/expenses/${draft.body.id}/cancel`, { reason: 'Duplicate bill' }, admin).expect(200);
      expect(res.body.status).toBe('CANCELLED');
      expect(res.body.journalEntries.map((e: { status: string }) => e.status).sort()).toEqual(['POSTED', 'REVERSED']);
      expect(await ledgerBalances(prisma, [draft.body.id])).toEqual({});
    });

    it('only lets approvers approve', async () => {
      const draft = await http
        .post('/admin/expenses', { categoryId: utilitiesId, amount: 10, expenseDate: today(), description: 'Test' }, admin)
        .expect(201);
      const roles = await http.get('/admin/roles', admin).expect(200);
      const sales = roles.body.find((r: { name: string }) => r.name === 'sales');
      const email = `sales.bo.${run}@dejavu.local`;
      await http.post('/admin/users', { name: 'Sales', email, password: 'sales-pass-1', roleIds: [sales.id] }, admin).expect(201);
      const salesToken = await staffToken(app, { email, password: 'sales-pass-1' });
      await http.post(`/admin/expenses/${draft.body.id}/approve`, {}, salesToken).expect(403);
    });
  });

  // ------------------------------------------------- returns & deliveries ---

  describe('sales returns, refused deliveries and courier settlements', () => {
    let shopper: { token: string; customerId: string };
    let addressId: string;
    let orderId: string;
    let orderNumber: string;
    const carrier = `Courier-${run}`;

    const order = async (items: [string, number][], paymentMethod: string) => {
      for (const [variantId, quantity] of items) {
        await http.post('/store/cart/items', { variantId, quantity }, shopper.token).expect(201);
      }
      return (await http.post('/store/orders', { addressId, paymentMethod }, shopper.token).expect(201)).body;
    };
    const status = (id: string, s: string, extra: object = {}) =>
      http.post(`/admin/orders/${id}/status`, { status: s, ...extra }, admin).expect(200);

    beforeAll(async () => {
      await http.put('/admin/shipping-zones', { governorate: 'القاهرة', fee: 50 }, admin).expect(200);
      shopper = await customerToken(app, 'عميلة المرتجعات');
      const addr = await http
        .post('/store/me/addresses', { recipient: 'عميلة', phone: '01011117777', governorate: 'القاهرة', city: 'حلوان', street: 'شارع منصور' }, shopper.token)
        .expect(201);
      addressId = addr.body.id;
    });

    it('delivers a COD order', async () => {
      const o = await order([[shirtS, 2]], 'CASH_ON_DELIVERY');
      orderId = o.id;
      orderNumber = o.number;
      expect(o.grandTotal).toBe('1190'); // 2 × 570 + 50
      await status(orderId, 'CONFIRMED');
      await status(orderId, 'SHIPPED', { carrier });
      const delivered = await status(orderId, 'DELIVERED');
      expect(delivered.body.paymentStatus).toBe('PAID');
    });

    it('takes a return request from the customer and refuses over-returns', async () => {
      const o = await http.get(`/admin/orders/${orderId}`, admin).expect(200);
      const itemId = o.body.items[0].id;
      await http.post(`/store/orders/${orderNumber}/returns`, { reason: 'كبير', items: [{ orderItemId: itemId, quantity: 3 }] }, shopper.token).expect(422);

      const ret = await http
        .post(`/store/orders/${orderNumber}/returns`, { reason: 'المقاس كبير', items: [{ orderItemId: itemId, quantity: 1 }] }, shopper.token)
        .expect(201);
      expect(ret.body).toMatchObject({ status: 'REQUESTED', refundAmount: '570' });
      // The second piece is the only one left to return while the first request is open.
      await http.post(`/store/orders/${orderNumber}/returns`, { reason: 'second request', items: [{ orderItemId: itemId, quantity: 2 }] }, shopper.token).expect(422);

      const mine = await http.get('/store/returns', shopper.token).expect(200);
      expect(mine.body).toHaveLength(1);
    });

    it('receives a restocked return: reverses revenue, VAT and cost, then refunds the cash', async () => {
      const list = await http.get('/admin/returns?status=REQUESTED', admin).expect(200);
      const ret = list.body.items.find((r: { order: { number: string } }) => r.order.number === orderNumber);
      await http.post(`/admin/returns/${ret.id}/refund`, { method: 'CASH' }, admin).expect(400);
      await http.post(`/admin/returns/${ret.id}/approve`, {}, admin).expect(200);

      const before = (await level(shirtS)).onHand;
      const received = await http.post(`/admin/returns/${ret.id}/receive`, {}, admin).expect(200);
      expect(received.body).toMatchObject({ status: 'RECEIVED', refundAmount: '570', refundDue: '570' });
      expect((await level(shirtS)).onHand).toBe(before + 1);
      // 570 incl. VAT 70; the piece goes back at its sale cost of 204.
      expect(await ledgerBalances(prisma, [ret.id])).toEqual({ SALES_RETURNS: 500, VAT_OUTPUT: 70, AR: -570, INVENTORY: 204, COGS: -204 });

      const refunded = await http.post(`/admin/returns/${ret.id}/refund`, { method: 'CASH' }, admin).expect(200);
      expect(refunded.body.status).toBe('REFUNDED');

      const o = await http.get(`/admin/orders/${orderId}`, admin).expect(200);
      expect(o.body).toMatchObject({ returnedTotal: '570', amountPaid: '620', balanceDue: '0', paymentStatus: 'PARTIALLY_REFUNDED' });
      const cust = await http.get(`/admin/customers/${shopper.customerId}`, admin).expect(200);
      expect(cust.body.ledgerBalance).toBe('0');
      const statement = await http.get(`/admin/customers/${shopper.customerId}/statement`, admin).expect(200);
      expect(statement.body.closingBalance).toBe('0');
    });

    it('does not restock damaged returns', async () => {
      const o = await http.get(`/admin/orders/${orderId}`, admin).expect(200);
      const ret = await http
        .post(`/admin/orders/${orderId}/returns`, { reason: 'تالف', items: [{ orderItemId: o.body.items[0].id, quantity: 1 }] }, admin)
        .expect(201);
      expect(ret.body.status).toBe('APPROVED'); // staff-created returns skip approval
      const before = (await level(shirtS)).onHand;
      await http.post(`/admin/returns/${ret.body.id}/receive`, { items: [{ itemId: ret.body.items[0].id, restock: false }] }, admin).expect(200);
      expect((await level(shirtS)).onHand).toBe(before);
      expect(await ledgerBalances(prisma, [ret.body.id])).toEqual({ SALES_RETURNS: 500, VAT_OUTPUT: 70, AR: -570 });
    });

    it('reverses the invoice and restocks when the customer refuses a delivery', async () => {
      const o = await order([[shirtM, 1]], 'CARD');
      await http.post(`/admin/orders/${o.id}/payments`, { method: 'CARD', amount: o.grandTotal }, admin).expect(201);
      const before = (await level(shirtM)).onHand;
      await status(o.id, 'SHIPPED', { carrier });
      expect((await level(shirtM)).onHand).toBe(before - 1);

      const refused = await status(o.id, 'RETURNED', { note: 'رفضت الاستلام' });
      expect(refused.body.status).toBe('RETURNED');
      expect((await level(shirtM)).onHand).toBe(before);
      expect(refused.body.shipments[0]).toMatchObject({ status: 'RETURNED_TO_SENDER', codAmount: '0' });

      // Invoice + its reversal cancel out; the prepayment is an advance again.
      const ids = [o.id, ...refused.body.payments.map((p: { id: string }) => p.id)];
      expect(await ledgerBalances(prisma, ids)).toEqual({ GATEWAY_CLEARING: 620, CUSTOMER_ADVANCES: -620 });

      await http.post(`/admin/orders/${o.id}/payments`, { method: 'CARD', amount: 1 }, admin).expect(422);
      const refunded = await http.post(`/admin/orders/${o.id}/refunds`, { method: 'CARD', amount: 620, reason: 'Refused' }, admin).expect(201);
      const all = [o.id, ...refunded.body.payments.map((p: { id: string }) => p.id)];
      expect(await ledgerBalances(prisma, all)).toEqual({});
    });

    it('settles COD cash with the courier, net of fees, exactly once', async () => {
      const pending = await http.get(`/admin/courier-settlements/pending?carrier=${carrier}`, admin).expect(200);
      expect(pending.body.carriers).toEqual([{ carrier, shipments: 1, collected: '1190' }]);
      const shipmentIds = pending.body.shipments.map((s: { id: string }) => s.id);

      await http.post('/admin/courier-settlements', { carrier: 'Other', shipmentIds, accountId: accounts['1102'] }, admin).expect(400);
      const st = await http
        .post('/admin/courier-settlements', { carrier, shipmentIds, feesAmount: 45, accountId: accounts['1102'], reference: 'PAYOUT-9' }, admin)
        .expect(201);
      expect(st.body).toMatchObject({ collectedAmount: '1190', feesAmount: '45', netAmount: '1145' });
      expect(await ledgerBalances(prisma, [st.body.id])).toEqual({ BANK: 1145, SHIPPING_EXPENSE: 45, COD_RECEIVABLE: -1190 });

      await http.post('/admin/courier-settlements', { carrier, shipmentIds, accountId: accounts['1102'] }, admin).expect(422);
      const after = await http.get(`/admin/courier-settlements/pending?carrier=${carrier}`, admin).expect(200);
      expect(after.body.shipments).toHaveLength(0);
    });
  });

  // --------------------------------------------------------- stock counts ---

  it('posts stock count differences as adjustments with one journal entry', async () => {
    const s = (await level(shirtS)).onHand;
    const m = (await level(shirtM)).onHand;
    const count = await http
      .post('/admin/stock-counts', { warehouseId, note: 'جرد شهري', items: [{ variantId: shirtS, countedQty: s + 2 }, { variantId: shirtM, countedQty: m - 1 }] }, admin)
      .expect(201);
    expect(count.body.items.map((i: { difference: number }) => i.difference).sort()).toEqual([-1, 2]);

    const posted = await http.post(`/admin/stock-counts/${count.body.id}/post`, {}, admin).expect(200);
    expect(posted.body.isPosted).toBe(true);
    expect((await level(shirtS)).onHand).toBe(s + 2);
    expect((await level(shirtM)).onHand).toBe(m - 1);

    const avgS = Number((await prisma.productVariant.findUniqueOrThrow({ where: { id: shirtS } })).avgCost);
    const avgM = Number((await prisma.productVariant.findUniqueOrThrow({ where: { id: shirtM } })).avgCost);
    const b = await ledgerBalances(prisma, [count.body.id]);
    expect(b.INVENTORY).toBeCloseTo(2 * avgS - avgM, 2);
    await http.post(`/admin/stock-counts/${count.body.id}/post`, {}, admin).expect(400);
  });

  // -------------------------------------------------------------- reports ---

  describe('financial reports', () => {
    it('produces a balance sheet that balances and agrees with the income statement', async () => {
      const bs = await http.get('/admin/reports/balance-sheet', admin).expect(200);
      expect(bs.body.balanced).toBe(true);
      expect(bs.body.assets.total).toBe(bs.body.totalLiabilitiesAndEquity);

      const is = await http.get('/admin/reports/income-statement?from=2000-01-01', admin).expect(200);
      expect(is.body.netIncome).toBe(bs.body.equity.currentEarnings);
      const codes = is.body.revenue.lines.map((l: { code: string }) => l.code);
      expect(codes).toEqual(expect.arrayContaining(['4101', '4102']));
      expect(is.body.operatingExpenses.lines.map((l: { code: string }) => l.code)).toEqual(expect.arrayContaining(['5203', '5205']));
    });

    it('reports sales by product with cost and gross profit', async () => {
      const res = await http.get('/admin/reports/sales?groupBy=product&from=2000-01-01', admin).expect(200);
      const shirt = res.body.groups.find((g: { label: string }) => g.label === `منتج shirt-${run}`);
      // Only the delivered COD order counts; the refused one was reversed.
      expect(shirt).toMatchObject({ orders: 1, units: 2, returnedUnits: 2, netSales: '1000', costOfGoods: '408', grossProfit: '592' });
    });

    it('keeps the stock valuation in line with the inventory account', async () => {
      const res = await http.get('/admin/reports/inventory-valuation', admin).expect(200);
      expect(Math.abs(Number(res.body.difference))).toBeLessThan(1);
    });

    it('summarises the business on the dashboard', async () => {
      const res = await http.get('/admin/reports/dashboard', admin).expect(200);
      expect(res.body.today.orders).toBeGreaterThanOrEqual(1);
      expect(res.body.cashAndBank.map((a: { code: string }) => a.code)).toEqual(expect.arrayContaining(['1101', '1102']));
      expect(res.body.payables.suppliers).toBe('3100'); // 5800 + 300 − 3000
    });
  });

  // ------------------------------------------------------- fiscal periods ---

  it('creates fiscal years and closes, reopens and locks periods', async () => {
    const fy = await http.post('/admin/fiscal-years', { year: 2031 }, admin).expect(201);
    expect(fy.body.periods).toHaveLength(12);
    await http.post('/admin/fiscal-years', { year: 2031 }, admin).expect(409);
    const march = fy.body.periods[2];

    const draft = await http
      .post('/admin/journal-entries', { entryDate: '2031-03-10', description: 'Draft', lines: [{ accountId: accounts['5202'], debit: 1 }, { accountId: accounts['1101'], credit: 1 }] }, admin)
      .expect(201);
    const blocked = await http.post(`/admin/fiscal-periods/${march.id}/close`, {}, admin).expect(422);
    expect(blocked.body.message).toContain(draft.body.number);
    await http.delete(`/admin/journal-entries/${draft.body.id}`, admin).expect(204);

    await http.post(`/admin/fiscal-periods/${march.id}/lock`, {}, admin).expect(400);
    await http.post(`/admin/fiscal-periods/${march.id}/close`, {}, admin).expect(200);
    await http.post(`/admin/fiscal-periods/${march.id}/reopen`, {}, admin).expect(200);
    await http.post(`/admin/fiscal-periods/${march.id}/close`, {}, admin).expect(200);
    await http.post(`/admin/fiscal-periods/${march.id}/lock`, {}, admin).expect(200);
    await http.post(`/admin/fiscal-periods/${march.id}/reopen`, {}, admin).expect(422);
  });
});
