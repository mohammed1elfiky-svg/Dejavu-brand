package com.dejavu.core.ui

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import okhttp3.Call

/**
 * The app's picture loader: a quarter of the memory for decoded pictures and 300 MB on disk,
 * so a picture is downloaded once and then shows instantly (and without a connection).
 * Pictures are decoded at the size they are shown, and WebP / AVIF files from the server are
 * decoded natively, so serving lighter formats needs no change here.
 */
fun dejavuImageLoader(context: Context, callFactory: () -> Call.Factory): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = callFactory)) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .build()
