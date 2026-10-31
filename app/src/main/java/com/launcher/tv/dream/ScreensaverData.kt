package com.launcher.tv.dream

import android.content.Context
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.HomeRepository
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.sports.SportsRepository
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/** Keeps the screensaver's data fresh: full refresh every 30 min, live scores every minute. */
class ScreensaverData(context: Context) {
    private val repo = HomeRepository(context, TraktClient(context))
    private val _state = MutableStateFlow(HomeData())
    val state: StateFlow<HomeData> = _state

    suspend fun run() = coroutineScope {
        _state.value = repo.loadCached()
        while (isActive) {
            val d = _state.value
            val now = System.currentTimeMillis()
            try {
                if (now - d.fetchedAt > 30 * 60_000L) {
                    _state.value = repo.refresh(d)
                } else {
                    val active = (d.myTeams + d.nfl).filter { it.home != null && (it.live || now in it.startMillis..(it.startMillis + 4 * 3600_000L)) }
                    if (active.isNotEmpty()) {
                        val updates = active.map { it.league }.distinct().flatMap { SportsRepository.liveScoreboard(it).orEmpty() }.associateBy { it.id }
                        _state.value = d.copy(myTeams = d.myTeams.map { merge(it, updates) }, nfl = d.nfl.map { merge(it, updates) })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            val anyLive = (_state.value.myTeams + _state.value.nfl).any { it.live }
            delay(if (anyLive) 60_000L else 5 * 60_000L)
        }
    }

    private fun merge(e: SportsEvent, updates: Map<String, SportsEvent>): SportsEvent {
        val u = updates[e.id] ?: return e
        return e.copy(
            live = u.live, finished = u.finished, status = u.status, period = u.period,
            home = e.home?.copy(score = u.home?.score), away = e.away?.copy(score = u.away?.score),
        )
    }
}
