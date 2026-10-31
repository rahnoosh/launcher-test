# HomeBase: a Google TV launcher for the Xiaomi TV Box S (3rd gen)

A replacement home screen built around what you actually watch:

| Row | Source |
|---|---|
| **Recently watched in TiviMate**: movies and episodes you played, with resume points | HomeBase player bridge |
| **Continue watching**: next episode of every show you're on | Trakt |
| **New episodes coming up**: the next 14 days of your shows | Trakt calendar |
| **Your teams**: Alabama Crimson Tide, Patriots, Lakers, Dodgers | ESPN public API |
| **Fight night**: numbered UFC events + big boxing cards | ESPN (UFC), TheSportsDB (boxing) |
| **NFL this week**: every game, with TV channel and live scores | ESPN |
| **Formula 1**: next race weekends | Jolpica (Ergast) |
| **Movie watchlist** | Trakt |
| **Apps**: TiviMate first, then all your other TV apps | Installed apps |

Cards for shows, movies and games open **TiviMate**. Data is cached on the box, so Home opens instantly. It refreshes every 30 minutes, or every 2 minutes while one of your games is live.

Teams and leagues are set in
[`SportsConfig.kt`](app/src/main/java/com/launcher/tv/data/sports/SportsConfig.kt).

## 1. Get API keys

1. **Trakt** (required for the TV/movie rows): https://trakt.tv/oauth/applications → *New application*.
   Name it anything and set the redirect URI to `urn:ietf:wg:oauth:2.0:oob`. Copy the Client ID and Client Secret.
2. **TMDB** (optional, for artwork): https://www.themoviedb.org/settings/api → API Key.

## 2. Build the APK

**Option A: GitHub Actions (no Android Studio needed).**
In the repo go to *Settings → Secrets and variables → Actions* and add `TRAKT_CLIENT_ID`,
`TRAKT_CLIENT_SECRET` and (optionally) `TMDB_API_KEY`. Re-run the *Build APK* workflow and
download the `HomeBase-apk` artifact.
Keep the repo private if you do this, because the keys end up inside the APK.

**Option B: locally.** Copy `local.properties.example` to `local.properties`, fill in the keys,
then run `./gradlew assembleRelease` (or open the project in Android Studio).
The APK is written to `app/build/outputs/apk/release/`.

## 3. Install on the box

Enable developer mode on the box (*Settings → System → About → click "Android TV OS build" 7 times*),
then turn on *USB debugging* / *Network debugging* in *Developer options*. From a computer on the same network:

```sh
adb connect <box-ip>:5555
adb install -r app-release.apk
```

(Or use the *Downloader* / *Send Files to TV* apps.)

## 4. Make the Home button open HomeBase

Google TV won't let a third-party app be the default launcher. HomeBase includes an
accessibility service that catches the Home button and opens HomeBase instead (the same trick Projectivy uses).

Android 13+ blocks enabling accessibility services for sideloaded apps from the UI, so enable it over adb:

```sh
adb shell appops set com.launcher.tv ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services com.launcher.tv/com.launcher.tv.service.HomeButtonService
adb shell settings put secure accessibility_enabled 1
```

(If you already use other accessibility services, append with `:` rather than overwrite. Check with
`adb shell settings get secure enabled_accessibility_services`.)

To undo it: *Settings → System → Accessibility → HomeBase Home button → Off*.

## 5. Connect Trakt

Open HomeBase, select **Connect Trakt**, then enter the on-screen code at `trakt.tv/activate` on your phone.

## 6. Record what you watch in TiviMate (player bridge)

TiviMate doesn't share its watch history with other apps. Instead, HomeBase includes its own video player
(**HomeBase Player**, built on ExoPlayer) that you set as TiviMate's external player. Everything you play then goes
through HomeBase:

- It shows up in **Recently watched in TiviMate** with a progress bar. Select it to resume where you stopped;
  long-press to remove it.
- It's matched to Trakt from the title and **scrobbled**: Trakt marks it watched once you pass 80%, so
  *Continue watching* moves on to the next episode by itself.

Setup: in TiviMate (external players need TiviMate Premium) open *Settings → Playback* and set the player for
movies/series to **External player → HomeBase Player**. Menu names vary a little between TiviMate versions. If Android
shows an "Open with" list, choose *HomeBase Player → Always*. Keep TiviMate's own player for live TV: live channels
aren't recorded or scrobbled.

How titles are matched: IPTV titles like `EN - The Bear S03 E05` or `|EN| Dune: Part Two (2024) [4K]` are
cleaned up before searching Trakt
([`TitleParser.kt`](app/src/main/java/com/launcher/tv/player/TitleParser.kt), with tests). Titles that
can't be parsed are still saved to the history but not scrobbled.

## 7. Connect your IPTV provider (Xtream Codes)

Open **Settings** (the gear at the top right) and choose **IPTV provider, Set up**. Enter the same Xtream Codes
server, username and password you use in TiviMate. You can also paste your M3U link
(`http://host:port/get.php?username=…&password=…`) into the server box, and HomeBase fills in the rest. The
login is stored only on the box.

After that:

- **Games:** *Watch* tunes straight to the game in HomeBase Player. HomeBase picks the channel by looking for:
  1. a channel you picked before for that team or network,
  2. event channels named after the teams (e.g. `NFL 05: Patriots vs Bills`),
  3. the network ESPN lists (CBS, ESPN, SEC Network, local affiliates…),
  4. the TV guide, once the game is under 6 hours away.

  When it isn't sure, it shows **Channels** so you can choose, and remembers your choice. *Start over* plays a
  live game from kickoff using catch-up, if your provider has catch-up for that channel.
- **Shows and movies:** *Play S04E03* on a next-up show, or *Play* on a watchlist movie, finds it in your
  provider's catalogue and plays it. Playback is scrobbled to Trakt with exact IDs.
- Channel lists and catalogues download once a day in the background.

## 8. Live alerts

While the HomeBase Home button service is on (step 4), a card appears in the top-right corner over any app,
TiviMate included:

- *Starting in 10 min* for your teams, numbered UFC events, big boxing cards and F1 races
- *Kickoff*, *Tip-off* or *First pitch*
- Scoring plays: touchdowns, field goals and runs. Basketball only gets *Close game* in the 4th quarter, so
  there isn't an alert for every basket.
- *Final*

While a card is showing, press **OK** to switch to that game, or **Back** to dismiss it. Alerts and scoring-play
alerts can be turned off in Settings. There are no alerts for the game you're already watching in HomeBase
Player.

## Notes and limits


- **Player bridge:** this needs TiviMate to pass the title to the external player. If it only passes the stream
  URL, history still works but Trakt scrobbling can't identify the video. Streams ExoPlayer can't decode (rare codecs
  such as DTS audio) show an error; use TiviMate's own player for those.
- **UFC PPVs:** from 2026 the numbered UFC events stream on Paramount+ rather than as pay-per-view.
  HomeBase shows every numbered event (`UFC 3xx`), which are the cards that used to be PPV.
- **Boxing** comes from TheSportsDB's free tier, which can lag behind fight announcements.
- **ESPN's API** is public but unofficial. If ESPN changes it, those rows keep showing cached data until the
  parser is updated.
