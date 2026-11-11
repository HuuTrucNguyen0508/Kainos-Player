# Provider limitations

Kainos Player isolates every catalog behind `MusicProvider`. When a service cannot legally or technically offer a capability, that limitation is expressed in `ProviderCapabilities` instead of being worked around.

## Spotify

The implementation uses Authorization Code + PKCE, validates callback state and redirect, and calls the official Web API. Credentials can be saved in Settings. Live account authorization and playback still require your own developer app and account.

Supported when `SPOTIFY_CLIENT_ID` is configured and the user signs in:

- Search, tracks, albums, artists, playlists
- Library / liked tracks and user playlists
- Artwork, ISRC, duration, explicit flag
- Compatibility with profiles that omit subscription information, as development-mode responses now do

Playback uses **Spotify Connect** for play, pause, resume, and seek. Premium is required.

On **Linux desktop**, Kainos starts a headless [librespot](https://github.com/librespot-org/librespot) Connect receiver named `Kainos Player` that decodes audio on the machine. librespot must be installed with `scripts/install-librespot.sh`, which also builds the `kainos-discover-weekly` helper used for Discover Weekly, playlists and Search radio. Setup is one-time OAuth via Settings → Music sources → Set up in-app Spotify playback. The receiver keeps a 2 GB disk cache and has autoplay off.

On **Android**, Kainos embeds [librespot-java](https://github.com/librespot-org/librespot-java) 0.2.0 (via `lib-librespot-android` sink/decoder modules) as a Connect receiver named `Kainos Player Android`. First setup uses browser OAuth with loopback redirect `http://127.0.0.1:5588/login`; credentials are stored under the app private files and reused on later launches. Audio is decoded on-device through `AndroidSinkOutput` and streams while it downloads. The receiver keeps a 1 GiB disk cache of the encrypted chunks it fetched and clears it when it finds it above that. When the default network changes, an idle receiver is rebuilt once the network settles. This is personal / unofficial use; Spotify may break or revoke access.

The disk cache only makes a replay start from disk. Kainos does not save Spotify audio as a playable download; a Spotify heart caches a YouTube match instead.

If no Kainos receiver is ready and no other Connect device is listed, Linux may try to launch the Spotify client as a fallback.

Quality: The Web API does not report per-track source format. Kainos labels Spotify quality **Unknown** and does not treat Connect output as lossless. A typical decode is still 16-bit / 44.1 kHz; that figure is output format, shown in Audio details, not a source badge. The app does not invent a lossy bitrate such as 320 kbps.

A Spotify rate limit is not worked around. A start waits once for a Retry-After of 5 seconds or less; a longer one pauses playback with a message instead of skipping the track. Web API playlists that refuse the app load through librespot instead, and Discover Weekly, which the Web API refuses most developer apps, loads through librespot too (Android retries after 1, 3 and 8 seconds and keeps the last good list per account).

While Spotify is playing on desktop, Kainos keeps a local clock and reconciles it about every four seconds with `GET /v1/me/player` (pause, seek, and completion). If those reads fail repeatedly, Now Playing shows **Playback state unavailable**.

## Local files

Desktop scanning uses `ffprobe` when available to fill tags (album artist, track and disc number, year, genre), sample rate, bit depth, and bitrate. Android reads tags lazily with `MediaMetadataRetriever` after a scan and on first play, and never opens files during the scan itself. Now Playing also shows Nyquist (sample rate / 2) and a **theoretical** PCM dynamic-range estimate from bit depth. Loudness is measured only to level playback (see below); measured TT Dynamic Range or loudness range is not implemented.

## YouTube Music

There is no official YouTube Music catalog or playback API for third-party players.

This project uses the **official YouTube Data API v3** for search and metadata. On Linux desktop, when `yt-dlp` is available (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, `tools/yt-dlp`, or `PATH`), the player resolves a progressive/adaptive **audio** URL and plays it in headless mpv. That is intentional NewPipe-style stream resolution, not an InnerTube catalog client and not a downloader UI.

On Android, [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) resolves an audio URL for the in-app Media3/ExoPlayer service. Without a YouTube `poToken` provider, some formats may be missing; the resolver picks the best available audio URL and returns null if none appear. Library sync with a YouTube Music account is not supported.

Save a YouTube Data API key in Settings or set `YOUTUBE_DATA_API_KEY` to enable search. Install yt-dlp with `scripts/install-yt-dlp.sh` (or your package manager) for desktop playback. The script updates every copy the app can find, so run it again when Settings → Downloads says the extractor looks out of date; on Android that notice means an app update.

YouTube is kept as a fallback for songs Spotify lacks. It is fixed when it breaks and not extended. A video that cannot be downloaded fails with the extractor's own reason and does not block the rest; transient errors retry after 30 seconds, 2 minutes and 8 minutes, then show Failed.

## Gapless playback and volume

Local files play gaplessly on both platforms: mpv on Linux, and on Android the next file waits in ExoPlayer behind the playing one. A hearted YouTube track whose audio is cached plays as a local file and joins the same way. Streamed Spotify and YouTube tracks have a short gap. There is no crossfade.

The Spotify receiver applies Spotify Loud normalization at +3 dB on both platforms, with no switch. Local files are levelled toward Spotify Loud, -11 LUFS (Settings → Playback, on by default). ReplayGain tags win; a file without tags uses a loudness the desktop measured with ffmpeg, which the phone receives over Home sync, since Android cannot measure. A file the PC never measured, and YouTube streams that are not cached, play at their own level. See `docs/CURRENT_STATE.md` for the details.

## Adding another provider

Implement `MusicProvider` (and `AuthenticatingProvider` if needed), register it in `AppContainer`, and keep provider DTO types inside that package. Domain models stay provider-agnostic.
