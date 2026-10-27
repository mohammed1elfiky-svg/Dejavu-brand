package com.dejavu.customer.ui.account

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dejavu.core.ui.theme.Liquid
import com.dejavu.core.ui.theme.OutlinedButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private fun hasPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One fresh position from the phone (GPS or network), or the last known one; null when none. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    if (!hasPermission(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val fresh = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont ->
            val provider = providers.firstOrNull()
            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { loc -> if (cont.isActive) cont.resume(loc) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { loc -> if (cont.isActive) cont.resume(loc) }, android.os.Looper.getMainLooper())
            }
        }
    }
    return fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

/**
 * "حدد موقعي الحالي": asks for location permission, reads the position and hands it to
 * [onLocated]; shows it once set, with a link to the map and a way to remove it.
 */
@Composable
fun MyLocationRow(latitude: Double?, longitude: Double?, onLocated: (Double?, Double?) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val locate: () -> Unit = {
        busy = true
        problem = null
        scope.launch {
            val loc = currentLocation(context)
            busy = false
            if (loc == null) problem = "تعذّر تحديد الموقع. شغّل الموقع (GPS) وحاول مرة أخرى." else onLocated(loc.latitude, loc.longitude)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else problem = "اسمح للتطبيق باستخدام الموقع لتحديد عنوانك."
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                if (hasPermission(context)) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null)
            Text(
                when {
                    busy -> "جارٍ تحديد الموقع…"
                    latitude != null -> "تحديث موقعي على الخريطة"
                    else -> "حدد موقعي الحالي على الخريطة"
                },
            )
        }
        if (latitude != null && longitude != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ تم تحديد الموقع", color = Liquid.Success, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { runCatching { uriHandler.openUri("https://maps.google.com/?q=$latitude,$longitude") } }) { Text("عرض", color = Liquid.GoldDeep) }
                TextButton(onClick = { onLocated(null, null) }) { Text("إزالة", color = MaterialTheme.colorScheme.error) }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            "يساعد المندوب على الوصول إليك بسهولة. استخدمه وأنت في مكان التوصيل.",
            style = MaterialTheme.typography.bodySmall,
            color = Liquid.InkSoft,
        )
    }
}
