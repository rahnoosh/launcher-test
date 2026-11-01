package com.launcher.tv.data

import android.content.Context

/**
 * Spoiler mode: hides scores of live and finished games everywhere (tiles, hero, strip, game pages,
 * screensaver, alerts) until you choose "Show score" for that game.
 */
object Spoilers {
    private fun prefs(c: Context) = c.getSharedPreferences("spoilers", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    fun revealed(c: Context): Set<String> = prefs(c).getStringSet("revealed", emptySet()).orEmpty()
    fun reveal(c: Context, eventId: String) {
        // Keep the list short; old games don't matter.
        val next = (revealed(c) + eventId).toList().takeLast(200).toSet()
        prefs(c).edit().putStringSet("revealed", next).apply()
    }

    /** Would this game's score be hidden? */
    fun hides(enabled: Boolean, revealed: Set<String>, e: SportsEvent): Boolean =
        enabled && e.id !in revealed && (e.live || e.finished)

    fun hides(c: Context, e: SportsEvent): Boolean = hides(enabled(c), revealed(c), e)

    private val leadingScore = Regex("""^\d+\s*-\s*\d+\s*""")

    /** "87-82 Q3 4:12" -> "Q3 4:12" */
    fun stripScore(status: String?): String? = status?.replace(leadingScore, "")?.ifBlank { null }

    /** The same game without anything that gives the result away. */
    fun safe(e: SportsEvent): SportsEvent = e.copy(
        status = stripScore(e.status),
        home = e.home?.copy(score = null),
        away = e.away?.copy(score = null),
    )

    fun apply(d: HomeData, enabled: Boolean, revealed: Set<String>): HomeData {
        if (!enabled) return d
        fun f(e: SportsEvent) = if (hides(true, revealed, e)) safe(e) else e
        return d.copy(
            myTeams = d.myTeams.map(::f), nfl = d.nfl.map(::f), fights = d.fights.map(::f),
            f1 = d.f1.map(::f), results = d.results.map(::f), events = d.events.map(::f),
        )
    }

    fun apply(c: Context, d: HomeData): HomeData = apply(d, enabled(c), revealed(c))
}
