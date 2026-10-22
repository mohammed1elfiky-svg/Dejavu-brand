@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.products

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.barcode.StickerData
import com.dejavu.admin.ui.barcode.stickerBitmap
import com.dejavu.admin.ui.barcode.stickerSheet
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.ImageUrl
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.media.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** The API keeps at most this many photos per product. */
internal const val MAX_IMAGES = 10

/** Most stickers built into one print sheet (keeps the bitmap a sane size). */
private const val MAX_STICKERS = 60

// ------------------------------------------------------------------ gallery ---

/**
 * Product photos: add from the gallery or camera ([onPicked] gets the JPEG to upload),
 * reorder, delete, and tag each photo with one of [colors] so the customer app shows it
 * when that color is chosen.
 */
@Composable
fun ProductGallery(
    images: List<ImageUrl>,
    colors: List<String>,
    uploading: Boolean,
    onChange: (List<ImageUrl>) -> Unit,
    onPicked: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberImagePicker { bytes -> onPicked(bytes) }
    var tagging by remember { mutableStateOf<Int?>(null) }
    val full = images.size >= MAX_IMAGES

    fun move(from: Int, to: Int) {
        if (to !in images.indices) return
        val list = images.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        onChange(list)
    }

    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("صور المنتج", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            GoldPill { Text("${images.size}/$MAX_IMAGES", color = Color.White, style = MaterialTheme.typography.labelLarge) }
        }
        Text(
            "الصورة الأولى هي الرئيسية. اضغط على الصورة لربطها بلون، فيعرضها تطبيق العملاء عند اختيار ذلك اللون.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(images, key = { i, img -> "$i|${img.url}" }) { i, img ->
                    GalleryTile(
                        image = img,
                        main = i == 0,
                        canEarlier = i > 0,
                        canLater = i < images.lastIndex,
                        onTag = { tagging = i },
                        onEarlier = { move(i, i - 1) },
                        onLater = { move(i, i + 1) },
                        onDelete = { onChange(images.filterIndexed { j, _ -> j != i }) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (uploading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("جارٍ رفع الصورة…", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = picker.fromGallery, enabled = !full && !uploading, modifier = Modifier.weight(1f)) { Text("من المعرض") }
            OutlinedButton(onClick = picker.fromCamera, enabled = !full && !uploading, modifier = Modifier.weight(1f)) { Text("الكاميرا") }
        }
        if (full) Text("وصلت للحد الأقصى ($MAX_IMAGES صور)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    val taggedIndex = tagging
    val tagged = taggedIndex?.let { images.getOrNull(it) }
    if (taggedIndex != null && tagged != null) {
        ColorTagDialog(tagged.color, colors, onDismiss = { tagging = null }) { color ->
            onChange(images.mapIndexed { j, x -> if (j == taggedIndex) x.copy(color = color) else x })
        }
    }
}

@Composable
private fun GalleryTile(
    image: ImageUrl,
    main: Boolean,
    canEarlier: Boolean,
    canLater: Boolean,
    onTag: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.width(124.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(124.dp).clip(RoundedCornerShape(18.dp)).background(Lux.Champagne).clickable(onClick = onTag)) {
            AsyncImage(model = image.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (main) {
                GoldPill(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Text("الرئيسية", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            image.color ?: "عام",
            style = MaterialTheme.typography.labelMedium,
            color = if (image.color != null) Lux.GoldDeep else Lux.InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEarlier, enabled = canEarlier, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "تقديم")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onLater, enabled = canLater, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "تأخير")
            }
        }
    }
}

@Composable
private fun ColorTagDialog(current: String?, colors: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("لون الصورة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = current == null, onClick = { onDismiss(); onPick(null) }, label = { Text("عام / بدون لون") })
                    colors.forEach { c ->
                        FilterChip(selected = current == c, onClick = { onDismiss(); onPick(c) }, label = { Text(c) })
                    }
                }
                if (colors.isEmpty()) {
                    Text("لا توجد ألوان للمنتج بعد؛ أضف الألوان في المقاسات والألوان.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

// -------------------------------------------------------------------- video ---

/** The API accepts videos up to this size. */
internal const val MAX_VIDEO_BYTES = 30 * 1024 * 1024

private val VIDEO_TYPES = setOf("video/mp4", "video/webm", "video/3gpp", "video/quicktime")

/** True for an http(s) link. */
internal fun isWebLink(v: String): Boolean {
    val t = v.trim().lowercase()
    return (t.startsWith("https://") || t.startsWith("http://")) && t.length > 10 && t.none { it.isWhitespace() }
}

/** Opens [url] as a video in the device's player (or browser). */
fun openVideo(context: Context, url: String) {
    val ok = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) openLink(context, url)
}

/** Opens [url] in the browser (or the app that handles it, e.g. Instagram). */
fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Reads at most [limit] + 1 bytes of [uri]; null when it cannot be read. */
private fun readCapped(context: Context, uri: Uri, limit: Int): ByteArray? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > limit) break
        }
        out.toByteArray()
    }
}.getOrNull()

/**
 * "فيديو الصنف": pick one video from the gallery (MP4/WebM/3GP/MOV ≤ 30 MB), [onPicked] gets
 * the bytes and MIME type to upload; once attached it can be played or removed.
 */
@Composable
fun ProductVideoCard(
    videoUrl: String?,
    uploading: Boolean,
    onPicked: (bytes: ByteArray, mimeType: String) -> Unit,
    onError: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: "video/mp4"
        if (mime !in VIDEO_TYPES) {
            onError("صيغة الفيديو غير مدعومة؛ استخدم MP4 أو WebM أو 3GP أو MOV")
            return@rememberLauncherForActivityResult
        }
        reading = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readCapped(context, uri, MAX_VIDEO_BYTES) }
            reading = false
            when {
                bytes == null || bytes.isEmpty() -> onError("تعذّرت قراءة الفيديو")
                bytes.size > MAX_VIDEO_BYTES -> onError("حجم الفيديو أكبر من 30 ميغابايت")
                else -> onPicked(bytes, mime)
            }
        }
    }
    val busy = uploading || reading

    GlassCard(modifier.fillMaxWidth()) {
        Text("فيديو الصنف", style = MaterialTheme.typography.titleSmall)
        Text(
            "فيديو واحد قصير (MP4 أو WebM أو 3GP أو MOV) بحجم 30 ميغابايت كحد أقصى.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (reading) "جارٍ تجهيز الفيديو…" else "جارٍ رفع الفيديو…", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            Spacer(Modifier.height(8.dp))
        }
        if (videoUrl != null && !busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تم إرفاق فيديو", Modifier.weight(1f), color = Lux.GoldDeep, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { openVideo(context, videoUrl) }) { Text("تشغيل") }
                TextButton(onClick = onRemove) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        }
        OutlinedButton(
            onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (videoUrl == null) "اختيار فيديو من المعرض" else "استبدال الفيديو") }
    }
}

// ----------------------------------------------------------------- stickers ---

/**
 * Preview of the barcode label(s) with a copies count, then print (Android print
 * service) or share the PNG.
 */
@Composable
fun StickerDialog(title: String, stickers: List<StickerData>, onDismiss: () -> Unit) {
    if (stickers.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copies by rememberSaveable { mutableStateOf(1) }
    var working by remember { mutableStateOf(false) }
    val maxCopies = (MAX_STICKERS / stickers.size).coerceIn(1, 50)
    val preview = remember(stickers) { stickerBitmap(stickers.first()).asImageBitmap() }
    val total = stickers.size * copies

    fun build(then: (Bitmap) -> Unit) {
        if (working) return
        working = true
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { runCatching { stickerPrintBitmap(stickers, copies) }.getOrNull() }
            working = false
            if (bmp != null) runCatching { then(bmp) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    Image(preview, contentDescription = "معاينة الملصق", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
                if (stickers.size > 1) {
                    Text(
                        "${stickers.size} ملصقات: " + stickers.joinToString("، ") { it.variant ?: it.sku },
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (stickers.size > 1) "عدد النسخ لكل مقاس" else "عدد النسخ", Modifier.weight(1f))
                    QuantityStepper(value = copies, onChange = { copies = it.coerceIn(1, maxCopies) }, max = maxCopies)
                }
                Text("الإجمالي: $total ملصق · المقاس 50×30 مم", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                build { bmp ->
                    PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap("dejavu-stickers", bmp)
                }
            }) { Text("طباعة") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !working, onClick = { build { bmp -> scope.launch { shareSticker(context, bmp) } } }) { Text("مشاركة") }
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        },
    )
}

/** One sticker as is; several (each repeated [copies] times) laid out on one sheet. */
private fun stickerPrintBitmap(stickers: List<StickerData>, copies: Int): Bitmap {
    val singles = stickers.map { stickerBitmap(it) }
    val all = singles.flatMap { b -> List(copies) { b } }.take(MAX_STICKERS)
    return if (all.size == 1) all.first() else stickerSheet(all, columns = if (all.size <= 4) 2 else 3)
}

/** Writes the PNG under cache/photos (shared through the app's FileProvider) and opens the share sheet. */
private suspend fun shareSticker(context: Context, bitmap: Bitmap) {
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "photos").apply { mkdirs() }
            File(dir, "sticker.png").also { f -> FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }.getOrNull()
    } ?: return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "مشاركة الملصق"))
    }
}
