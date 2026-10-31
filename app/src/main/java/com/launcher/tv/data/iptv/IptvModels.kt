package com.launcher.tv.data.iptv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One configured provider. Several can be added; their channels are searched together. */
@Serializable
sealed class SourceConfig {
    abstract val id: String
    abstract val name: String

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        override val id: String,
        override val name: String,
        val server: String,
        val username: String,
        val password: String,
    ) : SourceConfig()

    @Serializable
    @SerialName("m3u")
    data class M3u(
        override val id: String,
        override val name: String,
        val url: String,
        /** XMLTV guide; if empty, the playlist's own url-tvg is used. */
        val epgUrl: String? = null,
    ) : SourceConfig()
}

/** A live channel from any provider. */
@Serializable
data class IptvChannel(
    val sourceId: String,
    /** Xtream stream_id, or the playlist position for M3U. */
    val id: String,
    val name: String,
    val group: String? = null,
    val epgId: String? = null,
    val logo: String? = null,
    val archive: Boolean = false,
    /** M3U only: the stream URL and catch-up settings. */
    val url: String? = null,
    val catchup: String? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
) {
    val key: String get() = "$sourceId:$id"
}

/** Movie or episode entry of an M3U playlist. */
@Serializable
data class M3uVod(val name: String, val url: String, val group: String? = null, val userAgent: String? = null)

@Serializable
data class EpgEntry(val title: String, val description: String, val startMillis: Long, val endMillis: Long)

data class Playable(val url: String, val title: String, val headers: Map<String, String> = emptyMap())

/** What a provider can do. Implemented by [XtreamClient] and [M3uSource]. */
interface IptvSource {
    val config: SourceConfig
    suspend fun channels(): List<IptvChannel>
    suspend fun guide(channel: IptvChannel): List<EpgEntry>
    fun liveUrl(channel: IptvChannel): String
    /** Catch-up from [startMillis], or null if the channel/provider can't. */
    fun catchupUrl(channel: IptvChannel, startMillis: Long, durationMinutes: Int): String?
    fun headers(channel: IptvChannel): Map<String, String> = channel.userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
    suspend fun findEpisode(title: String, year: Int?, tmdbId: Long?, season: Int, episode: Int): Playable?
    suspend fun findMovie(title: String, year: Int?, tmdbId: Long?): Playable?
    suspend fun prefetch()
    /** One line for Settings, e.g. "Active · expires Mar 3" or "2,340 channels". */
    suspend fun status(): String
}
