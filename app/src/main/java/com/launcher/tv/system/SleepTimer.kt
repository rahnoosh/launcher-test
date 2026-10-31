package com.launcher.tv.system

import android.content.Context

/**
 * Sleep timer. The end time lives in preferences; the HomeBase accessibility service checks it,
 * warns a minute before (OK = 30 more minutes) and then puts the box to sleep.
 */
object SleepTimer {
    private val STEPS = listOf(30, 60, 90, 120)

    private fun prefs(c: Context) = c.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)

    /** End time, or 0 when off. */
    fun endsAt(c: Context): Long = prefs(c).getLong("ends_at", 0L)

    fun minutesLeft(c: Context, now: Long = System.currentTimeMillis()): Int {
        val end = endsAt(c)
        return if (end <= now) 0 else (((end - now) + 59_999) / 60_000).toInt()
    }

    fun set(c: Context, minutes: Int, now: Long = System.currentTimeMillis()) {
        prefs(c).edit()
            .putLong("ends_at", if (minutes > 0) now + minutes * 60_000L else 0L)
            .putBoolean("warned", false)
            .apply()
    }

    fun extend(c: Context, minutes: Int, now: Long = System.currentTimeMillis()) {
        val base = maxOf(now, endsAt(c))
        prefs(c).edit().putLong("ends_at", base + minutes * 60_000L).putBoolean("warned", false).apply()
    }

    fun clear(c: Context) = set(c, 0)

    fun warned(c: Context) = prefs(c).getBoolean("warned", false)
    fun setWarned(c: Context) = prefs(c).edit().putBoolean("warned", true).apply()

    /** Off -> 30 -> 60 -> 90 -> 120 -> Off, starting from whatever is left now. */
    fun nextStep(minutesLeft: Int): Int = STEPS.firstOrNull { it > minutesLeft + 2 } ?: 0

    fun label(minutesLeft: Int): String = when {
        minutesLeft <= 0 -> "Off"
        minutesLeft >= 60 -> "${minutesLeft / 60} h ${minutesLeft % 60} min left".replace(" 0 min", "")
        else -> "$minutesLeft min left"
    }
}
