package com.dejavu.admin.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import androidx.compose.material.icons.automirrored.filled.List
import com.dejavu.core.ui.EmptyState
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCategory
import com.dejavu.core.admin.InventoryRepository
import com.dejavu.core.admin.UpdateCategoryRequest
import com.dejavu.core.admin.VariantPlanner
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** All categories (with their depth): rename, show / hide in the customer app, add. */
class CategoriesViewModel(private val repo: InventoryRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<Pair<AdminCategory, Int>>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)

    /** Last outcome, for places without a snackbar (the product editor dialog). */
    val status = _status.asStateFlow()

    /** Fetches the list again; what is shown stays until the new one arrives. */
    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val r = repo.categories()
            if (r.isSuccess || _state.value !is Load.Ready) _state.value = r.toLoad()
        }
    }

    private suspend fun refresh() {
        repo.categories().onSuccess { _state.value = Load.Ready(it) }
    }

    val refresher = Refresher()

    /** Pull to refresh; a failure keeps the list shown and says why. */
    fun pullRefresh() = refresher.run(viewModelScope) {
        val r = repo.categories()
        if (r.isSuccess || _state.value !is Load.Ready) _state.value = r.toLoad()
        r.exceptionOrNull()?.let { say(it.userMessage()) }
    }

    private fun tell(message: String) {
        _status.value = message
        say(message)
    }

    private fun patchLocal(id: String, change: (AdminCategory) -> AdminCategory) {
        val list = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(list.map { (c, d) -> (if (c.id == id) change(c) else c) to d })
    }

    fun setVisible(c: AdminCategory, visible: Boolean) {
        if (_busy.value) return
        _busy.value = true
        patchLocal(c.id) { it.copy(isActive = visible) }
        viewModelScope.launch {
            repo.updateCategory(c.id, UpdateCategoryRequest(isActive = visible)).fold(
                { tell(if (visible) "أصبح قسم ${c.nameAr} ظاهراً في التطبيق" else "تم إخفاء قسم ${c.nameAr} من التطبيق") },
                { e ->
                    patchLocal(c.id) { it.copy(isActive = c.isActive) }
                    tell(e.userMessage())
                },
            )
            refresh()
            _busy.value = false
        }
    }

    fun rename(c: AdminCategory, nameAr: String, nameEn: String) {
        val ar = nameAr.trim()
        val en = nameEn.trim()
        val request = UpdateCategoryRequest(nameAr = ar.takeIf { it != c.nameAr }, nameEn = en.takeIf { it != c.nameEn })
        if (request == UpdateCategoryRequest() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.updateCategory(c.id, request).fold(
                {
                    tell("تم حفظ اسم القسم $ar")
                    refresh()
                },
                { e -> tell(e.userMessage()) },
            )
            _busy.value = false
        }
    }

    fun add(nameAr: String, nameEn: String, parentId: String?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.createCategory(nameAr, nameEn, parentId).fold(
                { c ->
                    tell("تمت إضافة قسم ${c.nameAr}")
                    refresh()
                },
                { e -> tell(e.userMessage()) },
            )
            _busy.value = false
        }
    }
}

/** Screen under "المزيد ← الأقسام". */
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "categories") { CategoriesViewModel(it.inventory) }
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("الأقسام", onBack) {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        RefreshList(refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.padding(padding)) {
            CategoryManager(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                vm = vm,
                inlineStatus = false,
            )
        }
    }
}

/**
 * The category list with its actions (rename, show / hide, add, add a sub-category).
 * Used by [CategoriesScreen] and by the product editor's "تعديل الأقسام" dialog; the caller bounds its height.
 */
@Composable
fun CategoryManager(
    modifier: Modifier = Modifier,
    vm: CategoriesViewModel = adminViewModel(key = "category-manager") { CategoriesViewModel(it.inventory) },
    /** Shows the last outcome as text (when there is no snackbar). */
    inlineStatus: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AdminCategory?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Parent of the category being added (null = a main category).
    var addParent by remember { mutableStateOf<AdminCategory?>(null) }
    LaunchedEffect(vm) { vm.load() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (inlineStatus) status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.GoldDeep) }
        OutlinedButton(onClick = { addParent = null; adding = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("قسم جديد", Modifier.padding(start = 6.dp))
        }
        LoadContent(state, onRetry = vm::load, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) { list ->
            if (list.isEmpty()) {
                if (inlineStatus) {
                    Text("لا توجد أقسام بعد", style = MaterialTheme.typography.bodyMedium, color = Lux.InkSoft)
                } else {
                    EmptyState(
                        Icons.AutoMirrored.Filled.List,
                        "لا توجد أقسام بعد",
                        "الأقسام ترتّب منتجاتك في التطبيق والكاشير، مثل: فساتين، عبايات، أحذية",
                        action = "إضافة قسم",
                        onAction = { addParent = null; adding = true },
                        modifier = Modifier.height(360.dp),
                    )
                }
            } else {
                GlassCard(Modifier.fillMaxWidth(), contentPadding = 8.dp, elevation = 4.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        list.forEachIndexed { i, (c, depth) ->
                            if (i > 0) HorizontalDivider(color = Lux.Hairline)
                            CategoryRow(
                                c,
                                depth,
                                enabled = !busy,
                                onToggle = { v -> vm.setVisible(c, v) },
                                onEdit = { editing = c },
                                onAddChild = { addParent = c; adding = true },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "القسم المخفي لا يظهر في تطبيق العملاء ولا تظهر منتجاته فيه، ويبقى متاحاً في الكاشير ولوحة التحكم.",
            style = MaterialTheme.typography.labelSmall,
            color = Lux.InkSoft,
        )
    }

    editing?.let { c ->
        CategoryNameDialog(
            title = "تعديل القسم",
            initialAr = c.nameAr,
            initialEn = c.nameEn,
            confirm = "حفظ",
            onDismiss = { editing = null },
        ) { ar, en ->
            editing = null
            vm.rename(c, ar, en)
        }
    }
    if (adding) {
        val parent = addParent
        CategoryNameDialog(
            title = parent?.let { "قسم فرعي في ${it.nameAr}" } ?: "قسم جديد",
            initialAr = "",
            initialEn = "",
            confirm = "إضافة",
            newCategory = true,
            onDismiss = { adding = false },
        ) { ar, en ->
            adding = false
            vm.add(ar, en, parent?.id)
        }
    }
}

@Composable
private fun CategoryRow(
    c: AdminCategory,
    depth: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onAddChild: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (c.isActive) 1f else 0.5f)) {
            Text(
                "${if (depth > 0) "↳ " else ""}${c.nameAr}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(c.nameEn, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            if (!c.isActive) Text("مخفي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Filled.Edit, contentDescription = "تعديل الاسم", tint = Lux.GoldDeep) }
        IconButton(onClick = onAddChild, enabled = enabled) { Icon(Icons.Filled.Add, contentDescription = "قسم فرعي", tint = Lux.GoldDeep) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("إظهار في التطبيق", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
            Switch(checked = c.isActive, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

/** Arabic and English names; a new category also needs an English name usable as its link. */
@Composable
private fun CategoryNameDialog(
    title: String,
    initialAr: String,
    initialEn: String,
    confirm: String,
    newCategory: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (nameAr: String, nameEn: String) -> Unit,
) {
    var nameAr by rememberSaveable { mutableStateOf(initialAr) }
    var nameEn by rememberSaveable { mutableStateOf(initialEn) }
    val enOk = if (newCategory) VariantPlanner.slugOf(nameEn).length >= 2 else nameEn.isNotBlank()
    val valid = nameAr.trim().length >= 2 && enOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it.take(80) },
                    label = { Text("الاسم بالعربية") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it.take(80) },
                    label = { Text("الاسم بالإنجليزية") },
                    supportingText = if (newCategory) ({ Text("يُستخدم في رابط القسم (أحرف إنجليزية)") }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(nameAr, nameEn) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}
