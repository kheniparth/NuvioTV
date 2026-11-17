# Live TV port plan: Nuvio Enhanced → NuvioTV fork (and NuvioDesktop fork)

Owner request (Parth, 2026-11-17): fork the official Nuvio apps, add the Live TV feature and its provider
settings from Nuvio Enhanced, keep merging upstream, and ship builds for Android TV / Chromecast (this repo,
`kheniparth/NuvioTV`, base branch `dev`) and Windows + macOS M1 (`kheniparth/NuvioDesktop`, base branch `Dev`).

Each `## Step` below is sized to become one `implement` issue and one PR. Steps are ordered; later steps assume
the earlier ones are merged. Every step must keep `./gradlew :app:assembleDebug` green and must not change
behaviour outside Live TV.

## Source material
- Enhanced repo: `~/nuvio-enhanced`, branch `enhanced` (read with `git show enhanced:<path>`; the working
  checkout may be on another branch).
- Live TV code: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/livetv/` (19 files, ~6,300 lines):
  - Data: `LiveTvModels.kt`, `LiveTvRepository.kt` (M3U + Xtream + Stalker loading, channel list, favourites,
    recents), `LiveTvPortalProviders.kt` (Stalker portal handshake, MAC auth, `create_link`),
    `LiveTvEpg.kt` + `LiveTvEpgGzip.kt` (XMLTV EPG, gzip), `LiveTvCache.kt`, `LiveTvStreamHeaders.kt`,
    `LiveTvStorage.kt` (expect; Android actual in `androidMain/.../livetv/LiveTvStorage.android.kt`).
  - UI: `LiveTvScreen.kt` (Channels), `LiveTvGuide.kt`, `LiveTvSports.kt`, `LiveTvOnDemand.kt`,
    `LiveTvEpgUi.kt`, `LiveTvFilterPanelRow.kt`, `LiveTvPlaylistFilePicker.kt`.
  - Settings: `features/settings/LiveTvSettingsPage.kt`.
  - Player side panel: `features/player/PlayerLiveChannelsPanel.kt` (channel list while watching).
- Provider bootstrap script: `scripts/tv-livetv.sh` + `scripts/tv-livetv-prefs.py` (reads `/mnt/d/stalker/.env`).
  The Windows thread extended it with `--android|--windows` and a `--properties` writer
  (`~/nuvio-enhanced`, branch `windows-desktop`, commit 7a790e185).

## How NuvioTV is built (what the port has to fit)
- Android-only app module `:app`, package `com.nuvio.tv`, ~990 Kotlin files. Hilt DI (`@HiltAndroidApp`,
  `@AndroidEntryPoint`), Jetpack Compose + `androidx.tv:tv-material`.
- Persistence: one DataStore class per feature in `data/local/` (e.g. `DebridSettingsDataStore.kt`), profile-scoped
  through `ProfileDataStoreFactory`.
- Network: OkHttp + Retrofit/Moshi (Ktor is also on the classpath).
- Navigation: `ui/navigation/Screen.kt` (sealed routes) + `NuvioNavHost.kt`; sidebar/top entries are wired in
  `NuvioNavHost.kt`.
- Settings: `ui/screens/settings/` with `SettingsScreen.kt`, `SettingsDesignSystem.kt`, one `*SettingsScreen` +
  `*ViewModel` per area (see `DebridSettingsScreen.kt`, `MediaServersSettingsScreen.kt` for the pattern).
- Player: route `Screen.Player` takes `streamUrl`, `headers`, `contentType`, title, poster, etc. Passing
  `contentType = "channel"` already switches the player to its live UI (`LivePlaybackUiPolicy`).
  Live playback therefore needs no player work: Live TV only has to build the URL + headers and navigate.

## What changes in the port
- Pure Kotlin data code (models, M3U/Xtream/Stalker/EPG parsing) copies over with package renames.
- Enhanced helpers get replaced by NuvioTV equivalents: `httpGetText`/`httpGetTextWithHeaders`/`httpRequestRaw`
  → an OkHttp-based `LiveTvHttp` in the Live TV package; `InAppLogger`/kermit → the app's logger;
  `AppFileCache` → `context.cacheDir`; `expect` storage/gzip → plain Android code (`java.util.zip.GZIPInputStream`).
- All UI is rewritten with NuvioTV's TV components (tv-material, its focus handling and design system).
  Do not copy Enhanced's phone UI or its `tvFocus*` workarounds.

## Step 1: Live TV data layer
Copy models, M3U parser, Xtream client, Stalker portal client and EPG (XMLTV + gzip) into
`com.nuvio.tv.livetv.data`. Replace the Enhanced HTTP/log/cache helpers as above. Expose a Hilt-provided
`LiveTvRepository` with: load channels from all enabled sources, categories, EPG per channel, favourites,
recents, and `resolvePlayableUrl(channel)` (Stalker `create_link`, Xtream URL building, per-source headers).
Unit tests: M3U parsing (EXTINF attributes, group-title, tvg-id/logo, headers), Xtream URL building, EPG time
parsing, Stalker token refresh path (use fixtures, no network).

## Step 2: Provider settings storage
`LiveTvSettingsDataStore` in `data/local/`, profile-scoped like the others. Holds: M3U playlists (URL or file),
Xtream (server, user, password), Stalker portal (URL, MAC, optional user/password), EPG sources, enabled flags,
favourites and recents. Keep the same field meanings as Enhanced's `LiveTvStorage` so the bootstrap script can
target both.

## Step 3: Live TV settings screen
New `LiveTvSettingsScreen` + ViewModel under `ui/screens/settings/`, entry in `SettingsScreen` next to Debrid /
Media servers. Add, edit, enable/disable and remove M3U, Xtream, Stalker and EPG sources. Text fields must let the
remote leave them with Up/Down. "Test connection" button per source showing channel count or the error.

## Step 4: Live TV entry and Channels screen
Route `Screen.LiveTv` + sidebar entry (hidden when no source is configured). Channels screen: category row,
channel list with logo, now/next from EPG, favourite toggle, search. One focus stop per actionable element
(no focusable card wrapping inner buttons). OK plays: navigate to `Screen.Player` with the resolved URL,
headers and `contentType = "channel"`.

## Step 5: Guide (EPG grid)
Channels down the left, programmes on a shared horizontal timeline, now-line, Left/Right step through programmes
and scroll the timeline, OK plays the channel. Category filter as in Channels.

## Step 6: In-player channel switching
While a Live TV channel plays: channel up/down (D-pad Up/Down when controls are hidden, and media channel keys)
and a channel list side panel. Back from the player returns to Live TV. Check the existing player Back handling
works on the error screen too (Enhanced bug: the error screen turned portrait and its "Go back" button was not
reachable; make sure NuvioTV's error UI is used and stays landscape).

## Step 7: Sports
Port `LiveTvSports.kt`'s logic (live/upcoming games matched to channels) into a Sports tab. Owner request: more
leagues than Enhanced (NBA, NFL, NHL): add cricket, MLB, soccer where the score source supports them. Each game's
channel buttons are individually focusable.

## Step 8: On Demand (Xtream / Stalker VOD)
Movies / Series tabs, then a focusable category list, then items. Search with a reachable clear (X) button.
Favourites. Items open the normal NuvioTV detail/player flow where possible.

## Step 9: Live preview (owner request)
In Channels, Guide and Sports, a small muted preview of the focused channel (after ~1 s of focus), so the user
can check the stream before going full screen. Use one shared lightweight player instance; stop it when leaving
the screen. Must not slow down list scrolling on the Chromecast.

## Step 10: Provider bootstrap for test devices
`scripts/livetv-bootstrap.sh`: reads `/mnt/d/stalker/.env` (STALKER_HOST, STALKER_MAC, ADDON_PUBLIC_URL,
ADDON_TOKEN) and writes the sources into the app on an adb device (Android TV / Chromecast / emulator).
Same approach as Enhanced's `scripts/tv-livetv.sh` (run-as on a debuggable build; the sources survive a later
`adb install -r` of a release-like build). Never print the token.

## Step 11: Upstream sync
Document and script `git fetch upstream && git merge upstream/dev` for this fork; Live TV lives in its own
package and touches shared files only at the few wiring points (Screen.kt, NuvioNavHost.kt, SettingsScreen.kt,
DI module), so merges stay small. Add those wiring points to a short list in this file.

## NuvioDesktop fork (Windows + macOS M1), after the TV steps
NuvioDesktop is Kotlin Multiplatform from the same lineage as Enhanced, so Enhanced's `features/livetv/` and
`LiveTvSettingsPage.kt` drop in as new files with much less change (do not merge Enhanced into it: that gave
~1,050 compile errors). Reuse from `windows-desktop` (commit 7a790e185): the desktop `LiveTvStorage` actual,
the `--windows` provider bootstrap, `windows-sync.sh` + `windows-package.ps1`, and the `checkAllPlatforms`
task. The desktop player (libmpv) plays the channel URLs; check header support. macOS builds need the Mac or a
macOS CI runner. Pin `mpv-android-lib` (0.2.1 broke Enhanced's player).

## Not ported
- Enhanced's phone-UI TV workarounds (`tvFocusOnAppear`, `tvNotFocusable`, `TvNavigationRail`, MainActivity key
  hacks): NuvioTV is built for the remote.
- Stalker content inside normal title streams: already works through the stalker bridge's Stremio addon; install
  it as an addon in NuvioTV.
