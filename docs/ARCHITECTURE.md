# Kainos Player modules

The storage, identity and module redesign has been on `main` since 2026-10-03. Kotlin packages remain `com.universalmusic.player.*`; directory ownership changed then. This page was last checked against `main` at `9c1fad7` (2026-11-11).

| Module | Owns | Project dependencies |
| --- | --- | --- |
| `core:model` | Domain models, provider/playback contracts, persisted DTOs, portable sync identities/protocol DTOs, platform primitives and playback trace | None |
| `core:data` | SQLDelight database/imports, repositories, settings/auth stores, caches, store factories | `core:model` |
| `core:playback` | PlayerSession, queue, source resolver, session persistence controller, sleep timer, Android Media3 and desktop mpv/MPRIS | `core:model` |
| `provider:local` | Local scanner/provider, artwork, content-key readers and folder pickers | `core:model` |
| `provider:spotify` | Spotify API/auth, librespot and Web Playback hosts | `core:model`, `core:data` |
| `provider:youtube` | YouTube search, NewPipe/yt-dlp resolution and audio download | `core:model`, `core:data` |
| `sync` | LAN coordinator, HTTPS hub/client, certificate pinning, vault I/O and transfers | `core:model`, `core:data`, `provider:local` |
| `shared` | Compose UI, seven presenters, navigation, AppContainer (which wires Search radio to the providers) | All modules above |
| `androidApp`, `desktopApp` | Platform entry points and packaging | `shared` |

`core:*` has no provider or sync implementation dependency. Sync contracts retain their existing `data.sync` package inside `core:model`, so a package name alone does not identify its Gradle module. Store interfaces and persisted DTOs likewise live in `core:model`; storage implementations live in `core:data`. Local hashing stays in `provider:local` and is reused by vault I/O.

Platform expects and actuals are split into `PlatformCoremodel`, `PlatformCoredata`, `PlatformCoreplayback`, `PlatformProviderlocal`, `PlatformProviderspotify`, `PlatformProvideryoutube` and `PlatformSync`, under their owning modules. Android's context is initialized by `initAndroidPlatform`; its setter is private.

`build-logic` supplies `kainos.kmp.library`: JVM and Android targets, SDK levels from the version catalog, serialization/coroutines, and common test dependencies. SQLDelight belongs only to `core:data`; Compose belongs to `shared` and app entry points. Shared test helpers are compiled from `test-fixtures/kotlin` into test source sets, without becoming production API. Tests otherwise moved with their subjects; repository/protocol integration tests live in `sync` where needed.

## AppContainer

`shared/src/commonMain/kotlin/com/universalmusic/player/app/AppContainer.kt` is the one object that builds the running app. Each platform creates it once at startup through `ensureAppContainer()` (desktop `Main.kt`, Android `MainActivity`), keeps it in a global, and hands it to the screens. Nothing else constructs a store, a provider or the player.

In the order it builds them, it creates:

- **Storage.** The token, settings, library, Kainos playlist, listening-session and play-history stores, the metadata and artwork cache, and the `LibraryRepository` and `KainosPlaylistRepository` on top of them. Settings load asynchronously and are exposed as `settings`.
- **Providers.** The local provider (scanner, tag reader, content keys, embedded artwork), Spotify (the Web API provider plus the librespot receiver host), YouTube (the Data API provider plus the yt-dlp or NewPipe stream resolver), and the hearted-audio cache with its download service. It also builds the loudness repository and the background loudness pass.
- **Home sync, remote control and Echo.** The Home sync service (hub and client, pairing, vault and cached-audio copies, loudness exchange), the listening exchange and foreground handoff between devices, the remote-control link, and the Echo now-playing poller (desktop only, and it polls only while something collects it).
- **The `PlayerSession`.** It receives the platform playback engine, the source resolver, the enrichment hook that asks a provider for a stream, the cache lookup that turns a hearted track into a local file, session persistence, and the radio factory. Around it come the sleep timer, the listening-handoff and the play-history tracker.
- **Wiring between them.** It keeps the Now Playing heart in step with the library, fills in a playing local track's embedded artwork and tags, starts the initial library, playlist, history and local-scan loads, restores the session paused, and then sets `ready`.

It then exposes app-wide actions that screens call instead of reaching into those parts: `playTracks`, `playSearchResults`, `startRadio`, `playFavorites`, `playKainosPlaylist`, `playAlbumById`, `playLocalFolder`, `unifiedSearch`, `refreshSpotifyLibrary`, `refreshLocalLibrary`, the hearted-download actions (retry, remove, download, pin, match override, Wi-Fi only), `restoreBackup`, `updateSettings`, the music-folder and vault-folder pickers, `onAppForeground` / `onAppBackground`, and `uiRequests`, the channel desktop shortcuts use to reach the UI. It also exposes the duplicate groups that Hide duplicates uses.

`hubOnly` mode (`kainos-player --hub-only`) builds the same object but leaves out everything that belongs to a window: it binds no media controls, restores and records no listening session, runs no download-connectivity monitor or missing-heart download pass, and never lets remote devices see its player. It only serves Home sync.

`AppContainer.kt` is about 1,250 lines and has no test of its own. Its parts have tests (`RemoteControlTest`, `ForegroundHandoffTest`, the presenters, the stores), but nothing builds the whole object, so the order above and the `hubOnly` exclusions are checked only by running the app.

## Playback units

`PlayerSession` keeps the public transport calls and coordinates transitions. `DesktopPlaybackEngine` keeps mpv start and restart decisions. Each unit below owns its state and is reached only through its own methods. None starts a thread or scope of its own.

| Unit | Owns | Change it for |
| --- | --- | --- |
| `ListeningLedger` | Revision, publication origin, last-active time, remote session, checkpoint timer | What gets recorded and when |
| `QueueRefiller` | The continuation and its refill job | Radio and other queue continuations |
| `RemoteSessionLoader` | The claim, deadline and rollback sequence (no state between calls) | Installing another device's session |
| `SuccessorPrefetcher` | Prefetch target and job, gapless successor, short-lived YouTube result | Getting the next entry ready |
| `TrackStarter` | Nothing between calls; walks prepare, resolve, enrich, engine start and fallback | A track failing to start |
| `PlaybackTurn` | Play generation and completion-armed flag | The guard asynchronous session work re-checks |
| `DesktopTransport` | The engine lock, published state, current handle, start token, user pause | State both engine paths share |
| `MpvSlot` | Active process, transport epoch, IPC FIFO, process termination | Reaching mpv |
| `PositionTicker` | Position anchor, running timestamp, sampling job | Published position |
| `SpotifyConnectPath` | Command queue, pause guard, receiver events, reconcile loop | Spotify transport |
| `GaplessBoundary` | Prepared successor, mpv playlist bindings, crossing state | Local gapless joins |
| `DesktopPlaybackRuntime` | Nothing; the process and socket seam tests fake | Talking to the OS |

The confinement rules do not change with these units. Session units run on the session thread and leave it only through `workContext`, re-checking `PlaybackTurn.generation` on return. Engine units hold `DesktopTransport.lock` for decisions, never across socket I/O, and enqueue IPC while holding it so FIFO order matches decision order.

## Other units

| Unit | Module | Owns |
| --- | --- | --- |
| `VolumeLevelling`, `LoudnessLookup` | `core:model` | The levelling rule: ReplayGain tags win, then a tagged album's aggregate, then the measured track; target -11 LUFS; a true-peak ceiling that limits a boost and never forces a cut |
| `LoudnessRepository`, `track_loudness` | `core:data` | Stored measurements by playback location, their freshness check, and import and export for Home sync |
| `LocalLoudnessPass`, `FfmpegLoudnessAnalyzer` | `provider:local` | The serialized, cancellable ffmpeg `ebur128` pass over new and changed local and cached files (desktop only) |
| `LoudnessSync` | `core:model`, `sync` | The `/kainos-sync/v3/loudness` exchange, matched by content key and then unique basename |
| `DuplicateTracks` | `core:model` | Which listed songs are the same recording from different sources, for Hide duplicates |
| `TrackSort`, `AlbumTrackOrder` | `core:model` | Library sort orders and album track order |
| `LastGoodDiscoverWeekly` | `provider:spotify` | The last Discover Weekly kept per account, so a failed refresh keeps the shelf |
| `ProviderRateLimitedException` | `core:model` | A typed rate limit that `TrackStarter` turns into a pause with a message instead of a skip |
| `HubOwnership`, `HubUserService` | `sync` | Who serves the Home sync hub: the GUI or the systemd user service, the owner lock, and the exit status of a hub-only process |
| `CollectionCatalog`, `CollectionStack` | `shared` | Album, artist and playlist pages, and each tab's stack of them |

## Database migrations

The schema is at version 10. SQLDelight numbers it as one more than the highest migration file, and `core/data/src/commonMain/sqldelight/com/universalmusic/player/data/db/` holds `1.sqm` to `9.sqm`; `N.sqm` upgrades version N to N+1. Verification fixtures for installed versions are in `core/data/src/commonMain/sqldelight/databases/` (`1.db`, `2.db`, `3.db`, `5.db` to `9.db`).

| Migration | Adds |
| --- | --- |
| `1.sqm` | Nullable `heart_op.local_identity_json` |
| `2.sqm` | Session publication origin and the `local_listening` publication |
| `3.sqm` | Listening delivery and refusal tables |
| `4.sqm` | `session_state.remote_session_json` |
| `5.sqm` | `local_listening.shuffle` |
| `6.sqm` | The `play_event` history table |
| `7.sqm` | Home pin order revision and the pin tombstone table |
| `8.sqm` | `session_state.radio_provider`, so a restored queue keeps its radio |
| `9.sqm` | Local file tags on `local_track` (album artist, track and disc number, year, genre, `tags_version`), the `track_loudness` table, the saved Spotify library (`spotify_library_state`, `spotify_liked_track`, `spotify_playlist`, `spotify_playlist_track`) and `home_sync_skip` |

The Android version code is the schema version times 100,000 plus the commit count (`build-logic`, `KainosVersion.kt`), so Android refuses to install a build with an older schema over a newer database. Desktop reports a database written by a newer build with a message that names it.

## Verification

Use JDK 17 and run builds serially to avoid memory pressure:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
./gradlew jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable :androidApp:assembleDebug --console=plain
```

The root `jvmTest` task selector includes every module. `:shared:jvmTest` covers UI/presenters only.

The latest reviews are in `docs/reviews/`. They ran the full JVM suite, the Android host tests, migration verification, Android debug assembly and desktop packaging on their own heads, not on the combined `main` head. `docs/CURRENT_STATE.md` lists what has and has not been checked on hardware. Loopback HTTPS tests cover the hub's keyed v2 and downgraded v1 responses, fallback to a v1-only server, and encrypted vault indexes and blob downloads. Old-APK and old-desktop compatibility is not tested.
