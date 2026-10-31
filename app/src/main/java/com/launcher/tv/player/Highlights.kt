package com.launcher.tv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.sports.GameDetail
import com.launcher.tv.data.sports.GameDetails
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/** Highlights: ESPN's own clip in the HomeBase player when there is one, otherwise a YouTube search. */
object Highlights {
    /** Returns a message for the user, or null when something started playing. */
    suspend fun play(context: Context, event: SportsEvent, known: GameDetail? = null): String? {
        val detail = known ?: if (event.home != null) runCatching { GameDetails.load(event) }.getOrNull() else null
        val clip = (detail as? GameDetail.Team)?.highlights?.firstOrNull { ".mp4" in it.url || ".m3u8" in it.url }
        if (clip != null) {
            context.startActivity(
                PlayerActivity.liveIntent(
                    context, clip.url,
                    if (com.launcher.tv.data.Spoilers.hides(context, event)) "Highlights · ${event.title}" else "${clip.title} · ${event.title}",
                    null,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return null
        }
        return if (youtube(context, query(event))) null else "Couldn't open YouTube"
    }

    fun query(e: SportsEvent): String {
        val home = e.home
        val away = e.away
        val year = Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.systemDefault()).year
        return when {
            home != null && away != null -> "${away.fullName ?: away.name} vs ${home.fullName ?: home.name} highlights"
            e.league == "F1" -> "F1 ${e.title} $year highlights"
            else -> "${e.title.substringBefore(":")} highlights"
        }
    }

    private fun youtube(context: Context, query: String): Boolean {
        val attempts = listOf(
            Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv").putExtra("query", query),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))),
        )
        for (i in attempts) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}
