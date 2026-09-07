## Learned User Preferences

- Prefer in-app headless playback on Linux (no separate player window such as Tauon)
- Want in-app local library folder pickers in Settings, not only env-based roots
- When launching the desktop app for testing, keep run logs for later debugging
- Want Now Playing to show audio quality for the current track
- Want Spotify Connect wired through a developer Client ID and Settings → Connect
- Expect Spotify tracks to show as CD-quality 16-bit / 44.1 kHz in Now Playing
- Prefer in-app YouTube audio (yt-dlp on desktop, NewPipe-style on Android) instead of only opening the browser
- Prefer Spotify on Linux without the Spotify desktop client (Web Playback SDK in Brave/Chromium as a Connect device; auto-start desktop Spotify only as fallback)
- Want in-app Library search that filters songs, albums, artists, and playlists on Android and desktop
- When shipping Android builds, publish a debug APK to GitHub Releases

## Learned Workspace Facts

- Kainos Player is a Kotlin Multiplatform Compose app (`androidApp`, `desktopApp`, `shared`) under package `com.universalmusic.player`
- Desktop runs need JDK 17 (commonly `~/.jdks/temurin-17`) via `./gradlew :desktopApp:run`; installable launcher is `kainos-player` with icon from `~/Pictures/4.png`; run logs go under `logs/desktop-run-*.log`, with `logs/desktop-run-latest.log` as the latest symlink
- Linux local/HTTP playback uses headless mpv with JSON IPC for pause/seek; Spotify uses Connect plus a Linux Web Playback host (Brave Origin preferred for Widevine) as a Connect device, with auto-transfer to a Computer `device_id` when none is active; desktop may still launch Spotify when the device list is empty
- Android URL playback uses Media3/ExoPlayer with a foreground MediaSession service; Android local library uses MediaStore; Android YouTube audio resolves via NewPipe Extractor into ExoPlayer
- Local library only indexes configured Settings folders (plus `KAINOS_MUSIC_DIRS`); defaults include `~/Music`; paths persist in `~/.universal-music-player/settings.json`; on Hyprland the folder picker uses zenity and must discard stderr so GTK warnings are not saved as paths
- Library has an in-app search field that filters songs, albums, artists, and playlists on both platforms
- Spotify OAuth redirect is `http://127.0.0.1:43821/callback`; put `SPOTIFY_CLIENT_ID` in gitignored `secrets.properties`; Web Playback needs the `streaming` scope
- Spotify playlists play in-app via paged playlist tracks (not a browser hop); Discover Weekly is detected from `/me/playlists`, pinned on Home (Made for you) and Library → Playlists
- YouTube search uses the Data API; desktop playback resolves audio with yt-dlp (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, or `tools/yt-dlp`) into mpv; Android uses NewPipe Extractor; SoundCloud was removed as a provider
- Local desktop scans use ffprobe for sample rate / bit depth; Now Playing shows Nyquist and theoretical PCM dynamic range
- Shared Compose UI follows Material 3 with the Caelestia shell tonalspot olive palette: distinct light (`#fafaf1` / `#4e6634`) and dark (`#0d0f0a` / `#b8ce9d`) schemes, default Material type, surface containers, and chips
- `docs/CURRENT_STATE.md` tracks what works versus incomplete Spotify/YouTube work
