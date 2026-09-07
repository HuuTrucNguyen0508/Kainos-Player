# Agent handoff

Updated 2026-09-07 after Search crash fix and Library search.

## Latest user request

- Search section crashed when typing → fixed (dedupe LazyColumn keys, drop blank Spotify playlist stubs, safer focus request, search on IO).
- Add search in Library on both apps → done in shared `LibraryScreen` (filters songs/albums/artists/playlists).

## Validation

- `logs/search-library-fix.log`: compileJvm + compileAndroid + desktop + androidDebug + jvmTest PASS

## Remaining gaps

- Android Spotify still Connect-only.
- Physical-device confirmation of the Search crash fix still useful.
- Live YouTube smoke previously passed; NewPipe without poToken may miss some formats.
