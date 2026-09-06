# Kainos Player

A Material 3 music player for **Android** and **Linux** with one search, one library, one queue, and one player across local files, Spotify, and YouTube Music.

Search once. Matching recordings are grouped. The highest-quality playable source starts automatically. The queue stores unified tracks, so the provider can change without rebuilding the queue.

This is a Kotlin Multiplatform project: shared domain, data, and Compose UI, with platform playback behind interfaces.

## Architecture

```
UI (Compose Multiplatform)
        │
        ▼
Domain   Track, Queue, MusicProvider, TrackMatcher, SourceResolver
        │
        ▼
Data     LocalMusicProvider · SpotifyProvider · YouTubeMusicProvider · sample catalog
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

That installs `~/.local/bin/kainos-player` and a desktop entry. The first launch packages a native distributable, then starts it.

Keyboard shortcuts:

| Key | Action |
| --- | --- |
| Space | Play / pause |
| Ctrl+← / Ctrl+→ | Previous / next |
| Ctrl+F / Ctrl+K | Open Search |
| Ctrl+Q | Toggle the queue panel |

Install `mpv` for in-app local-file and HTTP playback on Linux (headless, no extra window). Pause/seek use mpv’s IPC. For YouTube audio on desktop, install `yt-dlp` (`scripts/install-yt-dlp.sh` or your package manager). Spotify playback uses Connect; on Linux the app will try to start the Spotify client if no Connect device is listed. Premium is required.

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

Install the debug APK. Background playback uses Media3 / ExoPlayer for HTTP sources and Spotify Connect for Spotify. Lock-screen and Bluetooth controls follow the Media3 session when a URL is playing.

On first launch, allow music and audio access. The app reads the Android MediaStore index and refreshes the local library after permission is granted; it does not copy audio into the app.

## Connect providers

Enter your Spotify Client ID and YouTube Data API key in **Settings → Provider setup**, then choose **Save provider settings**. Changes apply without restarting. Values persist in the device settings file. Empty fields fall back to `secrets.properties` or environment variables.

For file-based setup, copy `secrets.properties.example` to `secrets.properties` (gitignored) or export the same environment variables.

### Spotify

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard).
2. Add redirect URI `http://127.0.0.1:43821/callback` for Linux and Android. The app starts a loopback callback listener before opening the browser, following Spotify’s [redirect URI requirements](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
3. Save the client ID in Settings, or set `SPOTIFY_CLIENT_ID`.
4. In Settings → Providers, connect Spotify and finish the browser login.

No client secret is needed. Login uses PKCE and validates the OAuth state and redirect. After connecting, Library loads your liked songs and playlists. Use **Refresh Spotify library** to reload them. Playlist links open Spotify.

Play, pause, resume, and seek control your active Spotify Connect device. On Linux desktop, if no device is listed, Kainos tries to launch Spotify and wait for Connect. Premium is required for playback. Developer apps must also meet Spotify’s [current development-mode requirements](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

### YouTube Music

Enable **YouTube Data API v3** in your Google Cloud project, create an API key, and save it in Settings or set `YOUTUBE_DATA_API_KEY`. Search returns videos and playlists, with durations fetched from video metadata.

On Linux desktop, install `yt-dlp` (`scripts/install-yt-dlp.sh`) so Search can play audio in-app through mpv. **Open YouTube** still opens the browser. Android remains browser-only for YouTube. Account library sync is not implemented.

The adapter uses the official [search](https://developers.google.com/youtube/v3/docs/search/list) and [video metadata](https://developers.google.com/youtube/v3/docs/videos/list) endpoints. Quota and credential errors appear in search. See [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Quality selection

Default: **Automatic — Best available**.

1. Drop sources that are not playable.
2. Rank known quality (tier, then bitrate).
3. On a tie, prefer the user’s preferred provider.
4. If start fails, fall back to the next source and show a quiet “Playback source changed” note.

You can force Spotify or YouTube Music in Settings.

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
