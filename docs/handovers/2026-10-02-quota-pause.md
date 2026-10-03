# Quota pause handover

Written 2026-10-02T16:46:51+00:00. Read this before the older lowercase `handover.md`.

## Stop condition

Authoritative Codex telemetry reached **98% weekly used, 2% remaining**. Five-hour usage was **22% used, 78% remaining**. Stop normal work until both limits are below 98% or the user explicitly overrides the quota rule. Do not switch models or agents to evade it.

Weekly reset: 2026-10-04T19:37:17+02:00. Five-hour reset: 2026-10-02T23:13:45+02:00. Telemetry comes from `codex app-server --stdio`, JSON-RPC initialize then `account/rateLimits/read`, without launching a model turn. Recheck before resuming. `/status` in the authenticated Codex CLI also shows the quotas.

## Goal and constraints

Continue the approved storage, local identity/sync and Gradle module redesign for Kainos Player. Use the current model. Plain presenters replace the originally proposed ViewModels. The user handles manual phone testing and has authorized wireless ADB installation. Preserve settings/data with `install -r`. Do not commit, push or publish a release without an explicit request. Use JDK 17 for builds and pnpm for any JS package work.

Approved plan: `/home/theadenkingof/.cursor/plans/Storage identity modules redesign-961d0bc9.plan.md`. The older handover has the branch history and earlier decisions.

## Repository and preserved work

- Root: `/home/theadenkingof/Documents/Code/Kainos-Player`.
- Branch: `refactor/storage-presenter-integration`; HEAD remains `c8ca79e`.
- All redesign changes are uncommitted, including many untracked source files. `main` and original presenter/storage branches remain unchanged.
- Pause snapshot: `/tmp/kainos-quota-pause-g5g_8rme` with `tracked.patch` and `untracked.tar.gz`. Also retain `/tmp/kainos-pre-integration-hfbjipld/` and `/tmp/kainos-before-remaining-presenters-c75envy1/`.

## Completed code

- All seven screen presenters under `shared/src/commonMain/kotlin/com/universalmusic/player/ui/screens/{library,home,nowplaying,settings,search,queue,downloads}/`. Search uses immutable state and canceled/debounced requests. Settings uses section presenters. Queue commands resolve live entry identities. Old root screen files are deleted. Koin dependencies are removed.
- SQLDelight schemas under `shared/src/commonMain/sqldelight/.../data/db/`; store/import implementations under common, Android and JVM `data/db/`. One transactional legacy import retains `*.json.migrated` backups. User-library, playlist and same-config scan writes change affected rows only. Read transactions protect multi-query snapshots.
- Content-key generation in `data/local/LocalContentKey.kt`, Android/JVM actual files, `JvmLocalTrackSource`, `LocalMusicProvider`, `StoredLocalTrack`, and `LocalLibraryDb`. Formula is `lc1:` plus SHA-256 of eight-byte big-endian size and final 64 KiB. Keys persist in indexed SQL and row JSON and reach domain/persisted tracks. Desktop hashes changed files/backfills absent keys. Android captures SAF/MediaStore mtimes, runs a cancellable key pass after scanning and logs batch counts/timing. Unreadable or non-seekable files keep basename fallback.
- Vault keys in `VaultSyncModels`, `AndroidHomeLanVaultStore`, `JvmHomeLanVaultStore`; full hashes take precedence, then known keys plus size, then size fallback. Android vault keys cache in memory by location/size/mtime.
- Local unheart propagation fixed in `LibraryRepository`: export remembered local tombstones, apply remote heart/unheart using revision/device ordering, retain state across restart and re-heart.
- v2 hearts/playlists/vault-index routes in `DesktopHomeLanSyncHub`; `HttpHomeLanSyncClient` tries v2 and retries v1 only on 404. Health/blob routes and pairing URI stay unchanged.
- `LocalSyncIdentity.kt` validates `localkey:lc1:<64 hex>` ids, matches keys before unique basename, rejects a known key mismatch and collapses unambiguous v1/v2 aliases. Helpers are tested; heart alias collapse is **not wired into live repositories yet**.

## Latest playlist identity work

Changed `KainosPlaylistModels`, `KainosPlaylistRepository`, `LocalSyncIdentity`, `AppContainer`, HTTP client and desktop hub.

- Playlist export now uses `localkey:` when its persisted metadata has a valid key, otherwise `localfile:`. Portable LOCAL source ids carry basename and no device URI.
- Playlist v1 exports convert keys back to basename ids and clear content keys. Both client 404 fallback and hub v1 response use this conversion.
- Playlist rematch uses the full local catalog, including tracks with duplicate basenames. It can match renamed keyed files, refuses same-name/different-key matches and backfills keys on existing local entries.
- Local rematch preserves playlist revision/device identity instead of inventing a user edit.
- Three new playlist tests cover v1 downgrade, rename/entry identity/revision preservation, key mismatch and backfill.

This build was already complete when the quota pause attempted to stop it. No new checks were launched after the threshold.

## Verification and phone state

Latest completed command:

```bash
JAVA_HOME=/home/theadenkingof/.jdks/temurin-17 ./gradlew :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug --console=plain
```

`logs/playlist-content-identity-verification-2026-10-02.log` reports BUILD SUCCESSFUL. Existing result XML reports 385 tests, 0 failures, 0 errors and 0 skipped. Earlier green logs include `logs/local-identity-rules-verification-2026-10-02.log`, `logs/sync-v2-transport-verification-2026-10-02.log`, `logs/content-key-final-verification-2026-10-02.log`, and `logs/row-writes-verification-2026-10-02.log`.

Latest installed phone APK: `release/kainos-player-debug-sync-v2-transport-2026-10-02.apk`. Wireless installation succeeded, log `logs/android-apk-install-20261002-sync-v2-transport.log`. It includes all presenters, storage/row writes, keys/vault comparison, unheart fix and v2 transport, but **predates live playlist key wiring and the final identity helpers/tests**. The latest assembled APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk` includes those playlist changes but was not installed before the pause. User earlier opened the storage-only APK and reported no issue; later builds still need their manual testing.

Not run: desktop live real-data migration/playback, Android instrumented checks/lint, manual restart/full migrated-record checks, live old/new-peer sync, key-pass cost measurement on the Poco. `git diff --check` passed before the final playlist changes; do not imply it checked the final patch.

## Remaining work and known limitations

1. Wire heart content-key identities, key-first rematch and mixed v1/v2 alias collapse. Hearts still export `localfile:`. Retain identity/basename metadata for unheart operations too, so v1 downgrade cannot lose tombstones. `LocalSyncIdentity` explicitly supports retaining this information, but its serialization is not in the hearts document yet.
2. Exporter metadata may predate the background key pass. Playlist backfill currently happens during rematch/sync. Ensure all heart/playlist exports can consult the latest local catalog/index. On-device ids must remain `local:<platform id>`.
3. Finish explicit v1 hearts and vault downgrade and cross-version tests. Playlist v1 downgrade is wired; consider how ambiguous same-basename/different-key files should be handled safely by older peers. Keep v1 for one release after full identity cutover.
4. SQL has the content-key index, but rematch currently uses an in-memory catalog index, not a direct SQL indexed lookup. Decide the lookup boundary before declaring the original plan fully complete.
5. Gradle module split has not started: `core:{model,data,playback}`, `provider:{local,spotify,youtube}`, `sync`, `shared` UI/AppContainer and build-logic conventions. Check dependency cycles before moving files, especially local/sync metadata helpers. Keep existing Kotlin packages and split Platform expects by ownership.
6. Update `AGENTS.md`, `docs/CURRENT_STATE.md`, `docs/plans/home-lan-library-sync.md` and lowercase `handover.md` after resuming. Their last update describes the earlier 378-test v2 transport state; this capital handover contains the later playlist progress.

## Precise resume commands

First read this file and recheck authoritative quota. A non-model CLI `/status` is available; the automation path is `account/rateLimits/read` over `codex app-server --stdio` after initialize.

Once resume is allowed:

```bash
cd /home/theadenkingof/Documents/Code/Kainos-Player
git status --short
git diff --check
export JAVA_HOME=/home/theadenkingof/.jdks/temurin-17
./gradlew :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug --console=plain > logs/resume-verification.log 2>&1
adb devices -l
# Use the freshly listed transport id, previously 1. Preserve user data.
cp androidApp/build/outputs/apk/debug/androidApp-debug.apk release/kainos-player-debug-playlist-identity.apk
adb -t 1 install -r release/kainos-player-debug-playlist-identity.apk > logs/android-playlist-identity-install.log 2>&1
```

No need to repeat the full test suite before making another change if the existing successful final log is sufficient. Inspect the completed result first. No commit/push/release is authorized.

## Background process state

No child agents were spawned. The quota-stop check found no running wrapper for our verification command; its log already reported success. No active build/test is owned by this turn. Shared Gradle/Kotlin daemons may remain idle and were not stopped, to avoid affecting concurrent work. No desktop app was launched by this turn. Phone app lifetime is controlled by the user.
