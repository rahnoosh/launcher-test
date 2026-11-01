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
import com.launcher.tv.data.iptv.IptvLibrary
import com.launcher.tv.data.iptv.SourceConfig
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
import com.launcher.tv.data.simkl.SimklClient.Sync as SimklSync

sealed interface TraktState {
    data object NotConfigured : TraktState
    data object SignedOut : TraktState
    data class AwaitingCode(val userCode: String, val url: String) : TraktState
    data object SignedIn : TraktState
}

sealed interface SimklState {
    data object NotConfigured : SimklState
    data object SignedOut : SimklState
    /** [qrUrl] opens Simkl's approval page with the code already filled in. */
    data class AwaitingCode(val userCode: String, val url: String, val qrUrl: String) : SimklState
    data object SignedIn : SimklState
}

/** A provider in Settings. */
data class SourceRow(val id: String, val name: String, val kind: String, val status: String? = null)

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
    val simkl: SimklState = SimklState.SignedOut,
    val message: String? = null,
    /** At least one IPTV provider (Xtream or M3U) added. */
    val iptv: Boolean = false,
    val sources: List<SourceRow> = emptyList(),
    val channels: Map<String, ChannelLookup> = emptyMap(),
    /** Game whose channel picker is open. */
    val picker: SportsEvent? = null,
    /** "Finding The Bear…" while a Play/Watch is being resolved. */
    val busy: String? = null,
    val alerts: Boolean = true,
    val scoringAlerts: Boolean = true,
    val sleepMinutes: Int = 0,
    /** Which API keys are set (Trakt, TMDB). */
    val keys: Pair<Boolean, Boolean> = false to false,
    /** A Simkl client ID is set. */
    val simklKey: Boolean = false,
    /** Title of what's playing in the picture-in-picture window, if anything. */
    val pip: String? = null,
    /** How many teams are followed (Quick settings tile). */
    val teamsCount: Int = 0,
    val phone: PhoneInfo = PhoneInfo(enabled = true, running = false, address = null, loginLink = null, pin = ""),
    val update: com.launcher.tv.update.UpdateManager.State = com.launcher.tv.update.UpdateManager.State(),
    /** "rows" (streaming-app style, default) or "tiles" (console style). */
    val layout: String = "rows",
    /** Experimental: type the channel number into TiviMate over local ADB. */
    val tiviMateTuning: Boolean = false,
    /** "Best stream" dialog for an event. */
    val streamCheck: StreamCheckState? = null,
    /** Spoiler mode: hide scores until "Show score". */
    val spoilers: Boolean = false,
    val revealed: Set<String> = emptySet(),
    /** The HomeBase accessibility service is on (Home button, alerts, sleep timer). */
    val serviceRunning: Boolean = false,
) {
    /** Trakt or Simkl is connected, so the show and watchlist rows have something to show. */
    val tracking: Boolean get() = trakt == TraktState.SignedIn || simkl == SimklState.SignedIn
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val traktClient = TraktClient(app)
    private val simklClient = com.launcher.tv.data.simkl.SimklClient(app)
    private val repo = HomeRepository(app, traktClient, simklClient)
    val apps = AppsRepository(app)
    private val history = WatchHistory.get(app)
    private val library = IptvLibrary.get(app)
    private val finder = ChannelFinder(app, library)

    private val _state = MutableStateFlow(
        HomeUiState(
            trakt = currentTraktState(),
            simkl = currentSimklState(),
            iptv = library.isConfigured,
            alerts = AlertSettings.enabled(app),
            scoringAlerts = AlertSettings.scoring(app),
            spoilers = com.launcher.tv.data.Spoilers.enabled(app),
            tiviMateTuning = com.launcher.tv.player.TiviMateTuner.enabled(app),
            layout = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getString("layout", "rows") ?: "rows",
            revealed = com.launcher.tv.data.Spoilers.revealed(app),
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
            refresh(force = false, simkl = SimklSync.IF_DUE)
        }
        reloadApps()
        viewModelScope.launch {
            history.load()
            history.entries.collect { list -> _state.update { it.copy(history = list) } }
        }
        // Download the provider's catalogues in the background so Play is instant later.
        viewModelScope.launch { library.prefetch() }

        // Changes made on the Teams screen or the phone page.
        viewModelScope.launch {
            com.launcher.tv.data.ChangeBus.events.collect { topic ->
                when (topic) {
                    com.launcher.tv.data.ChangeBus.FOLLOWS -> scheduleFollowRefresh()
                    com.launcher.tv.data.ChangeBus.PROVIDERS -> {
                        _state.update { it.copy(iptv = library.isConfigured, sources = sourceRows(emptyMap()), channels = emptyMap()) }
                        launch { library.prefetch() }
                    }
                    com.launcher.tv.data.ChangeBus.SETTINGS -> reloadSettings()
                    com.launcher.tv.data.ApiKeys.CHANGED -> {
                        _state.update { it.copy(trakt = currentTraktState(), simkl = currentSimklState(), keys = keyStatus(), simklKey = simklClient.isConfigured) }
                        refresh(force = true, restart = true)
                    }
                }
            }
        }
        // The picture-in-picture window: shown as a Now Playing card.
        viewModelScope.launch {
            com.launcher.tv.player.PipWindow.title.collect { t -> _state.update { it.copy(pip = t) } }
        }
        // Self-update status and download progress.
        viewModelScope.launch {
            com.launcher.tv.update.UpdateManager.state.collect { u ->
                _state.update {
                    val downloading = u.progress?.let { p -> "Downloading update ${(p * 100).toInt()}%" }
                    it.copy(update = u, busy = downloading ?: it.busy?.takeUnless { b -> b.startsWith("Downloading update") })
                }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(20_000)
            com.launcher.tv.update.UpdateManager.check()
        }
        _state.update { it.copy(teamsCount = com.launcher.tv.data.sports.FollowStore.load(app).teams.size, keys = keyStatus(), simklKey = simklClient.isConfigured) }
        refreshPhone()
    }

    private fun keyStatus(): Pair<Boolean, Boolean> {
        val app = getApplication<Application>()
        return com.launcher.tv.data.ApiKeys.hasTrakt(app) to com.launcher.tv.data.ApiKeys.hasTmdb(app)
    }

    /** Keys typed on the TV; blank fields keep the current value. */
    fun saveKeys(traktId: String, traktSecret: String, tmdb: String, simklId: String = "") {
        com.launcher.tv.data.ApiKeys.save(getApplication(), traktId, traktSecret, tmdb, simklId)
        _state.update { it.copy(message = "Keys saved") }
    }

    private var followJob: Job? = null

    /** Many quick changes on the Teams screen become one refresh. */
    private fun scheduleFollowRefresh() {
        _state.update { it.copy(teamsCount = com.launcher.tv.data.sports.FollowStore.load(getApplication()).teams.size) }
        followJob?.cancel()
        followJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            refresh(force = true, restart = true)
        }
    }

    private fun reloadSettings() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                spoilers = com.launcher.tv.data.Spoilers.enabled(app),
                revealed = com.launcher.tv.data.Spoilers.revealed(app),
                alerts = AlertSettings.enabled(app),
                scoringAlerts = AlertSettings.scoring(app),
                tiviMateTuning = com.launcher.tv.player.TiviMateTuner.enabled(app),
                layout = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getString("layout", "rows") ?: "rows",
                sleepMinutes = SleepTimer.minutesLeft(app),
            )
        }
    }

    // ---------------- phone page & updates ----------------

    fun refreshPhone() {
        val app = getApplication<Application>()
        val p = com.launcher.tv.phone.PhoneLink
        _state.update {
            it.copy(phone = PhoneInfo(p.enabled(app), p.isRunning(), p.address(), p.loginLink(app), p.pin(app)))
        }
    }

    fun newPhonePin() {
        com.launcher.tv.phone.PhoneLink.newPin(getApplication())
        refreshPhone()
    }

    fun togglePhonePage() {
        val app = getApplication<Application>()
        com.launcher.tv.phone.PhoneLink.setEnabled(app, !com.launcher.tv.phone.PhoneLink.enabled(app))
        viewModelScope.launch {
            kotlinx.coroutines.delay(600) // the server starts on a background thread
            refreshPhone()
        }
    }

    /** Installs the waiting update, or checks for one. */
    fun updateAction() {
        viewModelScope.launch {
            val u = com.launcher.tv.update.UpdateManager
            if (u.state.value.available == null) {
                _state.update { it.copy(busy = "Checking for updates…") }
                val found = u.check(force = true)
                _state.update {
                    it.copy(
                        busy = null,
                        message = found?.let { f -> "Update ${f.versionName} is available. Choose Updates again to install it." }
                            ?: (u.state.value.error?.let { e -> "Couldn't check for updates: $e" } ?: "HomeBase is up to date (${u.currentVersion})"),
                    )
                }
            } else {
                val msg = u.install(getApplication())
                if (msg != null) _state.update { it.copy(message = msg) }
            }
        }
    }

    // ---------------- IPTV ----------------

    fun addXtream(server: String, username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Checking your provider login…") }
            try {
                val status = com.launcher.tv.data.iptv.ProviderSetup.addXtream(getApplication(), server, username, password)
                afterSourcesChanged("Provider connected ($status). Loading channels…")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Couldn't connect: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun addM3u(url: String, epgUrl: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Loading playlist…") }
            try {
                val status = com.launcher.tv.data.iptv.ProviderSetup.addM3u(getApplication(), url, epgUrl)
                afterSourcesChanged("Playlist added: $status")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(message = "Couldn't load that playlist: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun removeSource(id: String) {
        library.remove(id)
        _state.update { s -> s.copy(iptv = library.isConfigured, sources = sourceRows(s.sources.associate { it.id to it.status }), channels = emptyMap()) }
    }

    private suspend fun afterSourcesChanged(message: String) {
        _state.update { it.copy(iptv = true, sources = sourceRows(emptyMap()), channels = emptyMap(), message = message) }
        library.prefetch()
        loadIptvStatus()
    }

    private fun sourceRows(statuses: Map<String, String?>): List<SourceRow> = library.configs().map {
        SourceRow(it.id, it.name, if (it is SourceConfig.M3u) "M3U" else "Xtream", statuses[it.id])
    }

    fun loadIptvStatus() {
        _state.update { it.copy(sources = sourceRows(it.sources.associate { r -> r.id to r.status })) }
        for (source in library.sources) {
            viewModelScope.launch {
                val status = try {
                    source.status()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "Couldn't reach provider: ${e.message}"
                }
                _state.update { s -> s.copy(sources = s.sources.map { if (it.id == source.config.id) it.copy(status = status) else it }) }
            }
        }
    }

    /** Finds channels for the selected game so the hero can show "on US: CBS Boston". */
    fun resolveChannels(event: SportsEvent) {
        if (!library.isConfigured) return
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
            _state.update { it.copy(busy = if (startOver && event.finished) "Finding the recording of ${event.title}…" else "Tuning to ${event.title}…", picker = null) }
            val known = _state.value.channels[event.id]?.candidates
            val result = Watch.game(getApplication(), event, choice, startOver, interactive = true, known = known)
            _state.update { s ->
                when (result) {
                    is Watch.Result.Choose -> s.copy(
                        busy = null, picker = event,
                        channels = s.channels + (event.id to ChannelLookup(candidates = result.candidates, at = System.currentTimeMillis())),
                    )
                    is Watch.Result.OpenedTiviMate -> s.copy(busy = null, message = result.why)
                    is Watch.Result.Unavailable -> s.copy(busy = null, message = result.why)
                    is Watch.Result.Playing -> s.copy(busy = null)
                }
            }
        }
    }

    fun highlights(event: SportsEvent) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Finding highlights…") }
            val msg = com.launcher.tv.player.Highlights.play(getApplication(), event)
            _state.update { it.copy(busy = null, message = msg) }
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

    private var checkJob: Job? = null

    /** Tries each provider's stream for the event and ranks them. */
    fun compareProviders(event: SportsEvent) {
        if (!library.isConfigured) {
            _state.update { it.copy(message = "Add an IPTV provider first (Quick settings → HomeBase)") }
            return
        }
        checkJob?.cancel()
        _state.update { it.copy(streamCheck = StreamCheckState(event)) }
        checkJob = viewModelScope.launch {
            try {
                com.launcher.tv.data.iptv.ProviderCheck(getApplication()).run(event, _state.value.channels[event.id]?.candidates) { rows, running ->
                    _state.update { s -> if (s.streamCheck?.event?.id == event.id) s.copy(streamCheck = s.streamCheck.copy(rows = rows, running = running)) else s }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { s -> s.copy(streamCheck = s.streamCheck?.copy(running = false, error = "Couldn't check: ${e.message}")) }
            }
        }
    }

    fun closeStreamCheck() {
        checkJob?.cancel()
        _state.update { it.copy(streamCheck = null) }
    }

    fun watchFromCheck(row: com.launcher.tv.data.iptv.CheckRow) {
        val event = _state.value.streamCheck?.event ?: return
        closeStreamCheck()
        watchGame(event, row.candidate)
    }

    fun tiviMateFromCheck(row: com.launcher.tv.data.iptv.CheckRow) {
        val event = _state.value.streamCheck?.event
        closeStreamCheck()
        com.launcher.tv.player.TiviMateHelper.openOrTune(getApplication(), event, row.candidate.channel, row.provider)
    }

    fun setLayout(layout: String) {
        getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putString("layout", layout).apply()
        _state.update { it.copy(layout = layout) }
    }

    fun setTiviMateTuning(on: Boolean) {
        com.launcher.tv.player.TiviMateTuner.setEnabled(getApplication(), on)
        _state.update { it.copy(tiviMateTuning = on) }
        if (on) testTiviMateTuning()
    }

    /** Connects to the box's own ADB once; the TV shows "Allow debugging?" the first time. */
    fun testTiviMateTuning() {
        viewModelScope.launch {
            _state.update { it.copy(busy = "Connecting… accept 'Allow debugging' on the TV if asked") }
            val r = com.launcher.tv.player.TiviMateTuner.test(getApplication())
            _state.update {
                it.copy(
                    busy = null,
                    message = r.fold(
                        onSuccess = { "One-press tuning is ready" },
                        onFailure = { e -> "One-press tuning can't connect: " + com.launcher.tv.player.TiviMateTuner.explain(e) },
                    ),
                )
            }
        }
    }

    fun setSpoilers(on: Boolean) {
        com.launcher.tv.data.Spoilers.setEnabled(getApplication(), on)
        _state.update { it.copy(spoilers = on, message = if (on) "Spoiler mode on: scores are hidden until you choose Show score" else "Spoiler mode off") }
    }

    fun revealScore(event: SportsEvent) {
        com.launcher.tv.data.Spoilers.reveal(getApplication(), event.id)
        _state.update { it.copy(revealed = it.revealed + event.id) }
    }

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
        syncSimkl()
        reloadApps()
        refreshSystemState()
        refreshPhone()
        viewModelScope.launch { com.launcher.tv.update.UpdateManager.check() }
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

    /** [simkl]: whether this refresh may ask Simkl for changes; Simkl wants syncs tied to you using the TV. */
    fun refresh(force: Boolean, restart: Boolean = false, simkl: SimklSync = SimklSync.NONE) {
        if (refreshJob?.isActive == true) {
            if (!restart) return
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch {
            cacheReady.await() // never refresh on top of an empty state and lose cached sections
            if (!force && !isStale()) return@launch
            _state.update { it.copy(refreshing = true) }
            val data = repo.refresh(_state.value.data, simkl)
            _state.update { it.copy(data = data, refreshing = false, trakt = currentTraktState(), simkl = currentSimklState()) }
        }
    }

    /** The refresh you ask for: also checks Simkl straight away. */
    fun manualRefresh() = refresh(force = true, restart = true, simkl = SimklSync.NOW)

    /** On coming back to the home screen: a cheap Simkl check (at most every 20 minutes), rows rebuilt only if something changed. */
    private fun syncSimkl() {
        if (!simklClient.isConfigured || !simklClient.isSignedIn) return
        viewModelScope.launch {
            val changed = try {
                simklClient.sync(SimklSync.IF_DUE)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (changed) refresh(force = true, restart = true)
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
                // Simkl's cards stay (without their Trakt ids); Trakt-only ones go.
                data = it.data.copy(
                    upNext = it.data.upNext.withoutTrakt(),
                    watchlist = it.data.watchlist.withoutTrakt(),
                    upcomingEpisodes = it.data.upcomingEpisodes.withoutTrakt(),
                ),
            )
        }
    }

    private fun List<com.launcher.tv.data.MediaItem>.withoutTrakt() = filter { it.simklId != null }.map { it.copy(traktId = null) }

    private var simklAuthJob: Job? = null

    fun connectSimkl() {
        if (!simklClient.isConfigured || simklAuthJob?.isActive == true) return
        simklAuthJob = viewModelScope.launch {
            try {
                val code = simklClient.startDeviceAuth()
                _state.update { it.copy(simkl = SimklState.AwaitingCode(code.userCode, code.verificationUri, code.verificationUriComplete)) }
                val ok = simklClient.awaitDeviceToken(code)
                _state.update {
                    it.copy(simkl = currentSimklState(), message = if (ok) "Connected to Simkl" else "Simkl sign-in didn't complete")
                }
                if (ok) refresh(force = true, restart = true, simkl = SimklSync.NOW)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = if (e is com.launcher.tv.data.HttpException && e.code == 401) "check the Simkl client ID (it must be a new, V2 app)" else e.message
                _state.update { it.copy(simkl = currentSimklState(), message = "Couldn't connect to Simkl: $why") }
            }
        }
    }

    fun cancelSimklAuth() {
        simklAuthJob?.cancel()
        _state.update { it.copy(simkl = currentSimklState()) }
    }

    fun signOutSimkl() {
        viewModelScope.launch {
            simklClient.signOut()
            _state.update { it.copy(simkl = currentSimklState(), message = "Disconnected from Simkl") }
            refresh(force = true, restart = true)
        }
    }

    private fun currentSimklState(): SimklState = when {
        !simklClient.isConfigured -> SimklState.NotConfigured
        simklClient.isSignedIn -> SimklState.SignedIn
        else -> SimklState.SignedOut
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
