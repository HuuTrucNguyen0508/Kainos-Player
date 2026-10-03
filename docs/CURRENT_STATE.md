# Kainos Player current state

## Storage and identity redesign, 2026-10-03

All seven screen presenters, SQLDelight import/stores, and row-level library/playlist/scan writes are implemented. Content keys use size plus SHA-256 of the final 64 KiB. Desktop computes them during changed scans and Android fills them after scan with batch traces. Hearts and playlists now export keyed identities, rematch renamed files and retain basename fallback for peers/files without keys. Local unheart operations retain identity across restart and v1 downgrade. Mixed v1/v2 aliases collapse only when basename-to-key mapping is unambiguous. Known different keys never match by name.

Hub and client support v2 hearts/playlists/vault index with 404-only client fallback. v1 downgrade clears keys and restores basename ids. Pairing URI, health and blob routes stay unchanged. v1 stays for one release after the full identity cutover. Database version 2 adds nullable heart-operation identity JSON; version 1 schema fixture and migration verification follow [SQLDelight migration guidance](https://sqldelight.github.io/sqldelight/2.1.0/multiplatform_sqlite/migrations/). Legacy JSON backups remain in place.

The Gradle split is complete: `core:{model,data,playback}`, `provider:{local,spotify,youtube}`, `sync`, and `shared` UI/AppContainer. Packages stay unchanged. Core modules have no provider or sync implementation dependencies. See [module ownership and verification](ARCHITECTURE.md). SQLDelight sources and migration fixtures now live in `core/data`.

All 396 JVM tests, schema migration verification, desktop packaging, Android assembly and Android lint pass. Log: `logs/module-split-final-verification-2026-10-03.log`. APK `release/kainos-player-debug-modular-2026-10-03.apk` installed successfully on the Poco. The packaged desktop app imported real JSON snapshots into database version 2; collection counts match all preserved originals, SQLite checks pass, and its 512-entry session restored paused. Logs: `logs/desktop-real-data-migration-2026-10-03.log` and `logs/desktop-run-20261003-030811.log`.

Live loopback HTTPS compatibility tests pass for keyed v2, downgraded v1, 404 fallback with the pinned client, encrypted vault indexes and blob downloads. Manual old/new phone-desktop sync, full playback/UI checks and Android key-pass cost measurement remain with the user. The redesign commit `2aef455` was merged from `refactor/storage-presenter-integration` into `main` on 2026-10-03 at the user's request. No release was requested. The earlier quota pause was lifted by fresh telemetry on 2026-10-03; both limits were below threshold.

## Working now

- Shared Material 3 UI themed to the Caelestia shell palette (olive tonalspot): separate light (`#fafaf1` paper / `#4e6634` primary) and dark (`#0d0f0a` / `#b8ce9d` primary) schemes, default Material type, surface containers, chips, and a filled play control
- Home, Search, Library, Settings, Now Playing, Queue
- Local library as a provider (desktop folder scan + Android MediaStore)
- Desktop and Android hydrate Library from the local scan tables in `kainos.db` on launch, then rescan in the background. The previous `local-library-cache.json` is imported once. Desktop ffprobe skips unchanged files (path, size, mtime); Now Playing shows Nyquist and theoretical PCM DR under Audio details
- **Local library roots (Milestone 5):** Settings stores an explicit configured-roots flag so empty folder lists do not silently restore `~/Music`. Desktop keeps zenity/kdialog/Swing pickers and run logs. Android uses SAF `OpenDocumentTree` with persistable URI grants, recursive scan, root removal (releases grants), and clear errors when all grants are revoked. MediaStore remains an optional Android source with title/artist/album/duration/size dedupe against SAF.
- **Album identity (Milestone 5):** local albums key on artist + title + directory/group path (not title alone), so same-titled albums no longer collide.
- **Artwork policy (Milestone 5):** shared precedence embedded → sidecar `cover.*`/`folder.*` (bounded ≤2 MiB) → MediaStore album art; used by Library, Now Playing, and system controls via `track.artwork`. Desktop caches unchanged ffprobe metadata by path+size+mtime and extracts embedded covers under `~/.universal-music-player/art-cache/`.
- **User library persistence (Milestone 6):** versioned favorites / remembered / recents; metadata+artwork cache under `~/.universal-music-player/meta-cache/` (desktop) and app files (Android); clear-cache in Settings.
- **Listening session restore (Phase 1):** `kainos.db` stores queue entry ids, provider identities, shuffle/repeat/shuffleOrder, and coarse position. The previous `playback-session.json` is imported once. Restores paused on boot with no auto-play; Play re-resolves and seeks. Never persists stream URLs or Spotify DRM audio.
- **Sleep timer (Phase 7):** Now Playing bedtime control with duration presets (5–60 min) and end-of-current-track mode. Remaining time shown while active; cancel/adjust from the same dialog. Duration expiry calls `PlayerSession.pauseTransport` (notification/Bluetooth agree). End-of-track waits for natural completion of the armed queue item; a skip cancels that mode without pausing. Uses `monotonicElapsedRealtimeMs` and is not persisted (cancels on process death / reboot).
- **Kainos playlists (Phase 3):** `kainos.db` stores app-owned playlists, importing the previous `kainos-playlists.json` once, with stable source identities + display metadata via `PersistedTrack` (never stream URLs). CRUD in Library → Playlists (create / rename / delete / expand / remove entry); Queue → Save as playlist; Home shows Your playlists. Playback goes through existing `PlayerSession` / resolver. Portable `localkey:` identities for Home sync, with `localfile:` fallback; merge is last-write-wins by revision with tombstones on delete. Sync endpoint `/kainos-sync/v2/playlists` (with v1 downgrade for one release) does not auto-copy vault audio.
- **Home pins (Phase 5):** explicit pins for Kainos playlists, Spotify provider playlists, local albums, and music folders. Order persists in the database-backed user library; legacy `user-library.json` format v3 imports once. Home shows Play favorites + Resume near the top, a Pinned section (Edit → reorder/unpin), and Continue listening for recents (separate from pins). Unpin never deletes the playlist/album/folder. Pin from Library (star on playlists/albums) or Settings (star on folders). Home-sync eligibility: Kainos + provider playlist pins are portable; albums and folders stay device-local (`HomePinKind.canHomeSync`). Wire `/pins` endpoint not shipped yet; merge helpers exist for a later sync pass.
- **Offline status & downloads (Phase 4):** `TrackAvailability` is independent of hearts (available locally / cached / downloading / waiting for network / unavailable / failed). Library, Search, and Now Playing show per-track status; Spotify favorites with offline audio label **Cached · YouTube match**. Settings → Downloads lists progress, retry, remove, pin, storage use, and recent budget evictions. Hearted YouTube + YouTube matches for Spotify hearts remain the only cacheable offline audio; Spotify DRM is never presented as downloaded. Now Playing shows the actual playback source and a Change YouTube match dialog (override persists in `audio-cache` index; Spotify identity/heart kept). Pinned and currently playing cache entries are not evicted for storage budget; evictions are recorded for the Downloads UI.
- **Desktop layout (Phase 6):** window bands Narrow (<840dp), Standard, Wide (≥1200dp). Desktop Now Playing side pane is collapsible and drag-resizable (280–560dp); Settings → Appearance **Compact desktop player** uses the mini bar until expanded. On wide windows, Ctrl+Q / Queue opens beside the main content (Library included) while Now Playing stays open; on standard width Queue still replaces the side pane. Narrow desktop width uses a single content pane + mini bar (no permanent side pane) and clears overlays on the breakpoint so resize does not cover Library or restart playback. Tab `SaveableStateHolder` keeps Library scroll across resize. Space / Escape / Ctrl+Q unchanged.
- **Search autoplay (Milestone 7):** `searchAutoplayEnabled` defaults **false**. Spotify radio/`GET /v1/recommendations` is gated per Client ID; for typical post–2024-11-27 development apps the endpoint is forbidden (403/404) — documented here, probed at runtime, and **not** replaced with Liked Songs shuffle.
- Linux desktop playback via headless mpv (local files / HTTP)
- Desktop YouTube audio via yt-dlp URL resolve → mpv (search still uses YouTube Data API)
- Android URL playback via Media3 / ExoPlayer, including YouTube audio resolved with NewPipe Extractor
- Linux Spotify integration uses an app-managed, headless librespot Connect receiver named `Kainos Player`
- Android Spotify uses in-process librespot-java 0.2.0 as the same Connect receiver (`AndroidLibrespotPlaybackHost`), decoding via `AndroidSinkOutput`
- Librespot native OAuth credentials persist under `~/.universal-music-player/librespot/system` on Linux; Android stores credentials under the app private `files/librespot/` directory after browser OAuth (loopback `http://127.0.0.1:5588/login`)
- Quality ranking and source fallback in the shared player session
- Sample catalog for UI/player demos without credentials

## Usability refinements

- Home starts with a recent track or local music when available; demo albums and playlists are labeled as samples
- Library labels sample content and supports playing sample playlists
- **Intended:** Spotify Discover Weekly is detected from `/me/playlists`, pinned in Library → Playlists, shown on Home under Made for you, and plays in-app via Connect/receiver. **Current (2026-09-08):** Discover Weekly was absent from this account’s playlist listing, so Made for you stays empty until Spotify returns it again (separate from playlist-item fetch).
- Search supports keyboard focus, clearing the query, and cancellation when the query changes; result lists dedupe provider rows so blank/duplicate Spotify playlist stubs cannot crash the UI
- **Search (Milestone 4):** Spotify + YouTube only (no local/sample). Empty state points to Settings when neither provider is configured. Library keeps its own local search.
- Library has an in-app search field that filters songs, albums, artists, and playlists on Android and desktop
- Library chips: **Local files only** and **Favorites only** (app hearts, not Spotify Liked); Songs has **Play all** / **Play favorites** for the filtered list
- **Back / Escape (Milestone 4):** Android back and desktop Escape dismiss Queue → Now Playing → tab without finishing the Activity or stopping playback; desktop transport shortcuts ignore text-field focus
- **Queue shuffle (Milestone 4):** Queue UI shows effective playback order (Played / Now playing / Up next); shuffle preserves history by queue-entry id across add/remove/Play next; manual Next escapes Repeat One
- **Local folders (Milestone 5):** pick folders in Settings, restart, and only those roots are indexed. Removing the last desktop root leaves an empty library (no silent `~/Music`). Android SAF needs device/emulator manual checks: revoked persistable URI (expect clear error when no MediaStore fallback tracks), overlapping SAF+MediaStore roots (dedupe), Unicode path/folder names, missing covers, same-titled albums in different folders.
- **Favorites & cache (Milestone 6):** app favorites, remembered tracks, and recents persist in `kainos.db` (legacy `user-library.json` imports once). App hearts stay distinct from Spotify Liked; Spotify-scoped app data is keyed to the signed-in account and cleared on disconnect/account switch. YouTube favorites keep metadata/artwork after restart (no YT account library sync). Bounded metadata/artwork cache with TTL, eviction, and Settings → Clear metadata & artwork cache. Streamed rows show “Needs connection”; local files keep playable locations. Resolved streaming URLs are never stored; librespot audio cache stays disabled.
- **Search autoplay (Milestone 7):** Settings → Playback toggle **Autoplay similar tracks after Search** defaults **off**. When on, finishing a Search-started queue may append **one** continuation batch (YouTube search-based when eligible; Spotify `/recommendations` only if the Client ID gate reports available). Manual Play-next items stay ahead of autoplay appends; obsolete requests cancel on a new play; unavailable recommendations stop cleanly with no retry loop and no Liked Songs shuffle fake. Discover Weekly is unchanged.
- Now Playing has a return button on mobile, scrolls on short windows, shows loading feedback, and seeks when the slider is released
- **Now Playing layout (2026-09-29):** on screens ≥640dp tall the first screen holds artwork, transport, and the heart / repeat / shuffle / queue / sleep row with volume; the provider·quality line and “Playing from …” sit under volume as a footer, and artwork shrinks to fit (min 120dp, phone max 280dp). Availability, Change YouTube match, expanded audio details, error diagnostics, and Spotify output scroll below; opening Details or Diagnostics scrolls to them. Shorter screens scroll everything at a fixed art size.
- Provider labels scroll horizontally on narrow screens
- Settings hides inactive gapless and normalization switches; these features still need implementation; sample catalog toggle applies to Home/Library, not Search
- **Compact desktop player (Phase 6):** Settings → Appearance toggle is live (`compactMode`); collapses the Now Playing side pane in favor of the mini bar

## Playback baseline (Milestone 1, 2026-09-08)

See [`docs/playback-baseline.md`](playback-baseline.md) for the full expected-vs-actual matrix and live probes.

Highlights:

- Local folder configured (`~/Music/Playlist`, hundreds of files); mpv end-of-file exit confirmed; yt-dlp resolve confirmed.
- Spotify liked library pages (**1722** tracks) after token refresh. Liked songs stay on `/me/tracks`.
- Spotify Connect devices visible were Echo speakers only; no live `Kainos Player` receiver during the probe.
- Shared session advances the queue only on armed `EngineStatus.ENDED` (Milestone 2); Spotify engines emit ENDED from track duration when known. Manual Next escapes Repeat One. Queue clear/remove go through the session and stop or retarget audio. Heart follows track identity.
- **Milestone 3 (controls):** Android MediaSession metadata + next/prev/seek bridge to `PlayerSession` (incl. Spotify overlay); Linux MPRIS for `playerctl`. M3 blocker fixes (2026-09-08 follow-up): main-looper Exo access, overlay listener events + idempotent play/pause + clear on stop, engine `playGeneration` on events, MPRIS CanPlay/CanPause/SetPosition/Seeked. Verified `:shared:jvmTest` and emulator `AndroidPlaybackTest` (8/8). HyperOS media notification/island behavior is verified on a Poco F7 Ultra; physical Bluetooth is still pending.
- Android: emulator MediaSession path verified for Spotify notification PLAYING; physical Poco verification covers local playback, artwork, media actions, and notification navigation. Physical-device Bluetooth remains open.

### Spotify playlist issues (track separately)

1. **Playlist fetch endpoint (hotfix 0b):** `getPlaylistTracks` now uses `/v1/playlists/{id}/items` and maps the `item` field (`track` kept as legacy fallback). `/tracks` remains 403 for this Client ID and is no longer called for playlist contents.
2. **Discover Weekly listing:** playlist was missing from `/me/playlists` during the 2026-09-08 probe. Home Made for you stays empty until Spotify returns Discover Weekly in the listing again. Track load for any playlist that *is* listed depends on issue (1), which is fixed in code.
3. **Recommendations / radio (Milestone 7):** `GET /v1/recommendations` is removed for apps registered on/after 2024-11-27 (Spotify Web API change). This project’s development Client ID is expected to receive **403/404**. Runtime probe caches `SpotifyRecommendationsAccess.UNAVAILABLE` and Search autoplay **does not** invent Liked Songs shuffle. YouTube Search continuation still works when autoplay is enabled and the seed is YouTube-eligible. Discover Weekly playlist playback is unchanged.

## Incomplete / platform gaps

- **Echo / Alexa local playback (Phases 1–4 started 2026-09-21):** `tools/echo-gateway` serves loopback AAC streams, token URL for Funnel, and `POST /alexa` AudioPlayer handler. Funnel needs a one-time tailnet enable; Alexa skill is Development-only (see `alexa-skill/README.md`). No `PlayerSession` integration yet.
- **System media controls (Milestone 3)** — code + emulator AndroidPlaybackTest (8/8). HyperOS media notification/island behavior is verified on a Poco F7 Ultra; physical Bluetooth and live Spotify/YouTube notification checks remain open.
- **Spotify (Android)** — in-app librespot-java receiver is wired; live Premium playback and background/FGS survival still need physical-device confirmation. Optional Connect device picker remains available only when explicit-device mode is re-enabled.
- **Spotify (desktop)** — native librespot sign-in, cached headless restart, and audio-backend initialization are verified live; device selection and transfer pass automated tests. Progress still interpolates locally between polls. While Spotify playback is active, the engine reconciles pause, seek, and completion with `GET /v1/me/player` about every four seconds and shows **Playback state unavailable** after repeated misses.
- **Spotify Discover Weekly** — intended Made for you / Library pin still applies; currently blocked by absence from `/me/playlists` for this account (not by item fetch after 0b).
- **Spotify radio / recommendations** — unavailable for this Client ID class; no fake fallback (Milestone 7).
- **YouTube Music** — desktop in-app audio when yt-dlp is installed; Android in-app audio via NewPipe Extractor → ExoPlayer; no YouTube Music account library

## Known limits

- Spotify in-app decode uses unofficial librespot / librespot-java (personal use). Protocol changes can break playback.
- Librespot requires Spotify Premium. Spotify Web API quota limits still apply to device lookup and playback commands even when librespot authentication works.
- No custom EQ / DSP
- No gapless playback or volume normalization yet

## Release validation checklist (post–Milestone 7)

Do **not** publish a GitHub Release or copy the APK to `release/` unless explicitly requested.

1. `./gradlew :shared:jvmTest` (persistence, autoplay controller, queue append, session continuation)
2. `./gradlew :desktopApp:compileKotlinJvm` (or `:desktopApp:run` with JDK 17; keep `logs/desktop-run-*.log`)
3. `./gradlew :androidApp:assembleDebug` and `:androidApp:lintDebug` (or project lint task)
4. Manual Search autoplay: toggle off → queue stops at end; toggle on → one continuation batch for YouTube seed; Spotify-only seed stops cleanly when recommendations gated off
5. Manual Play next before autoplay append → manual items remain ahead of the autoplay tail
6. Linux (if available): `playerctl` metadata / play-pause / next against a desktop run
7. Android physical device (if available): notification island + Bluetooth transport; MediaSession Spotify overlay PLAYING
8. Spot-check: favorites survive restart; local folders after M5; no Home redesign; no protected audio cache dirs

## Android Phase A (update_16), 2026-09-09

P0 fixes for HyperOS device feedback (not yet re-verified on device):

- SAF scan uses `DocumentsContract` child cursors (one query per directory); scan snapshot cache in `kainos.db` (legacy JSON imports once); single-flight refresh; adding the first SAF folder turns MediaStore off by default.
- Library row tap queues the chip-filtered list (text search is display-only); terminal play failure auto-advances when the queue has a next item.
- MediaSession `notifyOverlayChanges` runs for all backends so notification Next stays in sync for local/YouTube.
- Spotify Connect start uses a 20s wall-clock deadline; Spotify command I/O runs off Main; MediaController connect is non-blocking await. Log tags: `KainosSpotify`, `KainosPlayback` (service destroy / task removed).
- Still open for Phase B / device: HyperOS island rebuild on skip, Now Playing layout, Library scroll restore, live Premium ANR confirmation (ask tester for `adb bugreport` if Spotify still freezes).

`:shared:jvmTest`, `:androidApp:assembleDebug`, and `:androidApp:lintDebug` pass after these changes.

## Android Phase B (update_16 UX), 2026-09-09

- MediaSession: metadata/transport publishes are `distinctUntilChanged` on identity; position ticks update overlay only (no MediaItem replace). Spotify silence path prefers `replaceMediaItem`.
- Now Playing: phone art capped at 280.dp; `navigationBarsPadding` + bottom spacer.
- Library: `SaveableStateHolder` per destination; per-tab `LazyListState` via `rememberSaveable`; scroll reset only on sort/needle/chips.
- Library search uses `TrackNormalizer.fold` (accent + case); `UnifiedSearch` trims once.
- HyperOS island stability was still awaiting device confirmation at this stage; see the 2026-09-29 result below.

## HyperOS media notification, 2026-09-29

- The MediaSession now supplies an immutable session activity that opens Now Playing from both a warm task and a cold process. The UI request is buffered until Compose starts, and opening it closes an existing Queue overlay.
- Session metadata includes bounded embedded artwork bytes. On the Poco F7 Ultra, HyperOS used the bitmap for the notification artwork and system-owned tinted media chrome.
- Media3 custom actions expose Favorite and Repeat. Both buttons were visible on the Poco, updated their icon/label after use, and changed the matching in-app state. Previous, play/pause, and next remained available.
- The stock MediaStyle path produced one foreground-service notification and kept the MediaSession token and transports intact. The optional `miui.focus.*` / HyperIsland experiment was therefore not added; its gate required a remaining visual or behavior gap that justified the duplicate-notification and transport risk.
- Verified with a local track on the Poco F7 Ultra: notification tap, cold launch, Queue-to-Now-Playing priority, embedded artwork, Favorite, Repeat All/One/Off, and state restoration after the action checks. Live Spotify, YouTube, lock-screen, Bluetooth, and rapid-skip checks remain open.

## Android validation, 2026-09-07

Five instrumented tests pass on the Pixel 10 Pro emulator running Android 17/API 37 for MediaStore scanning, WAV play/pause/seek/completion, missing-file failure and recovery, foreground background-playback service and system pause, and pausing a simulated Spotify controller when switching to local audio. A sixth live smoke resolves a public YouTube video with NewPipe Extractor and plays the audio URL through ExoPlayer until position advances. The app launches successfully; lint has zero errors and one existing target-SDK warning. Shared JVM tests and desktop compilation also pass.

Fixed playback errors getting stuck in buffering, end-of-track becoming paused, missing Android media-session controls, and overlapping Spotify/local playback. Spotify account playback and physical-device/Bluetooth behavior still need device testing. Android YouTube resolves streams with NewPipe Extractor into ExoPlayer; live resolve-and-play was verified on the emulator for a public video id.
