# Dejavu Backend API

NestJS 11 + Prisma 7 + PostgreSQL 16. Serves both Android apps under `/api/v1`.
Interactive API docs (Swagger) are at **`/docs`** when the server is running.

## التشغيل

```bash
docker compose up -d            # من جذر المستودع: PostgreSQL + Redis
cp .env.example .env            # ثم غيّر JWT_ACCESS_SECRET
npm install                     # يولّد Prisma Client تلقائياً
npm run db:deploy               # الجداول + قواعد السلامة المحاسبية
npm run db:seed                 # دليل الحسابات، الأدوار، السنة المالية...
npm run start:dev               # http://localhost:3000/api/v1  —  docs: /docs
```

## الاختبارات

```bash
npm test             # اختبارات الوحدة (التسعير والضريبة والخصومات)
npm run test:e2e     # اختبارات شاملة على قاعدة بيانات حقيقية <db>_test (تُعاد بناؤها في كل تشغيل)
```

اختبارات e2e تغطي الدورة كاملة وتتحقق من كل قيد محاسبي: الدخول وتدوير التوكن، الصلاحيات،
المنتجات، المخزون الافتتاحي ومتوسط التكلفة، السلة والكوبونات، الطلب والحجز، الشحن (قيد المبيعات والتكلفة)،
التسليم والتحصيل، الدفع المسبق والاسترداد، الإلغاء، منع البيع الزائد عند الطلبات المتزامنة،
القيود اليدوية والعكس، الفترات المقفلة، وتوازن ميزان المراجعة مع قيمة المخزون الفعلية.

## هيكل الكود

```
src/
  auth/         الدخول (موظفين/عملاء)، JWT، Refresh Token مع التدوير وكشف إعادة الاستخدام
  users/        إدارة الموظفين والأدوار والصلاحيات
  catalog/      التصنيفات والمنتجات والمقاسات/الألوان + كتالوج المتجر العام
  inventory/    StockService (حجز/صرف/إضافة بمتوسط التكلفة) + التسويات والتحويلات
  sales/        السلة، التسعير، الطلبات ودورة حالاتها، المدفوعات والاسترداد، المرتجعات، رفض الاستلام،
                تسويات شركات الشحن، الكوبونات، مناطق الشحن
  purchasing/   الموردون، أوامر الشراء والاستلام الجزئي بتكلفة الوصول، سداد الموردين، كشف حساب المورد
  expenses/     تصنيفات المصروفات، دورة المصروف (مسودة ← اعتماد ← سداد / إلغاء)
  inventory/    + الجرد الفعلي (Stock Counts)
  accounting/   LedgerService (المدخل الوحيد لدفتر الأستاذ)، القيود اليدوية، ميزان المراجعة، كشف الحساب
  reports/      قائمة الدخل، الميزانية، تقرير المبيعات، تقييم المخزون، لوحة المؤشرات، الفترات المالية
  common/       الحراس (Guards)، المرشحات، الترقيم التسلسلي، سجل التدقيق
```

## الأمان والصلاحيات

| الآلية | التفاصيل |
|---|---|
| Access Token | JWT صالح 15 دقيقة، يحمل نوع الحساب (`staff`/`customer`) وصلاحيات الموظف |
| Refresh Token | عشوائي، يُخزَّن كـ SHA-256 فقط، يُدوَّر مع كل استخدام. إعادة استخدام توكن قديم ⇒ إلغاء كل جلسات الحساب |
| الصلاحيات | `@RequirePermissions('orders.read')` على كل endpoint إداري؛ العملاء لا يصلون لمسارات `/admin` والعكس |
| تغيير الأدوار/الإيقاف/كلمة المرور | يُلغي جلسات المستخدم فوراً |
| حماية من القفل | لا يمكن إيقاف أو تخفيض آخر مدير نشط |
| كلمات المرور | scrypt مع salt، ومقارنة بزمن ثابت |
| Rate limiting | `AUTH_RATE_LIMIT` محاولة دخول/دقيقة لكل IP (اضبط `TRUST_PROXY` خلف nginx) |
| المدخلات | تحقق صارم: أي حقل غير معروف يُرفض (400) |
| سجل التدقيق | كل العمليات الحساسة تُسجَّل في `audit_logs` |

## القيود المحاسبية التلقائية

كل عملية (شحن، تحصيل، استرداد، مرتجع، رفض استلام، استلام من مورد، سداد مورد، مصروف، جرد، تسوية شركة شحن)
تولّد قيدها تلقائياً. الجدول الكامل في [docs/DATABASE_SCHEMA.md](../docs/DATABASE_SCHEMA.md#قواعد-الترحيل-التلقائي-posting-rules).

كل قيد يمر عبر `LedgerService.post()` داخل نفس الـ Transaction الخاص بالعملية، ثم تتحقق Triggers قاعدة البيانات
من التوازن والفترة المفتوحة والحساب القابل للترحيل. إذا فشل أي جزء، تُلغى العملية بالكامل (المستند + المخزون + القيد).

## منع البيع الزائد (Overselling)

الحجز يتم بأمر `UPDATE ... WHERE on_hand - reserved >= qty` واحد (ذرّي)، وقيد `CHECK` في قاعدة البيانات يمنع
أن يتجاوز المحجوز الموجود. السلة نفسها تُقفل أثناء إتمام الطلب لمنع إنشاء طلبين من ضغطة مزدوجة.
المتجر الإلكتروني يبيع من **المخزن الافتراضي** فقط.

## أهم الـ Endpoints

| المجال | المسار |
|---|---|
| مشترك | `POST /auth/staff/login`، `POST /auth/customer/register`، `POST /auth/customer/login`، `POST /auth/refresh`، `POST /auth/logout`، `GET /auth/me` |
| المتجر (العملاء) | `GET /store/categories`، `GET /store/products[/:slug]`، `/store/me/addresses`، `/store/cart` (+ `/items`، `/coupon`)، `POST /store/orders`، `GET /store/orders[/:number]`، `POST /store/orders/:number/cancel`، `POST /store/orders/:number/returns`، `GET /store/returns` |
| الطلبات | `/admin/orders` + `/:id/status` (تأكيد/شحن/تسليم/رفض استلام/إلغاء) + `/:id/payments` + `/:id/refunds` + `/:id/returns` |
| المرتجعات | `/admin/returns` + `/:id/approve` · `/reject` · `/receive` · `/refund` |
| شركات الشحن | `GET /admin/courier-settlements/pending`، `POST /admin/courier-settlements` |
| المشتريات | `/admin/suppliers` (+ `/:id/statement`)، `/admin/purchase-orders` + `/:id/place` · `/receive` · `/close` · `/cancel`، `POST /admin/supplier-payments` |
| المصروفات | `/admin/expense-categories`، `/admin/expenses` + `/:id/approve` · `/pay` · `/cancel` |
| المخزون | `/admin/inventory/{levels,movements,adjustments,transfers,low-stock}`، `/admin/stock-counts` + `/:id/post` |
| المحاسبة | `/admin/accounts` (+ `/:id/ledger`)، `/admin/journal-entries` + `/post` · `/reverse`، `/admin/customers/:id/statement`، `/admin/fiscal-years`، `/admin/fiscal-periods/:id/{close,reopen,lock}` |
| التقارير | `/admin/reports/dashboard`، `/income-statement`، `/balance-sheet`، `/trial-balance`، `/sales?groupBy=day\|product\|category`، `/inventory-valuation` |
| الإدارة | `/admin/users`، `/admin/roles`، `/admin/products`، `/admin/customers`، `/admin/coupons`، `/admin/shipping-zones`، `/admin/warehouses` |

القائمة الكاملة مع الأمثلة في Swagger على `/docs`.
