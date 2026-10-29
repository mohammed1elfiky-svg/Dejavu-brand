@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.members

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.core.data.MembersRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Address
import com.dejavu.core.model.Currency
import com.dejavu.core.model.DeliveryMethods
import com.dejavu.core.model.GiftChoiceRequest
import com.dejavu.core.model.GroupGift
import com.dejavu.core.model.Money
import com.dejavu.core.model.MyGift
import com.dejavu.core.model.MyLoyalty
import com.dejavu.core.model.MyProfile
import com.dejavu.core.model.StartGroupGiftRequest
import com.dejavu.core.model.StoreCarrier
import com.dejavu.core.model.UpdateProfileRequest
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.StatusChip
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.Success
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One screen of data from [loader], with actions that show their result and reload. */
open class LoadViewModel<T>(private val loader: suspend () -> Result<T>) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _state.value = loader().toLoad() }
    }

    fun <R> act(done: String?, then: (R) -> Unit = {}, block: suspend () -> Result<R>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            block().orSay()?.let {
                done?.let(::say)
                then(it)
                _state.value = loader().toLoad()
            }
            _busy.value = false
        }
    }
}

// ---------------------------------------------------------------- profile ---

class ProfileViewModel(private val repo: MembersRepository) : LoadViewModel<MyProfile>({ repo.profile() }) {
    fun save(request: UpdateProfileRequest) = act("تم حفظ الملف الشخصي") { repo.updateProfile(request) }

    fun uploadAvatar(bytes: ByteArray) = act("تم تغيير الصورة") {
        repo.uploadPublicImage(bytes).mapCatching { url -> repo.updateProfile(UpdateProfileRequest(avatarUrl = url)).getOrThrow() }
    }
}

/** Instagram mark and star shown next to a verified username; tapping explains it. */
@Composable
fun VerifiedBadge(verified: Boolean) {
    if (!verified) return
    var explain by remember { mutableStateOf(false) }
    TextButton(onClick = { explain = true }) { Text("📷⭐", style = MaterialTheme.typography.labelLarge) }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("يوزر موثّق") },
            text = { Text("هذا اليوزر هو حساب صاحبه الحقيقي في انستجرام، وقد تحقق منه متجر ديجافو بكود أرسله صاحب الحساب من انستجرام.") },
            confirmButton = { TextButton(onClick = { explain = false }) { Text("حسناً") } },
        )
    }
}

/** Name, username (with the Instagram check), photo, bio and birthday. */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val vm = appViewModel(key = "profile") { ProfileViewModel(it.members) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberImagePicker(maxSide = 800) { vm.uploadAvatar(it) }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("ملفي الشخصي", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { p ->
                var name by rememberSaveable(p) { mutableStateOf(p.name) }
                var username by rememberSaveable(p) { mutableStateOf(p.username ?: p.pendingUsername.orEmpty()) }
                var gender by rememberSaveable(p) { mutableStateOf(p.gender?.takeIf { it == "MALE" || it == "FEMALE" }) }
                var findable by rememberSaveable(p) { mutableStateOf(p.findableByPhone) }
                var bio by rememberSaveable(p) { mutableStateOf(p.bio.orEmpty()) }
                var birth by rememberSaveable(p) { mutableStateOf(p.birthDate?.take(10).orEmpty()) }
                val usernameError = username.isNotBlank() && !com.dejavu.core.model.UsernameRule.valid(username)
                val birthError = birth.isNotBlank() && runCatching { java.time.LocalDate.parse(birth.trim()) }.isFailure
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(model = p.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(CircleShape))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.username?.let { "@$it" } ?: p.pendingUsername?.let { "@$it (قيد التوثيق)" } ?: p.name, style = MaterialTheme.typography.titleMedium)
                                    VerifiedBadge(p.instagramVerified)
                                }
                                Text("${p.counts.followers} متابِع · ${p.counts.following} متابَع · ${p.counts.posts} منشور", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = picker.fromGallery, enabled = !busy) { Text("تغيير الصورة") }
                            }
                        }
                    }
                    if (p.igStatus == "PENDING" && p.igCode != null && p.pendingUsername != null) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("وثّق يوزرك في انستجرام", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "أرسل هذا الكود من حسابك @${p.pendingUsername} في انستجرام إلى حساب المتجر${p.storeInstagram?.let { " @${it.removePrefix("@")}" } ?: ""}، فيتأكد فريقنا أن اليوزر لك ويظهر بشارة التوثيق. لا يظهر اليوزر لأحد قبل ذلك، وحسابك يعمل عادي.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(p.igCode!!, style = MaterialTheme.typography.headlineMedium, color = Liquid.GoldDeep)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { copy(context, p.igCode!!) }) { Text("نسخ الكود") }
                                p.storeInstagram?.let { handle ->
                                    Button(onClick = { runCatching { uri.openUri("https://ig.me/m/${handle.removePrefix("@").substringAfterLast('/')}") } }) { Text("فتح انستجرام") }
                                }
                            }
                        }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it.filter { c -> !c.isWhitespace() }.take(30) },
                                label = { Text("يوزر انستجرام") },
                                placeholder = { Text("مثل: sara.ali") },
                                isError = usernameError,
                                supportingText = { Text(if (usernameError) com.dejavu.core.model.UsernameRule.HINT else "تغيير اليوزر يخفي القديم ويحتاج كوداً جديداً للتوثيق") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("الجنس", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = gender == "FEMALE", onClick = { gender = "FEMALE" }, label = { Text("أنثى") })
                                FilterChip(selected = gender == "MALE", onClick = { gender = "MALE" }, label = { Text("ذكر") })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("السماح للآخرين بالعثور علي برقم جوالي", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                androidx.compose.material3.Switch(checked = findable, onCheckedChange = { findable = it })
                            }
                            OutlinedTextField(value = bio, onValueChange = { bio = it.take(160) }, label = { Text("نبذة (اختياري)") }, modifier = Modifier.fillMaxWidth())
                            com.dejavu.customer.ui.components.BirthDateField(value = birth, onChange = { birth = it }, error = null, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Button(
                        onClick = {
                            val newName = username.trim().removePrefix("@")
                            vm.save(
                                UpdateProfileRequest(
                                    name = name.trim().takeIf { it != p.name },
                                    username = if (newName != (p.username ?: p.pendingUsername).orEmpty()) newName else null,
                                    gender = gender?.takeIf { it != p.gender },
                                    findableByPhone = findable.takeIf { it != p.findableByPhone },
                                    bio = bio.trim().takeIf { it != p.bio.orEmpty() },
                                    birthDate = birth.trim().takeIf { it.isNotEmpty() && it != p.birthDate?.take(10) },
                                ),
                            )
                        },
                        enabled = !busy && !usernameError && !birthError && name.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("حفظ") }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("code", text))
}

// ---------------------------------------------------------------- loyalty ---

class LoyaltyViewModel(private val repo: MembersRepository) : LoadViewModel<MyLoyalty>({ repo.loyalty() }) {
    fun redeem(points: Int) = act("أُضيفت قيمة النقاط إلى رصيدك 🎉") { repo.redeem(points) }
}

private fun kindLabel(kind: String) = when (kind) {
    "EARN" -> "من طلب"
    "REVERSE" -> "إلغاء طلب"
    "REDEEM" -> "تحويل إلى رصيد"
    "AMBASSADOR" -> "مكافأة سفير"
    else -> "تعديل من المتجر"
}

/** "نقاطي": points, what they are worth, and turning them into wallet credit. */
@Composable
fun LoyaltyScreen(onBack: () -> Unit) {
    val vm = appViewModel(key = "loyalty") { LoyaltyViewModel(it.members) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("نقاطي", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { l ->
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("${l.points} نقطة", style = MaterialTheme.typography.headlineMedium, color = Liquid.GoldDeep)
                            Text("تساوي ${Money.plain(Money.parse(l.value), Currency.SAR)}", style = MaterialTheme.typography.titleSmall)
                            if (l.rules.enabled) {
                                Text(
                                    "كل 1 ر.س = ${trim(l.rules.pointsPerSar)} نقطة، وكل 100 نقطة = ${trim(l.rules.sarPer100Points)} ر.س. تُضاف النقاط بعد استلام طلبك.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text("برنامج النقاط متوقف حالياً", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val canRedeem = l.rules.enabled && l.points >= l.rules.minRedeemPoints
                            Button(onClick = { vm.redeem(l.points) }, enabled = canRedeem && !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text("تحويل كل النقاط إلى رصيد")
                            }
                            if (!canRedeem && l.rules.enabled) {
                                Text("أقل عدد للتحويل ${l.rules.minRedeemPoints} نقطة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (l.entries.isNotEmpty()) item { Text("الحركات", style = MaterialTheme.typography.titleMedium) }
                    items(l.entries, key = { it.id }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(kindLabel(e.kind), style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(e.note, formatDate(e.createdAt)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((if (e.points > 0) "+" else "") + e.points, color = if (e.points > 0) Success else MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

// ------------------------------------------------------------------ gifts ---

class GiftsViewModel(private val repo: MembersRepository) : LoadViewModel<List<MyGift>>({ repo.gifts() }) {
    private val _carriers = MutableStateFlow<List<StoreCarrier>>(emptyList())
    val carriers = _carriers.asStateFlow()
    private val _addresses = MutableStateFlow<List<Address>>(emptyList())
    val addresses = _addresses.asStateFlow()

    fun loadChoices(addresses: com.dejavu.core.data.AddressRepository) {
        viewModelScope.launch {
            repo.carriers().getOrNull()?.let { _carriers.value = it }
            addresses.list().getOrNull()?.let { _addresses.value = it }
        }
    }

    fun choose(id: String, request: GiftChoiceRequest) = act("تم، سنجهّز هديتك 🎁") { repo.chooseGift(id, request) }
}

private fun giftStatus(s: String) = when (s) {
    "WAITING" -> "اختر طريقة الاستلام"
    "PICKUP" -> "ستستلمها من المحل"
    "DELIVERY" -> "في الطريق إليك"
    "DONE" -> "استلمتها"
    else -> s
}

/** Presents for me: who sent them, and how I want to receive them. */
@Composable
fun GiftsScreen(onBack: () -> Unit, addAddress: () -> Unit) {
    val container = appContainer()
    val vm = appViewModel(key = "my-gifts") { GiftsViewModel(it.members) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val carriers by vm.carriers.collectAsStateWithLifecycle()
    val addresses by vm.addresses.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var choosing by remember { mutableStateOf<MyGift?>(null) }
    CollectMessages(vm, snackbar)
    LaunchedEffect(vm) { vm.loadChoices(container.addresses) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("هداياي", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { gifts ->
                if (gifts.isEmpty()) {
                    EmptyState(Icons.Filled.Favorite, "لا توجد هدايا بعد", "عندما يهديك أحد من ديجافو تظهر هديتك هنا")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(gifts, key = { it.id }) { g ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🎁 من ${g.giverName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    StatusChip(giftStatus(g.status), if (g.status == "DONE") Success else Liquid.GoldDeep)
                                }
                                g.occasion?.let { Text("بمناسبة $it", style = MaterialTheme.typography.bodyMedium) }
                                g.message?.let { Text("«$it»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                g.items.forEach { i ->
                                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AsyncImage(model = i.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(Liquid.CardShape))
                                        Text("${i.name} ${listOfNotNull(i.size, i.color).joinToString(" · ")} × ${i.quantity}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                g.trackingNumber?.let { Text("رقم التتبع: $it", style = MaterialTheme.typography.bodySmall) }
                                if (g.status == "WAITING" || g.status == "PICKUP" || g.status == "DELIVERY") {
                                    OutlinedButton(onClick = { choosing = g }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Text(if (g.status == "WAITING") "اختر طريقة الاستلام" else "تغيير طريقة الاستلام")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    choosing?.let { g ->
        GiftChoiceDialog(addresses, carriers, onDismiss = { choosing = null }, addAddress = { choosing = null; addAddress() }) { request ->
            choosing = null
            vm.choose(g.id, request)
        }
    }
}

@Composable
private fun GiftChoiceDialog(
    addresses: List<Address>,
    carriers: List<StoreCarrier>,
    onDismiss: () -> Unit,
    addAddress: () -> Unit,
    onChoose: (GiftChoiceRequest) -> Unit,
) {
    var method by rememberSaveable { mutableStateOf(DeliveryMethods.PICKUP) }
    var addressId by rememberSaveable { mutableStateOf(addresses.firstOrNull { it.isDefault }?.id ?: addresses.firstOrNull()?.id) }
    var officeId by rememberSaveable { mutableStateOf<String?>(null) }
    val offices = carriers.flatMap { c -> c.offices.map { c to it } }
    val ready = when (method) {
        DeliveryMethods.HOME -> addressId != null
        DeliveryMethods.OFFICE -> officeId != null
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كيف تستلم هديتك؟") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = method == DeliveryMethods.PICKUP, onClick = { method = DeliveryMethods.PICKUP }, label = { Text("من المحل") })
                    FilterChip(selected = method == DeliveryMethods.HOME, onClick = { method = DeliveryMethods.HOME }, label = { Text("توصيل") })
                    if (offices.isNotEmpty()) FilterChip(selected = method == DeliveryMethods.OFFICE, onClick = { method = DeliveryMethods.OFFICE }, label = { Text("مكتب شحن") })
                }
                when (method) {
                    DeliveryMethods.PICKUP -> Text("مجاناً، تعال للمحل واستلمها متى ما جهزت.", style = MaterialTheme.typography.bodySmall)
                    DeliveryMethods.HOME -> {
                        Text("أجرة التوصيل تدفعها لشركة الشحن عند الاستلام.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (addresses.isEmpty()) TextButton(onClick = addAddress) { Text("+ إضافة عنوان") }
                        addresses.forEach { a ->
                            FilterChip(selected = addressId == a.id, onClick = { addressId = a.id }, label = { Text(a.label ?: a.summary, maxLines = 1) })
                        }
                    }
                    else -> {
                        Text("أجرة الشحن تدفعها للمكتب عند الاستلام.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        offices.forEach { (c, o) ->
                            FilterChip(selected = officeId == o.id, onClick = { officeId = o.id }, label = { Text("${c.name} · ${o.name} (${o.governorate})", maxLines = 1) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onChoose(
                        GiftChoiceRequest(
                            deliveryMethod = method,
                            addressId = if (method == DeliveryMethods.HOME) addressId else null,
                            carrierOfficeId = if (method == DeliveryMethods.OFFICE) officeId else null,
                        ),
                    )
                },
                enabled = ready,
            ) { Text("تأكيد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع") } },
    )
}

// ------------------------------------------------------------ group gifts ---

class GroupGiftsViewModel(private val repo: MembersRepository) : LoadViewModel<List<GroupGift>>({ repo.myGroupGifts() }) {
    private val _found = MutableStateFlow<List<GroupGift>?>(null)
    val found = _found.asStateFlow()

    fun search(username: String) {
        viewModelScope.launch { _found.value = repo.groupGiftsFor(username).orSay() }
    }

    fun contribute(id: String, amount: String, message: String) = act<com.dejavu.core.model.Contributed>(null, then = {
        say(if (it.funded) "اكتمل المبلغ واشترينا الهدية 🎉" else "شكراً! أُضيفت مساهمتك")
        _found.value = _found.value?.map { g -> if (g.id == it.groupGift.id) it.groupGift else g }
    }) { repo.contribute(id, amount, message) }

    fun cancel(id: String) = act("أُوقفت الهدية وأُعيدت المساهمات") { repo.cancelGroupGift(id) }
}

/** Group gifts: the ones I started or joined, and joining a friend's collection. */
@Composable
fun GroupGiftsScreen(onBack: () -> Unit) {
    val vm = appViewModel(key = "group-gifts") { GroupGiftsViewModel(it.members) }
    val state by vm.state.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var paying by remember { mutableStateOf<GroupGift?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("الهدايا الجماعية", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(state, onRetry = vm::load) { mine ->
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("ساهم في هدية لصديقة", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = query, onValueChange = { query = it.take(30) }, label = { Text("يوزر صاحبة الهدية") }, singleLine = true, modifier = Modifier.weight(1f))
                                Button(onClick = { vm.search(query) }, enabled = query.trim().length >= 3) { Text("بحث") }
                            }
                            Text("لبدء هدية جماعية افتح المنتج واضغط «هدية جماعية».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    found?.let { list ->
                        if (list.isEmpty()) item { Text("لا توجد هدايا جماعية مفتوحة لهذا اليوزر", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(list, key = { "f:" + it.id }) { g -> GroupGiftCard(g, busy, onPay = { paying = g }, onCancel = null) }
                    }
                    item { Text("هداياي الجماعية", style = MaterialTheme.typography.titleMedium) }
                    if (mine.isEmpty()) item { EmptyState(Icons.Filled.Star, "لا توجد بعد", "ابدأ هدية جماعية من صفحة أي منتج وشارك صديقاتك") }
                    items(mine, key = { it.id }) { g -> GroupGiftCard(g, busy, onPay = { paying = g }, onCancel = if (g.isOrganizer && g.status == "OPEN") ({ vm.cancel(g.id) }) else null) }
                }
            }
        }
    }
    paying?.let { g ->
        var amount by rememberSaveable { mutableStateOf("") }
        var message by rememberSaveable { mutableStateOf("") }
        val value = amount.toBigDecimalOrNull()
        val remaining = Money.parse(g.remaining)
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("مساهمتك في «${g.title}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("المتبقي ${Money.plain(remaining, Currency.SAR)} · تُخصم من رصيد محفظتك", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("المبلغ (ر.س)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    TextButton(onClick = { amount = remaining.stripTrailingZeros().toPlainString() }) { Text("ادفع المتبقي كله") }
                    OutlinedTextField(value = message, onValueChange = { message = it.take(200) }, label = { Text("رسالة (اختياري)") })
                }
            },
            confirmButton = {
                TextButton(onClick = { paying = null; vm.contribute(g.id, amount, message) }, enabled = value != null && value.signum() > 0 && value <= remaining) { Text("مساهمة") }
            },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("تراجع") } },
        )
    }
}

@Composable
private fun GroupGiftCard(g: GroupGift, busy: Boolean, onPay: () -> Unit, onCancel: (() -> Unit)?) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(model = g.product?.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(Liquid.CardShape))
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleSmall)
                Text("إلى @${g.recipient?.username ?: g.recipient?.name.orEmpty()}${g.occasion?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                g.product?.let { Text("${it.name} ${listOfNotNull(it.size, it.color).joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("جُمع ${Money.plain(Money.parse(g.raised), Currency.SAR)} من ${Money.plain(Money.parse(g.target), Currency.SAR)} · حتى ${formatDate(g.endsAt)}", style = MaterialTheme.typography.bodySmall, color = Liquid.GoldDeep)
            }
        }
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (Money.parse(g.raised).toFloat() / Money.parse(g.target).toFloat().coerceAtLeast(0.01f)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        if (g.contributions.isNotEmpty()) {
            Text(g.contributions.joinToString("، ") { it.by?.name ?: "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (g.status) {
            "OPEN" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPay, enabled = !busy) { Text("ساهم") }
                if (onCancel != null) TextButton(onClick = onCancel, enabled = !busy) { Text("إيقاف وإرجاع المبالغ") }
            }
            "FUNDED" -> Text("اكتملت واشترينا الهدية 🎉", color = Success)
            else -> Text("أُوقفت وأُعيدت المساهمات", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** From a product: start a collection among friends for [variantId] for someone. */
@Composable
fun StartGroupGiftDialog(variantId: String, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val repo = appContainer().members
    val scope = rememberCoroutineScope()
    var to by rememberSaveable { mutableStateOf("") }
    var occasion by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(7) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("هدية جماعية 🎁") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اجمعي ثمن هذه القطعة مع صديقاتك من محافظكم، وعند اكتمال المبلغ تصل الهدية لصاحبتها مع إشعار. لن تراها قبل ذلك.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = to, onValueChange = { to = it.take(30) }, label = { Text("يوزر صاحبة الهدية") }, singleLine = true)
                OutlinedTextField(value = occasion, onValueChange = { occasion = it.take(80) }, label = { Text("المناسبة (اختياري)") }, singleLine = true)
                OutlinedTextField(value = message, onValueChange = { message = it.take(300) }, label = { Text("رسالة لها (اختياري)") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 7, 14).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d أيام") }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        repo.startGroupGift(StartGroupGiftRequest(to.trim().removePrefix("@"), variantId, occasion = occasion.trim().ifBlank { null }, message = message.trim().ifBlank { null }, days = days))
                            .onSuccess { onStarted("بدأت الهدية الجماعية؛ ساهمي وشاركي صديقاتك من «الهدايا الجماعية»") }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
                enabled = !busy && to.trim().length >= 3,
            ) { Text(if (busy) "…" else "ابدأ") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("تراجع") } },
    )
}

/**
 * Accounts made before gender was asked: once, a small question so the store knows who it is
 * serving. It closes for good after an answer; "later" only hides it until the app restarts.
 */
@Composable
fun GenderPrompt() {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val customer = (session as? com.dejavu.core.data.SessionState.LoggedIn)?.customer ?: return
    var later by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (later || saved || customer.gender == "MALE" || customer.gender == "FEMALE") return
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { later = true },
        title = { Text("أكمل بياناتك") },
        text = { Text("اختر الجنس ليناسبك المتجر. تظهر هذه الرسالة مرة واحدة.") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FEMALE" to "أنثى", "MALE" to "ذكر").forEach { (code, label) ->
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                container.members.updateProfile(UpdateProfileRequest(gender = code)).onSuccess { saved = true }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text(label) }
                }
            }
        },
        dismissButton = { TextButton(onClick = { later = true }) { Text("لاحقاً") } },
    )
}
