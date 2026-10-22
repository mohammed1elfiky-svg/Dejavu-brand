@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.staff

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import com.dejavu.core.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.dejavu.core.ui.theme.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.admin.ui.treasury.label
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.CashBoxKind
import com.dejavu.core.admin.CreateStaffRequest
import com.dejavu.core.admin.PermissionLabels
import com.dejavu.core.admin.StaffRepository
import com.dejavu.core.admin.StaffRole
import com.dejavu.core.admin.StaffUser
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.admin.UpdateStaffRequest
import com.dejavu.core.data.userMessage
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ----------------------------------------------------------------- shared ---

/** Arabic names of the system roles (custom roles show their own name). */
internal fun roleLabel(name: String): String = when (name) {
    "admin" -> "مدير النظام"
    "accountant" -> "محاسب"
    "storekeeper" -> "أمين مخزن"
    "sales" -> "مبيعات"
    "cashier" -> "كاشير"
    else -> name
}

private fun statusLabel(status: String): String = when (status) {
    "ACTIVE" -> "نشط"
    "SUSPENDED" -> "موقوف"
    "DISABLED" -> "معطّل"
    else -> status
}

/** The users API answers in English; these are the messages staff can hit here. */
private fun staffError(t: Throwable): String {
    val raw = t.message.orEmpty()
    return when {
        "username is already used" in raw -> "اسم المستخدم مستخدم لموظف آخر"
        "username must be" in raw -> "اسم المستخدم: من 3 إلى 40 حرفاً إنجليزياً صغيراً أو أرقاماً أو . _ -"
        "Give the employee a role or some permissions" in raw -> "اختر دوراً أو بعض الصلاحيات للموظف"
        "cannot deactivate your own account" in raw -> "لا يمكنك إيقاف حسابك أنت"
        "At least one active admin must remain" in raw -> "يجب أن يبقى مدير نظام نشط واحد على الأقل"
        "Some cash boxes do not exist" in raw -> "بعض الصناديق المختارة لم تعد موجودة"
        "Unique constraint" in raw || ("email" in raw && "exist" in raw) -> "البريد الإلكتروني مستخدم لموظف آخر"
        "email must be an email" in raw -> "البريد الإلكتروني غير صحيح"
        "password must be longer" in raw -> "كلمة المرور 8 أحرف على الأقل"
        else -> t.userMessage()
    }
}

private val usernamePattern = Regex("^[a-z0-9][a-z0-9._-]{2,39}$")

private fun boxKindOrder(kind: CashBoxKind): Int = when (kind) {
    CashBoxKind.CASHIER -> 0
    CashBoxKind.VAULT -> 1
    CashBoxKind.BANK -> 2
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Lux.GoldDeep, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
        }
    }
}

// -------------------------------------------------------------------- list ---

class StaffListViewModel(private val repo: StaffRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<StaffUser>>>(Load.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list().map { it.items }.toLoad() }
    }
}

/** Employees with their roles, own permissions, status and assigned boxes. */
@Composable
fun StaffScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val vm = adminViewModel { StaffListViewModel(it.staff) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("الموظفون والصلاحيات", onBack) {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { open("new") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("موظف جديد") },
                containerColor = Lux.GoldDeep,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { users ->
            if (users.isEmpty()) {
                EmptyState(Icons.Filled.Person, "لا يوجد موظفون", modifier = Modifier.padding(padding))
            } else {
                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(users, key = { it.id }) { u -> StaffCard(u) { open(u.id) } }
                }
            }
        }
    }
}

@Composable
private fun StaffCard(u: StaffUser, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(u.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(u.username, u.email).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                }
                StatusChip(statusLabel(u.status), if (u.isActive) Success else Warning)
            }
            val roles = u.roles.joinToString("، ") { roleLabel(it.name) }.ifBlank { "بدون دور" }
            Text(
                buildString {
                    append(roles)
                    if (u.customPermissions.isNotEmpty()) append(" + ${u.customPermissions.size} صلاحية خاصة")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (u.cashBoxIds.isEmpty()) "صناديقه الخاصة فقط" else "${u.cashBoxIds.size} صندوق مخصص",
                style = MaterialTheme.typography.labelMedium,
                color = Lux.GoldDeep,
            )
        }
    }
}

// -------------------------------------------------------------------- edit ---

data class StaffEditData(val user: StaffUser?, val roles: List<StaffRole>, val boxes: List<CashBox>, val boxesFailed: Boolean)

/** What the form holds when saving. */
data class StaffForm(
    val name: String,
    val email: String,
    val username: String,
    val phone: String,
    val password: String,
    val active: Boolean,
    val roleIds: Set<String>,
    val permissions: Set<String>,
    val boxIds: Set<String>,
)

class StaffEditViewModel(
    private val id: String?,
    private val repo: StaffRepository,
    private val treasury: TreasuryRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<StaffEditData>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val user = id?.let { repo.get(it) }
            val roles = repo.roles()
            val boxes = treasury.all()
            val failure = user?.exceptionOrNull() ?: roles.exceptionOrNull()
            _state.value = if (failure != null) {
                Load.Failed(staffError(failure))
            } else {
                Load.Ready(
                    StaffEditData(
                        user = user?.getOrNull(),
                        roles = roles.getOrDefault(emptyList()),
                        boxes = boxes.getOrDefault(emptyList()),
                        boxesFailed = boxes.isFailure,
                    ),
                )
            }
        }
    }

    private fun Result<*>.failed(): Boolean {
        exceptionOrNull()?.let {
            say(staffError(it))
            return true
        }
        return false
    }

    fun save(form: StaffForm, done: () -> Unit) {
        if (_busy.value) return
        val before = (_state.value as? Load.Ready)?.value?.user
        _busy.value = true
        viewModelScope.launch {
            if (before == null) {
                val r = repo.create(
                    CreateStaffRequest(
                        name = form.name.trim(),
                        email = form.email.trim(),
                        password = form.password,
                        roleIds = form.roleIds.toList(),
                        username = form.username.trim().lowercase().ifBlank { null },
                        phone = form.phone.trim().ifBlank { null },
                        permissions = form.permissions.toList().takeIf { it.isNotEmpty() },
                        cashBoxIds = form.boxIds.toList().takeIf { it.isNotEmpty() },
                    ),
                )
                if (!r.failed()) {
                    say("تمت إضافة ${form.name.trim()}")
                    done()
                }
            } else {
                val username = form.username.trim().lowercase()
                val status = if (form.active) "ACTIVE" else "SUSPENDED"
                val request = UpdateStaffRequest(
                    name = form.name.trim().takeIf { it != before.name },
                    // "" clears the username.
                    username = username.takeIf { it != before.username.orEmpty() },
                    phone = form.phone.trim().takeIf { it != before.phone.orEmpty() },
                    status = status.takeIf { form.active != before.isActive },
                    password = form.password.takeIf { it.isNotEmpty() },
                    roleIds = form.roleIds.toList().takeIf { form.roleIds != before.roles.map { it.id }.toSet() },
                )
                var ok = true
                if (request != UpdateStaffRequest()) ok = !repo.update(before.id, request).failed()
                if (ok && form.permissions != before.customPermissions.toSet()) {
                    ok = !repo.setPermissions(before.id, form.permissions.sorted()).failed()
                }
                if (ok && form.boxIds != before.cashBoxIds.toSet()) {
                    ok = !repo.setBoxes(before.id, form.boxIds.toList()).failed()
                }
                if (ok) {
                    say("تم الحفظ. تُطبَّق التغييرات عند تسجيل دخول الموظف التالي")
                    done()
                } else {
                    // Some parts may have been saved; show what the server now holds.
                    val current = (_state.value as? Load.Ready)?.value
                    repo.get(before.id).onSuccess { u -> if (current != null) _state.value = Load.Ready(current.copy(user = u)) }
                }
            }
            _busy.value = false
        }
    }
}

/** New employee ([id] null) or editing one: account, roles, own permissions and cash boxes. */
@Composable
fun StaffEditScreen(id: String?, onBack: () -> Unit) {
    val vm = adminViewModel(key = "staff-edit:${id ?: "new"}") { StaffEditViewModel(id, it.staff, it.treasury) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        if (vm.state.value !is Load.Ready) vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (id == null) "موظف جديد" else "تعديل موظف", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { data ->
            StaffFormContent(data, busy, Modifier.padding(padding)) { form -> vm.save(form, onBack) }
        }
    }
}

@Composable
private fun StaffFormContent(data: StaffEditData, busy: Boolean, modifier: Modifier, onSave: (StaffForm) -> Unit) {
    val u = data.user
    val isNew = u == null
    var name by remember(u?.id) { mutableStateOf(u?.name.orEmpty()) }
    var email by remember(u?.id) { mutableStateOf(u?.email.orEmpty()) }
    var username by remember(u?.id) { mutableStateOf(u?.username.orEmpty()) }
    var phone by remember(u?.id) { mutableStateOf(u?.phone.orEmpty()) }
    var password by remember(u?.id) { mutableStateOf("") }
    var active by remember(u?.id) { mutableStateOf(u?.isActive ?: true) }
    var roleIds by remember(u) { mutableStateOf(u?.roles?.map { it.id }?.toSet() ?: emptySet()) }
    var perms by remember(u) { mutableStateOf(u?.customPermissions?.toSet() ?: emptySet()) }
    var boxIds by remember(u) { mutableStateOf(u?.cashBoxIds?.toSet() ?: emptySet()) }

    val fromRoles = data.roles.filter { it.id in roleIds }.flatMap { it.permissions }.toSet()
    val usernameOk = username.isBlank() || usernamePattern.matches(username.trim().lowercase())
    val passwordOk = if (isNew) password.length >= 8 else password.isEmpty() || password.length >= 8
    val emailOk = !isNew || Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val hasAccess = roleIds.isNotEmpty() || perms.isNotEmpty()
    val valid = name.trim().length >= 2 && emailOk && usernameOk && passwordOk && (!isNew || hasAccess)

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        // ---- account
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("بيانات الحساب", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(100) }, label = { Text("الاسم") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it.take(150) }, label = { Text("البريد الإلكتروني") },
                    enabled = isNew, isError = isNew && email.isNotBlank() && !emailOk,
                    supportingText = if (!isNew) ({ Text("لا يمكن تغيير البريد الإلكتروني") }) else null,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it.take(40) }, label = { Text("اسم المستخدم (اختياري)") },
                    isError = !usernameOk,
                    supportingText = { Text("يسجّل به الدخول بدل البريد: أحرف إنجليزية صغيرة وأرقام و . _ -") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(20) },
                    label = { Text("رقم الجوال (اختياري)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it.take(128) },
                    label = { Text(if (isNew) "كلمة المرور" else "كلمة مرور جديدة (اختياري)") },
                    isError = password.isNotEmpty() && password.length < 8,
                    supportingText = { Text(if (isNew) "8 أحرف على الأقل" else "اتركها فارغة لإبقاء كلمة المرور الحالية") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("الحساب نشط")
                            Text(
                                if (active) "يستطيع الموظف تسجيل الدخول" else "موقوف: لا يستطيع تسجيل الدخول",
                                style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft,
                            )
                        }
                        Switch(checked = active, onCheckedChange = { active = it })
                    }
                }
            }
        }

        // ---- roles
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الأدوار", style = MaterialTheme.typography.titleSmall)
                if (data.roles.isEmpty()) {
                    Text("تعذّر تحميل الأدوار", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    data.roles.forEach { r ->
                        FilterChip(
                            selected = r.id in roleIds,
                            onClick = { roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id },
                            label = { Text(roleLabel(r.name)) },
                        )
                    }
                }
                Text(
                    "صلاحيات الأدوار تُضاف تلقائياً، ويمكنك إضافة صلاحيات خاصة لهذا الموظف بالأسفل",
                    style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft,
                )
            }
        }

        // ---- own permissions
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الصلاحيات الخاصة", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (perms.isNotEmpty()) GoldPill { Text("${perms.size}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                }
                Text("قوالب جاهزة", style = MaterialTheme.typography.labelMedium, color = Lux.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PermissionLabels.presets.forEach { (title, keys) ->
                        AssistChip(onClick = { perms = keys.toSet() }, label = { Text(title) })
                    }
                    AssistChip(onClick = { perms = emptySet() }, label = { Text("مسح الكل") })
                }
                PermissionLabels.groups.forEach { (group, items) ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val own = items.map { it.first }.filter { it !in fromRoles }
                    val allOn = own.isNotEmpty() && own.all { it in perms }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group, style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { perms = if (allOn) perms - own.toSet() else perms + own },
                            enabled = own.isNotEmpty(),
                        ) { Text(if (allOn) "إلغاء الكل" else "تحديد الكل") }
                    }
                    items.forEach { (key, label) ->
                        val viaRole = key in fromRoles
                        CheckRow(
                            text = label,
                            checked = viaRole || key in perms,
                            enabled = !viaRole,
                            hint = if (viaRole) "من الدور" else null,
                        ) { on -> perms = if (on) perms + key else perms - key }
                    }
                }
                // Keys the server knows but this app has no label for stay as they are.
                val unknown = perms.filter { k -> PermissionLabels.groups.none { g -> g.second.any { it.first == k } } }
                if (unknown.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    unknown.forEach { k -> CheckRow(PermissionLabels.label(k), true) { on -> if (!on) perms = perms - k } }
                }
            }
        }

        // ---- cash boxes
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("الصناديق المسموح بها", style = MaterialTheme.typography.titleSmall)
                Text(
                    "إن لم تختر أي صندوق يستخدم الموظف صناديقه الخاصة فقط. إن اخترت صناديق فلن يرى ويستخدم إلا هذه الصناديق، ويذهب ما يقبضه في الكاشير إلى صندوق مختار بنفس العملة.",
                    style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft,
                )
                if (data.boxesFailed) {
                    Text("تعذّر تحميل الصناديق", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                val groups = data.boxes
                    .sortedWith(compareBy<CashBox>({ boxKindOrder(it.kind) }, { it.currency.code }, { it.name }))
                    .groupBy { it.kind to it.currency }
                groups.forEach { (k, list) ->
                    Spacer(Modifier.height(4.dp))
                    GroupTitle("${k.first.label} · ${k.second.nameAr}")
                    list.forEach { b ->
                        CheckRow(
                            text = b.name,
                            checked = b.id in boxIds,
                            hint = listOfNotNull(b.currency.nameAr, b.user?.name, "موقوف".takeIf { !b.isActive }).joinToString(" · "),
                        ) { on -> boxIds = if (on) boxIds + b.id else boxIds - b.id }
                    }
                }
                // Assigned boxes that are no longer listed.
                val missing = boxIds.filter { bid -> data.boxes.none { it.id == bid } }
                if (missing.isNotEmpty() && !data.boxesFailed) {
                    TextButton(onClick = { boxIds = boxIds - missing.toSet() }) { Text("إزالة ${missing.size} صندوق غير موجود") }
                }
            }
        }

        if (isNew && !hasAccess) {
            Text("اختر دوراً أو بعض الصلاحيات للموظف", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "تُطبَّق التغييرات على الموظف عند تسجيل دخوله التالي.",
            style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft,
        )
        Button(
            onClick = {
                onSave(StaffForm(name, email, username, phone, password, active, roleIds, perms, boxIds))
            },
            enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Text(if (isNew) "إضافة الموظف" else "حفظ", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
    }
}
