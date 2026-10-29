@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.social

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.SocialRepository
import com.dejavu.core.model.Money
import com.dejavu.core.model.Poster
import com.dejavu.core.model.SocialProfile
import com.dejavu.core.model.WishlistOf
import com.dejavu.core.ui.BackTopBar
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
import com.dejavu.customer.ui.members.VerifiedBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserViewModel(repo: SocialRepository, private val username: String) : PostsViewModel(repo) {
    private val _profile = MutableStateFlow<Load<SocialProfile>>(Load.Loading)
    val profile = _profile.asStateFlow()
    private val _postsUi = MutableStateFlow(FeedUi())
    val postsUi = _postsUi.asStateFlow()
    private val _wishlist = MutableStateFlow<Load<WishlistOf>>(Load.Loading)
    val wishlist = _wishlist.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _profile.value = repo.profile(username).toLoad() }
        viewModelScope.launch {
            _postsUi.value = FeedUi(loading = true)
            repo.posts(username)
                .onSuccess { _posts.value = it; _postsUi.value = FeedUi(loading = false, hasMore = false) }
                .onFailure { _postsUi.value = FeedUi(loading = false, error = it.message ?: "تعذّر التحميل") }
        }
        viewModelScope.launch { _wishlist.value = repo.wishlistOf(username).toLoad() }
    }

    fun toggleFollow() {
        val p = (_profile.value as? Load.Ready)?.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            repo.follow(username, !p.isFollowing).orSay()?.let { _profile.value = Load.Ready(it) }
            _busy.value = false
        }
    }

    suspend fun people(followers: Boolean): List<Poster> = (if (followers) repo.followers(username) else repo.following(username)).getOrDefault(emptyList())
}

private fun birthdayText(md: String?): String? = md?.takeIf { it.length == 5 }?.let { "🎂 عيد ميلاده: ${it.substring(3)}/${it.substring(0, 2)}" }

/** A customer's page: who they are, follow, their posts, and the wishes friends can gift. */
@Composable
fun UserScreen(username: String, initialTab: Int, onBack: () -> Unit, openUser: (String) -> Unit, openProduct: (String) -> Unit, login: () -> Unit, openCart: () -> Unit, editProfile: () -> Unit, birthdayCard: () -> Unit) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val signedIn = session is SessionState.LoggedIn
    val mine = (session as? SessionState.LoggedIn)?.customer?.username
    val vm = appViewModel(key = "user:$username") { UserViewModel(it.social, username) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val postsUi by vm.postsUi.collectAsStateWithLifecycle()
    val wishlist by vm.wishlist.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val socialOn = appContainer().extras.config.collectAsStateWithLifecycle().value.socialEnabled
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // With the community off only the wishes remain.
    val shownTab = if (socialOn) tab else 1
    var people by remember { mutableStateOf<Pair<String, List<Poster>>?>(null) }
    var giftItem by remember { mutableStateOf<WishlistOf.Item?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    CollectMessages(vm, snackbar)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar(username.let { "@$it" }, onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadContent(profile, onRetry = vm::load, modifier = Modifier.weight(1f, fill = false)) { p ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    GlassCard(Modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Avatar(p.avatarUrl, 72.dp, p.stylist)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                                    VerifiedBadge(p.instagramVerified)
                                }
                                if (p.stylist) StylistBadge()
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    Text("${p.counts.posts} منشور", style = MaterialTheme.typography.bodySmall)
                                    Text("${p.counts.followers} متابِع", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { scope.launch { people = "المتابِعون" to vm.people(true) } })
                                    Text("${p.counts.following} يتابع", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { scope.launch { people = "يتابعهم" to vm.people(false) } })
                                }
                            }
                        }
                        p.bio?.takeIf { it.isNotBlank() }?.let { Caption(it, openUser, Modifier.padding(top = 8.dp)) }
                        birthdayText(p.birthday)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Liquid.GoldDeep, modifier = Modifier.padding(top = 4.dp)) }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.isMe) {
                                OutlinedButton(onClick = editProfile, modifier = Modifier.weight(1f)) { Text("تعديل ملفي") }
                                OutlinedButton(onClick = birthdayCard, modifier = Modifier.weight(1f)) { Text("🎂 بطاقة عيد ميلادي") }
                            } else if (p.isFollowing) {
                                OutlinedButton(onClick = { if (signedIn) vm.toggleFollow() else login() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("تتابعه ✓") }
                            } else {
                                Button(onClick = { if (signedIn) vm.toggleFollow() else login() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("متابعة") }
                            }
                        }
                    }
                    if (socialOn) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("المنشورات") })
                        FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("🎁 أمنياتي") })
                    }
                }
            }
            if (shownTab == 0) {
                PostList(vm, signedIn, mine, openUser = { if (it != username) openUser(it) }, openProduct, login, openCart, snackbar, emptyText = "لا توجد منشورات بعد", ui = postsUi, onRefresh = vm::load, onRetry = vm::load)
            } else {
                WishlistTab(wishlist, ownerIsMe = (profile as? Load.Ready)?.value?.isMe == true, username = username, openProduct = openProduct, onGift = { if (signedIn) giftItem = it else login() }, onRetry = vm::load, share = {
                    val text = "هذه قائمة أمنياتي في ديجافو 🎁\nافتح تطبيق ديجافو وابحث عن @$username لتهديني منها."
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "مشاركة قائمة أمنياتي").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }
    }
    people?.let { (title, list) ->
        AlertDialog(
            onDismissRequest = { people = null },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (list.isEmpty()) Text("لا أحد بعد")
                    list.forEach { u ->
                        Row(Modifier.fillMaxWidth().clickable { people = null; u.username?.let(openUser) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(u.avatarUrl, 36.dp, u.stylist)
                            UserLine(u)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { people = null }) { Text("إغلاق") } },
        )
    }
    giftItem?.let { item ->
        GiftProductDialog(
            to = username,
            name = item.name,
            price = item.price,
            variantId = item.giftVariantId.orEmpty(),
            occasion = null,
            onDismiss = { giftItem = null },
            onDone = { message -> giftItem = null; scope.launch { snackbar.showSnackbar(message) } },
        )
    }
}

/** What they wish for, each with a one-tap gift. */
@Composable
private fun WishlistTab(state: Load<WishlistOf>, ownerIsMe: Boolean, username: String, openProduct: (String) -> Unit, onGift: (WishlistOf.Item) -> Unit, onRetry: () -> Unit, share: () -> Unit) {
    LoadContent(state, onRetry = onRetry) { w ->
        if (w.items.isEmpty()) {
            EmptyState(Icons.Filled.Favorite, if (ownerIsMe) "قائمة أمنياتك فارغة" else "لا توجد أمنيات بعد", if (ownerIsMe) "اضغط ♡ على أي منتج ليظهر هنا، وشارك القائمة مع من يحب أن يهديك" else "لم يضف @$username منتجات لقائمة أمنياته")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ownerIsMe) item { Button(onClick = share, modifier = Modifier.fillMaxWidth()) { Text("شارك قائمة أمنياتي") } }
                items(w.items, key = { it.productId }) { it ->
                    GlassCard(Modifier.fillMaxWidth(), elevation = 6.dp, contentPadding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(model = it.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(Liquid.CardShape).clickable { openProduct(it.slug) })
                            Column(Modifier.weight(1f).clickable { openProduct(it.slug) }) {
                                Text(it.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(Money.format(it.price), style = MaterialTheme.typography.bodyMedium, color = Liquid.GoldDeep)
                            }
                            if (!ownerIsMe) {
                                when {
                                    it.pendingGift -> Text("قيد الإهداء 🎁", style = MaterialTheme.typography.labelMedium, color = Liquid.GoldDeep)
                                    it.giftVariantId != null -> Button(onClick = { onGift(it) }) { Text("🎁 أهدِه") }
                                    else -> Text("غير متوفر", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------- single post ---

class SinglePostViewModel(repo: SocialRepository, private val id: String) : PostsViewModel(repo) {
    private val _ui = MutableStateFlow(FeedUi())
    val ui = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        _ui.value = FeedUi(loading = true)
        viewModelScope.launch {
            repo.post(id)
                .onSuccess { _posts.value = listOf(it); _ui.value = FeedUi(loading = false, hasMore = false) }
                .onFailure { _ui.value = FeedUi(loading = false, error = it.message ?: "تعذّر التحميل") }
        }
    }
}

/** One post, opened from a notification (a like, a comment, a mention). */
@Composable
fun PostScreen(id: String, onBack: () -> Unit, openUser: (String) -> Unit, openProduct: (String) -> Unit, login: () -> Unit, openCart: () -> Unit) {
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    val vm = appViewModel(key = "post:$id") { SinglePostViewModel(it.social, id) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm, snackbar)
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { BackTopBar("منشور", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            PostList(vm, session is SessionState.LoggedIn, (session as? SessionState.LoggedIn)?.customer?.username, openUser, openProduct, login, openCart, snackbar, emptyText = "المنشور غير موجود", ui = ui, onRefresh = vm::load, onRetry = vm::load)
        }
    }
}
