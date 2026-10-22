package com.dejavu.admin.ui.purchasing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One line of the matching checklist. */
data class ChecklistRow(val name: String, val option: String, val sku: String, val quantity: Int, val imageUrl: String?)

/**
 * A4 checklist for the worker / clearing agent: thumbnail, name, size/colour, SKU and the
 * ordered quantity, with a box to tick and a space to write what was counted. Drawn with
 * Android's own text engine, so Arabic is shaped correctly.
 */
suspend fun checklistPdf(context: Context, title: String, subtitle: String, rows: List<ChecklistRow>): File = withContext(Dispatchers.IO) {
    val thumbs = coroutineScope { rows.map { r -> async { r.imageUrl?.let(::thumbnail) } }.awaitAll() }
    val pageW = 595
    val pageH = 842
    val margin = 32f
    val rowH = 78f
    val doc = PdfDocument()
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 26, 26); textSize = 12f; textAlign = Paint.Align.RIGHT }
    val soft = Paint(ink).apply { color = Color.rgb(108, 117, 125); textSize = 10f }
    val bold = Paint(ink).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 13f }
    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 119, 28) }
    val line = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 1f }
    val frameLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 175, 55); style = Paint.Style.STROKE; strokeWidth = 1f }
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 26, 26); style = Paint.Style.STROKE; strokeWidth = 1.2f }
    val right = pageW - margin

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
        c.drawText(title, right, margin + 40f, bold)
        c.drawText(subtitle, right, margin + 58f, soft)
        soft.textAlign = Paint.Align.LEFT
        c.drawText("صفحة $pageNo", margin, margin + 18f, soft)
        soft.textAlign = Paint.Align.RIGHT
        y = margin + 80f
        // Column headers.
        c.drawText("الصنف", right - 76f, y, soft)
        soft.textAlign = Paint.Align.CENTER
        c.drawText("الكمية", margin + 150f, y, soft)
        c.drawText("المستلم", margin + 85f, y, soft)
        c.drawText("✓", margin + 18f, y, soft)
        soft.textAlign = Paint.Align.RIGHT
        y += 8f
        c.drawLine(margin, y, right, y, line)
    }
    newPage()

    rows.forEachIndexed { i, r ->
        if (y + rowH > pageH - margin - 30f) newPage()
        val c = page!!.canvas
        val top = y + 6f
        // Thumbnail on the right (RTL), framed.
        val frame = RectF(right - 64f, top, right, top + 64f)
        thumbs[i]?.let { bmp ->
            val s = minOf(64f / bmp.width, 64f / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            c.drawBitmap(bmp, null, RectF(frame.centerX() - w / 2, frame.centerY() - h / 2, frame.centerX() + w / 2, frame.centerY() + h / 2), null)
        }
        c.drawRoundRect(frame, 6f, 6f, frameLine)
        val textRight = right - 76f
        c.drawText(fit(r.name, bold, textRight - margin - 190f), textRight, top + 18f, bold)
        if (r.option.isNotBlank()) c.drawText(r.option, textRight, top + 36f, ink)
        c.drawText("SKU ${r.sku}", textRight, top + 54f, soft)
        // Quantity, a line to write the count, and a tick box.
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = 16f
        c.drawText("${r.quantity}", margin + 150f, top + 38f, bold)
        bold.textSize = 13f
        bold.textAlign = Paint.Align.RIGHT
        c.drawLine(margin + 60f, top + 42f, margin + 110f, top + 42f, box)
        c.drawRect(margin + 8f, top + 22f, margin + 28f, top + 42f, box)
        y += rowH
        c.drawLine(margin, y, right, y, line)
    }

    val c = page!!.canvas
    val pieces = rows.sumOf { it.quantity }
    bold.textAlign = Paint.Align.RIGHT
    c.drawText("الإجمالي: ${rows.size} صنف · $pieces قطعة", right, y + 24f, bold)
    c.drawText("توقيع المستلم: ____________________", right, y + 50f, ink)
    doc.finishPage(page!!)

    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File(dir, "checklist-${System.currentTimeMillis()}.pdf")
    file.outputStream().use(doc::writeTo)
    doc.close()
    file
}

/** Opens the share sheet for the PDF (WhatsApp, email…). */
fun sharePdf(context: Context, file: File, subject: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "مشاركة ملف المطابقة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun fit(text: String, paint: Paint, max: Float): String {
    if (paint.measureText(text) <= max) return text
    var t = text
    while (t.length > 1 && paint.measureText("$t…") > max) t = t.dropLast(1)
    return "$t…"
}

/** A small copy of a product photo (at most ~200 px), or null if it cannot be loaded. */
private fun thumbnail(url: String): Bitmap? = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
    }
    val bytes = try {
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
