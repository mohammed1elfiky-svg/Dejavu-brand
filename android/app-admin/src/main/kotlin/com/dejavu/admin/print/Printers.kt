package com.dejavu.admin.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.dejavu.core.admin.PosReceipt
import com.dejavu.core.admin.print.EscPos
import com.dejavu.core.admin.print.MonoImage
import com.dejavu.core.admin.print.NetworkPrinter
import com.dejavu.core.admin.print.PrinterLink
import com.dejavu.core.admin.print.PrinterSettings
import com.dejavu.core.admin.print.PrinterTarget
import com.dejavu.admin.ui.barcode.StickerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/** Receipt and label printer settings, kept on this device. */
class PrinterStore(context: Context) {
    private val prefs = context.getSharedPreferences("printers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    val settings = _settings.asStateFlow()

    private fun load(): PrinterSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(PrinterSettings.serializer(), it) }.getOrNull() } ?: PrinterSettings()

    fun update(change: (PrinterSettings) -> PrinterSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit { putString(KEY, json.encodeToString(PrinterSettings.serializer(), next)) }
    }

    private companion object {
        const val KEY = "settings"
    }
}

/** A paired Bluetooth device the user can pick as a printer. */
data class PairedPrinter(val name: String, val address: String)

object Printers {
    /** Serial Port Profile: what Bluetooth thermal printers speak. */
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** Runtime permission Bluetooth printing needs (Android 12+), or null when none is needed. */
    val bluetoothPermission: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun canUseBluetooth(context: Context): Boolean =
        bluetoothPermission == null || ContextCompat.checkSelfPermission(context, bluetoothPermission) == PackageManager.PERMISSION_GRANTED

    /** Bluetooth devices paired in the phone's settings (printers must be paired there first). */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<PairedPrinter> {
        if (!canUseBluetooth(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty().map { PairedPrinter(it.name ?: it.address, it.address) } }.getOrDefault(emptyList()).sortedBy { it.name }
    }

    /** Sends raw printer commands to [target]. */
    suspend fun send(context: Context, target: PrinterTarget, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(target.isSet) { "لم يتم إعداد الطابعة بعد" }
            when (target.link) {
                PrinterLink.NETWORK -> NetworkPrinter.send(target.host, target.port, data)
                PrinterLink.BLUETOOTH -> sendBluetooth(context, target.bluetoothAddress, data)
            }
        }.recoverCatching { e ->
            throw IllegalStateException(printerError(target, e), e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetooth(context: Context, address: String, data: ByteArray) {
        check(canUseBluetooth(context)) { "اسمح للتطبيق باستخدام البلوتوث" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("هذا الجهاز لا يدعم البلوتوث")
        check(adapter.isEnabled) { "شغّل البلوتوث أولاً" }
        runCatching { adapter.cancelDiscovery() }
        val device = adapter.getRemoteDevice(address)
        val socket = device.createRfcommSocketToServiceRecord(SPP)
        socket.use { s ->
            s.connect()
            val out = s.outputStream
            // Small chunks: cheap printers drop data when their buffer overflows.
            var i = 0
            while (i < data.size) {
                val n = minOf(1024, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
                Thread.sleep(15)
            }
            Thread.sleep(400)
        }
    }

    private fun printerError(target: PrinterTarget, e: Throwable): String {
        val known = (e as? IllegalStateException)?.message
        if (known != null && e.cause == null && known.any { it in 'ء'..'ي' }) return known
        return if (target.link == PrinterLink.NETWORK) {
            "تعذّر الاتصال بالطابعة ${target.title}. تأكد أنها تعمل وعلى نفس الشبكة."
        } else {
            "تعذّر الاتصال بطابعة البلوتوث ${target.title}. تأكد أنها تعمل وقريبة ومقترنة بالهاتف."
        }
    }

    // ----------------------------------------------------------------- jobs ---

    suspend fun printReceipt(context: Context, settings: PrinterSettings, receipt: PosReceipt): Result<Unit> {
        val r = settings.receipt
        val data = withContext(Dispatchers.Default) { EscPos.receipt(receiptBitmap(receipt, r.dots).toMono(), cut = r.cut) }
        return send(context, r.target, data)
    }

    suspend fun printLabels(context: Context, settings: PrinterSettings, stickers: List<StickerData>, copies: Int): Result<Unit> {
        val l = settings.label
        val data = withContext(Dispatchers.Default) {
            stickers.map { l.commands(labelBitmap(it, l.widthDots, l.heightDots).toMono(), copies) }
                .fold(ByteArray(0)) { acc, b -> acc + b }
        }
        return send(context, l.target, data)
    }

    /**
     * The parcel label: on the receipt printer when one is set (80 / 58 mm roll), otherwise on
     * the label printer scaled to its label.
     */
    suspend fun printShippingLabel(context: Context, settings: PrinterSettings, label: ShippingLabel): Result<Unit> {
        val r = settings.receipt
        if (r.target.isSet) {
            val data = withContext(Dispatchers.Default) { EscPos.receipt(shippingLabelBitmap(label, r.dots).toMono(), cut = r.cut) }
            return send(context, r.target, data)
        }
        val l = settings.label
        val data = withContext(Dispatchers.Default) {
            val art = shippingLabelBitmap(label, 576)
            val scale = minOf(l.widthDots / art.width.toFloat(), l.heightDots / art.height.toFloat())
            val w = (art.width * scale).toInt().coerceAtLeast(1)
            val h = (art.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(l.widthDots, l.heightDots, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(Bitmap.createScaledBitmap(art, w, h, true), (l.widthDots - w) / 2f, (l.heightDots - h) / 2f, null)
            }
            l.commands(out.toMono(), 1)
        }
        return send(context, l.target, data)
    }

    suspend fun testReceipt(context: Context, settings: PrinterSettings): Result<Unit> {
        val r = settings.receipt
        val data = withContext(Dispatchers.Default) { EscPos.receipt(testBitmap(r.dots, "طابعة الفواتير تعمل ✓", "${r.paperMm} مم").toMono(), cut = r.cut) }
        return send(context, r.target, data)
    }

    suspend fun testLabel(context: Context, settings: PrinterSettings): Result<Unit> {
        val l = settings.label
        val sample = StickerData("100-1", "ملصق تجريبي", "M · أسود", "99.00 ر.س", null)
        val data = withContext(Dispatchers.Default) { l.commands(labelBitmap(sample, l.widthDots, l.heightDots).toMono(), 1) }
        return send(context, l.target, data)
    }
}

/** Black-and-white dots for the printer. */
fun Bitmap.toMono(): MonoImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return MonoImage.fromArgb(width, height, px)
}
