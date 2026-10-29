@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.social

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SocialRepository
import com.dejavu.core.data.WalletRepository
import com.dejavu.core.model.Poster
import com.dejavu.core.model.WalletBalance
import com.dejavu.core.ui.BackTopBar
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.Load
import com.dejavu.core.ui.LoadContent
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.toLoad
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import com.dejavu.customer.ui.wallet.GiftBalanceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BirthdaysViewModel(private val social: SocialRepository, private val wallet: WalletRepository) : BaseViewModel() {
    private val _people = MutableStateFlow<Load<List<Poster>>>(Load.Loading)
    val people = _people.asStateFlow()
    private val _balances = MutableStateFlow<List<WalletBalance>>(emptyList())
    val balances = _balances.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _people.value = social.birthdaysToday().toLoad() }
        viewModelScope.launch { wallet.wallet().getOrNull()?.let { _balances.value = it.balances } }
    }

    fun toggleFollow(p: Poster) {
        val name = p.username ?: return
        viewModelScope.launch {
            social.follow(name, !p.isFollowing).orSay()?.let { r ->
                _people.value = (_people.value as? Load.Ready)?.let { Load.Ready(it.value.map { x -> if (x.id == p.id) x.copy(isFollowing = r.isFollowing) else x }) } ?: _people.value
            }
        }
    }
}

/**
 * "أعياد ميلاد اليوم": customers with a username whose birthday is today (the list renews at
 * midnight). Each one can be followed, sent money, or gifted a piece from their wishes.
 */
@Composable
fun BirthdaysScreen(onBack: () -> Unit, openUser: (String, Int) -> Unit, login: () -> Unit) {
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    val signedIn = session is com.dejavu.core.data.SessionState.LoggedIn
    val vm = appViewModel(key = "birthdays") { BirthdaysViewModel(it.social, it.wallet) }
    val people by vm.people.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sendTo by remember { mutableStateOf<String?>(null) }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("🎂 أعياد ميلاد اليوم", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadContent(people, onRetry = vm::load, modifier = Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(Icons.Filled.Face, "لا أعياد ميلاد اليوم", "تتجدد القائمة كل يوم عند منتصف الليل، ويصلك إشعار بأعياد من تتابعهم")
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("اضغط على الاسم لزيارة صفحته، أو أهدِه مباشرة 🎁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.id }) { p ->
                        GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(p.avatarUrl, 52.dp, p.stylist, Modifier.clickable { p.username?.let { openUser(it, 0) } })
                                Column(Modifier.weight(1f)) {
                                    UserLine(p, onClick = { p.username?.let { openUser(it, 0) } })
                                    Text("اليوم عيد ميلاده 🎉", style = MaterialTheme.typography.bodySmall, color = Liquid.GoldDeep)
                                }
                                OutlinedButton(onClick = { if (signedIn) vm.toggleFollow(p) else login() }) { Text(if (p.isFollowing) "تتابعه ✓" else "متابعة") }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { if (signedIn) sendTo = p.username else login() }, modifier = Modifier.weight(1f)) { Text("💸 أهدِ رصيداً") }
                                Button(onClick = { p.username?.let { openUser(it, 1) } }, modifier = Modifier.weight(1f)) { Text("🎁 أهدِ قطعة") }
                            }
                        }
                    }
                }
            }
        }
    }
    sendTo?.let { name ->
        GiftBalanceDialog(balances, snackbar, onSent = { sendTo = null; vm.load() }, onDismiss = { sendTo = null }, initialUsername = name)
    }
}

// ----------------------------------------------------------- my birthday ---

/** A 1080×1920 story picture: the birthday, my @username, and the invitation to gift me. */
private fun birthdayStory(context: Context, username: String?): Bitmap {
    val w = 1080
    val h = 1920
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(AColor.rgb(255, 244, 214), AColor.rgb(236, 190, 90), AColor.rgb(184, 134, 43)), null, Shader.TileMode.CLAMP) })
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(60, 40, 10); textAlign = Paint.Align.CENTER }
    // Confetti.
    val rnd = java.util.Random(7)
    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(70) {
        dot.color = intArrayOf(AColor.WHITE, AColor.rgb(255, 214, 120), AColor.rgb(184, 134, 43), AColor.rgb(255, 255, 255))[rnd.nextInt(4)]
        dot.alpha = 150
        c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 6f + rnd.nextFloat() * 14f, dot)
    }
    ContextCompat.getDrawable(context, com.dejavu.core.ui.R.drawable.dejavu_logo)?.let { logo ->
        logo.setBounds(w / 2 - 300, 170, w / 2 + 300, 470)
        logo.draw(c)
    }
    ink.textSize = 150f
    ink.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    c.drawText("🎂", w / 2f, 760f, ink)
    ink.textSize = 120f
    c.drawText("اليوم عيد ميلادي", w / 2f, 960f, ink)
    username?.let {
        ink.textSize = 84f
        ink.typeface = Typeface.DEFAULT
        c.drawText("@$it", w / 2f, 1090f, ink)
    }
    ink.textSize = 64f
    ink.typeface = Typeface.DEFAULT
    c.drawText("أرسلوا لي هدية من تطبيق ديجافو 🎁", w / 2f, 1330f, ink)
    c.drawText("ابحث عن اسمي في قسم «المجتمع»", w / 2f, 1430f, ink)
    ink.textSize = 54f
    ink.alpha = 190
    c.drawText("DEJAVU", w / 2f, 1760f, ink)
    return bmp
}

private suspend fun saveStory(context: Context, bmp: Bitmap): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    File(dir, "birthday-${System.currentTimeMillis()}.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

/**
 * My birthday: a card to share as an Instagram story, so friends know to gift me in the app,
 * plus a link-style message to send in chats. Opened from the greeting notification.
 */
@Composable
fun BirthdayCardScreen(onBack: () -> Unit, openMyPage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsStateWithLifecycle()
    val username = (session as? com.dejavu.core.data.SessionState.LoggedIn)?.customer?.username
    val bmp = remember(username) { birthdayStory(context, username) }

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("بطاقة عيد ميلادي", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("كل عام وأنت بخير 🎉", style = MaterialTheme.typography.headlineSmall, color = Liquid.GoldDeep)
            Text("شارك هذه البطاقة في ستوري انستجرام ليعرف أصدقاؤك أن اليوم عيد ميلادك، فيهدونك رصيداً أو قطعة من أمنياتك من داخل تطبيق ديجافو.", style = MaterialTheme.typography.bodyMedium)
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(20.dp)))
            Button(
                onClick = {
                    scope.launch {
                        val file = saveStory(context, bmp)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                        // Straight into Instagram's story editor when it is installed, else the share sheet.
                        val story = Intent("com.instagram.share.ADD_TO_STORY").setDataAndType(uri, "image/png").putExtra("source_application", context.packageName)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK).setPackage("com.instagram.android")
                        val opened = runCatching { context.startActivity(story) }.isSuccess
                        if (!opened) {
                            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "مشاركة البطاقة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📸 شارك في ستوري انستجرام") }
            OutlinedButton(
                onClick = {
                    val text = "اليوم عيد ميلادي 🎂\nأرسلوا لي هدية من تطبيق ديجافو: ابحثوا عن @${username.orEmpty()} في قسم المجتمع 🎁"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("إرسال رسالة لأصدقائي") }
            if (username != null) OutlinedButton(onClick = { openMyPage(username) }, modifier = Modifier.fillMaxWidth()) { Text("🎁 قائمة أمنياتي ومنشوراتي") }
            else Text("اختر يوزراً من «ملفي الشخصي» ليتمكن أصدقاؤك من العثور عليك وإهدائك.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
