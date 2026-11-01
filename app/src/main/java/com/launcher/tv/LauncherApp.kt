package com.launcher.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LauncherApp : Application(), ImageLoaderFactory {
    /** Outlives activities: used to finish Trakt scrobbles after the player closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        com.launcher.tv.data.ApiKeys.apply(this)
        // Phone page server (PIN-protected, home network only). Runs while HomeBase or its service runs.
        com.launcher.tv.phone.PhoneLink.start(this)
    }

    // The box has ~2 GB RAM: keep the image memory cache small, lean on the disk cache.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(100L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
