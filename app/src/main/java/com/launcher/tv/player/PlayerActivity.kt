package com.launcher.tv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import com.launcher.tv.data.history.HistoryEntry
import com.launcher.tv.data.history.WatchHistory
import com.launcher.tv.data.trakt.TraktClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The "player bridge". Set HomeBase as TiviMate's external player and every movie/episode you
 * play from TiviMate comes through here:
 *  - it plays the stream with ExoPlayer,
 *  - remembers it in the "Recently watched in TiviMate" row with a resume point,
 *  - matches it to Trakt and scrobbles it, so Trakt's history / Next Up stay current.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private val history by lazy { WatchHistory.get(this) }
    private val scrobbler by lazy { Scrobbler(TraktClient(this)) }
    private val appScope get() = (application as LauncherApp).appScope

    private lateinit var url: String
    private var rawTitle: String? = null
    private var headers: Map<String, String> = emptyMap()
    private lateinit var parsed: TitleParser.Parsed
    private var match: Scrobbler.Match? = null
    private var scrobbling = false
    private var ended = false
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val data = intent.data ?: return finishWithError("No video to play")
        url = data.toString()
        rawTitle = readTitle(intent)
        headers = readHeaders(intent)
        parsed = TitleParser.parse(rawTitle, url)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { httpFactory.setUserAgent(it.value) }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .build()
        playerView = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            keepScreenOn = true
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
        }
        setContentView(playerView)
        playerView.requestFocus()

        player.setMediaItem(MediaItem.fromUri(data))
        startPosition()?.let { player.seekTo(it) }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = true

        lifecycleScope.launch {
            if (isVod()) saveHistory(moveToFront = true)
            match = scrobbler.identify(parsed)
            match?.let { m ->
                history.update(url) {
                    it.copy(title = m.displayTitle, subtitle = m.subtitle, imageUrl = m.imageUrl ?: it.imageUrl, posterUrl = m.posterUrl ?: it.posterUrl)
                }
                if (player.isPlaying) startScrobble()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startScrobble()
                progressJob?.cancel()
                progressJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(30_000)
                        if (isVod()) saveHistory(moveToFront = false)
                    }
                }
            } else {
                progressJob?.cancel()
                if (!ended && player.playbackState != Player.STATE_ENDED) pauseScrobble()
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

    override fun onStop() {
        super.onStop()
        if (!::player.isInitialized) return
        if (!ended) {
            stopScrobble(progressPercent())
            // ExoPlayer may only be touched on the main thread: read position now, save later.
            val pos = player.currentPosition
            val dur = duration()
            if (isVod()) appScope.launch { saveHistory(moveToFront = false, positionMs = pos, durationMs = dur) }
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
        super.onDestroy()
    }

    // ---------- Trakt ----------

    private fun startScrobble() {
        val m = match ?: return
        if (scrobbling) return
        scrobbling = true
        val p = progressPercent()
        appScope.launch { scrobbler.start(m, p) }
    }

    private fun pauseScrobble() {
        val m = match ?: return
        if (!scrobbling) return
        scrobbling = false
        val p = progressPercent()
        appScope.launch { scrobbler.pause(m, p) }
    }

    private fun stopScrobble(progress: Float) {
        val m = match ?: return
        scrobbling = false
        appScope.launch { scrobbler.stop(m, progress) }
    }

    // ---------- History / resume ----------

    private fun isVod(): Boolean =
        parsed.kind != TitleParser.Kind.LIVE && ((::player.isInitialized && duration() > 0) || parsed.canScrobble)

    private fun duration(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun progressPercent(): Float {
        val d = duration()
        return if (d > 0) player.currentPosition * 100f / d else 0f
    }

    private suspend fun saveHistory(
        moveToFront: Boolean,
        positionMs: Long = player.currentPosition,
        durationMs: Long = duration(),
    ) {
        val previous = history.get(url)
        val entry = HistoryEntry(
            url = url,
            rawTitle = rawTitle,
            title = match?.displayTitle ?: previous?.title ?: parsed.name.ifBlank { rawTitle ?: "Video" },
            subtitle = match?.subtitle ?: previous?.subtitle ?: listOfNotNull(
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
        )
        if (moveToFront || previous == null) history.upsert(entry) else history.update(url) { entry }
    }

    /** Resume point: the caller's ("position" extra, MX Player convention) or our own history. */
    private fun startPosition(): Long? {
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
        /** Play something from HomeBase's own history row. */
        fun intent(context: Context, entry: HistoryEntry): Intent =
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.parse(entry.url))
                .putExtra("title", entry.rawTitle ?: entry.title)
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
