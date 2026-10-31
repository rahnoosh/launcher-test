package com.launcher.tv.data.iptv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Xtream panels are inconsistent about types (ids as numbers or strings, "1" vs 1), so the
 * lean models below use String everywhere and a lenient parser.
 */
internal val XJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class XtreamChannel(
    @SerialName("stream_id") val streamId: String = "",
    val name: String = "",
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("tv_archive") val tvArchive: String? = null,
    @SerialName("tv_archive_duration") val archiveDays: String? = null,
    @SerialName("stream_icon") val icon: String? = null,
) {
    val hasArchive: Boolean get() = tvArchive == "1" || tvArchive == "true"
}

@Serializable
data class XtreamCategory(
    @SerialName("category_id") val id: String = "",
    @SerialName("category_name") val name: String = "",
)

@Serializable
data class XtreamSeries(
    @SerialName("series_id") val seriesId: String = "",
    val name: String = "",
    val cover: String? = null,
    val tmdb: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    val releaseDate: String? = null,
    val year: String? = null,
) {
    val tmdbAny: String? get() = (tmdb ?: tmdbId)?.takeIf { it.isNotBlank() && it != "0" }
    val yearAny: Int? get() = (year ?: releaseDate)?.take(4)?.toIntOrNull()
}

@Serializable
data class XtreamVod(
    @SerialName("stream_id") val streamId: String = "",
    val name: String = "",
    @SerialName("container_extension") val extension: String? = null,
    val tmdb: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    val year: String? = null,
    @SerialName("stream_icon") val icon: String? = null,
) {
    val tmdbAny: String? get() = (tmdb ?: tmdbId)?.takeIf { it.isNotBlank() && it != "0" }
}

data class XtreamEpisode(val id: String, val season: Int, val episode: Int, val title: String, val extension: String)

data class XtreamAccount(
    val active: Boolean,
    val status: String,
    val expiresMillis: Long?,
    val maxConnections: String?,
    val serverTimezone: String?,
)
