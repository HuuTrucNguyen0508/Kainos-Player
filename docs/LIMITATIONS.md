# Provider limitations

Kainos Player isolates every catalog behind `MusicProvider`. When a service cannot legally or technically offer a capability, that limitation is expressed in `ProviderCapabilities` instead of being worked around.

## Spotify

The implementation uses Authorization Code + PKCE, validates callback state and redirect, and calls the official Web API. Credentials can be saved in Settings. Live account authorization and playback still require your own developer app and account.

Supported when `SPOTIFY_CLIENT_ID` is configured and the user signs in:

- Search, tracks, albums, artists, playlists
- Library / liked tracks and user playlists
- Artwork, ISRC, duration, explicit flag
- Compatibility with profiles that omit subscription information, as development-mode responses now do

Playback uses **Spotify Connect** for play, pause, resume, and seek. The app never downloads, decrypts, or unwraps Spotify audio. Premium is required.

On Linux desktop, if no Connect device is listed, the app tries to launch the Spotify client (`spotify`, `/usr/bin/spotify`, or Flatpak `com.spotify.Client`) and waits briefly for a device to appear. It still cannot embed Spotify audio in-process; Connect remains the path.

The app sends playback commands to the active device. It does not poll remote playback state, so changes made directly in Spotify are not synchronized into Kainos Player. Playlist entries open Spotify.

Quality: The Web API does not report per-track format. The app assumes CD-quality Connect output at **16-bit / 44.1 kHz** (Nyquist 22.05 kHz). It still does not invent a lossy bitrate such as 320 kbps.

## Local files

Desktop scanning uses `ffprobe` when available to fill sample rate, bit depth, and bitrate. Now Playing also shows Nyquist (sample rate / 2) and a **theoretical** PCM dynamic-range estimate from bit depth. Measured TT Dynamic Range / loudness range would need full-file analysis and is not implemented yet.

## YouTube Music

There is no official YouTube Music catalog or playback API for third-party players.

This project uses the **official YouTube Data API v3** for search and metadata. On Linux desktop, when `yt-dlp` is available (`KAINOS_YT_DLP`, `~/.local/bin/yt-dlp`, `tools/yt-dlp`, or `PATH`), the player resolves a progressive/adaptive **audio** URL and plays it in headless mpv. That is intentional NewPipe-style stream resolution, not an InnerTube catalog client and not a downloader UI.

Android still opens results in the browser; in-app YouTube audio there is not wired yet. Library sync with a YouTube Music account is not supported.

Save a YouTube Data API key in Settings or set `YOUTUBE_DATA_API_KEY` to enable search. Install yt-dlp with `scripts/install-yt-dlp.sh` (or your package manager) for desktop playback.

## Sample catalog

The in-app sample library is royalty-free SoundHelix audio used so the UI and player can be exercised without credentials. It is labeled as a sample catalog and is not presented as a connected Spotify or YouTube Music session.

## Adding another provider

Implement `MusicProvider` (and `AuthenticatingProvider` if needed), register it in `AppContainer`, and keep provider DTO types inside that package. Domain models stay provider-agnostic.
