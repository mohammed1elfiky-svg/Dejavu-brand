package com.dejavu.core.ui

import com.dejavu.core.model.OrderStatus
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.model.PaymentStatus
import com.dejavu.core.model.ReturnStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val OrderStatus.label: String
    get() = when (this) {
        OrderStatus.PENDING -> "بانتظار التأكيد"
        OrderStatus.CONFIRMED -> "تم التأكيد"
        OrderStatus.PROCESSING -> "قيد التجهيز"
        OrderStatus.SHIPPED -> "تم الشحن"
        OrderStatus.DELIVERED -> "تم التسليم"
        OrderStatus.COMPLETED -> "مكتمل"
        OrderStatus.CANCELLED -> "ملغي"
        OrderStatus.RETURNED -> "لم يتم الاستلام"
    }

val PaymentStatus.label: String
    get() = when (this) {
        PaymentStatus.UNPAID -> "غير مدفوع"
        PaymentStatus.PARTIALLY_PAID -> "مدفوع جزئياً"
        PaymentStatus.PAID -> "مدفوع"
        PaymentStatus.PARTIALLY_REFUNDED -> "مسترد جزئياً"
        PaymentStatus.REFUNDED -> "مسترد"
    }

val PaymentMethod.label: String
    get() = when (this) {
        PaymentMethod.CASH_ON_DELIVERY -> "الدفع عند الاستلام"
        PaymentMethod.CARD -> "بطاقة بنكية"
        PaymentMethod.WALLET -> "محفظة إلكترونية"
        PaymentMethod.INSTAPAY -> "إنستاباي"
        PaymentMethod.BANK_TRANSFER -> "تحويل بنكي"
        PaymentMethod.CASH -> "نقداً"
        PaymentMethod.CUSTOMER_ACCOUNT -> "من رصيد المحفظة"
    }

val ReturnStatus.label: String
    get() = when (this) {
        ReturnStatus.REQUESTED -> "قيد المراجعة"
        ReturnStatus.APPROVED -> "تمت الموافقة، بانتظار استلام القطع"
        ReturnStatus.REJECTED -> "مرفوض"
        ReturnStatus.RECEIVED -> "تم استلام القطع"
        ReturnStatus.REFUNDED -> "تم رد المبلغ"
    }

private val cairo = ZoneId.of("Asia/Aden")
private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar"))
private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMMM، h:mm a", Locale("ar"))

/** "2026-10-21T00:22:54.000Z" → "21 أكتوبر 2026" */
fun formatDate(iso: String?): String = iso?.let { runCatching { dateFormat.format(Instant.parse(it).atZone(cairo)) }.getOrNull() } ?: ""

fun formatDateTime(iso: String?): String = iso?.let { runCatching { dateTimeFormat.format(Instant.parse(it).atZone(cairo)) }.getOrNull() } ?: ""
