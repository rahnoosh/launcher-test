package com.launcher.tv.data.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
)

@Serializable
data class TraktShow(val title: String = "", val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktMovie(val title: String = "", val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String? = null,
    val ids: TraktIds = TraktIds(),
    @SerialName("first_aired") val firstAired: String? = null,
)

@Serializable
data class WatchedShow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val show: TraktShow,
)

@Serializable
data class ShowProgress(
    val aired: Int = 0,
    val completed: Int = 0,
    @SerialName("next_episode") val nextEpisode: TraktEpisode? = null,
)

@Serializable
data class WatchlistMovie(@SerialName("listed_at") val listedAt: String? = null, val movie: TraktMovie)

@Serializable
data class CalendarEntry(
    @SerialName("first_aired") val firstAired: String? = null,
    val episode: TraktEpisode,
    val show: TraktShow,
)

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("created_at") val createdAt: Long,
)
