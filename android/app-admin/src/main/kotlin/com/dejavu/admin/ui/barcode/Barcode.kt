package com.dejavu.admin.ui.barcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Opens the camera scanner and hands back the decoded text (null when cancelled).
 * Reads every common retail format, so both our Code 128 labels and factory EAN/UPC codes work.
 */
@Composable
fun rememberBarcodeScanner(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.trim()?.takeIf { it.isNotEmpty() }?.let(onResult) }
    return {
        launcher.launch(
            ScanOptions()
                .setPrompt("وجّه الكاميرا نحو الباركود")
                .setBeepEnabled(true)
                .setOrientationLocked(false)
                .setDesiredBarcodeFormats(
                    ScanOptions.CODE_128, ScanOptions.CODE_39, ScanOptions.EAN_13, ScanOptions.EAN_8,
                    ScanOptions.UPC_A, ScanOptions.UPC_E, ScanOptions.QR_CODE,
                ),
        )
    }
}

/** A crisp black-on-white Code 128 image of [text], [width]×[height] pixels. */
fun code128(text: String, width: Int, height: Int): Bitmap {
    val matrix = Code128Writer().encode(text, BarcodeFormat.CODE_128, width, height, mapOf(EncodeHintType.MARGIN to 0))
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    return bmp
}

/** What one sticker shows. */
data class StickerData(val sku: String, val name: String, val variant: String?, val priceSar: String, val priceYer: String?)

/**
 * A 50×30 mm label at 300 dpi (590×354 px): store name, item name and option, the
 * Code 128 of the SKU with the SKU under it, and the price in SAR and YER.
 */
fun stickerBitmap(d: StickerData, store: String = "Dejavu"): Bitmap {
    val w = 590
    val h = 354
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)
    val gold = Color.rgb(154, 116, 38)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun text(s: String, x: Float, y: Float, size: Float, bold: Boolean = false, color: Int = Color.BLACK, align: Paint.Align = Paint.Align.CENTER) {
        paint.textSize = size
        paint.color = color
        paint.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        paint.textAlign = align
        c.drawText(s, x, y, paint)
    }
    fun fit(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    // Thin gold frame and header.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    paint.color = gold
    c.drawRoundRect(4f, 4f, w - 4f, h - 4f, 18f, 18f, paint)
    paint.style = Paint.Style.FILL
    text(store.uppercase(), w / 2f, 38f, 26f, bold = true, color = gold)
    text(fit(d.name, 30), w / 2f, 74f, 30f, bold = true)
    d.variant?.let { text(fit(it, 36), w / 2f, 104f, 24f, color = Color.DKGRAY) }

    val bars = code128(d.sku, 500, 120)
    c.drawBitmap(bars, (w - bars.width) / 2f, 116f, null)
    text(d.sku, w / 2f, 270f, 26f, bold = true)

    paint.color = gold
    c.drawRect(20f, 286f, w - 20f, 288f, paint)
    if (d.priceYer != null) {
        text(d.priceSar, w * 0.27f, 330f, 32f, bold = true)
        text(d.priceYer, w * 0.73f, 330f, 32f, bold = true)
    } else {
        text(d.priceSar, w / 2f, 330f, 34f, bold = true)
    }
    return bmp
}

/** Several stickers stacked on one sheet (for printing a batch in one job). */
fun stickerSheet(stickers: List<Bitmap>, columns: Int = 2): Bitmap {
    val w = stickers.first().width
    val h = stickers.first().height
    val gap = 24
    val rows = (stickers.size + columns - 1) / columns
    val sheet = Bitmap.createBitmap(columns * w + (columns + 1) * gap, rows * h + (rows + 1) * gap, Bitmap.Config.ARGB_8888)
    val c = Canvas(sheet)
    c.drawColor(Color.WHITE)
    stickers.forEachIndexed { i, s -> c.drawBitmap(s, (gap + (i % columns) * (w + gap)).toFloat(), (gap + (i / columns) * (h + gap)).toFloat(), null) }
    return sheet
}
