import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';
import { API, api, createApp, customerToken, staffToken } from './helpers';

/** Till sales in several currencies, cashier drawers, customer accounts, wallet top-ups and promotions. */
describe('POS, treasury & wallet (e2e)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let http: ReturnType<typeof api>;
  let admin: string;
  let cashier: string;
  let cashierId: string;
  let product: { id: string; skuNumber: number; variants: { id: string; sku: string }[] };
  const run = Date.now().toString(36);
  const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

  /** Ledger must stay balanced and every box must agree with its account. */
  async function assertBooksAgree() {
    const boxes = await prisma.cashBox.findMany();
    for (const b of boxes) {
      const gl = await prisma.journalLine.aggregate({ where: { accountId: b.accountId }, _sum: { debit: true, credit: true } });
      const entries = await prisma.cashBoxEntry.aggregate({ where: { cashBoxId: b.id }, _sum: { baseAmount: true } });
      expect(Number(gl._sum.debit ?? 0) - Number(gl._sum.credit ?? 0)).toBeCloseTo(Number(entries._sum.baseAmount ?? 0), 2);
    }
  }

  async function drawer(currency: string) {
    const res = await http.get('/admin/cash-boxes/mine', cashier).expect(200);
    return res.body.find((b: { currency: string }) => b.currency === currency);
  }

  beforeAll(async () => {
    ({ app, prisma } = await createApp());
    http = api(app);
    admin = await staffToken(app);
    await http.put('/admin/exchange-rates/YER', { rate: '140' }, admin).expect(200);
    const cashierRole = await prisma.role.findUniqueOrThrow({ where: { name: 'cashier' } });
    const user = await http
      .post('/admin/users', { name: 'كاشير الاختبار', email: `cashier-${run}@dejavu.local`, password: 'Cashier!2026', roleIds: [cashierRole.id] }, admin)
      .expect(201);
    cashierId = user.body.id;
    cashier = await staffToken(app, { email: `cashier-${run}@dejavu.local`, password: 'Cashier!2026' });

    const cat = await http.post('/admin/categories', { nameAr: 'أحذية', nameEn: 'Shoes', slug: `shoes-${run}` }, admin).expect(201);
    const created = await http
      .post(
        '/admin/products',
        {
          categoryId: cat.body.id,
          nameAr: 'حذاء رياضي',
          nameEn: 'Sneaker',
          slug: `sneaker-${run}`,
          status: 'ACTIVE',
          basePrice: '100',
          costPrice: '40',
          taxRateId: null,
          variants: [
            { color: 'أسود', size: '35', initialQuantity: 20, barcode: `629${run}1` },
            { color: 'أسود', size: '36', initialQuantity: 20 },
          ],
        },
        admin,
      )
      .expect(201);
    product = created.body;
  });
  afterAll(() => app.close());

  it('numbers SKUs automatically and finds items by SKU, factory barcode or product number', async () => {
    expect(product.variants.map((v) => v.sku)).toEqual([`${product.skuNumber}-1`, `${product.skuNumber}-2`]);
    const bySku = await http.get(`/admin/pos/lookup?code=${product.skuNumber}-2`, cashier).expect(200);
    expect(bySku.body.variantId).toBe(product.variants[1].id);
    const byBarcode = await http.get(`/admin/pos/lookup?code=629${run}1`, cashier).expect(200);
    expect(byBarcode.body.variantId).toBe(product.variants[0].id);
    const byNumber = await http.get(`/admin/pos/lookup?code=${product.skuNumber}`, cashier).expect(200);
    expect(byNumber.body.product.variants).toHaveLength(2);
    await http.get('/admin/pos/lookup?code=NOPE-404', cashier).expect(404);
  });

  it('manages currencies: add one, set its rate and choose whether shoppers see it', async () => {
    const all = await http.get('/admin/currencies', admin).expect(200);
    expect(all.body.currencies.map((c: { code: string }) => c.code)).toEqual(expect.arrayContaining(['SAR', 'YER', 'YEQ', 'USD']));
    await http.post('/admin/currencies', { code: 'aed', nameAr: 'درهم إماراتي', symbol: 'د.إ', rate: '0.98' }, cashier).expect(403);
    const aed = await http.post('/admin/currencies', { code: 'aed', nameAr: 'درهم إماراتي', symbol: 'د.إ', rate: '0.98' }, admin).expect(201);
    expect(aed.body).toMatchObject({ code: 'AED', rate: '0.98', showInCustomerApp: false });

    let store = await http.get('/store/currencies').expect(200);
    expect(store.body.currencies.map((c: { code: string }) => c.code)).not.toContain('AED');
    await http.patch('/admin/currencies/AED', { showInCustomerApp: true }, admin).expect(200);
    store = await http.get('/store/currencies').expect(200);
    expect(store.body.currencies.map((c: { code: string }) => c.code)).toContain('AED');
    await http.patch('/admin/currencies/AED', { showInCustomerApp: false, isActive: false }, admin).expect(200);
    await http.patch('/admin/currencies/SAR', { isActive: false }, admin).expect(422);
  });

  it('applies a running promotion in the store, the cart and the till', async () => {
    await http.post('/admin/promotions', { name: 'خصم الشتاء', percent: '20', productIds: [product.id] }, cashier).expect(403);
    const promo = await http.post('/admin/promotions', { name: 'خصم الشتاء', percent: '20', productIds: [product.id] }, admin).expect(201);
    expect(promo.body.running).toBe(true);

    const store = await http.get(`/store/products/sneaker-${run}`).expect(200);
    expect(store.body).toMatchObject({ basePrice: '80', compareAtPrice: '100', promotion: { percent: '20' } });
    expect(store.body.variants[0]).toMatchObject({ price: '80', compareAtPrice: '100' });

    const grid = await http.get('/admin/pos/products', cashier).expect(200);
    expect(grid.body.find((p: { id: string }) => p.id === product.id).variants[0].price).toBe('80');

    await http.patch(`/admin/promotions/${promo.body.id}`, { isActive: false }, admin).expect(200);
    const after = await http.get(`/store/products/sneaker-${run}`).expect(200);
    expect(after.body.basePrice).toBe('100');
  });

  it('sells for cash in YER paid with SAR and YER, giving change in YER from the drawer', async () => {
    // 2 × 100 SAR − 10% = 180 SAR = 25,200 YER at 140. Paid: 100 SAR + 14,000 YER (=100 SAR) → 20 SAR change = 2,800 YER.
    const sale = await http
      .post(
        '/admin/pos/sales',
        {
          items: [{ variantId: product.variants[0].id, quantity: 2 }],
          currency: 'YER',
          saleType: 'CASH',
          discountType: 'PERCENT',
          discountValue: '10',
          payments: [
            { currency: 'SAR', amount: '100' },
            { currency: 'YER', amount: '14000' },
          ],
        },
        cashier,
      )
      .expect(201);
    expect(sale.body).toMatchObject({ channel: 'POS', status: 'COMPLETED', paymentStatus: 'PAID', grandTotal: '180', discountTotal: '20' });
    expect(sale.body.inCurrency.grandTotal).toBe('25200');
    const tenders = sale.body.payments.map((p: { currency: string; currencyAmount: string; amount: string; isChange: boolean }) => [
      p.currency,
      p.currencyAmount,
      p.amount,
      p.isChange,
    ]);
    expect(tenders).toEqual([
      ['SAR', '100', '100', false],
      ['YER', '14000', '100', false],
      ['YER', '-2800', '-20', true],
    ]);
    expect((await drawer('SAR')).balance).toBe('100');
    expect((await drawer('YER')).balance).toBe('11200');
    await assertBooksAgree();
  });

  it('rejects a short cash payment and needs a named customer for credit', async () => {
    const items = [{ variantId: product.variants[1].id, quantity: 1 }];
    const short = await http.post('/admin/pos/sales', { items, saleType: 'CASH', payments: [{ currency: 'SAR', amount: '50' }] }, cashier).expect(422);
    expect(short.body.message).toContain('short by 50');
    await http.post('/admin/pos/sales', { items, saleType: 'CREDIT', payments: [] }, cashier).expect(400);
  });

  it('takes a bank transfer at the till into the bank box, with its slip number', async () => {
    const bank = (await http.get('/admin/cash-boxes?kind=BANK&currency=SAR', admin).expect(200)).body[0];
    const before = Number(bank.balance);
    const sale = await http
      .post(
        '/admin/pos/sales',
        {
          items: [{ variantId: product.variants[1].id, quantity: 1 }],
          saleType: 'CASH',
          paymentMethod: 'BANK_TRANSFER',
          reference: 'SLIP-77',
          payments: [{ currency: 'SAR', amount: '100' }],
        },
        cashier,
      )
      .expect(201);
    expect(sale.body).toMatchObject({ paymentMethod: 'BANK_TRANSFER', paymentStatus: 'PAID' });
    expect(sale.body.payments[0]).toMatchObject({ method: 'BANK_TRANSFER', gatewayPayload: { reference: 'SLIP-77' } });
    const after = (await http.get('/admin/cash-boxes?kind=BANK&currency=SAR', admin).expect(200)).body.find((b: { id: string }) => b.id === bank.id);
    expect(Number(after.balance)).toBe(before + 100);
    await assertBooksAgree();
  });

  it('sells on credit to a customer account in YER and takes the debt back at the till', async () => {
    const customer = await http.post('/admin/pos/customers', { name: 'عميل آجل', phone: `77${run.slice(-7).replace(/\D/g, '1').padStart(7, '1')}` }, cashier);
    expect(customer.status).toBe(201);
    const customerId = customer.body.id;
    // 1 × 100 SAR = 14,000 YER; 4,000 YER now, 10,000 YER on account.
    const sale = await http
      .post(
        '/admin/pos/sales',
        { items: [{ variantId: product.variants[1].id, quantity: 1 }], currency: 'YER', saleType: 'CREDIT', customerId, payments: [{ currency: 'YER', amount: '4000' }] },
        cashier,
      )
      .expect(201);
    expect(sale.body.paymentStatus).toBe('PAID');
    let account = await http.get(`/admin/customers/${customerId}/account`, admin).expect(200);
    expect(account.body.balances).toEqual([expect.objectContaining({ currency: 'YER', balance: '10000' })]);

    // The rate moves; the customer still owes riyals, not SAR.
    await http.put('/admin/exchange-rates/YER', { rate: '150' }, admin).expect(200);
    await http.post(`/admin/customers/${customerId}/account/receipts`, { currency: 'YER', amount: '6000' }, cashier).expect(201);
    account = await http.get(`/admin/customers/${customerId}/account`, admin).expect(200);
    expect(account.body.balances).toEqual([expect.objectContaining({ currency: 'YER', balance: '4000' })]);
    expect((await drawer('YER')).balance).toBe(String(11200 + 4000 + 6000));
    await http.put('/admin/exchange-rates/YER', { rate: '140' }, admin).expect(200);
    await assertBooksAgree();
  });

  it('takes a return at the till: stock back, money back from the drawer', async () => {
    const sale = await http
      .post('/admin/pos/sales', { items: [{ variantId: product.variants[0].id, quantity: 2 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '200' }] }, cashier)
      .expect(201);
    const found = await http.get(`/admin/pos/sales/by-number/${sale.body.number}`, cashier).expect(200);
    expect(found.body.items[0].returnableQty).toBe(2);
    const stockBefore = await prisma.stockLevel.findFirstOrThrow({ where: { variantId: product.variants[0].id } });
    const drawerBefore = Number((await drawer('SAR')).balance);
    const ret = await http
      .post('/admin/pos/returns', { orderId: sale.body.id, items: [{ orderItemId: found.body.items[0].id, quantity: 1 }], refundTo: 'CASH' }, cashier)
      .expect(201);
    expect(ret.body.return.status).toBe('REFUNDED');
    expect(ret.body.refunded.sar).toBe('100');
    expect(Number((await drawer('SAR')).balance)).toBe(drawerBefore - 100);
    const stockAfter = await prisma.stockLevel.findFirstOrThrow({ where: { variantId: product.variants[0].id } });
    expect(stockAfter.onHand).toBe(stockBefore.onHand + 1);
    const again = await http.get(`/admin/pos/sales/by-number/${sale.body.number}`, cashier).expect(200);
    expect(again.body.items[0].returnableQty).toBe(1);
    await http.post('/admin/pos/returns', { orderId: sale.body.id, items: [{ orderItemId: found.body.items[0].id, quantity: 2 }] }, cashier).expect(422);
    await assertBooksAgree();
  });

  it('exchanges one size for another and settles only the difference', async () => {
    // Sell size 35 at 100; customer swaps it for 2 × size 36 (200): pays 100 more.
    const sale = await http
      .post('/admin/pos/sales', { items: [{ variantId: product.variants[0].id, quantity: 1 }], saleType: 'CASH', payments: [{ currency: 'SAR', amount: '100' }] }, cashier)
      .expect(201);
    const drawerBefore = Number((await drawer('SAR')).balance);
    const ex = await http
      .post(
        '/admin/pos/returns',
        {
          orderId: sale.body.id,
          items: [{ orderItemId: sale.body.items[0].id, quantity: 1 }],
          newItems: [{ variantId: product.variants[1].id, quantity: 2 }],
          payments: [{ currency: 'SAR', amount: '100' }],
        },
        cashier,
      )
      .expect(201);
    expect(ex.body.sale).toMatchObject({ grandTotal: '200', paymentStatus: 'PAID' });
    expect(ex.body.sale.payments.map((p: { method: string; amount: string }) => [p.method, p.amount])).toEqual([
      ['CUSTOMER_ACCOUNT', '100'],
      ['CASH', '100'],
    ]);
    expect(Number((await drawer('SAR')).balance)).toBe(drawerBefore + 100);
    // Walk-in account nets to zero.
    const walkIn = await prisma.customer.findUniqueOrThrow({ where: { code: 'CUS-WALKIN' } });
    const bal = await prisma.customerAccountEntry.aggregate({ where: { customerId: walkIn.id, currency: 'SAR' }, _sum: { amount: true } });
    expect(Number(bal._sum.amount ?? 0)).toBe(0);

    // The other way round: swap 2 × 36 (200) for 1 × 35 (100): 100 back in cash.
    const back = await http
      .post(
        '/admin/pos/returns',
        { orderId: ex.body.sale.id, items: [{ orderItemId: ex.body.sale.items[0].id, quantity: 2 }], newItems: [{ variantId: product.variants[0].id, quantity: 1 }] },
        cashier,
      )
      .expect(201);
    expect(back.body.leftover).toBe('100');
    expect(Number((await drawer('SAR')).balance)).toBe(drawerBefore);
    await assertBooksAgree();
  });

  it('keeps cashiers to their own drawers; managers see and withdraw from all of them', async () => {
    await http.get('/admin/cash-boxes', cashier).expect(403);
    const all = await http.get('/admin/cash-boxes', admin).expect(200);
    const sarSafe = all.body.find((b: { kind: string; currency: string }) => b.kind === 'VAULT' && b.currency === 'SAR');
    const yerSafe = all.body.find((b: { kind: string; currency: string }) => b.kind === 'VAULT' && b.currency === 'YER');
    const otherDrawer = all.body.find((b: { kind: string; user?: { id: string } }) => b.kind === 'CASHIER' && b.user?.id !== cashierId);
    if (otherDrawer) await http.get(`/admin/cash-boxes/${otherDrawer.id}/entries`, cashier).expect(403);

    const yerDrawer = await drawer('YER');
    // The cashier hands over to the safe; the manager could take from any drawer.
    await http.post('/admin/cash-transfers', { fromBoxId: yerDrawer.id, toBoxId: yerSafe.id, amount: '999999' }, cashier).expect(422);
    await http.post('/admin/cash-transfers', { fromBoxId: yerDrawer.id, toBoxId: yerSafe.id, amount: '7000' }, cashier).expect(201);
    expect((await drawer('YER')).balance).toBe('14200');
    const sarDrawer = await drawer('SAR');
    await http.post('/admin/cash-transfers', { fromBoxId: sarDrawer.id, toBoxId: sarSafe.id, amount: sarDrawer.balance }, admin).expect(201);
    expect((await drawer('SAR')).balance).toBe('0');
    await assertBooksAgree();
  });

  it('exchanges currencies between boxes and books the realised gain', async () => {
    const all = await http.get('/admin/cash-boxes', admin).expect(200);
    const yerDrawer = await drawer('YER');
    const sarSafe = all.body.find((b: { kind: string; currency: string }) => b.kind === 'VAULT' && b.currency === 'SAR');
    await http.post('/admin/cash-transfers', { fromBoxId: yerDrawer.id, toBoxId: sarSafe.id, amount: '1400' }, admin).expect(400);
    // Sell 1,400 YER (book value ≈ 10 SAR) for 11 SAR.
    const fx = await http.post('/admin/cash-transfers', { fromBoxId: yerDrawer.id, toBoxId: sarSafe.id, amount: '1400', toAmount: '11' }, admin).expect(201);
    expect(Number(fx.body.baseAmount)).toBeCloseTo(10, 0);
    expect(Number(fx.body.fxDifference)).toBeGreaterThan(0);
    const listed = await http.get('/admin/cash-transfers?exchangesOnly=true', admin).expect(200);
    expect(listed.body.items[0].id).toBe(fx.body.id);
    await assertBooksAgree();
  });

  it('books an exchange gain or loss at the market rate, shows the box in its currency, and edits or cancels exchanges', async () => {
    const sarSafe = (await http.get('/admin/cash-boxes?kind=VAULT&currency=SAR', admin).expect(200)).body[0];
    const usdSafe = (await http.post('/admin/cash-boxes', { name: `خزنة دولار ${run}`, kind: 'VAULT', currency: 'USD', icon: '💵' }, admin).expect(201)).body;
    expect(usdSafe.icon).toBe('💵');
    const payer = await customerToken(app, 'عميل نقدي');
    await http.post(`/admin/customers/${payer.customerId}/account/receipts`, { currency: 'SAR', amount: '1000', cashBoxId: sarSafe.id }, admin).expect(201);
    await http.put('/admin/exchange-rates/USD', { rate: '0.25' }, admin).expect(200); // 1 USD = 4 SAR

    // 410 SAR for 100 USD, worth 400 SAR: a 10 SAR loss right away.
    const deal = (await http.post('/admin/cash-transfers', { fromBoxId: sarSafe.id, toBoxId: usdSafe.id, amount: '410', toAmount: '100' }, admin).expect(201)).body;
    expect(Number(deal.fxDifference)).toBeCloseTo(-10, 2);
    const dash = (await http.get('/admin/reports/dashboard', admin).expect(200)).body;
    const row = dash.cashAndBank.find((r: { accountId: string }) => r.accountId === usdSafe.account.id);
    expect(row).toMatchObject({ currency: 'USD', amount: '100', icon: '💵' });
    expect(Number(row.balance)).toBeCloseTo(400, 2);

    // Edit: it was 380 SAR, a 20 SAR gain.
    const fixed = (await http.put(`/admin/cash-transfers/${deal.id}`, { fromBoxId: sarSafe.id, toBoxId: usdSafe.id, amount: '380', toAmount: '100' }, admin).expect(200)).body;
    expect(Number(fixed.fxDifference)).toBeCloseTo(20, 2);
    const old = await prisma.cashTransfer.findUniqueOrThrow({ where: { id: deal.id } });
    expect(old.cancelledAt).not.toBeNull();
    expect(old.replacedById).toBe(fixed.id);
    await http.post(`/admin/cash-transfers/${deal.id}/cancel`, {}, admin).expect(422);

    // Cancel: the dollars go back, nothing is left in the USD safe.
    await http.post(`/admin/cash-transfers/${fixed.id}/cancel`, { reason: 'خطأ' }, admin).expect(200);
    const usdNow = (await http.get('/admin/cash-boxes?kind=VAULT&currency=USD', admin).expect(200)).body.find((b: { id: string }) => b.id === usdSafe.id);
    expect(usdNow.balance).toBe('0');
    expect(Number(usdNow.bookValue)).toBe(0);
    const fxLines = await prisma.journalLine.aggregate({
      where: { account: { systemKey: 'FX_DIFFERENCE' }, journalEntry: { sourceId: { in: [deal.id, fixed.id] } } },
      _sum: { debit: true, credit: true },
    });
    expect(Number(fxLines._sum.debit)).toBeCloseTo(Number(fxLines._sum.credit), 2);
    await assertBooksAgree();
  });

  it('records opening balances of customers in their currency', async () => {
    const c = await customerToken(app, 'عميل قديم');
    await http.post(`/admin/customers/${c.customerId}/account/opening-balance`, { currency: 'YER', amount: '14000', direction: 'OWES' }, cashier).expect(403);
    const owed = (await http.post(`/admin/customers/${c.customerId}/account/opening-balance`, { currency: 'YER', amount: '14000', direction: 'OWES', note: 'دفتر قديم' }, admin).expect(201)).body;
    expect(owed.balances.find((b: { currency: string }) => b.currency === 'YER').balance).toBe('14000');
    const credit = (await http.post(`/admin/customers/${c.customerId}/account/opening-balance`, { currency: 'SAR', amount: '50', direction: 'CREDIT' }, admin).expect(201)).body;
    expect(credit.balances.find((b: { currency: string }) => b.currency === 'SAR')).toMatchObject({ balance: '-50', available: '50' });
    const wallet = (await http.get('/store/me/wallet', c.token).expect(200)).body;
    expect(wallet.entries.map((e: { note: string }) => e.note)).toContain('رصيد افتتاحي');
    const equity = await prisma.journalLine.aggregate({ where: { account: { systemKey: 'OPENING_BALANCE' }, journalEntry: { sourceId: c.customerId } }, _sum: { debit: true, credit: true } });
    expect(Number(equity._sum.credit)).toBeCloseTo(100, 2); // 14,000 YER at 140
    expect(Number(equity._sum.debit)).toBeCloseTo(50, 2);
    await assertBooksAgree();
  });

  it('tops up a wallet from a bank deposit slip, then pays an order from the balance', async () => {
    const shopper = await customerToken(app, 'عميل المحفظة');
    const bank = await http
      .post('/admin/bank-accounts', { bankName: 'بنك الكريمي', accountName: 'ديجافو', accountNumber: `300${run}`, currency: 'YER' }, admin)
      .expect(201);
    const banks = await http.get('/store/bank-accounts').expect(200);
    expect(banks.body.map((b: { id: string }) => b.id)).toContain(bank.body.id);

    const upload = await request(app.getHttpServer())
      .post(`${API}/store/me/files`)
      .set('Authorization', `Bearer ${shopper.token}`)
      .attach('file', PNG, { filename: 'slip.png', contentType: 'image/png' })
      .expect(201);
    // Receipts are private: not on the public URL, but staff can open them.
    await http.get(`/files/${upload.body.id}`).expect(404);
    await http.get(`/admin/files/${upload.body.id}`, admin).expect(200);

    const topUp = await http
      .post('/store/me/wallet/top-ups', { currency: 'YER', amount: '28000', receiptFileId: upload.body.id, bankAccountId: bank.body.id, reference: 'TRX-1' }, shopper.token)
      .expect(201);
    expect(topUp.body.status).toBe('PENDING');
    const pending = await http.get('/admin/wallet/top-ups?status=PENDING', admin).expect(200);
    expect(pending.body.items.map((t: { id: string }) => t.id)).toContain(topUp.body.id);

    const yerBank = (await http.get('/admin/cash-boxes?kind=BANK&currency=YER', admin).expect(200)).body[0];
    const sarBank = (await http.get('/admin/cash-boxes?kind=BANK&currency=SAR', admin).expect(200)).body[0];
    await http.post(`/admin/wallet/top-ups/${topUp.body.id}/approve`, { cashBoxId: sarBank.id }, admin).expect(422);
    const approved = await http.post(`/admin/wallet/top-ups/${topUp.body.id}/approve`, { cashBoxId: yerBank.id }, admin).expect(200);
    expect(approved.body).toMatchObject({ status: 'APPROVED', baseAmount: '200' });
    await http.post(`/admin/wallet/top-ups/${topUp.body.id}/reject`, { reason: 'late' }, admin).expect(409);

    const wallet = await http.get('/store/me/wallet', shopper.token).expect(200);
    expect(wallet.body.balances).toEqual([expect.objectContaining({ currency: 'YER', balance: '28000' })]);

    // Spend it: 1 × 100 SAR + 20 shipping = 16,800 YER.
    await http.put('/admin/shipping-zones', { governorate: 'صنعاء', fee: 20 }, admin).expect(200);
    const addr = await http
      .post('/store/me/addresses', { recipient: 'عميل', phone: '771234567', governorate: 'صنعاء', city: 'صنعاء', street: 'شارع الزبيري' }, shopper.token)
      .expect(201);
    await http.post('/store/cart/items', { variantId: product.variants[1].id, quantity: 1 }, shopper.token).expect(201);
    const order = await http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CUSTOMER_ACCOUNT', currency: 'YER' }, shopper.token).expect(201);
    expect(order.body).toMatchObject({ paymentStatus: 'PAID', status: 'CONFIRMED' });
    const left = await http.get('/store/me/wallet', shopper.token).expect(200);
    expect(left.body.balances[0].balance).toBe('11200');

    // Not enough left for a second one in SAR.
    await http.post('/store/cart/items', { variantId: product.variants[1].id, quantity: 1 }, shopper.token).expect(201);
    await http.post('/store/orders', { addressId: addr.body.id, paymentMethod: 'CUSTOMER_ACCOUNT' }, shopper.token).expect(422);
    await assertBooksAgree();
  });

  it('stores product photos (max 10) with a photo per color, and saves the till order', async () => {
    const up = await request(app.getHttpServer())
      .post(`${API}/admin/files`)
      .set('Authorization', `Bearer ${admin}`)
      .attach('file', PNG, { filename: 'p.png', contentType: 'image/png' })
      .expect(201);
    await http.get(`/files/${up.body.id}`).expect(200).expect('Content-Type', 'image/png');
    const images = [{ url: up.body.url }, { url: up.body.url, color: 'أسود' }];
    await http.put(`/admin/products/${product.id}/images`, { images: Array.from({ length: 11 }, () => images[0]) }, admin).expect(400);
    await http.put(`/admin/products/${product.id}/images`, { images }, admin).expect(200);
    const store = await http.get(`/store/products/sneaker-${run}`).expect(200);
    expect(store.body.images[1]).toMatchObject({ color: 'أسود' });

    await http.put('/admin/pos/product-order', { productIds: [product.id] }, admin).expect(200);
    const grid = await http.get('/admin/pos/products', cashier).expect(200);
    expect(grid.body[0].id).toBe(product.id);
    const shift = await http.get('/admin/pos/shift', cashier).expect(200);
    expect(shift.body.count).toBeGreaterThanOrEqual(2);
  });
});
