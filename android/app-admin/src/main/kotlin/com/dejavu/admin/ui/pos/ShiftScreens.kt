package com.dejavu.admin.ui.pos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.CashBox
import com.dejavu.core.admin.CashBoxKind
import com.dejavu.core.admin.CashierShift
import com.dejavu.core.admin.CloseShiftRequest
import com.dejavu.core.admin.OpenShiftRequest
import com.dejavu.core.admin.PosRepository
import com.dejavu.core.admin.StaffSession
import com.dejavu.core.admin.TreasuryRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.CurrencyArt
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDateTime
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

private const val CASHBOXES_MANAGE = "cashboxes.manage"

class ShiftViewModel(private val pos: PosRepository, private val treasury: TreasuryRepository) : BaseViewModel() {
    private val _current = MutableStateFlow<Load<CashierShift?>>(Load.Loading)

    /** The signed-in cashier's open shift (null = none open). */
    val current = _current.asStateFlow()
    private val _drawers = MutableStateFlow<List<CashBox>>(emptyList())

    /** The cashier's own drawers (one per currency), for the float when opening. */
    val drawers = _drawers.asStateFlow()
    private val _safes = MutableStateFlow<List<CashBox>>(emptyList())

    /** Safes this employee may take a float from / hand cash over to. */
    val safes = _safes.asStateFlow()
    private val _history = MutableStateFlow<Load<List<CashierShift>>>(Load.Loading)
    val history = _history.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore = _hasMore.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _result = MutableStateFlow<CashierShift?>(null)

    /** The shift just closed, to show its differences. */
    val result = _result.asStateFlow()
    private var page = 1

    fun load() {
        if (_current.value !is Load.Ready) _current.value = Load.Loading
        viewModelScope.launch { _current.value = pos.currentShift().toLoad() }
        viewModelScope.launch { treasury.mine().getOrNull()?.let { boxes -> _drawers.value = boxes.filter { it.kind == CashBoxKind.CASHIER && it.isActive } } }
        viewModelScope.launch { treasury.safes().getOrNull()?.let { _safes.value = it.filter { b -> b.isActive } } }
        viewModelScope.launch { fetchHistory(reset = true) }
    }

    fun more() {
        viewModelScope.launch { fetchHistory(reset = false) }
    }

    private suspend fun fetchHistory(reset: Boolean) {
        val next = if (reset) 1 else page + 1
        pos.shifts(next).fold(
            { p ->
                page = next
                val before = if (reset) emptyList() else (_history.value as? Load.Ready)?.value.orEmpty()
                _history.value = Load.Ready(before + p.items)
                _hasMore.value = p.hasMore
            },
            { e -> if (reset) _history.value = Load.Failed(e.userMessage()) else say(e.userMessage()) },
        )
    }

    fun open(floats: List<OpenShiftRequest.Float>, note: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            pos.openShift(OpenShiftRequest(floats, note.trim().ifBlank { null })).orSay()?.let {
                say("تم فتح الوردية ${it.number}")
                load()
            }
            _busy.value = false
        }
    }

    /** Closes the cashier's own shift ([shiftId] null) or, for managers, someone else's. */
    fun close(counts: List<CloseShiftRequest.Count>, handOver: List<CloseShiftRequest.HandOver>, note: String, shiftId: String?, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            pos.closeShift(CloseShiftRequest(counts, handOver, note.trim().ifBlank { null }), shiftId).orSay()?.let {
                _result.value = it
                onDone()
                load()
            }
            _busy.value = false
        }
    }

    fun dismissResult() {
        _result.value = null
    }
}

/** A typed count: zero or more, with no more decimals than [currency] allows. */
private fun countedAmount(text: String, currency: Currency): BigDecimal? =
    text.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= currency.decimals }

private fun amountIn(amount: String?, currency: Currency): String = Money.plain(Money.parse(amount), currency)

/** Opening, live balance and closing of the cashier's shift, plus the shift history. */
@Composable
fun ShiftScreen(session: StaffSession.LoggedIn, onBack: () -> Unit) {
    val vm = adminViewModel { ShiftViewModel(it.pos, it.treasury) }
    val current by vm.current.collectAsStateWithLifecycle()
    val drawers by vm.drawers.collectAsStateWithLifecycle()
    val safes by vm.safes.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val manager = session.can(CASHBOXES_MANAGE)
    val myId = session.profile?.id
    // The shift being closed: own (from current) or another cashier's (manager, from history).
    var closing by remember { mutableStateOf<CashierShift?>(null) }
    CollectMessages(vm, snackbar)
    LifecycleResumeEffect(vm) {
        vm.load()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(if (closing != null) "إقفال الوردية" else "الوردية", onBack = { if (closing != null) closing = null else onBack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val target = closing
        if (target != null) {
            CloseShiftForm(
                shift = target,
                safes = safes,
                busy = busy,
                own = target.id == (current as? Load.Ready)?.value?.id,
                modifier = Modifier.padding(padding),
                onCancel = { closing = null },
                onClose = { counts, handOver, note ->
                    val own = target.id == (current as? Load.Ready)?.value?.id
                    vm.close(counts, handOver, note, shiftId = if (own) null else target.id) { closing = null }
                },
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result?.let { r -> item(key = "result") { ShiftResultCard(r, onDismiss = vm::dismissResult) } }
            item(key = "current") {
                when (val c = current) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is Load.Failed -> GlassCard(Modifier.fillMaxWidth()) {
                        Text(c.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::load) { Text("إعادة المحاولة") }
                    }
                    is Load.Ready -> {
                        val shift = c.value
                        if (shift == null) {
                            OpenShiftCard(drawers, safes, busy, onOpen = vm::open)
                        } else {
                            OpenShiftDetails(shift, busy, onClose = { closing = shift })
                        }
                    }
                }
            }
            item(key = "h") {
                Text(if (manager) "سجل الورديات (كل الموظفين)" else "سجل الورديات", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            when (val h = history) {
                Load.Loading -> item(key = "hl") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                is Load.Failed -> item(key = "hf") {
                    Column {
                        Text(h.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::load) { Text("إعادة المحاولة") }
                    }
                }
                is Load.Ready -> {
                    if (h.value.isEmpty()) item(key = "he") { Text("لا توجد ورديات بعد", color = Lux.InkSoft) }
                    items(h.value, key = { it.id }) { s ->
                        val canClose = s.isOpen && manager && s.user?.id != null && s.user?.id != myId
                        ShiftHistoryRow(s, onClose = if (canClose) ({ closing = s }) else null)
                    }
                    if (hasMore) {
                        item(key = "more") { OutlinedButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ open ---

@Composable
private fun OpenShiftCard(drawers: List<CashBox>, safes: List<CashBox>, busy: Boolean, onOpen: (List<OpenShiftRequest.Float>, String) -> Unit) {
    val amounts = remember { mutableStateMapOf<String, String>() }
    val sources = remember { mutableStateMapOf<String, String>() }
    var note by remember { mutableStateOf("") }
    val currencies = drawers.map { it.currency }.distinct()
    // Each float typed must be a valid amount with a safe picked.
    val problems = currencies.any { c ->
        val text = amounts[c.code].orEmpty()
        text.isNotBlank() && (validAmount(text, c) == null || sources[c.code] == null)
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("لا توجد وردية مفتوحة", style = MaterialTheme.typography.titleMedium)
        Text(
            "افتح الوردية قبل البيع. يمكنك استلام رصيد بداية (فكة) من خزينة إلى درجك لكل عملة، أو تركه فارغاً.",
            style = MaterialTheme.typography.bodySmall,
            color = Lux.InkSoft,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        if (currencies.isEmpty()) {
            Text("لا توجد صناديق كاشير مرتبطة بحسابك.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        currencies.forEach { c ->
            val drawer = drawers.first { it.currency == c }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                CurrencyArt(c.code)
                Text(c.nameAr, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("في الدرج: ${drawer.shownBalance}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
            }
            OutlinedTextField(
                value = amounts[c.code].orEmpty(),
                onValueChange = { amounts[c.code] = decimalInput(it) },
                label = { Text("رصيد البداية (${c.symbol}) — اختياري") },
                singleLine = true,
                isError = amounts[c.code].orEmpty().isNotBlank() && validAmount(amounts[c.code].orEmpty(), c) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (amounts[c.code].orEmpty().isNotBlank()) {
                ShiftSafePicker(
                    label = "من الخزينة",
                    options = safes.filter { it.currency == c },
                    selectedId = sources[c.code],
                    noneLabel = "اختر الخزينة",
                    onSelect = { id ->
                        sources.remove(c.code)
                        if (id != null) sources[c.code] = id
                    },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(200) },
            label = { Text("ملاحظة (اختياري)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                val floats = currencies.mapNotNull { c ->
                    val amount = validAmount(amounts[c.code].orEmpty(), c) ?: return@mapNotNull null
                    val from = sources[c.code] ?: return@mapNotNull null
                    OpenShiftRequest.Float(c, amount.toPlainString(), from)
                }
                onOpen(floats, note)
            },
            enabled = !busy && !problems,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("فتح الوردية")
        }
    }
}

@Composable
private fun OpenShiftDetails(shift: CashierShift, busy: Boolean, onClose: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الوردية ${shift.number}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip("مفتوحة", Success)
        }
        Text("فُتحت: ${formatDateTime(shift.openedAt)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        shift.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("المبيعات: ${shift.salesCount} فاتورة", Modifier.weight(1f))
            Text(Money.formatBase(shift.salesTotal), style = MaterialTheme.typography.titleSmall, color = Lux.GoldDeep)
        }
        shift.lines.forEach { l ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CurrencyArt(l.currency.code)
                Text(l.currency.nameAr, style = MaterialTheme.typography.titleSmall)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("رصيد البداية", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(amountIn(l.opening, l.currency), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth()) {
                Text("المتوقع في الدرج الآن", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(l.expected?.let { amountIn(it, l.currency) } ?: "—", style = MaterialTheme.typography.titleSmall)
            }
        }
        Button(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("إقفال الوردية") }
    }
}

// ----------------------------------------------------------------- close ---

@Composable
private fun CloseShiftForm(
    shift: CashierShift,
    safes: List<CashBox>,
    busy: Boolean,
    own: Boolean,
    modifier: Modifier,
    onCancel: () -> Unit,
    onClose: (List<CloseShiftRequest.Count>, List<CloseShiftRequest.HandOver>, String) -> Unit,
) {
    val counted = remember(shift.id) { mutableStateMapOf<String, String>() }
    val handTo = remember(shift.id) { mutableStateMapOf<String, String>() }
    var note by remember(shift.id) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val valid = shift.lines.isNotEmpty() && shift.lines.all { countedAmount(counted[it.currency.code].orEmpty(), it.currency) != null }

    LazyColumn(modifier.imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("الوردية ${shift.number}${if (!own) " · ${shift.user?.name.orEmpty()}" else ""}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "عُدّ النقد في كل درج واكتب المبلغ. يُسجَّل أي فرق تلقائياً في حساب «عجز وزيادة الصناديق».",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(shift.lines, key = { it.id }) { l ->
            val c = l.currency
            val text = counted[c.code].orEmpty()
            val amount = countedAmount(text, c)
            val expected = l.expected?.let { Money.parse(it) }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyArt(c.code, height = 26.dp)
                    Text(c.nameAr, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("المتوقع: ${l.expected?.let { amountIn(it, c) } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { counted[c.code] = decimalInput(it) },
                    label = { Text("المبلغ المعدود (${c.symbol})") },
                    singleLine = true,
                    isError = text.isNotBlank() && amount == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                if (amount != null && expected != null) DifferenceText(amount.subtract(expected), c)
                ShiftSafePicker(
                    label = "تسليم المبلغ إلى",
                    options = safes.filter { it.currency == c },
                    selectedId = handTo[c.code],
                    noneLabel = "يبقى في الدرج",
                    onSelect = { id ->
                        handTo.remove(c.code)
                        if (id != null) handTo[c.code] = id
                    },
                )
            }
        }
        item {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("ملاحظة (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirm = true }, enabled = valid && !busy, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("إقفال الوردية")
                }
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("تراجع") }
            }
        }
    }

    if (confirm) {
        val summary = shift.lines.joinToString("\n") { l ->
            val amount = countedAmount(counted[l.currency.code].orEmpty(), l.currency)
            "${l.currency.nameAr}: ${amount?.let { Money.plain(it, l.currency) } ?: "—"}"
        }
        ConfirmDialog("تأكيد إقفال الوردية", "المبالغ المعدودة:\n$summary\n\nلا يمكن فتح الوردية نفسها بعد إقفالها.", "إقفال", onDismiss = { confirm = false }) {
            val counts = shift.lines.mapNotNull { l ->
                countedAmount(counted[l.currency.code].orEmpty(), l.currency)?.let { CloseShiftRequest.Count(l.currency, it.toPlainString()) }
            }
            val handOver = shift.lines.mapNotNull { l -> handTo[l.currency.code]?.let { CloseShiftRequest.HandOver(l.currency, it) } }
            onClose(counts, handOver, note)
        }
    }
}

@Composable
private fun DifferenceText(difference: BigDecimal, currency: Currency) {
    val (label, color) = when {
        difference.signum() > 0 -> "زيادة ${Money.plain(difference, currency)}" to Success
        difference.signum() < 0 -> "عجز ${Money.plain(difference.negate(), currency)}" to MaterialTheme.colorScheme.error
        else -> "مطابق ✓" to Success
    }
    Text(label, color = color, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ShiftResultCard(shift: CashierShift, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("تم إقفال الوردية ${shift.number} ✓", style = MaterialTheme.typography.titleMedium)
        Text("المبيعات: ${shift.salesCount} فاتورة · ${Money.formatBase(shift.salesTotal)}", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
        shift.lines.forEach { l ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CurrencyArt(l.currency.code)
                Column(Modifier.weight(1f)) {
                    Text("المتوقع: ${l.expected?.let { amountIn(it, l.currency) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Text("المعدود: ${l.counted?.let { amountIn(it, l.currency) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                }
                l.difference?.let { DifferenceText(Money.parse(it), l.currency) }
            }
        }
        TextButton(onClick = onDismiss) { Text("تم") }
    }
}

// --------------------------------------------------------------- history ---

@Composable
private fun ShiftHistoryRow(shift: CashierShift, onClose: (() -> Unit)?) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${shift.number}${shift.user?.name?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatDateTime(shift.openedAt)}${shift.closedAt?.let { " ← ${formatDateTime(it)}" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Lux.InkSoft,
                )
            }
            StatusChip(if (shift.isOpen) "مفتوحة" else "مغلقة", if (shift.isOpen) Success else Lux.InkSoft)
        }
        Text("المبيعات: ${shift.salesCount} · ${Money.formatBase(shift.salesTotal)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        shift.lines.filter { it.difference != null && !Money.isZero(it.difference) }.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                CurrencyArt(l.currency.code, height = 16.dp)
                DifferenceText(Money.parse(l.difference), l.currency)
            }
        }
        if (onClose != null) {
            TextButton(onClick = onClose) { Text("إقفال هذه الوردية") }
        }
    }
}

// ----------------------------------------------------------------- shared ---

/** Drop-down of the safes of one currency; [noneLabel] is the "no safe" choice. */
@Composable
private fun ShiftSafePicker(label: String, options: List<CashBox>, selectedId: String?, noneLabel: String, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    Text(label, style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft, modifier = Modifier.padding(top = 6.dp))
    Box {
        OutlinedButton(
            onClick = { open = true },
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selected?.let { "${it.name} · ${it.shownBalance}" } ?: noneLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    onSelect(null)
                    open = false
                },
            )
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("لا توجد خزائن بهذه العملة متاحة لك") }, onClick = { open = false }, enabled = false)
            }
            options.forEach { b ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(b.name)
                            Text(b.shownBalance, style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                        }
                    },
                    onClick = {
                        onSelect(b.id)
                        open = false
                    },
                )
            }
        }
    }
}
