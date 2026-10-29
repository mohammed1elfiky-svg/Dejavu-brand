@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.customer.ui.social

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dejavu.core.data.SessionState
import com.dejavu.core.data.SocialRepository
import com.dejavu.core.model.Poster
import com.dejavu.core.model.Post
import com.dejavu.core.model.PostTag
import com.dejavu.core.model.ShopResult
import com.dejavu.core.ui.BaseViewModel
import com.dejavu.core.ui.CollectMessages
import com.dejavu.core.ui.EmptyState
import com.dejavu.core.ui.OfflineState
import com.dejavu.core.ui.ShimmerAvatar
import com.dejavu.core.ui.ShimmerLine
import com.dejavu.core.ui.isOfflineMessage
import com.dejavu.core.ui.theme.GlassTopBar
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Likes, comments, shop-the-look and delete: the same on the feed, a profile and a single post. */
abstract class PostsViewModel(protected val repo: SocialRepository) : BaseViewModel() {
    protected val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts = _posts.asStateFlow()

    protected fun patch(id: String, change: (Post) -> Post) {
        _posts.value = _posts.value.map { if (it.id == id) change(it) else it }
    }

    fun toggleLike(p: Post) {
        val on = !p.liked
        patch(p.id) { it.copy(liked = on, likeCount = (it.likeCount + if (on) 1 else -1).coerceAtLeast(0)) }
        viewModelScope.launch {
            repo.like(p.id, on)
                .onSuccess { c -> patch(p.id) { it.copy(liked = c.liked, likeCount = c.likeCount, commentCount = c.commentCount) } }
                .onFailure {
                    patch(p.id) { x -> x.copy(liked = p.liked, likeCount = p.likeCount) }
                    say(it.message ?: "تعذّر التنفيذ")
                }
        }
    }

    fun commentAdded(id: String) = patch(id) { it.copy(commentCount = it.commentCount + 1) }

    fun shop(p: Post, done: (ShopResult) -> Unit) {
        viewModelScope.launch { repo.shopTheLook(p.id).orSay()?.let(done) }
    }

    fun delete(p: Post) {
        viewModelScope.launch {
            repo.deletePost(p.id).orSay()?.let {
                _posts.value = _posts.value.filterNot { x -> x.id == p.id }
                say("حُذف المنشور")
            }
        }
    }
}

data class FeedUi(val loading: Boolean = true, val refreshing: Boolean = false, val error: String? = null, val page: Int = 0, val hasMore: Boolean = true)

class FeedViewModel(repo: SocialRepository, private val tab: String) : PostsViewModel(repo) {
    private val _ui = MutableStateFlow(FeedUi())
    val ui = _ui.asStateFlow()

    init {
        refresh(first = true)
    }

    fun refresh(first: Boolean = false) {
        _ui.value = FeedUi(loading = first, refreshing = !first)
        viewModelScope.launch { fetch(1) }
    }

    fun loadMore() {
        val u = _ui.value
        if (u.loading || u.refreshing || !u.hasMore || u.error != null) return
        _ui.value = u.copy(loading = true)
        viewModelScope.launch { fetch(u.page + 1) }
    }

    private suspend fun fetch(page: Int) {
        repo.feed(tab, page)
            .onSuccess { r ->
                _posts.value = if (page == 1) r.items else _posts.value + r.items.filter { n -> _posts.value.none { it.id == n.id } }
                _ui.value = FeedUi(loading = false, page = r.page, hasMore = r.hasMore)
            }
            .onFailure { e -> _ui.value = _ui.value.copy(loading = false, refreshing = false, error = e.message ?: "تعذّر التحميل") }
    }

    fun retry() {
        _ui.value = FeedUi(loading = true)
        viewModelScope.launch { fetch(1) }
    }
}

/** "المجتمع": what customers wear from the store. Two tabs: people I follow, and what is trending. */
@Composable
fun FeedScreen(
    openUser: (String) -> Unit,
    openProduct: (String) -> Unit,
    newPost: () -> Unit,
    birthdays: () -> Unit,
    login: () -> Unit,
    openCart: () -> Unit,
) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val signedIn = session is SessionState.LoggedIn
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var searching by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val following = appViewModel(key = "feed:FOLLOWING") { FeedViewModel(it.social, "FOLLOWING") }
    val explore = appViewModel(key = "feed:EXPLORE") { FeedViewModel(it.social, "EXPLORE") }
    CollectMessages(following, snackbar)
    CollectMessages(explore, snackbar)
    val vm = if (tab == 0) following else explore
    // Back from a post/profile: likes and comments may have changed.
    LifecycleResumeEffect(signedIn) {
        if (signedIn && following.ui.value.error != null) following.retry()
        onPauseOrDispose {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            GlassTopBar(
                title = { Text("المجتمع") },
                actions = {
                    TextButton(onClick = birthdays) { Text("🎂") }
                    TextButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "بحث عن يوزر") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (signedIn) newPost() else login() }, containerColor = Liquid.Gold) { Icon(Icons.Filled.Add, contentDescription = "منشور جديد") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("المتابَعون") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("اكتشف 🔥") })
            }
            if (tab == 0 && !signedIn) {
                EmptyState(Icons.Filled.Face, "سجّل الدخول لترى منشورات من تتابعهم", action = "تسجيل الدخول", onAction = login)
            } else {
                PostList(vm, signedIn, (session as? SessionState.LoggedIn)?.customer?.username, openUser, openProduct, login, openCart, snackbar, emptyText = if (tab == 0) "لا منشورات بعد. تابع أشخاصاً من تبويب «اكتشف» أو من البحث 🔍" else "لا توجد منشورات بعد، كن أول من ينشر!")
            }
        }
    }
    if (searching) UserSearchDialog(onDismiss = { searching = false }, openUser = { searching = false; openUser(it) })
}

/** The list shared by both tabs, with shimmer while loading and an offline state. */
@Composable
fun PostList(
    vm: PostsViewModel,
    signedIn: Boolean,
    mineUsername: String?,
    openUser: (String) -> Unit,
    openProduct: (String) -> Unit,
    login: () -> Unit,
    openCart: () -> Unit,
    snackbar: SnackbarHostState,
    emptyText: String,
    ui: FeedUi? = null,
    onRefresh: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val container = appContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val posts by vm.posts.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    var commentsFor by remember { mutableStateOf<String?>(null) }
    var giftTag by remember { mutableStateOf<Pair<Post, PostTag>?>(null) }
    val feed = ui ?: (vm as? FeedViewModel)?.ui?.collectAsStateWithLifecycle()?.value ?: FeedUi(loading = false)
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(nearEnd, posts.size) { if (nearEnd) (onMore ?: (vm as? FeedViewModel)?.let { f -> f::loadMore })?.invoke() }
    val refresh = onRefresh ?: (vm as? FeedViewModel)?.let { f -> { f.refresh() } }

    when {
        feed.loading && posts.isEmpty() -> PostsShimmer()
        feed.error != null && posts.isEmpty() ->
            if (isOfflineMessage(feed.error)) OfflineState(onRetry = { (onRetry ?: (vm as? FeedViewModel)?.let { f -> f::retry })?.invoke() })
            else com.dejavu.core.ui.ErrorBox(feed.error, onRetry = { (onRetry ?: (vm as? FeedViewModel)?.let { f -> f::retry })?.invoke() })
        else -> PullToRefreshBox(isRefreshing = feed.refreshing, onRefresh = { refresh?.invoke() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = state, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                header?.let { h -> item(key = "header") { h() } }
                if (posts.isEmpty()) {
                    item { Text(emptyText, Modifier.fillMaxWidth().padding(32.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(posts, key = { it.id }) { p ->
                    PostCard(
                        post = p,
                        isMine = mineUsername != null && p.author.username == mineUsername,
                        onLike = { if (signedIn) vm.toggleLike(p) else login() },
                        onComments = { commentsFor = p.id },
                        onShop = {
                            if (!signedIn) login() else vm.shop(p) { r ->
                                scope.launch {
                                    container.cart.refresh()
                                    val skipped = if (r.skipped.isNotEmpty()) " (${r.skipped.size} غير متوفر)" else ""
                                    val result = snackbar.showSnackbar(if (r.added > 0) "أُضيف ${r.added} منتج إلى السلة$skipped" else "لا توجد قطع متوفرة من هذه الإطلالة", actionLabel = if (r.added > 0) "السلة" else null)
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) openCart()
                                }
                            }
                        },
                        onDelete = { vm.delete(p) },
                        openUser = openUser,
                        openProduct = openProduct,
                        onGift = { t -> if (signedIn) giftTag = p to t else login() },
                    )
                }
                if (feed.loading && posts.isNotEmpty()) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            }
        }
    }
    commentsFor?.let { id ->
        CommentsSheet(id, mineUsername, canWrite = signedIn && mineUsername != null, openUser = { commentsFor = null; openUser(it) }, onAdded = { vm.commentAdded(id) }, onDismiss = { commentsFor = null })
    }
    giftTag?.let { (post, t) ->
        GiftProductDialog(
            to = post.author.username.orEmpty(),
            name = t.name,
            price = t.price,
            variantId = t.giftVariantId.orEmpty(),
            occasion = if (post.kind == "WISHLIST") "عيد ميلاده" else null,
            onDismiss = { giftTag = null },
            onDone = { message -> giftTag = null; scope.launch { snackbar.showSnackbar(message) } },
        )
    }
}

@Composable
private fun PostsShimmer() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShimmerAvatar(44.dp)
                    ShimmerLine(width = 140.dp, height = 14.dp)
                }
                Box(Modifier.fillMaxWidth().padding(top = 2.dp)) { com.dejavu.core.ui.ShimmerCard() }
            }
        }
    }
}

/** Finds a friend by the start of a username, or by a full mobile number. */
@Composable
fun FindContent(openUser: (String) -> Unit, modifier: Modifier = Modifier) {
    val repo = appContainer().members
    var q by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<com.dejavu.core.model.FoundPerson>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun run(text: String) {
        job?.cancel()
        job = scope.launch {
            delay(350)
            message = null
            if (text.trim().removePrefix("@").length < 2) { found = emptyList(); return@launch }
            repo.find(text).onSuccess { found = it }.onFailure { found = emptyList(); message = it.message }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it.filter { c -> !c.isWhitespace() }.take(32); run(q) },
            label = { Text("يوزر انستجرام أو رقم الجوال كاملاً") },
            placeholder = { Text("@sara.ali  أو  777123456") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        found.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (p.username != null) Modifier.clickable { openUser(p.username!!) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(p.avatarUrl, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.username?.let { "@$it" } ?: p.name, style = MaterialTheme.typography.titleSmall)
                    if (p.username == null) Text("${p.name} · بلا يوزر موثّق، يمكنك إهداؤه رصيداً من المحفظة برقمه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (p.viaPhone) Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.instagramVerified) com.dejavu.customer.ui.members.VerifiedBadge(true)
            }
        }
        if (found.isEmpty() && q.trim().removePrefix("@").length >= 2 && message == null) {
            Text("لا نتائج. تأكد من اليوزر، أو أن الرقم كامل، وأن صاحبه يسمح بالبحث برقمه.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun UserSearchDialog(onDismiss: () -> Unit, openUser: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ابحث عن صديقة") },
        text = { FindContent(openUser) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

/** "ابحث عن صديقة": for gifts and wishlists, with or without the community. */
@Composable
fun FindFriendsScreen(onBack: () -> Unit, openUser: (String) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { com.dejavu.core.ui.BackTopBar("ابحث عن صديقة", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) { FindContent(openUser) }
    }
}
