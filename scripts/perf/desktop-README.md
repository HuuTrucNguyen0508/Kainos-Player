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
perf_app_dir="$PWD/desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app"
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

This stage isolates the FLAC reader with AppCDS disabled in both arms. The original baseline jar's MD5 is `a977f65831d9501bad578adc8f60c86b`; its SHA-256 is retained in `desktop-results/engine-jars.json`; complete snapshot manifests remain under `/tmp/kainos-desktop-accepted-packages/`. The actual-launcher exercise separately verifies the generated archive is mapped and the same transport operations work.

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

Historical metric summaries are in `desktop-results/history.tsv`. Every raw numeric sample, capture and full log remains under matching `/tmp/kainos-desktop-accepted-*` directories for this session; these temporary files do not survive cleanup of `/tmp`. The harness regenerates them. The ledger is `docs/perf/desktop.tsv`.

All 20 final FLAC runs passed, with four observed ffprobe starts per baseline queue run and zero in the candidate. Correctness checks cover deliberately wrong supplied duration, 24-bit/96 kHz stereo, WAV, and FLAC with an unknown sample count. The rejected cached-duration implementation reports 123 seconds for a real two-second file and fails the same check; fresh-file probing passes.

The final startup series retains a roughly 3.8-second sample in **both** arms, plus a baseline 1.6-second sample. All ten paired differences favored CDS; median paired improvement was 94.4 ms. Report a median improvement, not a worst-case improvement. Prior clean interleaved sessions also found about 8–9%. The follow-up below explains and reproduces the earlier 50% archive result; the former attribution to a training artifact was incorrect.

Rejected/deferred: SerialGC gained under 5%; an extra second of training warmup gave no measurable benefit. TieredStopAtLevel 1/2 improved first draw but cap later optimization, so they were left out without evidence about longer-running UI behavior. Persistent mpv with sample-accurate prefetch needs a next-track/preference contract outside this lane; existing fresh-process semantics remain.

Calibration failures are retained: `/tmp/kainos-desktop-baseline-playback` lacked a standalone Main dispatcher; `/tmp/kainos-desktop-baseline-pcm` used malformed fixture URLs; `calibration-wrapper-*` includes Python-wrapper overhead and is superseded; `/tmp/kainos-desktop-baseline-native` and `/tmp/kainos-desktop-profile` used a package damaged by concurrent packaging. The original one-run Spotify attempt measured 3552 ms before refusing a second launch while its child exited; corrected runs wait after SIGTERM. None of these are silently pooled into final timings.

The separate JFR profile `/tmp/kainos-desktop-startup-valid.jfr` had only 25 execution samples, 14 on AWT. Three leaves were ZIP entry lookup; this is too small for precise attribution. AppContainer constructor markers in ten default runs had median 237.5 ms, range 234.1–242.4 ms. Shared wiring and hub internals were left unchanged.

## Follow-up: the 534.9 ms result was real

The original dynamic archive and immutable package survived in `/tmp`. Ten new
interleaved runs reproduced 1067.2 → 546.7 ms first draw, p90 1098.7 → 566.5.
The timer still starts immediately before `Popen(java)` and stops at the same
Compose `drawWithContent` callback, after drawing the app. It was not a blank-window
endpoint. The original benchmark simply did not collect mapped-window times.

The decisive difference was the space in the packaged `Kainos Player` directory.
On this Temurin 17, the archive maps successfully but most application classes then
load from jars. The preserved space-free snapshot used 7,916 dynamic shared classes;
the old shipped path used only 1,859 in separate diagnostic traces. A copied current
package without spaces recovered 544.9 ms. A symlink back to the spaced directory
failed to recover it. Changing the actual package directory to `KainosPlayer`
recovers sharing without changing the app's window title, JVM verification or JIT.
A repository parent path containing spaces can still lose this optimization.

Both archives are dynamic overlays on the bundled JDK static archive, with no
explicit class list, heap setting or tier cap. The old archive has 9,276 printed
class entries and 59,363,328 bytes; the new archive has 10,139 and 66,011,136 bytes.
The old training run reached first draw at 1472.3 ms and exited about 100 ms later
at 1567 ms. New training reaches first draw and a real, focused Library search render
before terminating, with no extra settling delay. Both use the alphabetically sorted
126-jar classpath and the launcher's resources/Skiko system properties. Old training
flags were only `ArchiveClassesAtExit`; JFR was used in a different, earlier archive.
Increasing training coverage did not explain the missing speedup.

The final controlled comparison uses byte-identical jars copied into a spaced
snapshot, trained through Library, against the packaged space-free path. All ten
pairs succeeded, with no discarded samples. Median/p90 milliseconds:

| Endpoint | Spaced package | Space-free package |
| --- | ---: | ---: |
| First Compose draw | 958.8 / 982.1 | 545.3 / 547.8 |
| Mapped window | 980.6 / 1007.2 | 561.0 / 570.6 |
| Focused Library search, two frames | 1224.2 / 1248.9 | 730.5 / 737.0 |

Raw runs and screenshots are `/tmp/kainos-final-accepted`; compact results are
`desktop-results/startup-path-summary.json`. An earlier `final-path-ab` attempt
has ten baseline errors because packaging removed the old package directory. Those
failures are retained in `/tmp` and are excluded from this successful comparison.

### Startup attribution

`desktop-profile.py --bench /tmp/kainos-final-accepted --trace
/tmp/kainos-profile-accepted-full/0-default/run.log` reports three disjoint spans.
Before wiring: 220.7 → 128.3 ms; AppContainer wiring: 217.3 → 93.3 ms;
after wiring through first composition/draw: 521.8 → 322.4 ms. These are medians of
per-run spans, so they need not sum exactly to the median total. Class loading and
initialization occur throughout these spans, rather than forming a separate
additive bucket. The last span includes window construction, composition, layout,
font work and Skia drawing; it is not pure GPU or composition CPU time.

A separate diagnostic run with `-Xlog:class+load=info,startuptime=info,library=debug,cds=info`
used 9,905 shared classes out of 10,137 loaded, including 8,782 dynamic shared classes.
VM creation completes at 35 ms; dynamic mapping begins at 7 ms. Skiko's class arrives
at 67 ms and its existing packaged native library loads at 70 ms. No Skia extraction
occurs. SQLite NativeDB arrives at 227 ms and the newly extracted SQLite native
library loads at 269 ms on the background initialization path. This 42 ms bound
includes extraction/loading and intervening scheduling, not the whole database open.
Schema/open cost and MPRIS registration were not independently instrumented;
MPRIS setup is inside the wiring span. The isolated fixture has hub autostart off,
so there is no hub-listener startup cost in these samples. No claim about a paired,
restored real library's DB or hub cost follows from this fixture.

### Rejected candidates and comparison limits

Ten interleaved runs per arm on the corrected package: dynamic CDS 546.9 ms;
Library-trained static CDS 528.6 ms; `-XX:-UsePerfData` 538.6 ms;
`-Xshare:on` 537.4 ms. All are under 5%, so stop after these three rejections.
`desktop-static-trial.py` accepts the class list captured with
`--jvm-flag=-XX:DumpLoadedClassList=/tmp/library.classes` during a Library run.
Tier 1 improved the earlier spaced configuration from 969.5 to 865.1 ms, but sustained
UI smoothness is unproved. Disabling remote bytecode verification gave 860.8 ms,
but removes verification of application/dependency code and is rejected. `-Xverify:none`
is not shipped either. No JDK newer than 17 is installed locally, so
`AutoCreateSharedArchive` on JDK 19+ was not tested. Jar count/order remains unchanged;
no trimming or reordered-classpath speed claim is made. Lazy hub/DB/MPRIS and an early
window were not changed: recovering class sharing removed the largest proven cost,
and mapping a placeholder window would not improve this usable-UI endpoint.

Spotify's ten fresh isolated runs map at median 303.1 ms, p90 314.5, and first
textured screenshot at median 667.1 ms, p90 827.5. All succeeded; the 3646.5 ms mapped
and 4133.9 ms paint outlier remain. Screenshot polling adds capture overhead. The
screenshots show rendered login text/button content, sometimes without its QR image.
This is a login paint, not an authenticated music library or proven interaction.
Kainos's 730.5 ms endpoint verifies search consumption/focus and two subsequent frames
in an empty Library. Do not call these equivalent or claim Kainos beats Spotify.
`--paint` retains private-display screenshot evidence and never plays audio.

Raw capture directories are ignored by the appended `.gitignore` rules. Keep only
compact summaries, verification status and playback artifact hashes in this directory.
No images, logs, per-run manifests or generated archives belong in the repository.
