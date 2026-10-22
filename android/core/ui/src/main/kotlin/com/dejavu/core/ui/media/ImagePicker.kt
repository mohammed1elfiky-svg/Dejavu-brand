package com.dejavu.core.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Gallery and camera pickers that hand back a JPEG ready to upload (≤1600 px, upright). */
class ImagePicker internal constructor(val fromGallery: () -> Unit, val fromCamera: () -> Unit)

@Composable
fun rememberImagePicker(maxSide: Int = 1600, onPicked: (ByteArray) -> Unit): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver: (Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch { withContext(Dispatchers.IO) { compressJpeg(context, uri, maxSide) }?.let(onPicked) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), deliver)
    val photoUri = remember { newPhotoUri(context) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) deliver(photoUri) }
    return remember(gallery, camera) {
        ImagePicker(
            fromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            fromCamera = { camera.launch(photoUri) },
        )
    }
}

private fun newPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    val file = File(dir, "capture.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}

/** Downscales to [maxSide], applies the EXIF rotation and re-encodes as JPEG (quality 82). */
fun compressJpeg(context: Context, uri: Uri, maxSide: Int = 1600): ByteArray? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return null
    val rotation = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation)
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
}.getOrNull()
