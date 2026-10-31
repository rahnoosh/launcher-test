package com.launcher.tv.data.iptv

import android.content.Context
import com.launcher.tv.data.SportsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ChannelCandidate(
    val channel: XtreamChannel,
    val score: Int,
    val reason: String,
    /** What the TV guide says is on around kick-off, if we checked. */
    val epgTitle: String? = null,
)

/**
 * Works out which IPTV channel is showing a game:
 *  1. channels you picked before for this team or network,
 *  2. event channels named after the teams ("NFL 05: Patriots vs Bills"),
 *  3. channels of the network ESPN lists (CBS, ESPN, local affiliates...),
 *  4. the TV guide on the best candidates, when the game is close.
 */
class ChannelFinder(context: Context, private val client: XtreamClient) {
    private val prefs = context.getSharedPreferences("channel_choices", Context.MODE_PRIVATE)

    suspend fun find(event: SportsEvent): List<ChannelCandidate> {
        val channels = client.channels()
        val categories = runCatching { client.liveCategories() }.getOrDefault(emptyMap())
        val learned = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val scored = withContext(Dispatchers.Default) { score(event, channels, categories, learned) }
        val now = System.currentTimeMillis()
        val soon = event.live || event.startMillis - now < 6 * 3600_000L
        return if (soon) checkGuide(event, scored).sortedByDescending { it.score } else scored
    }

    /** Remember the user's pick so the same team/network goes straight to it next time. */
    fun remember(event: SportsEvent, channel: XtreamChannel) {
        prefs.edit().apply {
            learnKeys(event).forEach { putString(it, channel.streamId) }
        }.apply()
    }

    private suspend fun checkGuide(event: SportsEvent, scored: List<ChannelCandidate>): List<ChannelCandidate> = coroutineScope {
        val gate = Semaphore(6)
        val top = scored.take(25)
        val checked = top.map { c ->
            async {
                gate.withPermit {
                    val epg = try { client.shortEpg(c.channel.streamId, 6) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    applyGuide(event, c, epg)
                }
            }
        }.awaitAll()
        checked + scored.drop(top.size)
    }

    companion object {
        private val COUNTRY = setOf("US", "USA")
        private val QUALITY = setOf("HD", "FHD", "UHD", "4K", "SD", "HEVC", "H265", "RAW", "60FPS", "50FPS")
        private val GENERIC = COUNTRY + QUALITY + setOf("TV", "CHANNEL", "LIVE", "EAST", "WEST")

        /** ESPN broadcast names -> how providers usually name the channel. */
        private val NETWORK_ALIASES = mapOf(
            "NFL NET" to listOf("NFL Network", "NFL NET", "NFLN"),
            "NFLN" to listOf("NFL Network", "NFL NET", "NFLN"),
            "SECN" to listOf("SEC Network", "SECN"),
            "SEC NETWORK" to listOf("SEC Network", "SECN"),
            "ACCN" to listOf("ACC Network", "ACCN"),
            "CBSSN" to listOf("CBS Sports Network", "CBSSN"),
            "BTN" to listOf("Big Ten Network", "BTN"),
            "FS1" to listOf("FS1", "Fox Sports 1"),
            "FS2" to listOf("FS2", "Fox Sports 2"),
            "SNLA" to listOf("SportsNet LA", "SNLA", "Spectrum SportsNet LA"),
            "SPECTRUM SN" to listOf("Spectrum SportsNet", "Spectrum SN"),
            "PRIME VIDEO" to listOf("Prime Video", "Amazon Prime", "Prime"),
            "PEACOCK" to listOf("Peacock"),
            "ESPN+" to listOf("ESPN Plus", "ESPN+"),
            "PARAMOUNT+" to listOf("Paramount Plus", "Paramount+"),
            "NBA TV" to listOf("NBA TV"),
            "MLB NET" to listOf("MLB Network", "MLB NET"),
            "MLBN" to listOf("MLB Network"),
            "TRUTV" to listOf("truTV"),
        )

        private fun networks(event: SportsEvent): List<List<String>> =
            event.broadcast.orEmpty().split("·", "/", ",").map { it.trim() }.filter { it.isNotEmpty() }
                .flatMap { b -> NETWORK_ALIASES[b.uppercase()] ?: listOf(b) }
                .map { Names.tokens(it) }.filter { it.isNotEmpty() }.distinct()

        private fun teamWords(event: SportsEvent): List<List<String>> =
            listOfNotNull(event.home, event.away).flatMap { listOfNotNull(it.name, it.fullName) }.map { Names.tokens(it) }.filter { it.isNotEmpty() }

        /** Distinctive words of non-team events: "UFC 329: Smith vs Jones" -> [UFC 329], [SMITH], [JONES]. */
        private fun eventWords(event: SportsEvent): List<List<String>> {
            if (event.home != null) return emptyList()
            val stop = setOf("VS", "V", "THE", "OF", "MAIN", "CARD", "PRELIMS", "GRAND", "PRIX", "AND", "FIGHT", "NIGHT")
            val tokens = Names.tokens(event.title)
            val numbered = Regex("""(UFC)\s*(\d+)""").find(event.title.uppercase())?.let { listOf(listOf(it.groupValues[1], it.groupValues[2])) }.orEmpty()
            return numbered + tokens.filter { it.length >= 4 && it !in stop && it.any(Char::isLetter) && it != "UFC" }.map { listOf(it) }
        }

        private fun leagueWords(event: SportsEvent): List<String> = when (event.league) {
            "NFL" -> listOf("NFL", "FOOTBALL")
            "College Football" -> listOf("FOOTBALL", "NCAA", "NCAAF", "COLLEGE")
            "NBA" -> listOf("NBA", "BASKETBALL")
            "MLB" -> listOf("MLB", "BASEBALL")
            "UFC" -> listOf("UFC", "MMA")
            "Boxing" -> listOf("BOXING", "PPV")
            "F1" -> listOf("F1", "FORMULA", "PRIX")
            else -> emptyList()
        }

        fun learnKeys(event: SportsEvent): List<String> =
            listOfNotNull(event.home, event.away).map { "team:${it.abbr.uppercase()}" } +
                networks(event).map { "net:${it.joinToString(" ")}" } +
                (if (event.home == null) listOf("league:${event.league}") else emptyList())

        /** Pure scoring, no network: unit-tested. */
        fun score(
            event: SportsEvent,
            channels: List<XtreamChannel>,
            categories: Map<String, String>,
            learned: Map<String, String>,
        ): List<ChannelCandidate> {
            val nets = networks(event)
            val teams = teamWords(event)
            val words = eventWords(event)
            val league = leagueWords(event)
            val learnedIds = learnKeys(event).mapNotNull { learned[it] }.toSet()

            return channels.mapNotNull { ch ->
                val t = Names.tokens(ch.name)
                if (t.isEmpty()) return@mapNotNull null
                val cat = Names.tokens(categories[ch.categoryId].orEmpty())
                var score = 0
                val why = mutableListOf<String>()

                if (ch.streamId in learnedIds) { score += 90; why += "your pick" }

                val teamHits = teams.count { Names.findRun(t, it) != null }
                    .coerceAtMost(2)
                if (teamHits > 0) {
                    score += if (teamHits >= 2) 75 else 45
                    why += "team in name"
                }
                val wordHits = words.count { Names.findRun(t, it) != null }
                if (wordHits > 0) { score += (30 + 15 * wordHits).coerceAtMost(75); why += "event in name" }

                for (net in nets) {
                    val run = Names.findRun(t, net) ?: continue
                    // "ESPN" must not match "ESPN 2"; "FOX" must not match "FOX NEWS".
                    val next = t.getOrNull(run.last + 1)
                    if (next != null && (next.all(Char::isDigit) || next in setOf("NEWS", "BUSINESS", "DEPORTES", "LIFE", "WEATHER", "SOUL", "KIDS"))) continue
                    val extra = t.filterIndexed { i, w -> i !in run && w !in GENERIC }.size
                    score += (45 - 5 * extra).coerceAtLeast(20)
                    why += "network"
                    break
                }
                if (score == 0) return@mapNotNull null

                if (t.any { it in COUNTRY } || cat.any { it in COUNTRY }) score += 8
                if (t.any { it == "FHD" || it == "HD" || it == "UHD" || it == "4K" }) score += 3
                if (league.any { it in t || it in cat }) score += 5
                ChannelCandidate(ch, score, why.distinct().joinToString(", "))
            }.sortedByDescending { it.score }.take(40)
        }

        /** Boosts a candidate whose guide shows the game around kick-off. */
        fun applyGuide(event: SportsEvent, c: ChannelCandidate, epg: List<EpgEntry>): ChannelCandidate {
            val window = (event.startMillis - 45 * 60_000L)..(event.startMillis + 45 * 60_000L)
            val atStart = epg.firstOrNull { it.startMillis <= window.last && it.endMillis >= window.first } ?: return c
            val text = Names.tokens(atStart.title + " " + atStart.description)
            val teams = teamWords(event).count { Names.findRun(text, it) != null }.coerceAtMost(2)
            val words = eventWords(event).count { Names.findRun(text, it) != null }
            val league = leagueWords(event).any { it in text }
            val bonus = when {
                teams >= 2 -> 70
                teams == 1 -> 45
                words >= 2 -> 55
                words == 1 -> 30
                league -> 12
                else -> -10
            }
            return c.copy(score = c.score + bonus, epgTitle = atStart.title, reason = if (bonus > 12) c.reason + ", in TV guide" else c.reason)
        }
    }
}
