# Playback baseline (Milestone 1)

Date: 2026-09-08. Investigation only; no session/engine rewrite.

Configured account live probes used refreshed OAuth tokens under `~/.universal-music-player/tokens.json`. Android had **no adb device** attached, so Android results below are code + prior instrumented tests (2026-09-07 emulator), not a new physical-device run.

## Expected behavior (product contract)

| Event | Expected |
| --- | --- |
| Natural completion | Advance once to the next queue entry (respect shuffle order). |
| Manual Next | Advance to the next queue entry even when Repeat One is on. |
| Manual Previous | Restart current if position > 3s; else previous entry. |
| Repeat One | Natural end restarts the same entry; manual Next leaves it. |
| Repeat All | Wrap at ends. |
| Clear queue | Stop audio and clear Now Playing / available commands. |
| Remove current | Engine follows the new current entry (or stops if empty). |
| Failure | Publish FAILED; try configured source fallbacks; do not revive cancelled jobs. |
| Spotify device transfer | Play on the intended Kainos receiver (or remembered device), not a random speaker. |
| System controls | Notification / lock screen / Bluetooth / `playerctl` drive the same logical queue. |

## Live checks (Linux)

| Surface | Result |
| --- | --- |
| Local library | Settings folder `/home/theadenkingof/Music/Playlist` present; **503** audio files found. |
| mpv end-of-file | Short `--length=1 --keep-open=no` run exited **0** with `Exiting... (End of file)`. Matches desktop engine reliance on process exit for ENDED (not `end-file` IPC). |
| yt-dlp | Public video audio URL resolve succeeded (`yt-dlp` on PATH). |
| Spotify identity | `GET /me` **200** after refresh. |
| Liked paging | `GET /me/tracks` **200**; total **1722**; page0/page1 each 50 items. |
| Playlists list | `GET /me/playlists` **200**; **14** playlists (API total field 15). **No “Discover Weekly”** title in the list at probe time. |
| Playlist tracks API | Owned playlist `Music`: `GET .../tracks` → **403 Forbidden**. `GET .../items` → **200** (items use `item` object, not `track`). |
| Connect devices | Three Echo speakers, **none active**. No `Kainos Player` librespot device (receiver not running during probe). |
| MPRIS (M1) | **Not implemented** at M1 probe time. |
| Queue / Spotify unit tests | `:shared:jvmTest` QueueController + SpotifyProvider suites **BUILD SUCCESSFUL**. |

## Android (this session)

- `adb devices`: empty. No new live local / Spotify / YouTube / notification run.
- Prior emulator evidence (CURRENT_STATE 2026-09-07): ExoPlayer ENDED, FGS, system pause, YouTube NewPipe smoke. Spotify Premium / island skip / Bluetooth still unconfirmed on device.

## Code vs expected (critical deltas)

### Session / queue

- `PlayerSession` treats natural `EngineStatus.ENDED` and manual Next as the same `skipToNext()` path ([`PlayerSession.kt`](../shared/src/commonMain/kotlin/com/universalmusic/player/domain/playback/PlayerSession.kt)).
- `QueueController.nextIndex()` returns the **current** index when `RepeatMode.ONE`, so **manual Next cannot escape Repeat One**.
- `QueueScreen` Clear / Remove call `queue.clear()` / `queue.remove()` directly; engine and `nowPlaying` are not updated → audio can disagree with the highlighted row.
- `playTrack` does not reset or re-derive `favorite` from library identity → heart can carry over.
- End of queue with Repeat Off sets UI paused/idle fields but does **not** call `engine.stop()`.

### Engines

| Backend | Completion | Position | Notes |
| --- | --- | --- | --- |
| Desktop mpv | Process exit → ENDED | 400 ms timer | No `end-file` IPC observe. |
| Desktop Spotify | **Never ENDED** | Timer only | Queue will not auto-advance. |
| Android ExoPlayer | `STATE_ENDED` → ENDED | Listener + ticker | Covered by instrumented test. |
| Android Spotify | **Never ENDED** | Timer only | Same auto-advance gap. |

### System controls (superseded by Milestone 3)

- M1 finding: Android `MediaSession` skip was ExoPlayer-local; Linux had no MPRIS. See Milestone 3 below.

### Spotify playlist `/items` vs Discover Weekly (two separate issues)

1. **Playlist item fetch (hotfix 0b, 2026-09-08):** App `getPlaylistTracks` now calls **`/v1/playlists/{id}/items`** and maps `item` (legacy `track` still accepted). `/tracks` remains **403** for this Client ID and is no longer used for playlist contents. Liked songs stay on `/me/tracks`.
2. **Discover Weekly listing:** During the M1 probe, Discover Weekly was **missing** from `/me/playlists`. Home Made for you stays empty until Spotify returns that playlist in the listing. That is independent of issue (1).

Live acceptance after 0b: owned playlist `Music` (`3OgXqu7vUdfV8uvi509uju`) loads via `/items` with playable track metadata (`id` / `name` on `item`).

## Playback session (Milestone 2, 2026-09-08)

Session owns queue mutations and transitions ([`PlayerSession`](../shared/src/commonMain/kotlin/com/universalmusic/player/domain/playback/PlayerSession.kt)):

- Clear / remove / move go through the session; clear and remove-current stop or retarget the engine so UI matches audio.
- Natural completion and manual Next are separate; manual Next escapes Repeat One.
- Play generations + completion arming drop stale ENDED/FAILED from superseded tracks.
- Heart derives from current track identity via `LibraryRepository.isFavorite` (no carryover).
- Spotify Connect backends receive track `durationMs` on `ProviderPlayback` and emit ENDED when the ticker reaches duration (desktop + Android). Desktop mpv prefers IPC `eof-reached` / `time-pos` when available, with process-exit as fallback.

## System media controls (Milestone 3, 2026-09-08)

Session remains the queue owner. Platform bridges only.

**Android**

- [`AndroidMediaControls`](../shared/src/androidMain/kotlin/com/universalmusic/player/platform/AndroidMediaControls.kt) syncs nowPlaying + queue into Media3 metadata (title, artist, album, art, duration) and skip availability.
- [`KainosForwardingPlayer`](../shared/src/androidMain/kotlin/com/universalmusic/player/platform/KainosForwardingPlayer.kt) routes next/previous/seek (and Spotify play/pause) to `PlayerSession`; URL plays use `mediaItemForUrl` (no bare `MediaItem.fromUri`).
- Spotify/librespot keeps the MediaSession/FGS path with a silence placeholder URI plus session metadata overlay (`setSpotifyActive`).

**Linux**

- [`MprisController`](../shared/src/jvmMain/kotlin/com/universalmusic/player/platform/MprisController.kt) exports `org.mpris.MediaPlayer2.kainosplayer` (metadata, transport, position, shuffle/loop). `playerctl` next/prev/play/pause/seek hit the same session queue. Register on bind; release on desktop window close / unbind. Headless mpv unchanged (no separate player window).

**Verified this session:** `:shared:compileKotlinJvm`, `:shared:compileAndroidMain`, `:androidApp:assembleDebug`, `:shared:jvmTest` including live D-Bus MPRIS (`playerctl -l` sees `kainosplayer`; Metadata + Player.Next drive the same `PlayerSession` queue). No adb device for notification/island/Bluetooth.

**Still needs a physical Android device (or emulator with media controls):** Dynamic Island / notification metadata+art under rapid skip, lock screen, Bluetooth headset next/prev/seek, Spotify overlay surviving FGS while system controls drive the app queue, YouTube URL metadata on the session.

## Explicit non-goals confirmed

- No Home landing redesign (1D).
- No protected audio cache.
- SAF folders, embedded art, favorites persistence, art/metadata cache, Search radio: later.
