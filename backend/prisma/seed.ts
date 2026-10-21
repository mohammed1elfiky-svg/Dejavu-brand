import "dotenv/config";
import { PrismaPg } from "@prisma/adapter-pg";
import { AccountType, NormalBalance, PrismaClient } from "../src/generated/prisma/client";
import { hashPasswordSync as hashPassword } from "../src/auth/password";

const prisma = new PrismaClient({
  adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL! }),
});

// -----------------------------------------------------------------------------
// Chart of accounts. `key` marks system accounts used by automatic posting rules.
// -----------------------------------------------------------------------------
type AccountSeed = {
  code: string;
  ar: string;
  en: string;
  parent?: string;
  group?: boolean;
  cash?: boolean;
  key?: string;
};

const ACCOUNTS: AccountSeed[] = [
  // 1 — Assets
  { code: "1", ar: "الأصول", en: "Assets", group: true },
  { code: "11", ar: "الأصول المتداولة", en: "Current Assets", parent: "1", group: true },
  { code: "1101", ar: "الخزينة الرئيسية", en: "Main Cash Box", parent: "11", cash: true, key: "CASH" },
  { code: "1102", ar: "البنك", en: "Bank Account", parent: "11", cash: true, key: "BANK" },
  { code: "1103", ar: "المحافظ الإلكترونية", en: "Mobile Wallets", parent: "11", cash: true, key: "WALLET" },
  { code: "1104", ar: "مستحقات بوابات الدفع", en: "Payment Gateway Clearing", parent: "11", cash: true, key: "GATEWAY_CLEARING" },
  { code: "1105", ar: "مستحقات شركات الشحن (تحصيل عند الاستلام)", en: "COD Receivable from Couriers", parent: "11", key: "COD_RECEIVABLE" },
  { code: "1110", ar: "العملاء (ذمم مدينة)", en: "Accounts Receivable", parent: "11", key: "AR" },
  { code: "1120", ar: "المخزون", en: "Inventory", parent: "11", key: "INVENTORY" },
  { code: "1130", ar: "ضريبة القيمة المضافة - مدخلات", en: "VAT Input", parent: "11", key: "VAT_INPUT" },
  { code: "12", ar: "الأصول الثابتة", en: "Fixed Assets", parent: "1", group: true },
  { code: "1201", ar: "الأثاث والتجهيزات", en: "Furniture & Fixtures", parent: "12" },
  { code: "1202", ar: "أجهزة ومعدات", en: "Equipment", parent: "12" },
  { code: "1209", ar: "مجمع الإهلاك", en: "Accumulated Depreciation", parent: "12" },

  // 2 — Liabilities
  { code: "2", ar: "الخصوم", en: "Liabilities", group: true },
  { code: "21", ar: "الخصوم المتداولة", en: "Current Liabilities", parent: "2", group: true },
  { code: "2101", ar: "الموردون (ذمم دائنة)", en: "Accounts Payable", parent: "21", key: "AP" },
  { code: "2102", ar: "ضريبة القيمة المضافة - مخرجات", en: "VAT Output", parent: "21", key: "VAT_OUTPUT" },
  { code: "2103", ar: "دفعات مقدمة من العملاء", en: "Customer Advances", parent: "21", key: "CUSTOMER_ADVANCES" },
  { code: "2104", ar: "مصروفات مستحقة", en: "Accrued Expenses", parent: "21", key: "ACCRUED_EXPENSES" },

  // 3 — Equity
  { code: "3", ar: "حقوق الملكية", en: "Equity", group: true },
  { code: "3101", ar: "رأس المال", en: "Capital", parent: "3", key: "CAPITAL" },
  { code: "3102", ar: "الأرباح المحتجزة", en: "Retained Earnings", parent: "3", key: "RETAINED_EARNINGS" },
  { code: "3103", ar: "جاري المالك", en: "Owner Drawings", parent: "3", key: "DRAWINGS" },
  { code: "3104", ar: "أرصدة افتتاحية", en: "Opening Balance Equity", parent: "3", key: "OPENING_BALANCE" },

  // 4 — Revenue
  { code: "4", ar: "الإيرادات", en: "Revenue", group: true },
  { code: "4101", ar: "إيرادات المبيعات", en: "Sales Revenue", parent: "4", key: "SALES" },
  { code: "4102", ar: "مردودات المبيعات", en: "Sales Returns", parent: "4", key: "SALES_RETURNS" },
  { code: "4103", ar: "خصومات المبيعات", en: "Sales Discounts", parent: "4", key: "SALES_DISCOUNTS" },
  { code: "4104", ar: "إيرادات الشحن", en: "Shipping Income", parent: "4", key: "SHIPPING_INCOME" },
  { code: "4199", ar: "إيرادات أخرى", en: "Other Income", parent: "4" },

  // 5 — Expenses
  { code: "5", ar: "المصروفات", en: "Expenses", group: true },
  { code: "51", ar: "تكلفة المبيعات", en: "Cost of Sales", parent: "5", group: true },
  { code: "5101", ar: "تكلفة البضاعة المباعة", en: "Cost of Goods Sold", parent: "51", key: "COGS" },
  { code: "5102", ar: "تسويات المخزون (عجز/تالف)", en: "Inventory Adjustments", parent: "51", key: "INVENTORY_ADJUSTMENT" },
  { code: "52", ar: "مصروفات تشغيلية", en: "Operating Expenses", parent: "5", group: true },
  { code: "5201", ar: "الرواتب والأجور", en: "Salaries & Wages", parent: "52" },
  { code: "5202", ar: "الإيجار", en: "Rent", parent: "52" },
  { code: "5203", ar: "الكهرباء والمياه والإنترنت", en: "Utilities", parent: "52" },
  { code: "5204", ar: "الإعلانات والتسويق", en: "Advertising & Marketing", parent: "52" },
  { code: "5205", ar: "مصروفات الشحن", en: "Shipping Expense", parent: "52", key: "SHIPPING_EXPENSE" },
  { code: "5206", ar: "عمولات بوابات الدفع", en: "Payment Gateway Fees", parent: "52", key: "GATEWAY_FEES" },
  { code: "5207", ar: "التغليف", en: "Packaging", parent: "52" },
  { code: "5208", ar: "مصروفات الإهلاك", en: "Depreciation Expense", parent: "52" },
  { code: "5299", ar: "مصروفات متنوعة", en: "Miscellaneous", parent: "52" },
];

const TYPE_BY_ROOT: Record<string, [AccountType, NormalBalance]> = {
  "1": [AccountType.ASSET, NormalBalance.DEBIT],
  "2": [AccountType.LIABILITY, NormalBalance.CREDIT],
  "3": [AccountType.EQUITY, NormalBalance.CREDIT],
  "4": [AccountType.REVENUE, NormalBalance.CREDIT],
  "5": [AccountType.EXPENSE, NormalBalance.DEBIT],
};

// Contra accounts carry the opposite normal balance of their section.
const CONTRA = new Set(["1209", "3103", "4102", "4103"]);

const PERMISSIONS: Record<string, string[]> = {
  dashboard: ["dashboard.view"],
  catalog: ["products.read", "products.write", "categories.write"],
  inventory: ["stock.read", "stock.adjust", "stock.transfer", "stock.count"],
  sales: ["orders.read", "orders.update_status", "orders.cancel", "returns.manage", "coupons.manage"],
  customers: ["customers.read", "customers.write"],
  purchasing: ["suppliers.manage", "purchases.read", "purchases.write", "purchases.receive"],
  payments: ["payments.read", "payments.record", "supplier_payments.record"],
  expenses: ["expenses.read", "expenses.write", "expenses.approve"],
  accounting: ["accounts.manage", "journal.read", "journal.write", "journal.post", "journal.reverse", "periods.close"],
  reports: ["reports.sales", "reports.inventory", "reports.financial"],
  system: ["users.manage", "roles.manage", "settings.manage", "audit.read", "notifications.send"],
};

const ROLES: Record<string, { description: string; permissions: string[] | "*" }> = {
  admin: { description: "Full access", permissions: "*" },
  accountant: {
    description: "Accounting, expenses, payments and financial reports",
    permissions: [
      "dashboard.view", "orders.read", "customers.read", "purchases.read", "suppliers.manage",
      "payments.read", "payments.record", "supplier_payments.record",
      "expenses.read", "expenses.write", "expenses.approve",
      "accounts.manage", "journal.read", "journal.write", "journal.post", "journal.reverse", "periods.close",
      "reports.sales", "reports.financial", "reports.inventory", "stock.read",
    ],
  },
  storekeeper: {
    description: "Stock, receiving and stock counts",
    permissions: ["products.read", "stock.read", "stock.adjust", "stock.transfer", "stock.count", "purchases.read", "purchases.receive", "reports.inventory"],
  },
  sales: {
    description: "Order handling and customer service",
    permissions: ["dashboard.view", "products.read", "stock.read", "orders.read", "orders.update_status", "orders.cancel", "returns.manage", "customers.read", "customers.write", "payments.read", "reports.sales"],
  },
};

const ymd = (y: number, m: number, d: number) => new Date(Date.UTC(y, m, d));

async function seedAccounts() {
  const ids = new Map<string, string>();
  for (const a of ACCOUNTS) {
    let [type, normal] = TYPE_BY_ROOT[a.code[0]];
    if (CONTRA.has(a.code)) normal = normal === NormalBalance.DEBIT ? NormalBalance.CREDIT : NormalBalance.DEBIT;
    const parentId = a.parent ? ids.get(a.parent) : undefined;
    const data = {
      nameAr: a.ar,
      nameEn: a.en,
      type,
      normalBalance: normal,
      parentId,
      level: a.parent ? ACCOUNTS.filter((x) => a.code.startsWith(x.code) && x.code !== a.code).length + 1 : 1,
      isPostable: !a.group,
      isCashOrBank: !!a.cash,
      systemKey: a.key,
    };
    const row = await prisma.account.upsert({ where: { code: a.code }, update: data, create: { code: a.code, ...data } });
    ids.set(a.code, row.id);
  }
  return ids;
}

async function seedAccess() {
  const allKeys: string[] = [];
  for (const [module, keys] of Object.entries(PERMISSIONS)) {
    for (const key of keys) {
      await prisma.permission.upsert({ where: { key }, update: { module }, create: { key, module } });
      allKeys.push(key);
    }
  }
  const perms = await prisma.permission.findMany();
  const permId = new Map(perms.map((p) => [p.key, p.id]));

  for (const [name, def] of Object.entries(ROLES)) {
    const role = await prisma.role.upsert({
      where: { name },
      update: { description: def.description },
      create: { name, description: def.description, isSystem: true },
    });
    const keys = def.permissions === "*" ? allKeys : def.permissions;
    await prisma.rolePermission.createMany({
      data: keys.map((k) => ({ roleId: role.id, permissionId: permId.get(k)! })),
      skipDuplicates: true,
    });
  }

  const email = process.env.SEED_ADMIN_EMAIL ?? "admin@dejavu.local";
  const password = process.env.SEED_ADMIN_PASSWORD ?? "ChangeMe!2026";
  const admin = await prisma.user.upsert({
    where: { email },
    update: {},
    create: { name: "System Admin", email, passwordHash: hashPassword(password) },
  });
  const adminRole = await prisma.role.findUniqueOrThrow({ where: { name: "admin" } });
  await prisma.userRole.upsert({
    where: { userId_roleId: { userId: admin.id, roleId: adminRole.id } },
    update: {},
    create: { userId: admin.id, roleId: adminRole.id },
  });
}

async function seedFiscalYear(year: number) {
  const fy = await prisma.fiscalYear.upsert({
    where: { name: `FY${year}` },
    update: {},
    create: { name: `FY${year}`, startDate: ymd(year, 0, 1), endDate: ymd(year, 11, 31) },
  });
  for (let m = 0; m < 12; m++) {
    const name = `${year}-${String(m + 1).padStart(2, "0")}`;
    await prisma.fiscalPeriod.upsert({
      where: { fiscalYearId_name: { fiscalYearId: fy.id, name } },
      update: {},
      create: { fiscalYearId: fy.id, name, startDate: ymd(year, m, 1), endDate: ymd(year, m + 1, 0) },
    });
  }
}

async function main() {
  const acc = await seedAccounts();
  await seedAccess();
  await seedFiscalYear(new Date().getUTCFullYear());

  await prisma.warehouse.upsert({
    where: { code: "MAIN" },
    update: {},
    create: { code: "MAIN", name: "المخزن الرئيسي", isDefault: true },
  });

  if (!(await prisma.taxRate.findFirst({ where: { name: "VAT 14%" } }))) {
    await prisma.taxRate.create({ data: { name: "VAT 14%", rate: 14, isDefault: true, accountId: acc.get("2102")! } });
  }

  const expenseCategories: [string, string][] = [
    ["الرواتب", "5201"], ["الإيجار", "5202"], ["المرافق", "5203"], ["التسويق", "5204"],
    ["الشحن", "5205"], ["التغليف", "5207"], ["متنوعة", "5299"],
  ];
  for (const [name, code] of expenseCategories) {
    await prisma.expenseCategory.upsert({ where: { name }, update: {}, create: { name, accountId: acc.get(code)! } });
  }

  const settings: Record<string, unknown> = {
    "store.name": "Dejavu",
    "store.currency": "EGP",
    "store.locale": "ar-EG",
    "store.timezone": "Africa/Cairo",
    "orders.autoConfirmPaid": true,
    "inventory.costingMethod": "WEIGHTED_AVERAGE",
    "tax.pricesIncludeTax": true,
  };
  for (const [key, value] of Object.entries(settings)) {
    await prisma.setting.upsert({ where: { key }, update: {}, create: { key, value: value as never } });
  }

  console.log(`Seeded ${ACCOUNTS.length} accounts, ${Object.keys(ROLES).length} roles, fiscal year and defaults.`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
