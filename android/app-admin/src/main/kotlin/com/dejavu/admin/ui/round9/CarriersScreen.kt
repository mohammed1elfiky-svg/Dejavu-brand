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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.ConfirmDialog
import com.dejavu.admin.ui.EmptyList
import com.dejavu.admin.ui.adminViewModel
import com.dejavu.admin.ui.theme.GlassCard
import com.dejavu.admin.ui.theme.Lux
import com.dejavu.core.admin.Carrier
import com.dejavu.core.admin.CarrierOfficeRequest
import com.dejavu.core.admin.CarrierRequest
import com.dejavu.core.admin.Round9Repository
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.theme.OutlinedTextField

class CarriersViewModel(private val repo: Round9Repository) : LoadViewModel<List<Carrier>>({ repo.carriers() }) {
    fun create(name: String, phones: String) = act("أُضيفت شركة الشحن") { repo.createCarrier(name, phonesList(phones)) }
    fun update(id: String, name: String, phones: String, active: Boolean) =
        act("تم الحفظ") { repo.updateCarrier(id, CarrierRequest(name = name.trim(), phones = phonesList(phones), isActive = active)) }
    fun remove(id: String) = act<com.dejavu.core.admin.CarrierRemoved>(null, then = { say(if (it.hidden) "أُخفيت لأنها مستخدمة في طلبات سابقة" else "حُذفت") }) { repo.removeCarrier(id) }
    fun saveOffice(carrierId: String, officeId: String?, r: CarrierOfficeRequest) =
        act("تم حفظ المكتب") { if (officeId == null) repo.addOffice(carrierId, r) else repo.updateOffice(officeId, r) }
    fun removeOffice(id: String) = act("تم") { repo.removeOffice(id) }
}

private sealed interface CarrierDialog {
    data class EditCarrier(val carrier: Carrier?) : CarrierDialog
    data class EditOffice(val carrierId: String, val office: Carrier.Office?) : CarrierDialog
    data class Remove(val carrier: Carrier) : CarrierDialog
}

/** Local shipping companies and their offices (customers pick the office nearest to them). */
@Composable
fun CarriersScreen(onBack: () -> Unit) {
    val vm = adminViewModel(key = "carriers") { CarriersViewModel(it.round9) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<CarrierDialog?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("شركات الشحن ومكاتبها", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = CarrierDialog.EditCarrier(null) }, containerColor = Lux.Gold) { Icon(Icons.Filled.Add, "إضافة شركة") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { list ->
                if (list.isEmpty()) {
                    EmptyList(Icons.Filled.Home, "لا توجد شركات شحن", "أضف شركات الشحن المحلية (مثل الجوال القدسي) ومكاتبها مع أرقام التواصل", "إضافة شركة") { dialog = CarrierDialog.EditCarrier(null) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { c ->
                            GlassCard(Modifier.fillMaxWidth(), elevation = 4.dp, contentPadding = 12.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name + if (!c.isActive) " (مخفية)" else "", style = MaterialTheme.typography.titleMedium)
                                        if (c.phones.isNotEmpty()) Text(c.phones.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                    }
                                    IconButton(onClick = { dialog = CarrierDialog.EditCarrier(c) }, enabled = !busy) { Icon(Icons.Filled.Edit, "تعديل") }
                                    IconButton(onClick = { dialog = CarrierDialog.Remove(c) }, enabled = !busy) { Icon(Icons.Filled.Delete, "حذف") }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                if (c.offices.isEmpty()) Text("لا توجد مكاتب بعد", style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                c.offices.forEach { o ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${o.name} · ${o.governorate}${o.city?.let { " / $it" } ?: ""}" + if (!o.isActive) " (مخفي)" else "", style = MaterialTheme.typography.bodyMedium)
                                            Text(listOfNotNull(o.address, o.phones.joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lux.InkSoft)
                                        }
                                        IconButton(onClick = { dialog = CarrierDialog.EditOffice(c.id, o) }, enabled = !busy) { Icon(Icons.Filled.Edit, "تعديل المكتب") }
                                        IconButton(onClick = { vm.removeOffice(o.id) }, enabled = !busy) { Icon(Icons.Filled.Delete, "حذف المكتب") }
                                    }
                                }
                                TextButton(onClick = { dialog = CarrierDialog.EditOffice(c.id, null) }, enabled = !busy) { Text("+ إضافة مكتب") }
                            }
                        }
                    }
                }
            }
        }
    }
    when (val d = dialog) {
        null -> Unit
        is CarrierDialog.EditCarrier -> CarrierDialogView(d.carrier, onDismiss = { dialog = null }) { name, phones, active ->
            dialog = null
            if (d.carrier == null) vm.create(name, phones) else vm.update(d.carrier.id, name, phones, active)
        }
        is CarrierDialog.EditOffice -> OfficeDialogView(d.office, onDismiss = { dialog = null }) { r ->
            dialog = null
            vm.saveOffice(d.carrierId, d.office?.id, r)
        }
        is CarrierDialog.Remove -> ConfirmDialog("حذف ${d.carrier.name}؟", "إن كانت مستخدمة في طلبات سابقة تُخفى فقط.", "حذف", onDismiss = { dialog = null }) { vm.remove(d.carrier.id) }
    }
}

@Composable
private fun CarrierDialogView(carrier: Carrier?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(carrier?.name.orEmpty()) }
    var phones by rememberSaveable { mutableStateOf(carrier?.phones?.joinToString(", ").orEmpty()) }
    var active by rememberSaveable { mutableStateOf(carrier?.isActive ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (carrier == null) "شركة شحن جديدة" else "تعديل ${carrier.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("اسم الشركة") }, singleLine = true)
                OutlinedTextField(value = phones, onValueChange = { phones = it.take(200) }, label = { Text("أرقام التواصل (افصل بفاصلة)") })
                if (carrier != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ظاهرة للعملاء", Modifier.weight(1f))
                        Switch(checked = active, onCheckedChange = { active = it })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, phones, active) }, enabled = name.trim().length >= 2) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

@Composable
private fun OfficeDialogView(office: Carrier.Office?, onDismiss: () -> Unit, onSave: (CarrierOfficeRequest) -> Unit) {
    var name by rememberSaveable { mutableStateOf(office?.name.orEmpty()) }
    var governorate by rememberSaveable { mutableStateOf(office?.governorate.orEmpty()) }
    var city by rememberSaveable { mutableStateOf(office?.city.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(office?.address.orEmpty()) }
    var phones by rememberSaveable { mutableStateOf(office?.phones?.joinToString(", ").orEmpty()) }
    var active by rememberSaveable { mutableStateOf(office?.isActive ?: true) }
    val valid = name.trim().length >= 2 && governorate.trim().length >= 2 && address.trim().length >= 3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (office == null) "مكتب جديد" else "تعديل المكتب") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("اسم المكتب / الفرع") }, singleLine = true)
                OutlinedTextField(value = governorate, onValueChange = { governorate = it.take(40) }, label = { Text("المحافظة") }, singleLine = true)
                OutlinedTextField(value = city, onValueChange = { city = it.take(60) }, label = { Text("المدينة (اختياري)") }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it.take(200) }, label = { Text("العنوان") })
                OutlinedTextField(value = phones, onValueChange = { phones = it.take(200) }, label = { Text("أرقام المكتب (افصل بفاصلة)") })
                if (office != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ظاهر للعملاء", Modifier.weight(1f))
                        Switch(checked = active, onCheckedChange = { active = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CarrierOfficeRequest(
                            name = name.trim(),
                            governorate = governorate.trim(),
                            city = city.trim().takeIf { it.isNotEmpty() },
                            address = address.trim(),
                            phones = phonesList(phones),
                            isActive = if (office != null) active else null,
                        ),
                    )
                },
                enabled = valid,
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}
