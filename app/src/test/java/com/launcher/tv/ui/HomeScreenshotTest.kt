package com.launcher.tv.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.launcher.tv.data.AppEntry
import com.launcher.tv.data.AppsRepository
import com.launcher.tv.data.HomeData
import com.launcher.tv.data.MediaItem
import com.launcher.tv.data.MediaKind
import com.launcher.tv.data.SportsEvent
import com.launcher.tv.data.TeamInfo
import com.launcher.tv.data.history.HistoryEntry
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import com.launcher.tv.alerts.AlertOverlay
import com.launcher.tv.dream.ScreensaverLayout
import com.launcher.tv.alerts.LiveAlert
import com.launcher.tv.alerts.OverlayCard
import com.launcher.tv.data.iptv.ChannelCandidate
import com.launcher.tv.data.iptv.IptvChannel
import com.launcher.tv.data.sports.GameDetail
import com.launcher.tv.data.sports.GameDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.launcher.tv.data.iptv.CheckRow
import com.launcher.tv.data.iptv.ProbeResult
import org.junit.Rule
import org.junit.Test

/**
 * Renders the real Compose home screen at 1920x1080 (TV, xhdpi) with sample data.
 * Run `./gradlew recordPaparazziDebug`; images land in app/src/test/snapshots/images.
 */
class HomeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920,
            screenHeight = 1080,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    // ---- Rows layout (default) ----
    @Test fun rowsHome() = rows(HomeTab.FOR_YOU, null)
    @Test fun rowsLiveGame() = rows(HomeTab.FOR_YOU, "g-espn-lal")
    @Test fun rowsSports() = rows(HomeTab.SPORTS, "g-espn-ne")
    @Test fun rowsRailOpen() = rows(HomeTab.FOR_YOU, null, railOpen = true)
    /** A game shrunk into the picture-in-picture window: Now Playing card on top. */
    @Test fun rowsNowPlaying() = rows(HomeTab.FOR_YOU, "now-playing", pip = "Patriots @ Bills · ESPN")

    private fun rows(tab: HomeTab, selected: String?, railOpen: Boolean = false, pip: String? = null) {
        val state = sampleState().copy(
            pip = pip,
            data = sampleState().data.let { d ->
                d.copy(
                    upNext = d.upNext.map { it.copy(overview = "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.") },
                )
            },
        )
        paparazzi.snapshot {
            HomeBaseTheme { RowsLayout(state, HomeCallbacks(), initialTab = tab, initialSelectedKey = selected, initialRailOpen = railOpen) }
        }
    }

    // ---- Aura layout (glass over a photograph; a painted sky here, since tests have no internet) ----
    @Test fun auraHome() = aura(HomeTab.FOR_YOU, null)
    @Test fun auraLiveGame() = aura(HomeTab.FOR_YOU, "g-espn-lal")
    @Test fun auraSports() = aura(HomeTab.SPORTS, "g-espn-ne")
    @Test fun auraNight() = aura(HomeTab.TV, null, phase = SkyPhase.NIGHT, hour = 23)
    @Test fun auraControlCenter() = aura(HomeTab.FOR_YOU, null, quick = true)

    private fun aura(tab: HomeTab, selected: String?, quick: Boolean = false, phase: SkyPhase = SkyPhase.DUSK, hour: Int = 18) {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.of(2026, 10, 31)
        val at = day.atTime(hour, 5).atZone(zone).toInstant().toEpochMilli()
        val weather = com.launcher.tv.data.sky.Weather.Now(
            tempC = 18.0, code = 2, isDay = hour in 7..17,
            sunrise = day.atTime(6, 36).atZone(zone).toInstant().toEpochMilli(),
            sunset = day.atTime(17, 46).atZone(zone).toInstant().toEpochMilli(),
            city = "Tuscaloosa",
        )
        val state = sampleState().copy(layout = "aura", weather = weather, teamsCount = 4)
        paparazzi.snapshot {
            HomeBaseTheme {
                AuraLayout(
                    state, HomeCallbacks(), initialTab = tab, initialSelectedKey = selected, initialQuickSettings = quick,
                    sky = buildSky(null, phase, null), now = at,
                )
            }
        }
    }

    // ---- Photo screensaver (a painted sky stands in for the photograph) ----
    @Test fun saverQuote() = saver(com.launcher.tv.dream.SaverStyle.QUOTES)
    @Test fun saverPhoto() = saver(com.launcher.tv.dream.SaverStyle.PHOTOS)

    private fun saver(style: com.launcher.tv.dream.SaverStyle) {
        val zone = java.time.ZoneId.systemDefault()
        val at = java.time.LocalDate.of(2026, 10, 31).atTime(16, 28).atZone(zone).toInstant().toEpochMilli()
        val photo = com.launcher.tv.dream.paintedPhoto(SkyPhase.DUSK, "Larch trees in the Dolomites, Italy", "© Jane Doe/Getty Images")
        paparazzi.snapshot {
            HomeBaseTheme {
                com.launcher.tv.dream.PhotoSaverLayout(
                    sampleState().data, style, photo,
                    quote = com.launcher.tv.data.sky.Quote("Be it ever so humble, there's no place like home.", "John Howard Payne"),
                    now = at, animate = false,
                )
            }
        }
    }

    /** Who's watching, with a kids profile. */
    @Test fun profilePicker() {
        val sky = buildSky(null, SkyPhase.NIGHT, null)
        val people = listOf(
            com.launcher.tv.data.Profile("main", "Alex"),
            com.launcher.tv.data.Profile("p2", "Sam", color = 1),
            com.launcher.tv.data.Profile("p3", "Kids", color = 2, kids = true),
        )
        paparazzi.snapshot {
            HomeBaseTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalBackdrop provides Backdrop(sky.tiny, androidx.compose.ui.unit.IntSize(1920, 1080))) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        SkyBackground(sky)
                        ProfilePicker(people, "main", onPick = {}, onEdit = {}, onAdd = {}, onAccounts = {}, onDismiss = {})
                    }
                }
            }
        }
    }

    // ---- Tiles layout ----
    @Test fun forYou() = snap(HomeTab.FOR_YOU, null)
    @Test fun forYouLiveGame() = snap(HomeTab.FOR_YOU, "g-espn-lal")
    @Test fun sports() = snap(HomeTab.SPORTS, "g-espn-ne")
    @Test fun tvAndMovies() = snap(HomeTab.TV, null)

    /** Provider connected: the live game knows its channel and offers Start over / Channels. */
    @Test fun liveGameWithIptv() {
        val state = sampleState().copy(
            iptv = true,
            channels = mapOf(
                "espn-lal" to ChannelLookup(
                    candidates = listOf(
                        ChannelCandidate(IptvChannel("p1", "1", "US: ESPN FHD", archive = true), 120, "network, in TV guide", "NBA: Lakers at Warriors"),
                    ),
                ),
            ),
        )
        paparazzi.snapshot {
            HomeBaseTheme { HomeLayout(state, HomeCallbacks(), initialTab = HomeTab.SPORTS, initialSelectedKey = "g-espn-lal") }
        }
    }

    @Test fun quickSettings() {
        paparazzi.snapshot {
            HomeBaseTheme {
                HomeLayout(sampleState().copy(sleepMinutes = 45), HomeCallbacks(), initialTab = HomeTab.FOR_YOU, initialQuickSettings = true)
            }
        }
    }

    @Test fun screensaver() {
        val data = sampleState().data
        paparazzi.snapshot { HomeBaseTheme { ScreensaverLayout(data, startNow = System.currentTimeMillis()) } }
    }

    @Test fun finishedGameHero() = snap(HomeTab.FOR_YOU, "g-espn-lad")

    @Test fun gamePageFootball() {
        val json = javaClass.classLoader!!.getResource("nfl_summary.json")?.readText()
        val detail = json?.let { GameDetails.parseSummary(Json.parseToJsonElement(it).jsonObject, "NFL") }
        val ev = sampleState().data.myTeams.first { it.id == "espn-ne" }.copy(live = true, status = "14-10 Q3 4:12")
        paparazzi.snapshot { HomeBaseTheme { GamePageLayout(ev, detail, loading = false, actions = GameActions(iptv = true)) } }
    }

    /** Yesterday's game, unseen, spoiler mode on: Watch full game comes first. */
    @Test fun gamePageReplay() {
        val json = javaClass.classLoader!!.getResource("nfl_summary.json")?.readText()
        val detail = json?.let { GameDetails.parseSummary(Json.parseToJsonElement(it).jsonObject, "NFL") }
        val ev = sampleState().data.myTeams.first { it.id == "espn-ne" }
            .copy(live = false, finished = true, status = null, startMillis = System.currentTimeMillis() - 26 * 3_600_000L)
        paparazzi.snapshot { HomeBaseTheme { GamePageLayout(ev, detail, loading = false, actions = GameActions(iptv = true), hideScores = true) } }
    }

    @Test fun gamePageF1() {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        val detail = GameDetail.F1(
            sessions = listOf(
                GameDetail.Session("Practice 1", now - 30 * h), GameDetail.Session("Sprint qualifying", now - 26 * h),
                GameDetail.Session("Sprint", now - 6 * h), GameDetail.Session("Qualifying", now + 1 * h), GameDetail.Session("Race", now + 22 * h),
            ),
            results = emptyList(),
            standings = listOf("Lando Norris" to "390", "Oscar Piastri" to "366", "Max Verstappen" to "341", "George Russell" to "263", "Charles Leclerc" to "214")
                .mapIndexed { i, (n, p) -> GameDetail.Standing("${i + 1}", n, "", "$p pts") },
            circuit = "Autódromo José Carlos Pace",
        )
        val ev = sampleState().data.f1.first()
        paparazzi.snapshot { HomeBaseTheme { GamePageLayout(ev, detail, loading = false, actions = GameActions(iptv = true)) } }
    }

    @Test fun spoilerMode() {
        paparazzi.snapshot {
            HomeBaseTheme { HomeLayout(sampleState().copy(spoilers = true), HomeCallbacks(), initialTab = HomeTab.FOR_YOU, initialSelectedKey = "g-espn-lad") }
        }
    }

    @Test fun bestStream() {
        val ev = sampleState().data.myTeams.first { it.id == "espn-ne" }
        fun row(provider: String, id: String, name: String, r: ProbeResult?, rating: Int, archive: Boolean = false, guide: Boolean = false) =
            CheckRow(ChannelCandidate(IptvChannel(provider, id, name, archive = archive), 80, if (guide) "network, in TV guide" else "network", if (guide) "NFL" else null), provider, r, rating)
        val state = StreamCheckState(
            ev,
            rows = listOf(
                row("Provider A", "1", "US: CBS (WBZ) Boston FHD", ProbeResult(true, 640, 8_900, 1080), 92, archive = true, guide = true),
                row("Provider B", "2", "NFL 05: Patriots vs Bills", ProbeResult(true, 1_400, 5_200, 720), 71),
                row("Provider A", "3", "US: CBS HD", ProbeResult(true, 2_900, 3_100, null), 58),
                row("Provider C", "4", "USA CBS East", ProbeResult(false, error = "HTTP 403"), 0),
            ),
            running = false,
        )
        paparazzi.snapshot {
            HomeBaseTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0E14)),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { StreamCheckContent(state, {}, {}) }
            }
        }
    }

    @Test fun teamsScreen() {
        val cfg = com.launcher.tv.data.sports.FollowConfig()
        val nfl = com.launcher.tv.data.sports.Leagues.byKey("nfl")!!
        val colors = listOf("97233F", "A71930", "241773", "00338D", "0085CA", "0B162A", "E31837", "008E97", "002244", "125740", "004C54", "FFB612")
        val teams = listOf(
            "Arizona Cardinals" to "ARI", "Atlanta Falcons" to "ATL", "Baltimore Ravens" to "BAL", "Buffalo Bills" to "BUF",
            "Carolina Panthers" to "CAR", "Chicago Bears" to "CHI", "Kansas City Chiefs" to "KC", "Miami Dolphins" to "MIA",
            "New England Patriots" to "NE", "New York Jets" to "NYJ", "Philadelphia Eagles" to "PHI", "Pittsburgh Steelers" to "PIT",
        ).mapIndexed { i, (n, a) ->
            com.launcher.tv.data.sports.FollowedTeam("nfl", if (a == "NE") "17" else "${100 + i}", n, a, colors[i])
        }
        paparazzi.snapshot {
            HomeBaseTheme { TeamsScreen(cfg, nfl, teams, null, "", {}, {}, {}, {}, {}, {}) }
        }
    }

    @Test fun phonePanel() {
        val info = PhoneInfo(enabled = true, running = true, address = "http://192.168.1.40:8686", loginLink = "http://192.168.1.40:8686/?pin=482913", pin = "482913")
        paparazzi.snapshot {
            HomeBaseTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0E14)),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { PhonePanelContent(info, {}, {}, {}) }
            }
        }
    }

    /** Simkl sign-in: a QR code that opens the approval page with the code filled in. */
    @Test fun simklSignIn() {
        val s = SimklState.AwaitingCode("BDWP-HQPK", "https://simkl.com/pin", "https://simkl.com/pin?user_code=BDWP-HQPK")
        paparazzi.snapshot {
            HomeBaseTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0E14)),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { SimklCodePanel(s) {} }
            }
        }
    }

    /** The live alert card drawn over whatever is playing. */
    @Test fun alertCard() {
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val alert = LiveAlert(
            key = "k", kicker = "TOUCHDOWN", title = "Patriots score", detail = "NE 14 – 10 BUF · Q3 4:12",
            teams = listOf(TeamInfo("Patriots", "NE", color = "002244", altColor = "C60C30"), TeamInfo("Bills", "BUF", color = "00338D", altColor = "FFFFFF")),
            event = SportsEvent("espn-ne", "Patriots @ Bills", "NFL", 0L),
        )
        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(AndroidColor.rgb(38, 52, 40), AndroidColor.rgb(14, 18, 24)))
        }
        frame.addView(
            AlertOverlay.buildCard(ctx, OverlayCard.from(alert) {}),
            FrameLayout.LayoutParams((380 * d).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = (24 * d).toInt(); rightMargin = (28 * d).toInt()
            },
        )
        paparazzi.snapshot(frame)
    }

    private fun snap(tab: HomeTab, selected: String?) {
        paparazzi.snapshot {
            HomeBaseTheme { HomeLayout(sampleState(), HomeCallbacks(), initialTab = tab, initialSelectedKey = selected) }
        }
    }

    private fun sampleState(): HomeUiState {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val day = 24 * hour
        val lal = TeamInfo("Lakers", "LAL", color = "552583", altColor = "FDB927", score = "87")
        val gsw = TeamInfo("Warriors", "GSW", color = "1D428A", altColor = "FFC72C", score = "82")
        val ne = TeamInfo("Patriots", "NE", color = "002244", altColor = "C60C30")
        val buf = TeamInfo("Bills", "BUF", color = "00338D", altColor = "C60C30")
        val lsu = TeamInfo("LSU", "LSU", color = "461D7C", altColor = "FDD023")
        val bama = TeamInfo("Alabama", "ALA", color = "9E1B32", altColor = "FFFFFF")
        val kc = TeamInfo("Chiefs", "KC", color = "E31837", altColor = "FFB612")
        val den = TeamInfo("Broncos", "DEN", color = "FB4F14", altColor = "002244")
        return HomeUiState(
            trakt = TraktState.SignedIn,
            history = listOf(
                HistoryEntry(url = "http://x/series/1.mkv", title = "The Bear", subtitle = "S04E03", isEpisode = true,
                    positionMs = 13 * 60_000L, durationMs = 34 * 60_000L, lastPlayedAt = now - 20 * hour),
            ),
            apps = listOf(AppEntry("TiviMate", AppsRepository.TIVIMATE, null, null), AppEntry("YouTube", "com.google.android.youtube.tv", null, null)),
            data = HomeData(
                upNext = listOf(
                    MediaItem("upnext-1", MediaKind.EPISODE, "Severance", "S02E08 · Sweet Vitriol · 3 left", timeMillis = now - 2 * day),
                    MediaItem("upnext-2", MediaKind.EPISODE, "Slow Horses", "S05E02 · 5 left", timeMillis = now - 30 * day),
                ),
                upcomingEpisodes = listOf(
                    MediaItem("cal-1", MediaKind.EPISODE, "Severance", "S02E09", timeMillis = now + day),
                    MediaItem("cal-2", MediaKind.EPISODE, "Slow Horses", "S05E03", timeMillis = now + 3 * day),
                ),
                watchlist = listOf(MediaItem("movie-1", MediaKind.MOVIE, "Dune: Part Two", "2024")),
                myTeams = listOf(
                    SportsEvent("espn-lal", "Lakers @ Warriors", "NBA", now - hour, live = true, status = "87-82 Q3 4:12",
                        broadcast = "ESPN", venue = "Chase Center", home = gsw, away = lal),
                    SportsEvent("espn-ne", "Patriots @ Bills", "NFL", now + day + 3 * hour, broadcast = "CBS",
                        venue = "Highmark Stadium", home = buf, away = ne),
                    SportsEvent("espn-bama", "LSU @ Alabama", "College Football", now + 7 * day, broadcast = "ABC",
                        venue = "Bryant-Denny Stadium", home = bama, away = lsu),
                ),
                nfl = listOf(
                    SportsEvent("espn-ne", "Patriots @ Bills", "NFL", now + day + 3 * hour, broadcast = "CBS", home = buf, away = ne),
                    SportsEvent("espn-kc", "Chiefs @ Broncos", "NFL", now + day + 6 * hour, broadcast = "FOX", home = den, away = kc),
                ),
                fights = listOf(SportsEvent("ufc-329", "UFC 329: Main Card", "UFC", now + 14 * day, broadcast = "Paramount+")),
                f1 = listOf(SportsEvent("f1-2026-21", "São Paulo Grand Prix", "F1", now + 8 * day, status = "Qualifying Sat 3:00 PM",
                    venue = "Interlagos · Brazil")),
                results = listOf(
                    SportsEvent("espn-lad", "Yankees @ Dodgers", "MLB", now - 20 * hour, finished = true, broadcast = "FOX",
                        home = TeamInfo("Dodgers", "LAD", color = "005A9C", altColor = "EF3E42", score = "5"),
                        away = TeamInfo("Yankees", "NYY", color = "003087", altColor = "E4002C", score = "3")),
                ),
                fetchedAt = now,
            ),
        )
    }
}
