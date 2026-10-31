package com.launcher.tv.alerts

import android.content.Context

object AlertSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("enabled", v).apply()

    /** Touchdowns, runs, etc. Off = only "starting soon", "under way" and "final". */
    fun scoring(c: Context) = prefs(c).getBoolean("scoring", true)
    fun setScoring(c: Context, v: Boolean) = prefs(c).edit().putBoolean("scoring", v).apply()
}

/** Set by the player so alerts aren't shown for the game you're already watching. */
object NowPlaying {
    @Volatile var eventId: String? = null
}
