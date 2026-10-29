@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.dejavu.customer.ui.social

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dejavu.core.data.userMessage
import com.dejavu.core.model.Currency
import com.dejavu.core.model.Money
import com.dejavu.core.model.Poster
import com.dejavu.core.model.Post
import com.dejavu.core.model.PostTag
import com.dejavu.core.model.StartGroupGiftRequest
import com.dejavu.core.ui.formatDate
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.GlassCard
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import com.dejavu.core.ui.theme.OutlinedTextField
import com.dejavu.customer.ui.appContainer
import com.dejavu.customer.ui.members.VerifiedBadge
import kotlinx.coroutines.launch

private val MENTION = Regex("@([A-Za-z][A-Za-z0-9._]{1,29})")

/** The gold gradient ring around the avatar of a "مُنسِّق متميز". */
private val StylistRing = Brush.sweepGradient(listOf(Color(0xFFFFE08A), Color(0xFFB8862B), Color(0xFFFFE08A), Color(0xFFE6B84A), Color(0xFFFFE08A)))

@Composable
fun Avatar(url: String?, size: Dp, stylist: Boolean = false, modifier: Modifier = Modifier) {
    val ring = if (stylist) 3.dp else 0.dp
    Box(
        modifier.size(size).then(if (stylist) Modifier.border(ring, StylistRing, CircleShape) else Modifier).padding(ring + if (stylist) 2.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Liquid.GoldPale), contentAlignment = Alignment.Center) { Text("👤") }
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
    }
}

/** "@sara" with the Instagram badge when verified and the stylist badge when earned. */
@Composable
fun UserLine(p: Poster, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Text(p.username?.let { "@$it" } ?: p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        VerifiedBadge(p.instagramVerified)
        if (p.stylist) StylistBadge()
    }
}

@Composable
fun StylistBadge() {
    Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color(0xFFE6B84A), Color(0xFFB8862B)))).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text("✦ مُنسِّق متميز", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** Caption text with @mentions in gold that open the mentioned account. */
@Composable
fun Caption(text: String, openUser: (String) -> Unit, modifier: Modifier = Modifier) {
    val gold = Liquid.GoldDeep
    val annotated: AnnotatedString = remember(text) {
        buildAnnotatedString {
            var last = 0
            for (m in MENTION.findAll(text)) {
                append(text.substring(last, m.range.first))
                withLink(LinkAnnotation.Clickable(m.groupValues[1], TextLinkStyles(SpanStyle(color = gold, fontWeight = FontWeight.Bold))) { openUser(m.groupValues[1]) }) { append(m.value) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

/** The photos of a post, swiped sideways, with the product pins on top. */
@Composable
fun PostPhotos(post: Post, openProduct: (String) -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(pageCount = { post.images.size.coerceAtLeast(1) })
    var open by remember { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(18.dp))) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = post.images.getOrNull(page),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { open = null },
                )
                post.tags.filter { it.imageIndex == page && it.x != null && it.y != null }.forEach { t ->
                    val x = (maxWidth * t.x!!.toFloat()) - 15.dp
                    val y = (maxHeight * t.y!!.toFloat()) - 15.dp
                    Box(Modifier.offset(x = x.coerceAtLeast(0.dp), y = y.coerceAtLeast(0.dp))) {
                        TagPin(t, expanded = open == t.id, onToggle = { open = if (open == t.id) null else t.id }, onOpen = { openProduct(t.slug) })
                    }
                }
            }
        }
        if (post.images.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(post.images.size) { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

@Composable
private fun TagPin(t: PostTag, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Column {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFE08A), Color(0xFFB8862B)))).border(2.dp, Color.White, CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { Text("＋", color = Color.White, style = MaterialTheme.typography.labelLarge) }
        if (expanded) {
            Row(
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.95f)).clickable(onClick = onOpen).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                t.imageUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))) }
                Column {
                    Text(t.name, style = MaterialTheme.typography.labelLarge, color = Color.Black, maxLines = 1)
                    Text(Money.format(t.price), style = MaterialTheme.typography.labelMedium, color = Liquid.GoldDeep)
                }
                Text(if (t.available) "›" else "نفد", color = if (t.available) Color.Black else Color.Red)
            }
        }
    }
}

/**
 * One post: author, photos with pins, like / comment / shop-the-look, caption, tagged products
 * (a wishlist post lets friends gift them).
 */
@Composable
fun PostCard(
    post: Post,
    isMine: Boolean,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onShop: () -> Unit,
    onDelete: (() -> Unit)?,
    openUser: (String) -> Unit,
    openProduct: (String) -> Unit,
    onGift: ((PostTag) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth(), elevation = 8.dp, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(post.author.avatarUrl, 44.dp, post.author.stylist, Modifier.clickable { post.author.username?.let(openUser) })
            Column(Modifier.weight(1f)) {
                UserLine(post.author, onClick = { post.author.username?.let(openUser) })
                Text(formatDate(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isMine && onDelete != null) TextButton(onClick = onDelete) { Text("حذف", color = MaterialTheme.colorScheme.error) }
        }
        if (post.kind == "WISHLIST") {
            Text("🎁 أمنيات عيد ميلادي", style = MaterialTheme.typography.titleSmall, color = Liquid.GoldDeep, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PostPhotos(post, openProduct)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLike) {
                Text((if (post.liked) "❤️ " else "🤍 ") + post.likeCount, color = if (post.liked) Color(0xFFD64545) else MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = onComments) { Text("💬 ${post.commentCount}", color = MaterialTheme.colorScheme.onSurface) }
        }
        post.caption?.takeIf { it.isNotBlank() }?.let { Caption(it, openUser) }
        if (post.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (post.kind == "LOOK") {
                Button(onClick = onShop, modifier = Modifier.fillMaxWidth()) { Text("🛍 اشترِ الإطلالة (${post.tags.size})") }
            }
            post.tags.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = t.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable { openProduct(t.slug) },
                    )
                    Column(Modifier.weight(1f).clickable { openProduct(t.slug) }) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(Money.format(t.price), style = MaterialTheme.typography.labelMedium, color = Liquid.GoldDeep)
                    }
                    if (onGift != null && t.giftVariantId != null && !isMine) OutlinedButton(onClick = { onGift(t) }) { Text("🎁 أهدِه") }
                }
            }
        }
    }
}

/** The comments of a post and a box to add one. [onAdded] lets the list update its counter. */
@Composable
fun CommentsSheet(postId: String, mineUsername: String?, canWrite: Boolean, openUser: (String) -> Unit, onAdded: () -> Unit, onDismiss: () -> Unit) {
    val repo = appContainer().social
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<com.dejavu.core.model.Comment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(postId, attempt) {
        error = null
        repo.comments(postId).onSuccess { comments = it }.onFailure { error = it.userMessage() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).fillMaxWidth().height(480.dp)) {
            Text("التعليقات", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = comments
                when {
                    error != null -> com.dejavu.core.ui.OfflineState(onRetry = { attempt++ }, message = error!!)
                    list == null -> com.dejavu.core.ui.ShimmerList(rows = 5)
                    list.isEmpty() -> Text("كن أول من يعلّق 💬", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.id }) { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(c.author?.avatarUrl, 36.dp, c.author?.stylist == true, Modifier.clickable { c.author?.username?.let(openUser) })
                                Column(Modifier.weight(1f)) {
                                    c.author?.let { UserLine(it, onClick = { it.username?.let(openUser) }) }
                                    Caption(c.body, openUser)
                                    Text(formatDate(c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (c.author?.username != null && c.author?.username == mineUsername) {
                                    TextButton(onClick = { scope.launch { repo.deleteComment(c.id).onSuccess { comments = comments?.filterNot { x -> x.id == c.id } } } }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (canWrite) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, placeholder = { Text("اكتب تعليقاً… (@ للإشارة)") }, modifier = Modifier.weight(1f), maxLines = 3)
                    Button(
                        onClick = {
                            sending = true
                            sendError = null
                            scope.launch {
                                repo.comment(postId, text)
                                    .onSuccess { c ->
                                        comments = (comments ?: emptyList()) + com.dejavu.core.model.Comment(c.id, c.body, c.createdAt, Poster(id = "me", username = mineUsername, name = mineUsername.orEmpty()))
                                        text = ""
                                        onAdded()
                                    }
                                    .onFailure { sendError = it.userMessage() }
                                sending = false
                            }
                        },
                        enabled = text.isNotBlank() && !sending,
                    ) { Text("نشر") }
                }
            } else {
                Text("سجّل الدخول واختر يوزراً للتعليق", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

/**
 * "أهدِه": buys [name] for [to] right now. It opens a one-person gift collection that I fund in
 * full from my wallet; when it is complete the receiver is notified and picks delivery or pickup.
 */
@Composable
fun GiftProductDialog(to: String, name: String, price: String, variantId: String, occasion: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val repo = appContainer().members
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("أهدِ «$name» لـ @$to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "تُخصم ${Money.plain(Money.parse(price), Currency.SAR)} من رصيد محفظتك، ويصل @$to إشعار بهديتك منك، فيختار التوصيل أو الاستلام من المحل.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = message, onValueChange = { message = it.take(300) }, label = { Text("رسالة مع الهدية (اختياري)") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val started = repo.startGroupGift(StartGroupGiftRequest(to = to, variantId = variantId, occasion = occasion, message = message.trim().ifBlank { null }, days = 3))
                        started.onSuccess { g ->
                            repo.contribute(g.id, Money.parse(g.target).toPlainString(), null)
                                .onSuccess { onDone("أُرسلت هديتك 🎁") }
                                .onFailure {
                                    // Not enough balance: the collection stays open so friends can chip in.
                                    error = it.userMessage() + "\nبقيت الهدية مفتوحة في «الهدايا الجماعية» لتكمل منها."
                                }
                        }.onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text(if (busy) "…" else "أهدِ الآن", color = Liquid.GoldDeep) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } },
    )
}
