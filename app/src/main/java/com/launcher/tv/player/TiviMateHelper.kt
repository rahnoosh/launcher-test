package com.launcher.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.launcher.tv.LauncherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.launcher.tv.alerts.OverlayBus
import com.launcher.tv.alerts.OverlayCard
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.iptv.IptvChannel
import com.launcher.tv.service.HomeButtonService

/**
 * TiviMate has no way for other apps to open a specific channel, so this opens TiviMate and keeps a
 * card on screen saying exactly which playlist, group and channel to pick.
 */
object TiviMateHelper {
    /**
     * With one-press tuning on (experimental) and a channel number known, opens TiviMate and types the
     * number for you; otherwise (or if that fails) opens TiviMate with the "pick this channel" card.
     */
    fun openOrTune(context: Context, event: SportsEvent?, channel: IptvChannel, provider: String) {
        val number = channel.number
        if (!TiviMateTuner.enabled(context) || number == null || TiviMateTuner.command(number) == null) {
            open(context, event, channel, provider)
            return
        }
        val app = context.applicationContext
        (app as LauncherApp).appScope.launch {
            val result = TiviMateTuner.tune(app, number)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    OverlayBus.show(
                        OverlayCard(
                            kicker = "TIVIMATE · CHANNEL $number", title = channel.name,
                            detail = "Playlist: $provider" + (channel.group?.let { "  ·  $it" } ?: ""),
                            teams = listOfNotNull(event?.away, event?.home), badge = "TV",
                            hint = "Wrong channel? Check TiviMate's channel numbering (README)", onOk = null,
                        ),
                        6_000L,
                    )
                }.onFailure { e ->
                    Toast.makeText(app, "One-press tuning didn't work: " + TiviMateTuner.explain(e), Toast.LENGTH_LONG).show()
                    // Still get the user there: TiviMate plus the "pick this channel" card.
                    Handler(Looper.getMainLooper()).post { open(app, event, channel, provider) }
                }
            }
        }
    }

    fun open(context: Context, event: SportsEvent?, channel: IptvChannel, provider: String) {
        if (!AppsRepository(context).launch(AppsRepository.TIVIMATE)) {
            Toast.makeText(context, "TiviMate isn't installed", Toast.LENGTH_LONG).show()
            return
        }
        val detail = listOfNotNull(
            "Playlist: $provider",
            channel.group?.let { "Group: $it" },
            channel.number?.let { "No. $it" },
        ).joinToString("  ·  ")
        val card = OverlayCard(
            kicker = "PICK THIS CHANNEL IN TIVIMATE",
            title = channel.name,
            detail = detail,
            teams = listOfNotNull(event?.away, event?.home),
            badge = "TV",
            hint = event?.title?.let { "For $it  ·  this card closes by itself" } ?: "This card closes by itself",
            onOk = null, // keys go straight to TiviMate while it's up
        )
        if (!HomeButtonService.running || !OverlayBus.show(card, 30_000L)) {
            Toast.makeText(context, "In TiviMate: ${channel.name}\n$detail", Toast.LENGTH_LONG).show()
        }
    }
}
