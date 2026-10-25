@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.core.data.CatalogRepository
import com.dejavu.core.data.SessionRepository
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.MyReview
import com.dejavu.core.model.ProductReview
import com.dejavu.core.model.ProductReviews
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

private const val MAX_PHOTOS = 5
private const val PREVIEW_COUNT = 3

/** The review being written in the sheet. */
data class ReviewDraft(
    val rating: Int = 0,
    val comment: String = "",
    val photos: List<String> = emptyList(),
    val uploading: Int = 0,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val canAddPhoto: Boolean get() = photos.size + uploading < MAX_PHOTOS && !sending
}

/** Reviews on the product screen and the customer's own review. */
class ReviewsViewModel(
    private val slug: String,
    private val catalog: CatalogRepository,
    private val session: SessionRepository,
) : BaseViewModel() {
    private val _reviews = MutableStateFlow<Load<ProductReviews>>(Load.Loading)
    val reviews = _reviews.asStateFlow()

    /** Null while signed out or not loaded yet. */
    private val _mine = MutableStateFlow<MyReview?>(null)
    val mine = _mine.asStateFlow()

    /** Null while the sheet is closed. */
    private val _draft = MutableStateFlow<ReviewDraft?>(null)
    val draft = _draft.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            session.state.distinctUntilChangedBy { it is SessionState.LoggedIn }.collect { state ->
                if (state is SessionState.LoggedIn) {
                    catalog.myReview(slug).onSuccess { _mine.value = it }
                } else {
                    _mine.value = null
                    _draft.value = null
                }
            }
        }
    }

    fun load() {
        if (_reviews.value !is Load.Ready) _reviews.value = Load.Loading
        viewModelScope.launch { _reviews.value = catalog.reviews(slug).toLoad() }
    }

    fun openEditor() {
        val r = _mine.value?.review
        _draft.value = ReviewDraft(rating = r?.rating ?: 0, comment = r?.comment.orEmpty(), photos = r?.images.orEmpty().take(MAX_PHOTOS))
    }

    fun closeEditor() {
        if (_draft.value?.sending != true) _draft.value = null
    }

    fun edit(change: ReviewDraft.() -> ReviewDraft) {
        _draft.value = _draft.value?.change()
    }

    fun addPhoto(bytes: ByteArray) {
        val d = _draft.value ?: return
        if (!d.canAddPhoto) return
        edit { copy(uploading = uploading + 1, error = null) }
        viewModelScope.launch {
            catalog.uploadReviewPhoto(bytes)
                .onSuccess { file -> edit { copy(uploading = uploading - 1, photos = (photos + file.url).take(MAX_PHOTOS)) } }
                .onFailure { e -> edit { copy(uploading = uploading - 1, error = e.userMessage()) } }
        }
    }

    fun removePhoto(url: String) = edit { copy(photos = photos - url) }

    fun send() {
        val d = _draft.value ?: return
        if (d.sending || d.uploading > 0) return
        if (d.rating !in 1..5) {
            edit { copy(error = "اختر عدد النجوم") }
            return
        }
        edit { copy(sending = true, error = null) }
        viewModelScope.launch {
            catalog.writeReview(slug, d.rating, d.comment, d.photos)
                .onSuccess { review ->
                    _mine.value = (_mine.value ?: MyReview(canReview = true)).copy(review = review)
                    _draft.value = null
                    say("شكراً! سيظهر تقييمك بعد مراجعته")
                    load()
                }
                .onFailure { e -> edit { copy(sending = false, error = e.userMessage()) } }
        }
    }
}

// ------------------------------------------------------------------- stars ---

/** Five stars, the first [rating] (rounded) in gold. */
@Composable
fun Stars(rating: Float, size: Dp = 16.dp) {
    val filled = Math.round(rating.coerceIn(0f, 5f))
    Row {
        repeat(5) { i ->
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = if (i < filled) Liquid.Gold else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** "★★★★☆ 4.3 (12)" for the price area. */
@Composable
fun RatingSummary(average: String, count: Int, modifier: Modifier = Modifier) {
    val avg = average.toFloatOrNull() ?: 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Stars(avg)
        Text(String.format(java.util.Locale.US, "%.1f", avg), style = MaterialTheme.typography.labelLarge)
        Text("($count)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------- product section ---

/** "آراء العملاء" on the product screen: summary, the latest reviews and the write button. */
@Composable
fun ReviewsSection(vm: ReviewsViewModel, openAll: () -> Unit) {
    val state by vm.reviews.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    // Outside the sheet so the result still arrives if the sheet recomposes meanwhile.
    val picker = rememberImagePicker { bytes -> vm.addPhoto(bytes) }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("آراء العملاء", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep)
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            Load.Loading -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Liquid.Gold, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            is Load.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::load) { Text("إعادة المحاولة", color = Liquid.GoldDeep) }
            }
            is Load.Ready -> {
                val data = s.value
                if (data.count > 0) {
                    RatingSummary(data.average, data.count)
                } else {
                    Text("لا توجد تقييمات بعد", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val latest = data.items.take(PREVIEW_COUNT)
                latest.forEach { r ->
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Liquid.Gold.copy(alpha = 0.25f))
                    ReviewItem(r)
                }
                if (data.total > latest.size) {
                    TextButton(onClick = openAll, modifier = Modifier.padding(top = 4.dp)) { Text("عرض المزيد", color = Liquid.GoldDeep) }
                }
            }
        }
        mine?.takeIf { it.canReview }?.let { m ->
            Spacer(Modifier.height(10.dp))
            if (m.review?.isApproved == false) {
                Text("تقييمك قيد المراجعة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = vm::openEditor, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Liquid.Gold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (m.review == null) "قيّم المنتج" else "تعديل تقييمك")
            }
        }
    }

    draft?.let { d ->
        ReviewSheet(
            draft = d,
            onChange = vm::edit,
            onRemovePhoto = vm::removePhoto,
            fromGallery = picker.fromGallery,
            fromCamera = picker.fromCamera,
            onSend = vm::send,
            onDismiss = vm::closeEditor,
        )
    }
}

/** One review: stars, first name, date, comment and photos (tap to enlarge). */
@Composable
fun ReviewItem(r: ProductReview) {
    var enlarged by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stars(r.rating.toFloat(), size = 14.dp)
            Text(r.customerName?.trim()?.ifBlank { null } ?: "عميل", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(formatDate(r.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        r.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (r.images.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.images.forEach { url ->
                    ProductImage(url, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable { enlarged = url }, contentDescription = "صورة التقييم")
                }
            }
        }
    }
    enlarged?.let { url -> PhotoViewer(url) { enlarged = null } }
}

/** Full-screen photo; tap anywhere to close. */
@Composable
private fun PhotoViewer(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = "صورة التقييم",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
            }
        }
    }
}

// ------------------------------------------------------------- the sheet ---

@Composable
private fun ReviewSheet(
    draft: ReviewDraft,
    onChange: (ReviewDraft.() -> ReviewDraft) -> Unit,
    onRemovePhoto: (String) -> Unit,
    fromGallery: () -> Unit,
    fromCamera: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("قيّم المنتج", style = MaterialTheme.typography.titleMedium, color = Liquid.GoldDeep)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                (1..5).forEach { n ->
                    IconButton(onClick = { onChange { copy(rating = n, error = null) } }, enabled = !draft.sending) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "$n نجوم",
                            tint = if (n <= draft.rating) Liquid.Gold else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft.comment,
                onValueChange = { v -> onChange { copy(comment = v.take(1000)) } },
                label = { Text("تعليقك (اختياري)") },
                minLines = 3,
                enabled = !draft.sending,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("الصور (حتى $MAX_PHOTOS)", style = MaterialTheme.typography.labelLarge)
            if (draft.photos.isNotEmpty() || draft.uploading > 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.photos.forEach { url ->
                        Box(Modifier.size(72.dp)) {
                            ProductImage(url, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentDescription = "صورة")
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable(enabled = !draft.sending) { onRemovePhoto(url) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "إزالة الصورة", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    repeat(draft.uploading) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Liquid.Gold, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = fromGallery, enabled = draft.canAddPhoto, modifier = Modifier.weight(1f)) { Text("من المعرض") }
                OutlinedButton(onClick = fromCamera, enabled = draft.canAddPhoto, modifier = Modifier.weight(1f)) { Text("التقاط صورة") }
            }
            draft.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onSend,
                enabled = !draft.sending && draft.uploading == 0 && draft.rating in 1..5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (draft.sending) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("إرسال التقييم")
                }
            }
            Text(
                "يظهر تقييمك في المتجر بعد مراجعته",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------ all reviews ---

class AllReviewsViewModel(private val slug: String, private val catalog: CatalogRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<ProductReviews>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = catalog.reviews(slug, 1).toLoad() }
    }

    fun more() {
        val current = (_state.value as? Load.Ready)?.value ?: return
        if (!current.hasMore || _loadingMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            catalog.reviews(slug, current.page + 1).orSay()?.let { next ->
                val seen = current.items.map { it.id }.toSet()
                _state.value = Load.Ready(next.copy(items = current.items + next.items.filter { it.id !in seen }))
            }
            _loadingMore.value = false
        }
    }
}

/** Every approved review of a product, a page at a time. */
@Composable
fun AllReviewsScreen(slug: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "reviews:$slug") { AllReviewsViewModel(slug, it.catalog) }
    val state by vm.state.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("آراء العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { data ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        if (data.count > 0) {
                            RatingSummary(data.average, data.count)
                        } else {
                            Text("لا توجد تقييمات بعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(data.items, key = { it.id }) { r ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp) { ReviewItem(r) }
                }
                if (data.hasMore) {
                    item {
                        LaunchedEffect(data.page) { vm.more() }
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                CircularProgressIndicator(color = Liquid.Gold, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = vm::more) { Text("عرض المزيد", color = Liquid.GoldDeep) }
                            }
                        }
                    }
                }
            }
        }
    }
}
