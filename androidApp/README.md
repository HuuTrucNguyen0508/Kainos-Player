# Android optimized candidate

Recommend `optimized` for the next personal-use APK after the phone checks below.
On the emulator it starts faster and uses less idle RSS than debug; it is 80.10%
smaller. Keep debug as rollback. No release or physical-phone install was made.
The application ID, versionCode, database schema and debug signing identity are
unchanged. Compare certificates before installing an APK built on another machine.

## Measured results, 2026-10-31

Pixel_10_Pro, API 37, x86_64, SwiftShader, 120 generated silent WAV tracks.
Each state has 15 successful cold launches and complete asserted journeys.
Values are median / p90. Frame values summarize each whole journey, not scroll alone.

| State | Initial display ms | Frame CPU ms | Idle RSS KiB |
| --- | ---: | ---: | ---: |
| Debug, None | 1023.25 / 1045.78 | 51.54 / 52.28 | 169616 / 208384 |
| Optimized, None | 691.87 / 720.50 | 51.19 / 51.46 | 153804 / 162012 |
| Optimized, Partial | 655.25 / 675.97 | 51.20 / 52.12 | 151644 / 152584 |

Optimized None is 0.676 times debug startup and 0.907 times debug idle RSS.
Partial is 0.947 times optimized None startup. The profile increment is modest,
with overlapping ranges and non-interleaved state batches. Frame ranges overlap:
no meaningful frame-time improvement was established. A retained debug trace
averages 25.6 ms in RenderThread EGL swap and 16.8 ms in main-thread postAndWait.
No service/engine startup deferral was made without a measured safe change.

`None` resets compilation and skips profile installation. `Partial` requires the
packaged baseline profile with zero warmup iterations. Debug ran with the
DEBUGGABLE suppression; every state used EMULATOR suppression. Guest shader and
kernel page caches were cleared by Macrobenchmark COLD. The host disk-image page
cache was not cleared. CPU frequency was not locked. Host load at run start was
1.87 for debug and 2.51 for optimized on 24 cores. These are emulator ratios,
not predictions for the Poco or physical storage.

[Summary with ranges](runtime-benchmark-summary.json) and
[comparison chart](runtime-benchmark.png) record the samples' statistics.
Raw traces, JSON, XML and logcats remain in `logs/perf-android-runtime/`, outside Git.
The lane ledger is `docs/perf/android.tsv`; `self` means the commit adding the row.

## Profiles and runtime proof

The generated baseline has 35,501 rules including startup rules. Startup has
23,730 rules, with 3,288 Kainos entries in AGP's merged startup input. Sources are
`src/main/baseline-prof.txt` and `src/main/baselineProfiles/startup-prof.txt`.
Collection covers Home, Library fast scroll, local Now Playing and Search typing.
The non-minified `profile` target is profileable; optimized is profileable too.
BaselineProfileRule generates the files; no profile rules were invented.

The normal APK is 6,328,389 bytes versus debug's 31,795,904. It contains
`assets/dexopt/baseline.prof` at 16,760 bytes and `.profm` at 1,111 bytes.
Its SHA-256 is `eb4d81d501e0db8f6ce55f2528724ff281ba018d29c0b4f5df1c065e345b8c28`.
After app-module tests, rebuilding the normal APK reproduced this measured hash.
[APK inspection](optimized-apk-check.json) confirms matching signing identity and version.

The original optimized startup crashed before Activity creation. R8's usage file
showed Room's `WorkDatabase_Impl` no-argument constructor removed. A focused
production keep rule fixed that crash. Normal-APK Gradle-run UiAutomator tests
passed launch, Library, advancing local playback, pause, seek, next, Settings,
Devices and sync, and retained-heart verification after debug → optimized → debug.
All installs used `-r`; no uninstall, data clear or schema change was used.
Retention proves the app heart and usable synthetic library, not SAF permissions,
all settings, playlists or every stored row.

App-module tests passed the real Spotify login WebView and live NewPipe resolution,
playback position advance and pause. They need instrumentation-only keeps for
precompiled runner dependencies, mocks and public test-facing contracts after R8
inlines production callers. Those keeps are enabled only by
`-PandroidTestTarget=optimized`; that APK is not the measured or delivery artifact.
Normal-APK YouTube Search was attempted but blocked by absent provider credentials:
its screen said “Connect a provider” and requested a Data API key. Direct NewPipe
playback needs no key and passed. Spotify account playback remains a phone check.

## Rerun

Use the emulator-only [runtime harness](../androidMacrobenchmark/README.md).
It documents boot/shutdown, synthetic data, profile collection, all compilation
states, retention methods and optional network tests. Every Gradle command must
inherit `ANDROID_SERIAL=emulator-5554`. No interactive device tools are needed
for these Gradle-run tests. A physical phone must never be substituted.

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SERIAL=emulator-5554
scripts/perf/android-runtime seed profile
scripts/perf/android-runtime profile
scripts/perf/android-copy-profiles.py
# Use an owned private Xvfb DISPLAY for JVM UI tests, not the user's desktop.
flock -s /tmp/kainos-perf.lock ./gradlew :androidApp:assembleDebug :androidApp:assembleOptimized :androidApp:lintDebug jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable --continue --console=plain
scripts/perf/android-runtime measure debug none
scripts/perf/android-runtime measure optimized
python3 scripts/perf/android-apk-check.py androidApp/build/outputs/apk/debug/androidApp-debug.apk androidApp/build/outputs/apk/optimized/androidApp-optimized.apk --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

All listed checks passed on the final normal build. JVM XML totals are 927 tests,
zero failures, zero errors and three skips; cache/up-to-date results were reused.
The first JVM attempt failed on an unavailable X display and passed with private
Xvfb. Initial driver attempts had stale nodes and coordinate misses after flings;
the successful harness resolves accessibility actions and asserts playback advance.

## Phone acceptance and shipping

A plain `adb install -r` immediately gives R8 shrinking and startup DEX layout.
It does not establish profile-guided AOT compilation. ProfileInstaller supplies
profile data after launch; ART compilation timing depends on the device. Compare
first launch after sideload separately from later idle-compiled launches. See
[Android's profile guidance](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).

Only when explicitly asked to use the phone:

- Back up and compare the installed certificate. Update with `install -r`.
  Verify hearts, SAF folders, playlists, settings, logs and paused-session restore.
- Play SAF audio, pause, seek and skip. Check notification, lock-screen, Bluetooth
  and HyperOS island controls through several transitions.
- Use the real Spotify account for login, librespot playback and local/Spotify
  transitions. A WebView opening does not prove account playback.
- Configure YouTube search and play two results. Recheck library scroll and typing
  with real artwork and the user's actual library size.
- Test actual phone/PC sync only when both are available at home. Opening the
  Devices and sync screen is not an interoperability test.
- Roll back with the retained matching debug APK using `install -r` if necessary.
  Verify the same data. Never uninstall or use version-downgrade flags to hide failure.

When explicitly asked to ship, build without `androidTestTarget`, retain mapping
files, copy the chosen APK to repo-root `release/`, and publish an official GitHub
Release marked Latest with both the Android APK and Linux tarball. Keep debug
available until phone acceptance. This lane neither copied a release nor published one.
