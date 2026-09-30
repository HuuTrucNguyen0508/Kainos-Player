# Phase 4: Offline status and download management

Implemented 2026-09-29.

## What shipped

- `TrackAvailability` separate from favorite: available locally, cached, downloading, waiting for network, unavailable, failed.
- Per-track status in Library / Search / Now Playing via `TrackRow.availability` and `HeartedAudioCacheService.availabilityFor`.
- Settings → Downloads: storage use, progress, retry, remove, pin, recent eviction list.
- Policy unchanged and enforced in UI copy: hearted YT + YT matches for Spotify hearts cacheable; local from folders; never Spotify DRM as downloaded.
- Now Playing shows actual playback source (local file / YouTube cache / YouTube match cache / stream).
- Spotify→YouTube match correction dialog: pick another candidate or clear override; Spotify identity and heart preserved; override stored in hearted audio index v2 (`matchOverrides`).
- Cache eviction: pinned + currently playing owners protected; budget removals recorded in `recentEvictions` (visible in Downloads). Fixed budget double-count when incoming file already on disk.

## Tests

- `TrackAvailabilityTest` (commonTest)
- Extended `HeartedAudioCacheTest` (priority queue, pin protection, match override persistence, playing-owner protection)
- `:shared:jvmTest --tests com.universalmusic.player.data.cache.*` PASS
- `:shared:compileAndroidMain`, `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlinJvm` PASS

## Left / follow-ups

- Live device check of Downloads progress UI and match correction against real YouTube search.
- Optional: network-loss live probe on Android ConnectivityManager path (desktop `isNetworkAvailable` is optimistic true).
