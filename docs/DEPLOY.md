# النشر والتشغيل الفعلي

هذا الدليل ينقل Dejavu من التجربة إلى التشغيل الحقيقي. له ثلاثة أجزاء:
1. تشغيل الخادم على الإنترنت.
2. تفعيل الدفع بالبطاقة (Paymob) والإشعارات (Firebase).
3. إصدار نسخ أندرويد موقّعة.

> كل خطوة اختيارية ومستقلة. مثلاً يمكنك تشغيل الخادم بدون Paymob، فيبقى الدفع عند الاستلام والتحويل متاحاً.

---

## 1) تشغيل الخادم

كل رفع للكود يبني صورة Docker جاهزة وينشرها على GitHub Container Registry:
`ghcr.io/mohammed1elfiky-svg/dejavu-api`.
يتم النشر بعد نجاح الاختبارات، وتحمل الصورة الوسوم: اسم الفرع، و`sha-xxxxxxx`، و`latest` للفرع الرئيسي.

### الخيار أ: Render (الأسهل، بضغطة واحدة)

1. أنشئ حساباً على [render.com](https://render.com) واربطه بـ GitHub.
2. **New ← Blueprint** ← اختر هذا المستودع. سيقرأ Render ملف `render.yaml` وينشئ الخادم وقاعدة PostgreSQL.
3. سيطلب منك قيمتين:
   - `SEED_ADMIN_EMAIL`: بريد حساب المدير الأول.
   - `SEED_ADMIN_PASSWORD`: كلمة مرور قوية له.

   أما المفتاح السري `JWT_ACCESS_SECRET` فيُولَّد تلقائياً.
4. بعد اكتمال النشر سيظهر رابط مثل `https://dejavu-api.onrender.com`. رابط التطبيقات هو: `https://dejavu-api.onrender.com/api/v1/`.

| ملاحظة | التفاصيل |
|---|---|
| الخطة المجانية | الخادم ينام بعد 15 دقيقة بلا استخدام (أول طلب بعدها يأخذ نحو دقيقة)، وقاعدة البيانات المجانية تنتهي بعد 30 يوماً. للتشغيل الفعلي اختر خطة مدفوعة من لوحة Render. |
| بيانات تجريبية | لجعله خادم عرض ضع `DEMO_DATA=true`. **لا تفعل ذلك في متجر حقيقي** لأن الحسابات التجريبية كلمات مرورها معروفة. |
| الرابط العام | يُضبط تلقائياً من `RENDER_EXTERNAL_URL` ويُستخدم في روابط الدفع. |

### الخيار ب: خادم خاص (VPS) مع HTTPS تلقائي

يعمل على أي خادم Linux عليه Docker، مثل Hetzner أو DigitalOcean أو Contabo. الملفات موجودة في مجلد `deploy/`.

```bash
# على الخادم
git clone https://github.com/mohammed1elfiky-svg/Dejavu-brand.git /opt/dejavu
cd /opt/dejavu/deploy
cp .env.prod.example .env.prod
nano .env.prod          # DOMAIN وكلمات المرور والمفاتيح

# إن كانت الحزمة خاصة: سجّل الدخول بـ Personal Access Token صلاحيته read:packages
echo <TOKEN> | docker login ghcr.io -u <github-user> --password-stdin

docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

- وجّه سجل DNS من نوع **A** لنطاقك (مثلاً `api.your-store.com`) إلى عنوان IP للخادم **قبل التشغيل**، حتى يحصل Caddy على شهادة HTTPS تلقائياً.
- **التحديث لنسخة أحدث:**
  ```bash
  docker compose -f docker-compose.prod.yml --env-file .env.prod pull && docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
  ```
  الترحيلات (migrations) تُطبّق تلقائياً عند كل تشغيل.
- **التثبيت على نسخة محددة:** ضع `API_IMAGE=ghcr.io/mohammed1elfiky-svg/dejavu-api:sha-xxxxxxx` في `.env.prod`.
- **النسخ الاحتياطي اليومي:** الأمر `./backup.sh` يحفظ نسخة في `deploy/backups/` ويحذف ما هو أقدم من 14 يوماً. أضفه إلى cron:
  ```
  0 3 * * * cd /opt/dejavu/deploy && ./backup.sh
  ```
- **الاسترجاع:**
  ```bash
  docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres pg_restore -U dejavu -d dejavu --clean < backups/<file>.dump
  ```

### بعد التشغيل

1. ادخل لتطبيق الإدارة بحساب المدير، ثم:
   - أضف الأقسام والمنتجات.
   - سجّل رصيد المخزون الافتتاحي.
   - حدد مناطق الشحن ورسومها.
2. أضف الموظفين من `POST /admin/users` (من صفحة `/docs` إن كانت مفعّلة) مع أدوارهم: محاسب، أمين مخزن، مبيعات.
3. في الإنتاج يُفضّل إيقاف صفحة Swagger بـ `SWAGGER_ENABLED=false` (موجودة في `.env.prod.example`).

---

## 2) الدفع بالبطاقة (Paymob)

### كيف يعمل
- يختار العميل **بطاقة** عند إتمام الطلب، فتُفتح صفحة دفع Paymob الآمنة. التطبيق لا يرى بيانات البطاقة أبداً.
- ترسل Paymob النتيجة إلى الخادم موقّعة بـ HMAC-SHA512. عندها فقط:
  - يُسجَّل الدفع.
  - يُنشأ القيد المحاسبي (من ح/ مستحقات بوابات الدفع 1104، إلى ح/ دفعات مقدمة من العملاء).
  - يُؤكَّد الطلب تلقائياً.
- رفض البطاقة لا يسجّل أي قيد، ويمكن للعميل إعادة المحاولة من صفحة الطلب.
- الطلب بالبطاقة الذي لم يُدفع خلال `CARD_PAYMENT_TIMEOUT_MINUTES` (60 دقيقة افتراضياً) يُلغى تلقائياً، وترجع قطعه للمخزون.
- **ردّ مبلغ بطاقة:** نفّذ الاسترداد من لوحة Paymob، ثم سجّله في تطبيق الإدارة (استرداد ← بطاقة) ليظهر في الحسابات.
- **عند تحويل Paymob للمبلغ إلى بنكك:** سجّل قيداً من ح/ البنك ومن ح/ عمولات بوابات الدفع (5206)، إلى ح/ مستحقات بوابات الدفع (1104).

### التجربة بدون حساب Paymob
في وضع التطوير (`PAYMENT_GATEWAY=test`، وهو الافتراضي محلياً وفي Codespaces) تظهر **صفحة دفع تجريبية** فيها زران: "ادفع الآن" و"محاكاة رفض البطاقة". لا يُخصم فيها أي مبلغ حقيقي، والدورة المحاسبية كاملة كما في الحقيقة.

### التفعيل الحقيقي
1. أنشئ حساب تاجر على [paymob.com](https://paymob.com). ابدأ بوضع الاختبار (Test mode).
2. من لوحة Paymob انسخ:
   - **Secret key** و**Public key** (من Settings).
   - **Integration ID** لبطاقات الائتمان (من Payment Integrations).
   - **HMAC secret**.
3. في **Transaction processed callback** ضع: `https://<نطاقك>/api/v1/payments/paymob/callback`
4. اضبط على الخادم:
   ```
   PAYMENT_GATEWAY=paymob
   PAYMOB_SECRET_KEY=...
   PAYMOB_PUBLIC_KEY=...
   PAYMOB_INTEGRATION_ID=...
   PAYMOB_HMAC_SECRET=...
   ```
   الخادم يرفض البدء إذا نقص أحدها، أو إذا لم يكن `PUBLIC_BASE_URL` بـ https.
5. جرّب طلباً ببطاقة اختبار Paymob، ثم بدّل إلى مفاتيح Live.

---

## 3) الإشعارات (Firebase)

### بدون أي إعداد
الإشعارات تظهر داخل تطبيق العملاء في أيقونة الجرس مع عدّاد غير المقروء، وذلك عند:
- تأكيد الطلب، وتجهيزه، وشحنه (مع رقم التتبع)، وتوصيله، وإلغائه.
- استلام الدفع.
- رد المبلغ.
- قرارات المرتجعات.

### للإشعارات على الموبايل (Push)
1. أنشئ مشروعاً على [console.firebase.google.com](https://console.firebase.google.com).
2. أضف تطبيقي أندرويد بالمعرّفين `com.dejavu.customer` و`com.dejavu.admin`. هذان معرّفا نسخ الإصدار؛ النسخ التجريبية تنتهي بـ `.debug` وتحتاج تسجيلاً منفصلاً إن أردت الإشعارات فيها.
3. **على الخادم:**
   - من Project settings ← Service accounts ← **Generate new private key** حمّل ملف JSON.
   - ضع محتواه في `FCM_SERVICE_ACCOUNT`، كما هو أو بصيغة base64 بالأمر `base64 -w0 key.json`.
4. **في GitHub:** من Settings ← Secrets and variables ← Actions، أضف القيم من Project settings ← General لكل تطبيق:

| النوع | الاسم | القيمة |
|---|---|---|
| Secret | `FIREBASE_API_KEY` | Web API key |
| Variable | `FIREBASE_PROJECT_ID` | Project ID |
| Variable | `FIREBASE_SENDER_ID` | Project number |
| Variable | `FIREBASE_CUSTOMER_APP_ID` | App ID لتطبيق العملاء (`1:...:android:...`) |
| Variable | `FIREBASE_ADMIN_APP_ID` | App ID لتطبيق الإدارة |

لا تحتاج ملف `google-services.json`؛ القيم تُمرَّر للتطبيقين وقت البناء.

ماذا يصل لكل طرف:
- **العميل:** إشعار بكل تغيير في طلبه، والضغط عليه يفتح الطلب.
- **موظفو المبيعات** (صلاحية `orders.update_status`): إشعار "طلب جديد" مع كل طلب.

---

## 4) نسخ أندرويد موقّعة (Release)

### مرة واحدة: إنشاء مفتاح التوقيع
**احتفظ به في مكان آمن.** فقدانه يعني عدم القدرة على تحديث التطبيق على Google Play.

```bash
keytool -genkeypair -v -keystore dejavu-release.jks -alias dejavu -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 dejavu-release.jks    # انسخ الناتج
```

ثم أضف في GitHub ← Settings ← Secrets and variables ← Actions:

| النوع | الاسم | القيمة |
|---|---|---|
| Secret | `ANDROID_KEYSTORE_BASE64` | ناتج base64 أعلاه |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | كلمة مرور الملف |
| Secret | `ANDROID_KEY_ALIAS` | `dejavu` |
| Secret | `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح |
| Variable | `DEJAVU_API_URL` | `https://<نطاقك>/api/v1/` |

### إصدار نسخة
```bash
git tag v1.0.0 && git push origin v1.0.0
```

سيبني workflow **Android release** نسختين موقّعتين:
- `dejavu-customer-1.0.0.apk`
- `dejavu-admin-1.0.0.apk`

وينشرهما في صفحة **Releases** على GitHub. يمكن تشغيله يدوياً من تبويب Actions أيضاً.

نسخ الإصدار:
- تقبل `https` فقط.
- مضبوطة على رابط خادمك، ويبقى تغييره ممكناً من شاشة الخادم.
- رقم الإصدار يُؤخذ من الوسم.

---

## قائمة التحقق قبل الإطلاق

- [ ] `JWT_ACCESS_SECRET` و`POSTGRES_PASSWORD` عشوائيان وطويلان.
- [ ] تغيير كلمة مرور المدير (`SEED_ADMIN_PASSWORD`).
- [ ] `DEMO_DATA=false`.
- [ ] HTTPS يعمل، و`PUBLIC_BASE_URL` يطابق النطاق.
- [ ] Paymob: callback مسجّل، وتجربة دفع ورفض ناجحة في Test mode.
- [ ] النسخ الاحتياطي اليومي مفعّل، وتمت تجربة الاسترجاع مرة.
- [ ] مفتاح توقيع أندرويد محفوظ في مكانين آمنين.
