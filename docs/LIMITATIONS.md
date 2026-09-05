# Provider limitations

Kainos Player isolates every catalog behind `MusicProvider`. When a service cannot legally or technically offer a capability, that limitation is expressed in `ProviderCapabilities` instead of being worked around.

## Spotify

The implementation includes the official Authorization Code + PKCE flow and Web API. The end-to-end Spotify experience is still in progress; these code paths do not mean the integration is complete.

Supported when `SPOTIFY_CLIENT_ID` is configured and the user signs in:

- Search, tracks, albums, artists, playlists
- Library / liked tracks and user playlists
- Artwork, ISRC, duration, explicit flag
- Premium account detection via `/me` (`product`)

Playback uses **Spotify Connect** (`PUT /me/player/play`). That is the supported third-party mechanism. The app never downloads, decrypts, or unwraps Spotify audio. Linux and Android both require an active Spotify Premium device (desktop app, phone, or another Connect target).

Quality: Spotify does not report per-track bitrate on the Web API, so the resolver treats it as unknown. The app never assumes 320 kbps. Lossless is not claimed.

## YouTube Music

There is no official YouTube Music catalog or playback API for third-party players.

This project uses the **official YouTube Data API v3** for search and metadata only. Playback, background playback, and library sync are reported as unsupported. Unofficial InnerTube clients are intentionally not used, so the rest of the app does not depend on undocumented YouTube Music behavior.

Set `YOUTUBE_DATA_API_KEY` to enable search. Results still group with other providers when ISRC or normalized metadata match.

## SoundCloud

Implemented against the official SoundCloud HTTP API. Access is application-approved; without `SOUNDCLOUD_CLIENT_ID` the provider stays `NOT_CONFIGURED`.

When the API returns a progressive `stream_url`, that URL is played through the platform engine. The app does not scrape HTML or bypass SoundCloud authentication.

## Sample catalog

The in-app sample library is royalty-free SoundHelix audio used so the UI and player can be exercised without credentials. It is labeled as a sample catalog and is not presented as a connected Spotify, YouTube Music, or SoundCloud session.

## Adding another provider

Implement `MusicProvider` (and `AuthenticatingProvider` if needed), register it in `AppContainer`, and keep provider DTO types inside that package. Domain models stay provider-agnostic.
