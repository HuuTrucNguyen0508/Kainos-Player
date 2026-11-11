# SQLite or Turso for Kainos Player

Checked 2026-10-07. Recommendation: retain SQLite through SQLDelight for the current Android/Linux player. This is a fit assessment, not a performance benchmark or a migration proposal.

## Current implementation

`core:data` uses SQLDelight 2.4.0 with `AndroidSqliteDriver` on Android and `JdbcSqliteDriver` on desktop. Both open `kainos.db`; desktop enables WAL and both enable foreign keys. The database stores library state, playlists, listening-session snapshots and local scan snapshots. Settings, tokens and audio/artwork files have separate stores.

Evidence: `gradle/libs.versions.toml`, `core/data/build.gradle.kts`, `core/data/src/androidMain/kotlin/com/universalmusic/player/platform/PlatformCoredata.android.kt`, and the corresponding JVM file. `docs/ARCHITECTURE.md` and `HANDOVER.md` record prior migration and integrity validation; those checks were not rerun for this comparison.

Home sync exchanges selected portable identities, heart operations, playlist revisions and tombstones. Android SAF URIs and desktop paths remain device-specific. Vault file transfers have their own confirmation and transport workflow. See `docs/plans/home-lan-library-sync.md`.

## What Turso means

Turso offers a managed cloud platform, the libSQL SQLite fork, and a newer embedded engine written in Rust. These are distinct choices. The embedded engine supports local use, and Turso Sync supports local reads/writes with push/pull synchronization. A cloud dependency is therefore not inherent to every Turso deployment. [Turso introduction](https://docs.turso.tech/introduction), [sync usage](https://docs.turso.tech/sync/usage).

The CLI also has a local sync server. Its documentation describes development/testing use without a cloud account. That establishes a local option, not production readiness for Kainos's authenticated, pinned-HTTPS hub. [Local sync server](https://docs.turso.tech/sync/local-sync-server).

The documented Android libSQL client remains in technical preview and requires the Android Gradle Plugin. The newer engine's Java guide describes building bindings locally instead of installing from Maven Central. These docs do not establish a ready replacement for our two SQLDelight drivers. [Android quickstart](https://docs.turso.tech/sdk/kotlin/quickstart), [Java bindings](https://docs.turso.tech/connect/java).

Turso's compatibility matrix reports full SQLite file-format support but partial SQL and C API support. A migration would need verification against our schema, generated queries, migrations and driver behavior. [Compatibility matrix](https://github.com/tursodatabase/turso/blob/main/COMPAT.md).

## Why retain SQLite

SQLite explicitly targets local application/device storage, including phones and desktop media catalogs. This matches our offline library and paused-session restore. [Appropriate SQLite uses](https://sqlite.org/whentouse.html).

Our selective LAN sync already expresses music-specific rules. Turso Sync's documented conflict policy is last push wins, which is a different rule from our revision/tombstone merge. Database synchronization alone would not replace file rematching or vault transfers. This is an inference from our implementation and Turso's [conflict documentation](https://docs.turso.tech/sync/conflict-resolution).

No measured database bottleneck or required Turso-only feature was identified in this assessment. Switching now would create integration and migration work without an established player benefit. Reconsider Turso if requirements change to managed cloud sync or if a concrete database limitation appears. Keep device-specific state separate from any future portable synced data.

No application code changed, no dependencies were installed, and no builds or tests ran. The research controller returned shadow mode with execution disallowed, so this check was performed directly without delegated tasks.
