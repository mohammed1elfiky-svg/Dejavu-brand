package com.dejavu.core.admin.print

import java.io.ByteArrayOutputStream

/**
 * A 1-bit image ready for a thermal printer: [black] holds width × height pixels, row by row.
 * Text (Arabic included) and barcodes are drawn into a picture first and sent as raster,
 * so receipts and labels look the same on every printer, whatever its code pages or fonts.
 */
class MonoImage(val width: Int, val height: Int, val black: BooleanArray) {
    init {
        require(width > 0 && height > 0 && black.size == width * height) { "Image size mismatch" }
    }

    /** Bytes per printed row: 8 dots per byte, padded on the right. */
    val bytesPerRow: Int get() = (width + 7) / 8

    operator fun get(x: Int, y: Int) = black[y * width + x]

    /** Packed rows, most significant bit first; a set bit is a black dot (ESC/POS, ZPL). */
    fun packed(blackIsOne: Boolean = true): ByteArray {
        val out = ByteArray(bytesPerRow * height)
        for (y in 0 until height) {
            for (byteX in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = byteX * 8 + bit
                    val dot = x < width && this[x, y]
                    if (dot == blackIsOne) b = b or (0x80 ushr bit)
                }
                out[y * bytesPerRow + byteX] = b.toByte()
            }
        }
        return out
    }

    /** Rows [from] until [to] as a new image. */
    fun band(from: Int, to: Int) = MonoImage(width, to - from, black.copyOfRange(from * width, to * width))

    companion object {
        /** From ARGB pixels: dark, opaque pixels become black dots. */
        fun fromArgb(width: Int, height: Int, argb: IntArray, threshold: Int = 160): MonoImage {
            val black = BooleanArray(width * height) { i ->
                val p = argb[i]
                val alpha = p ushr 24 and 0xFF
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                alpha > 127 && (r * 299 + g * 587 + b * 114) / 1000 < threshold
            }
            return MonoImage(width, height, black)
        }
    }
}

/** ESC/POS receipt printers (Xprinter, Epson, Rongta, Sunmi…). */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D

    /** Rows per raster command: small enough for the input buffer of cheap printers. */
    private const val BAND = 128

    /** Prints [image] (as wide as the paper: 384 dots for 58 mm, 576 for 80 mm), feeds and cuts. */
    fun receipt(image: MonoImage, cut: Boolean = true, openDrawer: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte())) // initialise
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1)) // centre
        raster(out, image)
        out.write(byteArrayOf(ESC.toByte(), 'd'.code.toByte(), 4)) // feed 4 lines past the cutter
        if (cut) out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)) // partial cut
        if (openDrawer) out.write(byteArrayOf(ESC.toByte(), 'p'.code.toByte(), 0, 25, 250.toByte()))
        return out.toByteArray()
    }

    /** Labels on an ESC/POS label printer: each picture, then "feed to the next label". */
    fun labels(image: MonoImage, copies: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        repeat(copies.coerceAtLeast(1)) {
            raster(out, image)
            out.write(byteArrayOf(GS.toByte(), 0x0C)) // GS FF: feed to the start of the next label
        }
        return out.toByteArray()
    }

    /** GS v 0: raster bit image, in bands. */
    fun raster(out: ByteArrayOutputStream, image: MonoImage) {
        var y = 0
        while (y < image.height) {
            val band = image.band(y, minOf(image.height, y + BAND))
            val w = band.bytesPerRow
            val h = band.height
            out.write(byteArrayOf(GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0, (w and 0xFF).toByte(), (w shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
            out.write(band.packed(blackIsOne = true))
            y += BAND
        }
    }
}

/** TSPL: TSC and most Xprinter / Gprinter label printers. */
object Tspl {
    /** [image] fills a label of [widthMm] × [heightMm] with a [gapMm] gap; prints [copies]. */
    fun labels(image: MonoImage, widthMm: Int, heightMm: Int, gapMm: Int, copies: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write("$s\r\n".toByteArray(Charsets.US_ASCII))
        line("SIZE $widthMm mm,$heightMm mm")
        line("GAP $gapMm mm,0 mm")
        line("DIRECTION 1,0")
        line("REFERENCE 0,0")
        line("CLS")
        out.write("BITMAP 0,0,${image.bytesPerRow},${image.height},0,".toByteArray(Charsets.US_ASCII))
        // TSPL bitmaps print a dot where the bit is 0.
        out.write(image.packed(blackIsOne = false))
        line("")
        line("PRINT 1,${copies.coerceAtLeast(1)}")
        return out.toByteArray()
    }
}

/** ZPL II: Zebra (and Zebra-compatible) label printers. */
object Zpl {
    fun labels(image: MonoImage, copies: Int): ByteArray {
        val bytes = image.packed(blackIsOne = true)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) hex.append(HEX[b.toInt() shr 4 and 0xF]).append(HEX[b.toInt() and 0xF])
        val zpl = buildString {
            append("^XA")
            append("^PW${image.width}")
            append("^LL${image.height}")
            append("^FO0,0^GFA,${bytes.size},${bytes.size},${image.bytesPerRow},").append(hex).append("^FS")
            append("^PQ${copies.coerceAtLeast(1)}")
            append("^XZ")
        }
        return zpl.toByteArray(Charsets.US_ASCII)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
