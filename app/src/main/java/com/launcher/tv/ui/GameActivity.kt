package com.launcher.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.launcher.tv.data.Http
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.ChannelFinder
import com.launcher.tv.data.iptv.IptvLibrary
import com.launcher.tv.data.sports.GameDetail
import com.launcher.tv.data.sports.GameDetails
import com.launcher.tv.player.Highlights
import com.launcher.tv.player.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full page for one game, fight night or race weekend. Refreshes every minute while live. */
class GameActivity : ComponentActivity() {
    private var detail by mutableStateOf<GameDetail?>(null)
    private var loading by mutableStateOf(true)
    private var picker by mutableStateOf<ChannelLookup?>(null)
    private var hidden by mutableStateOf(false)
    private var check by mutableStateOf<StreamCheckState?>(null)
    private var checkJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val event = intent.getStringExtra(EXTRA_EVENT)
            ?.let { runCatching { Http.json.decodeFromString(SportsEvent.serializer(), it) }.getOrNull() }
            ?: return finish()

        hidden = com.launcher.tv.data.Spoilers.hides(this, event)
        lifecycleScope.launch {
            while (true) {
                runCatching { GameDetails.load(event) }.getOrNull()?.let { detail = it }
                loading = false
                if (!event.live) break
                delay(60_000)
            }
        }

        val library = IptvLibrary.get(this)
        val actions = GameActions(
            watch = { watch(event, null, false) },
            startOver = { watch(event, null, true) },
            channels = {
                picker = ChannelLookup(loading = true)
                lifecycleScope.launch {
                    picker = runCatching { ChannelLookup(candidates = ChannelFinder(this@GameActivity, library).find(event)) }
                        .getOrElse { ChannelLookup(error = it.message) }
                }
            },
            highlights = { lifecycleScope.launch { Highlights.play(this@GameActivity, event, detail)?.let(::toast) } },
            revealScore = {
                com.launcher.tv.data.Spoilers.reveal(this, event.id)
                hidden = false
            },
            compare = {
                checkJob?.cancel()
                check = StreamCheckState(event)
                checkJob = lifecycleScope.launch {
                    runCatching {
                        com.launcher.tv.data.iptv.ProviderCheck(this@GameActivity).run(event, null) { rows, running ->
                            check = check?.copy(rows = rows, running = running)
                        }
                    }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) check = check?.copy(running = false, error = e.message) }
                }
            },
            iptv = library.isConfigured,
        )

        setContent {
            HomeBaseTheme {
                GamePageLayout(event, detail, loading, actions, hideScores = hidden)
                check?.let { c ->
                    StreamCheckDialog(
                        c,
                        onWatch = { row -> checkJob?.cancel(); check = null; watch(event, row.candidate, false) },
                        onTiviMate = { row ->
                            checkJob?.cancel(); check = null
                            com.launcher.tv.player.TiviMateHelper.openOrTune(this, event, row.candidate.channel, row.provider)
                        },
                        onDismiss = { checkJob?.cancel(); check = null },
                    )
                }
                picker?.let { lookup ->
                    ChannelPicker(event, lookup, onPick = { c -> picker = null; watch(event, c, false) }, onDismiss = { picker = null })
                }
            }
        }
    }

    private fun watch(event: SportsEvent, choice: ChannelCandidate?, startOver: Boolean) {
        lifecycleScope.launch {
            when (val r = Watch.game(this@GameActivity, event, choice, startOver, interactive = true)) {
                is Watch.Result.Choose -> picker = ChannelLookup(candidates = r.candidates)
                is Watch.Result.OpenedTiviMate -> r.why?.let(::toast)
                is Watch.Result.Playing -> Unit
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_EVENT = "event"

        fun intent(context: Context, event: SportsEvent): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_EVENT, Http.json.encodeToString(SportsEvent.serializer(), event))
    }
}
