@file:OptIn(ExperimentalMaterial3Api::class)

package com.dejavu.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dejavu.core.model.Money
import com.dejavu.core.ui.theme.Gold
import com.dejavu.core.ui.theme.PriceStyle

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
        },
        actions = { actions() },
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Info,
        title = "تعذّر التحميل",
        message = message,
        action = "إعادة المحاولة",
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** Renders a [Load]: spinner, error with retry, or [content]. */
@Composable
fun <T> LoadContent(state: Load<T>, onRetry: () -> Unit, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(state.message, onRetry, modifier)
        is Load.Ready -> content(state.value)
    }
}

/** Shows a ViewModel's messages in the given snackbar host. */
@Composable
fun CollectMessages(vm: BaseViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
}

@Composable
fun PriceText(price: String, compareAt: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Money.format(price), style = PriceStyle, color = MaterialTheme.colorScheme.primary)
        val off = Money.discountPercent(price, compareAt)
        if (off != null) {
            Text(
                Money.format(compareAt),
                style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("-$off%", style = MaterialTheme.typography.labelMedium, color = Gold)
        }
    }
}

@Composable
fun ProductImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun QuantityStepper(value: Int, onChange: (Int) -> Unit, max: Int = 20, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 1, modifier = Modifier.size(32.dp)) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        OutlinedIconButton(onClick = { onChange(value + 1) }, enabled = enabled && value < max, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "زيادة", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
        Text(label, style = style)
        Text(value, style = style, color = valueColor)
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (action != null) {
            Text(action, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable(onClick = onAction))
        }
    }
}

@Composable
fun RetryButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("تحديث")
    }
}
