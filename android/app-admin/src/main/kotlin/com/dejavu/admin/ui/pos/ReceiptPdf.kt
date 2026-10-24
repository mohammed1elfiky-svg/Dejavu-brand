package com.dejavu.admin.ui.pos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.model.Money
import com.dejavu.core.model.PaymentMethod
import com.dejavu.core.ui.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The till receipt as a one-page PDF (page as tall as the invoice), drawn right-to-left with
 * Android's own text engine so Arabic is shaped correctly: store name, number, date, customer,
 * the lines, discount, total in the invoice currency and what was paid / handed back / put on account.
 */
suspend fun receiptPdf(context: Context, r: PosReceipt): File = withContext(Dispatchers.IO) {
    val cur = r.currency
    fun inCur(v: String) = Money.plain(Money.parse(v), cur)

    val pageW = 420
    val margin = 28f
    val rowH = 44f
    val right = pageW - margin

    // What is printed under the total.
    val payRows = buildList {
        r.payments.forEach { p ->
            val label = when {
                p.isChange -> "الباقي المُرجَع"
                p.method == PaymentMethod.CUSTOMER_ACCOUNT -> "على حساب العميل"
                p.method == PaymentMethod.BANK_TRANSFER -> "مدفوع بتحويل بنكي"
                else -> "مدفوع نقداً"
            }
            val value = p.currencyAmount?.let { Money.plain(Money.parse(it).abs(), p.currency) }
                ?: Money.formatBase(Money.parse(p.amount).abs().toPlainString())
            add(label to value)
        }
        if (!Money.isZero(r.onAccount) && r.payments.none { it.method == PaymentMethod.CUSTOMER_ACCOUNT }) {
            add("على حساب العميل" to r.inInvoiceCurrency(r.onAccount))
        }
    }
    val hasDiscount = !Money.isZero(r.inCurrency.discountTotal)
    val headerH = 150f + (if (r.customer != null) 18f else 0f) + (if (r.cashier != null) 18f else 0f)
    val totalsH = 30f + 22f * (2 + (if (hasDiscount) 1 else 0) + (if (!cur.isBase) 1 else 0) + payRows.size)
    val pageH = (headerH + 30f + rowH * r.items.size + totalsH + 70f).toInt().coerceAtLeast(400)

    val doc = PdfDocument()
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 26, 26); textSize = 12f; textAlign = Paint.Align.RIGHT }
    val soft = Paint(ink).apply { color = Color.rgb(108, 117, 125); textSize = 10f }
    val bold = Paint(ink).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 13f }
    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 119, 28) }
    val goldText = Paint(bold).apply { color = Color.rgb(170, 119, 28) }
    val line = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 1f }

    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
    val c = page.canvas
    c.drawColor(Color.WHITE)
    c.drawRect(0f, 0f, pageW.toFloat(), 6f, gold)

    // Header.
    var y = margin + 22f
    goldText.textSize = 24f
    goldText.textAlign = Paint.Align.CENTER
    c.drawText("DEJAVU", pageW / 2f, y, goldText)
    goldText.textAlign = Paint.Align.RIGHT
    goldText.textSize = 13f
    y += 20f
    soft.textAlign = Paint.Align.CENTER
    c.drawText("فاتورة مبيعات", pageW / 2f, y, soft)
    soft.textAlign = Paint.Align.RIGHT
    y += 28f
    fun pair(label: String, value: String, valuePaint: Paint = ink) {
        c.drawText(label, right, y, soft)
        valuePaint.textAlign = Paint.Align.LEFT
        c.drawText(value, margin, y, valuePaint)
        valuePaint.textAlign = Paint.Align.RIGHT
        y += 18f
    }
    pair("رقم الفاتورة", r.number, bold)
    pair("التاريخ", formatDateTime(r.placedAt))
    r.customer?.let { pair("العميل", fitText(listOfNotNull(it.name, it.phone).joinToString(" · "), ink, pageW - 2 * margin - 90f)) }
    r.cashier?.let { pair("الكاشير", it.name) }
    pair("العملة", cur.nameAr)
    y += 4f
    c.drawLine(margin, y, right, y, line)

    // Column headers: item (right), qty, price, total (left).
    val qtyX = margin + 150f
    val priceX = margin + 95f
    y += 16f
    c.drawText("الصنف", right, y, soft)
    soft.textAlign = Paint.Align.CENTER
    c.drawText("الكمية", qtyX, y, soft)
    c.drawText("السعر", priceX, y, soft)
    soft.textAlign = Paint.Align.LEFT
    c.drawText("الإجمالي", margin, y, soft)
    soft.textAlign = Paint.Align.RIGHT
    y += 8f
    c.drawLine(margin, y, right, y, line)

    // Lines.
    val nameMax = right - qtyX - 24f
    r.items.forEach { i ->
        val top = y
        c.drawText(fitText(i.productName, bold, nameMax), right, top + 18f, bold)
        val option = listOfNotNull(i.size, i.color).joinToString(" / ").ifBlank { i.sku }
        c.drawText(fitText(option, soft, nameMax), right, top + 34f, soft)
        ink.textAlign = Paint.Align.CENTER
        c.drawText("${i.quantity}", qtyX, top + 26f, ink)
        c.drawText(r.inInvoiceCurrency(i.unitPrice), priceX, top + 26f, ink)
        ink.textAlign = Paint.Align.LEFT
        c.drawText(r.inInvoiceCurrency(i.lineTotal), margin, top + 26f, ink)
        ink.textAlign = Paint.Align.RIGHT
        y += rowH
        c.drawLine(margin, y, right, y, line)
    }

    // Totals.
    y += 22f
    pair("المجموع", inCur(r.inCurrency.subtotal))
    if (hasDiscount) pair("الخصم", "− ${inCur(r.inCurrency.discountTotal)}")
    y += 2f
    goldText.textSize = 15f
    c.drawText("الإجمالي", right, y, goldText)
    goldText.textAlign = Paint.Align.LEFT
    c.drawText(inCur(r.inCurrency.grandTotal), margin, y, goldText)
    goldText.textAlign = Paint.Align.RIGHT
    y += 20f
    if (!cur.isBase) pair("يعادل", "${Money.formatBase(r.grandTotal)} · السعر ${r.exchangeRate}", soft)
    if (payRows.isNotEmpty()) {
        y += 2f
        c.drawLine(margin, y - 12f, right, y - 12f, line)
        payRows.forEach { (label, value) -> pair(label, value) }
    }

    // Footer.
    y += 18f
    soft.textAlign = Paint.Align.CENTER
    c.drawText("شكراً لتسوقكم من DEJAVU", pageW / 2f, y, soft)
    soft.textAlign = Paint.Align.RIGHT
    doc.finishPage(page)

    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val safe = r.number.replace(Regex("[^A-Za-z0-9-]"), "_")
    val file = File(dir, "invoice-$safe-${System.currentTimeMillis()}.pdf")
    file.outputStream().use(doc::writeTo)
    doc.close()
    file
}

/**
 * The WhatsApp chat id for a customer phone: Yemeni 9-digit mobiles (7xxxxxxxx) get 967,
 * Saudi 05xxxxxxxx becomes 9665xxxxxxxx; numbers with a country code are kept. Null if unusable.
 */
internal fun whatsappJid(phone: String?): String? {
    if (phone.isNullOrBlank()) return null
    var d = phone.filter { it.isDigit() }
    if (d.startsWith("00")) d = d.drop(2)
    d = when {
        d.length == 9 && d.startsWith("7") -> "967$d"
        d.length == 10 && d.startsWith("05") -> "966${d.drop(1)}"
        d.length == 9 && d.startsWith("5") -> "966$d"
        else -> d
    }
    return if (d.length >= 10) "$d@s.whatsapp.net" else null
}

/**
 * Sends the receipt PDF on WhatsApp — straight to the customer's chat when [phone] is known —
 * then WhatsApp Business, then the normal share sheet if neither is installed.
 */
fun shareReceiptToWhatsApp(context: Context, file: File, phone: String?, caption: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val jid = whatsappJid(phone)
    fun send(pkg: String?): Intent = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply {
            if (pkg != null) {
                setPackage(pkg)
                if (jid != null) putExtra("jid", jid)
            }
        }
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(send(pkg))
            return
        } catch (_: ActivityNotFoundException) {
            // Not installed: try the next one.
        }
    }
    context.startActivity(Intent.createChooser(send(null), "إرسال الفاتورة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun fitText(text: String, paint: Paint, max: Float): String {
    if (paint.measureText(text) <= max) return text
    var t = text
    while (t.length > 1 && paint.measureText("$t…") > max) t = t.dropLast(1)
    return "$t…"
}
