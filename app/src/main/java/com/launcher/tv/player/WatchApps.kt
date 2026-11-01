package com.launcher.tv.player

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.tmdb.Tmdb

/** Apps that can play a movie or episode for you through their own add-ons. */
enum class WatchApp(val key: String, val label: String, val packages: List<String>) {
    STREMIO("stremio", "Stremio", listOf("com.stremio.one")),
    NUVIO("nuvio", "Nuvio", listOf("com.nuvio.tv", "com.nuvio.app")),
    ;

    companion object {
        fun byKey(key: String?): WatchApp? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Opens a next-up episode or watchlist movie in Stremio or Nuvio, on its list of streams, so you pick
 * a source there. Both find titles by IMDb id. HomeBase only hands over the title.
 */
object WatchApps {
    /** The preference value meaning "HomeBase's player with your providers (or TiviMate)". */
    const val HOMEBASE = "homebase"

    private const val PREFS = "ui"
    private const val KEY = "watch_app"

    fun preferred(context: Context): String =
        com.launcher.tv.data.Profiles.prefs(context, PREFS).getString(KEY, HOMEBASE) ?: HOMEBASE

    fun setPreferred(context: Context, key: String) {
        val v = if (WatchApp.byKey(key) != null) key else HOMEBASE
        com.launcher.tv.data.Profiles.prefs(context, PREFS).edit().putString(KEY, v).apply()
    }

    /** The app movies and shows open in, if it's set and installed. */
    fun preferredApp(context: Context): WatchApp? =
        WatchApp.byKey(preferred(context))?.takeIf { packageOf(context, it) != null }

    fun installed(context: Context): List<WatchApp> = WatchApp.entries.filter { packageOf(context, it) != null }

    fun packageOf(context: Context, app: WatchApp): String? = app.packages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Opens [item] in [app]. Returns a message to show, or null when it opened as asked. */
    suspend fun open(context: Context, app: WatchApp, item: MediaItem): String? {
        val pkg = packageOf(context, app) ?: return "${app.label} isn't installed"
        val imdb = imdbOf(item)
        if (imdb == null) {
            AppsRepository(context).launch(pkg)
            return "Couldn't find ${item.title}'s IMDb id: search for it in ${app.label}"
        }
        val target = when (app) {
            WatchApp.STREMIO -> Target(WatchLinks.stremioUri(item, imdb), null, emptyMap())
            WatchApp.NUVIO -> Target(WatchLinks.nuvioUri(item, imdb), NUVIO_ACTIVITY, WatchLinks.nuvioExtras(item, imdb))
        }
        val intents = buildList {
            // Nuvio: the launch its own Continue Watching channel uses, straight to the streams
            // (only when the episode is known; otherwise its link opens the show's page).
            if (target.activity != null && target.extras["launchMode"] == "stream") add(
                Intent(Intent.ACTION_VIEW).setComponent(ComponentName(pkg, target.activity)).apply {
                    target.extras.forEach { (k, v) -> if (v is Int) putExtra(k, v) else putExtra(k, v.toString()) }
                }
            )
            add(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).setPackage(pkg))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return null
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        AppsRepository(context).launch(pkg)
        return "Opened ${app.label}: search for ${item.title} there"
    }

    /** From Trakt/Simkl, or looked up on TMDB. */
    private suspend fun imdbOf(item: MediaItem): String? =
        item.imdbId?.takeIf { it.startsWith("tt") }
            ?: Tmdb.imdbId(if (item.kind == MediaKind.MOVIE) Tmdb.Type.MOVIE else Tmdb.Type.TV, item.tmdbId)?.takeIf { it.startsWith("tt") }

    private class Target(val uri: String, val activity: String?, val extras: Map<String, Any>)

    private const val NUVIO_ACTIVITY = "com.nuvio.tv.MainActivity"
}
