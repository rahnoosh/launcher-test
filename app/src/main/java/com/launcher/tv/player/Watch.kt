package com.launcher.tv.player

import android.content.Context
import android.content.Intent
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.ChannelFinder
import com.launcher.tv.data.iptv.IptvLibrary

/** One place that turns "watch this" into playback: your IPTV providers first, TiviMate as the fallback. */
object Watch {
    sealed interface Result {
        data class Playing(val what: String) : Result
        /** Several plausible channels and nothing remembered: let the user pick. */
        data class Choose(val candidates: List<ChannelCandidate>) : Result
        data class OpenedTiviMate(val why: String?) : Result
    }

    /**
     * @param interactive false from the alert pop-up: no picker there, so the best channel just plays.
     */
    suspend fun game(
        context: Context,
        event: SportsEvent,
        choice: ChannelCandidate? = null,
        startOver: Boolean = false,
        interactive: Boolean = true,
        known: List<ChannelCandidate>? = null,
    ): Result {
        val library = IptvLibrary.get(context)
        if (!library.isConfigured) return tivimate(context, null)
        val candidates = choice?.let { listOf(it) } ?: known?.takeIf { it.isNotEmpty() } ?: try {
            ChannelFinder(context, library).find(event)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return tivimate(context, "Couldn't load your channels (${e.message})")
        }
        val best = candidates.firstOrNull() ?: return if (interactive) Result.Choose(emptyList())
        else tivimate(context, "No channel found for ${event.title}")

        val clearWinner = best.score >= 90 || candidates.getOrNull(1)?.let { best.score - it.score >= 15 } ?: (best.score >= 45)
        if (choice == null && interactive && !clearWinner) return Result.Choose(candidates)
        if (choice != null) ChannelFinder(context, library).remember(event, choice.channel)

        val ch = best.channel
        val now = System.currentTimeMillis()
        val url = (if (startOver && event.startMillis < now) {
            val from = event.startMillis - 5 * 60_000L
            library.catchupUrl(ch, from, ((now - from) / 60_000L + 240).toInt())
        } else null) ?: library.liveUrl(ch) ?: return tivimate(context, "Couldn't open ${ch.name}")
        context.startActivity(
            PlayerActivity.liveIntent(context, url, "${event.title} · ${ch.name}", event.id, library.headers(ch)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return Result.Playing(ch.name)
    }

    /** Next-up episode or watchlist movie from your providers' catalogues. */
    suspend fun media(context: Context, item: MediaItem): Result {
        val library = IptvLibrary.get(context)
        if (!library.isConfigured) return tivimate(context, null)
        val found = try {
            library.find(item)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return tivimate(context, "Couldn't search your providers (${e.message})")
        } ?: return tivimate(context, "${item.title} isn't in your providers' catalogues")

        val intent = if (item.kind == MediaKind.MOVIE) {
            PlayerActivity.movieIntent(context, found.url, found.title, item.traktId, item.imageUrl, item.posterUrl)
        } else {
            PlayerActivity.episodeIntent(context, found.url, found.title, item.title, item.traktId, item.season ?: 0, item.episode ?: 0, item.imageUrl, item.posterUrl)
        }
        if (found.headers.isNotEmpty()) intent.putExtra("headers", found.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.Playing(found.title)
    }

    private fun tivimate(context: Context, why: String?): Result {
        AppsRepository(context).launch(AppsRepository.TIVIMATE)
        return Result.OpenedTiviMate(why)
    }
}
