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

On **Linux desktop**, Kainos starts a headless [librespot](https://github.com/librespot-org/librespot) Connect receiver named `Kainos Player` that decodes audio on the machine. Setup is one-time OAuth via Settings → Set up in-app Spotify playback.

On **Android**, Kainos embeds [librespot-java](https://github.com/librespot-org/librespot-java) 0.2.0 (via `lib-librespot-android` sink/decoder modules) as the same Connect receiver. First setup uses browser OAuth with loopback redirect `http://127.0.0.1:5588/login`; credentials are stored under the app private files and reused on later launches. Audio is decoded on-device through `AndroidSinkOutput`. This is personal / unofficial use; Spotify may break or revoke access.

If no Kainos receiver is ready and no other Connect device is listed, Linux may try to launch the Spotify client as a fallback. Remote player state is not polled, so changes made directly in Spotify are not synchronized into Kainos Player.

Quality: The Web API does not report per-track format. The app assumes CD-quality Connect output at **16-bit / 44.1 kHz** (Nyquist 22.05 kHz). It still does not invent a lossy bitrate such as 320 kbps.

## Local files

Desktop scanning uses `ffprobe` when available to fill sample rate, bit depth, and bitrate. Now Playing also shows Nyquist (sample rate / 2) and a **theoretical** PCM dynamic-range estimate from bit depth. Measured TT Dynamic Range / loudness range would need full-file analysis and is not implemented yet.

## YouTube Music

There is no official YouTube Music catalog or playback API for third-party players.

This project uses the **official YouTube Data API v3** for search and metadata. On Linux desktop, when `yt-dlp` is available (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, `tools/yt-dlp`, or `PATH`), the player resolves a progressive/adaptive **audio** URL and plays it in headless mpv. That is intentional NewPipe-style stream resolution, not an InnerTube catalog client and not a downloader UI.

On Android, [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) resolves an audio URL for the in-app Media3/ExoPlayer service. Without a YouTube `poToken` provider, some formats may be missing; the resolver picks the best available audio URL and returns null if none appear. Library sync with a YouTube Music account is not supported.

Save a YouTube Data API key in Settings or set `YOUTUBE_DATA_API_KEY` to enable search. Install yt-dlp with `scripts/install-yt-dlp.sh` (or your package manager) for desktop playback.

## Sample catalog

The in-app sample library is royalty-free SoundHelix audio used so the UI and player can be exercised without credentials. It is labeled as a sample catalog and is not presented as a connected Spotify or YouTube Music session.

## Adding another provider

Implement `MusicProvider` (and `AuthenticatingProvider` if needed), register it in `AppContainer`, and keep provider DTO types inside that package. Domain models stay provider-agnostic.
