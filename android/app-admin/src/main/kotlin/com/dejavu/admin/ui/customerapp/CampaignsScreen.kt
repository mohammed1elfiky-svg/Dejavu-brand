@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.customerapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.Campaign
import com.dejavu.core.admin.CampaignPreview
import com.dejavu.core.admin.CampaignRequest
import com.dejavu.core.admin.CampaignTarget
import com.dejavu.core.admin.CustomerAppRepository
import com.dejavu.core.admin.FinanceRepository
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.Segment
import com.dejavu.core.admin.StoreSetupRepository
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.dejavu.core.admin.MarketingTexts
import com.dejavu.core.admin.OffersRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.network.ApiException

// ------------------------------------------------------------------ draft ---

/** Something picked in a dialog: a product, category or customer. */
data class Pick(val id: String, val name: String, val slug: String = "", val note: String? = null)

private const val TARGET_NONE = "none"
private const val TARGET_PRODUCT = "product"
private const val TARGET_CATEGORY = "category"
private const val TARGET_URL = "url"

/** The campaign being written. */
data class CampaignDraft(
    val title: String = "",
    val body: String = "",
    val imageUrl: String? = null,
    val targetType: String = TARGET_NONE,
    /** Product / category slug, or the link. */
    val targetValue: String = "",
    /** Name of the picked product / category. */
    val targetLabel: String = "",
    val everyone: Boolean = true,
    val merchantsOnly: Boolean = false,
    val governorates: Set<String> = emptySet(),
    val categories: List<Pick> = emptyList(),
    val boughtWithinDays: String = "",
    val notBoughtForDays: String = "",
    val minSpent: String = "",
    val joinedWithinDays: String = "",
    val hasCart: Boolean = false,
    val customers: List<Pick> = emptyList(),
    val push: Boolean = true,
    val sms: Boolean = false,
) {
    fun segment(): Segment = if (everyone) {
        Segment()
    } else {
        Segment(
            merchantsOnly = merchantsOnly.takeIf { it },
            governorates = governorates.toList().sorted().takeIf { it.isNotEmpty() },
            boughtCategoryIds = categories.map { it.id }.takeIf { it.isNotEmpty() },
            boughtWithinDays = boughtWithinDays.toIntOrNull()?.takeIf { it > 0 },
            notBoughtForDays = notBoughtForDays.toIntOrNull()?.takeIf { it > 0 },
            minSpent = minSpent.toDoubleOrNull()?.takeIf { it > 0 },
            joinedWithinDays = joinedWithinDays.toIntOrNull()?.takeIf { it > 0 },
            hasCart = hasCart.takeIf { it },
            customerIds = customers.map { it.id }.takeIf { it.isNotEmpty() },
        )
    }

    fun target(): CampaignTarget = when (targetType) {
        TARGET_PRODUCT, TARGET_CATEGORY -> if (targetValue.isBlank()) CampaignTarget() else CampaignTarget(targetType, targetValue)
        TARGET_URL -> if (targetValue.isBlank()) CampaignTarget() else CampaignTarget(TARGET_URL, targetValue.trim())
        else -> CampaignTarget()
    }

    fun channels(): List<String> = buildList {
        if (push) add("PUSH")
        if (sms) add("SMS")
    }

    val targetOk: Boolean
        get() = when (targetType) {
            TARGET_PRODUCT, TARGET_CATEGORY -> targetValue.isNotBlank()
            TARGET_URL -> targetValue.trim().let { it.startsWith("https://") || it.startsWith("http://") } && targetValue.trim().length > 10
            else -> true
        }

    val valid: Boolean
        get() = title.trim().length in 2..100 && body.trim().length in 2..500 && channels().isNotEmpty() && targetOk
}

// -------------------------------------------------------------- viewmodel ---

class CampaignsViewModel(
    private val repo: CustomerAppRepository,
    private val inventory: InventoryRepository,
    private val storeSetup: StoreSetupRepository,
    private val finance: FinanceRepository,
    private val offerRepo: OffersRepository,
) : BaseViewModel() {
    private val _history = MutableStateFlow<Load<List<Campaign>>>(Load.Loading)
    val history = _history.asStateFlow()
    val refresher = Refresher()

    private val _composing = MutableStateFlow(false)
    val composing = _composing.asStateFlow()

    /** The message being written (Compose state, so typing stays in sync). */
    var draft by mutableStateOf(CampaignDraft())
        private set

    private val _preview = MutableStateFlow<CampaignPreview?>(null)
    val preview = _preview.asStateFlow()
    private val _previewing = MutableStateFlow(false)
    val previewing = _previewing.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()

    private val _governorates = MutableStateFlow<List<String>>(emptyList())
    val governorates = _governorates.asStateFlow()
    private val _categories = MutableStateFlow<List<Pair<AdminCategory, Int>>?>(null)
    val categories = _categories.asStateFlow()

    private val _writing = MutableStateFlow(false)

    /** True while the AI writes the message. */
    val writing = _writing.asStateFlow()
    private val _suggestions = MutableStateFlow<List<MarketingTexts.Suggestion>>(emptyList())

    /** Wordings the AI suggested for the idea. */
    val suggestions = _suggestions.asStateFlow()
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError = _writeError.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun load() {
        if (_history.value !is Load.Ready) _history.value = Load.Loading
        viewModelScope.launch { _history.value = repo.campaigns().toLoad() }
    }

    fun refresh() = refresher.run(viewModelScope) {
        repo.campaigns().orSay()?.let { _history.value = Load.Ready(it) }
    }

    fun compose() {
        _composing.value = true
        if (_governorates.value.isEmpty()) {
            viewModelScope.launch {
                storeSetup.zones().getOrNull()?.let { zones -> _governorates.value = zones.map { it.governorate } }
            }
        }
        if (_categories.value == null) {
            viewModelScope.launch { inventory.categories().getOrNull()?.let { _categories.value = it } }
        }
    }

    fun closeComposer() {
        _composing.value = false
    }

    fun edit(change: (CampaignDraft) -> CampaignDraft) {
        val old = draft
        val next = change(old)
        draft = next
        if (old.segment() != next.segment()) _preview.value = null
    }

    fun upload(bytes: ByteArray) {
        if (_uploading.value) return
        _uploading.value = true
        viewModelScope.launch {
            inventory.uploadImage(bytes).orSay()?.let { file -> edit { it.copy(imageUrl = file.url) } }
            _uploading.value = false
        }
    }

    /** Asks the AI for three wordings of [idea] (SMS length when only SMS is chosen). */
    fun writeText(idea: String) {
        if (_writing.value || idea.isBlank()) return
        val d = draft
        val channel = if (d.sms && !d.push) "SMS" else "PUSH"
        _writing.value = true
        _writeError.value = null
        _suggestions.value = emptyList()
        viewModelScope.launch {
            offerRepo.marketingText(idea, channel).fold(
                { r ->
                    _suggestions.value = r.suggestions
                    if (r.suggestions.isEmpty()) _writeError.value = "لم يقترح الذكاء الاصطناعي نصاً، جرّب وصف الفكرة بشكل أوضح"
                },
                { e -> _writeError.value = if ((e as? ApiException)?.status == 503) "أضف مفتاح Gemini في إعدادات الخادم" else e.userMessage() },
            )
            _writing.value = false
        }
    }

    /** Puts a suggested wording in the title and text. */
    fun useSuggestion(s: MarketingTexts.Suggestion) {
        edit { it.copy(title = s.title.trim().ifBlank { it.title }.take(100), body = s.body.trim().take(500)) }
        clearSuggestions()
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
        _writeError.value = null
    }

    fun previewCount() {
        if (_previewing.value) return
        _previewing.value = true
        val segment = draft.segment()
        viewModelScope.launch {
            repo.previewCampaign(segment).orSay()?.let { p -> if (draft.segment() == segment) _preview.value = p }
            _previewing.value = false
        }
    }

    fun send() {
        val d = draft
        if (_sending.value || !d.valid) return
        _sending.value = true
        viewModelScope.launch {
            val request = CampaignRequest(
                title = d.title.trim(),
                body = d.body.trim(),
                imageUrl = d.imageUrl,
                target = d.target(),
                segment = d.segment(),
                channels = d.channels(),
            )
            repo.sendCampaign(request).orSay()?.let { c ->
                say(
                    "تم الإرسال إلى ${c.recipients} عميل" +
                        if (c.smsCount > 0) " (${c.smsCount} رسالة SMS)" else "",
                )
                draft = CampaignDraft()
                _preview.value = null
                _composing.value = false
                repo.campaigns().getOrNull()?.let { _history.value = Load.Ready(it) }
            }
            _sending.value = false
        }
    }

    suspend fun searchProducts(query: String): List<Pick> =
        inventory.products(query, pageSize = 20).getOrNull()?.items.orEmpty().map { p ->
            Pick(p.id, p.nameAr, p.slug, p.skuNumber?.let { "#$it" })
        }

    suspend fun searchCustomers(query: String): List<Pick> =
        finance.customers(query).getOrNull()?.items.orEmpty().map { c -> Pick(c.id, c.name, note = c.phone) }
}

// ------------------------------------------------------------------- ui ---

@Composable
internal fun CampaignsContent(onBack: () -> Unit) {
    val vm = adminViewModel(key = "campaigns") { CampaignsViewModel(it.customerApp, it.inventory, it.storeSetup, it.finance, it.offers) }
    val composing by vm.composing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.start() }

    if (composing) {
        BackHandler { vm.closeComposer() }
        CampaignComposer(vm, snackbar)
    } else {
        History(vm, snackbar, onBack)
    }
}

@Composable
private fun History(vm: CampaignsViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val state by vm.history.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("رسائل للعملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::compose,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("رسالة جديدة") },
                containerColor = Lux.GoldDeep,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
                if (list.isEmpty()) {
                    EmptyList(
                        Icons.Filled.Notifications,
                        "لم ترسل أي رسالة بعد",
                        "أرسل إشعاراً في التطبيق أو رسالة SMS لكل العملاء أو لمجموعة منهم.",
                        action = "رسالة جديدة",
                        onAction = vm::compose,
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(list, key = { it.id }) { c -> CampaignCard(c) }
                    }
                }
            }
        }
    }
}

private fun CampaignTarget.label(): String? = when (type) {
    TARGET_PRODUCT -> "يفتح منتجاً"
    TARGET_CATEGORY -> "يفتح قسماً"
    TARGET_URL -> "يفتح رابطاً"
    else -> null
}

@Composable
private fun CampaignCard(c: Campaign) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (c.imageUrl != null) {
                ProductImage(c.imageUrl, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
            }
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.body, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(formatDateTime(c.createdAt), style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            }
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip("${c.recipients} عميل", Lux.GoldDeep)
            if ("PUSH" in c.channels) StatusChip("إشعار", Lux.GoldDeep)
            if ("SMS" in c.channels) StatusChip("SMS ${c.smsCount}", Lux.GoldDeep)
            c.target.label()?.let { StatusChip(it, Lux.InkSoft) }
        }
    }
}

@Composable
private fun CampaignComposer(vm: CampaignsViewModel, snackbar: SnackbarHostState) {
    val d = vm.draft
    val preview by vm.preview.collectAsStateWithLifecycle()
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val governorates by vm.governorates.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var writingText by remember { mutableStateOf(false) }
    val picker = rememberImagePicker { bytes -> vm.upload(bytes) }
    val smsMax = preview?.smsMax ?: 500

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("رسالة جديدة", vm::closeComposer) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- message
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الرسالة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { writingText = true }, enabled = !sending) { Text("✨ اكتب لي النص") }
                }
                FormField(d.title, { v -> vm.edit { it.copy(title = v.take(100)) } }, "العنوان", enabled = !sending)
                FormField(
                    d.body,
                    { v -> vm.edit { it.copy(body = v.take(500)) } },
                    "النص",
                    singleLine = false,
                    minLines = 3,
                    note = "${d.body.length}/500",
                    enabled = !sending,
                )
                Text("صورة (اختياري)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                if (d.imageUrl != null) {
                    ProductImage(
                        d.imageUrl,
                        Modifier.fillMaxWidth().height(160.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)),
                        contentDescription = "صورة الإشعار",
                    )
                }
                if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = picker.fromGallery, enabled = !uploading && !sending, modifier = Modifier.weight(1f)) {
                        Text("من المعرض", maxLines = 1)
                    }
                    OutlinedButton(onClick = picker.fromCamera, enabled = !uploading && !sending, modifier = Modifier.weight(1f)) {
                        Text("الكاميرا", maxLines = 1)
                    }
                    if (d.imageUrl != null) {
                        OutlinedButton(onClick = { vm.edit { it.copy(imageUrl = null) } }, enabled = !sending, modifier = Modifier.weight(1f)) {
                            Text("إزالة", maxLines = 1)
                        }
                    }
                }
                Text(
                    "تظهر الصورة كبيرة في إشعار التطبيق ولا تُرسل في SMS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }

            // ---- target
            GlassCard(Modifier.fillMaxWidth()) {
                Text("عند الضغط يفتح", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TARGET_NONE to "لا شيء", TARGET_PRODUCT to "منتج", TARGET_CATEGORY to "قسم", TARGET_URL to "رابط").forEach { (type, label) ->
                        FilterChip(
                            selected = d.targetType == type,
                            onClick = {
                                if (d.targetType != type) vm.edit { it.copy(targetType = type, targetValue = "", targetLabel = "") }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                when (d.targetType) {
                    TARGET_PRODUCT, TARGET_CATEGORY -> {
                        val product = d.targetType == TARGET_PRODUCT
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                d.targetLabel.ifBlank { if (product) "لم يتم اختيار منتج" else "لم يتم اختيار قسم" },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (d.targetLabel.isBlank()) Lux.InkSoft else Color.Unspecified,
                            )
                            OutlinedButton(onClick = { picking = if (product) PICK_PRODUCT else PICK_TARGET_CATEGORY }, enabled = !sending) {
                                Text(if (product) "اختيار منتج" else "اختيار قسم")
                            }
                        }
                    }
                    TARGET_URL -> FormField(
                        d.targetValue,
                        { v -> vm.edit { it.copy(targetValue = v.trim().take(500)) } },
                        "الرابط",
                        hint = "https://",
                        isError = d.targetValue.isNotBlank() && !d.targetOk,
                        enabled = !sending,
                    )
                    else -> {}
                }
            }

            // ---- audience
            GlassCard(Modifier.fillMaxWidth()) {
                Text("إلى من؟", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = d.everyone, onClick = { vm.edit { it.copy(everyone = true) } }, label = { Text("كل العملاء") })
                    FilterChip(selected = !d.everyone, onClick = { vm.edit { it.copy(everyone = false) } }, label = { Text("مجموعة محددة") })
                }
                if (!d.everyone) {
                    Text(
                        "يجب أن يطابق العميل كل الشروط التي تختارها.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                    SwitchRow("التجار فقط", checked = d.merchantsOnly, onChange = { v -> vm.edit { it.copy(merchantsOnly = v) } })
                    HorizontalDivider(color = Lux.Hairline)
                    Text("المحافظات", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    if (governorates.isEmpty()) {
                        Text("جاري تحميل المحافظات…", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            governorates.forEach { g ->
                                FilterChip(
                                    selected = g in d.governorates,
                                    onClick = {
                                        vm.edit { it.copy(governorates = if (g in it.governorates) it.governorates - g else it.governorates + g) }
                                    },
                                    label = { Text(g) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Lux.Hairline)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("اشترى من أقسام", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { picking = PICK_SEGMENT_CATEGORY }) { Text("اختيار الأقسام") }
                    }
                    if (d.categories.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.categories.forEach { c ->
                                RemovableChip(c.name) { vm.edit { it.copy(categories = it.categories.filter { x -> x.id != c.id }) } }
                            }
                        }
                    }
                    FormField(
                        d.boughtWithinDays,
                        { v -> vm.edit { it.copy(boughtWithinDays = v.filter(Char::isDigit).take(4)) } },
                        "اشترى خلال آخر N يوم",
                        number = true,
                        note = if (d.categories.isEmpty()) "اتركه فارغاً لعدم التقييد" else "من الأقسام المختارة؛ فارغ = في أي وقت",
                    )
                    FormField(
                        d.notBoughtForDays,
                        { v -> vm.edit { it.copy(notBoughtForDays = v.filter(Char::isDigit).take(4)) } },
                        "لم يشترِ منذ N يوم",
                        number = true,
                    )
                    FormField(
                        d.minSpent,
                        { v -> vm.edit { it.copy(minSpent = v.filter { ch -> ch.isDigit() || ch == '.' }.take(10)) } },
                        "أنفق على الأقل (ر.س)",
                        number = true,
                    )
                    FormField(
                        d.joinedWithinDays,
                        { v -> vm.edit { it.copy(joinedWithinDays = v.filter(Char::isDigit).take(4)) } },
                        "سجّل خلال آخر N يوم",
                        number = true,
                    )
                    SwitchRow("عنده سلة متروكة", checked = d.hasCart, onChange = { v -> vm.edit { it.copy(hasCart = v) } })
                    HorizontalDivider(color = Lux.Hairline)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("عملاء محددون", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { picking = PICK_CUSTOMER }) { Text("إضافة عميل") }
                    }
                    if (d.customers.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.customers.forEach { c ->
                                RemovableChip(c.name) { vm.edit { it.copy(customers = it.customers.filter { x -> x.id != c.id }) } }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Lux.Hairline)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val p = preview
                        if (p == null) {
                            Text("اضغط \"احسب العدد\" لمعرفة عدد المستلمين", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        } else {
                            Text("${p.count} عميل مطابق", style = MaterialTheme.typography.titleSmall)
                            Text("${p.withApp} منهم لديهم التطبيق", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    }
                    OutlinedButton(onClick = vm::previewCount, enabled = !previewing) {
                        if (previewing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("احسب العدد")
                    }
                }
                preview?.let { p ->
                    if (p.sample.isNotEmpty()) {
                        Text(
                            "مثل: " + p.sample.take(5).joinToString("، ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = Lux.InkSoft,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ---- channels
            GlassCard(Modifier.fillMaxWidth()) {
                Text("طريقة الإرسال", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    "إشعار في التطبيق",
                    note = "يصل لمن ثبّت التطبيق وسمح بالإشعارات",
                    checked = d.push,
                    enabled = !sending,
                    onChange = { v -> vm.edit { it.copy(push = v) } },
                )
                SwitchRow(
                    "رسالة SMS",
                    note = "تُرسل من هاتف المتجر، حتى $smsMax رسالة في المرة",
                    checked = d.sms,
                    enabled = !sending,
                    onChange = { v -> vm.edit { it.copy(sms = v) } },
                )
                val p = preview
                if (d.sms && p != null && p.count > p.smsMax) {
                    Text(
                        "العدد أكبر من ${p.smsMax}؛ ستصل SMS لأول ${p.smsMax} عميل فقط.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Warning,
                    )
                }
                if (d.channels().isEmpty()) {
                    Text("اختر طريقة إرسال واحدة على الأقل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            SaveButton(busy = sending, enabled = d.valid && !uploading, text = "إرسال") { confirming = true }
        }
    }

    if (writingText) {
        WriteTextDialog(vm, initialIdea = listOf(d.title.trim(), d.body.trim()).filter { it.isNotEmpty() }.joinToString(" — ")) {
            writingText = false
            vm.clearSuggestions()
        }
    }

    if (confirming) {
        val p = preview
        val who = when {
            p != null -> "${p.count} عميل"
            d.everyone -> "كل العملاء"
            else -> "العملاء المطابقين للشروط"
        }
        val how = listOfNotNull(if (d.push) "إشعار في التطبيق" else null, if (d.sms) "SMS" else null).joinToString(" و")
        ConfirmDialog(
            title = "إرسال الرسالة؟",
            text = "سيتم إرسال \"${d.title.trim()}\" إلى $who ($how). لا يمكن التراجع بعد الإرسال.",
            confirm = "إرسال",
            onDismiss = { confirming = false },
            onConfirm = vm::send,
        )
    }

    when (picking) {
        PICK_PRODUCT -> SearchPickDialog(
            title = "اختيار منتج",
            label = "ابحث بالاسم أو الرقم",
            search = vm::searchProducts,
            onDismiss = { picking = null },
        ) { p ->
            vm.edit { it.copy(targetValue = p.slug, targetLabel = p.name) }
            picking = null
        }
        PICK_CUSTOMER -> SearchPickDialog(
            title = "إضافة عميل",
            label = "ابحث بالاسم أو الهاتف",
            search = vm::searchCustomers,
            selected = d.customers.map { it.id }.toSet(),
            onDismiss = { picking = null },
        ) { p ->
            vm.edit { s ->
                if (s.customers.any { it.id == p.id }) s.copy(customers = s.customers.filter { it.id != p.id }) else s.copy(customers = s.customers + p)
            }
        }
        PICK_TARGET_CATEGORY -> CategoryPickDialog(
            categories,
            multi = false,
            selected = emptySet(),
            onDismiss = { picking = null },
        ) { c ->
            vm.edit { it.copy(targetValue = c.slug, targetLabel = c.nameAr) }
            picking = null
        }
        PICK_SEGMENT_CATEGORY -> CategoryPickDialog(
            categories,
            multi = true,
            selected = d.categories.map { it.id }.toSet(),
            onDismiss = { picking = null },
        ) { c ->
            vm.edit { s ->
                if (s.categories.any { it.id == c.id }) {
                    s.copy(categories = s.categories.filter { it.id != c.id })
                } else {
                    s.copy(categories = s.categories + Pick(c.id, c.nameAr, c.slug))
                }
            }
        }
        else -> {}
    }
}

/** Asks for the idea, then shows the AI's three wordings; tapping one fills the title and text. */
@Composable
private fun WriteTextDialog(vm: CampaignsViewModel, initialIdea: String, onDismiss: () -> Unit) {
    var idea by rememberSaveable { mutableStateOf(initialIdea) }
    val writing by vm.writing.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val error by vm.writeError.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("✨ اكتب لي النص") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = idea,
                    onValueChange = { idea = it.take(300) },
                    label = { Text("فكرة الرسالة") },
                    placeholder = { Text("مثل: خصم 20% على الكعب العالي حتى الخميس") },
                    minLines = 2,
                    enabled = !writing,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (suggestions.isNotEmpty()) {
                    Text("اختر صيغة:", style = MaterialTheme.typography.labelLarge)
                    suggestions.forEach { s ->
                        GlassCard(Modifier.fillMaxWidth(), elevation = 2.dp, contentPadding = 12.dp, onClick = {
                            vm.useSuggestion(s)
                            onDismiss()
                        }) {
                            if (s.title.isNotBlank()) Text(s.title, style = MaterialTheme.typography.titleSmall)
                            Text(s.body, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.writeText(idea) }, enabled = !writing && idea.trim().length >= 3) {
                Text(if (suggestions.isEmpty()) "اكتب" else "صيغ أخرى")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private const val PICK_PRODUCT = "product"
private const val PICK_CUSTOMER = "customer"
private const val PICK_TARGET_CATEGORY = "target-category"
private const val PICK_SEGMENT_CATEGORY = "segment-category"

@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "إزالة", modifier = Modifier.size(16.dp)) },
    )
}

@Composable
private fun SearchPickDialog(
    title: String,
    label: String,
    search: suspend (String) -> List<Pick>,
    onDismiss: () -> Unit,
    selected: Set<String>? = null,
    onPick: (Pick) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pick>?>(null) }
    LaunchedEffect(query) {
        delay(300)
        results = null
        results = search(query.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                val list = results
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).padding(top = 8.dp)) {
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text("لا توجد نتائج", Modifier.align(Alignment.Center), color = Lux.InkSoft)
                        else -> LazyColumn {
                            items(list, key = { it.id }) { p ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selected != null) Checkbox(checked = p.id in selected, onCheckedChange = null)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!p.note.isNullOrBlank()) {
                                            Text(p.note, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (selected != null) "تم" else "إغلاق") } },
    )
}

@Composable
private fun CategoryPickDialog(
    categories: List<Pair<AdminCategory, Int>>?,
    multi: Boolean,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onPick: (AdminCategory) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multi) "اختيار الأقسام" else "اختيار قسم") },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    categories == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    categories.isEmpty() -> Text("لا توجد أقسام", Modifier.align(Alignment.Center), color = Lux.InkSoft)
                    else -> LazyColumn {
                        items(categories, key = { it.first.id }) { entry ->
                            val c = entry.first
                            val depth = entry.second
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(c) }
                                    .padding(start = (depth * 16).dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (multi) Checkbox(checked = c.id in selected, onCheckedChange = null)
                                Text(
                                    c.nameAr,
                                    style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (multi) "تم" else "إغلاق") } },
    )
}
