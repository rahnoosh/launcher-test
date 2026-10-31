# HomeBase: a Google TV launcher for the Xiaomi TV Box S (3rd gen)

A replacement home screen built around what you actually watch:

| Row | Source |
|---|---|
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

## Notes and limits

- **TiviMate history:** TiviMate doesn't share its watch history with other apps, so HomeBase reads your
  history from Trakt. The planned next step is a small "player bridge": set HomeBase as TiviMate's external player
  for VOD. It would scrobble what you watch to Trakt and then play it, so TiviMate viewing ends up in *Continue watching*.
- **UFC PPVs:** from 2026 the numbered UFC events stream on Paramount+ rather than as pay-per-view.
  HomeBase shows every numbered event (`UFC 3xx`), which are the cards that used to be PPV.
- **Boxing** comes from TheSportsDB's free tier, which can lag behind fight announcements.
- **ESPN's API** is public but unofficial. If ESPN changes it, those rows keep showing cached data until the
  parser is updated.
