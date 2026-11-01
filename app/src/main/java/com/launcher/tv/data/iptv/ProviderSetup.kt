package com.launcher.tv.data.iptv

import android.content.Context

/** Adding providers, shared by the TV settings and the phone page. */
object ProviderSetup {
    /** Checks the login, saves the provider and returns a status line ("Active"). Throws with a readable message. */
    suspend fun addXtream(context: Context, server: String, username: String, password: String): String {
        val cfg = XtreamClient.normalize(server, username, password)
        val account = XtreamClient.account(cfg)
        IptvLibrary.get(context).add(
            SourceConfig.Xtream(IptvLibrary.newId(), IptvLibrary.hostOf(cfg.server), cfg.server, cfg.username, cfg.password)
        )
        return account.status
    }

    /** Downloads and checks the playlist, then saves it. get.php links are added as Xtream. */
    suspend fun addM3u(context: Context, url: String, epgUrl: String?): String {
        if (XtreamClient.isXtreamM3uLink(url)) return "Added as Xtream: " + addXtream(context, url, "", "")
        val trimmed = url.trim().let { if (it.contains("://")) it else "http://$it" }
        val config = SourceConfig.M3u(IptvLibrary.newId(), IptvLibrary.hostOf(trimmed), trimmed, epgUrl?.trim()?.ifBlank { null })
        val status = IptvLibrary.create(context, config).status()
        IptvLibrary.get(context).add(config)
        return status
    }
}
