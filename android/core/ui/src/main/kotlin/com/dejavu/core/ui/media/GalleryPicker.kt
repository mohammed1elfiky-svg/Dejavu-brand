package com.dejavu.core.ui.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dejavu.core.ui.ShimmerList
import com.dejavu.core.ui.theme.Button
import com.dejavu.core.ui.theme.Liquid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the in-app gallery that picks several photos at once. */
class MultiImagePicker internal constructor(val open: () -> Unit)

/**
 * Several photos from the gallery in one go. The grid is the phone's own photo library and it
 * remembers where you were: leave by mistake and open it again, and it is at the same photo.
 * [onPicked] gets each chosen photo as a JPEG ready to upload (at most [maxCount], scaled to [maxSide]).
 */
@Composable
fun rememberMultiImagePicker(maxCount: () -> Int, maxSide: Int = 1600, onPicked: (List<ByteArray>) -> Unit): MultiImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showing by remember { mutableStateOf(false) }
    val deliver: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { uris.take(maxCount().coerceAtLeast(0)).mapNotNull { compressJpeg(context, it, maxSide) } }
            if (bytes.isNotEmpty()) onPicked(bytes)
        }
    }
    // Without permission to read the library the system picker still lets several be chosen.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { deliver(it) }
    val permissions = remember { requiredPermissions() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (canRead(context)) showing = true else systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    if (showing) GalleryDialog(maxCount = maxCount(), onDismiss = { showing = false }, onDone = { uris -> showing = false; deliver(uris) })
    return remember(systemPicker, ask) {
        MultiImagePicker {
            if (canRead(context)) showing = true else ask.launch(permissions)
        }
    }
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun canRead(context: Context): Boolean = requiredPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Where the gallery was left: the photo at the top of the screen, kept even after the app closes. */
private object GalleryMemory {
    private const val PREFS = "gallery_memory"
    private const val KEY = "anchor"

    fun load(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, -1L)
    fun save(context: Context, id: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, id).apply()
    }
}

private suspend fun photoIds(context: Context): List<Long> = withContext(Dispatchers.IO) {
    val out = ArrayList<Long>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out.add(c.getLong(col))
        }
    }
    out
}

private fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

@Composable
private fun GalleryDialog(maxCount: Int, onDismiss: () -> Unit, onDone: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    var ids by remember { mutableStateOf<List<Long>?>(null) }
    val chosen = remember { mutableStateListOf<Long>() }
    LaunchedEffect(Unit) { ids = photoIds(context) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("إلغاء") }
                    Text(
                        if (chosen.isEmpty()) "اختر الصور (حتى $maxCount)" else "${chosen.size} من $maxCount",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { onDone(chosen.map(::uriOf)) }, enabled = chosen.isNotEmpty()) { Text("إضافة") }
                }
                val list = ids
                when {
                    list == null -> ShimmerList()
                    list.isEmpty() -> Text("لا توجد صور في المعرض", Modifier.padding(32.dp))
                    else -> {
                        // Open where it was left: the photo remembered at the top, found by its id.
                        val saved = remember { GalleryMemory.load(context) }
                        val start = remember(list) { list.indexOf(saved).coerceAtLeast(0) }
                        val grid = rememberLazyGridState(initialFirstVisibleItemIndex = start)
                        LaunchedEffect(grid, list) {
                            snapshotFlow { grid.firstVisibleItemIndex }.debounce(250).collect { i -> list.getOrNull(i)?.let { GalleryMemory.save(context, it) } }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = grid,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(list, key = { it }) { id ->
                                val order = chosen.indexOf(id)
                                Box(
                                    Modifier.aspectRatio(1f).clickable {
                                        if (order >= 0) chosen.remove(id) else if (chosen.size < maxCount) chosen.add(id)
                                    },
                                ) {
                                    AsyncImage(model = uriOf(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    if (order >= 0) {
                                        Box(Modifier.fillMaxSize().background(Liquid.Gold.copy(alpha = 0.25f)))
                                        Box(
                                            Modifier.padding(6.dp).size(24.dp).align(Alignment.TopEnd).clip(CircleShape).background(Liquid.GoldDeep),
                                            contentAlignment = Alignment.Center,
                                        ) { Text("${order + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                                    } else {
                                        Box(Modifier.padding(6.dp).size(22.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
