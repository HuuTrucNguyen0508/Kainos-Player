Project review, 6 September 2026 (superseded)

This review is out of date. `docs/CURRENT_STATE.md` describes what works, what is limited and what has been verified, and `docs/LIMITATIONS.md` covers provider limits.

Every High and Medium finding from the September review has since been addressed:

- Spotify progress and track ends come from the in-app librespot receiver's events, not a local timer.
- Spotify plays on a named in-app receiver (`Kainos Player` on Linux, `Kainos Player Android`), so it no longer falls back to an arbitrary speaker. The browser Web Playback host is only a fallback.
- Queue removal goes through `PlayerSession.removeFromQueue`.
- Android forwards Media3 errors (`onPlayerError`) as `EngineStatus.FAILED`, which starts the fallback path.
- The Now Playing heart is set from the current track on every track change.
- Favorites, remembered tracks, recents, playlists and the listening session persist in `kainos.db`.
- Library tabs show an empty state instead of sample content.
- Unreadable or missing music folders are reported as unavailable roots, and their cached tracks are kept.
