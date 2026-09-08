# Agent follow-up: M3 project check

Review date: 2026-09-08. User requested an immediate handoff because usage is low.

## Repair status (same day)

Follow-up fixes applied in order from this doc:

1. `AndroidMediaControls` publish/attach hop to the main looper.
2. `KainosForwardingPlayer` listener fan-out for Spotify overlay, idempotent play/pause, `clearRetainedMedia` on stop.
3. `EngineState.playGeneration` stamped by engines; `PlayerSession` ignores mismatched events before mutating nowPlaying; ownership tests cover delayed A ENDED/FAILED after B.
4. MPRIS truthful CanPlay/CanPause, queueItemId track paths + stale SetPosition reject, Pause while buffering, Seeked signal.

Verification: `:shared:jvmTest` green; emulator `AndroidPlaybackTest` **OK (8 tests)** including `switchingToSpotifyKeepsMediaSessionForNotification` (assertions unchanged).

---

## Outcome (original review)

M3 is not ready to sign off. Compilation and existing JVM tests pass, but the Android instrumented suite has a failure, and a separate regression probe exposes incomplete stale-event protection. No application source was changed during this review. This document is the review deliverable; preserve all existing working-tree changes.

Review baseline: `dcda2f7` plus all current uncommitted and untracked files. M2, playlist endpoint fixes, and M3 are mixed in the working tree. Requirements come from `docs/playback-baseline.md`, `docs/CURRENT_STATE.md`, and the conversation. No additional repository coding standards were found beyond AGENTS.md.

## Verified checks

- JDK 17 command passed: `JAVA_HOME=/home/theadenkingof/.jdks/temurin-17 ./gradlew :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug :androidApp:lintDebug :androidApp:assembleDebugAndroidTest --console=plain`.
- Existing JVM results: 129 tests, zero failures/errors/skips. Lint: zero errors, six warnings. Build log: `/tmp/kainos-m3-review-build.log`.
- Started existing Pixel_10_Pro AVD headlessly as emulator-5554, installed app/test APKs, and ran the existing AndroidPlaybackTest class through adb instrumentation.
- Android result: 8 tests, 1 failure. `switchingToSpotifyKeepsMediaSessionForNotification` at AndroidPlaybackTest.kt:110 expected PlaybackState.STATE_PLAYING (3), received STATE_PAUSED (2). Log: `/tmp/kainos-m3-review-android-tests.log`.
- Temporary JVM test outside the repo: play A, manually advance to B, then deliver a delayed ENDED representing A after B has started. Expected B; actual C. This proves the session cannot reject that stale event. Source: `/tmp/kainos-m3-review-probes/jvm/ReviewGenerationTest.kt`. Log: `/tmp/kainos-m3-review-probes.log`. The targeted failing run replaced the latest shared JVM test report; the earlier full suite was green.
- No physical Android/HyperOS/Bluetooth validation or live Spotify-account audio validation was performed in this review.

## Findings and repair order

### 1. P1: Android metadata bridge accesses ExoPlayer off its application thread

`AppContainer.kt:57` creates a Dispatchers.Default scope and passes it to bindPlatformMediaControls at :126. `AndroidMediaControls.kt:33-38` collects in that scope, then publishes directly to `KainosForwardingPlayer.publishSessionState`. That method reads/writes the service-created ExoPlayer at approximately :61-79. ExoPlayer is created on the main thread in AndroidPlaybackService.

A queue/now-playing update after attachment therefore violates ExoPlayer's application-thread requirement. This is source-confirmed; the dedicated runtime crash probe was still being prepared when the user requested immediate handoff. Confine attach/publish/player access to the application looper. Do not mistake volatile fields for thread confinement.

### 2. P1: Spotify overlay changes do not notify Media3 listeners

`KainosForwardingPlayer.kt` changes overlay playing/buffering/position/duration and available-command fields but never emits matching Player.Listener events. ForwardingPlayer delegates listener events from the underlying ExoPlayer, which remains paused on the silence placeholder. Getter overrides alone cannot reliably update the MediaSession, notification, or foreground-service state.

The existing Android notification test fails with PAUSED instead of PLAYING. Its setup uses a separate engine from the application's bound PlayerSession, so repair the test wiring as well as testing the production session path. Use a coherent Player implementation/state publication mechanism and verify listener notifications, transport availability, and foreground playback. Do not just weaken the assertion.

### 3. P1: Generation checks do not identify the originating engine request

`PlayerSession.kt:65` reads the current `engineEventGeneration`, not an identity attached to the received EngineState. `beginTransition` assigns it to current playGeneration, and startResolved assigns it again. Once B is armed, a late A ENDED/FAILED is treated as B's event. `_nowPlaying` is also updated before the generation check.

Carry playback-attempt identity with engine events, or ensure equivalent backend filtering and serialized event/command ownership. Check identity before mutating state. Add tests for delayed A completion and failure after B starts, not just ENDED at the queue tail. Temporary probe described above reproduces the completion defect.

### 4. P1: Android controls can bypass or invert shared session behavior

- `KainosForwardingPlayer.play()` around :140 unconditionally toggles Spotify playback. Explicit PLAY while already playing can pause it. Use idempotent play/pause commands.
- `AndroidPlaybackEngine.stop()` around :210-218 stops but does not clear retained MediaItems. After shared clearQueue, non-Spotify wrapper play delegates to ExoPlayer. A controller can prepare/play the retained URL outside the now-empty shared queue. Clear retained media state and route transport through the session without creating engine/controller recursion.

These are static findings requiring targeted controller tests.

### 5. P1/P2: Linux MPRIS transport and identity gaps

- `MprisController.kt:125,191-197`: buffering is advertised as Playing, but Pause ignores it because isPlaying is false. Play toggles during buffering and cancels it. A pause during resolution may be ignored and audio then starts. Use explicit session commands handling buffering.
- `MprisController.kt:171,203-204`: track ID comes from canonicalId, and SetPosition ignores its supplied trackId. Duplicate queue entries collide; a delayed seek for an old track seeks the new one. Use queueItemId and reject stale IDs/out-of-range positions.
- `MprisController.kt:146`: CanPlay/CanPause remain true after Stop clears the queue. Publish truthful command availability.
- No Seeked signal implementation was found. Validate seek signaling against the MPRIS spec.

The review found no hard AGENTS.md standards violation. Linux playback remains headless and MPRIS is released on window close. Correctness findings above are separate from stylistic standards.

## Immediate next actions

1. Finish or stop the pending temporary Android probe/build described below; inspect its result before rerunning anything.
2. Report the blockers to the user. The request was a project check; do not assume fixes or deployment are authorized by this handoff alone.
3. If fixes are authorized, address Android thread confinement and state publication first, then explicit transport/session ownership and stale-event provenance.
4. Run the production-session Android path and existing instrumented suite, then MPRIS transport checks including buffering, stale SetPosition, clear/play, and duplicate entries.
5. Keep full Connect remote pause/seek/transfer synchronization separate: duration-based Spotify completion is an acknowledged limitation, not a newly discovered M3 regression.

## Temporary tooling and cleanup

A headless emulator was started by this review with:
`/home/theadenkingof/Android/Sdk/emulator/emulator -avd Pixel_10_Pro -no-window -no-audio -no-snapshot-save -no-boot-anim`
Log: `/tmp/kainos-m3-review-emulator.log`. It may still be running. It was not running before the review. Stop it with `adb -s emulator-5554 emu kill` when finished, after confirming it is still the review instance.

Temporary source injection uses `/tmp/kainos-m3-review-probes/init.gradle`; nothing was added to repo source directories. Initial Kotlin Android-test source injection did not work. The last tool call rewrote it to add a Java test source directory and launched `:androidApp:assembleDebugAndroidTest`; that call was interrupted by the user and the build may still be running. Check `/tmp/kainos-m3-review-bridge-build.log` and processes first.

The Java probe is `/tmp/kainos-m3-review-probes/android-java/ReviewMediaBridgeTest.java`. It launches MainActivity, waits for the forwarding player, then adds a sample track to the shared queue without requesting playback. The queue update should reproduce the background-thread ExoPlayer access. It has not yet been successfully run. Earlier `/tmp/kainos-m3-review-bridge-test.log` contains ClassNotFoundException from the unsuccessful Kotlin injection; that is a harness failure, not an app defect.

If the Java probe builds, install its test APK and run only `com.universalmusic.player.ReviewMediaBridgeTest`; capture crash logcat. Do not claim runtime confirmation until it executes.

The original existing Android test APK was installed on the emulator; a later injected test APK may be built but not installed. Generated build/test artifacts can contain temporary probes. A normal Gradle invocation without the init script restores the standard source inputs.

Review agents: `/root/m3_standards` completed; `/root/m3_spec` supplied findings and may still need finalization. Do not launch further agents for this handoff.

## Constraints retained

No Home redesign; no protected audio caching; Discover Weekly listing absence stays separate from the fixed /items endpoint. Folder selection, persistent favorites/artwork, Search provider filtering, and radio remain later milestones. Preserve user changes in AGENTS.md and all uncommitted implementation files. No commits, publication, or release upload performed.

Primary references: https://developer.android.com/reference/androidx/media3/common/ForwardingPlayer and https://specifications.freedesktop.org/mpris/latest/Player_Interface.html.
