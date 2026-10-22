package com.dejavu.core.admin.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class PrinterCommandsTest {
    /** 10×2 image: first row dots at x = 0 and 9, second row all white. */
    private val image = MonoImage(10, 2, BooleanArray(20) { it == 0 || it == 9 })

    @Test
    fun `packs rows to whole bytes, most significant bit first`() {
        assertEquals(2, image.bytesPerRow)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x40, 0, 0), image.packed())
        // TSPL wants the opposite: 0 = black.
        assertArrayEquals(byteArrayOf(0x7F, 0xBF.toByte(), 0xFF.toByte(), 0xFF.toByte()), image.packed(blackIsOne = false))
    }

    @Test
    fun `turns dark opaque pixels into dots`() {
        val px = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0x00000000, 0xFF404040.toInt())
        val m = MonoImage.fromArgb(4, 1, px)
        assertEquals(listOf(true, false, false, true), m.black.toList())
    }

    @Test
    fun `ESC POS receipt initialises, sends raster with its size, feeds and cuts`() {
        val bytes = EscPos.receipt(image)
        assertArrayEquals(byteArrayOf(0x1B, '@'.code.toByte()), bytes.copyOfRange(0, 2))
        val header = byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0, 2, 0, 2, 0)
        assertEquals(5, indexOf(bytes, header))
        assertArrayEquals(byteArrayOf(0x1D, 'V'.code.toByte(), 66, 0), bytes.copyOfRange(bytes.size - 4, bytes.size))
    }

    @Test
    fun `ESC POS splits tall images into bands`() {
        val tall = MonoImage(8, 300, BooleanArray(2400))
        val bytes = EscPos.receipt(tall, cut = false)
        val bands = (0 until bytes.size - 2).count { bytes[it] == 0x1D.toByte() && bytes[it + 1] == 'v'.code.toByte() && bytes[it + 2] == '0'.code.toByte() }
        assertEquals(3, bands) // 128 + 128 + 44 rows
    }

    @Test
    fun `TSPL label sets size and gap, sends the bitmap and prints the copies`() {
        val text = String(Tspl.labels(image, 50, 30, 2, 3), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("SIZE 50 mm,30 mm\r\nGAP 2 mm,0 mm\r\n"))
        assertTrue(text.contains("CLS\r\nBITMAP 0,0,2,2,0,"))
        assertTrue(text.endsWith("PRINT 1,3\r\n"))
    }

    @Test
    fun `ZPL label embeds the image as hex graphic field`() {
        val zpl = String(Zpl.labels(image, 2), Charsets.US_ASCII)
        assertEquals("^XA^PW10^LL2^FO0,0^GFA,4,4,2,80400000^FS^PQ2^XZ", zpl)
    }

    @Test
    fun `label size in dots follows the print head`() {
        val s = LabelPrinterSettings(widthMm = 50, heightMm = 30, dpi = 203)
        assertEquals(392, s.widthDots)
        assertEquals(239, s.heightDots)
        assertEquals(576, ReceiptPrinterSettings(paperMm = 80).dots)
        assertEquals(384, ReceiptPrinterSettings(paperMm = 58).dots)
    }

    @Test
    fun `network printer delivers the bytes over raw TCP`() {
        ServerSocket(0).use { server ->
            var received = ByteArray(0)
            val t = thread { server.accept().use { received = it.getInputStream().readBytes() } }
            NetworkPrinter.send("127.0.0.1", server.localPort, byteArrayOf(1, 2, 3))
            t.join(5000)
            assertArrayEquals(byteArrayOf(1, 2, 3), received)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
