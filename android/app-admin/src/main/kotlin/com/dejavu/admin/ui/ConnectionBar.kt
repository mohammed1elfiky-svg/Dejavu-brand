package com.dejavu.admin.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.pos.OfflineSales

/** Whether the phone has a working internet connection, updated live. */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember { mutableStateOf(cm.isOnline()) }
    DisposableEffect(cm) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = cm.isOnline()
            }

            override fun onLost(network: Network) {
                online = cm.isOnline()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun ConnectivityManager.isOnline(): Boolean =
    runCatching { getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }.getOrDefault(true)

/**
 * A thin strip on every screen: "بدون إنترنت" when offline, and how many till sales are
 * waiting to be sent. Hidden when online with nothing waiting. [aboveSystemBar] when no
 * bottom bar is shown, so it clears the phone's navigation bar.
 */
@Composable
fun ConnectionBar(modifier: Modifier = Modifier, aboveSystemBar: Boolean = false) {
    val context = LocalContext.current
    val online = rememberOnline()
    val queue by remember { OfflineSales.get(context) }.queue.collectAsStateWithLifecycle()
    val waiting = queue.size
    AnimatedVisibility(!online || waiting > 0, modifier, enter = expandVertically(), exit = shrinkVertically()) {
        val color = if (!online) Color(0xFFC62828) else Color(0xFFB26A00)
        Row(
            Modifier
                .then(if (aboveSystemBar) Modifier.navigationBarsPadding() else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(color.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (!online) Icons.Filled.Warning else Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            val text = buildString {
                append(if (!online) "بدون إنترنت" else "متصل")
                if (waiting > 0) append(" • بانتظار الإرسال: $waiting ${if (waiting == 1) "فاتورة" else "فواتير"}")
                else if (!online) append(" — البيع بالكاشير يعمل وتُرسل الفواتير عند عودة الاتصال")
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
