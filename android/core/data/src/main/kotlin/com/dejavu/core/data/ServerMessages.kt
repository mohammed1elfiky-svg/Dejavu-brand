package com.dejavu.core.data

import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.network.ApiException

/**
 * The API answers in English; customers read Arabic. Known messages are
 * translated here, anything unknown is shown as-is.
 */
object ServerMessages {
    private val exact = mapOf(
        "Invalid credentials" to "رقم الموبايل أو كلمة المرور غير صحيحة",
        "Phone number already registered" to "رقم الموبايل مسجّل بالفعل، سجّل الدخول",
        "Email already registered" to "البريد الإلكتروني مسجّل بالفعل",
        "Account is not active" to "الحساب غير مفعّل، يرجى التواصل مع خدمة العملاء",
        "Out of stock" to "نفدت الكمية",
        "No longer available" to "لم يعد متاحاً",
        "Product is not available" to "المنتج غير متاح حالياً",
        "Coupon code is not valid" to "كود الخصم غير صحيح",
        "Coupon has expired" to "انتهت صلاحية كود الخصم",
        "Coupon usage limit reached" to "تم استهلاك كود الخصم بالكامل",
        "You have already used this coupon" to "تم استخدام كود الخصم هذا من قبل",
        "Your cart is empty" to "السلة فارغة",
        "Cart needs attention" to "يرجى مراجعة السلة قبل إتمام الطلب",
        "Item is not in the cart" to "المنتج لم يعد في السلة",
        "Address not found" to "العنوان غير موجود",
        "Order not found" to "الطلب غير موجود",
        "This order can no longer be cancelled; please contact support" to "لا يمكن إلغاء الطلب الآن، يرجى التواصل مع خدمة العملاء",
        "Only delivered orders can be returned" to "يمكن إرجاع الطلبات بعد استلامها فقط",
        "You can save up to 10 addresses" to "يمكن حفظ 10 عناوين كحد أقصى",
        "Each item may appear only once" to "يمكن اختيار كل منتج مرة واحدة فقط",
        "Amount must be greater than zero" to "المبلغ يجب أن يكون أكبر من صفر",
        "Upload the deposit receipt first" to "أرفق صورة إيصال الإيداع أولاً",
        "Bank account not found" to "الحساب البنكي غير متاح",
        "You already have several requests under review" to "لديك عدة طلبات شحن قيد المراجعة، انتظر حتى تتم مراجعتها",
    )

    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^Not enough wallet balance in (.+)$") to { m -> "رصيد المحفظة بعملة ${Currency.of(m.groupValues[1]).nameAr} غير كافٍ" },
        Regex("^Only (\\d+) left in stock$") to { m -> "متبقي ${m.groupValues[1]} فقط" },
        Regex("^Insufficient stock for (.+)$") to { m -> "الكمية المطلوبة غير متاحة (${m.groupValues[1]})" },
        Regex("^Minimum order for this coupon is (.+)$") to { m -> "الحد الأدنى للطلب مع هذا الكود ${Money.format(m.groupValues[1])}" },
        Regex("^Delivery to (.+) is not available yet$") to { m -> "التوصيل إلى ${m.groupValues[1]} غير متاح حالياً" },
        Regex("^At most (\\d+) pieces per item$") to { m -> "الحد الأقصى ${m.groupValues[1]} قطعة لكل منتج" },
        Regex("^Returns are accepted within (\\d+) days of delivery$") to { m -> "يمكن الإرجاع خلال ${m.groupValues[1]} يوماً من الاستلام" },
        Regex("^(.+): only (\\d+) piece\\(s\\) can still be returned$") to { m -> "${m.groupValues[1]}: يمكن إرجاع ${m.groupValues[2]} قطعة فقط" },
        // Cart problems arrive as "SKU: problem"
        Regex("^([A-Z0-9-]+): (.+)$") to { m -> "${m.groupValues[1]}: ${translate(m.groupValues[2])}" },
    )

    fun translate(message: String): String {
        exact[message]?.let { return it }
        for ((regex, render) in patterns) regex.matchEntire(message)?.let { return render(it) }
        return message
    }
}

/** Arabic, user-facing text for any failure coming out of the repositories. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message.lines().joinToString("\n") { ServerMessages.translate(it) }
    else -> message ?: "حدث خطأ غير متوقع"
}
