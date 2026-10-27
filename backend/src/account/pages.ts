/** The two public pages Google Play asks for: privacy policy and account deletion. */

interface Store {
  name: string;
  contact: string;
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

function layout(title: string, body: string) {
  return `<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif;margin:0;background:#f6f3ee;color:#222;line-height:1.75}
main{max-width:760px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:1.5rem;margin:.2em 0 .6em}h2{font-size:1.1rem;margin:1.6em 0 .4em}
.card{background:#fff;border-radius:14px;padding:18px 20px;box-shadow:0 1px 3px #0001}
label{display:block;margin:.8em 0 .3em;font-weight:600}
input[type=tel],input[type=password]{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid #ccc;border-radius:10px;font-size:1rem}
button{margin-top:1.2em;width:100%;padding:12px;border:0;border-radius:10px;background:#b3261e;color:#fff;font-size:1rem;font-weight:600}
.err{background:#fde8e7;color:#8c1d18;padding:10px 12px;border-radius:10px}.ok{background:#e6f4ea;color:#14532d;padding:10px 12px;border-radius:10px}
.en{direction:ltr;text-align:left;color:#555;font-size:.92rem;border-top:1px solid #ddd;margin-top:2em;padding-top:1em}
small{color:#666}
</style></head><body><main>${body}</main></body></html>`;
}

export function privacyPage(store: Store) {
  const name = esc(store.name);
  const contact = store.contact ? `<p>للتواصل: <b dir="ltr">${esc(store.contact)}</b></p>` : '';
  return layout(
    `سياسة الخصوصية — ${store.name}`,
    `<div class="card">
<h1>سياسة الخصوصية — تطبيق ${name}</h1>
<p>توضح هذه الصفحة البيانات التي يجمعها تطبيق ${name} للتسوق، ولماذا، وكيف تُحذف.</p>
<h2>البيانات التي نجمعها</h2>
<ul>
<li><b>بيانات الحساب:</b> الاسم ورقم الجوال وكلمة المرور (محفوظة مشفّرة)، والمحافظة والمنطقة، وعناوين التوصيل.</li>
<li><b>الطلبات والمدفوعات:</b> الطلبات والمرتجعات ورصيد المحفظة والعربون وإيصالات التحويل التي ترفعها. الدفع بالبطاقة يتم في صفحة بوابة الدفع، ولا يصلنا رقم البطاقة.</li>
<li><b>استخدام التطبيق:</b> معرّف عشوائي للجهاز، ومدة استخدام التطبيق، والمنتجات التي تشاهدها، وعمليات البحث، وذلك لتحسين المتجر وعرض منتجات تناسبك.</li>
<li><b>الإشعارات:</b> رمز الجهاز لإرسال إشعارات الطلبات والعروض (عبر Firebase من Google).</li>
<li><b>المساعد الذكي:</b> الرسائل والصور التي ترسلها للمساعد تُحفظ في حسابك وتُرسل إلى خدمة Google Gemini لتوليد الرد.</li>
</ul>
<h2>كيف نستخدمها</h2>
<p>لتنفيذ الطلبات وتوصيلها، وإدارة حسابك ورصيدك، وإرسال رموز التحقق والإشعارات، والرد على استفساراتك، وتحسين المتجر. لا نبيع بياناتك ولا نشاركها لأغراض إعلانية.</p>
<h2>مع من نشاركها</h2>
<p>فقط مع مزوّدي الخدمة اللازمين لعمل التطبيق: شركات التوصيل (الاسم والجوال والعنوان)، وبوابة الدفع عند الدفع بالبطاقة، وGoogle (الإشعارات والمساعد الذكي).</p>
<h2>الحماية والاحتفاظ</h2>
<p>تنتقل البيانات مشفّرة (HTTPS)، وكلمات المرور محفوظة بتشفير لا يمكن عكسه. نحتفظ ببيانات حسابك ما دام الحساب قائماً.</p>
<h2>حذف الحساب</h2>
<p>يمكنك حذف حسابك في أي وقت من التطبيق (حسابي ← حذف الحساب)، أو من <a href="delete-account">صفحة حذف الحساب</a>. عند الحذف نمسح الاسم والجوال وكلمة المرور والعناوين والسلة والمفضلة والإشعارات ومحادثات المساعد فوراً. أما سجلات الفواتير والمدفوعات فنحتفظ بها بدون بياناتك الشخصية لأن القانون والمحاسبة يلزماننا بها.</p>
<h2>الأطفال</h2>
<p>التطبيق غير موجّه لمن هم دون 13 عاماً.</p>
<h2>التواصل</h2>
<p>لأي سؤال عن بياناتك تواصل معنا من صفحة "عن المتجر" في التطبيق.</p>${contact}
<div class="en"><h2>Privacy policy (summary)</h2>
<p>The ${name} shopping app collects: account data (name, phone number, hashed password, region, delivery addresses); orders, returns, wallet balance and payment slips (card numbers are handled by the payment gateway, never by us); app usage (a random device ID, time in the app, viewed products and searches); a push token (Firebase); and messages and photos sent to the in-app assistant, which are processed by Google Gemini.</p>
<p>Data is used to fulfil orders, run your account, send verification codes and notifications, answer questions and improve the store. It is not sold or used for advertising. It is shared only with delivery companies, the payment gateway and Google, as needed. Data travels over HTTPS.</p>
<p>You can delete your account in the app (Account → Delete account) or at the <a href="delete-account">account deletion page</a>. Personal data is erased immediately; invoices and payment records are kept without personal data for legal and accounting reasons. The app is not directed at children under 13.</p></div>
</div>`,
  );
}

export function deletePage(store: Store, state: { error?: string; done?: boolean } = {}) {
  const name = esc(store.name);
  if (state.done) {
    return layout(`حذف الحساب — ${store.name}`, `<div class="card"><h1>تم حذف الحساب</h1><p class="ok">تم حذف حسابك وبياناتك الشخصية من ${name}.</p><div class="en">Your ${name} account and personal data have been deleted.</div></div>`);
  }
  return layout(
    `حذف الحساب — ${store.name}`,
    `<div class="card">
<h1>حذف حساب ${name}</h1>
<p>أدخل رقم جوالك وكلمة المرور لحذف حسابك نهائياً. يمكنك أيضاً حذفه من التطبيق: <b>حسابي ← حذف الحساب</b>.</p>
<p><small>يُحذف فوراً: الاسم والجوال وكلمة المرور والعناوين والسلة والمفضلة والإشعارات ومحادثات المساعد. ويُفقد رصيد المحفظة والنقاط. تبقى الفواتير والمدفوعات بدون بياناتك الشخصية لأسباب محاسبية وقانونية.</small></p>
${state.error ? `<p class="err">${esc(state.error)}</p>` : ''}
<form method="post" action="delete-account">
<label for="phone">رقم الجوال</label><input id="phone" name="phone" type="tel" dir="ltr" required autocomplete="tel">
<label for="password">كلمة المرور</label><input id="password" name="password" type="password" dir="ltr" required autocomplete="current-password">
<label><input type="checkbox" name="confirm" value="yes" required> أفهم أن الحذف نهائي ولا يمكن التراجع عنه</label>
<button type="submit">حذف حسابي نهائياً</button>
</form>
<div class="en"><b>Delete your ${name} account.</b> Enter your phone number and password. Your name, phone, password, addresses, cart, wishlist, notifications and assistant chats are erased immediately; wallet balance and points are lost. Invoices and payment records are kept without personal data for accounting and legal reasons. You can also delete it in the app: Account → Delete account.</div>
</div>`,
  );
}
