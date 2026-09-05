# Kainos Player — current state

Kotlin Multiplatform Compose music player (`androidApp`, `desktopApp`, `shared`) with unified search, library, queue, and playback.

## Working now

- Shared Material 3 UI: Home, Search, Library, Settings, Now Playing, Queue
- Local library as a provider (desktop folder scan + Android MediaStore)
- Linux desktop playback via headless mpv (local files / HTTP)
- Android URL playback via Media3 / ExoPlayer
- Quality ranking and source fallback in the shared player session
- Sample catalog for UI/player demos without credentials
- SoundCloud provider path exists when `SOUNDCLOUD_CLIENT_ID` is set (search + progressive streams)

## Usability refinements

- Home starts with a recent track or local music when available; demo albums and playlists are labeled as samples
- Library labels sample content and supports playing sample playlists
- Search supports keyboard focus, clearing the query, and cancellation when the query changes
- Now Playing has a return button on mobile, scrolls on short windows, shows loading feedback, and seeks when the slider is released
- Provider labels scroll horizontally on narrow screens
- Settings hides inactive gapless, normalization, and compact-mode switches; these features still need implementation

## Not done yet (needs implementation)

- **Spotify** — not finished; still needs to be implemented end-to-end
- **YouTube Music** — not finished; still needs to be implemented (search/metadata alone is not a full player path)

## Known limits

- No stream ripping, DRM bypass, or unofficial downloads
- No custom EQ / DSP
- No gapless playback or volume normalization yet
- Full Spotify and YouTube Music experiences stay out of scope until those providers are properly implemented
