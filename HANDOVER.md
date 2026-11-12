# Current handover

Written 2026-11-11 for local `main` at `9c1fad7` (before this docs commit). Read `docs/CURRENT_STATE.md` for what works and what does not. October's storage-redesign handovers are archived in `docs/handovers/`.

## Where things stand

- 2026-11-12: multi-select for Home sync (pending copies, skipped copies and conflicts; `a160827`, `31f9757`, docs `5682a7f`) is on `main`, pushed, and released as `linux-android-2026-11-12` (Latest, versionCode 1000643, schema 10). The phone has 1000643 and the PC package and `kainos-hub.service` run it. Not yet tried with a real pairing.
- `main` holds the whole 2026-11-11 program and is pushed to `origin`. The owner asked for a release: `linux-android-2026-11-11` (Latest) is built from `88524e9` (versionCode 1000639) with the Linux tarball and the debug APK. GitHub CI for `88524e9` stuck in its queue, so the CI check set ran locally: 2,492 tests, 0 failures, plus the migration check, the optimized build and lint.
- Schema is 10 (migrations `1.sqm` to `9.sqm`). The Android version code is schema × 100,000 plus the commit count, so an older-schema APK is refused as a downgrade.
- Merged, in order (`git log --first-parent main -13`): `aae92f0` Frost size fix, `7ef57dc` Frost polish, `4cd2486` dock motion, `f126234` ultrawide layouts, `c4833ff` Android Spotify streaming and the librespot cache, `39707e6` Spotify on mobile data round 2, `0c2742e` playback and platform fixes, `2cdbba4` local file tags, `669c36f` collection pages, `505f025` loudness levelling, `d5620cc` YouTube download fixes, `9c1fad7` queue, Shuffle and Hide duplicates. Each "reviewed" merge was reviewed and fixed on its own branch. Reports for two of them are in `docs/reviews/`.
- The combined head has no recorded full test run. Run the commands below first.

## Owner decisions

- Lyrics are dropped.
- The branch that would write to Spotify Liked Songs (`feat/spotify-heart-choice`) was dropped.
- YouTube stays as a fallback source for songs Spotify lacks. Fix it when it breaks; do not extend it.
- The rule against caching Spotify audio on the device was lifted on 2026-11-11. The receivers' disk caches are on. Kainos still offers no Spotify offline library.

## Still to test by the owner

On the phone, nothing from this program has run yet:

- Streamed Spotify start, the 1 GiB cache, recovery after a network switch or takeover, and the rate-limit pause (a message, no skip).
- Android radio and Discover Weekly through the receiver's session, and the Home shelf keeping its last good list.
- Tags, albums, artists and sorts on a real library; collection pages and Go to album / Go to artist / Start radio; Search Albums and Artists.
- Hide duplicates, Shuffle, the queue's Clear upcoming and Stop radio.
- Volume levelling on analysed files, which needs the PC to measure first and then a Sync now.
- Download on Wi-Fi only, failure reasons and Remove staying removed.
- A skip after a failed track staying paused in the background on HyperOS.
- Installing over the current build keeps data (`adb -t <transport> install -r`). Re-list transports first; they change.

On the desktop:

- Ultrawide layouts (2000 dp and up), dock drag and motion, and the Frost size fixes.
- The first loudness pass over the library (about 4 minutes for 561 files), and the Spotify receiver cache.
- The hub service: opening the GUI stops it, closing the GUI starts it again; `--hub-only` binds no media keys.
- After pulling, run `scripts/install-librespot.sh` (builds the radio-capable helper) and `scripts/install-yt-dlp.sh` (updates every yt-dlp copy).
- The launcher runs the checkout it was installed from and only rebuilds with `--rebuild`. A plain launch can run a stale package.

## Logs and where things are

- Desktop GUI runs: `logs/desktop-run-latest.log`. `--hub-only` runs: `logs/hub-run-latest.log`. Both are gitignored.
- Phone: Settings → Advanced → Playback log (shareable), or the `KainosTrace` logcat tag. Phone screenshots and shared logs go in `~`.
- Per-connection Home sync detail is off unless `KAINOS_TRACE_DEBUG=1` on desktop.
- Echo gateway: runs from the main checkout's `tools/echo-gateway` under `kainos-echo-gateway.service`; now-playing API on `127.0.0.1:8788`.
- A JVM crash leaves `hs_err_pid*.log` in the working directory. They are gitignored.

## Checks

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
./gradlew jvmTest :core:playback:testAndroidHostTest :core:data:verifyCommonMainKainosDatabaseMigration :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
```

Build serially. Do not use adb, restart the owner's app, or take over the owner's display for tests: use a private Xvfb (`scripts/perf/private_xvfb.py`) for UI proof.

## Known gaps

- `AppContainer` has no test of its own (`docs/ARCHITECTURE.md`).
- Many old local branches remain unmerged. Treat them as stale unless the owner names one.
- Sync between an old and a new build of the phone and PC has not been tested on real devices.
