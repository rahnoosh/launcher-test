package com.launcher.tv.data.iptv

import android.content.Context
import com.launcher.tv.data.SportsEvent

/** One provider/channel option for an event, after trying its stream. */
data class CheckRow(
    val candidate: ChannelCandidate,
    val provider: String,
    val result: ProbeResult?,
    val rating: Int,
)

/**
 * "Which provider works best for this event?": takes the channel finder's matches across all
 * providers, tries the best one or two per provider (one at a time, to respect connection limits)
 * and ranks them.
 */
class ProviderCheck(private val context: Context) {
    private val library = IptvLibrary.get(context)

    suspend fun run(event: SportsEvent, known: List<ChannelCandidate>?, update: (List<CheckRow>, Boolean) -> Unit) {
        val candidates = known?.takeIf { it.isNotEmpty() } ?: ChannelFinder(context, library).find(event)
        val names = library.configs().associate { it.id to it.name }
        val picks = pick(candidates)
        var rows = picks.map { CheckRow(it, names[it.channel.sourceId] ?: "Provider", null, -1) }
        update(rows, true)
        for (c in picks) {
            val url = library.liveUrl(c.channel)
            val r = if (url == null) ProbeResult(false, error = "No stream URL") else StreamProbe.probe(url, library.headers(c.channel))
            rows = rows.map { if (it.candidate === c) it.copy(result = r, rating = StreamProbe.rating(r, c)) else it }
            update(sorted(rows), true)
        }
        update(sorted(rows), false)
    }

    companion object {
        /** Best two matches per provider, strongest providers first, at most eight streams. */
        fun pick(candidates: List<ChannelCandidate>): List<ChannelCandidate> =
            candidates.filter { it.score >= 25 }
                .groupBy { it.channel.sourceId }
                .values.flatMap { l -> l.sortedByDescending { it.score }.take(2) }
                .sortedByDescending { it.score }
                .take(8)

        fun sorted(rows: List<CheckRow>) = rows.sortedWith(compareByDescending<CheckRow> { it.rating }.thenByDescending { it.candidate.score })
    }
}
