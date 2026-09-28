# Kainos Player

A Material 3 music player for **Android** and **Linux** with one search, one library, one queue, and one player across local files, Spotify, and YouTube Music.

Search once. Matching recordings are grouped. Playable sources are ranked by quality tier, then bitrate, then provider preference. The queue stores unified tracks, so the provider can change without rebuilding the queue.

## Known limitations

- Spotify’s Web API does not report source quality. The player shows **Unknown** for Spotify tracks. A typical Connect decode is 16-bit / 44.1 kHz output, not proof the source is lossless.
- In-app Spotify decode uses unofficial librespot (Linux) and librespot-java (Android). It needs Spotify Premium and can break if Spotify changes the protocol. Web Playback / the Spotify desktop app is only a fallback.
- YouTube playback resolves audio with yt-dlp (desktop) or NewPipe Extractor (Android). There is no YouTube Music account library.
- Search is Spotify and YouTube only. Local files stay in Library.
- Home sync is on the local network. It mirrors YouTube and local-file hearts. It does not copy Spotify DRM audio.
- The sample catalog is a small demo source. It is not a connected streaming session, and Search does not include it.

Details and credential requirements are in [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

This is a Kotlin Multiplatform project: shared domain, data, and Compose UI, with platform playback behind interfaces.

## Architecture

```
UI (Compose Multiplatform)
        │
        ▼
Domain   Track, Queue, MusicProvider, TrackMatcher, SourceResolver
        │
        ▼
Data     LocalMusicProvider · SpotifyProvider · YouTubeMusicProvider
        │
        ▼
Platform Android Media3 · Linux desktop player · Spotify Connect
```

UI code never calls a provider HTTP API. Adding Tidal, Qobuz, Bandcamp, or local files means implementing `MusicProvider`, not rewriting the app.

## Run on Linux

JDK 17+ is required.

```bash
./gradlew :desktopApp:run
```

Install a menu launcher (uses `~/Pictures/4.png` as the app icon, copied into `desktopApp/icons/`):

```bash
./scripts/install-desktop-launcher.sh
kainos-player
```

That installs `~/.local/bin/kainos-player` and a desktop entry. The installed launcher starts the packaged jars directly. Rebuild with `scripts/kainos-player-dev` or `kainos-player --rebuild` (or `KAINOS_REBUILD=1`). Build and app output are saved in `logs/desktop-run-latest.log`. Desktop also shows the last library snapshot immediately and re-reads only files whose size or modification time changed.

Keyboard shortcuts:

| Key | Action |
| --- | --- |
| Space | Play / pause |
| Ctrl+← / Ctrl+→ | Previous / next |
| Ctrl+F / Ctrl+K | Open Search |
| Ctrl+Q | Toggle the queue panel |

Install `mpv` for in-app local-file and HTTP playback on Linux (headless, no extra window). Pause/seek use mpv’s IPC. For YouTube audio on desktop, install `yt-dlp` (`scripts/install-yt-dlp.sh` or your package manager). For Spotify on Linux, run `scripts/install-librespot.sh` to build the headless receiver. This requires Rust/Cargo and ALSA development libraries. Kainos starts the receiver for playback; Spotify Premium is required.

```bash
sudo pacman -S mpv
```

The local library scans `~/Music` by default whenever the app starts and when you choose **Refresh** in Settings or Library. On Linux you can add or remove folders in **Settings → Local library → Add folder**. Choices are saved in `~/.universal-music-player/settings.json`.

You can still add extra folders for a single launch with `KAINOS_MUSIC_DIRS` (colon-separated). Those are merged with the folders configured in Settings:

```bash
KAINOS_MUSIC_DIRS="$HOME/Downloads/Music:/mnt/media/audio" ./gradlew :desktopApp:run
```

## Run on Android

Open the project in Android Studio, or:

```bash
./gradlew :androidApp:assembleDebug
```

Install `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Local and HTTP audio use a Media3 foreground playback service with system media controls. Spotify on Android uses in-process librespot-java (Premium, personal use), with Connect device selection when that mode is enabled. YouTube search uses the Data API; playback resolves an audio URL with NewPipe Extractor into ExoPlayer.

On first launch, allow music and audio access. The app reads the Android MediaStore index and refreshes the local library after permission is granted; it does not copy audio into the app.

With an emulator or Android device connected, run the playback and library checks:

```bash
JAVA_HOME="$HOME/.jdks/temurin-17" ./gradlew :androidApp:connectedDebugAndroidTest :androidApp:lintDebug
```

The instrumented tests cover MediaStore scanning, local playback controls and completion, recovery from missing audio, background service/system pause, and switching from Spotify to local audio with a simulated Spotify controller. They do not sign in to Spotify or test physical Bluetooth hardware.

## Connect providers

Enter your Spotify Client ID and YouTube Data API key in **Settings → Provider setup**, then choose **Save provider settings**. Changes apply without restarting. Values persist in the device settings file. Empty fields fall back to `secrets.properties` or environment variables.

For file-based setup, copy `secrets.properties.example` to `secrets.properties` (gitignored) or export the same environment variables.

### Spotify

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard).
2. Add redirect URI `http://127.0.0.1:43821/callback` for Linux and Android. The app starts a loopback callback listener before opening the browser, following Spotify’s [redirect URI requirements](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
3. Save the client ID in Settings, or set `SPOTIFY_CLIENT_ID`.
4. In Settings → Providers, connect Spotify and finish the browser login.

No client secret is needed. Login uses PKCE and validates the OAuth state and redirect. After connecting, Library loads your liked songs and playlists. Use **Refresh Spotify library** to reload them. Playlist links open Spotify.

On Linux, Kainos runs [librespot](https://github.com/librespot-org/librespot) as a background Spotify Connect receiver. Its first use requires a separate browser sign-in; later launches reuse private cached credentials. No Spotify player window is needed during playback. Librespot is unofficial and requires Premium. Use `KAINOS_LIBRESPOT` to override the executable; the launcher detects the repository installation automatically. Play, pause, resume, and seek still use Spotify’s Web API, so API rate limits can block these controls and library loading even when the receiver is running. Developer apps must also meet Spotify’s [current development-mode requirements](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

### YouTube Music

Enable **YouTube Data API v3** in your Google Cloud project, create an API key, and save it in Settings or set `YOUTUBE_DATA_API_KEY`. Search returns videos and playlists, with durations fetched from video metadata.

On Linux desktop, install `yt-dlp` (`scripts/install-yt-dlp.sh`) so Search can play audio in-app through mpv. **Open YouTube** still opens the browser. Android resolves audio with NewPipe Extractor into the in-app ExoPlayer service. Account library sync is not implemented.

The adapter uses the official [search](https://developers.google.com/youtube/v3/docs/search/list) and [video metadata](https://developers.google.com/youtube/v3/docs/videos/list) endpoints. Quota and credential errors appear in search. See [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Capability matrix

| | Linux | Android |
| --- | --- | --- |
| Local files | Folder scan, ffprobe, headless mpv | SAF folders, optional MediaStore, ExoPlayer |
| Spotify search and library | Web API + Client ID | Web API + Client ID |
| Spotify playback | librespot Connect receiver (Premium, experimental/unofficial) | librespot-java on device (Premium, experimental/unofficial) |
| YouTube search | Data API key | Data API key |
| YouTube audio | yt-dlp → mpv | NewPipe Extractor → ExoPlayer |
| System media keys | MPRIS / playerctl | Notification, lock screen, Bluetooth |
| Home sync | HTTPS hub on port 43822 | Phone starts sync while the app is open |

Spotify Client ID, YouTube Data API key, and Spotify Premium are required for those providers. Local playback does not need them.

## Quality selection

Default: **Automatic — Best available**.

1. Drop sources that are not playable.
2. Rank verified quality tier. Unverified source quality (Spotify, today) ranks below every known tier and is labeled **Unknown**.
3. Break tier ties with bitrate. A missing bitrate does not outrank a lower number in the same tier, and it does not pull a lower tier above a higher one.
4. On a tie, prefer the user’s preferred provider, then a local file.
5. If start fails, fall back to the next source. Now Playing can retry or try another source.

You can force Spotify or YouTube Music in Settings. Nyquist, theoretical dynamic range, and the selection reason sit under **Audio details**.

## Tests

```bash
./gradlew :shared:jvmTest
```

Coverage includes track matching (ISRC, featuring, remix/live/remaster separation), source ranking, isolated provider search failures, and queue behavior.

## What this build does not do

- No stream ripping, DRM bypass, or unofficial downloads
- No custom DSP / equalizer engine (the OS audio stack is left alone)
- No fake authentication or fake successful playback
- No hardcoded API keys
