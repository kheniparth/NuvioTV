# Nuvio progress

Last updated: 2026-11-21. Single source of truth for picking the work back up with any agent.
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

- 2026-11-18/20: Follow-up fixes merged: #59, #64, #70-#75, #79-#82 (see Current state). Remaining work
  filed as #83-#85 and desktop briefs D1-D8; queued on Paperclip.
- 2026-11-20: #85 TMDB key done: Parth added `TMDB_API_KEY` to the factory props; agents added a missing-key
  guard in `app/build.gradle.kts` (PR #90, merged `56d94fb8e`).
- 2026-11-21: TEC-51 Sports timeout fixed (PR #89) and TEC-52 offline Live TV error finished with the fail-fast
  follow-up (PR #95); both merged into `dev` after Tester3 passes.

## Current state (2026-11-21)
- TV fork `dev` has the full Live TV feature plus every fix filed so far. All Live TV issues through #82 are closed:
  Sports load (#70), category-left layout (#71), Guide names and focus (#72, #81), episode sort latest-first (#74, #80),
  CH+/CH- and Page Up/Down (#75), orange copper test icon (#79), Stalker stream release and 403 retry (#82),
  release builds keep Live TV settings (#52). Apart from the TEC-76 batch, none of these has had one combined
  release-build pass on a device yet (TEC-6).
- TEC-51 Sports fixed on `dev` (PR #89, merge `17a46a13f`): the ESPN fan-out is cut to today+tomorrow, scores
  and channels load in parallel, and the load budget is 15 s, so games render in 4–5 s instead of the
  "Couldn't load scores" timeout. Tester3 passed (Sports unit tests 16/16).
- TEC-52 Live TV offline fixed on `dev` (PR #88, merge `08fba9cd7`; fail-fast follow-up PR #95, merge
  `3e82305a5`): a DNS/connection failure shows "No connection — check your network" after the first load
  attempt (9.4 s on the emulator, was 75 s). Tester3 passed steps 1–2; the 403 recovery path was unchanged
  but could not be re-observed because the source issued no 403 that session. The fail-fast lives in the
  shared player load policy, so a VOD DNS/connection failure also stops retrying the same URL.

- #85 TMDB key closed: the factory props now have `TMDB_API_KEY`, and a missing or blank key warns
  `TMDB_API_KEY missing: metadata will not load` on debug while `:app:assembleFullRelease` fails (PR #90).
- Real-TV testing is on hold (owner); emulator only, TV steps are queued as owner tests.
- Desktop fork: no Live TV work yet, last upstream sync 2026-11-17. GitHub Issues are disabled on the fork; the
  desktop work is written up as 8 briefs (D1-D8) on the Paperclip board (paperclip.kheni.site, company TechMuzz).
- Work is now also queued on Paperclip (Parth's agent board). Paperclip tasks for NuvioTV link the GitHub issue;
  check the issue's labels and open PRs before starting so the factory and Paperclip agents don't both build it.

## Next steps
1. #83 one combined release build (`:app:assembleFullRelease`, which now needs `TMDB_API_KEY` in
   `local.properties`) and test pass of everything merged 2026-11-19/20, emulator now, Chromecast steps queued.
   Failures become new bug issues.
2. #84 revert the orange test icon (#79) after Parth signs off a stable build (`later`).
3. Merge upstream into both forks before each round (`scripts/upstream-sync.sh` for TV), test upstream's new
   features along with ours.
4. Desktop fork Live TV, in order: D1 fork setup (AGENTS.md, PROGRESS.md, upstream sync script, first sync),
   D2 data layer + desktop storage, D3 settings page, D4 Channels + nav + libmpv playback with headers,
   D5 Guide/Sports/On Demand, D6 in-player channel switching, D7 bootstrap + Windows packaging,
   D8 macOS arm64 build in CI. Do not merge Enhanced into it (~1,050 compile errors). Bring the TV fixes over
   (Stalker one stream per MAC, ESPN one day at a time, category-left layout, Down = next channel).

## How to build and test (TV)
- Checks: `bash .factory/build.sh` (Gradle in WSL on a native-disk copy: `:app:assembleFullDebug
  :app:testFullDebugUnitTest`). One Gradle build needs ~13 GB of WSL's ~23 GB: never run two at once.
- `:app:assembleFullRelease` fails with `TMDB_API_KEY missing: metadata will not load` when the key is absent or
  blank; debug builds only warn and still run.
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
passwords, `local.properties` (includes `TMDB_API_KEY`; factory copy is
`~/nuvio/factory/NuvioTV.local.properties`).
