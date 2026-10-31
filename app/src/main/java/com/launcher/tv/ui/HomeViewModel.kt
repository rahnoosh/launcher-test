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
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.ChannelFinder
import com.launcher.tv.data.iptv.XtreamClient
import com.launcher.tv.data.trakt.TraktClient
import com.launcher.tv.alerts.AlertSettings
import com.launcher.tv.player.Watch
import com.launcher.tv.service.HomeButtonService
import com.launcher.tv.system.SleepTimer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** Channels found for one game. */
data class ChannelLookup(
    val loading: Boolean = false,
    val candidates: List<ChannelCandidate> = emptyList(),
    val error: String? = null,
    val at: Long = 0,
)

data class HomeUiState(
    val data: HomeData = HomeData(),
    val apps: List<AppEntry> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val refreshing: Boolean = false,
    val trakt: TraktState = TraktState.SignedOut,
    val message: String? = null,
    /** IPTV (Xtream) login saved. */
    val iptv: Boolean = false,
    val iptvStatus: String? = null,
    val channels: Map<String, ChannelLookup> = emptyMap(),
    /** Game whose channel picker is open. */
    val picker: SportsEvent? = null,
    /** "Finding The Bear…" while a Play/Watch is being resolved. */
    val busy: String? = null,
    val alerts: Boolean = true,
    val scoringAlerts: Boolean = true,
    val sleepMinutes: Int = 0,
    /** The HomeBase accessibility service is on (Home button, alerts, sleep timer). */
    val serviceRunning: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val traktClient = TraktClient(app)
    private val repo = HomeRepository(app, traktClient)
    val apps = AppsRepository(app)
    private val history = WatchHistory.get(app)
    private val iptv = XtreamClient.get(app)
    private val finder = ChannelFinder(app, iptv)

    private val _state = MutableStateFlow(
        HomeUiState(
            trakt = currentTraktState(),
            iptv = iptv.isConfigured,
            alerts = AlertSettings.enabled(app),
            scoringAlerts = AlertSettings.scoring(app),
        )
    )
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
        // Download the provider's catalogues in the background so Play is instant later.
        viewModelScope.launch { iptv.prefetch() }
    }

    // ---------------- IPTV ----------------

    fun saveXtream(server: String, username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Checking your provider login…") }
            try {
                val cfg = XtreamClient.normalize(server, username, password)
                val account = iptv.account(cfg)
                iptv.save(cfg)
                iptv.account() // stores the server time zone for catch-up
                val expires = account.expiresMillis?.let {
                    " · expires " + DateTimeFormatter.ofPattern("MMM d, yyyy").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                }.orEmpty()
                val status = "${account.status}$expires"
                _state.update { it.copy(iptv = true, iptvStatus = status, channels = emptyMap(), message = "Provider connected: $status. Loading channels…") }
                iptv.prefetch()
                _state.update { it.copy(message = "Channels and catalogue ready") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Couldn't connect: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun removeXtream() {
        iptv.clear()
        _state.update { it.copy(iptv = false, iptvStatus = null, channels = emptyMap()) }
    }

    fun loadIptvStatus() {
        if (!iptv.isConfigured) return
        viewModelScope.launch {
            val status = runCatching { iptv.account() }.fold(
                onSuccess = { a ->
                    a.status + (a.expiresMillis?.let {
                        " · expires " + DateTimeFormatter.ofPattern("MMM d, yyyy").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                    } ?: "")
                },
                onFailure = { "Couldn't reach provider: ${it.message}" },
            )
            _state.update { it.copy(iptvStatus = status) }
        }
    }

    /** Finds channels for the selected game so the hero can show "on US: CBS Boston". */
    fun resolveChannels(event: SportsEvent) {
        if (!iptv.isConfigured) return
        val existing = _state.value.channels[event.id]
        if (existing != null && (existing.loading || System.currentTimeMillis() - existing.at < 10 * 60_000L)) return
        _state.update { it.copy(channels = it.channels + (event.id to ChannelLookup(loading = true, at = System.currentTimeMillis()))) }
        viewModelScope.launch {
            val lookup = try {
                ChannelLookup(candidates = finder.find(event), at = System.currentTimeMillis())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ChannelLookup(error = e.message, at = System.currentTimeMillis())
            }
            _state.update { it.copy(channels = it.channels + (event.id to lookup)) }
        }
    }

    fun watchGame(event: SportsEvent, choice: ChannelCandidate? = null, startOver: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Tuning to ${event.title}…", picker = null) }
            val known = _state.value.channels[event.id]?.candidates
            val result = Watch.game(getApplication(), event, choice, startOver, interactive = true, known = known)
            _state.update { s ->
                when (result) {
                    is Watch.Result.Choose -> s.copy(
                        busy = null, picker = event,
                        channels = s.channels + (event.id to ChannelLookup(candidates = result.candidates, at = System.currentTimeMillis())),
                    )
                    is Watch.Result.OpenedTiviMate -> s.copy(busy = null, message = result.why)
                    is Watch.Result.Playing -> s.copy(busy = null)
                }
            }
        }
    }

    fun openPicker(event: SportsEvent) {
        _state.update { it.copy(picker = event) }
        resolveChannels(event)
    }

    fun closePicker() = _state.update { it.copy(picker = null) }

    fun playMedia(item: MediaItem) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Finding ${item.title}…") }
            val result = Watch.media(getApplication(), item)
            _state.update { it.copy(busy = null, message = (result as? Watch.Result.OpenedTiviMate)?.why) }
        }
    }

    // ---------------- alerts ----------------

    fun setAlerts(on: Boolean) {
        AlertSettings.setEnabled(getApplication(), on)
        _state.update { it.copy(alerts = on) }
    }

    fun setScoringAlerts(on: Boolean) {
        AlertSettings.setScoring(getApplication(), on)
        _state.update { it.copy(scoringAlerts = on) }
    }

    fun removeFromHistory(entry: HistoryEntry) {
        viewModelScope.launch { history.remove(entry.url) }
    }

    fun onResume() {
        refresh(force = false)
        reloadApps()
        refreshSystemState()
    }

    fun refreshSystemState() {
        _state.update {
            it.copy(
                sleepMinutes = SleepTimer.minutesLeft(getApplication()),
                serviceRunning = HomeButtonService.running,
            )
        }
    }

    /** Off -> 30 -> 60 -> 90 -> 120 -> Off. */
    fun cycleSleepTimer() {
        val app = getApplication<Application>()
        val next = SleepTimer.nextStep(SleepTimer.minutesLeft(app))
        SleepTimer.set(app, next)
        val msg = when {
            next == 0 -> "Sleep timer off"
            !HomeButtonService.running -> "Sleep timer set, but it needs the HomeBase Home button service switched on (see README)"
            else -> "Going to sleep in $next minutes"
        }
        _state.update { it.copy(sleepMinutes = next, serviceRunning = HomeButtonService.running, message = msg) }
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
