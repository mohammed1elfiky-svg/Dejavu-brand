package com.dejavu.core.data

/** Client-side checks mirroring the API's rules, so users see mistakes before submitting. Null = valid. */
object Validators {
    /** Same rule as the API: Yemeni (7XXXXXXXX, optionally +967/00967), Saudi (05… / +9665…) or Egyptian (01… / +201…). */
    private val PHONE = Regex("^(?:(?:\\+967|00967)?7[01378]\\d{7}|(?:\\+966|0)5\\d{8}|(?:\\+20|0)1[0125]\\d{8})$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun phone(value: String): String? =
        if (PHONE.matches(value.trim().replace(" ", ""))) null else "رقم موبايل غير صحيح (مثال: 771234567 أو 0501234567)"

    fun password(value: String): String? = if (value.length >= 8) null else "كلمة المرور 8 أحرف على الأقل"

    fun name(value: String): String? = if (value.trim().length >= 2) null else "اكتب الاسم"

    fun email(value: String): String? = if (value.isBlank() || EMAIL.matches(value.trim())) null else "بريد إلكتروني غير صحيح"

    fun required(value: String, label: String): String? = if (value.trim().length >= 2) null else "$label مطلوب"
}
