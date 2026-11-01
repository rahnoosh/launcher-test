package com.launcher.tv.phone

import android.content.Context
import android.content.Intent
import android.os.Build
import com.launcher.tv.BuildConfig
import com.launcher.tv.alerts.AlertSettings
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.ChangeBus
import com.launcher.tv.data.HomeRepository
import com.launcher.tv.data.Http
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.Spoilers
import com.launcher.tv.data.iptv.ChannelFinder
import com.launcher.tv.data.iptv.IptvChannel
import com.launcher.tv.data.iptv.IptvLibrary
import com.launcher.tv.data.iptv.Names
import com.launcher.tv.data.iptv.Playable
import com.launcher.tv.data.iptv.ProviderSetup
import com.launcher.tv.data.iptv.SourceConfig
import com.launcher.tv.data.iptv.XJson
import com.launcher.tv.data.sports.EventFollow
import com.launcher.tv.data.sports.FollowConfig
import com.launcher.tv.data.sports.FollowStore
import com.launcher.tv.data.sports.FollowedTeam
import com.launcher.tv.data.sports.Leagues
import com.launcher.tv.data.sports.SportsRepository
import com.launcher.tv.phone.PhoneServer.Request
import com.launcher.tv.phone.PhoneServer.Response
import com.launcher.tv.player.PlayerActivity
import com.launcher.tv.player.TiviMateHelper
import com.launcher.tv.player.TiviMateTuner
import com.launcher.tv.player.Watch
import com.launcher.tv.system.SleepTimer
import com.launcher.tv.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom

/**
 * The phone page's API. Everything except the page itself and /api/login needs the PIN cookie;
 * every POST needs the X-HomeBase header (which other websites can't send without permission).
 * Provider passwords and stream URLs are never sent back to the phone.
 */
class PhoneApi(context: Context, private val auth: PhoneAuth, private val page: () -> ByteArray) {
    private val app = context.applicationContext
    private val library get() = IptvLibrary.get(app)
    private val random = SecureRandom()

    /** Search results by opaque id, so stream URLs (with logins in them) stay on the box. */
    private val refs = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 400
    }

    fun handle(r: Request): Response {
        if (!hostAllowed(r.header("host"))) return Response.text(403, "Open the address shown on the TV")
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            return Response(200, "text/html; charset=utf-8", page(), mapOf("Content-Security-Policy" to CSP))
        }
        if (!r.path.startsWith("/api/")) return Response.text(404, "Not found")
        if (r.method == "POST" && r.header("x-homebase") != "1") return error(403, "Missing X-HomeBase header")
        if (r.path == "/api/login" && r.method == "POST") return login(r)
        if (!auth.valid(r.cookie("hb"))) return error(401, "Enter the PIN shown on the TV")
        return try {
            runBlocking(Dispatchers.IO) { withTimeout(120_000) { route(r) } }
        } catch (e: Exception) {
            error(500, e.message ?: "Something went wrong")
        }
    }

    private fun login(r: Request): Response {
        if (auth.isLocked(r.remoteIp)) return error(429, "Too many wrong PINs. Wait a minute and try again.")
        val pin = body(r)["pin"].string() ?: return error(400, "PIN missing")
        val token = auth.login(pin, r.remoteIp) ?: return error(401, "Wrong PIN")
        return Response.json(200, """{"ok":true}""", mapOf("Set-Cookie" to "hb=$token; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"))
    }

    private suspend fun route(r: Request): Response {
        val b = if (r.method == "POST") body(r) else JsonObject(emptyMap())
        return when (r.method to r.path) {
            "GET" to "/api/state" -> ok(state())
            "GET" to "/api/teams" -> {
                val league = Leagues.byKey(r.query["league"].orEmpty()) ?: return error(400, "Unknown league")
                val follow = FollowStore.load(app)
                val teams = SportsRepository.teams(league)
                ok(buildJsonObject {
                    putJsonArray("teams") { teams.forEach { add(teamJson(it, follow.isFollowed(it.league, it.id))) } }
                })
            }
            "POST" to "/api/teams/follow" -> {
                val team = Http.json.decodeFromJsonElement(FollowedTeam.serializer(), b["team"] ?: return error(400, "team missing"))
                FollowStore.setTeam(app, team, b["follow"].bool() ?: true)
                ok(state())
            }
            "POST" to "/api/follows" -> {
                val key = b["key"].string() ?: return error(400, "key missing")
                if (EventFollow.entries.none { it.key == key }) return error(400, "Unknown option")
                FollowStore.setEvent(app, key, b["on"].bool() ?: true)
                ok(state())
            }
            "POST" to "/api/games-per-team" -> {
                FollowStore.setGamesPerTeam(app, b["n"].int() ?: 2)
                ok(state())
            }
            "POST" to "/api/providers/xtream" -> {
                val status = ProviderSetup.addXtream(app, b["server"].string().orEmpty(), b["username"].string().orEmpty(), b["password"].string().orEmpty())
                message("Provider added ($status). The TV is loading its channels.")
            }
            "POST" to "/api/providers/m3u" -> {
                val status = ProviderSetup.addM3u(app, b["url"].string().orEmpty(), b["epg"].string())
                message("Playlist added: $status")
            }
            "POST" to "/api/providers/remove" -> {
                library.remove(b["id"].string() ?: return error(400, "id missing"))
                ok(state())
            }
            "POST" to "/api/settings" -> {
                val key = b["key"].string() ?: return error(400, "key missing")
                applySetting(key, b["value"])
                ChangeBus.emit(ChangeBus.SETTINGS)
                ok(state())
            }
            "POST" to "/api/sleep" -> {
                SleepTimer.set(app, (b["minutes"].int() ?: 0).coerceIn(0, 240))
                ChangeBus.emit(ChangeBus.SETTINGS)
                ok(state())
            }
            "GET" to "/api/search" -> ok(search(r.query["q"].orEmpty()))
            "POST" to "/api/play" -> play(b["ref"].string().orEmpty(), b["action"].string() ?: "watch")
            "GET" to "/api/update" -> ok(updateJson())
            "POST" to "/api/update/check" -> {
                UpdateManager.check(force = true)
                ok(updateJson())
            }
            "POST" to "/api/update/install" -> {
                val msg = withContext(Dispatchers.Main) { UpdateManager.install(app) }
                message(msg ?: "Look at the TV: Android is asking to install the update.")
            }
            "POST" to "/api/keys" -> {
                com.launcher.tv.data.ApiKeys.save(app, b["traktId"].string(), b["traktSecret"].string(), b["tmdb"].string())
                ok(state())
            }
            "GET" to "/api/backup" -> Response.json(
                200, Http.json.encodeToString(JsonObject.serializer(), backup(includeLogins = r.query["logins"] == "1")),
                mapOf("Content-Disposition" to "attachment; filename=\"homebase-backup.json\""),
            )
            "POST" to "/api/restore" -> {
                restore(b)
                message("Restored. The TV is reloading.")
            }
            else -> error(404, "Unknown request")
        }
    }

    // ---------------- state ----------------

    private fun state(): JsonObject {
        val follow = FollowStore.load(app)
        return buildJsonObject {
            put("tv", Build.MODEL)
            put("version", BuildConfig.VERSION_NAME)
            put("update", updateJson())
            putJsonArray("teams") { follow.teams.forEach { add(teamJson(it, true)) } }
            // Only whether keys are set, never the keys themselves.
            putJsonObject("keys") {
                put("trakt", com.launcher.tv.data.ApiKeys.hasTrakt(app))
                put("tmdb", com.launcher.tv.data.ApiKeys.hasTmdb(app))
            }
            putJsonArray("events") {
                EventFollow.entries.forEach { e -> addJsonObject { put("key", e.key); put("label", e.label); put("on", follow.follows(e)) } }
            }
            put("gamesPerTeam", follow.gamesPerTeam)
            putJsonArray("leagues") { Leagues.all.forEach { l -> addJsonObject { put("key", l.key); put("label", l.label) } } }
            putJsonArray("providers") {
                library.configs().forEach { c ->
                    addJsonObject { put("id", c.id); put("name", c.name); put("kind", if (c is SourceConfig.M3u) "M3U" else "Xtream") }
                }
            }
            putJsonObject("settings") {
                put("spoilers", Spoilers.enabled(app))
                put("alerts", AlertSettings.enabled(app))
                put("scoringAlerts", AlertSettings.scoring(app))
                put("tivimateTuning", TiviMateTuner.enabled(app))
                put("layout", layout())
                put("sleepMinutes", SleepTimer.minutesLeft(app))
            }
        }
    }

    private fun updateJson(): JsonObject {
        val u = UpdateManager.state.value
        return buildJsonObject {
            put("current", BuildConfig.VERSION_NAME)
            put("available", u.available?.versionName)
            put("notes", u.available?.notes)
            put("checking", u.checking)
            put("progress", u.progress)
            put("error", u.error)
        }
    }

    private fun teamJson(t: FollowedTeam, followed: Boolean) = buildJsonObject {
        put("league", t.league); put("id", t.id); put("name", t.name); put("abbr", t.abbr)
        put("color", t.color); put("altColor", t.altColor); put("logo", t.logo)
        put("leagueLabel", Leagues.byKey(t.league)?.label)
        put("followed", followed)
    }

    private fun layout() = app.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("layout", "rows") ?: "rows"

    private fun applySetting(key: String, value: JsonElement?) {
        when (key) {
            "spoilers" -> Spoilers.setEnabled(app, value.bool() ?: false)
            "alerts" -> AlertSettings.setEnabled(app, value.bool() ?: true)
            "scoringAlerts" -> AlertSettings.setScoring(app, value.bool() ?: true)
            "tivimateTuning" -> TiviMateTuner.setEnabled(app, value.bool() ?: false)
            "layout" -> app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                .putString("layout", if (value.string() == "tiles") "tiles" else "rows").apply()
            else -> throw IllegalArgumentException("Unknown setting $key")
        }
    }

    // ---------------- search & play ----------------

    private suspend fun search(raw: String): JsonObject {
        val q = raw.trim()
        if (q.length < 2) return buildJsonObject { putJsonArray("results") {} }
        val words = Names.tokens(q)
        fun matches(text: String): Boolean {
            val t = Names.tokens(text).joinToString(" ")
            return words.all { it in t }
        }
        val home = Spoilers.apply(app, HomeRepository.readCache(app))

        val games = (home.myTeams + home.nfl + home.fights + home.f1 + home.events + home.results).distinctBy { it.id }
            .filter { e -> matches(e.title) || listOfNotNull(e.home?.fullName, e.away?.fullName, e.league).any(::matches) }
            .take(10)
        val media = (home.upNext.map { it to "Next up" } + home.upcomingEpisodes.map { it to "New episode" } + home.watchlist.map { it to "Watchlist" })
            .filter { matches(it.first.title) }.distinctBy { it.first.id }.take(10)
        val channels = library.channels().filter { matches(it.name) }.sortedBy { it.name.length }.take(12)
        val vod = library.searchVod(q, 12)
        val providers = library.configs().associate { it.id to it.name }

        return buildJsonObject {
            putJsonArray("results") {
                games.forEach { e ->
                    addJsonObject {
                        put("ref", remember(e)); put("kind", "game"); put("title", e.title)
                        put("subtitle", listOfNotNull(e.league, gameWhen(e), e.broadcast).joinToString(" · "))
                    }
                }
                media.forEach { (m, label) ->
                    addJsonObject {
                        put("ref", remember(m)); put("kind", if (m.kind == MediaKind.MOVIE) "movie" else "show"); put("title", m.title)
                        put("subtitle", listOf(label, m.subtitle).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                }
                channels.forEach { c ->
                    addJsonObject {
                        put("ref", remember(c)); put("kind", "channel"); put("title", c.name)
                        put("subtitle", listOfNotNull(providers[c.sourceId], c.group, c.number?.let { "No. $it" }).joinToString(" · "))
                    }
                }
                vod.forEach { (name, p) ->
                    addJsonObject { put("ref", remember(p)); put("kind", "vod"); put("title", name); put("subtitle", "From your provider") }
                }
            }
        }
    }

    private fun gameWhen(e: SportsEvent) = when {
        e.live -> "Live"
        e.finished -> "Final"
        else -> java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a")
            .format(java.time.Instant.ofEpochMilli(e.startMillis).atZone(java.time.ZoneId.systemDefault()))
    }

    @Synchronized
    private fun remember(o: Any): String {
        val id = ByteArray(9).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        refs[id] = o
        return id
    }

    @Synchronized
    private fun lookup(ref: String): Any? = refs[ref]

    private suspend fun play(ref: String, action: String): Response {
        val o = lookup(ref) ?: return error(404, "That result expired. Search again.")
        val tivimate = action == "tivimate"
        val providers = library.configs().associate { it.id to it.name }
        val msg: String = withContext(Dispatchers.Main) {
            when (o) {
                is SportsEvent -> if (tivimate) {
                    val best = runCatching { ChannelFinder(app, library).find(o).firstOrNull() }.getOrNull()
                    if (best != null) {
                        TiviMateHelper.openOrTune(app, o, best.channel, providers[best.channel.sourceId] ?: "Provider")
                        "Opening TiviMate on ${best.channel.name}"
                    } else {
                        AppsRepository(app).launch(AppsRepository.TIVIMATE); "Opened TiviMate (no matching channel found)"
                    }
                } else when (val r = Watch.game(app, o, interactive = false)) {
                    is Watch.Result.Playing -> "Playing on the TV: ${r.what}"
                    is Watch.Result.OpenedTiviMate -> r.why ?: "Opened TiviMate"
                    is Watch.Result.Choose -> "Choose a channel on the TV"
                }
                is MediaItem -> if (tivimate) {
                    AppsRepository(app).launch(AppsRepository.TIVIMATE); "Opened TiviMate"
                } else when (val r = Watch.media(app, o)) {
                    is Watch.Result.Playing -> "Playing on the TV: ${r.what}"
                    is Watch.Result.OpenedTiviMate -> r.why ?: "Opened TiviMate"
                    is Watch.Result.Choose -> "Choose on the TV"
                }
                is IptvChannel -> if (tivimate) {
                    TiviMateHelper.openOrTune(app, null, o, providers[o.sourceId] ?: "Provider"); "Opening TiviMate on ${o.name}"
                } else {
                    val url = library.liveUrl(o) ?: return@withContext "Couldn't open ${o.name}"
                    app.startActivity(PlayerActivity.liveIntent(app, url, o.name, null, library.headers(o)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Playing ${o.name} on the TV"
                }
                is Playable -> if (tivimate) {
                    AppsRepository(app).launch(AppsRepository.TIVIMATE); "Opened TiviMate"
                } else {
                    val intent = PlayerActivity.movieIntent(app, o.url, o.title, null, null, null)
                    if (o.headers.isNotEmpty()) intent.putExtra("headers", o.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                    app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Playing ${o.title} on the TV"
                }
                else -> "Can't play that"
            }
        }
        return message(msg)
    }

    // ---------------- backup ----------------

    /** Provider logins are only included when asked for (the file is then as sensitive as the logins). */
    private fun backup(includeLogins: Boolean): JsonObject = buildJsonObject {
        put("homebase", 1)
        if (includeLogins) put("sources", XJson.encodeToJsonElement(ListSerializer(SourceConfig.serializer()), library.configs()))
        put("follows", Http.json.encodeToJsonElement(FollowConfig.serializer(), FollowStore.load(app)))
        putJsonObject("settings") {
            put("spoilers", Spoilers.enabled(app))
            put("alerts", AlertSettings.enabled(app))
            put("scoringAlerts", AlertSettings.scoring(app))
            put("tivimateTuning", TiviMateTuner.enabled(app))
            put("layout", layout())
        }
        putJsonObject("channelChoices") {
            app.getSharedPreferences("channel_choices", Context.MODE_PRIVATE).all.forEach { (k, v) -> if (v is String) put(k, v) }
        }
    }

    private fun restore(b: JsonObject) {
        require(b["homebase"].int() == 1) { "That isn't a HomeBase backup" }
        b["sources"]?.let { library.replaceAll(XJson.decodeFromJsonElement(ListSerializer(SourceConfig.serializer()), it)) }
        b["follows"]?.let { FollowStore.save(app, Http.json.decodeFromJsonElement(FollowConfig.serializer(), it)) }
        (b["settings"] as? JsonObject)?.forEach { (k, v) -> runCatching { applySetting(k, v) } }
        (b["channelChoices"] as? JsonObject)?.let { choices ->
            app.getSharedPreferences("channel_choices", Context.MODE_PRIVATE).edit().apply {
                choices.forEach { (k, v) -> v.string()?.let { putString(k, it) } }
            }.apply()
        }
        ChangeBus.emit(ChangeBus.SETTINGS)
    }

    // ---------------- helpers ----------------

    private fun body(r: Request): JsonObject =
        runCatching { Http.json.parseToJsonElement(r.text).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun ok(o: JsonObject) = Response.json(200, Http.json.encodeToString(JsonObject.serializer(), o))
    private fun message(m: String) = Response.json(200, """{"ok":true,"message":${PhoneServer.quote(m)}}""")
    private fun error(status: Int, m: String) = Response.json(status, """{"error":${PhoneServer.quote(m)}}""")

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    companion object {
        private const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src https: data:; " +
            "connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"

        /** Only answer to the box's own address (an IP or .local name), which blocks DNS-rebinding tricks. */
        fun hostAllowed(host: String?): Boolean {
            val h = host?.trim()?.lowercase() ?: return false
            if (h.startsWith("[")) return true // IPv6 literal
            val name = h.substringBefore(':')
            return name == "localhost" || name.endsWith(".local") ||
                Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(name)
        }
    }
}
