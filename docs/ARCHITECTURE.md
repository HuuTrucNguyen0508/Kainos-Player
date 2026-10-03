# Kainos Player modules

The storage, identity and module redesign is implemented on `refactor/storage-presenter-integration`. Kotlin packages remain `com.universalmusic.player.*`; directory ownership changed on 2026-10-03.

| Module | Owns | Project dependencies |
| --- | --- | --- |
| `core:model` | Domain models, provider/playback contracts, persisted DTOs, portable sync identities/protocol DTOs, platform primitives and playback trace | None |
| `core:data` | SQLDelight database/imports, repositories, settings/auth stores, caches, store factories | `core:model` |
| `core:playback` | PlayerSession, queue, source resolver, session persistence controller, sleep timer, Android Media3 and desktop mpv/MPRIS | `core:model` |
| `provider:local` | Local scanner/provider, artwork, content-key readers and folder pickers | `core:model` |
| `provider:spotify` | Spotify API/auth, librespot and Web Playback hosts | `core:model`, `core:data` |
| `provider:youtube` | YouTube search, NewPipe/yt-dlp resolution and audio download | `core:model`, `core:data` |
| `sync` | LAN coordinator, HTTPS hub/client, certificate pinning, vault I/O and transfers | `core:model`, `core:data`, `provider:local` |
| `shared` | Compose UI, seven presenters, navigation, AppContainer and provider-dependent SearchContinuationFetcher | All modules above |
| `androidApp`, `desktopApp` | Platform entry points and packaging | `shared` |

`core:*` has no provider or sync implementation dependency. Sync contracts retain their existing `data.sync` package inside `core:model`, so a package name alone does not identify its Gradle module. Store interfaces and persisted DTOs likewise live in `core:model`; storage implementations live in `core:data`. Local hashing stays in `provider:local` and is reused by vault I/O.

Platform expects and actuals are split into `PlatformCoremodel`, `PlatformCoredata`, `PlatformCoreplayback`, `PlatformProviderlocal`, `PlatformProviderspotify`, `PlatformProvideryoutube` and `PlatformSync`, under their owning modules. Android's context is initialized by `initAndroidPlatform`; its setter is private.

`build-logic` supplies `kainos.kmp.library`: JVM and Android targets, SDK levels from the version catalog, serialization/coroutines, and common test dependencies. SQLDelight belongs only to `core:data`; Compose belongs to `shared` and app entry points. Shared test helpers are compiled from `test-fixtures/kotlin` into test source sets, without becoming production API. Tests otherwise moved with their subjects; repository/protocol integration tests live in `sync` where needed.

## Verification

Use JDK 17 and run builds serially to avoid memory pressure:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
./gradlew jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable :androidApp:assembleDebug --console=plain
```

The root `jvmTest` task selector includes every module. `:shared:jvmTest` now covers UI/presenters only. SQLDelight's version-1 fixture is `core/data/src/commonMain/sqldelight/databases/1.db`; migration `1.sqm` upgrades installed databases to version 2 by adding nullable heart-operation identity JSON.

396 tests pass. Packaging, Android assembly, Android lint and migration verification pass. The packaged desktop app imported the real JSON snapshots: 66 favorites/heart operations, 168 remembered tracks, 41 recents, 512 scan tracks and a 512-entry session. Collection counts match preserved originals; SQLite quick_check and foreign_key_check pass. The queue restored paused. Originals remain as `*.json.migrated` and an additional pre-launch backup is recorded in HANDOVER.md.

Loopback HTTPS tests cover the new hub's keyed v2 and downgraded v1 responses, new-client fallback to a v1-only server with pinned-client status validation enabled, encrypted vault indexes and blob downloads. Manual old-APK/desktop compatibility and Android scan-cost checks remain with the user.
