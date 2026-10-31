package com.launcher.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.launcher.tv.data.AppEntry
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.HomeRepository
import com.launcher.tv.data.history.HistoryEntry
import com.launcher.tv.data.history.WatchHistory
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TraktState {
    data object NotConfigured : TraktState
    data object SignedOut : TraktState
    data class AwaitingCode(val userCode: String, val url: String) : TraktState
    data object SignedIn : TraktState
}

data class HomeUiState(
    val data: HomeData = HomeData(),
    val apps: List<AppEntry> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val refreshing: Boolean = false,
    val trakt: TraktState = TraktState.SignedOut,
    val message: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val traktClient = TraktClient(app)
    private val repo = HomeRepository(app, traktClient)
    val apps = AppsRepository(app)
    private val history = WatchHistory.get(app)

    private val _state = MutableStateFlow(HomeUiState(trakt = currentTraktState()))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var authJob: Job? = null
    private val cacheReady = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            _state.update { it.copy(data = repo.loadCached()) }
            cacheReady.complete(Unit)
            refresh(force = false)
        }
        reloadApps()
        viewModelScope.launch {
            history.load()
            history.entries.collect { list -> _state.update { it.copy(history = list) } }
        }
    }

    fun removeFromHistory(entry: HistoryEntry) {
        viewModelScope.launch { history.remove(entry.url) }
    }

    fun onResume() {
        refresh(force = false)
        reloadApps()
    }

    fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            cacheReady.await() // never refresh on top of an empty state and lose cached sections
            if (!force && !isStale()) return@launch
            _state.update { it.copy(refreshing = true) }
            val data = repo.refresh(_state.value.data)
            _state.update { it.copy(data = data, refreshing = false, trakt = currentTraktState()) }
        }
    }

    private fun isStale(): Boolean {
        val data = _state.value.data
        val age = System.currentTimeMillis() - data.fetchedAt
        // Live games need fresher scores; otherwise refresh every 30 minutes.
        val hasLive = (data.myTeams + data.nfl + data.fights).any { it.live }
        return age > if (hasLive) 2 * 60_000L else 30 * 60_000L
    }

    private fun reloadApps() {
        viewModelScope.launch { _state.update { it.copy(apps = apps.loadApps()) } }
    }

    fun connectTrakt() {
        if (!traktClient.isConfigured || authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            try {
                val code = traktClient.startDeviceAuth()
                _state.update { it.copy(trakt = TraktState.AwaitingCode(code.userCode, code.verificationUrl)) }
                val ok = traktClient.awaitDeviceToken(code)
                _state.update {
                    it.copy(trakt = currentTraktState(), message = if (ok) "Connected to Trakt" else "Trakt sign-in didn't complete")
                }
                if (ok) refresh(force = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(trakt = currentTraktState(), message = "Couldn't reach Trakt: ${e.message}") }
            }
        }
    }

    fun cancelTraktAuth() {
        authJob?.cancel()
        _state.update { it.copy(trakt = currentTraktState()) }
    }

    fun signOutTrakt() {
        traktClient.signOut()
        _state.update {
            it.copy(
                trakt = currentTraktState(),
                data = it.data.copy(upNext = emptyList(), watchlist = emptyList(), upcomingEpisodes = emptyList()),
            )
        }
    }

    fun launchPackage(pkg: String) {
        if (!apps.launch(pkg)) _state.update { it.copy(message = "App not installed: $pkg") }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun currentTraktState(): TraktState = when {
        !traktClient.isConfigured -> TraktState.NotConfigured
        traktClient.isSignedIn -> TraktState.SignedIn
        else -> TraktState.SignedOut
    }
}
