# تجربة النظام بشكل حي

## الطريقة 1 — من المتصفح مباشرة (GitHub Codespaces) ⭐ الأسهل

لا تحتاج تثبيت أي شيء على جهازك.

1. افتح المستودع على GitHub واختر الفرع `claude/accounting-system-setup-u5okpy`.
2. اضغط الزر الأخضر **Code** ← تبويب **Codespaces** ← **Create codespace on claude/accounting-system-setup-u5okpy**.
3. انتظر 3–5 دقائق في أول مرة (يتم تلقائياً: تثبيت المكتبات، إنشاء قاعدة البيانات، تحميل البيانات التجريبية).
4. سيبدأ الخادم وحده في نافذة الطرفية، وتفتح صفحة **Swagger** في تبويب جديد.
   - إذا لم تفتح: تبويب **PORTS** بالأسفل ← المنفذ `3000` ← أيقونة الكرة الأرضية 🌐.

> Codespaces مجاني حتى 60 ساعة شهرياً للحسابات الشخصية. أوقفه من github.com/codespaces عند الانتهاء.

## الطريقة 2 — على جهازك بأمر واحد (Docker)

يتطلب [Docker Desktop](https://www.docker.com/products/docker-desktop/) فقط:

```bash
git clone https://github.com/mohammed1elfiky-svg/Dejavu-brand.git
cd Dejavu-brand
git checkout claude/accounting-system-setup-u5okpy
docker compose up --build
```

ثم افتح: **http://localhost:3000** (ستتحول تلقائياً إلى صفحة Swagger).
لإعادة البيانات من الصفر: `docker compose down -v` ثم `docker compose up`.

## الطريقة 3 — للمطورين (Node.js 22)

```bash
docker compose up -d postgres
cd backend && cp .env.example .env && npm install
npm run db:deploy && npm run db:seed && npm run db:seed:demo
npm run start:dev
```

---

## الحسابات التجريبية

كلمة المرور لكل الحسابات التجريبية: **`Demo!2026`** (عدا المدير).

| الدور | البريد | كلمة المرور | ماذا يستطيع |
|---|---|---|---|
| مدير | `admin@dejavu.local` | `ChangeMe!2026` | كل شيء |
| محاسب | `accountant@dejavu.local` | `Demo!2026` | القيود، الحسابات، المدفوعات، التقارير المالية |
| أمين مخزن | `store@dejavu.local` | `Demo!2026` | المخزون والتسويات والتحويلات |
| مبيعات | `sales@dejavu.local` | `Demo!2026` | الطلبات والعملاء |

| العميل | الموبايل | كلمة المرور |
|---|---|---|
| منى أحمد | `01000000001` | `Demo!2026` |
| سارة محمود | `01000000002` | `Demo!2026` |
| ياسمين علي | `01000000003` | `Demo!2026` |

## ماذا ستجد في البيانات التجريبية

- 8 منتجات في 4 تصنيفات (فساتين، بلوزات، بناطيل، إكسسوارات) بمقاسات وألوان، ومخزون افتتاحي.
- كوبون `WELCOME10` (خصم 10% بحد أقصى 300 جنيه) ومناطق شحن لـ 5 محافظات.
- قيد إيداع رأس مال 200,000 وقيد إيجار 15,000.
- 5 طلبات في حالات مختلفة:

| الطلب | الحالة | ما توضّحه |
|---|---|---|
| SO-…-000001 | تم التسليم، دفع عند الاستلام، بكوبون | قيد المبيعات + التكلفة + تحويل المستحق لشركة الشحن |
| SO-…-000002 | تم الشحن، مدفوع بالبطاقة مسبقاً | الدفعة المقدمة ثم تسويتها مع الفاتورة |
| SO-…-000003 | بانتظار التأكيد | المخزون محجوز ولا يوجد قيد بعد |
| SO-…-000004 | قيد التجهيز | — |
| SO-…-000005 | ملغي ومسترد | قيد الدفع ثم قيد الاسترداد |

## جولة مقترحة في Swagger (10 دقائق)

**1. الدخول**
- افتح **Auth ← POST /auth/staff/login** ← **Try it out**، واكتب:
  `{ "email": "admin@dejavu.local", "password": "ChangeMe!2026" }` ← **Execute**.
- انسخ قيمة `accessToken` من الرد، واضغط زر **Authorize** 🔓 أعلى الصفحة والصقها. سيبقى الدخول محفوظاً حتى لو حدّثت الصفحة.

**2. شاهد الطلبات وقيودها**
- `GET /admin/orders` ← انسخ `id` أول طلب.
- `GET /admin/orders/{id}` ← انزل إلى `journalEntries`: سترى القيود التي ولّدها الطلب تلقائياً، و`profit` (الإيراد والتكلفة ومجمل الربح).

**3. التقارير المالية**
- `GET /admin/reports/trial-balance` ← ميزان المراجعة، و`totals.balanced` يجب أن تكون `true`.
- `GET /admin/accounts` ← انسخ `id` حساب المخزون (1120) ← `GET /admin/accounts/{id}/ledger` لكشف الحساب.

**4. نفّذ عملية بنفسك**
- `POST /admin/orders/{id}/status` على الطلب 000003 بـ `{ "status": "CONFIRMED" }` ثم `{ "status": "SHIPPED" }`.
- ارجع لميزان المراجعة: ستزيد المبيعات وتكلفة البضاعة وينقص المخزون، ويبقى الميزان متوازناً.

**5. جرّب كعميل**
- `POST /auth/customer/login` بـ `{ "phone": "01000000003", "password": "Demo!2026" }` ← ضع التوكن في **Authorize** بدل توكن المدير.
- `GET /store/products` ← اختر `id` أحد المقاسات من `variants`.
- `POST /store/cart/items` ← `PUT /store/cart/coupon` بـ `WELCOME10` ← `GET /store/me/addresses` لأخذ `id` العنوان.
- `GET /store/cart?addressId=...` ← ستظهر الأسعار والضريبة والشحن والخصم.
- `POST /store/orders` بـ `{ "addressId": "...", "paymentMethod": "CASH_ON_DELIVERY" }`.

**6. جرّب الصلاحيات**
- ادخل بحساب `store@dejavu.local` وحاول `GET /admin/orders`: ستحصل على 403 مع اسم الصلاحية الناقصة.

> **نصيحة:** يمكنك أيضاً استيراد الـ API كاملاً في **Postman** أو **Insomnia** من الرابط `/docs-json`.

---

## وماذا عن تطبيقات أندرويد؟

التطبيقان لم يُبنيا بعد (المرحلتان 3 و4). عند بنائهما:
- ستبني **GitHub Actions** ملفات APK تلقائياً مع كل تحديث، وتحمّلها من صفحة الـ Actions أو Releases وتثبّتها على موبايلك مباشرة.
- سيتصل التطبيق بنفس الخادم الذي شغّلته بإحدى الطرق السابقة:
  - على المحاكي (Emulator): العنوان `http://10.0.2.2:3000`.
  - على موبايل حقيقي مع Codespaces: اجعل المنفذ 3000 **Public** من تبويب PORTS، واستخدم الرابط الذي يظهر.
