# Nuvio progress

Last updated: 2026-11-18. Single source of truth for picking the work back up with any agent.
Update it at the end of every work session (see [AGENTS.md](AGENTS.md)).

## Goal
Fork the official Nuvio apps, add the Live TV feature and its provider settings from Nuvio Enhanced, keep
merging upstream, and ship builds for Android TV / Chromecast and Windows + macOS M1. Full plan:
[LIVE_TV_PORT_PLAN.md](LIVE_TV_PORT_PLAN.md).

## Repos and checkouts (all in WSL Ubuntu on "beast")
| What | GitHub | Base branch | Checkout |
|---|---|---|---|
| TV fork (this repo) | kheniparth/NuvioTV (upstream NuvioMedia/NuvioTV) | `dev` | `~/nuvio/nuvio-tv` |
| Factory's own checkout of the TV fork | same | `dev` | `~/nuvio/factory/nuvio-tv` (do not edit by hand, see Gotchas) |
| Desktop fork | kheniparth/NuvioDesktop (upstream NuvioMedia/NuvioDesktop) | `Dev` | `~/nuvio/nuvio-desktop` |
| Enhanced (reference source, read only) | luqmanfadlli/NuvioMobile-Enhanced | `enhanced` | `~/nuvio/nuvio-enhanced` |
| Earlier Windows build of Enhanced | | `windows-desktop` | `~/nuvio/nuvio-win` |
| Built APKs | | | `~/nuvio/apks/` |

Board: https://github.com/users/kheniparth/projects/2 (gh needs `gh auth refresh -s read:project` to read it).

## Past work
- 2026-11-17: Forked both apps. Wrote LIVE_TV_PORT_PLAN.md. Factory set up on the TV fork (`.factory/`).
- 2026-11-17: Live TV steps 1-11 (issues #2-#15) all merged: data layer, settings storage and screen, Channels,
  Guide, in-player channel switching, Sports (more leagues), On Demand, live preview, bootstrap script
  (`scripts/livetv-bootstrap.sh`), upstream sync script (`scripts/upstream-sync.sh`). Upstream synced (PR #29).
  APK `~/nuvio/apks/nuvio-tv-livetv-20261117.apk`.
- 2026-11-17/18: Bugs from the first real-device test fixed and merged: #37 channel switch 404 (PR #43),
  #38 Sports never loads (PR #41), #39 no EPG/logos (PR #42), #40 preview as a side panel (PR #44),
  bootstrap on fresh installs (PR #36).

## Current state (2026-11-18)
- TV fork `dev` has the full Live TV feature. Open: PR #48 (#45 category column), PR #49 (#47 preview flashes
  and disappears), #50 (release builds lose Live TV settings: R8 renames Gson fields).
- The owner's TV runs the release build (`com.nuvio.tv`, signed in, no Live TV sources yet) because debug builds
  lag (59% janky frames vs 24% release, 12% official). Release builds can't run `livetv-bootstrap.sh` (no run-as).
- Desktop fork: no Live TV work yet; it only tracks upstream.

## Next steps
1. #45 (labelled `implement`, the factory will pick it up): categories as the first left column on every Live TV
   screen (Channels, Guide, Sports, On Demand), one shared `LiveTvCategoryColumn`. Owner's layout preference.
2. Build one release APK (`:app:assembleFullRelease`) with everything merged and test it on the emulator only
   (batch the fixes: one combined build and test per set). Queue Chromecast tests until the owner lifts the hold.
3. Merge upstream into both forks before each round (`scripts/upstream-sync.sh` for TV), test upstream's new
   features along with ours.
4. Desktop fork: port Enhanced's `features/livetv/` (see "NuvioDesktop fork" in LIVE_TV_PORT_PLAN.md).
   Do not merge Enhanced into it (~1,050 compile errors). macOS builds need the Mac or a macOS CI runner.

## How to build and test (TV)
- Checks: `bash .factory/build.sh` (Gradle in WSL on a native-disk copy: `:app:assembleFullDebug
  :app:testFullDebugUnitTest`). One Gradle build needs ~13 GB of WSL's ~23 GB: never run two at once.
- Debuggable APK for bootstrap/run-as: build with `-Pdebuggable=true`. Package `com.nuviodebug.com`.
- adb: WSL `~/Android/Sdk/platform-tools/adb` only.
- Devices: Android TV emulator in WSL (AVD `nuvio_tv`, `emulator-5554`, run it as a long-lived background job);
  Chromecast with Google TV at 192.168.2.10 (adb TLS: `adb mdns services`, then `adb connect <ip:port>`).
- Live TV sources: `bash scripts/livetv-bootstrap.sh [--device]` reads `/mnt/d/stalker/.env`. Rerun it after
  onboarding (finishing onboarding resets Live TV settings).
- Saved login: `~/nuvio/nuvio-enhanced/scripts/tv-login.sh` types NUVIO_EMAIL/NUVIO_PASSWORD from that repo's
  `.env` into the sign-in screen over adb. Signing key: `keystore/debug.keystore` in nuvio-enhanced.
- Upstream: `bash scripts/upstream-sync.sh` from a clean `dev` (creates `sync/upstream-YYYYMMDD`, runs checks,
  does not push).

## The factory
- Lives in `.factory/` (see `.factory/README.md`, `FLOW.md`). Windows task `nuvio-tv-tick` runs the router every
  10 min: Cursor and DeepSeek implement issues labelled `implement`, a Claude review run reviews and merges.
- Status: `bash ~/nuvio/factory/nuvio-tv/.factory/status`. Pause: `.factory/freeze`. UI: `.factory/ui/tui.py`.
- Labels are intents (`architect`, `implement`, `review`, `later`, `owner-check`), never worker names.
- Worker rules: `.factory/AGENTS.md`; PR handoff format: `.factory/handoff.md`.

## Decisions (do not reopen)
- Live TV code lives in `com.nuvio.tv.livetv`; upstream files change only at wiring points (listed in
  LIVE_TV_PORT_PLAN.md) so upstream merges stay cheap.
- UI is rewritten with NuvioTV's TV components; Enhanced's phone UI and `tvFocus*` hacks are not ported.
- Stalker content inside normal title streams goes through the stalker bridge Stremio addon, not Live TV.
- Never change native player library versions or upstream workflows. Never touch `NuvioMedia/*` on GitHub.
- No testing on the real TV / Chromecast (owner hold from 2026-11-18 02:07 UTC, until the owner says otherwise).
  Use the emulator and queue TV tests as "Owner tests" in the PR handoff.

## Gotchas
- The Stalker MAC allows one stream at a time: a second device gets HTTP 403.
- Screencap of hardware-decoded video is black on the Chromecast; check `dumpsys media_session` instead.
- Never leave uncommitted edits in `~/nuvio/factory/nuvio-tv`: they break the review run's `git pull --ff-only`.
- Never run the router by hand near a scheduled tick (:01/:11/:21...); two routers race. Run it from a Windows
  cwd (C:\), not `\\wsl.localhost`.
- A dead worker leaves its `issue-N.txt` marker in `%USERPROFILE%\.claude\scheduled-tasks\nuvio-tv\dispatch`,
  so the issue is never retried: delete the marker.
- `CLAUDE.md` and `scripts/*` are gitignored upstream (whitelisted scripts excepted); agent rules go in AGENTS.md.

## Secrets (never commit)
`/mnt/d/stalker/.env` (Stalker host, MAC, addon token), nuvio-enhanced `.env` (login), keystores and their
passwords, `local.properties`.
