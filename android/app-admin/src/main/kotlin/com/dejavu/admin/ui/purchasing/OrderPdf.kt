package com.dejavu.admin.ui.purchasing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.dejavu.core.admin.PurchaseOrderDetail
import com.dejavu.core.admin.SupplierAccount
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

/**
 * A4 invoice of an import order in the supplier's currency (yuan): the lines with their factory
 * note, the inland shipping and the total, then the supplier's account balance per currency
 * ([balances]: + = owed to the supplier). Freight and customs are not on it: they belong to
 * the shipping company. Same RTL drawing as [checklistPdf]; share it with [sharePdf].
 */
suspend fun orderPdf(context: Context, po: PurchaseOrderDetail, balances: List<SupplierAccount.Balance>): File = withContext(Dispatchers.IO) {
    val code = po.foreignCurrency?.ifBlank { null } ?: "CNY"
    val cur = Currency.of(code)
    fun money(v: BigDecimal) = Money.plain(v, cur)

    val pageW = 595
    val pageH = 842
    val margin = 32f
    val rowH = 46f
    val doc = PdfDocument()
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 26, 26); textSize = 11f; textAlign = Paint.Align.RIGHT }
    val soft = Paint(ink).apply { color = Color.rgb(108, 117, 125); textSize = 9.5f }
    val bold = Paint(ink).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 12f }
    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 119, 28) }
    val goldText = Paint(bold).apply { color = Color.rgb(170, 119, 28) }
    val line = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 1f }
    val right = pageW - margin
    // Columns, right to left (RTL): item, quantity, unit price, line total.
    val qtyX = margin + 215f
    val unitX = margin + 150f
    val totalX = margin + 60f
    val nameMax = right - (qtyX + 30f)

    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f
    fun newPage() {
        page?.let(doc::finishPage)
        pageNo += 1
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        val c = page!!.canvas
        c.drawRect(0f, 0f, pageW.toFloat(), 6f, gold)
        bold.textSize = 20f
        c.drawText("DEJAVU", right, margin + 18f, bold)
        bold.textSize = 13f
        c.drawText("فاتورة طلبية ${po.number}", right, margin + 40f, bold)
        c.drawText("${formatDate(po.orderDate)} · المورد: ${po.supplier.name}", right, margin + 58f, soft)
        po.supplierInvoiceNo?.ifBlank { null }?.let { c.drawText("رقم فاتورة المورد: $it", right, margin + 72f, soft) }
        soft.textAlign = Paint.Align.LEFT
        c.drawText("صفحة $pageNo", margin, margin + 18f, soft)
        c.drawText("العملة: $code", margin, margin + 34f, soft)
        soft.textAlign = Paint.Align.RIGHT
        bold.textSize = 12f
        y = margin + 94f
        // Column headers.
        c.drawText("الصنف", right, y, soft)
        soft.textAlign = Paint.Align.CENTER
        c.drawText("الكمية", qtyX, y, soft)
        c.drawText("سعر القطعة", unitX, y, soft)
        c.drawText("الإجمالي", totalX, y, soft)
        soft.textAlign = Paint.Align.RIGHT
        y += 8f
        c.drawLine(margin, y, right, y, line)
    }
    newPage()

    var goods = BigDecimal.ZERO
    var pieces = 0
    po.items.forEach { item ->
        if (y + rowH > pageH - margin - 30f) newPage()
        val c = page!!.canvas
        val top = y + 4f
        val unit = Money.parse(item.unitPriceForeign)
        val total = unit.multiply(BigDecimal(item.quantity))
        goods = goods.add(total)
        pieces += item.quantity
        c.drawText(orderFit(item.variant.product.nameAr, bold, nameMax), right, top + 15f, bold)
        val details = listOfNotNull(item.option.ifBlank { null }, item.note?.ifBlank { null }).joinToString(" · ")
        if (details.isNotBlank()) c.drawText(orderFit(details, soft, nameMax), right, top + 32f, soft)
        ink.textAlign = Paint.Align.CENTER
        c.drawText("${item.quantity}", qtyX, top + 22f, ink)
        c.drawText(money(unit), unitX, top + 22f, ink)
        bold.textAlign = Paint.Align.CENTER
        c.drawText(money(total), totalX, top + 22f, bold)
        bold.textAlign = Paint.Align.RIGHT
        ink.textAlign = Paint.Align.RIGHT
        y += rowH
        c.drawLine(margin, y, right, y, line)
    }

    val domestic = Money.parse(po.domesticShipping)
    val balanceRows = balances.size.coerceAtLeast(1)
    // Totals (4 rows) and the balance block must fit on the page.
    if (y + 110f + balanceRows * 18f > pageH - margin) newPage()
    var c = page!!.canvas
    fun row(label: String, value: String, paint: Paint = ink) {
        y += 18f
        paint.textAlign = Paint.Align.RIGHT
        c.drawText(label, right, y, paint)
        paint.textAlign = Paint.Align.LEFT
        c.drawText(value, margin, y, paint)
        paint.textAlign = Paint.Align.RIGHT
    }
    y += 6f
    row("عدد الأصناف / القطع", "${po.items.size} / $pieces")
    row("قيمة البضاعة", money(goods))
    row("الشحن الداخلي", money(domestic))
    row("الإجمالي ($code)", money(goods.add(domestic)), goldText)
    y += 10f
    c.drawLine(margin, y, right, y, line)

    y += 6f
    if (y + 40f + balanceRows * 18f > pageH - margin) {
        newPage()
        c = page!!.canvas
    }
    row("رصيد حساب المورد", "", bold)
    if (balances.isEmpty()) {
        row("لا توجد حركات", "", soft)
    } else {
        balances.forEach { b ->
            val amount = Money.parse(b.balance)
            val shown = Money.plain(amount.abs(), Currency.of(b.currency))
            val what = when (amount.signum()) {
                1 -> "مستحق للمورد"
                -1 -> "مدفوع زيادة للمورد"
                else -> "مسدَّد"
            }
            row("${b.currency} · $what", shown, if (amount.signum() > 0) goldText else ink)
        }
    }
    y += 14f
    c.drawText("الشحن الخارجي والجمارك لا تدخل في هذه الفاتورة (تُسجَّل على شركة الشحن)", right, y, soft)
    doc.finishPage(page!!)

    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File(dir, "order-${po.number.filter { it.isLetterOrDigit() || it == '-' }}-${System.currentTimeMillis()}.pdf")
    file.outputStream().use(doc::writeTo)
    doc.close()
    file
}

private fun orderFit(text: String, paint: Paint, max: Float): String {
    if (paint.measureText(text) <= max) return text
    var t = text
    while (t.length > 1 && paint.measureText("$t…") > max) t = t.dropLast(1)
    return "$t…"
}
