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

Teams and leagues are chosen on the TV (Quick settings → Your teams) or on the phone page.

## 1. Get API keys

1. **Trakt** (required for the TV/movie rows): https://trakt.tv/oauth/applications → *New application*.
   Name it anything and set the redirect URI to `urn:ietf:wg:oauth:2.0:oob`. Copy the Client ID and Client Secret.
2. **TMDB** (optional, for artwork): https://www.themoviedb.org/settings/api → API Key.

You enter these **on the box**, either on the phone page (easiest) or in Quick settings → HomeBase → API keys.
They're stored only on the box. They are **not** built into the APK: CI builds are published publicly for
self-updates, so they must not contain secrets. Don't add them as GitHub secrets.

## 2. Get the APK

**Option A: GitHub Actions (no Android Studio needed).** Every push builds the APK. Download the
`HomeBase-apk` artifact from the run, or, once self-updates are set up (see below), the `HomeBase.apk`
asset of the `homebase-latest` release.

**Option B: locally.** Copy `local.properties.example` to `local.properties` and run `./gradlew assembleRelease`
(or open the project in Android Studio). The APK is written to `app/build/outputs/apk/release/`.
Keys in `local.properties` are built into that local APK. Only do that for an APK you won't share.

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

## 7. Connect your IPTV providers (Xtream Codes or M3U)

Open **Quick settings** (the gear) and choose **HomeBase**. You can add as many providers as you like; their
channels are searched together.

- **Add Xtream:** the same server, username and password you use in TiviMate.
- **Add M3U playlist:** the playlist URL, plus an optional XMLTV guide URL. If the playlist names its own guide
  (`url-tvg`), you can leave the guide empty. Gzipped guides work too.
  If the M3U link is actually an Xtream login (`…/get.php?username=…&password=…`), HomeBase adds it as Xtream
  instead, which gives you catch-up, series info and a faster guide.

Logins are stored only on the box. Playlists and catalogues refresh daily, M3U guides every 12 hours.
For M3U providers, *Start over* uses the playlist's `catchup` / `catchup-source` settings, or the Xtream
timeshift path when the stream URLs look like Xtream. Movies and episodes in the playlist are matched by title
(`The Bear S04 E03`, `Dune: Part Two (2024)`).

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

## 8. Game pages, results and highlights

- **Game details** on any game opens its page:
  - **Team games:** scoreboard with records, line score, leaders, the latest scoring plays and team stats.
    It refreshes every minute while the game is live.
  - **F1:** the race weekend schedule in your time zone, the race result and the drivers' standings.
  - **UFC:** the fight card, main event first, with records and winners.
- **Results:** for 48 hours after a game ends, its tile shows the final score. The same goes for numbered UFC
  events and the last F1 race.
- **Highlights:** HomeBase plays ESPN's own highlight clip in HomeBase Player when ESPN has one. Otherwise it
  opens a YouTube search ("New England Patriots vs Buffalo Bills highlights", "F1 São Paulo Grand Prix 2026
  highlights").
- The *Final* alert card now opens the game page.

## 8a. Spoiler mode, best stream, and TiviMate

- **Spoiler mode** (Quick settings tile, or HomeBase settings) hides the scores of live and finished games
  everywhere: tiles, the hero, the bottom strip, game pages (line score, plays, leaders, stats, F1 results and
  standings, UFC winners), the screensaver, and alerts. With spoiler mode on, alerts announce kickoff and the end
  of a game, but no scoring plays and no final score. **Show score** reveals one game; HomeBase remembers that.
- **Best stream** (on a game, or its page) answers "which provider should I watch this on?". It takes the
  channels that match the event from all your providers and plays the best one or two from each for a few
  seconds, one at a time so your providers' connection limits aren't exceeded. It measures how fast each starts,
  its bitrate and resolution, and ranks them with a **BEST** label. Pick a row to watch it in HomeBase.
- **TiviMate** on a row opens TiviMate and shows a card for 30 seconds with the playlist, group, channel name
  and channel number to pick. While the card is up, your remote works in TiviMate as normal. TiviMate has no
  documented way for other apps to open a specific channel, so HomeBase can't switch it for you.

### One-press tuning in TiviMate (experimental)

TiviMate switches channel when you type a channel number, but the Xiaomi remote has no number keys. With
one-press tuning on, the **TiviMate** button opens TiviMate and types the number for you. To do that, HomeBase
uses the box's own network debugging (ADB on port 5555, the same connection you used to install it).

1. Make sure ADB over the network is on. If `adb connect <box-ip>:5555` works from your computer, it is.
   If not, connect by USB once and run `adb tcpip 5555`. Some boxes turn this off again after a reboot; if
   tuning stops working after one, run it again.
2. In **Quick settings → HomeBase**, turn **One-press tuning** on. The TV asks *Allow network debugging?*:
   tick **Always allow from this computer** and choose **Allow**. **Test connection** checks it any time.
3. HomeBase uses your provider's channel numbers: Xtream `num`, or M3U `tvg-chno`. If TiviMate opens the wrong
   channel, set TiviMate to number channels the way your playlist does, rather than numbering them in order.
   With several playlists in TiviMate, their numbers can overlap.

If tuning fails for any reason, HomeBase falls back to the card that tells you which channel to pick.
Network debugging lets any device on your network that you've allowed control the box, so only accept
prompts you expect. You can turn it off in Developer options when you're not using this.

## 8b. Live alerts

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

## Home screen layouts

**Rows** (the default) looks like a streaming app: a menu on the left (Home, TV & Movies, Sports, Apps, Refresh,
Quick settings), a large preview of the selected item with its description on top, and rows of wide cards below:
Continue Watching, Live Now, Next Up, Your Teams, Recent Results, New Episodes, Fight Night, NFL This Week,
Formula 1, My Watchlist and Apps. **OK** plays or watches. **Hold OK** opens more options; for a game, that's its
game page, with Best stream, Start over, Channels and Show score.

**Tiles** is the console-style layout with a row of square tiles. Switch layouts in Quick settings → HomeBase →
Home screen layout.

## Your teams (on the TV)

**Quick settings → Your teams**, or the **+ Add or remove teams** card at the end of *Your Teams*, opens the
Teams screen:

- Pick a league on the left, then select teams to follow or unfollow. **Search** filters long lists, which helps
  with college football and college basketball. Leagues: NFL, college football, NBA, WNBA, college basketball,
  MLB, NHL, MLS, Premier League, Champions League and LaLiga.
- The row of switches turns on **every NFL game this week**, **UFC numbered events**, **big boxing fights**,
  **Formula 1**, **golf majors** and **tennis Grand Slams**.
- **Games per team** sets how many upcoming games each team shows (1–3).

Changes show on Home within a couple of seconds. Live alerts follow whatever you pick. Hockey and soccer get
*GOAL* alerts; basketball gets *close game* alerts late on.

## Phone page

HomeBase runs a small web page on your home network. **Quick settings → Phone page** shows a QR code, the
address and a 6-digit PIN. Scan the code and you're signed in. From your phone you can:

- **Watch:** search games, shows, channels and movies, then *Watch on TV* / *Play on TV* or open in TiviMate
- **Teams:** the same as the Teams screen, with typing
- **Providers:** add Xtream logins or M3U playlists without the TV keyboard, or remove them
- **Settings:** API keys, spoiler mode, alerts, layout, sleep timer and one-press tuning; check for and install
  updates; **download a backup** (teams, settings, channel picks, and provider logins only if you tick that box)
  and **restore** it

Security:

- It only answers on your home network, only to the box's own address, and only after the PIN.
- The PIN is shown only on the TV. Five wrong tries lock that phone out for a minute.
- API keys, provider logins and stream links are never sent back to the phone. The only exception is a backup
  file you download with *Include provider logins* ticked; keep that file private.
- The page uses plain HTTP inside your home network, so only use it on a network you trust.
- **New PIN** signs every phone out. **Turn off** stops the page.

## Self-updates

HomeBase can update itself from this repo's GitHub releases. CI publishes every build of the default branch,
as `HomeBase.apk` plus `version.json`, to a rolling release called `homebase-latest`. These builds contain no
API keys. When there's a newer build, *Updates* in Quick settings (and the phone page) says so. Choose it to
download the build and install it. Android asks you to confirm, and the first time it asks you to allow HomeBase
to install apps.

Android only installs an update that's signed with the same key as the installed app. Set up a key once:

1. On your computer, create a keystore (keep this file and its password safe and private; you need the same one
   for every future build):
   ```sh
   keytool -genkeypair -v -keystore homebase.keystore -alias homebase -keyalg RSA -keysize 2048 -validity 10000
   ```
2. In the repo's *Settings → Secrets and variables → Actions*, add:
   - `SIGNING_KEYSTORE_BASE64`: the keystore file in base64, from `base64 -w0 homebase.keystore`
     (on macOS: `base64 -i homebase.keystore`)
   - `SIGNING_STORE_PASSWORD`: the password you chose
   - `SIGNING_KEY_ALIAS`: `homebase`
   - `SIGNING_KEY_PASSWORD`: the key password (leave unset if it's the same as the store password)
3. In the repo's *Actions* tab, open *Build APK* and choose *Run workflow* (or push any commit). That build is
   signed and published.
4. **Once:** builds before this were signed with a throwaway key, so Android won't update them. Back up from the
   phone page, uninstall HomeBase, install the new `HomeBase.apk`, then restore the backup and re-enter the API
   keys. After that, updates install over the top.

For local builds, put the same values in `local.properties`, with `SIGNING_KEYSTORE_FILE=/path/to/homebase.keystore`
in place of the base64 one.

## 9. Quick settings and sleep timer

The gear at the top right opens **Quick settings**, a strip of tiles along the bottom like the PS5 control
centre:

| Tile | Opens |
|---|---|
| Network | Wi-Fi & Ethernet settings |
| Remotes & Bluetooth | Pairing remotes, headphones, controllers |
| Display / Sound | Picture, HDR, resolution, audio output |
| Sleep timer | Each press steps Off → 30 → 60 → 90 → 120 min |
| Screensaver | Preview of the HomeBase screensaver |
| HomeBase | Provider login, alerts, Trakt |
| All settings | The full Android TV settings |

The **sleep timer** runs in the HomeBase Home button service (step 4). A minute before the end, a card appears:
press **OK** for 30 more minutes, or do nothing and the box goes to standby. If HDMI-CEC is on, the TV turns off
too.

## 10. Screensaver

HomeBase includes a screensaver. It shows a big clock, your shows' artwork and team colours slowly zooming, live
scores, and what's on in the next three days. It shifts a few pixels every minute to protect OLED screens.
Google TV doesn't list third-party screensavers in its menus, so switch it on over adb:

```sh
adb shell settings put secure screensaver_components com.launcher.tv/com.launcher.tv.dream.HomeBaseDream
adb shell settings put secure screensaver_enabled 1
```

It starts after the same idle time as Google TV's own screensaver. To go back to Google's ambient mode,
put back the component that was there before. You can see it first with
`adb shell settings get secure screensaver_components`; on Google TV it's usually
`com.google.android.apps.tv.dreamx/.service.Backdrop`.

## Notes and limits


- **Player bridge:** this needs TiviMate to pass the title to the external player. If it only passes the stream
  URL, history still works but Trakt scrobbling can't identify the video. Streams ExoPlayer can't decode (rare codecs
  such as DTS audio) show an error; use TiviMate's own player for those.
- **UFC PPVs:** from 2026 the numbered UFC events stream on Paramount+ rather than as pay-per-view.
  HomeBase shows every numbered event (`UFC 3xx`), which are the cards that used to be PPV.
- **Boxing** comes from TheSportsDB's free tier, which can lag behind fight announcements.
- **ESPN's API** is public but unofficial. If ESPN changes it, those rows keep showing cached data until the
  parser is updated.
