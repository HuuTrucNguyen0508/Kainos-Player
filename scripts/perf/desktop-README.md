# Desktop startup and transition measurements

Final changes: read a local FLAC's first 42 bytes for STREAMINFO before falling back to ffprobe; optionally train an AppCDS archive after packaging. Other codecs and network streams retain ffprobe. Unknown FLAC sample counts also fall back. No cache freshness assumption, schema change, PlayerSession change, or shared/sync edit is needed. The header layout follows [RFC 9639 §8.2](https://www.rfc-editor.org/rfc/rfc9639.html#section-8.2).

## Reproduce

Use a new output directory for every measurement. Builds/tests use the shared lock; timing and snapshot sessions use the exclusive lock. All benchmark commands start Temurin java on `createDistributable` jars with the launcher's classpath/system properties. They never use `gradle run`.

```bash
flock -s /tmp/kainos-perf.lock env KAINOS_CDS_XVFB=/path/to/Xvfb python scripts/perf/desktop-checks.py --xvfb /path/to/Xvfb
python scripts/perf/desktop-launcher-test.py
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-duration-regression.py --out /tmp/duration-check
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-launcher-e2e.py --out /tmp/launcher-check
```

`desktop-checks.py` uses JDK 17, serializes builds in this worktree, runs root `jvmTest`, database migration verification and desktop packaging, and supplies a private display. No physical phone or user desktop is used. Xvfb can be an unpacked executable; this run used `/tmp/kainos-desktop-tools/usr/bin/Xvfb`. Required playback tools are mpv, ffmpeg and psutil. PCM capture additionally uses numpy and PulseAudio input through ffmpeg/pactl.

For startup and Library, interleave ten runs per JVM configuration:

```bash
perf_app_dir="$PWD/desktopApp/build/compose/binaries/main/app/Kainos Player/lib/app"
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode startup --runs 10 --cds-archive "$perf_app_dir/kainos.jsa" --xvfb /path/to/Xvfb --out /tmp/startup-ab
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode library --runs 10 --cds-archive "$perf_app_dir/kainos.jsa" --xvfb /path/to/Xvfb --out /tmp/library-ab
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode idle-gui --runs 10 --jvm-flag=-Xshare:auto --jvm-flag="-XX:SharedArchiveFile=$perf_app_dir/kainos.jsa" --xvfb /path/to/Xvfb --out /tmp/idle-gui
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode spotify --runs 10 --xvfb /path/to/Xvfb --out /tmp/spotify-startup
```

For the backend A/B, build `:core:playback:jvmJar` at base commit `5087402` in a separate checkout and retain that jar. Snapshot the current package twice, substituting only the baseline playback module in one copy. This keeps the probe and dependencies identical. Snapshot creation verifies content hashes and refuses an existing destination.

```bash
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-snapshot.py --out /tmp/desktop-baseline --playback-jar /path/to/base/playback-jvm.jar
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-snapshot.py --out /tmp/desktop-candidate
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode playback --capture-pcm --runs 10 --compare-app-dir /tmp/desktop-baseline --app-dir /tmp/desktop-candidate --out /tmp/playback-ab
```

This stage isolates the FLAC reader with AppCDS disabled in both arms. The original baseline jar's MD5 is `a977f65831d9501bad578adc8f60c86b`; its SHA-256 and complete snapshot manifests are retained in `desktop-results/`. The actual-launcher exercise separately verifies the generated archive is mapped and the same transport operations work.

## What the endpoints mean

- Startup starts before launching java. `first_draw_ms` ends after the first Compose content draw callback. `mapped_ms` ends when the private X server reports a mapped nontrivial window. Drawing is not proof of physical monitor presentation.
- Library waits for AppContainer readiness, sends the existing type-to-search request, verifies the text was consumed and the search field focused, then waits for two frame callbacks. The fixture is an empty isolated Library, not a restored 512-track library.
- Playback starts at the real `PlayerSession.play` call and ends at mpv's `playback-restart` event. An external observer connects only to this probe JVM's mpv children; it inserts no wrapper into the playback path. ffprobe child observations explain the removed work.
- Pause/resume start at the engine call and end at mpv's pause-property notification. They measure acknowledgement, not hardware drain latency.
- Three real generated FLACs exercise pause, resume, seek, skip and natural completion. A private null sink is recorded as PCM; 660 Hz and 880 Hz identify the naturally consecutive files. Silence is counted in five-millisecond low-energy blocks between them. This is recorded audio, not event spacing, but it cannot establish sample-accurate gapless.
- `idle-gui` installs and verifies a synthetic 512-entry queue paused in the real GUI, settles for two seconds and samples for five. RSS is the JVM's VmRSS; CPU is percentage of one core, with about 0.2 percentage point tick resolution. Playback-only probe RSS/CPU is separate and excludes Compose UI.

All startup comparisons are cold JVMs, private Xvfb software rendering and synthetic isolated data. The OS page cache was **not dropped**. No real accounts, library, speakers or phone were used. Spotify ran only when no Spotify process existed, with private config/cache, no playback, and SIGTERM cleanup. Compare mapped-window endpoints between apps; do not equate Spotify's login window with Kainos Library readiness.

## AppCDS behavior and limits

Packaging trains Home and Library on a private display, validates the archive with the same JDK/classpath, and installs it atomically with a size manifest. The launcher uses `-Xshare:auto` only for a complete archive. Missing Python/Xvfb does not prevent packaging. Missing/truncated archives skip sharing; valid stale archives fall back in the JVM. The real-JVM fallback helper checks missing and stale archives; launcher tests cover incomplete files. Forcing a tiny corrupt file directly on this JDK exited before main, so the launcher guards against incomplete archives and the trainer validates before installation.

Archives are specific to the JDK, jar set and absolute classpath. Moving the package or upgrading the JDK can require retraining; fallback keeps the app usable. The measured tradeoff is roughly 40–53 MiB more GUI RSS. Normal JIT and GC settings remain unchanged.

## Results, noise and rejected attempts

Final data is `accepted-ab-*`, `accepted-startup-*`, `accepted-library-*` and `accepted-idle-*` in `desktop-results/`, with every raw numeric sample and summary. Captures and full logs remain under matching `/tmp/kainos-desktop-accepted-*` directories. The ledger is `docs/perf/desktop.tsv`.

All 20 final FLAC runs passed, with four observed ffprobe starts per baseline queue run and zero in the candidate. Correctness checks cover deliberately wrong supplied duration, 24-bit/96 kHz stereo, WAV, and FLAC with an unknown sample count. The rejected cached-duration implementation reports 123 seconds for a real two-second file and fails the same check; fresh-file probing passes.

The final startup series retains a roughly 3.8-second sample in **both** arms, plus a baseline 1.6-second sample. All ten paired differences favored CDS; median paired improvement was 94.4 ms. Report a median improvement, not a worst-case improvement. Prior clean interleaved sessions also found about 8–9%. An early non-interleaved archive showed a 50% result that was not reproduced by the final package; it is recorded, not shipped as a claim.

Rejected/deferred: SerialGC gained under 5%; an extra second of training warmup gave no measurable benefit. TieredStopAtLevel 1/2 improved first draw but cap later optimization, so they were left out without evidence about longer-running UI behavior. Persistent mpv with sample-accurate prefetch needs a next-track/preference contract outside this lane; existing fresh-process semantics remain.

Calibration failures are retained: `/tmp/kainos-desktop-baseline-playback` lacked a standalone Main dispatcher; `/tmp/kainos-desktop-baseline-pcm` used malformed fixture URLs; `calibration-wrapper-*` includes Python-wrapper overhead and is superseded; `/tmp/kainos-desktop-baseline-native` and `/tmp/kainos-desktop-profile` used a package damaged by concurrent packaging. The original one-run Spotify attempt measured 3552 ms before refusing a second launch while its child exited; corrected runs wait after SIGTERM. None of these are silently pooled into final timings.

The separate JFR profile `/tmp/kainos-desktop-startup-valid.jfr` had only 25 execution samples, 14 on AWT. Three leaves were ZIP entry lookup; this is too small for precise attribution. AppContainer constructor markers in ten default runs had median 237.5 ms, range 234.1–242.4 ms. Shared wiring and hub internals were left unchanged.
