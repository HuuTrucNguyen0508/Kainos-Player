# Android stability investigation

Base: e2abd2e, branch fix/phone-stability. Evidence is read-only in the owner's .audit/phone-logs-2026-11-02 directory. No device access.

## Root causes, before implementation

1. `AndroidLibrespotPlaybackHost.kt:217` creates the unpatched librespot-android 0.2.0 Session. Artifact bytecode confirms Session.reconnect invokes Receiver.stop then ApResolver.refreshPool. Receiver.stop unconditionally interrupts its thread, including itself. Reconnect catches IOException, GeneralSecurityException and SpotifyAuthenticationException, but not InterruptedException escaping the HTTP implementation. logcat.txt:99738-99766 proves that exact fatal path at 17:24:17.488; all.log:19940 records the receiver crash. No host shutdown is needed to trigger it. A narrowly scoped per-receiver guard installed by the public reconnection listener is necessary because the library owns thread creation and exposes no thread factory. It recognizes only InterruptedException at ApResolver.request under Session.reconnect. Recovery closes/recreates the exact failed session under the host mutex; shutdown/replacement wins by identity. It pauses transport without autoplay. Existing R8 rules preserve the matched library class/method names.

2. `AndroidPlaybackService.kt:38` has no foreground denial listener. More importantly Media3 1.8.0 MediaNotificationManager.java:271-276 asynchronously calls updateNotificationInternal after artwork loads; startForeground at :461 calls ContextCompat.startForegroundService outside MediaSessionService's synchronous exception catch. logcat.txt:2-31 records the main-thread fatal at 09:21:40.432. The supplied all.log begins at 14:49:19, and logcat starts with the morning fatal, so neither preserves the playback initiator at 09:21. Desktop remote, handoff, media button and resumption cannot honestly be distinguished from this evidence. Fix both synchronous denial via the documented listener and asynchronous notification callback denial at the provider boundary. Pause PlayerSession and tell the user to reopen the app and press Play.

3. `AndroidLibrespotPlaybackHost.kt:289-296` deduplicates identical booleans but forwards every alternating halt as BUFFERING/PLAYING. `AndroidPlaybackEngine.kt:334-340` publishes each with a new position; PlayerSession.kt:160 logs each publication. Add a short persistent-halt delay and status deduplication at the receiver event boundary; terminal/seek/track changes bypass/reset the filter.

## Final behavior and evidence

- Reconnect: per-thread guard at `provider/spotify/src/jvmAndroidMain/kotlin/com/universalmusic/player/platform/LibrespotReconnectGuard.kt:6`; Android recovery at `AndroidLibrespotPlaybackHost.kt:227-242`. It does not install a JVM default handler. The public library callback runs before Receiver.stop; an old session's recovery cannot replace a newer session. Recreation failure goes through existing Failed state and normal bounded connection retries. No surprise autoplay; press Play to resume. The unit test exercises classification, recovery dispatch and forwarding of unrelated errors, not a real authenticated Session recreation.
- Foreground denial: documented listener at `AndroidPlaybackService.kt:43`; async boundary at `ForegroundSafeNotificationProvider.kt:20`. Denial pauses through AndroidMediaControls.pause -> PlayerSession.pauseTransport -> existing FIFO engine commands and displays a resume instruction. The original worktree already contained a mapping whose pg_map_id exactly matches the phone's ef36658c... mapping. Before rebuilding I extracted the relevant entries into logs/phone-original-mapping-excerpts.txt. They resolve a43.d:36 to startForeground/updateNotificationInternal and x33.run:82 to onNotificationUpdated:276 / lambda$updateNotification$4:183, confirming the async artwork path. New build mapping must not be used as an exact mapping for the old crash.
- Buffering: `SpotifyHaltFilter.kt:25` delays BUFFERING by 250 ms only while decoded position <=1,000 ms, including negative decoder start positions. Unknown or later positions publish immediately. This is deliberately position-based: real trace replay showed onFinishedLoading arrives before much of the storm ends. Identical status events are dropped; seek, terminal and track reset events are immediate and cancel pending halts. All nine mid-track stalls in the trace remain visible. No PlayerSession confinement, stopForTransition, FIFO ordering or natural-end rules changed.

## Trace replay measurement

The committed TSV contains only relative milliseconds, halt booleans, decoded position and track/loading markers extracted in original order from all.log; no credentials or track metadata. The deterministic virtual-time test drives the same filter used by Android, then counts emitted events. Baseline forwarding emitted all 3,740 halt events, including 1,870 BUFFERING states. Final filtering emits 63 state events, including 26 BUFFERING states, and explicitly asserts all nine mid-track stalls remain visible. This is event-count reduction, not measured CPU, latency or battery improvement. An intermediate filter applied 250 ms at every position and emitted 37 states but hid brief real mid-track stalls; that version was rejected. A loading-callback-based filter emitted 917 events and was also rejected.

## Offline Home sync verdict

all.log contains 293 three-second probe timeouts, 162 listening-delivery failures and 130 remote retry failures between the afternoon trace start and the receiver crash. This is excessive repeated connection setup plus four-second mDNS discovery while the hub is off. Socket wait time is not measured radio awake time and no battery percentage claim is supported. ListeningDeliveryRunner previously woke on any publication during its retry timeout, so new revisions bypassed even the nominal thirty-second delay (`ListeningExchange.kt`, original line 422). It now keeps requests conflated during 30/60/120/240/300-second backoff; foreground visits bypass it. RemoteLinkClient retains its quick first retries, then increases to 60/120/300 seconds instead of staying at thirty seconds. Explicit Sync now uses its direct exchange path. A failed offline listening test observed attempts at 0,10,20,...120 seconds; fixed behavior is 0,30,90 seconds, with foreground retry immediate at 120 seconds.

## Validation commands and results

All Gradle commands use this prefix in this worktree:

```sh
JAVA_HOME=/home/theadenkingof/.jdks/temurin-17 nice -n 10 dbus-run-session -- ./gradlew --max-workers=6
```

| Task arguments / command | Result and saved output |
| --- | --- |
| `:provider:spotify:jvmTest --tests '*LibrespotReconnectGuardTest'` before fix | FAIL, expected recovery=1 got 0; logs/phone-fix1-red.log |
| Same after fix | PASS; logs/phone-fix1-green.log |
| `:core:playback:testAndroidHostTest --tests '*ForegroundSafeNotificationProviderTest'` before fix | First harness run failed with constructor argument mismatch; corrected harness then failed with the actual ForegroundServiceStartNotAllowedException; logs/phone-fix2-red.log |
| Same after fix | PASS; logs/phone-fix2-green.log |
| `:provider:spotify:jvmTest --tests '*SpotifyHaltFilterTest'` before fix | FAIL, 3,740 events forwarded; logs/phone-fix3-red.log |
| Same after first debounce | PASS but later replay inspection rejected suppression of brief mid-track stalls; logs/phone-fix3-green.log |
| `:sync:jvmTest --tests '*ListeningDeliveryLoopTest.offlineDelivery*' --tests '*RemoteLinkClientTest.aFailingHub*'` before fix | FAIL for both retry schedules; logs/phone-sync-red.log |
| `:sync:jvmTest` after fix | Old constant-30s behavior assertion failed; updated it to require exponential delay, keeping background handoff assertions; logs/phone-sync-green.log |
| `:provider:spotify:jvmTest --tests '*SpotifyHaltFilterTest' :sync:jvmTest --tests '*ListeningDeliveryLoopTest*' --tests '*RemoteLinkClientTest*'` final focused run | PASS; logs/phone-targeted-final.log |
| `KAINOS_GAPLESS_STRESS=1` plus `jvmTest :core:playback:testAndroidHostTest :androidApp:assembleOptimized :androidApp:lintDebug` | FAIL: newly enabled Android host target also ran inherited common tests outside Robolectric, causing Log.i-not-mocked errors. The targeted Android regression passes; run common suite on JVM. Initial JVM stress result was cached from a non-stress run, so added environment as an explicit test input. logs/phone-final-verification.log |
| `python3 scripts/tests/test_android_release_gate.py` | PASS, 7 tests; logs/phone-release-gate.log |

Read-only inspection used rg/nl/cat, artifact javap for Session and Receiver, and official Media3 1.8.0 source retrieved into logs/Media*.java. No device, desktop app, user mpv instance or user data accessed. No subagents; no pushes. History-vault recording is left to the parent because this assignment permits writes only inside the worktree.

## Parent phone verification

1. Install the final optimized APK in place, preserving data. Keep today's logs for comparison. Open app and confirm session is restored paused.
2. Spotify reconnect: play a Spotify track, background/lock the phone, repeatedly switch Wi-Fi off/on and between Wi-Fi/mobile data. Also disconnect/reconnect Spotify setup during a network interruption to exercise close/replacement. Expect no AndroidRuntime fatal on session-packet-receiver. For the known library failure expect KainosSpotify `interrupted AP reconnect; recreating receiver`, shutdown and a fresh `AP connected` / `session ready`. Playback pauses; reopening and pressing Play should resume. No older recovery should replace a newly signed-in session. Try a second track after recovery.
3. Foreground denial: pause long enough for the playback service to leave foreground, leave Kainos in background, then trigger playback from desktop Connect/remote controls, Bluetooth/notification, and handoff separately. Allow delayed artwork to arrive while backgrounded. If Android denies promotion, expect Session3 `foreground promotion denied`, paused transport and a toast asking to open Kainos and press Play. No process death or repeated autoplay; foreground Play must work. Android decides eligibility, so a successful exempt media-button start is also valid. The original initiator is unknown, so cover all plausible entry points.
4. Buffering: play and skip through at least ten Spotify tracks, including natural completion with Repeat All and a short queue. Compare `Session engine ->` counts in the first 1.5 seconds with the old capture. Brief start underflows should not flip notification/island/UI repeatedly. Disconnect network during established playback: a mid-track halt must become BUFFERING immediately and return to PLAYING on recovery. Natural end must advance once; pause/seek/skip must remain responsive.
5. Hub-off behavior: leave desktop hub off for at least ten minutes while phone plays and publishes position. Expect increasing delivery intervals to five minutes, with no attempts per position tick. Reopen app / Sync now for immediate retry. Bring hub online and verify next retry or explicit Sync now connects and resets backoff.

## Final verification

- `KAINOS_GAPLESS_STRESS=1` plus the Gradle prefix and `jvmTest :androidApp:assembleOptimized :androidApp:lintDebug --continue`: PASS, logs/phone-required-verification-retry.log. First attempt hit a transient desktop Compose assertion `NowPlayingPaneStateTest.compactToggleResetsManualPaneChoiceInBothDirections` at line 46; both implementation and test are byte-for-byte unchanged from e2abd2e. A full rerun passed without changing them. logs/phone-required-verification.log preserves the failed attempt.
- Native stress actually executed: GaplessBoundaryStressTest has 2 tests, zero skips/failures; short-successor elapsed 2,231 ms, identity=b. The test owns private null-output mpv instances and synthetic temporary WAVs; it does not touch the owner's app or mpv. GaplessPcmTest remains separately opt-in and skipped.
- Targeted Android host regression rerun after final changes: PASS, logs/phone-android-regression-final.log. The entire newly enabled host suite is not configured for common non-Robolectric tests; use the documented class selector. Common behavior tests are covered by root jvmTest.
- `python3 scripts/tests/test_android_release_gate.py`: PASS, 7 tests.
- JVM XML totals: 1,103 tests, zero failures/errors, seven skipped; 1,096 executed.
- APK SHA-256: `12f9ca42091e85ae724b548bc7d3786d52e3a532f0ec534700c00d63f97303f7`.
- `git diff --check`: PASS.
- APK: `androidApp/build/outputs/apk/optimized/androidApp-optimized.apk`.
- New optimized R8 mapping id: `50e319a1ce2d921dceae93cad118965746f4c1d4204240824ec79292d565870b`; this differs from the phone crash's original `ef36658cd1c215e454fc0bd8fa5be5695b7c62d76195cfc2cebd301ce72065c1`. The pre-existing matching original mapping was inspected before rebuilding; relevant excerpts remain in logs.
- No phone verification is claimed. No Gradle commands or child tasks remain running. Idle build daemons were not stopped.

## Local commits

- 750bf9d: interrupted AP reconnect recovery.
- bb40e50: foreground service denial handling, including async artwork.
- de70839: filtered Spotify receiver state with real trace replay fixture.
- 5d3b4fd: bounded offline sync retry backoff.
- Final validation/report commit records the stress environment as a test input so opting in cannot reuse cached skipped results.

Each commit includes the requested Claude Opus 5.5 co-author trailer. Only this worktree was edited; parent owns pushing and phone installation. Latest authoritative Codex weekly usage: 39%, below pause threshold.


## Follow-ups after phone verification

The parent reported one session publication per Spotify start on the real phone, but 2,276 raw halt trace writes during a few minutes of skips. This follow-up did not access the phone.

### Recovered receiver and explicit Play

Root cause: AndroidLibrespotPlaybackHost.kt:237 previously recreated the Session after emitting PAUSED without invalidating SpotifyProvider's cached target. SpotifyProvider.kt:760 resumed that retired device without sending the current track URI or saved position. New receiver generations invalidate cached targets before PAUSED. AndroidPlaybackEngine.kt:449 captures its paused position and passes it through the existing media-command FIFO to resumeAt. Explicit Play rediscovers the named receiver, transfers paused, and starts the remembered track with position_ms in the same play request. Normal resumes retain the existing target without discovery. No observer loop, authentication refresh, or state polling was added.

Regression: SpotifyProviderTest.explicitPlayAfterReceiverRecoveryRediscoversAndRestartsAtSavedPosition. Before implementation the test failed with expected replacement, actual retired. After implementation it passes, verifies restart at 12,345 ms, no autoplay on recovery, and no extra device discovery on the following ordinary resume. The JVM fake changes the same host generation property Android recovery changes; this is not an authenticated Android Session integration test.

Commit: be102f5, fix(spotify): rediscover recovered receiver on explicit Play.

### Halt trace writes

Root cause: AndroidLibrespotPlaybackHost.kt:317 sent events through SpotifyHaltFilter but separately wrote every raw halt flip. Move trace emission into SpotifyHaltFilter.kt:56, after status filtering. Only reported stalls and their PLAYING recovery write immediately. Reset writes at most one absorbed-underflow summary per start, including on shutdown or the next track. Raw flips do no trace-file writes.

The trace callback now runs in the JVM-tested filter. A mechanical move of the old raw logging into that callback first reproduced 3,740 writes from the committed fixture and failed the new less-than-100 assertion. Final replay produces 68 halt-related trace lines, with the same 63 transport events and 26 reported stalls as before. All nine mid-track stalls remain visible both as events and trace lines. This count covers halt logging for the fixture, not every category of app trace output, and is not a phone performance measurement. A separate test checks silent absorbed underflows, delayed reported stalls, recovery logging and only one summary across repeated reset calls.

### Follow-up command ledger

All shell commands ran in .claude/worktrees/stellar-astra. The Gradle prefix is the same JDK 17 / nice / dbus-run-session / max-workers=6 prefix documented above. Commands ran sequentially; no --stop, device, app launch, user mpv, user-data, .audit or other worktree access occurred.

| Command or task arguments | Result / evidence |
| --- | --- |
| pwd; git log --oneline origin/main..HEAD; cat docs/investigations/phone-stability-2026-11-02.md | Confirmed assigned worktree and five existing commits; read investigation first. |
| git status --short; rg / sed / cat / nl of touched source, tests, fixture references and HANDOVER.md | Read-only inspection; clean initial tree; located cached-device resume and raw Android logging. Read unslop, debugging, TDD and work-history skill instructions. |
| Python edit scripts using absolute paths | Added regression and minimal API scaffolding first; then recovery fix; later moved logging, added replay assertions, and changed logging after red. All scripts exited zero. |
| :provider:spotify:jvmTest --tests '*SpotifyProviderTest.explicitPlayAfterReceiverRecovery*' | Expected FAIL: retired device still targeted. logs/phone-followup1-red.log |
| :provider:spotify:jvmTest --tests '*SpotifyProviderTest*' | PASS. logs/phone-followup1-green.log |
| git diff --check; git add explicit recovery files; git commit with requested trailer; git commit --amend --no-edit | PASS; recovery commit be102f5. Amendment made all resume overloads use recovery handling. |
| :provider:spotify:jvmTest --tests '*SpotifyHaltFilterTest.realPhoneHalts*' | Expected FAIL: 3,740 raw trace lines. logs/phone-followup2-red.log |
| :provider:spotify:jvmTest --tests '*SpotifyHaltFilterTest*' --tests '*SpotifyProviderTest*' | PASS; replay 68 lines. logs/phone-followup2-green.log |
| jvmTest :androidApp:assembleOptimized :androidApp:lintDebug | Final result recorded below. logs/phone-followups-required.log |

Weekly Codex quota was freshly verified at 39% before work and before required validation, below the pause threshold. Reservation 34074742-ba26-4bfe-a8d5-0de267c745c2 was reused; no reservation or subagent was created. History-vault recording remains with the parent because this task authorizes writes only inside the assigned worktree.

### Follow-up final verification

- Required Gradle invocation passed in 1m 10s; 298 actionable tasks, 115 executed, 183 up-to-date. No retries were needed. JVM XML totals: 1,105 tests, zero failures/errors, nine skipped, 1,096 executed. Native stress tests stayed opt-in and were not enabled for this follow-up.
- `python3` XML aggregation: PASS, counts above. `sha256sum androidApp/build/outputs/apk/optimized/androidApp-optimized.apk`: 101d2e74a591a839e91fdc5abcf684a78433a2f54a8fd263167c93e815babf77.
- `git diff --check`: PASS. `git add` explicit logging/test/investigation files and `git commit` with the requested co-author trailer: PASS. Final `git status --short` is clean and `git log -2` shows the two separate follow-up commits.
- Quota recheck after the build: Codex weekly 39%. No Gradle command or child process remains running; idle Gradle daemons were left alone. No push, install or phone verification performed. The work-history vault write is left to the parent under the worktree-only write constraint.
