package com.launcher.tv.data

import android.content.Context
import com.launcher.tv.BuildConfig
import com.launcher.tv.data.tmdb.Tmdb

/**
 * Trakt and TMDB keys, entered on the TV or the phone page and stored only on the box.
 * Keeping them out of the APK means the builds published for self-update contain no secrets.
 * (A local build can still bake them in via local.properties; entered keys win.)
 */
object ApiKeys {
    const val CHANGED = "keys"

    private fun prefs(c: Context) = c.getSharedPreferences("api_keys", Context.MODE_PRIVATE)
    private fun stored(c: Context, k: String) = prefs(c).getString(k, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun traktId(c: Context): String = stored(c, "trakt_id") ?: BuildConfig.TRAKT_CLIENT_ID
    fun traktSecret(c: Context): String = stored(c, "trakt_secret") ?: BuildConfig.TRAKT_CLIENT_SECRET
    fun tmdb(c: Context): String = stored(c, "tmdb") ?: BuildConfig.TMDB_API_KEY

    fun hasTrakt(c: Context) = traktId(c).isNotBlank() && traktSecret(c).isNotBlank()
    fun hasTmdb(c: Context) = tmdb(c).isNotBlank()

    /** Blank or null values leave that key as it was. */
    fun save(c: Context, traktId: String?, traktSecret: String?, tmdb: String?) {
        prefs(c).edit().apply {
            traktId?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("trakt_id", it) }
            traktSecret?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("trakt_secret", it) }
            tmdb?.trim()?.takeIf { it.isNotEmpty() }?.let { putString("tmdb", it) }
        }.apply()
        Tmdb.apiKey = tmdb(c)
        ChangeBus.emit(CHANGED)
    }

    /** Called at app start so TMDB artwork works everywhere. */
    fun apply(c: Context) {
        Tmdb.apiKey = tmdb(c)
    }
}
