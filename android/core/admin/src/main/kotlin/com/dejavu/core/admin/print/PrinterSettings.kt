package com.dejavu.core.admin.print

import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.net.Socket

@Serializable
enum class PrinterLink(val label: String) { BLUETOOTH("بلوتوث"), NETWORK("شبكة / Wi-Fi") }

/** Command language of a label (barcode) printer. */
@Serializable
enum class LabelLanguage(val label: String) { TSPL("TSPL (TSC / Xprinter)"), ZPL("ZPL (Zebra)"), ESC("ESC/POS") }

/** Where a printer is and how to reach it. */
@Serializable
data class PrinterTarget(
    val link: PrinterLink = PrinterLink.BLUETOOTH,
    /** Paired Bluetooth printer (MAC address) and its name. */
    val bluetoothAddress: String = "",
    val bluetoothName: String = "",
    /** Network printer: IP or host name and raw port (9100 on almost all printers). */
    val host: String = "",
    val port: Int = 9100,
) {
    val isSet: Boolean get() = if (link == PrinterLink.BLUETOOTH) bluetoothAddress.isNotBlank() else host.isNotBlank() && port in 1..65535
    val title: String get() = if (link == PrinterLink.BLUETOOTH) bluetoothName.ifBlank { bluetoothAddress } else "$host:$port"
}

@Serializable
data class ReceiptPrinterSettings(
    val target: PrinterTarget = PrinterTarget(),
    /** Paper roll width: 58 or 80 mm. */
    val paperMm: Int = 80,
    /** Print the receipt as soon as a sale is completed at the till. */
    val autoPrint: Boolean = true,
    val cut: Boolean = true,
) {
    /** Printable dots across the paper at 203 dpi. */
    val dots: Int get() = if (paperMm <= 58) 384 else 576
}

@Serializable
data class LabelPrinterSettings(
    val target: PrinterTarget = PrinterTarget(),
    val language: LabelLanguage = LabelLanguage.TSPL,
    val widthMm: Int = 50,
    val heightMm: Int = 30,
    val gapMm: Int = 2,
    /** Print head resolution: 203 or 300 dpi. */
    val dpi: Int = 203,
) {
    /** Label size in dots; the width is a whole number of bytes. */
    val widthDots: Int get() = (widthMm * dpi / 25.4).toInt() / 8 * 8
    val heightDots: Int get() = (heightMm * dpi / 25.4).toInt()

    /** Commands for [copies] labels of [image] (already [widthDots] × [heightDots]). */
    fun commands(image: MonoImage, copies: Int): ByteArray = when (language) {
        LabelLanguage.TSPL -> Tspl.labels(image, widthMm, heightMm, gapMm, copies)
        LabelLanguage.ZPL -> Zpl.labels(image, copies)
        LabelLanguage.ESC -> EscPos.labels(image, copies)
    }
}

@Serializable
data class PrinterSettings(
    val receipt: ReceiptPrinterSettings = ReceiptPrinterSettings(),
    val label: LabelPrinterSettings = LabelPrinterSettings(),
)

/** Raw TCP printing (port 9100 / "JetDirect"), used by Wi-Fi and Ethernet printers. */
object NetworkPrinter {
    fun send(host: String, port: Int, data: ByteArray, timeoutMs: Int = 8000) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host.trim(), port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(data)
                flush()
            }
            // Give the printer a moment to take the job before the connection closes.
            Thread.sleep(300)
        }
    }
}
