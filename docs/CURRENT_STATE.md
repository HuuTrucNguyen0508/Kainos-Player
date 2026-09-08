# Kainos Player — current state

Kotlin Multiplatform Compose music player (`androidApp`, `desktopApp`, `shared`) with unified search, library, queue, and playback.

## Working now

- Shared Material 3 UI themed to the Caelestia shell palette (olive tonalspot): separate light (`#fafaf1` paper / `#4e6634` primary) and dark (`#0d0f0a` / `#b8ce9d` primary) schemes, default Material type, surface containers, chips, and a filled play control
- Home, Search, Library, Settings, Now Playing, Queue
- Local library as a provider (desktop folder scan + Android MediaStore)
- Desktop local scans probe sample rate / bit depth via ffprobe; Now Playing shows Nyquist and theoretical PCM DR
- **Local library roots (Milestone 5):** Settings stores an explicit configured-roots flag so empty folder lists do not silently restore `~/Music`. Desktop keeps zenity/kdialog/Swing pickers and run logs. Android uses SAF `OpenDocumentTree` with persistable URI grants, recursive scan, root removal (releases grants), and clear errors when all grants are revoked. MediaStore remains an optional Android source with title/artist/album/duration/size dedupe against SAF.
- **Album identity (Milestone 5):** local albums key on artist + title + directory/group path (not title alone), so same-titled albums no longer collide.
- **Artwork policy (Milestone 5):** shared precedence embedded → sidecar `cover.*`/`folder.*` (bounded ≤2 MiB) → MediaStore album art; used by Library, Now Playing, and system controls via `track.artwork`. Desktop caches unchanged ffprobe metadata by path+size+mtime and extracts embedded covers under `~/.universal-music-player/art-cache/`.
- **User library persistence (Milestone 6):** versioned favorites / remembered / recents; metadata+artwork cache under `~/.universal-music-player/meta-cache/` (desktop) and app files (Android); clear-cache in Settings.
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
- **Favorites & cache (Milestone 6):** app favorites, remembered tracks, and recents persist in a versioned on-disk snapshot (`user-library.json` / Android files dir). App hearts stay distinct from Spotify Liked; Spotify-scoped app data is keyed to the signed-in account and cleared on disconnect/account switch. YouTube favorites keep metadata/artwork after restart (no YT account library sync). Bounded metadata/artwork cache with TTL, eviction, and Settings → Clear metadata & artwork cache. Streamed rows show “Needs connection”; local files keep playable locations. Resolved streaming URLs are never stored; librespot audio cache stays disabled.
- **Search autoplay (Milestone 7):** Settings → Playback toggle **Autoplay similar tracks after Search** defaults **off**. When on, finishing a Search-started queue may append **one** continuation batch (YouTube search-based when eligible; Spotify `/recommendations` only if the Client ID gate reports available). Manual Play-next items stay ahead of autoplay appends; obsolete requests cancel on a new play; unavailable recommendations stop cleanly with no retry loop and no Liked Songs shuffle fake. Discover Weekly is unchanged.
- Now Playing has a return button on mobile, scrolls on short windows, shows loading feedback, and seeks when the slider is released
- Provider labels scroll horizontally on narrow screens
- Settings hides inactive gapless, normalization, and compact-mode switches; these features still need implementation; sample catalog toggle applies to Home/Library, not Search

## Playback baseline (Milestone 1, 2026-09-08)

See [`docs/playback-baseline.md`](playback-baseline.md) for the full expected-vs-actual matrix and live probes.

Highlights:

- Local folder configured (`~/Music/Playlist`, hundreds of files); mpv end-of-file exit confirmed; yt-dlp resolve confirmed.
- Spotify liked library pages (**1722** tracks) after token refresh. Liked songs stay on `/me/tracks`.
- Spotify Connect devices visible were Echo speakers only; no live `Kainos Player` receiver during the probe.
- Shared session advances the queue only on armed `EngineStatus.ENDED` (Milestone 2); Spotify engines emit ENDED from track duration when known. Manual Next escapes Repeat One. Queue clear/remove go through the session and stop or retarget audio. Heart follows track identity.
- **Milestone 3 (controls):** Android MediaSession metadata + next/prev/seek bridge to `PlayerSession` (incl. Spotify overlay); Linux MPRIS for `playerctl`. M3 blocker fixes (2026-09-08 follow-up): main-looper Exo access, overlay listener events + idempotent play/pause + clear on stop, engine `playGeneration` on events, MPRIS CanPlay/CanPause/SetPosition/Seeked. Verified `:shared:jvmTest` and emulator `AndroidPlaybackTest` (8/8). Physical Bluetooth / island polish still pending.
- Android: emulator MediaSession path verified for Spotify notification PLAYING; physical-device Bluetooth still open.

### Spotify playlist issues (track separately)

1. **Playlist fetch endpoint (hotfix 0b):** `getPlaylistTracks` now uses `/v1/playlists/{id}/items` and maps the `item` field (`track` kept as legacy fallback). `/tracks` remains 403 for this Client ID and is no longer called for playlist contents.
2. **Discover Weekly listing:** playlist was missing from `/me/playlists` during the 2026-09-08 probe. Home Made for you stays empty until Spotify returns Discover Weekly in the listing again. Track load for any playlist that *is* listed depends on issue (1), which is fixed in code.
3. **Recommendations / radio (Milestone 7):** `GET /v1/recommendations` is removed for apps registered on/after 2024-11-27 (Spotify Web API change). This project’s development Client ID is expected to receive **403/404**. Runtime probe caches `SpotifyRecommendationsAccess.UNAVAILABLE` and Search autoplay **does not** invent Liked Songs shuffle. YouTube Search continuation still works when autoplay is enabled and the seed is YouTube-eligible. Discover Weekly playlist playback is unchanged.

## Incomplete / platform gaps

- **System media controls (Milestone 3)** — code + emulator AndroidPlaybackTest (8/8) and JVM suite green after follow-up fixes. Physical Bluetooth / HyperOS island still need a device.
- **Spotify (Android)** — in-app librespot-java receiver is wired; live Premium playback and background/FGS survival still need physical-device confirmation. Optional Connect device picker remains available only when explicit-device mode is re-enabled.
- **Spotify (desktop)** — native librespot sign-in, cached headless restart, and audio-backend initialization are verified live; device selection and transfer pass automated tests. Queue auto-advance uses duration-based ENDED when track length is known; remote pause/seek/transfer sync is still incomplete versus a full Connect state poll.
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

## Android validation, 2026-09-07

Five instrumented tests pass on the Pixel 10 Pro emulator running Android 17/API 37 for MediaStore scanning, WAV play/pause/seek/completion, missing-file failure and recovery, foreground background-playback service and system pause, and pausing a simulated Spotify controller when switching to local audio. A sixth live smoke resolves a public YouTube video with NewPipe Extractor and plays the audio URL through ExoPlayer until position advances. The app launches successfully; lint has zero errors and one existing target-SDK warning. Shared JVM tests and desktop compilation also pass.

Fixed playback errors getting stuck in buffering, end-of-track becoming paused, missing Android media-session controls, and overlapping Spotify/local playback. Spotify account playback and physical-device/Bluetooth behavior still need device testing. Android YouTube resolves streams with NewPipe Extractor into ExoPlayer; live resolve-and-play was verified on the emulator for a public video id.
