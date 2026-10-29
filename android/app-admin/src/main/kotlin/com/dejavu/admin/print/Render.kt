package com.dejavu.admin.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.dejavu.admin.ui.barcode.StickerData
import com.dejavu.admin.ui.barcode.stickerBitmap
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.formatDateTime

/**
 * Lines of a receipt, measured first and then drawn, so the picture is exactly as tall as
 * its content. Arabic is shaped by Android's text engine; labels sit on the right (RTL),
 * amounts on the left.
 */
private class Slip(private val width: Int) {
    private val scale = width / 576f
    private val margin = 12 * scale
    private val ops = mutableListOf<Pair<Float, (Canvas, Float) -> Unit>>()

    /** A line [h] dots tall; [draw] gets the canvas and the line's bottom. */
    private fun op(h: Float, draw: (Canvas, Float) -> Unit) {
        ops.add(h to draw)
    }

    private fun paint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size * scale
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun gap(h: Float) {
        op(h * scale) { _, _ -> }
    }

    fun center(text: String, size: Float = 24f, bold: Boolean = false) {
        val p = paint(size, bold).apply { textAlign = Paint.Align.CENTER }
        wrap(text, p, width - 2 * margin).forEach { line ->
            op(p.fontSpacing) { c, y -> c.drawText(line, width / 2f, y - p.descent(), p) }
        }
    }

    /** Label on the right, value on the left. */
    fun row(label: String, value: String, size: Float = 24f, bold: Boolean = false) {
        val p = paint(size, bold)
        op(p.fontSpacing) { c, y ->
            p.textAlign = Paint.Align.RIGHT
            c.drawText(label, width - margin, y - p.descent(), p)
            p.textAlign = Paint.Align.LEFT
            c.drawText(value, margin, y - p.descent(), p)
        }
    }

    /** Right-aligned text, wrapped to the paper. */
    fun right(text: String, size: Float = 24f, bold: Boolean = false) {
        val p = paint(size, bold).apply { textAlign = Paint.Align.RIGHT }
        wrap(text, p, width - 2 * margin).forEach { line ->
            op(p.fontSpacing) { c, y -> c.drawText(line, width - margin, y - p.descent(), p) }
        }
    }

    fun rule(dashed: Boolean = true) {
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 2f * scale }
        op(14 * scale) { c, y ->
            val ly = y - 7 * scale
            if (!dashed) {
                c.drawLine(margin, ly, width - margin, ly, p)
            } else {
                var x = margin
                while (x < width - margin) {
                    c.drawLine(x, ly, minOf(x + 10 * scale, width - margin), ly, p)
                    x += 18 * scale
                }
            }
        }
    }

    fun draw(): Bitmap {
        val height = ops.sumOf { it.first.toDouble() }.toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = 0f
        for ((h, paintLine) in ops) {
            y += h
            paintLine(c, y)
        }
        return bmp
    }

    private fun wrap(text: String, p: Paint, max: Float): List<String> {
        if (p.measureText(text) <= max) return listOf(text)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= max || line.isEmpty()) line = next else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}

/** The sale receipt, [width] dots wide (384 for 58 mm paper, 576 for 80 mm). */
fun receiptBitmap(r: PosReceipt, width: Int, store: String = "Dejavu"): Bitmap {
    val cur = r.currency
    fun inCur(v: String) = Money.plain(Money.parse(v), cur)
    return Slip(width).apply {
        gap(8f)
        center(store.uppercase(), size = 44f, bold = true)
        center("فاتورة مبيعات", size = 24f)
        rule(dashed = false)
        row("رقم الفاتورة", r.number, bold = true)
        row("التاريخ", formatDateTime(r.placedAt))
        r.cashier?.let { row("الكاشير", it.name) }
        r.customer?.let { row("العميل", it.name) }
        row("العملة", cur.code)
        rule()
        r.items.forEach { i ->
            right(i.productName, bold = true)
            val option = listOfNotNull(i.size, i.color).joinToString(" · ").ifBlank { i.sku }
            row("$option  ×${i.quantity}", r.inInvoiceCurrency(i.lineTotal), size = 22f)
        }
        rule()
        row("المجموع", inCur(r.inCurrency.subtotal))
        if (!Money.isZero(r.inCurrency.discountTotal)) row("الخصم", "- ${inCur(r.inCurrency.discountTotal)}")
        row("الإجمالي", inCur(r.inCurrency.grandTotal), size = 32f, bold = true)
        if (!cur.isBase) row("يعادل", Money.formatBase(r.grandTotal), size = 20f)
        if (r.payments.isNotEmpty()) rule()
        r.payments.forEach { p ->
            val label = when {
                p.isChange -> "الباقي"
                p.method == PaymentMethod.CUSTOMER_ACCOUNT -> "على حساب العميل"
                p.method == PaymentMethod.BANK_TRANSFER -> "تحويل بنكي"
                else -> "مدفوع نقداً"
            }
            val value = p.currencyAmount?.let { Money.plain(Money.parse(it).abs(), p.currency) } ?: Money.formatBase(Money.parse(p.amount).abs().toPlainString())
            row(label, value)
        }
        if (!Money.isZero(r.onAccount) && r.payments.none { it.method == PaymentMethod.CUSTOMER_ACCOUNT }) {
            row("على حساب العميل", r.inInvoiceCurrency(r.onAccount))
        }
        rule(dashed = false)
        center("شكراً لتسوقكم من $store", size = 24f, bold = true)
        center("نسعد بزيارتكم مرة أخرى", size = 20f)
        gap(16f)
    }.draw()
}

/** A one-line test page. */
fun testBitmap(width: Int, title: String, detail: String): Bitmap = Slip(width).apply {
    gap(8f)
    center("DEJAVU", size = 44f, bold = true)
    center(title, size = 28f, bold = true)
    center(detail, size = 22f)
    gap(16f)
}.draw()

/** What goes on a parcel: who it is for and how to reach them. */
data class ShippingLabel(
    val orderNumber: String,
    val name: String,
    val phone: String,
    val address: String,
    val carrier: String? = null,
    /** Cash to collect on delivery, already formatted (null when paid). */
    val collect: String? = null,
)

/** The parcel label, [width] dots wide: big name and phone, the address and the order number. */
fun shippingLabelBitmap(l: ShippingLabel, width: Int): Bitmap = Slip(width).apply {
    center("DEJAVU", size = 36f, bold = true)
    rule(dashed = false)
    right("إلى:", size = 22f)
    right(l.name, size = 34f, bold = true)
    right(l.phone, size = 34f, bold = true)
    gap(6f)
    right(l.address, size = 26f)
    l.carrier?.let { right("شركة الشحن: $it", size = 24f) }
    rule()
    row("رقم الطلب", l.orderNumber, size = 28f, bold = true)
    l.collect?.let { row("المطلوب تحصيله", it, size = 28f, bold = true) }
    gap(24f)
}.draw()

/** The barcode sticker scaled to the label: [width] × [height] dots. */
fun labelBitmap(d: StickerData, width: Int, height: Int): Bitmap {
    val art = stickerBitmap(d)
    // Keep the sticker's proportions, centred on the label.
    val scale = minOf(width / art.width.toFloat(), height / art.height.toFloat())
    val w = (art.width * scale).toInt().coerceAtLeast(1)
    val h = (art.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(art, w, h, true)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        drawColor(Color.WHITE)
        drawBitmap(scaled, (width - w) / 2f, (height - h) / 2f, null)
    }
    return out
}
