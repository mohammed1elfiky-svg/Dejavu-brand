/**
 * Loads a realistic demo data set for exploring the system: catalog, stock,
 * demo staff for every role, customers and orders in different states.
 *
 * Everything goes through the real services, so stock movements and journal
 * entries are produced exactly as they would be in production.
 * Safe to run repeatedly: it does nothing once the demo data exists.
 *
 *   npm run db:seed:demo
 */
import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { AccountingService } from '../accounting/accounting.service';
import { AppModule } from '../app.module';
import { AuthService } from '../auth/auth.service';
import { CatalogService } from '../catalog/catalog.service';
import { ExpensesService } from '../expenses/expenses.service';
import { AdjustmentReason } from '../inventory/dto/inventory.dto';
import { InventoryService } from '../inventory/inventory.service';
import { PrismaService } from '../prisma/prisma.service';
import { PurchasingService } from '../purchasing/purchasing.service';
import { CartService } from '../sales/cart.service';
import { CourierSettlementsService } from '../sales/courier-settlements.service';
import { CustomersService } from '../sales/customers.service';
import { OrdersService } from '../sales/orders.service';
import { ReturnsService } from '../sales/returns.service';
import { UsersService } from '../users/users.service';
import { DEMO_CUSTOMERS, DEMO_PASSWORD, DEMO_STAFF } from './demo-accounts';

const MARKER = 'demo.seededAt';

const img = (seed: string) => `https://picsum.photos/seed/dejavu-${seed}/600/800`;

const CATEGORIES = [
  { slug: 'dresses', nameAr: 'فساتين', nameEn: 'Dresses' },
  { slug: 'tops', nameAr: 'بلوزات وقمصان', nameEn: 'Tops & Shirts' },
  { slug: 'pants', nameAr: 'بناطيل', nameEn: 'Pants' },
  { slug: 'accessories', nameAr: 'إكسسوارات', nameEn: 'Accessories' },
];

interface DemoProduct {
  category: string;
  slug: string;
  nameAr: string;
  nameEn: string;
  descriptionAr: string;
  price: string;
  compareAt?: string;
  cost: number;
  featured?: boolean;
  sizes: string[];
  colors: [string, string][];
  qty: number;
}

const PRODUCTS: DemoProduct[] = [
  {
    category: 'dresses', slug: 'evening-satin-dress', nameAr: 'فستان سهرة ساتان', nameEn: 'Satin Evening Dress',
    descriptionAr: 'فستان سهرة طويل من الساتان الناعم بقصة أنيقة.', price: '1850', compareAt: '2200', cost: 820,
    featured: true, sizes: ['S', 'M', 'L'], colors: [['أسود', '#000000'], ['نبيتي', '#7B1E3A']], qty: 6,
  },
  {
    category: 'dresses', slug: 'floral-summer-dress', nameAr: 'فستان صيفي مشجر', nameEn: 'Floral Summer Dress',
    descriptionAr: 'فستان قطن خفيف بطبعة ورود، مثالي للصيف.', price: '950', cost: 380,
    featured: true, sizes: ['S', 'M', 'L', 'XL'], colors: [['أبيض', '#FFFFFF']], qty: 8,
  },
  {
    category: 'tops', slug: 'linen-shirt', nameAr: 'قميص كتان', nameEn: 'Linen Shirt',
    descriptionAr: 'قميص كتان طبيعي واسع.', price: '650', cost: 240,
    sizes: ['M', 'L'], colors: [['بيج', '#D8C3A5'], ['أزرق فاتح', '#A7C7E7']], qty: 10,
  },
  {
    category: 'tops', slug: 'basic-cotton-tee', nameAr: 'تيشيرت قطن أساسي', nameEn: 'Basic Cotton Tee',
    descriptionAr: 'تيشيرت قطن 100% بقصة مريحة.', price: '320', cost: 110,
    sizes: ['S', 'M', 'L', 'XL'], colors: [['أسود', '#000000'], ['أبيض', '#FFFFFF']], qty: 15,
  },
  {
    category: 'pants', slug: 'wide-leg-trousers', nameAr: 'بنطلون واسع', nameEn: 'Wide-Leg Trousers',
    descriptionAr: 'بنطلون بخصر عالٍ وأرجل واسعة.', price: '780', cost: 300,
    featured: true, sizes: ['S', 'M', 'L'], colors: [['كحلي', '#1F2A44']], qty: 7,
  },
  {
    category: 'pants', slug: 'high-waist-jeans', nameAr: 'جينز بخصر عالٍ', nameEn: 'High-Waist Jeans',
    descriptionAr: 'جينز مرن بقصة مستقيمة.', price: '890', compareAt: '990', cost: 360,
    sizes: ['28', '30', '32'], colors: [['أزرق', '#3B5B92']], qty: 5,
  },
  {
    category: 'accessories', slug: 'leather-handbag', nameAr: 'حقيبة يد جلد', nameEn: 'Leather Handbag',
    descriptionAr: 'حقيبة يد من الجلد الطبيعي بمقبضين.', price: '1450', cost: 640,
    sizes: ['ONE'], colors: [['بني', '#6F4E37'], ['أسود', '#000000']], qty: 4,
  },
  {
    category: 'accessories', slug: 'silk-scarf', nameAr: 'إيشارب حرير', nameEn: 'Silk Scarf',
    descriptionAr: 'إيشارب حرير مطبوع.', price: '420', cost: 150,
    // Deliberately low stock so the low-stock report has something to show.
    sizes: ['ONE'], colors: [['متعدد', '#C97B84']], qty: 2,
  },
];

const ZONES = [
  { governorate: 'القاهرة', fee: '50', estimatedDays: 2 },
  { governorate: 'الجيزة', fee: '50', estimatedDays: 2 },
  { governorate: 'الإسكندرية', fee: '65', estimatedDays: 3 },
  { governorate: 'الدقهلية', fee: '70', estimatedDays: 4 },
  { governorate: 'أسيوط', fee: '85', estimatedDays: 5 },
];

async function main() {
  const app = await NestFactory.createApplicationContext(AppModule, { logger: ['error', 'warn'] });
  const get = <T>(cls: new (...args: never[]) => T) => app.get(cls, { strict: false });
  const prisma = get(PrismaService);

  if (await prisma.setting.findUnique({ where: { key: MARKER } })) {
    console.log('Demo data already loaded — nothing to do.');
    await app.close();
    return;
  }

  const catalog = get(CatalogService);
  const inventory = get(InventoryService);
  const customers = get(CustomersService);
  const cart = get(CartService);
  const orders = get(OrdersService);
  const auth = get(AuthService);
  const users = get(UsersService);
  const accounting = get(AccountingService);
  const purchasing = get(PurchasingService);
  const expenses = get(ExpensesService);
  const returns = get(ReturnsService);
  const settlements = get(CourierSettlementsService);

  const admin = await prisma.user.findFirstOrThrow({ where: { roles: { some: { role: { name: 'admin' } } } } });
  const warehouse = await prisma.warehouse.findFirstOrThrow({ where: { isDefault: true } });

  // ---- staff for each role
  const roles = await prisma.role.findMany();
  for (const s of DEMO_STAFF.filter((x) => x.role !== 'admin')) {
    if (await prisma.user.findUnique({ where: { email: s.email } })) continue;
    await users.createUser(
      { name: s.name, email: s.email, password: DEMO_PASSWORD, roleIds: [roles.find((r) => r.name === s.role)!.id] },
      admin.id,
    );
  }

  // ---- catalog + opening stock
  const categoryId: Record<string, string> = {};
  for (const c of CATEGORIES) {
    categoryId[c.slug] = (await catalog.createCategory({ ...c, imageUrl: img(c.slug) }, admin.id)).id;
  }
  const variantsBySlug: Record<string, { id: string; sku: string }[]> = {};
  for (const p of PRODUCTS) {
    const code = p.slug.split('-').map((w) => w[0]).join('').toUpperCase();
    const variants = p.sizes.flatMap((size) =>
      p.colors.map(([color, colorHex], ci) => ({ sku: `DJV-${code}-${size}-${ci + 1}`, size, color, colorHex })),
    );
    const product = await catalog.createProduct(
      {
        categoryId: categoryId[p.category],
        slug: p.slug,
        nameAr: p.nameAr,
        nameEn: p.nameEn,
        descriptionAr: p.descriptionAr,
        status: 'ACTIVE',
        basePrice: p.price,
        compareAtPrice: p.compareAt,
        isFeatured: p.featured ?? false,
        variants,
        images: [{ url: img(p.slug) }, { url: img(`${p.slug}-2`) }],
      },
      admin.id,
    );
    variantsBySlug[p.slug] = product.variants;
    await inventory.adjust(
      {
        warehouseId: warehouse.id,
        reason: AdjustmentReason.OPENING,
        note: 'Demo opening stock',
        items: product.variants.map((v) => ({ variantId: v.id, quantity: p.qty, unitCost: String(p.cost) })),
      },
      admin.id,
    );
  }

  for (const z of ZONES) await customers.upsertZone(z, admin.id);
  await customers.createCoupon(
    { code: 'WELCOME10', description: 'خصم 10% لأول طلب', type: 'PERCENTAGE', value: '10', maxDiscount: '300', startsAt: '2020-01-01T00:00:00Z' },
    admin.id,
  );

  // ---- owner's capital and a paid rent bill (manual journal entries)
  const acc = Object.fromEntries((await prisma.account.findMany()).map((a) => [a.code, a.id]));
  const today = new Date().toISOString().slice(0, 10);
  for (const je of [
    { description: 'إيداع رأس المال في البنك', lines: [{ accountId: acc['1102'], debit: '200000' }, { accountId: acc['3101'], credit: '200000' }] },
    { description: 'إيجار المحل لهذا الشهر', lines: [{ accountId: acc['5202'], debit: '15000' }, { accountId: acc['1102'], credit: '15000' }] },
  ]) {
    const draft = await accounting.createManualEntry({ entryDate: today, ...je }, admin.id);
    await accounting.postEntry(draft.id, admin.id);
  }

  // ---- customers and orders in different states
  const v = (slug: string, i = 0) => variantsBySlug[slug][i].id;
  const shoppers = [];
  for (const c of DEMO_CUSTOMERS) {
    const session = await auth.customerRegister({ name: c.name, phone: c.phone, email: c.email, password: DEMO_PASSWORD });
    const address = await customers.createAddress(session.customer.id, {
      recipient: c.name, phone: c.phone, governorate: c.governorate, city: c.city, street: c.street,
    });
    shoppers.push({ id: session.customer.id, addressId: address.id });
  }

  const place = async (
    who: { id: string; addressId: string },
    items: [string, number][],
    paymentMethod: 'CASH_ON_DELIVERY' | 'CARD' | 'WALLET',
    coupon?: string,
  ) => {
    for (const [variantId, quantity] of items) await cart.addItem(who.id, { variantId, quantity });
    if (coupon) await cart.setCoupon(who.id, coupon);
    return orders.checkout(who.id, { addressId: who.addressId, paymentMethod });
  };
  const status = (id: string, s: 'CONFIRMED' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED' | 'COMPLETED' | 'CANCELLED', extra = {}) =>
    orders.changeStatus(id, { status: s, ...extra }, admin.id);

  // 1) Delivered cash-on-delivery order with a coupon
  const o1 = await place(shoppers[0], [[v('evening-satin-dress'), 1], [v('silk-scarf'), 1]], 'CASH_ON_DELIVERY', 'WELCOME10');
  await status(o1.id, 'CONFIRMED');
  await status(o1.id, 'SHIPPED', { carrier: 'Bosta', trackingNumber: 'BST-100200' });
  await status(o1.id, 'DELIVERED');

  // 2) Card order paid upfront and shipped (prepayment settled against the invoice)
  const o2 = await place(shoppers[1], [[v('wide-leg-trousers'), 1], [v('linen-shirt', 1), 2]], 'CARD');
  await orders.recordPayment(o2.id, { method: 'CARD', amount: o2.grandTotal.toString(), reference: 'paymob-demo-0001' }, admin.id);
  await status(o2.id, 'SHIPPED', { carrier: 'Aramex', trackingNumber: 'ARX-55501' });

  // 3) Waiting for confirmation (stock is reserved)
  await place(shoppers[2], [[v('basic-cotton-tee'), 3], [v('high-waist-jeans'), 1]], 'CASH_ON_DELIVERY');

  // 4) Being prepared
  const o4 = await place(shoppers[0], [[v('leather-handbag'), 1]], 'WALLET');
  await status(o4.id, 'CONFIRMED');
  await status(o4.id, 'PROCESSING');

  // 5) Paid by card, then cancelled and refunded
  const o5 = await place(shoppers[1], [[v('floral-summer-dress'), 1]], 'CARD');
  await orders.recordPayment(o5.id, { method: 'CARD', amount: o5.grandTotal.toString(), reference: 'paymob-demo-0002' }, admin.id);
  await status(o5.id, 'CANCELLED', { note: 'العميلة غيرت رأيها' });
  await orders.refund(o5.id, { method: 'CARD', amount: o5.grandTotal.toString(), reason: 'إلغاء الطلب' }, admin.id);

  // ---- purchasing: a fully received order (partly paid) and one still arriving
  const supplier = await purchasing.createSupplier(
    { name: 'مصنع النيل للملابس', contactName: 'أ. حسن', phone: '0225550101', taxNumber: '512-334-901' },
    admin.id,
  );
  const po1 = await purchasing.createOrder(
    {
      supplierId: supplier.id,
      supplierInvoiceNo: 'NIL-2026-118',
      shippingCost: '250',
      items: [
        { variantId: v('basic-cotton-tee'), quantity: 20, unitCost: '110' },
        { variantId: v('basic-cotton-tee', 1), quantity: 20, unitCost: '110' },
        { variantId: v('silk-scarf'), quantity: 10, unitCost: '150' },
      ],
    },
    admin.id,
  );
  await purchasing.placeOrder(po1.id, admin.id);
  await purchasing.receive(po1.id, { items: po1.items.map((i) => ({ itemId: i.id, quantity: i.quantity })) }, admin.id);
  await purchasing.paySupplier(
    { supplierId: supplier.id, purchaseOrderId: po1.id, method: 'BANK_TRANSFER', amount: '3000', reference: 'TRX-55120' },
    admin.id,
  );

  const po2 = await purchasing.createOrder(
    { supplierId: supplier.id, items: [{ variantId: v('wide-leg-trousers'), quantity: 12, unitCost: '300' }] },
    admin.id,
  );
  await purchasing.placeOrder(po2.id, admin.id);
  await purchasing.receive(po2.id, { items: [{ itemId: po2.items[0].id, quantity: 5 }] }, admin.id);

  // ---- expenses: paid on the spot, and a bill still to pay
  const cats = await prisma.expenseCategory.findMany();
  const cat = (name: string) => cats.find((c) => c.name === name)!.id;
  const internet = await expenses.create(
    { categoryId: cat('المرافق'), amount: '450', taxAmount: '63', expenseDate: today, description: 'اشتراك الإنترنت', payee: 'WE' },
    admin.id,
  );
  await expenses.approve(internet.id, { paidFromAccountId: acc['1101'] }, admin.id);
  const ads = await expenses.create(
    { categoryId: cat('التسويق'), amount: '3500', expenseDate: today, description: 'إعلانات انستجرام', payee: 'Meta' },
    admin.id,
  );
  await expenses.approve(ads.id, { paidFromAccountId: acc['1102'] }, admin.id);
  const power = await expenses.create(
    { categoryId: cat('المرافق'), amount: '1200', taxAmount: '168', expenseDate: today, description: 'فاتورة الكهرباء', payee: 'شركة الكهرباء' },
    admin.id,
  );
  await expenses.approve(power.id, {}, admin.id); // accrued, waiting to be paid
  await expenses.create(
    { categoryId: cat('التغليف'), amount: '800', expenseDate: today, description: 'أكياس وعلب تغليف', payee: 'مطبعة الأمل' },
    admin.id,
  ); // draft, waiting for approval

  // ---- returns: one received and refunded, one waiting for review
  const o1Items = await prisma.orderItem.findMany({ where: { orderId: o1.id } });
  const scarf = o1Items.find((i) => i.sku.startsWith('DJV-SS'))!;
  const dress = o1Items.find((i) => i.sku.startsWith('DJV-ESD'))!;
  const r1 = await returns.staffCreate(o1.id, { reason: 'لون مختلف عن الصورة', items: [{ orderItemId: scarf.id, quantity: 1 }] }, admin.id);
  await returns.receive(r1.id, {}, admin.id);
  await returns.refund(r1.id, { method: 'CASH' }, admin.id);
  await returns.customerRequest(shoppers[0].id, o1.number, {
    reason: 'المقاس صغير',
    items: [{ orderItemId: dress.id, quantity: 1 }],
  });

  // ---- the courier pays over the COD cash of order 1, minus its fee
  const pending = await settlements.pending({ carrier: 'Bosta' });
  await settlements.settle(
    { carrier: 'Bosta', shipmentIds: pending.shipments.map((sh) => sh.id), feesAmount: '35', accountId: acc['1102'], reference: 'BOSTA-PAYOUT-0042' },
    admin.id,
  );

  // ---- a stock count that found one tee missing
  const count = await inventory.createCount(
    { warehouseId: warehouse.id, note: 'جرد رف التيشيرتات', items: [{ variantId: v('basic-cotton-tee'), countedQty: 34 }] },
    admin.id,
  );
  await inventory.postCount(count.id, admin.id);

  await prisma.setting.create({ data: { key: MARKER, value: new Date().toISOString() } });
  console.log('Demo data loaded: 8 products, 3 customers, 5 orders, purchases, expenses, returns, staff for every role.');
  await app.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
