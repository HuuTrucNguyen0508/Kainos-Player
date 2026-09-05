## Learned User Preferences

- Prefer in-app headless playback on Linux (no separate player window such as Tauon)
- Want in-app local library folder pickers in Settings, not only env-based roots
- When launching the desktop app for testing, keep run logs for later debugging
- Want Now Playing to show audio quality for the current track
- Want Spotify Connect wired through a developer Client ID and Settings → Connect

## Learned Workspace Facts

- Kainos Player is a Kotlin Multiplatform Compose app (`androidApp`, `desktopApp`, `shared`) under package `com.universalmusic.player`
- Desktop runs need JDK 17 (commonly `~/.jdks/temurin-17`) via `./gradlew :desktopApp:run`
- Linux local/HTTP playback uses headless mpv with JSON IPC for pause/seek; Spotify playback uses Spotify Connect with the official client
- Android URL playback uses Media3/ExoPlayer; Android local library uses MediaStore
- Local library defaults to `~/Music`; Settings folders persist in `~/.universal-music-player/settings.json`; `KAINOS_MUSIC_DIRS` merges extra roots; on Hyprland the folder picker uses zenity
- Spotify OAuth redirect is `http://127.0.0.1:43821/callback`; put `SPOTIFY_CLIENT_ID` in gitignored `secrets.properties`
- YouTube Music is search/metadata only (no playback); SoundCloud supports search plus progressive stream URLs when configured
- Desktop run logs go under `logs/desktop-run-*.log`, with `logs/desktop-run-latest.log` as the latest symlink
