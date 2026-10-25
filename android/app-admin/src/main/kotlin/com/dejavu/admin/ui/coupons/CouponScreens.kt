@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.dejavu.admin.ui.coupons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.RefreshList
import com.dejavu.admin.ui.Refresher
import androidx.compose.foundation.layout.fillMaxSize
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.GoldPill
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminCoupon
import com.dejavu.core.admin.CouponRepository
import com.dejavu.core.admin.CouponRequest
import com.dejavu.core.admin.CouponType
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.theme.Warning
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ------------------------------------------------------------------ helpers ---

private val CODE_REGEX = Regex("^[A-Z0-9_-]{3,40}$")

/** A money amount the server accepts (≥ 0, up to 2 decimals, 12 digits), or null. */
private fun amountOf(text: String): BigDecimal? =
    text.trim().replace(',', '.').toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 && it.scale() <= 2 && it.precision() - it.scale() <= 12 }

private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString().let { if (it.contains('E')) v.toPlainString() else it }

/** A server amount shown without trailing zeros in a form field. */
private fun fieldOf(amount: String?): String = amount?.toBigDecimalOrNull()?.let(::plain).orEmpty()

/** yyyy-MM-dd (or an ISO timestamp) → its UTC date, or null. */
private fun dayOf(iso: String?): LocalDate? = iso?.let {
    runCatching { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
}

private fun startIso(day: LocalDate): String = "${day}T00:00:00Z"

/** The coupon works until the end of its last day. */
private fun endIso(day: LocalDate): String = "${day}T23:59:59Z"

private fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

private val CouponType.label: String
    get() = when (this) {
        CouponType.PERCENTAGE -> "نسبة مئوية"
        CouponType.FIXED_AMOUNT -> "مبلغ ثابت"
        CouponType.FREE_SHIPPING -> "شحن مجاني"
    }

/** "10%" / "50 ر.س" / "شحن مجاني". */
private fun AdminCoupon.discountText(): String = when (type) {
    CouponType.PERCENTAGE -> "${fieldOf(value).ifBlank { "0" }}%"
    CouponType.FIXED_AMOUNT -> Money.formatBase(value)
    CouponType.FREE_SHIPPING -> "شحن مجاني"
}

private enum class CouponState(val label: String) { RUNNING("يعمل الآن"), SCHEDULED("مجدول"), ENDED("منتهي"), USED_UP("نفد"), PAUSED("متوقف") }

private fun AdminCoupon.state(now: Instant = Instant.now()): CouponState = when {
    !isActive -> CouponState.PAUSED
    parseInstant(endsAt)?.let { !it.isAfter(now) } == true -> CouponState.ENDED
    usageLimit?.let { usedCount >= it } == true -> CouponState.USED_UP
    parseInstant(startsAt)?.isAfter(now) == true -> CouponState.SCHEDULED
    else -> CouponState.RUNNING
}

@Composable
private fun CouponState.tint(): Color = when (this) {
    CouponState.RUNNING -> Success
    CouponState.SCHEDULED -> Lux.GoldDeep
    CouponState.ENDED, CouponState.USED_UP -> MaterialTheme.colorScheme.onSurfaceVariant
    CouponState.PAUSED -> Warning
}

// --------------------------------------------------------------- view model ---

class CouponsViewModel(private val repo: CouponRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<List<AdminCoupon>>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch { _state.value = repo.list().toLoad() }
    }

    val refresher = Refresher()

    fun refresh() = refresher.run(viewModelScope) { _state.value = repo.list().toLoad() }

    /** Creates the coupon ([id] = null) or saves changes to it. */
    fun save(id: String?, request: CouponRequest, done: () -> Unit) = act {
        val result = if (id == null) repo.create(request) else repo.update(id, request)
        result.orSay()?.let { saved ->
            upsert(saved)
            say(if (id == null) "تمت إضافة الكود ${saved.code}" else "تم حفظ الكود ${saved.code}")
            done()
        }
    }

    fun setActive(c: AdminCoupon, active: Boolean) = act {
        repo.update(c.id, CouponRequest(isActive = active)).orSay()?.let { saved ->
            upsert(saved)
            say(if (active) "تم تفعيل الكود ${saved.code}" else "تم إيقاف الكود ${saved.code}")
        }
    }

    private fun upsert(c: AdminCoupon) {
        val list = (_state.value as? Load.Ready)?.value ?: emptyList()
        _state.value = Load.Ready(if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else listOf(c) + list)
    }

    private fun act(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block()
            _busy.value = false
        }
    }
}

// ------------------------------------------------------------------- screen ---

/** Discount codes customers enter in the app or at the till. */
@Composable
fun CouponsScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "coupons") { CouponsViewModel(it.coupons) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refresher.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var formOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminCoupon?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("أكواد الخصم", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; formOpen = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("كود جديد") },
                containerColor = Lux.GoldDeep,
                contentColor = Color.White,
            )
        },
    ) { padding ->
        LoadContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            RefreshList(refreshing, onRefresh = vm::refresh, modifier = Modifier.padding(padding)) {
            if (list.isEmpty()) {
                EmptyList(
                    Icons.Filled.Favorite,
                    "لا توجد أكواد خصم بعد",
                    "أنشئ كوداً بنسبة أو مبلغ ثابت أو شحن مجاني، يعمل في التطبيق وفي الكاشير.",
                    action = "إنشاء كود",
                    onAction = { editing = null; formOpen = true },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(list, key = { it.id }) { c ->
                        CouponCard(
                            c,
                            busy = busy,
                            onOpen = { editing = c; formOpen = true },
                            onToggle = { vm.setActive(c, it) },
                        )
                    }
                }
            }
            }
        }
    }

    if (formOpen) {
        CouponFormDialog(edit = editing, busy = busy, onDismiss = { formOpen = false }) { request ->
            vm.save(editing?.id, request) { formOpen = false }
        }
    }
}

@Composable
private fun CouponCard(c: AdminCoupon, busy: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val st = c.state()
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldPill { Text(c.discountText(), color = Color.White, style = MaterialTheme.typography.titleMedium) }
            Column(Modifier.weight(1f)) {
                Text(c.code, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                c.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusChip(st.label, st.tint())
        }
        val conditions = buildList {
            c.minOrderAmount?.takeIf { !Money.isZero(it) }?.let { add("حد أدنى للطلب ${Money.formatBase(it)}") }
            if (c.type == CouponType.PERCENTAGE) c.maxDiscount?.takeIf { !Money.isZero(it) }?.let { add("أقصى خصم ${Money.formatBase(it)}") }
            c.perCustomerLimit?.let { add("$it لكل عميل") }
        }
        if (conditions.isNotEmpty()) {
            Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "الاستخدام: ${c.usedCount} / ${c.usageLimit?.toString() ?: "بلا حد"}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "من ${dayOf(c.startsAt) ?: ""}" + (dayOf(c.endsAt)?.let { " · حتى $it" } ?: " · بدون تاريخ انتهاء"),
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مفعّل", Modifier.weight(1f))
            Switch(checked = c.isActive, onCheckedChange = onToggle, enabled = !busy)
        }
    }
}

// --------------------------------------------------------------------- form ---

@Composable
private fun CouponFormDialog(edit: AdminCoupon?, busy: Boolean, onDismiss: () -> Unit, onSave: (CouponRequest) -> Unit) {
    val today = remember { LocalDate.now() }
    val originalStart = remember(edit) { dayOf(edit?.startsAt) }
    val originalEnd = remember(edit) { dayOf(edit?.endsAt) }
    var code by remember { mutableStateOf(edit?.code.orEmpty()) }
    var description by remember { mutableStateOf(edit?.description.orEmpty()) }
    var type by remember { mutableStateOf(edit?.type ?: CouponType.PERCENTAGE) }
    var value by remember { mutableStateOf(if (edit?.type == CouponType.FREE_SHIPPING) "" else fieldOf(edit?.value)) }
    var minOrder by remember { mutableStateOf(fieldOf(edit?.minOrderAmount)) }
    var maxDiscount by remember { mutableStateOf(fieldOf(edit?.maxDiscount)) }
    var usageLimit by remember { mutableStateOf(edit?.usageLimit?.toString().orEmpty()) }
    var perCustomer by remember { mutableStateOf(edit?.perCustomerLimit?.toString().orEmpty()) }
    var start by remember { mutableStateOf(originalStart ?: today) }
    var end by remember { mutableStateOf(originalEnd) }
    var active by remember { mutableStateOf(edit?.isActive ?: true) }
    var picking by remember { mutableStateOf<Int?>(null) }

    // ---- validation
    val codeError = when {
        edit != null -> null
        code.isBlank() -> "أدخل الكود"
        !CODE_REGEX.matches(code) -> "من 3 إلى 40 حرفاً: أحرف إنجليزية وأرقام و _ و -"
        else -> null
    }
    val valueNumber = amountOf(value)
    val valueError = when (type) {
        CouponType.FREE_SHIPPING -> null
        CouponType.PERCENTAGE -> when {
            value.isBlank() -> "أدخل النسبة"
            valueNumber == null || valueNumber.signum() <= 0 || valueNumber > BigDecimal(100) -> "نسبة بين 0 و 100 (حتى خانتين عشريتين)"
            else -> null
        }
        CouponType.FIXED_AMOUNT -> when {
            value.isBlank() -> "أدخل المبلغ"
            valueNumber == null || valueNumber.signum() <= 0 -> "مبلغ أكبر من صفر (حتى خانتين عشريتين)"
            else -> null
        }
    }
    // Blank on an edit leaves the saved value as is (the server can't clear it), so it is not allowed once set.
    fun keptError(text: String, had: Boolean) = if (text.isBlank() && edit != null && had) "لا يمكن ترك الحقل فارغاً بعد حفظه" else null
    val minOrderNumber = amountOf(minOrder)
    val minOrderError = keptError(minOrder, edit?.minOrderAmount != null)
        ?: if (minOrder.isNotBlank() && minOrderNumber == null) "مبلغ غير صحيح" else null
    val maxDiscountNumber = amountOf(maxDiscount)
    val maxDiscountError = if (type != CouponType.PERCENTAGE) {
        null
    } else {
        keptError(maxDiscount, edit?.maxDiscount != null)
            ?: if (maxDiscount.isNotBlank() && (maxDiscountNumber == null || maxDiscountNumber.signum() <= 0)) "مبلغ أكبر من صفر" else null
    }
    val usageNumber = usageLimit.trim().toIntOrNull()
    val usageError = keptError(usageLimit, edit?.usageLimit != null)
        ?: if (usageLimit.isNotBlank() && (usageNumber == null || usageNumber < 1)) "عدد صحيح 1 أو أكثر" else null
    val perCustomerNumber = perCustomer.trim().toIntOrNull()
    val perCustomerError = keptError(perCustomer, edit?.perCustomerLimit != null)
        ?: if (perCustomer.isNotBlank() && (perCustomerNumber == null || perCustomerNumber < 1)) "عدد صحيح 1 أو أكثر" else null
    val endError = end?.let { if (it.isBefore(start)) "تاريخ الانتهاء قبل البداية" else null }
    val ok = listOf(codeError, valueError, minOrderError, maxDiscountError, usageError, perCustomerError, endError).all { it == null }

    fun request(): CouponRequest = CouponRequest(
        code = if (edit == null) code.trim().uppercase() else null,
        description = description.trim().ifBlank { null },
        type = type,
        value = if (type == CouponType.FREE_SHIPPING) "0" else plain(valueNumber!!),
        minOrderAmount = minOrderNumber?.let(::plain),
        maxDiscount = if (type == CouponType.PERCENTAGE) maxDiscountNumber?.let(::plain) else null,
        usageLimit = usageNumber,
        perCustomerLimit = perCustomerNumber,
        // On an edit, unchanged dates are not sent so their saved time of day is kept.
        startsAt = if (edit == null || start != originalStart) startIso(start) else null,
        endsAt = end?.takeIf { edit == null || it != originalEnd }?.let(::endIso),
        isActive = active,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit == null) "كود خصم جديد" else "تعديل الكود ${edit.code}") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit == null) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }.take(40) },
                        label = { Text("الكود") },
                        supportingText = { Text(codeError ?: "مثال: WELCOME10") },
                        isError = code.isNotBlank() && codeError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(255) },
                    label = { Text("الوصف (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("نوع الخصم", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CouponType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                when (type) {
                    CouponType.PERCENTAGE -> NumberField("النسبة %", value, valueError) { value = it }
                    CouponType.FIXED_AMOUNT -> NumberField("المبلغ (ر.س)", value, valueError) { value = it }
                    CouponType.FREE_SHIPPING -> Text("يُلغى سعر الشحن عن الطلب", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                }
                NumberField("الحد الأدنى للطلب (ر.س، اختياري)", minOrder, minOrderError) { minOrder = it }
                if (type == CouponType.PERCENTAGE) {
                    NumberField("أقصى خصم (ر.س، اختياري)", maxDiscount, maxDiscountError) { maxDiscount = it }
                }
                NumberField("عدد مرات الاستخدام الكلي (اختياري)", usageLimit, usageError, integer = true) { usageLimit = it }
                NumberField("مرات الاستخدام لكل عميل (اختياري)", perCustomer, perCustomerError, integer = true) { perCustomer = it }
                Text("مدة الصلاحية", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = 0 }, modifier = Modifier.weight(1f)) { Text("من $start") }
                    OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f)) { Text(end?.let { "حتى $it" } ?: "بدون انتهاء") }
                }
                // A saved end date can't be removed (the server keeps it when none is sent).
                if (end != null && (edit == null || originalEnd == null)) {
                    TextButton(onClick = { end = null }) { Text("إزالة تاريخ الانتهاء") }
                }
                endError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مفعّل", Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { active = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = ok && !busy, onClick = { onSave(request()) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
    when (picking) {
        0 -> CouponDayPicker(start, onDismiss = { picking = null }) { start = it; picking = null }
        1 -> CouponDayPicker(end ?: if (start.isAfter(today)) start else today, onDismiss = { picking = null }) { end = it; picking = null }
    }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (!integer && (it == '.' || it == ',')) }.take(14)) },
        label = { Text(label) },
        supportingText = error?.let { { Text(it) } },
        isError = error != null && (value.isNotBlank() || error.startsWith("لا يمكن")),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CouponDayPicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The date picker works in UTC midnights.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    ) { DatePicker(state = state) }
}
