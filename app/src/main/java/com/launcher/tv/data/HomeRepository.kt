package com.launcher.tv.data

import android.content.Context
import com.launcher.tv.data.sports.SportsRepository
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads all home-screen sections in parallel and caches the result on disk.
 * If one source fails, its previous cached data is kept.
 */
class HomeRepository(context: Context, val trakt: TraktClient) {
    private val cacheFile = File(context.filesDir, "home_cache.json")

    suspend fun loadCached(): HomeData = withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString<HomeData>(cacheFile.readText()) }.getOrDefault(HomeData())
    }

    suspend fun refresh(previous: HomeData): HomeData = coroutineScope {
        val signedIn = trakt.isConfigured && trakt.isSignedIn
        val upNext = async { if (signedIn) safe { trakt.upNext() } else emptyList() }
        val watchlist = async { if (signedIn) safe { trakt.watchlistMovies() } else emptyList() }
        val upcoming = async { if (signedIn) safe { trakt.upcomingEpisodes() } else emptyList() }
        val teams = async { SportsRepository.myTeams() }
        val nfl = async { SportsRepository.nflThisWeek() }
        val fights = async { SportsRepository.fights() }
        val f1 = async { SportsRepository.f1() }

        val data = HomeData(
            upNext = upNext.await() ?: previous.upNext,
            watchlist = watchlist.await() ?: previous.watchlist,
            upcomingEpisodes = upcoming.await() ?: previous.upcomingEpisodes,
            myTeams = teams.await() ?: previous.myTeams,
            nfl = nfl.await() ?: previous.nfl,
            fights = fights.await() ?: previous.fights,
            f1 = f1.await() ?: previous.f1,
            fetchedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            runCatching { cacheFile.writeText(Http.json.encodeToString(HomeData.serializer(), data)) }
        }
        data
    }

    private suspend fun <T> safe(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
