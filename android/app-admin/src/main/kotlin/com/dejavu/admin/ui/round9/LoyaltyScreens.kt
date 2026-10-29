@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.admin.ui.round9

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.AdminPost
import com.dejavu.core.admin.AmbassadorSettings
import com.dejavu.core.admin.LoyaltySettings
import com.dejavu.core.admin.Round9Repository
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import java.time.LocalDate
import java.time.ZoneId

class LoyaltySettingsViewModel(private val repo: Round9Repository) : LoadViewModel<LoyaltySettings>({ repo.loyaltySettings() }) {
    fun save(s: LoyaltySettings) = act("تم حفظ إعدادات النقاط") { repo.setLoyaltySettings(s) }
}

private val scopes = listOf("ALL" to "كل العملاء", "RETAIL" to "عملاء التجزئة فقط", "MERCHANTS" to "التجار فقط", "SELECTED" to "عملاء أختارهم")

/** Loyalty points: who earns, how many per riyal, what 100 points are worth, and from when. */
@Composable
fun LoyaltySettingsScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "loyalty") { LoyaltySettingsViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("نقاط الولاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { s ->
                var enabled by rememberSaveable(s) { mutableStateOf(s.enabled) }
                var scope by rememberSaveable(s) { mutableStateOf(s.scope) }
                var perSar by rememberSaveable(s) { mutableStateOf(trimNumber(s.pointsPerSar)) }
                var worth by rememberSaveable(s) { mutableStateOf(trimNumber(s.sarPer100Points)) }
                var minRedeem by rememberSaveable(s) { mutableStateOf(s.minRedeemPoints.toString()) }
                var from by rememberSaveable(s) { mutableStateOf(s.startsAt?.let { formatIsoDay(it) }.orEmpty()) }
                val fromValid = from.isBlank() || runCatching { LocalDate.parse(from.trim()) }.isSuccess
                val valid = perSar.toDoubleOrNull() != null && worth.toDoubleOrNull() != null && (minRedeem.toIntOrNull() ?: 0) >= 1 && fromValid
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("تفعيل النقاط", style = MaterialTheme.typography.titleMedium)
                                Text("يربح العميل نقاطاً عند استلام طلبه أو الشراء من المحل، ويحوّلها إلى رصيد.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("من يربح النقاط", style = MaterialTheme.typography.titleSmall)
                        scopes.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (key, label) -> FilterChip(selected = scope == key, onClick = { scope = key }, label = { Text(label) }) }
                            }
                        }
                        Text("تقدر تفعّل أو توقف النقاط لعميل واحد من صفحة العميل.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = perSar, onValueChange = { perSar = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("نقاط لكل 1 ر.س") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = worth, onValueChange = { worth = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("قيمة 100 نقطة (ر.س)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = minRedeem, onValueChange = { minRedeem = it.filter(Char::isDigit).take(6) }, label = { Text("أقل عدد نقاط للاستبدال") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = from,
                                onValueChange = { from = it.take(10) },
                                label = { Text("احتساب المشتريات من تاريخ (yyyy-mm-dd)") },
                                supportingText = { Text("اتركه فارغاً ليبدأ من لحظة التفعيل") },
                                isError = !fromValid,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    val example = (perSar.toDoubleOrNull() ?: 0.0) * 100
                    Text(
                        "مثال: طلب بـ 100 ر.س = ${trimNumber(example)} نقطة، وكل 100 نقطة = ${worth.ifBlank { "0" }} ر.س في رصيد العميل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Lux.InkSoft,
                    )
                    Button(
                        onClick = {
                            vm.save(
                                LoyaltySettings(
                                    enabled = enabled,
                                    scope = scope,
                                    pointsPerSar = perSar.toDouble(),
                                    sarPer100Points = worth.toDouble(),
                                    minRedeemPoints = minRedeem.toInt(),
                                    startsAt = from.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).atStartOfDay(ZoneId.of("Asia/Aden")).toInstant().toString() },
                                ),
                            )
                        },
                        enabled = valid && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("حفظ") }
                }
            }
        }
    }
}

private fun trimNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

private fun formatIsoDay(iso: String): String = runCatching { java.time.Instant.parse(iso).atZone(ZoneId.of("Asia/Aden")).toLocalDate().toString() }.getOrDefault("")

// ------------------------------------------------------------ ambassadors ---

class SocialAdminViewModel(private val repo: Round9Repository) : LoadViewModel<Pair<AmbassadorSettings, List<AdminPost>>>({
    repo.ambassadorSettings().mapCatching { s -> s to repo.posts().getOrThrow() }
}) {
    fun save(s: AmbassadorSettings) = act("تم الحفظ") { repo.setAmbassadorSettings(s) }
    fun hide(id: String, hidden: Boolean) = act(if (hidden) "أُخفي المنشور" else "أُظهر المنشور") { repo.hidePost(id, hidden) }
}

/** Ambassador rewards (a share of what sells through a customer's photo) and the posts customers publish. */
@Composable
fun SocialAdminScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "social-admin") { SocialAdminViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("السفراء ومنشورات العملاء", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { (settings, posts) ->
                var enabled by rememberSaveable(settings) { mutableStateOf(settings.enabled) }
                var percent by rememberSaveable(settings) { mutableStateOf(trimNumber(settings.percent)) }
                var kind by rememberSaveable(settings) { mutableStateOf(settings.kind) }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("برنامج السفراء", style = MaterialTheme.typography.titleMedium)
                                        Text("إذا اشترى أحد منتجاً من صورة عميل (تاغ أو «اشترِ الإطلالة») يأخذ صاحب الصورة نسبة بعد استلام الطلب.", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                                }
                                OutlinedTextField(value = percent, onValueChange = { percent = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("النسبة من قيمة المشتريات %") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = kind == "POINTS", onClick = { kind = "POINTS" }, label = { Text("نقاط ولاء") })
                                    FilterChip(selected = kind == "WALLET", onClick = { kind = "WALLET" }, label = { Text("رصيد في المحفظة") })
                                }
                                val p = percent.toDoubleOrNull()
                                Button(onClick = { vm.save(AmbassadorSettings(enabled, p ?: 0.0, kind)) }, enabled = p != null && p <= 50 && !busy, modifier = Modifier.fillMaxWidth()) { Text("حفظ") }
                            }
                        }
                    }
                    item { Text("آخر المنشورات", style = MaterialTheme.typography.titleMedium) }
                    if (posts.isEmpty()) item { Text("لا توجد منشورات بعد", color = Lux.InkSoft) }
                    items(posts, key = { it.id }) { p ->
                        GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(
                                    model = p.images.firstOrNull(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("@${p.author.username ?: ""}" + if (p.author.instagramVerified) " ✔" else "", style = MaterialTheme.typography.titleSmall)
                                    p.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                                    Text("❤ ${p.likeCount} · 💬 ${p.commentCount} · ${p.tags.size} منتج · ${formatDate(p.createdAt)}", style = MaterialTheme.typography.labelSmall, color = Lux.InkSoft)
                                    if (p.isHidden) Text("مخفي عن العملاء", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            OutlinedButton(onClick = { vm.hide(p.id, !p.isHidden) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (p.isHidden) "إظهار المنشور" else "إخفاء المنشور") }
                        }
                    }
                }
            }
        }
    }
}
