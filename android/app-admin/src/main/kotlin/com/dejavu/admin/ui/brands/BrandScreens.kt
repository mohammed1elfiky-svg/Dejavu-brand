package com.dejavu.admin.ui.brands

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import com.dejavu.admin.ui.UndoableDeletes
import com.dejavu.admin.ui.showUndoDelete
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminBrand
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the brand dialog is editing: null id = a new brand. */
data class BrandDraft(val id: String? = null, val name: String = "", val logoUrl: String = "", val savedLogo: String = "", val isActive: Boolean = true)

class BrandsViewModel(private val repo: InventoryRepository, appScope: CoroutineScope) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminBrand>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _draft = MutableStateFlow<BrandDraft?>(null)

    /** The brand being added or edited, null when the dialog is closed. */
    val draft = _draft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        viewModelScope.launch { _state.value = repo.brands().toLoad() }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) { _state.value = repo.brands().toLoad() }

    /** Deleted brands wait a few seconds for "تراجع" before the server is asked. */
    val deletes = UndoableDeletes(appScope, viewModelScope) { say(it) }

    fun open(brand: AdminBrand?) {
        _draft.value = brand?.let { BrandDraft(it.id, it.name, it.logoUrl.orEmpty(), it.logoUrl.orEmpty(), it.isActive) } ?: BrandDraft()
    }

    fun close() {
        _draft.value = null
    }

    fun edit(change: (BrandDraft) -> BrandDraft) {
        _draft.value = _draft.value?.let(change)
    }

    fun uploadLogo(bytes: ByteArray) {
        _busy.value = true
        viewModelScope.launch {
            repo.uploadImage(bytes).orSay()?.let { file -> edit { it.copy(logoUrl = file.url) } }
            _busy.value = false
        }
    }

    fun save() {
        val d = _draft.value ?: return
        if (d.name.isBlank()) return say("اكتب اسم الماركة")
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = if (d.id == null) {
                repo.createBrand(d.name, d.logoUrl.ifBlank { null })
            } else {
                // "" removes a logo that was set; an unchanged blank is left out.
                repo.updateBrand(d.id, d.name, d.logoUrl.ifBlank { if (d.savedLogo.isNotBlank()) "" else null }, d.isActive)
            }
            result.orSay()?.let {
                say(if (d.id == null) "تمت إضافة ${it.name}" else "تم حفظ ${it.name}")
                _draft.value = null
                load()
            }
            _busy.value = false
        }
    }

    /** Hides the brand now; it is deleted (its products keep no brand) once the undo snackbar is gone. */
    fun delete(brand: AdminBrand) {
        _draft.value = null
        deletes.hide(brand.id) {
            repo.deleteBrand(brand.id).onSuccess {
                repo.brands().onSuccess { _state.value = Load.Ready(it) }
            }
        }
    }

    override fun onCleared() {
        deletes.flush()
    }
}

/** Brands list: logo, name and product count; add, edit, delete and open its products. */
@Composable
fun BrandsScreen(canEdit: Boolean, onBack: () -> Unit, openProducts: (String) -> Unit) {
    val vm = adminViewModel { BrandsViewModel(it.inventory, it.appScope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val hidden by vm.deletes.hidden.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("الماركات", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { vm.open(null) },
                    containerColor = Lux.GoldDeep,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("ماركة جديدة") },
                )
            }
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { all ->
            val brands = all.filterNot { it.id in hidden }
            RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
            if (brands.isEmpty()) {
                EmptyList(
                    Icons.Filled.Star,
                    "لا توجد ماركات بعد",
                    "أضف الماركات التي تبيعها ليختار العملاء منتجاتهم حسب الماركة",
                    action = if (canEdit) "إضافة ماركة" else null,
                    onAction = { vm.open(null) },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(brands, key = { it.id }) { b ->
                        GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 12.dp, onClick = { openProducts(b.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BrandLogo(b.name, b.logoUrl, 52.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${b.productCount} منتج${if (!b.isActive) " · موقوفة" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (b.isActive) Lux.InkSoft else MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (canEdit) TextButton(onClick = { vm.open(b) }) { Text("تعديل") }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    draft?.let { d ->
        val brand = (state as? Load.Ready)?.value?.firstOrNull { it.id == d.id }
        BrandDialog(
            d,
            busy,
            vm,
            onDelete = brand?.let { b ->
                {
                    vm.delete(b)
                    scope.showUndoDelete(snackbar, vm.deletes, b.id, "تم حذف ${b.name}")
                }
            },
        )
    }
}

@Composable
private fun BrandDialog(d: BrandDraft, busy: Boolean, vm: BrandsViewModel, onDelete: (() -> Unit)?) {
    val picker = rememberImagePicker(maxSide = 600) { vm.uploadLogo(it) }
    AlertDialog(
        onDismissRequest = vm::close,
        title = { Text(if (d.id == null) "ماركة جديدة" else "تعديل الماركة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { v -> vm.edit { it.copy(name = v.take(80)) } },
                    label = { Text("اسم الماركة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandLogo(d.name.ifBlank { "؟" }, d.logoUrl.ifBlank { null }, 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = picker.fromGallery, enabled = !busy) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (d.logoUrl.isBlank()) "رفع الشعار" else "تغيير الشعار")
                        }
                        if (d.logoUrl.isNotBlank()) TextButton(onClick = { vm.edit { it.copy(logoUrl = "") } }) { Text("إزالة الشعار") }
                    }
                }
                if (d.id != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ظاهرة للعملاء", Modifier.weight(1f))
                        Switch(checked = d.isActive, onCheckedChange = { v -> vm.edit { it.copy(isActive = v) } })
                    }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) { Text("حذف الماركة", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::save, enabled = !busy && d.name.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = vm::close) { Text("إلغاء") } },
    )
}

/** Round brand logo, or the first letter in gold when there is none. */
@Composable
fun BrandLogo(name: String, logoUrl: String?, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Lux.Champagne),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl != null) {
            AsyncImage(model = logoUrl, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(name.trim().take(1), color = Lux.GoldDeep, style = MaterialTheme.typography.titleMedium)
        }
    }
}
