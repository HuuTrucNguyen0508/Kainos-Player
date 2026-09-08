## Learned User Preferences

- Prefer in-app headless playback on Linux (no separate player window such as Tauon)
- Want in-app local library folder pickers in Settings on desktop and Android, not only env-based roots or MediaStore-only
- When launching the desktop app for testing, keep run logs for later debugging
- Want Now Playing to show audio quality for the current track; expect Spotify tracks as CD-quality 16-bit / 44.1 kHz
- Want Spotify Connect wired through a developer Client ID and Settings → Connect; prefer in-app librespot decode on Linux and Android (personal-use app) without requiring the Spotify app or desktop client (Web Playback / desktop Spotify only as fallback)
- Prefer in-app YouTube audio (yt-dlp on desktop, NewPipe-style on Android) instead of only opening the browser
- Want Search to be Spotify + YouTube only (exclude local files); Library keeps its own search across songs, albums, artists, and playlists
- Want a Library Local files only filter that hides Spotify/saved/sample content
- Want a Library Favorites only filter (app hearts) plus Play favorites / Play all for that list; when not local-only, Library should also surface hearted Spotify and YouTube alongside local folders
- Want Settings → Appearance color scheme picker with multiple light/dark schemes, not a single fixed palette
- When shipping Android builds, copy the debug APK to repo-root `release/` (e.g. `kainos-player-debug-0.1.0.apk`) and publish it to GitHub Releases
- Want Android back / edge-swipe to dismiss Now Playing instead of exiting the app
- Want system media controls (Android notification/island/lock screen/Bluetooth and Linux MPRIS/playerctl) to show track metadata and drive the same in-app queue for local, Spotify, and YouTube

## Learned Workspace Facts

- Kainos Player is a Kotlin Multiplatform Compose app (`androidApp`, `desktopApp`, `shared`) under package `com.universalmusic.player`
- Desktop runs need JDK 17 (commonly `~/.jdks/temurin-17`) via `./gradlew :desktopApp:run`; installable launcher is `kainos-player` with icon from `~/Pictures/4.png`; run logs go under `logs/desktop-run-*.log`, with `logs/desktop-run-latest.log` as the latest symlink
- Linux local/HTTP playback uses headless mpv with JSON IPC for pause/seek; Spotify uses Connect plus a Linux librespot receiver named `Kainos Player` (Web Playback / desktop Spotify only as fallback); system controls expose MPRIS via `MprisController` (dbus-java) for playerctl
- Android URL playback uses Media3/ExoPlayer with a foreground MediaSession service bridged to shared `PlayerSession` (`KainosForwardingPlayer`); Android local library uses SAF folder picks (persistable tree URIs) plus optional MediaStore with dedupe; Android YouTube audio resolves via NewPipe Extractor into ExoPlayer; Android Spotify uses librespot-android 0.2.0 sink/decoder (`AndroidLibrespotPlaybackHost`) as an on-device Connect receiver; Keymaster OAuth redirect `http://127.0.0.1:5588/login` is handled in-app by `SpotifyAuthActivity` with a plain full-screen WebView (not Compose) so HyperOS paints login; androidApp keeps `protobuf-java` for librespot (`GeneratedMessageV3`) and excludes `protobuf-javalite` so NewPipe and librespot coexist
- Local library roots: `localMusicFoldersConfigured` distinguishes “use platform default” from “explicit list (may be empty)”; empty explicit list does not reintroduce `~/Music`. Desktop also honors `KAINOS_MUSIC_DIRS`. Paths/URIs persist in settings (`~/.universal-music-player/settings.json` on desktop); Hyprland folder picker uses zenity and must discard stderr so GTK warnings are not saved as paths. Local artwork policy is embedded → sidecar cover/folder.* → MediaStore album art.
- App favorites / remembered / recents persist in versioned `user-library.json` (desktop `~/.universal-music-player/`, Android app files); Spotify-scoped hearts are account-keyed and distinct from Spotify Liked; metadata/artwork cache lives under `meta-cache/` with Settings clear-cache; never store resolved stream URLs or librespot audio
- Library has in-app search plus a persisted Local files only chip (`libraryLocalOnly`) that limits Songs/Albums/Artists/Playlists to scanned local content on both platforms
- Spotify OAuth redirect is `http://127.0.0.1:43821/callback`; put `SPOTIFY_CLIENT_ID` in gitignored `secrets.properties`; Web Playback needs the `streaming` scope; librespot receivers do not need the streaming scope
- Spotify playlist tracks load via `/v1/playlists/{id}/items` mapping `item` (`/tracks` can 403 for this Client ID); liked songs stay on `/me/tracks`; Discover Weekly is intended from `/me/playlists` on Home Made for you and Library → Playlists but can be absent when Spotify omits it
- YouTube search uses the Data API; desktop playback resolves audio with yt-dlp (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, or `tools/yt-dlp`) into mpv; Android uses NewPipe Extractor; SoundCloud was removed as a provider
- Local desktop scans use ffprobe for sample rate / bit depth; Now Playing shows Nyquist and theoretical PCM dynamic range
- Librespot Access Point traffic (often port 4070) can fail through Tailscale exit nodes or VPNs that block non-HTTP ports; Tailscale mesh without an exit node is usually fine after credentials are saved
- Shared Compose UI is Material 3 with multi-scheme Appearance settings; shared `PlayerSession` owns queue, metadata, and transport commands; `docs/CURRENT_STATE.md` tracks playback/Spotify/YouTube status
