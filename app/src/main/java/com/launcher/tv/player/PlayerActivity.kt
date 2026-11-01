package com.launcher.tv.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.launcher.tv.LauncherApp
import com.launcher.tv.alerts.NowPlaying
import com.launcher.tv.data.history.HistoryEntry
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.history.WatchHistory
import com.launcher.tv.data.simkl.SimklClient
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The "player bridge". Set HomeBase as TiviMate's external player and every movie/episode you
 * play from TiviMate comes through here:
 *  - it plays the stream with ExoPlayer,
 *  - remembers it in the "Recently watched in TiviMate" row with a resume point,
 *  - works out what it is and reports it to Trakt and Simkl, so their history / Next Up stay current.
 * It also plays what HomeBase starts (games, channels, replays, next episodes), and those can shrink
 * into a picture-in-picture window while you browse.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private val history by lazy { WatchHistory.get(this) }
    private val scrobbler by lazy { Scrobbler(TraktClient(this), SimklClient(this)) }
    private val appScope get() = (application as LauncherApp).appScope

    private lateinit var url: String
    private var rawTitle: String? = null
    private var headers: Map<String, String> = emptyMap()
    private lateinit var parsed: TitleParser.Parsed
    private var match: Scrobbler.Match? = null
    private var scrobbling = false
    private var ended = false
    private var progressJob: Job? = null
    private var identifyJob: Job? = null
    /** Live TV started by HomeBase: no history, no scrobbling. */
    private var forceLive = false
    /** A full-game replay: kept in history (resumable) but never reported to Trakt or Simkl. */
    private var replay = false
    /** Started from HomeBase (not TiviMate's external-player call): may shrink into a window. */
    private var fromHomeBase = false
    private var eventId: String? = null
    private var resumedNow = false

    /** Back (with the controls hidden) shrinks a game into the window instead of closing it. */
    private val backToWindow = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!enterWindow()) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
        }
        setContentView(playerView)
        playerView.requestFocus()
        onBackPressedDispatcher.addCallback(this, backToWindow)
        start(intent)
    }

    /** singleTask: a new "play this" reaches the running player, e.g. from a live alert or while in the window. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Full screen" from the home screen: Android has already brought the window back.
        if (intent.getBooleanExtra(EXTRA_FULL_SCREEN, false) || intent.data == null) return
        endPlayback()
        setIntent(intent)
        start(intent)
    }

    /** Sets up and starts playback of [intent]'s stream. */
    private fun start(intent: Intent) {
        val data = intent.data ?: return finishWithError("No video to play")
        url = data.toString()
        rawTitle = readTitle(intent)
        headers = readHeaders(intent)
        parsed = TitleParser.parse(rawTitle, url)
        forceLive = intent.getBooleanExtra(EXTRA_LIVE, false)
        replay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        fromHomeBase = intent.getBooleanExtra(EXTRA_FROM_HOMEBASE, false)
        eventId = intent.getStringExtra(EXTRA_EVENT)
        match = presetMatch(intent)
        scrobbling = false
        ended = false
        backToWindow.isEnabled = canWindow() && (forceLive || replay)
        if (resumedNow || isInPictureInPictureMode) NowPlaying.eventId = eventId

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { httpFactory.setUserAgent(it.value) }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        playerView.player = player

        player.setMediaItem(MediaItem.fromUri(data))
        startPosition(intent)?.let { player.seekTo(it) }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = true
        if (isInPictureInPictureMode) PipWindow.set(displayTitle())

        identifyJob = lifecycleScope.launch {
            if (isVod()) store(entry(player.currentPosition, duration()), moveToFront = true)
            if (forceLive || replay) return@launch
            if (match == null && scrobbler.enabled) match = scrobbler.identify(parsed)
            match?.let { m ->
                val key = url
                history.update(key) {
                    it.copy(title = m.displayTitle, subtitle = m.subtitle, imageUrl = m.imageUrl ?: it.imageUrl, posterUrl = m.posterUrl ?: it.posterUrl)
                }
                if (player.isPlaying) startScrobble()
                if (isInPictureInPictureMode) PipWindow.set(displayTitle())
            }
        }
    }

    /** Saves where you got to, tells Trakt/Simkl, and releases the player (before switching streams or closing). */
    private fun endPlayback() {
        if (!::player.isInitialized) return
        progressJob?.cancel()
        identifyJob?.cancel()
        if (!ended) {
            stopScrobble(progressPercent())
            // ExoPlayer may only be touched on the main thread: build the entry now, save it later.
            if (isVod()) {
                val e = entry(player.currentPosition, duration())
                appScope.launch { store(e, moveToFront = false) }
            }
        }
        player.removeListener(listener)
        player.release()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startScrobble()
                progressJob?.cancel()
                progressJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(30_000)
                        if (isVod()) store(entry(player.currentPosition, duration()), moveToFront = false)
                    }
                }
            } else {
                progressJob?.cancel()
                // Buffering also stops "playing"; only a real pause is reported.
                if (!ended && player.playbackState != Player.STATE_ENDED && !player.playWhenReady) pauseScrobble()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ended = true
                stopScrobble(100f)
                setPlayerResult()
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "Can't play this stream (${error.errorCodeName}). Try TiviMate's own player.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the controller handle D-pad / media keys; Back closes the controls first, then the player.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && playerView.isControllerFullyVisible) {
            playerView.hideController()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        NowPlaying.eventId = eventId
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
    }

    override fun onPause() {
        resumedNow = false
        super.onPause()
    }

    /** The system's own Home (when HomeBase's Home button service is off): keep it playing in a window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (canWindow()) enterWindow()
    }

    override fun onStop() {
        super.onStop()
        // In a window the player stays started; this is the window being closed, or a real exit.
        if (NowPlaying.eventId == eventId) NowPlaying.eventId = null
        if (!::player.isInitialized) return
        if (!ended) {
            stopScrobble(progressPercent())
            if (isVod()) {
                val e = entry(player.currentPosition, duration())
                appScope.launch { store(e, moveToFront = false) }
            }
            setPlayerResult()
        }
        player.pause()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            player.removeListener(listener)
            player.release()
        }
        if (current?.get() === this) {
            current = null
            PipWindow.set(null)
        }
        super.onDestroy()
    }

    // ---------- Picture-in-picture ----------

    /** Only for what HomeBase started; TiviMate's player bridge returns to TiviMate as before. */
    private fun canWindow(): Boolean =
        fromHomeBase && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterWindow(): Boolean {
        if (isInPictureInPictureMode) return true
        if (!canWindow() || !::player.isInitialized) return false
        playerView.hideController()
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        // Android only accepts ratios between 1:2.39 and 2.39:1.
        val r = ratio.toFloat()
        val safe = when {
            r > 2.39f -> Rational(239, 100)
            r < 1 / 2.39f -> Rational(100, 239)
            else -> ratio
        }
        return try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(safe).build())
        } catch (e: IllegalStateException) {
            false
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) playerView.hideController()
        PipWindow.set(if (isInPictureInPictureMode) displayTitle() else null)
    }

    private fun displayTitle(): String = match?.displayTitle ?: rawTitle ?: parsed.name.ifBlank { "Video" }

    // ---------- Trakt and Simkl ----------

    private fun startScrobble() {
        if (!scrobbler.enabled) return
        val m = match ?: return
        if (scrobbling) return
        scrobbling = true
        val p = progressPercent()
        appScope.launch { scrobbler.start(m, p) }
    }

    private fun pauseScrobble() {
        if (!scrobbler.enabled) return
        val m = match ?: return
        if (!scrobbling) return
        scrobbling = false
        val p = progressPercent()
        appScope.launch { scrobbler.pause(m, p) }
    }

    private fun stopScrobble(progress: Float) {
        if (!scrobbler.enabled) return
        val m = match ?: return
        scrobbling = false
        appScope.launch { scrobbler.stop(m, progress) }
    }

    // ---------- History / resume ----------

    private fun isVod(): Boolean =
        !forceLive && (replay || parsed.kind != TitleParser.Kind.LIVE && ((::player.isInitialized && duration() > 0) || parsed.canScrobble))

    private fun duration(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun progressPercent(): Float {
        val d = duration()
        return if (d > 0) player.currentPosition * 100f / d else 0f
    }

    /** The history entry for where playback is now (read on the main thread, saved later). */
    private fun entry(positionMs: Long, durationMs: Long): HistoryEntry {
        val previous = history.get(url)
        return HistoryEntry(
            url = url,
            rawTitle = rawTitle,
            title = match?.displayTitle ?: previous?.title ?: (if (replay) rawTitle else null) ?: parsed.name.ifBlank { rawTitle ?: "Video" },
            subtitle = match?.subtitle ?: previous?.subtitle ?: if (replay) "Full game" else listOfNotNull(
                parsed.season?.let { s -> parsed.episode?.let { e -> "S%02dE%02d".format(s, e) } },
                parsed.year?.toString(),
            ).firstOrNull().orEmpty(),
            isEpisode = parsed.kind == TitleParser.Kind.EPISODE,
            imageUrl = match?.imageUrl ?: previous?.imageUrl,
            posterUrl = match?.posterUrl ?: previous?.posterUrl,
            positionMs = positionMs,
            durationMs = durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
            lastPlayedAt = System.currentTimeMillis(),
            headers = headers,
            replay = replay,
        )
    }

    private suspend fun store(entry: HistoryEntry, moveToFront: Boolean) {
        if (moveToFront || history.get(entry.url) == null) history.upsert(entry) else history.update(entry.url) { entry }
    }

    /** Resume point: the caller's ("position" extra, MX Player convention) or our own history. */
    private fun startPosition(intent: Intent): Long? {
        val fromCaller = when (val p = intent.extras?.get("position")) {
            is Int -> p.toLong()
            is Long -> p
            else -> null
        }?.takeIf { it > 0 }
        if (fromCaller != null) return fromCaller
        val saved = history.get(url) ?: return null
        if (saved.positionMs < 30_000 || saved.finished) return null
        Toast.makeText(this, "Resuming from ${formatTime(saved.positionMs)}", Toast.LENGTH_SHORT).show()
        return saved.positionMs
    }

    /** MX Player-style result, so TiviMate can keep its own resume point too. */
    private fun setPlayerResult() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("position", player.currentPosition.toInt())
                .putExtra("duration", duration().toInt())
                .putExtra("end_by", if (ended) "playback_completion" else "user"),
        )
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_LIVE = "homebase.live"
        const val EXTRA_EVENT = "homebase.event_id"
        private const val EXTRA_REPLAY = "homebase.replay"
        private const val EXTRA_FROM_HOMEBASE = "homebase.from_homebase"
        private const val EXTRA_FULL_SCREEN = "homebase.full_screen"

        @Volatile private var current: WeakReference<PlayerActivity>? = null

        /** Home pressed while HomeBase's player shows something HomeBase started: shrink it into a window. */
        fun shrinkToWindow(): Boolean {
            val a = current?.get() ?: return false
            return a.resumedNow && !a.isFinishing && a.canWindow() && a.enterWindow()
        }

        /** Back to full screen from the window. */
        fun fullScreen(context: Context) {
            if (current?.get() == null) {
                PipWindow.set(null)
                return
            }
            context.startActivity(
                Intent(context, PlayerActivity::class.java).putExtra(EXTRA_FULL_SCREEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /** Stop whatever is playing in the window. */
        fun stopWindow() {
            current?.get()?.finish()
            PipWindow.set(null)
        }
        private const val EXTRA_MOVIE = "homebase.movie"
        private const val EXTRA_TRAKT = "homebase.trakt"
        private const val EXTRA_SIMKL = "homebase.simkl"
        private const val EXTRA_TMDB = "homebase.tmdb"
        private const val EXTRA_IMDB = "homebase.imdb"
        private const val EXTRA_YEAR = "homebase.year"
        private const val EXTRA_ANIME = "homebase.anime"
        private const val EXTRA_SEASON = "homebase.season"
        private const val EXTRA_EPISODE = "homebase.episode"
        private const val EXTRA_SHOW_TITLE = "homebase.show_title"
        private const val EXTRA_IMAGE = "homebase.image"
        private const val EXTRA_POSTER = "homebase.poster"

        fun liveIntent(context: Context, url: String, title: String, eventId: String?, headers: Map<String, String> = emptyMap()): Intent =
            Intent(context, PlayerActivity::class.java).setData(Uri.parse(url))
                .putExtra("title", title).putExtra(EXTRA_LIVE, true).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_FROM_HOMEBASE, true)
                .apply { if (headers.isNotEmpty()) putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray()) }

        /** A next-up episode or watchlist movie found at your provider, with every id Trakt and Simkl need. */
        fun mediaIntent(context: Context, url: String, streamTitle: String, item: com.launcher.tv.data.MediaItem): Intent =
            Intent(context, PlayerActivity::class.java).setData(Uri.parse(url))
                .putExtra("title", streamTitle)
                .putExtra(EXTRA_FROM_HOMEBASE, true)
                .putExtra(EXTRA_SHOW_TITLE, item.title)
                .putExtra(EXTRA_MOVIE, item.kind == MediaKind.MOVIE)
                .putExtra(EXTRA_TRAKT, item.traktId ?: 0L)
                .putExtra(EXTRA_SIMKL, item.simklId ?: 0L)
                .putExtra(EXTRA_TMDB, item.tmdbId ?: 0L)
                .putExtra(EXTRA_IMDB, item.imdbId)
                .putExtra(EXTRA_YEAR, item.year ?: 0)
                .putExtra(EXTRA_ANIME, item.anime)
                .putExtra(EXTRA_SEASON, item.season ?: -1)
                .putExtra(EXTRA_EPISODE, item.episode ?: -1)
                .putExtra(EXTRA_IMAGE, item.imageUrl)
                .putExtra(EXTRA_POSTER, item.posterUrl)

        /** A film from your provider found by searching on the phone page (nothing to report it as). */
        fun movieIntent(context: Context, url: String, title: String, image: String?, poster: String?): Intent =
            Intent(context, PlayerActivity::class.java).setData(Uri.parse(url))
                .putExtra("title", title).putExtra(EXTRA_FROM_HOMEBASE, true)
                .putExtra(EXTRA_IMAGE, image).putExtra(EXTRA_POSTER, poster)

        private fun presetMatch(intent: Intent): Scrobbler.Match? {
            if (!intent.hasExtra(EXTRA_MOVIE)) return null
            val movie = intent.getBooleanExtra(EXTRA_MOVIE, false)
            val trakt = intent.getLongExtra(EXTRA_TRAKT, 0L).takeIf { it > 0 }
            val simkl = intent.getLongExtra(EXTRA_SIMKL, 0L).takeIf { it > 0 }
            val tmdb = intent.getLongExtra(EXTRA_TMDB, 0L).takeIf { it > 0 }
            val imdb = intent.getStringExtra(EXTRA_IMDB)?.takeIf { it.isNotBlank() }
            if (trakt == null && simkl == null && tmdb == null && imdb == null) return null
            val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
            val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
            if (!movie && episode == null) return null
            val title = intent.getStringExtra(EXTRA_SHOW_TITLE) ?: intent.getStringExtra("title").orEmpty()
            val year = intent.getIntExtra(EXTRA_YEAR, 0).takeIf { it > 0 }
            return Scrobbler.Match(
                movie = movie, title = title, year = year, season = season, episode = episode,
                traktId = trakt, simklId = simkl, tmdbId = tmdb, imdbId = imdb,
                anime = intent.getBooleanExtra(EXTRA_ANIME, false),
                subtitle = when {
                    movie -> year?.toString().orEmpty()
                    season != null -> "S%02dE%02d".format(season, episode)
                    else -> "Episode $episode"
                },
                imageUrl = intent.getStringExtra(EXTRA_IMAGE),
                posterUrl = intent.getStringExtra(EXTRA_POSTER),
            )
        }

        /** A finished game from your provider's recordings. */
        fun replayIntent(context: Context, url: String, title: String, headers: Map<String, String>): Intent =
            Intent(context, PlayerActivity::class.java).setData(Uri.parse(url))
                .putExtra("title", title).putExtra(EXTRA_REPLAY, true).putExtra(EXTRA_FROM_HOMEBASE, true)
                .apply { if (headers.isNotEmpty()) putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray()) }

        /** Play something from HomeBase's own history row. */
        fun intent(context: Context, entry: HistoryEntry): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(entry.url))
                .putExtra("title", entry.rawTitle ?: entry.title)
                .putExtra(EXTRA_REPLAY, entry.replay)
                .putExtra(EXTRA_FROM_HOMEBASE, true)
                .putExtra("headers", entry.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())

        private fun readTitle(intent: Intent): String? =
            listOf("title", Intent.EXTRA_TITLE, "name", "android.media.metadata.TITLE")
                .firstNotNullOfOrNull { intent.getStringExtra(it)?.takeIf(String::isNotBlank) }

        /** Headers as an alternating key/value String array (MX Player / VLC convention) or a Bundle. */
        private fun readHeaders(intent: Intent): Map<String, String> {
            intent.getStringArrayExtra("headers")?.let { arr ->
                return arr.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
            }
            intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")?.let { b ->
                return b.keySet().mapNotNull { k -> b.getString(k)?.let { k to it } }.toMap()
            }
            return emptyMap()
        }

        private fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
