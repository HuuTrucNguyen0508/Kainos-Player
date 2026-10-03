> Current status (2026-10-03): read [HANDOVER.md](HANDOVER.md) first. Keyed identity/sync and module extraction are complete; 396 tests, migration verification, Android assembly and desktop packaging pass. The final modular APK is installed. The redesign was committed as `2aef455` and merged into main at the user's request on 2026-10-03. This file retains earlier history.

# Handover: storage, identity, and module redesign

**Date:** 2026-10-02
**Checkout:** `refactor/storage-presenter-integration` (dirty, storage + presenters uncommitted)
**`main` / `origin/main`:** `c8ca79e` — *Gate persistence on load, add album light, and keep learned durations*
**Nothing from this redesign is pushed.**

Plan (approved): [`~/.cursor/plans/Storage identity modules redesign-961d0bc9.plan.md`](/home/theadenkingof/.cursor/plans/Storage%20identity%20modules%20redesign-961d0bc9.plan.md)

Goal: implement the three redesign items from the earlier architecture discussion — (3) SQLDelight instead of versioned JSON piles, (4) content-key local track identity + sync v2 with one-release v1 hub compat, (5) screen presenters then Gradle module split.

---

## Branch map

| Branch | Base | State | Contents |
|---|---|---|---|
| `main` | — | clean, matches origin | Pre-redesign. Last ship: load gate for sync, album light, learned durations. |
| `refactor/library-presenter` | `main` | **committed** `5af97ff`, not pushed | Library screen → presenter + pure catalog + split files + tests. |
| `refactor/home-nowplaying-presenters` | `main` | **committed** `576a9d1`, not pushed | Home + Now Playing → presenters + content fns + split files + tests. |
| `refactor/sqldelight-storage` | `main` | tip = `c8ca79e`; no storage commit | Original branch. Its WIP was preserved before integration. |
| `refactor/storage-presenter-integration` | `main` | tip = `c8ca79e`; **all combined work uncommitted** | SQLDelight WIP + all seven screen presenters + row-level writes; unused Koin removed; docs updated. |

The original refactor branches do **not** contain each other. The current integration working tree applies the presenter diffs from `5af97ff` and `576a9d1` on top of the SQLDelight WIP. There are no merge commits; `main` and the original branches are unchanged. Preserve the integration WIP before switching branches. The pre-integration storage changes and original handover are backed up in `/tmp/kainos-pre-integration-hfbjipld/` as `tracked.patch` and `untracked.tar.gz`.

---

## Decisions already locked

1. **Local identity:** content key = `lc1:` + hex(SHA-256(size as 8 bytes || last 64 KB of file)). Survives renames; distinguishes same-named files. Desktop at scan; Android background pass after scan (SAF does not open files during scan).
2. **Sync compat:** hub serves `/kainos-sync/v1` *and* `/kainos-sync/v2` for one release. Client tries v2, falls back to v1 on 404. Pairing URI `kainos-homesync:2` unchanged.
3. **SQLDelight 2.4.0** works with Kotlin 2.4.10 + AGP 9 `com.android.kotlin.multiplatform.library`. No Room fallback needed. No `android.newDsl=false` workaround required for this project.
4. **Presenter pattern (actual, not ViewModel):** plain `XxxPresenter` class taking specific `StateFlow`s + lambdas + `CoroutineScope` (not whole `AppContainer`), factory `from(container, scope)`, `StateFlow<XxxUiState>`, public screen signatures unchanged so `App.kt` need not change. Kotlin package stays `com.universalmusic.player.ui.screens`; files live under `ui/screens/<name>/`.
5. **What stays files:** settings, tokens, `meta-cache/`, `audio-cache/`. Not migrated to SQLite.

---

## Phase status

### Phase 1 — screen presenters (item 5 part 1)

| Screen | Status |
|---|---|
| Library | Done on `refactor/library-presenter`; applied to integration working tree |
| Home | Done on `refactor/home-nowplaying-presenters`; applied to integration working tree |
| Now Playing | Done on `refactor/home-nowplaying-presenters`; applied to integration working tree |
| Settings (sectioned) | Done; preferences, folders, connect, sync and advanced presenters |
| Search (replace mutable `SearchUiState`) | Done; immutable state, cancellation and credential-driven refresh tested |
| Queue, Downloads | Done; queue actions use live entry identity and downloads read live pin state |
| Drop unused Koin deps | Done in integration working tree; no source callers |
| Merge → `main`, desktop run, phone install | Integration builds pass; `main` merge and desktop live run pending. Phone installation recorded below. |

**Library (`5af97ff`):** deletes 1,529-line monolith; adds `ui/screens/library/` (`LibraryPresenter`, `LibraryCatalog`, `LibraryUiState`, tabs, dialogs, ledger) + `LibraryCatalogTest` / `LibraryPresenterTest`. Chips still define the play queue; search is display-only fuzzy.

**Home / NP (`576a9d1`):** `ui/screens/home/` + `ui/screens/nowplaying/`. Position ticks deliberately do **not** rebuild the whole screen (`nowPlaying.map { it.copy(positionMs = 0) }.distinctUntilChanged()` on Home; progress row still collects live position). Album light stays in Compose. Known non-pixel risks called out by the author: busy/error flags may appear one frame later via `StateFlow`; YouTube match dialog / Spotify device list still call container from the screen.

Verify a presenter branch:

```bash
git checkout refactor/library-presenter   # or refactor/home-nowplaying-presenters
export JAVA_HOME=~/.jdks/temurin-17
./gradlew :shared:compileKotlinJvm :shared:jvmTest :shared:compileAndroidMain --console=plain
```

### Phase 2 — SQLDelight (item 3)

| Step | Status |
|---|---|
| Spike SQLDelight 2.4.0 | Done |
| Schema (`.sq` files) | Done, uncommitted |
| `KainosStorage` + one-time JSON import | Done, uncommitted |
| Db* stores behind existing interfaces | Done, uncommitted |
| Platform wiring (desktop + Android) | Done, uncommitted |
| Round-trip + import tests | Done, uncommitted; combined tree verified with 366 JVM tests, Android assembly, and desktop compilation on 2026-10-02 |
| Storage/integration commit | **Not done**, awaiting an explicit commit request |
| Row-level writes (hearts, recents, pins, playlist edits) | Done; snapshot interfaces retained, SQL diffs only changed rows; trigger tests protect untouched rows |
| Device soak | Storage-only APK installed on Poco; user opened it and reported no problems. Full data/restart/playback checks and desktop real-data migration remain pending. |

**Uncommitted paths on current checkout:**

```
build.gradle.kts
gradle/libs.versions.toml
shared/build.gradle.kts
shared/src/commonMain/sqldelight/.../{StorageMeta,UserLibrary,KainosPlaylists,ListeningSession,LocalLibrary}.sq
shared/src/commonMain/kotlin/.../data/db/{KainosStorage,StorageJson,UserLibraryDb,KainosPlaylistsDb,SessionDb,LocalLibraryDb}.kt
shared/src/commonMain/kotlin/.../data/local/StoredLocalTrack.kt
shared/src/jvmMain/.../data/db/JvmLegacyJsonSource.kt
shared/src/androidMain/.../data/db/AndroidLegacyJsonSource.kt
shared/src/jvmTest/.../data/db/DatabaseRoundTripTest.kt
Platform.jvm.kt / Platform.android.kt   # kainosStorage lazy + Db* factories
JvmLocalLibraryScanCache.kt / AndroidLocalLibraryScanCache.kt  # StoredLocalTrack + readAnyConfig()
```

**Design notes for the DB layer:**

- One process-wide `KainosStorage`; `write {}` wraps a transaction; write helpers must not open their own.
- Legacy import + `storage_step` marker commit together; crash mid-import → next launch imports again; `retire()` renames to `*.json.migrated`.
- Desktop DB: `~/.universal-music-player/kainos.db` (WAL). Android: `kainos.db` via `AndroidSqliteDriver`.
- `local_track.content_key` column already exists (nullable) for Phase 3 — no schema migration needed for the key itself.
- Repositories (`LibraryRepository`, etc.) unchanged; they still talk to store interfaces.
- `AndroidLocalLibraryScanCache.clear()` has no app callers; `DbLocalLibraryScanCache.clear()` exists and is tested.

**Suggested next commit message (when asked to commit):**

```
Replace JSON library/session/scan stores with SQLDelight

One kainos.db per device; one-time import renames the old JSON
files to *.migrated. Settings, tokens, and caches stay on disk.
```

### Phase 3 — content key + sync v2 (item 4)

**In progress.** Content-key generation and vault comparison are complete; v2 transport aliases and client fallback are implemented. Hearts/playlists still export basename IDs. Remaining work:

- Portable id `localkey:<contentKey>`; keep `localfile:<basename>` as fallback until key exists.
- Rematch: content_key index first, then unique basename.
- Hearts/playlists need `/kainos-sync/v2/...`; vault can add `contentKey` field in-place (unknown JSON fields are ignored) — no new vault route required.
- Hub downgrades v2 → v1 (`localkey` → `localfile`) for one release.
- The local unheart bug is fixed. Exports include remembered local tombstones, and rematch applies the newer heart or unheart using the original revision/device tie-break.

### Phase 4 — Gradle modules (item 5 part 2)

**Not started.** Planned modules: `:core:model`, `:core:data`, `:core:playback`, `:provider:{local,spotify,youtube}`, `:sync`, `:shared` (UI + `AppContainer`). Convention plugin under `build-logic/`. Keep packages; move files. Split the ~40 `expect`s in `Platform.kt` by owner module.

---

## Suggested resume order

1. Preserve the current integration working tree. Commit only when asked; no further presenter merge/apply is needed on this tree.
2. Let the user test the integrated Android UI; desktop cold start with real JSON remains pending.
3. Phase 1 is complete. Review the remaining-presenter and row-write changes when preparing a commit.
4. Phase 2 row writes are complete. Device soak and desktop real-data checks remain with the user.
5. Phase 3 content key + sync v2.
6. Phase 4 module split.
7. Update `AGENTS.md`, `docs/CURRENT_STATE.md`, `docs/plans/home-lan-library-sync.md` per phase.

Do **not** push until the user asks. Do **not** ship a GitHub Release unless asked.

---

## Verify commands

```bash
export JAVA_HOME=~/.jdks/temurin-17
./gradlew :shared:jvmTest :shared:compileAndroidMain :desktopApp:compileKotlinJvm --console=plain
./gradlew :androidApp:assembleDebug --console=plain

# Desktop run (logs under logs/desktop-run-*.log)
./gradlew :desktopApp:run

# Phone: adb devices -l, then install -r release/<apk> over wireless transport
```

Memory note: Kotlin/Gradle daemons are heavy here (~4G JVM args). Avoid three parallel full builds; presenter worktrees + SQLDelight already OOMed once during the fan-out.

---

## Pitfalls

- Working tree currently **carries all uncommitted storage and presenter integration files**. Switching branches without preserving them will drag them to other branches. Use a stash with untracked files or commit when asked.
- The original `refactor/sqldelight-storage` branch has the old screen monoliths in its committed tree. The active integration working tree deletes those files and uses `ui/screens/{library,home,nowplaying}/` instead.
- `formatTime` was left `internal` in the screens package so Library can still call it after the Home/NP split — if you move packages later, watch that.
- Legacy import renames JSON in place. Keep `*.migrated` until a real device has been verified; do not delete them automatically.
- Home sync load gate (`awaitLoaded()` on library + playlists) from `c8ca79e` must keep working across the DB cutover.

---

## Latest verification and phone feedback, 2026-10-02

- `JAVA_HOME=/home/theadenkingof/.jdks/temurin-17 ./gradlew :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug --console=plain` passed. All 361 tests passed, with no failures, errors, or skipped tests. Log: `logs/integration-build-2026-10-02.log`.
- `git diff --check` passed. No source references to Koin remain.
- Storage-only APK: `release/kainos-player-debug-sqldelight-2026-10-02.apk`. Installation succeeded; the user opened the app and reported no problems. This is an initial launch smoke test, not a complete migration or playback validation.
- Integrated APK: `release/kainos-player-debug-storage-presenters-2026-10-02.apk` installed successfully on the Poco with `adb -t 1 install -r`. Log: `logs/android-apk-install-20261002-storage-presenters.log`. User handles manual UI/playback testing.
- Not run: desktop live launch/import, Android lint, instrumented/device automation, full manual migrated-record/restart checks, or cross-version sync checks.
- No build or test process remains running. Authoritative telemetry is available via `codex app-server --stdio`, initialize, then `account/rateLimits/read`. Latest check: 18% five-hour used, 97% weekly used. The active pause threshold is 98% used or 2% remaining for either quota.

---

## Agent / transcript crumbs

- Plan id: `961d0bc9`
- Library presenter agent: `0d40db52-5720-4d6c-9252-14ce174496a0`
- Home/NP presenter agent: `bf1798f5-ec26-4ece-a27c-ee36bd91ffbc`
- SQLDelight stores agent: `93deb1ff-c5d7-4f59-a363-8cc3b1e414ab`

## Remaining presenters and row writes, 2026-10-02

- All screen presenters are implemented. Search debounces and cancels stale requests; Settings is split into sections with operation state outside Compose; queue commands resolve current entry identities. New tests cover canceled searches, credential changes, and shuffled duplicate queue entries.
- User-library and playlist writes compare the prior database snapshot and touch changed rows only. Playlist metadata uses UPDATE to avoid foreign-key cascade deletion. Multi-query reads use a read transaction. SQL-trigger tests reject any accidental write to unchanged tables or playlists.
- All 366 JVM tests passed with zero failures, errors or skips. Desktop compilation and Android assembly passed. Logs: `logs/remaining-presenters-verification-2026-10-02.log`, `logs/row-writes-verification-2026-10-02.log`.
- Installed `release/kainos-player-debug-presenters-row-writes-2026-10-02.apk` on the Poco with `adb -t 1 install -r`. Installation succeeded; manual testing remains with the user. Log: `logs/android-apk-install-20261002-presenters-row-writes.log`.
- Additional preservation snapshot: `/tmp/kainos-before-remaining-presenters-c75envy1/` with tracked patch and untracked archive. No commit, push or release was made.

## Content identity foundation and v2 transport, 2026-10-02

- Desktop hashes size plus the final 64 KiB during new/changed scans and backfills missing keys without repeating ffprobe. Android records SAF/MediaStore mtimes, computes keys after scan, caches by location/size/mtime and logs ContentKey batch counts/timing. Non-seekable or unreadable Android files retain basename fallback.
- Keys persist in both local row JSON and indexed `local_track.content_key`, and propagate through domain/persisted metadata. Same-config scan writes now touch changed rows only, including background artwork/key batches. SQL-trigger tests protect unchanged scan rows.
- Vault indexes carry nullable keys. Full hashes take precedence; known keys detect same-size conflicts, with size fallback for old peers. Android caches vault keys in memory by location/size/mtime.
- Local unheart propagation now works across export, rematch, restart and re-heart. Tests also protect newer local hearts from older remote unhearts.
- Hub serves v1 and v2 hearts/playlists/vault-index routes with the same existing basename identity. Client tries v2 first and retries v1 only on 404, never auth/server failures. Health/blob routes and pairing URI stay unchanged. Key-based portable IDs, alias merge/rematch and explicit v1 downgrade remain pending.
- Latest verification: 378 JVM tests passed, desktop compilation and Android assembly passed. Log: `logs/sync-v2-transport-verification-2026-10-02.log`. Previous foundation log: `logs/content-key-final-verification-2026-10-02.log`.
- Installed content-key/unheart build: `release/kainos-player-debug-content-keys-2026-10-02.apk`, log `logs/android-apk-install-20261002-content-keys.log`. This predates the v2 transport aliases.
- Not run: desktop real-data launch/import, Android instrumented checks, live old/new-peer sync, user validation of key-pass cost. No module extraction yet.

## Resume update, 2026-10-03

Quota rechecked below threshold, so work resumed. Keyed hearts/unhearts, renamed-file rematch, metadata refresh from the latest scan, v1 downgrade, and version 2 database migration are implemented. 392 JVM tests, migration verification, desktop compile and Android assembly pass. Latest installed APK: `release/kainos-player-debug-keyed-heart-sync-2026-10-03.apk`; install log `logs/android-apk-install-20261003-keyed-heart-sync.log`. Live manual sync/device checks remain pending with the user. Module split is next.

## Module extraction complete, 2026-10-03

Keyed heart/playlist export and rematch, version-2 database migration, and all seven Gradle modules are implemented. 396 JVM tests, SQL migration verification, desktop packaging, Android assembly and lint pass. CI now runs every module's tests. Real desktop JSON import preserved collection counts and restored the queue paused; SQLite checks passed. The final modular APK is installed on the Poco. Manual device interoperability, playback/UI checks and Android scan cost remain with the user. Current paths, backups, logs and next steps are in `HANDOVER.md`. No commit, push or release was made.

## Publication authorized, 2026-10-03

The user requested commit and push to GitHub. The complete redesign is being committed on `refactor/storage-presenter-integration` and pushed to `HuuTrucNguyen0508/Kainos-Player`. Earlier uncommitted-state notes above are historical. Check `git log -1` and `git status -sb` for the current revision and remote state.
