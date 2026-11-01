package com.launcher.tv.data.iptv

/**
 * Parses M3U / M3U8 IPTV playlists (#EXTINF with tvg-* attributes), splitting live channels from
 * movies and episodes. Works line by line so very large playlists stream through.
 */
object M3uParser {
    data class Result(val epgUrl: String?, val channels: List<IptvChannel>, val vod: List<M3uVod>)

    private val attr = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val vodGroup = Regex("""(?i)\b(VOD|MOVIES?|FILMS?|SERIES|SERIE|TV SHOWS|SHOWS|CINEMA|PELICULAS)\b""")
    private val vodExt = Regex("""(?i)\.(mp4|mkv|avi|mov|wmv|m4v)(\?.*)?$""")

    fun parse(lines: Sequence<String>, sourceId: String): Result {
        var epgUrl: String? = null
        val channels = mutableListOf<IptvChannel>()
        val vod = mutableListOf<M3uVod>()
        var info: Map<String, String> = emptyMap()
        var name: String? = null
        var group: String? = null
        var userAgent: String? = null
        var index = 0

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attributes(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF") -> {
                    info = attributes(line)
                    name = displayName(line)
                    group = info["group-title"]
                }
                line.startsWith("#EXTGRP:") -> group = group ?: line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> userAgent = line.substringAfter("=").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val title = name?.takeIf { it.isNotBlank() } ?: info["tvg-name"] ?: url.substringAfterLast('/')
                    val path = url.substringBefore('?').lowercase()
                    val isVod = "/movie/" in path || "/series/" in path || vodExt.containsMatchIn(url) ||
                        (group?.let { vodGroup.containsMatchIn(it) } == true && "/live/" !in path)
                    if (isVod) {
                        vod += M3uVod(title, url, group, userAgent)
                    } else {
                        val catchup = info["catchup"] ?: info["catchup-type"]
                        val days = (info["catchup-days"] ?: info["tvg-rec"] ?: info["timeshift"])?.toIntOrNull() ?: 0
                        channels += IptvChannel(
                            sourceId = sourceId,
                            id = (index++).toString(),
                            name = title,
                            group = group,
                            epgId = info["tvg-id"]?.ifBlank { null },
                            logo = info["tvg-logo"]?.ifBlank { null },
                            archive = days > 0 || (catchup != null && catchup != "disabled"),
                            archiveDays = days.takeIf { it > 0 },
                            url = url,
                            catchup = catchup,
                            catchupSource = info["catchup-source"]?.ifBlank { null },
                            userAgent = userAgent,
                            number = info["tvg-chno"]?.ifBlank { null },
                        )
                    }
                    info = emptyMap(); name = null; group = null; userAgent = null
                }
            }
        }
        return Result(epgUrl, channels, vod)
    }

    private fun attributes(line: String): Map<String, String> =
        attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The title is after the first comma that isn't inside an attribute's quotes. */
    private fun displayName(line: String): String {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return line.substring(i + 1).trim()
            }
        }
        return ""
    }
}
