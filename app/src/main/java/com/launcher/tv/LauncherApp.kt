package com.launcher.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class LauncherApp : Application(), ImageLoaderFactory {
    // The box has ~2 GB RAM: keep the image memory cache small, lean on the disk cache.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(100L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
