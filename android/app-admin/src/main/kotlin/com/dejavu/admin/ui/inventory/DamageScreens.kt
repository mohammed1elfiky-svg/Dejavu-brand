@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.inventory

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.InfoCard
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminContainer
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.layaway.PiecePickerDialog
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.DamageItem
import com.dejavu.core.admin.ManagementRepository
import com.dejavu.core.admin.Perm
import com.dejavu.core.admin.PosProduct
import com.dejavu.core.admin.PosVariant
import com.dejavu.core.admin.PurchasingRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.StockDamage
import com.dejavu.core.admin.StockDamagePage
import com.dejavu.core.admin.StockDamageRequest
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.QuantityStepper
import com.dejavu.core.ui.SummaryRow
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PHOTOS = 6

/** A piece being written off. */
data class DamageLine(val variantId: String, val name: String, val option: String, val sku: String, val imageUrl: String?, val available: Int, val qty: Int)

/** A photo of the damage: shown from [bytes] at once, [url] ("admin/files/<id>") once uploaded. */
class DamagePhoto(val key: Long, val bytes: ByteArray) {
    var url by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
}

class DamagesViewModel(private val repo: ManagementRepository, private val files: PurchasingRepository) : BaseViewModel() {
    private val _page = MutableStateFlow<Load<StockDamagePage>>(Load.Loading)
    val page = _page.asStateFlow()
    val refresher = Refresher()

    var lines by mutableStateOf<List<DamageLine>>(emptyList())
        private set
    var photos by mutableStateOf<List<DamagePhoto>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    private var nextKey = 0L

    val uploading: Boolean get() = photos.any { it.url == null && !it.failed }

    fun load() {
        viewModelScope.launch {
            val r = repo.damages()
            if (_page.value is Load.Ready) r.fold({ _page.value = Load.Ready(it) }, { say(it.userMessage()) }) else _page.value = r.toLoad()
        }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.damages().fold({ _page.value = Load.Ready(it) }, { say(it.userMessage()) })
    }

    fun add(p: PosProduct, v: PosVariant) {
        val have = lines.firstOrNull { it.variantId == v.id }
        lines = if (have == null) {
            listOf(DamageLine(v.id, p.nameAr, v.title, v.sku, v.imageUrl ?: p.imageUrl, v.available, 1)) + lines
        } else {
            lines.map { if (it.variantId == v.id) it.copy(qty = it.qty + 1) else it }
        }
    }

    fun setQty(variantId: String, qty: Int) {
        lines = lines.map { if (it.variantId == variantId) it.copy(qty = qty.coerceAtLeast(1)) else it }
    }

    fun remove(variantId: String) {
        lines = lines.filterNot { it.variantId == variantId }
    }

    fun addPhoto(bytes: ByteArray) {
        if (photos.size >= MAX_PHOTOS) return say("الحد الأقصى $MAX_PHOTOS صور")
        val photo = DamagePhoto(nextKey++, bytes)
        photos = photos + photo
        upload(photo)
    }

    private fun upload(photo: DamagePhoto) {
        photo.failed = false
        viewModelScope.launch {
            files.uploadInvoiceImage(photo.bytes).fold(
                { photo.url = it.url },
                { e ->
                    photo.failed = true
                    say(e.userMessage())
                },
            )
        }
    }

    fun retry(photo: DamagePhoto) = upload(photo)

    fun removePhoto(photo: DamagePhoto) {
        photos = photos.filterNot { it.key == photo.key }
    }

    fun discard() {
        lines = emptyList()
        photos = emptyList()
    }

    fun submit(note: String, done: () -> Unit) {
        if (lines.isEmpty()) return say("أضف القطع التالفة")
        if (note.trim().length < 3) return say("اكتب سبب التلف")
        if (uploading) return say("انتظر حتى تكتمل الصور")
        if (busy) return
        busy = true
        viewModelScope.launch {
            repo.writeOffDamage(
                StockDamageRequest(
                    items = lines.map { DamageItem(it.variantId, it.qty) },
                    note = note.trim(),
                    photoUrls = photos.mapNotNull { it.url },
                ),
            ).orSay()?.let { d ->
                say("سُجّل التالف ${d.number} بقيمة ${Money.formatBase(d.totalValue)}")
                discard()
                load()
                done()
            }
            busy = false
        }
    }
}

/** Damaged goods: write pieces off with photos and a reason; the list of past write-offs. */
@Composable
fun DamagesScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { DamagesViewModel(it.management, it.purchasing) }
    val snackbar = remember { SnackbarHostState() }
    var writing by rememberSaveable { mutableStateOf(false) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }
    val canWrite = session.can(Perm.STOCK_ADJUST)
    if (writing && canWrite) {
        DamageForm(vm, snackbar, onBack = { writing = false })
    } else {
        DamageList(vm, snackbar, canWrite, onBack, onNew = { writing = true })
    }
}

@Composable
private fun DamageList(vm: DamagesViewModel, snackbar: SnackbarHostState, canWrite: Boolean, onBack: () -> Unit, onNew: () -> Unit) {
    val state by vm.page.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<String?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("البضاعة التالفة", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canWrite) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("تسجيل تالف") },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { page ->
            RefreshList(refreshing, vm::refresh, Modifier.padding(padding)) {
                if (page.items.isEmpty()) {
                    EmptyList(
                        Icons.Filled.Warning,
                        "لا توجد بضاعة تالفة",
                        "سجّل القطع التالفة أو المكسورة مع صورها وسببها، فتُخصم من المخزون وتُقيَّد خسارتها",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            InfoCard {
                                SummaryRow("إجمالي قيمة التالف", Money.formatBase(page.totalValue), strong = true, valueColor = MaterialTheme.colorScheme.error)
                                Text("${page.total} عملية", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                        }
                        items(page.items, key = { it.id }) { d -> DamageCard(d) { viewing = it } }
                    }
                }
            }
        }
    }
    viewing?.let { url -> PhotoViewer(url) { viewing = null } }
}

@Composable
private fun DamageCard(d: StockDamage, onPhoto: (String) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.number, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(formatDateTime(d.createdAt), d.createdBy).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            Text(Money.formatBase(d.totalValue), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        }
        d.items.forEach { i ->
            Text(
                "• ${i.name.ifBlank { i.sku }}${listOfNotNull(i.color, i.size).joinToString(" · ").let { if (it.isBlank()) "" else " ($it)" }} × ${i.quantity}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 4.dp)) }
        if (d.photoUrls.isNotEmpty()) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.photoUrls.forEach { url ->
                    PrivatePhoto(url, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).clickable { onPhoto(url) })
                }
            }
        }
    }
}

private fun decode(bytes: ByteArray, sample: Int): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()

/** A private file ("admin/files/<id>") read with the staff token. */
@Composable
private fun PrivatePhoto(url: String, modifier: Modifier, sample: Int = 4) {
    val files = adminContainer().purchasing
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val id = url.trimEnd('/').substringAfterLast('/')
        val bytes = files.file(id).getOrNull()
        bitmap = bytes?.let { withContext(Dispatchers.Default) { decode(it, sample) } }
        failed = bitmap == null
    }
    Box(modifier.background(Lux.Champagne), contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b != null -> Image(b, contentDescription = "صورة التالف", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = Lux.InkSoft, modifier = Modifier.size(18.dp))
            else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PhotoViewer(url: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { PrivatePhoto(url, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)), sample = 1) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

@Composable
private fun DamageForm(vm: DamagesViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val picker = rememberImagePicker { bytes -> vm.addPhoto(bytes) }
    fun leave() {
        if (vm.lines.isEmpty() && vm.photos.isEmpty()) onBack() else confirmDiscard = true
    }
    BackHandler { leave() }
    val totalValue = vm.lines.sumOf { it.qty }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("تسجيل تالف", { leave() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("القطع التالفة ($totalValue)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text("+ إضافة قطعة") }
                }
            }
            if (vm.lines.isEmpty()) {
                item { Text("ابحث بالاسم أو امسح الباركود، ثم اختر المقاس واللون", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
            }
            items(vm.lines, key = { it.variantId }) { l ->
                GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProductImage(l.imageUrl, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${l.option} · ${l.sku}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                        IconButton(onClick = { vm.remove(l.variantId) }) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("في المخزون ${l.available}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.weight(1f))
                        QuantityStepper(l.qty, { vm.setQty(l.variantId, it) }, max = maxOf(l.available, 1))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("سبب التلف") },
                    placeholder = { Text("مثال: تمزق أثناء العرض، بقعة لا تزول…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            item {
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الصور (${vm.photos.size}/$MAX_PHOTOS)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = picker.fromCamera, enabled = vm.photos.size < MAX_PHOTOS) { Text("تصوير") }
                        TextButton(onClick = picker.fromGallery, enabled = vm.photos.size < MAX_PHOTOS) { Text("من المعرض") }
                    }
                    if (vm.photos.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vm.photos.forEach { p -> PhotoThumb(p, onRetry = { vm.retry(p) }, onRemove = { vm.removePhoto(p) }) }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { vm.submit(note) { onBack() } },
                    enabled = !vm.busy && !vm.uploading && vm.lines.isNotEmpty() && note.trim().length >= 3,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (vm.uploading) "جارٍ رفع الصور…" else "خصم من المخزون وتسجيل التالف") }
            }
            item {
                OutlinedButton(onClick = { leave() }, modifier = Modifier.fillMaxWidth()) { Text("تراجع") }
            }
        }
    }
    if (picking) PiecePickerDialog("إضافة قطعة تالفة", onDismiss = { picking = false }, requireStock = false) { p, v -> vm.add(p, v) }
    if (confirmDiscard) {
        ConfirmDialog("تجاهل التسجيل؟", "لن تُحفظ القطع والصور المضافة.", "تجاهل", onDismiss = { confirmDiscard = false }) {
            vm.discard()
            onBack()
        }
    }
}

@Composable
private fun PhotoThumb(p: DamagePhoto, onRetry: () -> Unit, onRemove: () -> Unit) {
    val bitmap = remember(p.key) { decode(p.bytes, 4) }
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).background(Lux.Champagne), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = "صورة", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        when {
            p.failed -> TextButton(onClick = onRetry) { Text("إعادة", color = MaterialTheme.colorScheme.error) }
            p.url == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(3.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = "إزالة", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}
