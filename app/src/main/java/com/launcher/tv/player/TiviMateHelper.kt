package com.launcher.tv.player

import android.content.Context
import android.widget.Toast
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
