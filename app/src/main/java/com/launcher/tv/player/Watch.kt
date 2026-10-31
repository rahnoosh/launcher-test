package com.launcher.tv.player

import android.content.Context
import android.content.Intent
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.CatalogMatcher
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.ChannelFinder
import com.launcher.tv.data.iptv.XtreamClient

/** One place that turns "watch this" into playback: IPTV first, TiviMate as the fallback. */
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
        val client = XtreamClient.get(context)
        if (!client.isConfigured) return tivimate(context, null)
        val candidates = choice?.let { listOf(it) } ?: known?.takeIf { it.isNotEmpty() } ?: try {
            ChannelFinder(context, client).find(event)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return tivimate(context, "Couldn't load your channels (${e.message})")
        }
        val best = candidates.firstOrNull() ?: return if (interactive) Result.Choose(emptyList())
        else tivimate(context, "No channel found for ${event.title}")

        val clearWinner = best.score >= 90 || candidates.getOrNull(1)?.let { best.score - it.score >= 15 } ?: (best.score >= 45)
        if (choice == null && interactive && !clearWinner) return Result.Choose(candidates)
        if (choice != null) ChannelFinder(context, client).remember(event, choice.channel)

        val ch = best.channel
        val now = System.currentTimeMillis()
        val url = if (startOver && ch.hasArchive && event.startMillis < now) {
            val from = event.startMillis - 5 * 60_000L
            client.timeshiftUrl(ch.streamId, from, ((now - from) / 60_000L + 240).toInt())
        } else {
            client.liveUrl(ch.streamId)
        }
        context.startActivity(PlayerActivity.liveIntent(context, url, "${event.title} · ${ch.name}", event.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.Playing(ch.name)
    }

    /** Next-up episode or watchlist movie from the provider's catalogue. */
    suspend fun media(context: Context, item: MediaItem): Result {
        val client = XtreamClient.get(context)
        if (!client.isConfigured) return tivimate(context, null)
        val matcher = CatalogMatcher(client)
        val found = try {
            if (item.kind == MediaKind.MOVIE) matcher.movie(item) else matcher.episode(item)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return tivimate(context, "Couldn't search your provider (${e.message})")
        } ?: return tivimate(context, "${item.title} isn't in your provider's catalogue")

        val intent = if (item.kind == MediaKind.MOVIE) {
            PlayerActivity.movieIntent(context, found.url, found.title, item.traktId, item.imageUrl, item.posterUrl)
        } else {
            PlayerActivity.episodeIntent(context, found.url, found.title, item.title, item.traktId, item.season ?: 0, item.episode ?: 0, item.imageUrl, item.posterUrl)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.Playing(found.title)
    }

    private fun tivimate(context: Context, why: String?): Result {
        AppsRepository(context).launch(AppsRepository.TIVIMATE)
        return Result.OpenedTiviMate(why)
    }
}
