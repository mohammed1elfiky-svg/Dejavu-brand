@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.MembersRepository
import com.dejavu.core.data.SocialRepository
import com.dejavu.core.model.Money
import com.dejavu.core.model.NewPostRequest
import com.dejavu.core.model.NewPostTag
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.media.rememberMultiImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A product pinned on photo [imageIndex] at ([x], [y]), both 0..1. */
data class DraftTag(val product: StoreProduct, val imageIndex: Int, val x: Double, val y: Double)

data class DraftPost(
    val kind: String = "LOOK",
    val caption: String = "",
    val images: List<String> = emptyList(),
    val uploading: Int = 0,
    val tags: List<DraftTag> = emptyList(),
    val posting: Boolean = false,
    /** null while unknown; "" = the customer has no username yet. */
    val username: String? = null,
)

const val MAX_POST_IMAGES = 10
private const val WISH_CAPTION = "أمنيات عيد ميلادي هذا العام 🎂"

class CreatePostViewModel(private val social: SocialRepository, private val members: MembersRepository, private val catalog: CatalogRepository) : BaseViewModel() {
    private val _draft = MutableStateFlow(DraftPost())
    val draft = _draft.asStateFlow()
    private val _done = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init {
        viewModelScope.launch { members.profile().onSuccess { p -> _draft.value = _draft.value.copy(username = p.username.orEmpty()) } }
    }

    private fun edit(change: (DraftPost) -> DraftPost) {
        _draft.value = change(_draft.value)
    }

    fun setKind(kind: String) = edit { d -> d.copy(kind = kind, caption = if (kind == "WISHLIST" && d.caption.isBlank()) WISH_CAPTION else d.caption) }
    fun setCaption(text: String) = edit { it.copy(caption = text.take(1000)) }

    /** Uploads the photos one after the other and adds them to the post. */
    fun upload(photos: List<ByteArray>) {
        for (bytes in photos) {
            if (_draft.value.images.size + _draft.value.uploading >= MAX_POST_IMAGES) return say("الحد الأقصى $MAX_POST_IMAGES صور")
            edit { it.copy(uploading = it.uploading + 1) }
            viewModelScope.launch {
                members.uploadPublicImage(bytes)
                    .onSuccess { url -> edit { it.copy(images = it.images + url, uploading = it.uploading - 1) } }
                    .onFailure { e -> edit { it.copy(uploading = it.uploading - 1) }; say(e.message ?: "تعذّر رفع الصورة") }
            }
        }
    }

    fun removeImage(index: Int) = edit { d ->
        d.copy(
            images = d.images.filterIndexed { i, _ -> i != index },
            tags = d.tags.filter { it.imageIndex != index }.map { if (it.imageIndex > index) it.copy(imageIndex = it.imageIndex - 1) else it },
        )
    }

    fun addTag(product: StoreProduct, imageIndex: Int) = edit { d ->
        if (d.tags.any { it.product.id == product.id && it.imageIndex == imageIndex }) d
        else d.copy(tags = d.tags + DraftTag(product, imageIndex, 0.5, 0.5 + 0.08 * (d.tags.count { it.imageIndex == imageIndex } % 4)))
    }

    fun moveTag(index: Int, dx: Double, dy: Double) = edit { d ->
        d.copy(tags = d.tags.mapIndexed { i, t -> if (i == index) t.copy(x = (t.x + dx).coerceIn(0.03, 0.97), y = (t.y + dy).coerceIn(0.03, 0.97)) else t })
    }

    fun removeTag(index: Int) = edit { d -> d.copy(tags = d.tags.filterIndexed { i, _ -> i != index }) }

    suspend fun search(q: String): List<StoreProduct> = catalog.products(query = q.ifBlank { null }, pageSize = 20).map { it.items }.getOrDefault(emptyList())

    fun post() {
        val d = _draft.value
        if (d.posting) return
        if (d.images.isEmpty()) return say("أضف صورة واحدة على الأقل")
        if (d.uploading > 0) return say("انتظر حتى تكتمل الصور")
        edit { it.copy(posting = true) }
        viewModelScope.launch {
            social.createPost(
                NewPostRequest(
                    kind = d.kind,
                    caption = d.caption.trim().ifBlank { null },
                    images = d.images,
                    tags = d.tags.map { NewPostTag(it.product.id, null, it.imageIndex, it.x, it.y) },
                ),
            ).onSuccess { _done.tryEmit(it.id) }.onFailure { say(it.message ?: "تعذّر النشر") }
            edit { it.copy(posting = false) }
        }
    }
}

/**
 * A new post: photos from the gallery (several at once), product pins you drag onto the
 * picture, a caption with @mentions — or a birthday wishlist.
 */
@Composable
fun CreatePostScreen(onBack: () -> Unit, posted: (String) -> Unit, editProfile: () -> Unit) {
    val vm = appViewModel(key = "new-post") { CreatePostViewModel(it.social, it.members, it.catalog) }
    val d by vm.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val gallery = rememberMultiImagePicker(maxCount = { MAX_POST_IMAGES - d.images.size - d.uploading }) { vm.upload(it) }
    val camera = rememberImagePicker { vm.upload(listOf(it)) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.done.collect { posted(it) } }
    val index = selected.coerceIn(0, (d.images.size - 1).coerceAtLeast(0))

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("منشور جديد", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.username == "") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("اختر يوزراً أولاً", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                    Text("النشر والتعليق والمتابعة تحتاج يوزراً لحسابك.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = editProfile, modifier = Modifier.padding(top = 8.dp)) { Text("اختيار يوزر") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = d.kind == "LOOK", onClick = { vm.setKind("LOOK") }, label = { Text("إطلالة") })
                FilterChip(selected = d.kind == "WISHLIST", onClick = { vm.setKind("WISHLIST") }, label = { Text("🎂 أمنيات عيد ميلادي") })
            }
            if (d.kind == "WISHLIST") {
                Text("وسم المنتجات التي تتمنى الحصول عليها؛ عندما يحين عيد ميلادك يفتح أصدقاؤك المنشور ويهدونك منه بنقرة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Photos.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(d.images) { i, url ->
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).then(if (i == index) Modifier.border(2.dp, Liquid.GoldDeep, RoundedCornerShape(12.dp)) else Modifier).clickable { selected = i }) {
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Text("✕", Modifier.align(Alignment.TopEnd).clickable { vm.removeImage(i) }.padding(4.dp), color = Color.White)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = gallery.open, enabled = d.images.size + d.uploading < MAX_POST_IMAGES, modifier = Modifier.weight(1f)) { Text("صور من المعرض") }
                OutlinedButton(onClick = camera.fromCamera, enabled = d.images.size + d.uploading < MAX_POST_IMAGES, modifier = Modifier.weight(1f)) { Text("📷 كاميرا") }
            }
            if (d.uploading > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("جارٍ رفع ${d.uploading} صورة…", style = MaterialTheme.typography.bodySmall)
            }

            // The picture with its draggable pins.
            if (d.images.isNotEmpty()) {
                Text("اسحب الدبابيس إلى مكان القطعة في الصورة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp))) {
                    AsyncImage(model = d.images[index], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    val wPx = with(LocalDensity.current) { maxWidth.toPx() }
                    val hPx = with(LocalDensity.current) { maxHeight.toPx() }
                    d.tags.forEachIndexed { i, t ->
                        if (t.imageIndex != index) return@forEachIndexed
                        Box(
                            Modifier
                                .offset(x = maxWidth * t.x.toFloat() - 15.dp, y = maxHeight * t.y.toFloat() - 15.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFFFFE08A), Color(0xFFB8862B))))
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(i) { detectDragGestures { change, drag -> change.consume(); vm.moveTag(i, drag.x / wPx.toDouble(), drag.y / hPx.toDouble()) } },
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ وسم منتج على هذه الصورة") }
            }
            d.tags.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
                    AsyncImage(model = t.product.images.firstOrNull()?.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(t.product.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("الصورة ${t.imageIndex + 1} · ${Money.format(t.product.basePrice)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeTag(i) }) { Text("إزالة", color = MaterialTheme.colorScheme.error) }
                }
            }

            OutlinedTextField(
                value = d.caption,
                onValueChange = vm::setCaption,
                label = { Text("اكتب وصفاً… (@ للإشارة إلى صديقة)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(onClick = vm::post, enabled = !d.posting && d.images.isNotEmpty() && d.uploading == 0 && d.username != "", modifier = Modifier.fillMaxWidth()) {
                Text(if (d.posting) "جارٍ النشر…" else "نشر")
            }
        }
    }

    if (picking) ProductPickerDialog(search = vm::search, onDismiss = { picking = false }, onPick = { vm.addTag(it, index); picking = false })
}

/** Pick a store product by searching its name. */
@Composable
private fun ProductPickerDialog(search: suspend (String) -> List<StoreProduct>, onDismiss: () -> Unit, onPick: (StoreProduct) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StoreProduct>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { results = search("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر المنتج") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = {
                        q = it
                        job?.cancel()
                        job = scope.launch { delay(300); results = search(q) }
                    },
                    placeholder = { Text("ابحث عن منتج") },
                    singleLine = true,
                )
                val list = results
                if (list == null) com.dejavu.core.ui.ShimmerList(rows = 4, modifier = Modifier.size(width = 300.dp, height = 240.dp))
                else LazyColumn(Modifier.size(width = 320.dp, height = 320.dp)) {
                    items(list, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AsyncImage(model = p.images.firstOrNull()?.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(p.nameAr, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(Money.format(p.basePrice), style = MaterialTheme.typography.labelMedium, color = Liquid.GoldDeep)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
