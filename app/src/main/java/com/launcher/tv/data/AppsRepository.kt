package com.launcher.tv.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val packageName: String, val banner: Bitmap?, val isBanner: Boolean)

/** Installed TV apps for the Apps row, plus launching apps by package. */
class AppsRepository(private val context: Context) {
    private val pm = context.packageManager

    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val info = ri.activityInfo
                val banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm)
                val drawable = banner ?: info.loadIcon(pm)
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = info.packageName,
                    banner = runCatching {
                        if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(128, 128)
                    }.getOrNull(),
                    isBanner = banner != null,
                )
            }
            .sortedWith(compareBy<AppEntry> { it.packageName != TIVIMATE }.thenBy { it.label.lowercase() })
    }

    fun launch(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        const val TIVIMATE = "ar.tvplayer.tv"
    }
}
