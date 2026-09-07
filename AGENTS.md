## Learned User Preferences

- Prefer in-app headless playback on Linux (no separate player window such as Tauon)
- Want in-app local library folder pickers in Settings, not only env-based roots
- When launching the desktop app for testing, keep run logs for later debugging
- Want Now Playing to show audio quality for the current track
- Want Spotify Connect wired through a developer Client ID and Settings → Connect
- Expect Spotify tracks to show as CD-quality 16-bit / 44.1 kHz in Now Playing
- Prefer in-app YouTube audio (yt-dlp / NewPipe-style) instead of only opening the browser
- Prefer less friction for Spotify Connect (auto-start desktop client when no device)

## Learned Workspace Facts

- Kainos Player is a Kotlin Multiplatform Compose app (`androidApp`, `desktopApp`, `shared`) under package `com.universalmusic.player`
- Desktop runs need JDK 17 (commonly `~/.jdks/temurin-17`) via `./gradlew :desktopApp:run`; installable launcher is `kainos-player` with icon from `~/Pictures/4.png`
- Linux local/HTTP playback uses headless mpv with JSON IPC for pause/seek; Spotify uses Connect and auto-transfers to a Computer `device_id` when none is active; desktop also tries to launch Spotify when the device list is empty
- Android URL playback uses Media3/ExoPlayer; Android local library uses MediaStore
- Local library only indexes configured Settings folders (plus `KAINOS_MUSIC_DIRS`); defaults include `~/Music`; paths persist in `~/.universal-music-player/settings.json`; on Hyprland the folder picker uses zenity and must discard stderr so GTK warnings are not saved as paths
- Spotify OAuth redirect is `http://127.0.0.1:43821/callback`; put `SPOTIFY_CLIENT_ID` in gitignored `secrets.properties`
- YouTube search uses the Data API; desktop playback resolves audio with yt-dlp (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, or `tools/yt-dlp`) into mpv; SoundCloud was removed as a provider
- Local desktop scans use ffprobe for sample rate / bit depth; Now Playing shows Nyquist and theoretical PCM dynamic range
- Desktop run logs go under `logs/desktop-run-*.log`, with `logs/desktop-run-latest.log` as the latest symlink
- `docs/CURRENT_STATE.md` tracks what works versus incomplete Spotify/YouTube work
