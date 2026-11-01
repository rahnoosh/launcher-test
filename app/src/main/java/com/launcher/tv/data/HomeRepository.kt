package com.launcher.tv.data

import android.content.Context
import com.launcher.tv.data.sports.EventFollow
import com.launcher.tv.data.sports.FollowStore
import com.launcher.tv.data.sports.SportsRepository
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Loads all home-screen sections in parallel and caches the result on disk.
 * If one source fails, its previous cached data is kept.
 */
class HomeRepository(private val context: Context, val trakt: TraktClient) {
    private val cacheFile = File(context.filesDir, "home_cache.json")

    suspend fun loadCached(): HomeData = withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString<HomeData>(cacheFile.readText()) }.getOrDefault(HomeData())
    }

    suspend fun refresh(previous: HomeData): HomeData = coroutineScope {
        val signedIn = trakt.isConfigured && trakt.isSignedIn
        val upNext = async { if (signedIn) safe { trakt.upNext() } else emptyList() }
        val watchlist = async { if (signedIn) safe { trakt.watchlistMovies() } else emptyList() }
        val upcoming = async { if (signedIn) safe { trakt.upcomingEpisodes() } else emptyList() }
        val follow = FollowStore.load(context)
        val teams = async { SportsRepository.myTeamsAndResults(follow) }
        val nfl = async { SportsRepository.nflThisWeek(follow.follows(EventFollow.NFL_WEEK)) }
        val fights = async { SportsRepository.fightsAndResults(follow.follows(EventFollow.UFC), follow.follows(EventFollow.BOXING)) }
        val f1 = async { SportsRepository.f1AndResults(follow.follows(EventFollow.F1)) }
        val majors = async { SportsRepository.majors(follow.follows(EventFollow.GOLF), follow.follows(EventFollow.TENNIS)) }

        val data = HomeData(
            upNext = upNext.await() ?: previous.upNext,
            watchlist = watchlist.await() ?: previous.watchlist,
            upcomingEpisodes = upcoming.await() ?: previous.upcomingEpisodes,
            myTeams = teams.await()?.first ?: previous.myTeams,
            nfl = nfl.await() ?: previous.nfl,
            fights = fights.await()?.first ?: previous.fights,
            f1 = f1.await()?.first ?: previous.f1,
            events = majors.await() ?: previous.events,
            results = run {
                val t = teams.await()?.second
                val f = fights.await()?.second
                val r = f1.await()?.second
                if (t == null && f == null && r == null) previous.results
                else (t.orEmpty() + f.orEmpty() + r.orEmpty()).sortedByDescending { it.startMillis }
            },
            fetchedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            runCatching { cacheFile.writeText(Http.json.encodeToString(HomeData.serializer(), data)) }
        }
        data
    }

    companion object {
        /** The last refreshed home data, without touching the network (phone page, alerts). */
        fun readCache(context: Context): HomeData =
            runCatching { Http.json.decodeFromString(HomeData.serializer(), File(context.filesDir, "home_cache.json").readText()) }.getOrDefault(HomeData())
    }

    private suspend fun <T> safe(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
