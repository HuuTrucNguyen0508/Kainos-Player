# Android runtime measurements

Use JDK 17 and the Pixel_10_Pro AVD. The driver is a self-instrumenting
`com.android.test` module, so killing the target does not kill the runner.
Every launch and interaction is executed by a Gradle-run test. The host script
refuses any device other than emulator-5554. Do not target a physical phone.

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SERIAL=emulator-5554
# Background this command and retain its output; wait for sys.boot_completed=1.
"$ANDROID_HOME/emulator/emulator" -avd Pixel_10_Pro -no-window -no-boot-anim -no-snapshot-save -no-audio
scripts/perf/android-runtime seed profile
scripts/perf/android-runtime profile
scripts/perf/android-copy-profiles.py
flock -s /tmp/kainos-perf.lock ./gradlew :androidApp:assembleDebug :androidApp:assembleOptimized
scripts/perf/android-runtime measure debug none
scripts/perf/android-runtime measure optimized
```

Copy each run's `androidMacrobenchmark/build/outputs/` to a different log directory
before the next test run overwrites results. Summarize retained benchmark JSON
with `scripts/perf/android-results.py <file.json>`. Keep traces, JSON, XML and
logcats as local evidence, outside Git. `docs/perf/android.tsv` contains summaries.

`SeedLibrary` inserts 120 thirty-second silent mono WAVs into MediaStore's
Music/KainosPerf folder. It leaves existing files intact and skips its own named
fixtures on reruns. The installed application's permission is granted from the
test. Fixture data is emulator-only. No Spotify credentials are used.

The startup profile covers launch to the Home navigation frame. The baseline
profile also covers Library, five fast scrolls, playing a local row to open Now
Playing, then Search typing. Later interactions are excluded from the startup
profile. The copy script includes startup rules in the baseline too. It refuses
empty or dependency-only profiles. Profiles are collected on the non-minified,
non-debuggable `profile` variant, then rewritten by R8 for `optimized`.

`StartupTimingMetric` measures launch intent to initial display. It does not
measure playback latency or wait for every background scan/artwork request.
`FrameTimingMetric` covers the whole asserted journey, including startup and
navigation, not only scrolling. The tests fail if Library rows, playback Pause,
or the Now Playing close control are absent. Startup is process-cold. Whether
kernel page caches were dropped must be checked in the retained benchmark logs;
never describe this as disk-cold without that evidence.

Each measurement test takes 15 iterations. After launch it waits five seconds
with the restored session paused and logs TOTAL RSS from `dumpsys meminfo`, then
performs the interactive journey. RSS is sampled before decoding/playback.
CompilationMode.None resets ahead-of-time compilation and skips the installed
profile. Partial requires the packaged profile and runs zero warmup iterations.
The emulator and debuggable-target checks are explicitly suppressed with
`EMULATOR,DEBUGGABLE`. Suppression permits comparisons; it does not make emulator
results representative of a physical phone.

`RuntimeSmoke` covers local playback, pause, seek, next, Settings and the Devices
and sync screen without pairing or syncing. Its separate seed/verify methods
check a heart stored by the app. For update retention, run seed on debug, install
optimized with `adb -s emulator-5554 install -r`, run verify on optimized, install
debug with `-r`, and run verify on debug. Pass the method as the third argument,
for example `scripts/perf/android-runtime smoke debug seedRetainedFavorite`.
Never uninstall between these steps. The host script asks Gradle to leave APKs
installed after each run.

`AndroidOptimizedRuntimeTest` opens the actual Spotify login WebView without
submitting credentials. Run it with the app module's `-PandroidTestTarget=optimized`.
The existing `AndroidPlaybackTest#youtubeLiveResolveAndPlaySmoke` exercises the
real NewPipe resolver and playback, subject to network access. An account-backed
Spotify playback test still belongs on the phone checklist.

The test APK uses an R8 mapping pass because AGP requires it for a shrinking
target; its driver is kept intact with no optimization or obfuscation. Its two
Error Prone annotation suppressions are test-only. Application keep rules remain
separate.

Stop only the owned emulator at the end:

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 emu kill
```

App-module white-box tests need `androidApp/optimized-test-rules.pro` to retain
symbols that test code links after R8 has inlined production callers. This file
keeps test-runner dependencies, fake-controller construction, and the provider
and playback contracts. It applies only with `-PandroidTestTarget=optimized`.
These tests exercise NewPipe/Rhino and the login WebView but are not byte-for-byte
the timed target. The external UiAutomator module uses the normal optimized APK.
Always rebuild without `androidTestTarget` before selecting an APK for delivery.

```bash
flock -s /tmp/kainos-perf.lock ./gradlew :androidApp:connectedOptimizedAndroidTest \
  -PandroidTestTarget=optimized \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.universalmusic.player.AndroidOptimizedRuntimeTest,com.universalmusic.player.AndroidPlaybackTest#youtubeLiveResolveAndPlaySmoke'
# Restore the normal artifact after this instrumentation-only target.
flock -s /tmp/kainos-perf.lock ./gradlew :androidApp:assembleOptimized
scripts/perf/android-runtime network optimized
```

The network journey submits one YouTube search and attempts playback of a matching
result. It needs a configured Data API key. The direct NewPipe smoke uses a public
video and needs no API key. Treat search configuration failures separately from
extractor or playback failures.

Macrobenchmark 1.5 clears guest shader and kernel page caches before each COLD
iteration on this API 37 emulator. The cache-clear implementation polls
`perf.drop_caches` and fails if clearing does not complete. The host's page cache
for the emulator disk image was not cleared. CPU frequency was not locked.
Results compare configurations, not physical-phone performance. Test order was
debug None, optimized Partial, then optimized None; it was not interleaved.
The profile increment is therefore a modest observation with overlapping ranges.

To inspect a retained trace without launching the app:

```bash
scripts/perf/android-trace-summary.py --processor /path/to/trace_processor_shell /path/to/trace.perfetto-trace
scripts/perf/android-plot.py
```

The plot reads the committed `androidApp/runtime-benchmark-summary.json`; its
frame values are per-iteration medians, not one pool of all frames. Circles show
medians, triangles p90, and lines the observed minimum and maximum. Raw traces,
JSON and logcats remain under `logs/perf-android-runtime/`.
