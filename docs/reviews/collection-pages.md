# Collection pages review

Reviewed 2026-11-11 on `review/collection-pages`, originally `feat/collection-pages` at `5e38e2c`, based on `0988f45`. Main at `2cdbba48` merged in `0d112a6e`. No textual conflicts. Git auto-merged SpotifyProvider and AppContainer; inspection confirmed both the collection APIs and main's receiver/rate-limit fixes remain.

## Fixed findings

| Severity | Location in reviewed result | Scenario and fix | Commit |
| --- | --- | --- | --- |
| P2 | `shared/src/commonMain/kotlin/com/universalmusic/player/ui/screens/collection/CollectionCatalog.kt:111` | A tag pass changes an album's ID while its page is open. Exact-ID filtering emptied the page. Observe the provider's alias map and follow only unambiguous replacements. | `74b90a6b` |
| P2 | `shared/src/commonMain/kotlin/com/universalmusic/player/ui/App.kt:431`, `screens/collection/CollectionStack.kt:77` | Leave Library on a collection, switch tabs, then type. Library reopened its retained page, hiding the typed query. Type-to-search now clears Library's pages before navigating; other tabs retain theirs. | `74b90a6b` |
| P2 | `shared/src/commonMain/kotlin/com/universalmusic/player/ui/screens/collection/CollectionPresenter.kt:206` | A stale reorder request after removing the final entry called coerceIn with an empty range and threw. Reject invalid positions first. | `74b90a6b` |
| P2, inherited from base | `provider/spotify/src/jvmMain/kotlin/com/universalmusic/player/platform/LibrespotEventBridge.kt:42` | Rapid receiver shutdown deletes the event journal before its IO worker opens it. The baseline suite caught FileNotFoundException leaking into an unrelated coroutine test. Open the descriptor synchronously and close it on worker completion, including cancellation before dispatch. | `bff3b08e` |

Each finding has a failing-then-passing regression. Red evidence is in `logs/collection-alias-final-red.log`, `logs/collection-typeahead-red.log`, `logs/collection-move-red.log`, and `logs/collection-bridge-red.log`. The bridge regression deterministically unlinks the journal before dispatching the reader; the baseline log captures the real shutdown race.

`f0755805` adds Frost/Caelestia collection rendering and undo/sync coverage. All review commits include the requested co-author trailer.

## Focus-area results

- Back/Escape code orders dock-drag cancellation, Queue, Now Playing, collection page, inner Library navigation, then main-tab history. Each tab retains its own stack, with globally unique page IDs and separate saveable-state providers. Existing UI tests prove artist-list return scroll and nested page back. Actual Android edge gestures and the full desktop window's Escape dispatch were inspected in source, not driven end to end.
- Typing on a Library collection intentionally returns to Library search. Playlist changes are written immediately, so this does not discard edits. Closing a page cancels pending collection playback loads. The cross-tab hidden-query bug above is fixed.
- Search still filters to Spotify and YouTube. Automatic Search continuation honors searchRadioEnabled; explicit Start radio requests source radio independently of that automatic setting. SourceRadio uses Spotify radio or YouTube Mix, without cross-source/name-search/Liked shuffle fallback. Local tracks do not offer Start radio.
- Artist/album Web API pages use IDs. Artist endpoint failures degrade independently, including 403; no name-search fallback was introduced. No live owner-account Spotify calls were made. Large artist discographies remain limited to the endpoint's first 50 releases, as implemented by this branch.
- restoreEntry uses the normal mutation path, advances the playlist revision, preserves entry identity, persists, survives merge with an older removed snapshot, and does not resurrect a deleted playlist. Added repository test proves these properties. Existing UI test proves remove/undo and menu/drag reorder against repository order; disk persistence and sync use the same mutation path.
- New collection controls have text or content descriptions. Reorder is also available through Move up/down menu items. Search and playlist-name text fields report input focus; the name dialog requests focus on open. No sample production content was added.

## Verification

Run with JDK 17 in this worktree, on a private X display using `scripts/perf/private_xvfb.py` and `~/.local/share/kainos-player/Xvfb`:

```bash
export JAVA_HOME=$HOME/.jdks/temurin-17
./gradlew jvmTest :core:playback:testAndroidHostTest :core:data:verifyCommonMainKainosDatabaseMigration :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
```

Final result: **BUILD SUCCESSFUL**, 1,708 tests discovered, 1,693 passed, 15 skipped, zero failures/errors. Per-module discovered counts: model 126, data 197, playback including Android host 565, local 66, Spotify 104, YouTube 18, sync 386, shared 246. Shared includes seven collection UI tests. Optional fixture/performance tests account for the skips.

Final log: `logs/collection-review-final.log`. APK and desktop distribution were built only under this worktree. No adb, phone access, owner-display windows/input, main-checkout builds, push, PR, or merge into main occurred. The private X server and build commands finished.

Skin checks passed for light Frost at 560×900 and light Caelestia at 1100×900, including visible back/play controls, scrolling and return to Albums. PNGs were inspected:

- `shared/build/collection-pages-proof/frost-album-page.png`
- `shared/build/collection-pages-proof/caelestia-album-page.png`

The test harness now supplies FrostGround, matching the app's wallpaper-free mist fallback. Dark skins, real wallpaper integration, Android touch/edge gestures, exhaustive tab history, and live Spotify authorization/rate-limit behavior were not manually exercised.

## Verdict

Ready to merge with the four confirmed findings fixed and all requested checks passing. Keep the manual-verification limits above visible to the integrator. Do not cherry-pick only the fix commits onto main: the collection feature commits are required; merge this branch after integration approval.
