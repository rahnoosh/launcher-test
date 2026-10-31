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

    @Test fun forYou() = snap(HomeTab.FOR_YOU, null)
    @Test fun forYouLiveGame() = snap(HomeTab.FOR_YOU, "g-espn-lal")
    @Test fun sports() = snap(HomeTab.SPORTS, "g-espn-ne")
    @Test fun tvAndMovies() = snap(HomeTab.TV, null)

    private fun snap(tab: HomeTab, selected: String?) {
        paparazzi.snapshot {
            HomeBaseTheme { HomeLayout(sampleState(), HomeCallbacks(), initialTab = tab, initialSelectedKey = selected) }
        }
    }

    private fun sampleState(): HomeUiState {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val day = 24 * hour
        val lal = TeamInfo("Lakers", "LAL", "552583", "FDB927", score = "87")
        val gsw = TeamInfo("Warriors", "GSW", "1D428A", "FFC72C", score = "82")
        val ne = TeamInfo("Patriots", "NE", "002244", "C60C30")
        val buf = TeamInfo("Bills", "BUF", "00338D", "C60C30")
        val lsu = TeamInfo("LSU", "LSU", "461D7C", "FDD023")
        val bama = TeamInfo("Alabama", "ALA", "9E1B32", "FFFFFF")
        val kc = TeamInfo("Chiefs", "KC", "E31837", "FFB612")
        val den = TeamInfo("Broncos", "DEN", "FB4F14", "002244")
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
                fetchedAt = now,
            ),
        )
    }
}
