# Android optimized candidate

Keep debug available. The new `optimized` variant is a personal-use candidate,
not yet cleared for shipping. It enables R8 full mode (the AGP 9.1 default),
integrated resource shrinking and the optimizing default ProGuard configuration.
Reflection boundaries in NewPipe/Rhino and librespot/protobuf are kept conservatively.
The application ID, version code and database schema are unchanged. It uses the
same Gradle debug signing configuration as debug, not a new release certificate.
There is no release signing configuration in this checkout. A different machine's
debug keystore may differ; compare certificate SHA-256 before any phone update.

Build without publishing a release:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
export ANDROID_HOME="$HOME/Android/Sdk"
flock -s /tmp/kainos-perf.lock ./gradlew :androidApp:assembleDebug :androidApp:assembleOptimized :androidApp:lintDebug
python3 scripts/perf/android-apk-check.py \
  androidApp/build/outputs/apk/debug/androidApp-debug.apk \
  androidApp/build/outputs/apk/optimized/androidApp-optimized.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

The candidate APK is `androidApp/build/outputs/apk/optimized/androidApp-optimized.apk`.
Keep `androidApp/build/outputs/mapping/optimized/` with it for crash retracing.
The checker inspects actual DEX class definitions, manifest flags, versions and
verified signing certificates. It does not prove runtime reflection or data retention.

## Required emulator gate

Open the emulator with T3 `device_list` then `device_open`. Use the returned
`agent-device` command for app launches, taps and screenshots. Never use a physical
phone for this lane. Every install, log and test-runner adb command must select
an explicit `emulator-NNNN` serial. Do not uninstall or clear the app.

1. Install debug with `adb -s emulator-NNNN install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
2. Open via agent-device. Seed two generated audio files, pick their folder using
   the app's SAF picker, heart one track, change an appearance setting and play
   a track. Preserve a copy of the playback log. Record database rows, settings,
   persisted URI permissions and log contents before updating.
3. Install optimized with `adb -s emulator-NNNN install -r androidApp/build/outputs/apk/optimized/androidApp-optimized.apk`.
4. Verify the same setting, folder and heart remain, and the previous log still
   exists. Exercise Library scroll, local play/pause/seek/next, Now Playing,
   YouTube search and playback, Settings, Home sync and the Spotify login screen.
   Capture logcat and check for reflection, serialization and verifier failures.
5. Reinstall debug using the first command, with no uninstall, then verify the
   same state again. Never use `-d` or clear data to disguise a version mismatch.

No emulator runtime proof exists yet: T3 `device_open` failed twice because its
avdmanager process used Java 1.8.0_503 and requires JDK 17. No fallback emulator
launcher or phone was used. Fix the device host Java environment outside this
lane, then rerun this gate.

## Profiles and measurement

AGP merges dependency-provided baseline profiles into optimized's APK. Inspect
`assets/dexopt/baseline.prof` and `.profm` with the APK checker. These are upstream
library profiles, not a generated Kainos journey profile. No app profile or
startup profile was invented. Device startup failed before rootless profile
collection could be evaluated. Once the device service works, use a dedicated
Macrobenchmark/BaselineProfileRule test module to cover cold start, Library
scroll, Now Playing and Search, then collect on API 33+ and commit the generated
profile. The baselineprofile plugin/module was not added without a runnable gate.

Cold-process timing must use at least 15 successful runs per arm, force-stop
between runs, the same seeded data, and an exclusive `/tmp/kainos-perf.lock`.
Record the host load inside the lock. The requested `am start -W` collection needs
a device test runner or an explicitly authorized shell benchmark environment;
T3 interactive launches must use agent-device. OS page cache is not dropped.
Compare verify-only and speed compilation separately; for speed-profile, reset
compilation first, install the packaged profile, compile with speed-profile,
and record the actual ART state rather than assuming a reset preserves profiles.
Do not mix compilation states in one median. After identical agent-device Library
scroll journeys, capture `adb -s emulator-NNNN shell dumpsys gfxinfo com.universalmusic.player framestats`
and idle `dumpsys meminfo`. Keep all samples and report failures, median, p90 and
range. Emulator ratios cannot predict phone latency.

## Phone checklist and future shipping

Phone testing is deferred to the user after the emulator gate. First confirm
certificate equality with the installed APK and take the usual app-data backup.
Use `adb -t <fresh-phone-transport-id> install -r <candidate>` only when explicitly
asked. Check retained hearts, SAF folders, settings and logs first; then local
playback/notification controls, YouTube, real Spotify login/playback/transitions,
Home sync and a process restart that restores paused. Spotify playback has no
runtime coverage on the emulator and needs this real-device test.

Rollback with `adb -t <fresh-phone-transport-id> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
The checker establishes matching version/signature eligibility; actual rollback
data survival still requires the emulator gate. Do not uninstall.

Only when explicitly asked to ship and after acceptance, copy the chosen APK to
repo-root `release/kainos-player-optimized-<version>.apk` (or keep the existing
`kainos-player-debug-<version>.apk` if debug is selected). Publish an official
GitHub Release marked Latest with both the selected Android APK and
`kainos-player-linux-*.tar.gz`. No release was built or published by this lane.
Root README/CI and AGENTS shipping wording are outside this lane's ownership;
the controller should link these instructions and decide whether to promote the
candidate once runtime gates pass. Existing debug commands remain valid.

Sources: [Android optimization defaults](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
[NewPipe's Android Rhino rules](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/proguard-rules.pro).

## Recorded results, 2026-10-31

Base `5087402`, same application sources and version for both APKs. The APK check
result is committed as `androidApp/optimized-apk-check.json`; reproduce it with
the command above. Debug is 31,795,904 bytes; optimized is 6,131,683 bytes, 80.715%
smaller. This is one deterministic artifact observation per build, not a latency
benchmark with repeated samples. Both verified APKs have certificate SHA-256
`780b3387b1f828858b3e3c27e59117a5ab24f171bc27db17055f2d3a93961895`, application ID
`com.universalmusic.player` and versionCode 1. Debug is debuggable; optimized is
not. Required decoder, sink, librespot Session, protobuf GeneratedMessageV3,
Spotify LoginCredentials, NewPipe and Rhino Context class definitions exist in
both DEX sets. Optimized includes dependency baseline.prof and baseline.profm;
its merged source profile has 6,331 lines. No application-generated profile exists.

Successful combined check, JDK 17 / Android SDK, under the shared build lock:

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleOptimized :androidApp:lintDebug jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable --console=plain
```

JVM XML reports total 927 tests, 0 failures, 0 errors, 3 skipped. Tasks used
Gradle cache/up-to-date results; they were not forced to rerun. Desktop packaging
was executed successfully in the first check. Local logs remain in
`logs/perf-android/{baseline-build,checks,checks-2}.log`. The first R8 attempt
failed on optional desktop API references; the scoped rules fixed that build.
No broad `-ignorewarnings` was added. The lane ledger is `docs/perf/android.tsv`.
Its `self` commit value denotes the commit that added the row.

Startup audit found no custom Application.onCreate in the manifest. MainActivity
initializes the platform and container before composition. AndroidPlaybackEngine
connects its MediaController during initialization; the service builds ExoPlayer,
its MediaSession and a silence file onCreate. All remain unchanged because there
is no timing evidence to justify moving them. Shared AppContainer is outside
this lane. Runtime serialization, NewPipe playback, service/media-session
behavior, Home sync, Spotify login, in-place retention and rollback retention are
unverified. Do not infer those from successful compilation or DEX class presence.
