# Android optimized candidate

Debug stays the published default until the owner has run the manual checks the
[release gate](#release-gate) prints on the phone. No agent can run them. After that,
`optimized` is the recommended build: on the emulator it starts faster, uses less
idle RSS and is about 80% smaller. No release or physical-phone install was made.

## Version

`versionCode` is the commit count and `versionName` is the commit date, so
`2026-11-01` matches the tag `linux-android-2026-11-01`. Both appear in
**Settings → Advanced**. Override them with `-PkainosVersionCode` and
`-PkainosVersionName`, for example for a second release on one day.

A shallow clone counts only its fetched commits and yields a low code, so CI passes
the override and the release gate rejects a code of 1. The count falls after a
rebase or squash, so build shipped APKs from `main`.

## Rolling back

Android refuses an older `versionCode`, so a retained APK can no longer simply be
reinstalled with `-r`. Every row was run on the emulator (API 37).

| Installed | Installing | Command | Result |
| --- | --- | --- | --- |
| Debug | Older debug or older optimized | `adb install -r -d` | Works: `-d` needs a debuggable installed app |
| Optimized | Anything with an older code | `adb install -r -d` | Refused, `INSTALL_FAILED_VERSION_DOWNGRADE` |
| Optimized | Debug or optimized rebuilt with a higher code | `adb install -r` | Works |
| Rotated key | Debug certificate, same or higher code | `adb install -r` | Works only with rollback capability in the lineage |

To leave an optimized build, build the known-good commit with a code above the
installed one, then update normally. Debug and optimized both work:

```bash
./gradlew :androidApp:assembleDebug -PkainosVersionCode=<installed + 1>
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Commits before this scheme have no override, so edit their `versionCode` by hand.
An older database schema still fails at launch with "Can't downgrade database";
no data is lost, and installing a newer build recovers it. Never uninstall.

## Signing

`optimized` uses the debug certificate unless a release key is configured. Configure
one through a gitignored `signing.properties` (see `signing.properties.example`) or
the `KAINOS_SIGNING_STORE_FILE`, `KAINOS_SIGNING_STORE_PASSWORD`,
`KAINOS_SIGNING_KEY_ALIAS` and `KAINOS_SIGNING_KEY_PASSWORD` variables. Partial
configuration fails the build. Keep keystores outside this repository.

**A release key alone cannot update the phone.** Android rejects it with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and uninstalling deletes the library, hearts
and folder grants. Follow the steps below instead.

### Adopting a release key

Rotation adds a v3 proof, signed by the old key, that the new key succeeds it. Gradle
cannot apply a lineage, so `scripts/android-sign-rotated.py` does.

1. Back up `~/.android/debug.keystore`, alias `androiddebugkey`, password `android`.
   Its SHA-256 is `780b3387b1f828858b3e3c27e59117a5ab24f171bc27db17055f2d3a93961895`.
   This key is the root of the lineage. **Keep it, and the lineage file, for as long
   as the app exists.** Losing them means no more updates on API 26-27 and no way to
   rebuild the rotation.
2. Create the release key, outside the repository:

   ```bash
   keytool -genkeypair -keystore ~/keys/kainos-release.jks -alias kainos-release \
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kainos Player"
   ```

3. Prove the rotation once. `--set-rollback true` keeps debug-certificate APKs
   installable as a rollback; without it they are refused after the first rotated
   install, and only an uninstall gets back.

   ```bash
   apksigner rotate --out ~/keys/kainos.lineage \
     --old-signer --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey \
       --ks-pass pass:android --key-pass pass:android --set-rollback true \
     --new-signer --ks ~/keys/kainos-release.jks --ks-key-alias kainos-release
   ```

4. Write `signing.properties`, build, sign and update:

   ```bash
   ./gradlew :androidApp:assembleOptimized
   scripts/android-sign-rotated.py --lineage ~/keys/kainos.lineage
   adb install -r androidApp/build/outputs/apk/optimized/androidApp-optimized-rotated.apk
   ```

Ship the script's output, never Gradle's new-key APK. The script prints each API
range's signer and fails if rotation did not apply.

**API 28 and later** read the v3 block and accept the new key through the lineage,
keeping app data, SAF grants and Keystore identities. **API 26-27 ignore v3.** They
verify the v2 signature, which the script makes with the old debug key, so those
devices keep updating in place but stay on the debug certificate. The new key adds
nothing there, and every release must carry the old signer while minSdk is below 28.

Proof on the emulator, API 37, using a throwaway key since deleted: a debug-certificate
optimized build stored a heart; `adb install -r` of the rotated APK succeeded;
`firstInstallTime` was unchanged; the signature moved to v3 with the debug certificate
in `past signatures`; `RuntimeSmoke#verifyRetainedFavorite` then passed. With rollback
capability the debug-certificate APK updated back in place and the heart survived.
Without it, and for a new key with no lineage, Android refused the install and the
data stayed intact. API 26-27 is verified with `apksigner verify`, which reports the
debug certificate for that range; it has not been run on such a device.

## Network security

`src/main/res/xml/network_security_config.xml` denies cleartext everywhere except
`127.0.0.1`, which the Spotify OAuth redirects use. Library code has no fixed `http://`
endpoints: librespot's only literal is that redirect, and NewPipe's are scheme checks.
`AndroidCleartextPolicyTest` proves the policy with live requests, through both the
network stack and WebView. It failed as expected when the policy was opened.

Spotify picks its audio CDN URLs at runtime and this was not tested with an account.
If live playback fails with a `CLEARTEXT` error in logcat, the CDN host needs an
entry in that file. `http://` artwork URLs now fail quietly and fall back.

## Release gate

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
scripts/android-release-gate.py --serial emulator-5554
```

The gate refuses a physical device. With `--allow-physical` it checks the certificate
and runs `adb install -r`, nothing else: its other stages clear data and revoke
grants. It writes `logs/android-release-gate-<time>/summary.txt`, exits non-zero on
any failure, and ends with the manual checks as an unchecked list.

On an emulator it regenerates the baseline profile (`--skip-profile` keeps the
committed one and reports how stale it is), builds, verifies the APK, installs it,
smoke-tests that exact APK, then runs each instrumented class and first-run stage.
Commit regenerated profiles. Instrumented classes use `-PandroidTestTarget=optimized`,
whose APK has extra test-only keep rules; only the smoke stage proves the shipped APK.
Those runs overwrite `androidApp-optimized.apk`, so ship the verified copy the gate
keeps beside its summary, or rebuild without `androidTestTarget`.

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
[APK inspection](optimized-apk-check.json) confirmed the signing identity and version of that build.

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

- Back up and compare the installed certificate (the gate does this with
  `--allow-physical`). Update with `install -r`.
  Verify hearts, SAF folders, playlists, settings, logs and paused-session restore.
- Play SAF audio, pause, seek and skip. Check notification, lock-screen, Bluetooth
  and HyperOS island controls through several transitions.
- Use the real Spotify account for login, librespot playback and local/Spotify
  transitions. A WebView opening does not prove account playback.
- Configure YouTube search and play two results. Recheck library scroll and typing
  with real artwork and the user's actual library size.
- Test actual phone/PC sync only when both are available at home. Opening the
  Devices and sync screen is not an interoperability test.
- If it fails, follow [Rolling back](#rolling-back) and verify the same data.
  Never uninstall.

When explicitly asked to ship, build without `androidTestTarget`, retain mapping
files, copy the chosen APK to repo-root `release/`, and publish an official GitHub
Release marked Latest with both the Android APK and Linux tarball. Debug stays the
published APK until phone acceptance. This lane neither copied a release nor published one.
