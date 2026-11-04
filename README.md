# Kainos Player

A Material 3 music player for **Android** and **Linux**, built with Kotlin Multiplatform and Compose Multiplatform. Local files, Spotify, and YouTube share a library, queue, and player.

Search Spotify and YouTube together, organize music into Kainos playlists, pin favorites to Home, and play through the app or system media controls. Local playback works without provider credentials.

This README describes the current source on `main`. [Release builds](https://github.com/HuuTrucNguyen0508/Kainos-Player/releases/latest) may be older; check the release notes for the features included in each build.

## Features

| Area | What you can do |
| --- | --- |
| First run | Home shows each source's real state and takes you straight to what it still needs, so you can get to first sound without these docs. |
| Home | Play favorites, resume a listening session, revisit recent tracks, and reorder pins for playlists, local albums, and music folders. Discover Weekly appears when available. |
| Search | Search Spotify and YouTube tracks and playlists together. Matching recordings are grouped, with source selection and fallback handled by the player. Local files have their own Library search. |
| Library | Browse songs, albums, artists, and playlists; use case-insensitive fuzzy search, sorting, **Local files only**, and **Favorites only** filters. |
| Kainos playlists | Create, rename, reorder, and delete app-owned playlists; add the current queue, remove entries, or save a queue as a new playlist. |
| Queue | Use shuffle and repeat, drag or use arrows to reorder, remove entries, and undo removal or clearing. |
| Now Playing | Seek, adjust volume, heart tracks, inspect source and audio quality, retry failed playback or try another source, correct a Spotify track's YouTube cache match, and see or control a paired device. |
| Downloads | See offline availability, download progress, storage use, failures, and cache removals; retry, remove, or pin cached audio. |
| Listening session | Restore the queue, shuffle/repeat state, and position **paused** after restarting. Press Play to resume. |
| Sleep timer | Choose a 5–60 minute preset or pause at the end of the current track from Now Playing. |
| Appearance | Choose the Folio or Caelestia skin, one of seven tints, and light, dark, or system mode. On Linux, Caelestia can follow the shell's live colours. Desktop also has a compact player option. |
| Home sync | Pair phone and desktop by comparing a code, then sync YouTube and local hearts, Kainos playlists, and confirmed local-audio vault transfers over the home network. |

App hearts are separate from **Spotify Liked Songs**. In Library, the filter chips and sort order define the playback queue; typing a search narrows the displayed matches without restricting that queue to the text results. **Play all** / **Play favorites** uses the chip-filtered queue.

Pin playlists and albums with their star buttons in Library; pin folders in Settings. Use **Home → Pinned → Edit** to reorder or unpin. Unpinning leaves the underlying music or playlist intact.

## Platform support

| Capability | Linux | Android |
| --- | --- | --- |
| Local library | Picked folders; `~/Music` by default; optional ffprobe metadata | Persisted SAF folder access; optional MediaStore index |
| Local / HTTP playback | Headless mpv with IPC controls | Media3 / ExoPlayer foreground service |
| Spotify search and library | Spotify Web API + Client ID | Spotify Web API + Client ID |
| Spotify playback | App-managed librespot Connect receiver | In-process librespot-java receiver |
| YouTube search | YouTube Data API v3 key | YouTube Data API v3 key |
| YouTube audio and cache | yt-dlp → mpv | NewPipe Extractor → ExoPlayer |
| System controls | MPRIS, media keys, playerctl | Notification, lock screen, Bluetooth |
| Home sync | HTTPS hub on port `43822`; optional login autostart | Foreground sync client; foreground service for vault transfers |

Spotify playback requires **Premium** and uses unofficial, experimental librespot integrations. YouTube Music account-library sync is not implemented. macOS, Windows, and iOS are not documented supported targets.

## Build and run

Use **JDK 17** (the version used by CI). Android builds also require an Android SDK; the project currently targets/compiles against API 36 and supports **Android 8.0 / API 26 or newer**. Gradle is provided by the wrapper.

```bash
git clone https://github.com/HuuTrucNguyen0508/Kainos-Player.git
cd Kainos-Player
```

### Linux

`mpv` is required for local and YouTube playback; Kainos does not bundle it. FFmpeg's `ffprobe` improves local metadata and audio-quality reporting. yt-dlp is needed for YouTube playback and caching. For example, on Arch Linux:

```bash
sudo pacman -S mpv ffmpeg
./scripts/install-yt-dlp.sh
./gradlew :desktopApp:run
```

For Spotify playback, install Rust/Cargo and ALSA development libraries, then build the pinned librespot receiver:

```bash
./scripts/install-librespot.sh
```

Install a menu launcher with the repository's included icons:

```bash
./scripts/install-desktop-launcher.sh
JAVA_HOME=/path/to/jdk-17 kainos-player
```

The installer creates `~/.local/bin/kainos-player` and a desktop entry. Ensure that directory is on `PATH` and keep the checkout where it was installed: the launcher references its scripts. It defaults to `~/.jdks/temurin-17` unless `JAVA_HOME` is set.

The launcher builds a distributable when one is missing, then starts the packaged jars directly. After changing source, rebuild with:

```bash
kainos-player --rebuild
# Or, always rebuild for development:
./scripts/kainos-player-dev
```

`KAINOS_REBUILD=1` also requests a rebuild. Launcher build/run output is saved in `logs/desktop-run-*.log`, with `logs/desktop-run-latest.log` pointing to the latest run. For a Linux release archive, extract it and run its `./kainos-player` script as described in that release's notes.

Desktop layout adapts to window width: narrow windows use one content pane and a mini player; standard windows have a collapsible, resizable Now Playing pane; wide windows can show Queue alongside the main content and Now Playing. **Settings → Appearance → Compact desktop player** starts with the mini bar.

### Android

Open the project in Android Studio and configure the SDK, or copy `local.properties.example` to `local.properties` and set `sdk.dir`. Then:

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Choose music folders in **Settings → Music sources → Add folder**. Android keeps SAF access grants across restarts. MediaStore is an optional device-wide source; selecting the first explicit folder turns it off, and you can re-enable it in Music sources. Allow music/audio access when using MediaStore.

Playback uses a foreground media service. System pause, seek, skip, and Bluetooth controls drive the shared in-app session. Tapping the music notification opens Now Playing. Back dismisses Now Playing/Queue first, then follows main-tab history; playback continues while navigating.

### Android releases

Published releases are `kainos-player-linux-*.tar.gz` plus an Android debug APK, tagged `linux-android-YYYY-MM-DD`, and only when the owner asks. The debug APK stays the default until the owner has accepted the optimized build on the phone. Check a candidate with the release gate on an emulator:

```bash
scripts/android-release-gate.py --serial emulator-5554
```

The app's version is in **Settings → Advanced**. An older version can't be installed over a newer one with a plain `install -r`, and a build signed by a different key can't update the app at all. See [androidApp/README.md](androidApp/README.md) for rollback and for adopting a release key without uninstalling.

## Local library

Add, remove, refresh, and pin folders in **Settings → Music sources** on both platforms. Linux scans `~/Music` until you configure folders. An explicitly empty folder list stays empty.

On Linux, `KAINOS_MUSIC_DIRS` adds colon-separated folders to the configured roots for that launch:

```bash
KAINOS_MUSIC_DIRS="$HOME/Downloads/Music:/mnt/media/audio" ./gradlew :desktopApp:run
```

Both platforms load a saved library snapshot immediately and rescan in the background. Desktop skips probing files whose path, size, and modification time are unchanged. Artwork uses embedded covers, sidecar `cover.*` / `folder.*`, and Android MediaStore artwork where available; Android extracts embedded covers lazily and caches them.

## Connect providers

Spotify and YouTube both need credentials of your own, because Kainos ships with none. Both are free. Home and **Settings → Music sources** walk you through each step, with the links and the exact redirect URI. Changes apply without restarting and persist on that device.

For desktop file-based setup, copy `secrets.properties.example` to `secrets.properties` and fill in the values, or export `SPOTIFY_CLIENT_ID` and `YOUTUBE_DATA_API_KEY`. Desktop reads `secrets.properties` from its working directory and `~/.universal-music-player/secrets.properties`; the latter works with both the launcher and Gradle run task. Enter credentials in-app on Android: the root `secrets.properties` file is **not bundled into the APK**. Keep real credentials out of Git.

### Spotify

1. Create an app at the [Spotify developer dashboard](https://developer.spotify.com/dashboard).
2. Register `http://127.0.0.1:43821/callback` as the redirect URI for Kainos on Linux and Android.
3. Paste the Client ID into **Settings → Music sources** and choose **Save Client ID**.
4. Choose **Connect** and complete Spotify's sign-in. Kainos uses PKCE; no client secret is required.
5. Choose **Set up in-app Spotify playback** for the receiver's separate one-time sign-in. On Linux this needs librespot installed. Settings shows when it is missing.

Library loads Spotify Liked Songs and playlists; **Refresh Spotify library** reloads them. Spotify playlists can play in-app and be pinned to Home. If Discover Weekly cannot be found through the Web API, paste its share URL or ID into **Discover Weekly playlist link** in Music sources. Fetching it also depends on the librespot integration; desktop needs the optional `kainos-discover-weekly` helper, which `install-librespot.sh` does not install.

On Linux, Kainos starts librespot in the background; Android decodes with librespot-java on-device. Receiver credentials are reused after sign-in. The receiver applies Spotify Loud normalization with +3 dB pregain. `KAINOS_LIBRESPOT` overrides the Linux executable. Spotify's desktop client / Web Playback are fallback paths.

Spotify development-mode restrictions and Web API rate limits can block search, library loading, and transport controls even if the receiver is running. Follow Spotify's [current development-mode guidance](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide). Librespot access can break when Spotify changes its protocol.

### YouTube

Enable **YouTube Data API v3** in a Google Cloud project, create an API key, paste it into Music sources, and choose **Save API key**. Search uses the official API for videos, playlists, and duration metadata; playback resolves audio with yt-dlp on desktop or NewPipe Extractor on Android.

The desktop yt-dlp executable can be selected with `KAINOS_YT_DLP`; the app also checks local installations and `PATH`. **Open YouTube** opens the browser. API quota/credential failures appear in Search, and extractor changes can interrupt playback. There is no connected YouTube Music account library.

**Settings → Playback → Start the source's radio from Search** is on by default. A Search result plays, then continues with its own source's radio: Spotify radio through librespot, or the video's YouTube Mix. Sources are never mixed, and tracks you queue stay ahead of the radio. If radio is unavailable, only the picked track plays. With the switch off, the picked track plays and then the same-source results from the list. On Linux, Spotify radio needs the optional `kainos-discover-weekly` helper.

## Offline audio and Downloads

Hearting a YouTube track queues its audio for caching. Spotify hearts can cache a matched YouTube recording, with direct YouTube hearts taking priority. Local files play from their folders. **Spotify DRM audio is never downloaded**, and a YouTube match is not guaranteed to be the same recording.

Library, Search, and Now Playing show availability separately from hearts: local, cached, downloading, waiting for network, unavailable, or failed. Spotify matches are labeled **Cached · YouTube match**; Now Playing also shows the actual playback source.

Use **Settings → Downloads** to inspect progress, retry failures, remove downloads, or pin entries against automatic eviction. The audio-cache budget is currently 2 GiB; pinned and currently playing entries are protected from budget eviction. **Now Playing → Change YouTube match** lets you choose or clear a replacement while retaining the original Spotify track identity and heart.

Clear metadata/artwork or hearted audio separately in **Settings → Advanced**. A heart is not proof that audio is available offline; wait for its cached status before relying on it.

## Home sync

Home sync uses a desktop HTTPS hub on port `43822`. Pairing compares a code on both screens and pins the hub's certificate.

1. Put the desktop and phone on the same home network, and allow the hub port through your LAN firewall. In **Settings → Devices and sync**, enable home sync on both.
2. On the phone, choose **Pair with a PC** and pick your PC from the list.
3. Check that both screens show the same code, then confirm on each.
4. Choose a writable **Vault folder** on each device if you want local-audio transfers.
5. Choose **Sync now** on the phone. Review missing-file transfers with **Transfer** / **Skip** or **Transfer all** / **Skip all**, and conflicts with **Keep local** / **Keep remote**.

| Data | Current sync behavior |
| --- | --- |
| YouTube and local-file hearts | Merge both ways; local identities rematch by filename when files are present. |
| Kainos playlists | Sync playlist metadata and entries; local entries use portable filename identities. Audio requires a separate vault transfer. |
| Local vault audio | Missing files wait for transfer confirmation. **Vault: hearted tracks only** is enabled by default; disable it to include the full vault. |
| Spotify app hearts | Remain scoped to the Spotify account; excluded from this LAN heart exchange. |
| YouTube cached audio | Cache blobs are not copied by Home sync; each device needs its own cached copy or network access. |
| Home pins | Persist on each device; pin synchronization is not yet shipped. |

The phone checks for sync when the app returns to the foreground; it does not run sync after the app is killed. Desktop can enable **Start hub at login** or run `kainos-player --hub-only` without opening the player window. Paired devices can see and control each other from Now Playing, and **Play here** moves the other device's queue to this one. In-app QR pairing is not implemented. Keep port `43822` private to the home network.

## Playback and quality

**Automatic — Best available** ranks playable sources by known quality tier, then bitrate, then provider preference, with local audio preferred on a remaining tie. Unknown quality ranks below known tiers. A failed source can fall back to another attached source; Now Playing offers retry/source controls.

**Settings → Playback** also offers Prefer lossless, Prefer highest bitrate, Prefer Spotify, and Prefer YouTube Music. Highest-bitrate mode ranks bitrate first; provider preferences break quality ties rather than guaranteeing a provider. Now Playing offers **Try another source** after an error when a fallback is available.

Spotify's source format is not reported by its Web API, and tracks mapped from that API are labeled **Unknown**. The Discover Weekly librespot adapters currently assign a **Lossless** badge using fixed 16-bit / 44.1 kHz metadata; this is an unresolved labeling limitation, not verified source quality. Typical 16-bit / 44.1 kHz Connect output does not establish lossless source quality. **Audio details** shows available format information, the selection reason, Nyquist frequency, and theoretical PCM dynamic range; this is not a measured loudness/dynamic-range analysis.

On Linux, local files play gaplessly, one into the next; there is no switch for it. Spotify and YouTube tracks have a short gap. On Android, all tracks have a short gap. Spotify uses the fixed receiver normalization described above. **Settings → Playback → Even out the volume of local files** (on by default) applies each local file's ReplayGain track gain plus 7 dB, limited by the file's peak, so tagged files sit near Spotify Loud. On Linux the gain is applied by ffmpeg's `volume` filter inside mpv, on the first sample of each gapless file; ffmpeg reads ReplayGain from FLAC, MP3, Ogg Vorbis and MP4 files, not from Opus or Matroska. On Android it is an ExoPlayer volume multiplier, so it can only turn tracks down. Untagged files and YouTube play at their own level; Now Playing → Details says which case applies. Crossfade and lyrics are not implemented. Play next / Add to queue exists in the domain layer but is not exposed by the current Search/Library UI.

The sleep timer is set from Now Playing's bedtime button. End-of-track mode is canceled if you skip that track; timers are not restored after the process exits.

### Desktop controls

| Input | Action |
| --- | --- |
| Space | Play / pause when no text input is focused |
| Ctrl+← / Ctrl+→ | Previous / next when no text input is focused |
| Ctrl+F / Ctrl+K | Open Search when no text input is focused |
| Ctrl+Q | Toggle Queue when no text input is focused |
| Printable key with no text input focused | Open Library search with that character |
| Enter in Library song search | Play the top match within the chip-filtered queue |
| Escape | Clear focused Library search, or dismiss overlays / navigate back |
| Mouse wheel over volume | Adjust playback volume |

## Optional Echo / Alexa gateway

[`tools/echo-gateway`](tools/echo-gateway/README.md) is a separate experimental service for shuffled local-folder playback on an Echo. It uses FFmpeg to transcode audio to AAC, a token-protected stream, Tailscale Funnel HTTPS, and an Alexa custom-skill endpoint. A private **fr-FR / Amazon.fr** skill package is included.

It has its own queue and does not control Kainos's player session, Spotify, or YouTube. Setup and device-validation status are in the gateway docs. Expose only the gateway if using Funnel, never the Home sync hub.

## Development and diagnostics

| Module / directory | Responsibility |
| --- | --- |
| `core:model` | Domain models, persisted DTOs, provider/playback contracts and portable sync identity |
| `core:data` | SQLDelight database/imports, repositories, stores and caches |
| `core:playback` | Queue/session, timers, Android Media3 and desktop mpv/MPRIS |
| `provider:local`, `provider:spotify`, `provider:youtube` | Scanners/folder pickers, provider APIs, librespot, NewPipe and yt-dlp |
| `sync` | HTTPS LAN hub/client, pinned certificates, vault I/O and transfers |
| `shared` | Compose UI, screen presenters, navigation and AppContainer |
| `desktopApp` / `androidApp` | Platform entry points and packaging; project dependency is shared |
| `build-logic` / `test-fixtures` | KMP convention plugin and shared test helpers |
| `scripts` / `tools` | Launch/install helpers, Spotify helper source and standalone Echo gateway |

`PlayerSession` owns transport and queue state across the app and system controls. Providers implement `MusicProvider`; matching and source resolution use common domain contracts. Kotlin packages stay unchanged across module boundaries. See [module ownership](docs/ARCHITECTURE.md).

SQLDelight stores the library, playlists, listening session and local scan in `kainos.db`. Desktop data lives under `~/.universal-music-player/` (or `KAINOS_DATA_DIR`); Android uses its private database directory. Legacy JSON snapshots import once and remain as `*.json.migrated`. Settings, tokens, `meta-cache/` and `audio-cache/` stay as files/SharedPreferences. Persisted playback sessions and playlists store identities and metadata, without resolved streaming URLs.

### Run desktop instances side by side

Three environment variables isolate a desktop instance, so several can run at once without sharing state. The app reads them once at startup. A present but blank variable or an invalid value stops the app with exit status 2 and a message that names the variable.

| Variable | Default | Effect |
| --- | --- | --- |
| `KAINOS_DATA_DIR` | `~/.universal-music-player` | Absolute data directory for `kainos.db`, settings, tokens, caches, librespot state and the Home sync hub key. The app creates it and stops if it cannot be created or written. Helper binaries are still found under `~/.universal-music-player/bin`. |
| `KAINOS_HUB_PORT` | `43822` | Port the Home sync hub listens on and advertises in its pairing URI. Must be 1 to 65535. |
| `KAINOS_INSTANCE` | unset | Appends `.instance<value>` to the MPRIS bus name `org.mpris.MediaPlayer2.kainosplayer`. Letters, digits, `_` and `-` only. The full bus name must be at most 255 characters. |

```bash
KAINOS_DATA_DIR=/tmp/kainos-2/data KAINOS_HUB_PORT=44002 KAINOS_INSTANCE=2 ./gradlew :desktopApp:run
playerctl -p kainosplayer.instance2 play-pause
```

Setting any of these overrides leaves login autostart unchanged and logs `Hub autostart unchanged: isolated instance`. With `KAINOS_DATA_DIR`, the Spotify Web Playback browser profile lives in `<dataDir>/spotify-web-player`. Without it, the profile keeps its XDG cache location.

At startup the app prints one `Kainos data dir … , hub port … , MPRIS …` line with the values in use.

Run the checks used by CI (`jvmTest` covers every module):

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
./gradlew jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable :androidApp:assembleDebug :androidApp:assembleOptimized :androidApp:lintDebug
```

Real-engine tests are reported as skipped unless enabled. `KAINOS_GAPLESS_STRESS=1` runs the gapless stress test against real mpv and ffmpeg, as CI does.

With a connected Android device/emulator:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

`AndroidFirstRunProofTest` depends on app state, so run it through the [release gate](androidApp/README.md#release-gate).

Instrumented tests cover local scanning/playback, controls, completion, missing-file recovery, background service behavior, and simulated Spotify-to-local transitions. They do not authenticate with Spotify or test physical Bluetooth hardware.

Compose UI tests live in `shared/src/jvmTest` and run under `jvmTest` without a display. They host real screens with fake device state and drive them with `runComposeUiTest`, through real clicks, wheel input and asserted text.

Find playback traces in desktop launcher logs or **Settings → Advanced → Playback log**. Android can share the log from that screen; traces also appear under the `KainosTrace` logcat tag.

Further references: [provider limitations](docs/LIMITATIONS.md), [implementation status](docs/CURRENT_STATE.md), [player roadmap](docs/plans/player-experience-roadmap.md), and [Home sync design](docs/plans/home-lan-library-sync.md). Some historical status/plan notes describe earlier behavior; verify them against the source when making changes.
