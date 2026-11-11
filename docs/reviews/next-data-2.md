# Loudness review of feat/next-data-2

Reviewed head `2f2e9e99` on `review/next-data-2`, 2026-11-11. Verdict: ready to merge after the fixes below. Android device playback remains unverified, as requested; no phone access was used.

## Integration

- `5ff428f0` merges the requested main baseline `2cdbba48`. Two conflicts: AndroidPlaybackEngine constructor keeps both the loudness lookup and main's injectable player connector; DesktopPlaybackEngine keeps main's remote-probe optimization and the analysed local gain. Main's FIFO commands, transition retention, forwarding-player changes, and mpv flags remain intact.
- `ae64581a` fixes the findings below, updates Playback settings text, and adds the cache sweep.
- `baf2b538` merges main's subsequent `669c36fa`, including reviewed collection pages and the receiver event-journal fix. No conflicts.

## Findings and fixes

Locations refer to the fixed source. Every behavior finding below has a regression observed failing before the fix and passing afterward.

| Severity | Location | Scenario and fix |
| --- | --- | --- |
| P1 | `core/playback/src/androidMain/kotlin/com/universalmusic/player/platform/AndroidItemGain.kt:67` | Renderer input lookahead changed shared item gain before outgoing decoded formats necessarily reached the sink. Attach gain to the input Format's customData instead; Media3 carries that Format through its timed output queue and copies customData to the sink format. The processor takes tags first, then that output format's analysed gain, and switches only at flush. The regression checks rendered PCM across delayed formats, a discarded successor, seek, and an unmeasured replacement. |
| P2 | `provider/local/src/jvmMain/kotlin/com/universalmusic/player/data/local/LocalLoudness.jvm.kt:35` | Cancellation waited for a coroutineScope's blocked pipe reader before it could kill ffmpeg. Spool summary output to a temporary log and interrupt process.waitFor; cancellation promptly destroys the child. A blocked-child regression checks cancellation latency and process death. |
| P2 | `provider/local/src/jvmMain/kotlin/com/universalmusic/player/data/local/LocalLoudness.jvm.kt:23` | JVM exit could orphan ffmpeg, including during process startup. Register cleanup before startup and hand the process to the shutdown hook through a future. A separate JVM fixture exits while its analyzer sleeps; the child is gone when the fixture exits. |
| P2 | `provider/local/src/commonMain/kotlin/com/universalmusic/player/data/local/LocalLoudness.kt:68` | Downloads and a library pass could run concurrent ffmpeg processes. Serialize them; check cancellation after measurement and cancel the old pass when a rescan starts. Use nice 19, idle I/O when ionice exists, and one decoder/filter thread. A concurrent-pass regression observes at most one active analyzer. |
| P2 | `core/model/src/commonMain/kotlin/com/universalmusic/player/data/sync/LoudnessSync.kt:84` | A unique basename could override a known content-key mismatch. Reject mismatches. An unmeasured duplicate was also absent from the exported entries, falsely making the sender's basename unique. Export basenameUnique computed over every subject; gate fallback on it while retaining exact content-key matches. Both cases have regressions. |
| P2 | `core/model/src/commonMain/kotlin/com/universalmusic/player/domain/playback/VolumeLevelling.kt:94` | The first album track used track gain and the next gapless track switched to album gain. Use the complete tagged-album aggregate from the first played track, including a mid-album start. Tracks without aggregates retain track gain. The shared regression and native mpv PCM boundary test cover this. |
| P2 | `core/model/src/commonMain/kotlin/com/universalmusic/player/data/loudness/LoudnessModels.kt:40` | Stored paths were normalized but cached playback subjects used file URIs, so unchanged cached audio was repeatedly measured. Normalize both sides of the freshness check. A file-URI regression covers escaped filenames. |

The eight new regressions are in LoudnessModelsTest, VolumeLevellingTest, LevellingAudioProcessorTest, LocalLoudnessTest, and FfmpegLoudnessAnalyzerTest. Existing native PCM tests were updated for a consistent album gain.

Android uses the separate Spotify receiver path, so these PCM gains do not affect Spotify. Tags remain preferred over analysis. Existing tests verify boosts capped by measured peak on Android and native mpv; analysed boosts retain the -1 dBTP ceiling. Existing source overs can remain at unity under the requested boost-only peak policy. Echo code was not changed.

Media3 behavior was checked against [MediaCodecAudioRenderer 1.8.0](https://raw.githubusercontent.com/androidx/media/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java) and [MediaCodecRenderer 1.8.0](https://raw.githubusercontent.com/androidx/media/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/MediaCodecRenderer.java). Host tests cover the PCM handoff; this review did not run a phone decoder or audio capture.

## Requested gaps

Playback settings now describe tags, analysis, peak-limited boosts, desktop measurement, phone Home sync, and unmeasured/streamed playback accurately. The existing cache index contains one entry, `yt:ZwZC4vlZTbI`, 5,832,555 bytes. Existing entries now join the next background library pass and are measured once per unchanged file. The owner's app was not restarted to trigger it.

## Verification

The exact requested command passed on `baf2b538`:

```sh
export JAVA_HOME=$HOME/.jdks/temurin-17
./gradlew jvmTest :core:playback:testAndroidHostTest :core:data:verifyCommonMainKainosDatabaseMigration :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
```

- JVM: 1,476 cases, 1,460 passed, 16 intentionally skipped, no failures.
- Android host: 265 cases passed, no skips or failures.
- Native mpv PCM levelling tests passed, including per-file gain, peak-capped boost, album boundary, and toggling without dropped samples.
- The old-hub 404 fallback and live pinned-HTTPS loudness exchange tests passed.
- Migration verification also passed with `--rerun-tasks`, all 10 tasks executed. L2a ALTER statements precede the L2b table in 9.sqm; schema remains 9 to 10.
- Android debug assembly and desktop distribution passed. Artifacts stay in this worktree.
- git diff --check passed.

Logs: `/tmp/next-data-2-verified.log` and `/tmp/next-data-2-migration.log`. Failing regression runs are `/tmp/next-data-2-{red,cancel-red,android-red,album-red,uri-red,duplicate-red,quit-red}.log`.

The phone, owner's desktop process, main checkout build directory, and real database were not touched. Only the cache index was read. Nothing was pushed, published, or merged into main.
