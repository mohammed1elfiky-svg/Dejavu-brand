package com.dejavu.customer.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.StoreExtrasRepository
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.ChatMessage
import com.dejavu.core.model.ChatRole
import com.dejavu.core.model.Money
import com.dejavu.core.model.Numerals
import com.dejavu.core.model.StoreProduct
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.ProductImage
import com.dejavu.core.ui.media.rememberImagePicker
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.core.ui.theme.glass
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One bubble: a message, with the photo's bytes when it was just sent from this phone. */
data class ChatEntry(val message: ChatMessage, val photo: ByteArray? = null, val failed: Boolean = false)

/** What the chat screen shows. */
data class ChatUi(val enabled: Boolean, val welcome: String, val whatsapp: String, val entries: List<ChatEntry>)

class ChatViewModel(private val extras: StoreExtrasRepository) : BaseViewModel() {
    private val _state = MutableStateFlow<Load<ChatUi>>(Load.Loading)
    val state = _state.asStateFlow()

    /** Waiting for the assistant's answer ("يكتب..."). */
    private val _typing = MutableStateFlow(false)
    val typing = _typing.asStateFlow()

    /** A photo picked for the next message (compressed JPEG), not sent yet. */
    private val _photo = MutableStateFlow<ByteArray?>(null)
    val photo = _photo.asStateFlow()

    private var localIds = 0

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            extras.chat()
                .onSuccess { h -> _state.value = Load.Ready(ChatUi(h.enabled, h.welcome, h.whatsapp, h.messages.map { ChatEntry(it) })) }
                .onFailure { if (_state.value is Load.Ready) say(it.userMessage()) else _state.value = Load.Failed(it.userMessage()) }
        }
    }

    fun attach(bytes: ByteArray) {
        _photo.value = bytes
    }

    fun removePhoto() {
        _photo.value = null
    }

    /** Sends [text] (and the picked photo); false when there is nothing to send or an answer is awaited. */
    fun send(text: String): Boolean {
        if (_state.value !is Load.Ready || _typing.value) return false
        val body = text.trim()
        val photo = _photo.value
        if (body.isEmpty() && photo == null) return false
        val local = ChatMessage(
            id = "local-${++localIds}",
            role = ChatRole.USER,
            text = body,
            imageFileId = if (photo != null) "local" else null,
            createdAt = Instant.now().toString(),
        )
        update { it.copy(entries = it.entries + ChatEntry(local, photo)) }
        _photo.value = null
        _typing.value = true
        viewModelScope.launch {
            val imageId = if (photo != null) {
                extras.uploadPhoto(photo, "image/jpeg").getOrElse {
                    failed(local.id, it)
                    return@launch
                }.id
            } else {
                null
            }
            extras.sendChat(body.ifEmpty { null }, imageId)
                .onSuccess { reply -> update { it.copy(entries = it.entries + ChatEntry(reply)) } }
                .onFailure { failed(local.id, it) }
            _typing.value = false
        }
        return true
    }

    /** Reports an assistant answer as wrong or offensive. */
    fun report(messageId: String, reason: String) {
        viewModelScope.launch {
            extras.reportChat(messageId, reason).orSay() ?: return@launch
            update { ui -> ui.copy(entries = ui.entries.map { if (it.message.id == messageId) it.copy(message = it.message.copy(reported = true)) else it }) }
            say("شكراً، وصلنا بلاغك وسنراجع الرد")
        }
    }

    fun clear() {
        viewModelScope.launch {
            extras.clearChat().orSay() ?: return@launch
            update { it.copy(entries = emptyList()) }
            say("تم مسح المحادثة")
        }
    }

    private fun failed(localId: String, error: Throwable) {
        _typing.value = false
        update { ui -> ui.copy(entries = ui.entries.map { if (it.message.id == localId) it.copy(failed = true) else it }) }
        say(error.userMessage())
    }

    private fun update(change: (ChatUi) -> ChatUi) {
        val ui = (_state.value as? Load.Ready)?.value ?: return
        _state.value = Load.Ready(change(ui))
    }
}

/** The shopping assistant: text and photos, product cards, hand-over to WhatsApp. */
@Composable
fun ChatScreen(onBack: () -> Unit, openProduct: (String) -> Unit, login: () -> Unit) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val config by container.extras.config.collectAsStateWithLifecycle()
    val vm = appViewModel { ChatViewModel(it.extras) }
    val state by vm.state.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val photo by vm.photo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<String?>(null) }
    val loggedIn = session is SessionState.LoggedIn
    CollectMessages(vm, snackbar)
    LaunchedEffect(loggedIn) { if (loggedIn) vm.load() }

    val ui = (state as? Load.Ready)?.value
    val whatsapp = ui?.whatsapp.orEmpty()
    val openWhatsApp: (String) -> Unit = { number ->
        val digits = number.filter { it.isDigit() }
        if (digits.isNotEmpty()) runCatching { uriHandler.openUri("https://wa.me/$digits") }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar("مساعد ديجافو", onBack) {
                if (loggedIn && ui != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "المزيد") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (whatsapp.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("تواصل مع خدمة العملاء") },
                                    onClick = {
                                        menu = false
                                        openWhatsApp(whatsapp)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("مسح المحادثة") },
                                enabled = ui.entries.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        if (!loggedIn) {
            if (!config.chat.enabled) {
                EmptyState(Icons.Filled.Face, "المساعد غير متاح حالياً", modifier = modifier)
            } else {
                EmptyState(
                    Icons.Filled.Face,
                    "سجّل الدخول للتحدث مع مساعد التسوق",
                    "يساعدك المساعد في إيجاد المنتجات والمقاسات ومتابعة طلباتك",
                    action = "تسجيل الدخول",
                    onAction = login,
                    modifier = modifier,
                )
            }
            return@Scaffold
        }
        LoadContent(state, onRetry = vm::load, modifier = modifier) { chat ->
            if (!chat.enabled) {
                EmptyState(Icons.Filled.Face, "المساعد غير متاح حالياً", "يمكنك التواصل معنا عبر واتساب", modifier = modifier)
                return@LoadContent
            }
            Column(modifier.fillMaxSize()) {
                Conversation(
                    chat = chat,
                    welcome = chat.welcome.ifBlank { config.chat.welcome },
                    typing = typing,
                    openProduct = openProduct,
                    openWhatsApp = openWhatsApp,
                    onReport = { reporting = it },
                    modifier = Modifier.weight(1f),
                )
                InputBar(
                    photo = photo,
                    sending = typing,
                    onAttach = vm::attach,
                    onRemovePhoto = vm::removePhoto,
                    onSend = vm::send,
                )
            }
        }
    }

    reporting?.let { id ->
        ReportDialog(
            onDismiss = { reporting = null },
            onSend = { reason ->
                reporting = null
                vm.report(id, reason)
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح المحادثة") },
            text = { Text("هل تريد حذف كل رسائل المحادثة؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear()
                }) { Text("مسح", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun Conversation(
    chat: ChatUi,
    welcome: String,
    typing: Boolean,
    openProduct: (String) -> Unit,
    openWhatsApp: (String) -> Unit,
    onReport: (String) -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val count = 1 + chat.entries.size + (if (typing) 1 else 0)
    LaunchedEffect(count) { list.animateScrollToItem(count - 1) }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "welcome") {
            BotBubble(welcome.ifBlank { "أهلاً بك! أنا مساعد ديجافو 🌷 اسألني عن أي منتج أو مقاس أو عن طلبك، ويمكنك إرسال صورة لمنتج تبحث عنه." }, time = null)
        }
        items(chat.entries, key = { it.message.id }) { entry ->
            val m = entry.message
            if (m.role == ChatRole.USER) {
                UserBubble(entry)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotBubble(m.text, time = m.createdAt, reported = m.reported, onReport = { onReport(m.id) })
                    if (m.products.isNotEmpty()) ProductsRow(m.products, openProduct)
                    val number = m.whatsapp?.takeIf { it.isNotBlank() } ?: chat.whatsapp
                    if (m.handover && number.isNotBlank()) WhatsAppButton { openWhatsApp(number) }
                }
            }
        }
        if (typing) item(key = "typing") { TypingBubble() }
    }
}

private val userShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
private val botShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)

/** The shopper's message: gold, on the right. */
@Composable
private fun UserBubble(entry: ChatEntry) {
    val m = entry.message
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Right) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(userShape).background(Liquid.GoldBrushH).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val bytes = entry.photo
            if (bytes != null) {
                LocalPhoto(bytes)
            } else if (!m.imageFileId.isNullOrBlank()) {
                Text("📷 صورة", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            if (m.text.isNotBlank()) Text(m.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (entry.failed) "لم تُرسل" else timeOf(m.createdAt),
                color = if (entry.failed) Color(0xFFFFE0E0) else Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The assistant's answer: clear glass, on the left. */
@Composable
private fun BotBubble(text: String, time: String?, reported: Boolean = false, onReport: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        Column(
            Modifier.widthIn(max = 300.dp).glass(botShape, elevation = 6.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text.replace("**", "").trim(), color = Liquid.Ink, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (time != null) Text(timeOf(time), color = Liquid.InkSoft, style = MaterialTheme.typography.labelSmall)
                when {
                    reported -> Text("تم الإبلاغ", color = Liquid.InkSoft, style = MaterialTheme.typography.labelSmall)
                    onReport != null -> Text(
                        "⚑ إبلاغ عن الرد",
                        color = Liquid.InkSoft,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReport).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private val reportReasons = listOf("معلومة خاطئة", "رد مسيء أو غير لائق", "لا علاقة له بسؤالي")

/** Why the answer is reported: a reason, plus an optional note. */
@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf(reportReasons.first()) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإبلاغ عن رد المساعد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("سيراجع فريق المتجر هذا الرد.", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft)
                reportReasons.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { reason = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text("تفاصيل (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(listOf(reason, note.trim()).filter { it.isNotBlank() }.joinToString(" — ")) }) { Text("إرسال البلاغ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        Row(
            Modifier.glass(botShape, elevation = 6.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Liquid.Gold)
            Text("يكتب...", color = Liquid.InkSoft, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Products the assistant suggested, as small cards in a row. */
@Composable
private fun ProductsRow(products: List<StoreProduct>, openProduct: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        items(products, key = { it.id }) { p ->
            GlassCard(Modifier.width(140.dp), elevation = 8.dp, contentPadding = 0.dp, onClick = { openProduct(p.slug) }) {
                ProductImage(p.images.firstOrNull()?.url, Modifier.fillMaxWidth().aspectRatio(1f), contentDescription = p.nameAr)
                Column(Modifier.padding(8.dp)) {
                    Text(p.nameAr, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Liquid.Ink)
                    val price = p.variants.minByOrNull { Money.parse(it.price) }?.price ?: p.basePrice
                    Text(Money.format(price), style = MaterialTheme.typography.labelLarge, color = Liquid.GoldDeep, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WhatsAppButton(onClick: () -> Unit) {
    val green = Color(0xFF25D366)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.Left) {
        com.dejavu.core.ui.theme.Button(
            onClick = onClick,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.White),
        ) {
            Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("تواصل مع خدمة العملاء (واتساب)")
        }
    }
}

@Composable
private fun InputBar(
    photo: ByteArray?,
    sending: Boolean,
    onAttach: (ByteArray) -> Unit,
    onRemovePhoto: () -> Unit,
    onSend: (String) -> Boolean,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    val picker = rememberImagePicker { bytes -> onAttach(bytes) }
    val canSend = !sending && (text.isNotBlank() || photo != null)
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(26.dp), elevation = 14.dp)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photo != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LocalPhoto(photo, Modifier.size(56.dp))
                Text("صورة مرفقة", style = MaterialTheme.typography.bodySmall, color = Liquid.InkSoft, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemovePhoto) { Icon(Icons.Filled.Clear, contentDescription = "إزالة الصورة", tint = Liquid.InkSoft) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = !sending) {
                    Icon(Icons.Filled.Add, contentDescription = "إرسال صورة", tint = Liquid.GoldDeep)
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("التقاط صورة") }, onClick = {
                        attachMenu = false
                        picker.fromCamera()
                    })
                    DropdownMenuItem(text = { Text("من المعرض") }, onClick = {
                        attachMenu = false
                        picker.fromGallery()
                    })
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1000) },
                placeholder = { Text("اكتب رسالتك…") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(if (canSend) Liquid.GoldBrush else SolidColor(Liquid.Mist)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { if (onSend(text)) text = "" }, enabled = canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال", tint = if (canSend) Color.White else Liquid.InkSoft)
                }
            }
        }
    }
}

/** A photo picked on this phone (JPEG bytes), decoded small for the bubble. */
@Composable
private fun LocalPhoto(bytes: ByteArray, modifier: Modifier = Modifier.size(180.dp)) {
    val bitmap: ImageBitmap? = remember(bytes) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "صورة", contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(14.dp)))
    } else {
        Text("📷 صورة", style = MaterialTheme.typography.bodyMedium)
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale("ar"))

/** "2026-10-21T18:05:00.000Z" → "6:05 م" in the phone's time zone. */
private fun timeOf(iso: String): String =
    runCatching { Numerals.of(timeFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))) }.getOrDefault("")
