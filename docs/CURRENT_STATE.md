# Kainos Player — current state

Kotlin Multiplatform Compose music player (`androidApp`, `desktopApp`, `shared`) with unified search, library, queue, and playback.

## Working now

- Shared Material 3 UI themed to the Caelestia shell palette (olive tonalspot): separate light (`#fafaf1` paper / `#4e6634` primary) and dark (`#0d0f0a` / `#b8ce9d` primary) schemes, default Material type, surface containers, chips, and a filled play control
- Home, Search, Library, Settings, Now Playing, Queue
- Local library as a provider (desktop folder scan + Android MediaStore)
- Desktop local scans probe sample rate / bit depth via ffprobe; Now Playing shows Nyquist and theoretical PCM DR
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
- Spotify Discover Weekly is detected from the user’s playlists, pinned in Library → Playlists, shown on Home under Made for you, and plays in-app via Connect/receiver
- Search supports keyboard focus, clearing the query, and cancellation when the query changes; result lists dedupe provider rows so blank/duplicate Spotify playlist stubs cannot crash the UI
- Library has an in-app search field that filters songs, albums, artists, and playlists on Android and desktop
- Now Playing has a return button on mobile, scrolls on short windows, shows loading feedback, and seeks when the slider is released
- Provider labels scroll horizontally on narrow screens
- Settings hides inactive gapless, normalization, and compact-mode switches; these features still need implementation

## Incomplete / platform gaps

- **Spotify (Android)** — in-app librespot-java receiver is wired; live Premium playback and background/FGS survival still need physical-device confirmation. Optional Connect device picker remains available only when explicit-device mode is re-enabled.
- **Spotify (desktop)** — native librespot sign-in, cached headless restart, and audio-backend initialization are verified live; device selection and transfer pass automated tests. Remote state sync remains limited.
- **YouTube Music** — desktop in-app audio when yt-dlp is installed; Android in-app audio via NewPipe Extractor → ExoPlayer; no YouTube Music account library

## Known limits

- Spotify in-app decode uses unofficial librespot / librespot-java (personal use). Protocol changes can break playback.
- Librespot requires Spotify Premium. Spotify Web API quota limits still apply to device lookup and playback commands even when librespot authentication works.
- No custom EQ / DSP
- No gapless playback or volume normalization yet

## Android validation, 2026-09-07

Five instrumented tests pass on the Pixel 10 Pro emulator running Android 17/API 37 for MediaStore scanning, WAV play/pause/seek/completion, missing-file failure and recovery, foreground background-playback service and system pause, and pausing a simulated Spotify controller when switching to local audio. A sixth live smoke resolves a public YouTube video with NewPipe Extractor and plays the audio URL through ExoPlayer until position advances. The app launches successfully; lint has zero errors and one existing target-SDK warning. Shared JVM tests and desktop compilation also pass.

Fixed playback errors getting stuck in buffering, end-of-track becoming paused, missing Android media-session controls, and overlapping Spotify/local playback. Spotify account playback and physical-device/Bluetooth behavior still need device testing. Android YouTube resolves streams with NewPipe Extractor into ExoPlayer; live resolve-and-play was verified on the emulator for a public video id.
