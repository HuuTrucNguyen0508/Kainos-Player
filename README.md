# Kainos Player

A Material 3 music player for **Android** and **Linux** with one search, one library, one queue, and one player across local files, Spotify, YouTube Music, and SoundCloud.

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
Data     LocalMusicProvider · SpotifyProvider · YouTubeMusicProvider · SoundCloudProvider · sample catalog
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

Keyboard shortcuts:

| Key | Action |
| --- | --- |
| Space | Play / pause |
| Ctrl+← / Ctrl+→ | Previous / next |
| Ctrl+F / Ctrl+K | Open Search |
| Ctrl+Q | Toggle the queue panel |

Install `mpv` for in-app local-file and HTTP playback on Linux (headless, no extra window). Pause/seek use mpv’s IPC. Spotify playback uses Connect and needs the official Spotify client running on a Premium account.

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

Copy `secrets.properties.example` to `secrets.properties` (gitignored) or export the same environment variables.

### Spotify

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard).
2. Add redirect URI `http://127.0.0.1:43821/callback` (Linux) and `universalmusic://spotify-callback` (Android).
3. Put the client ID in `SPOTIFY_CLIENT_ID`.
4. In Settings → Providers, connect Spotify and finish the browser login.

No client secret is stored. PKCE is used. The app never pretends a login succeeded.

### YouTube Music

Set `YOUTUBE_DATA_API_KEY` for official YouTube Data API search. Playback is not offered; see [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

### SoundCloud

Set `SOUNDCLOUD_CLIENT_ID` from an approved SoundCloud application. Progressive stream URLs from the official API are playable.

## Quality selection

Default: **Automatic — Best available**.

1. Drop sources that are not playable.
2. Rank known quality (tier, then bitrate).
3. On a tie, prefer the user’s preferred provider.
4. If start fails, fall back to the next source and show a quiet “Playback source changed” note.

You can force Spotify, YouTube Music, or SoundCloud in Settings.

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
