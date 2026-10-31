# Gapless and first-play follow-up

Use JDK 17 and the packaged `KainosPlayer/lib/app` jars. All instances use synthetic
files, private configuration/data and either null audio or a private Pulse sink.
Never point these tools at the user's data, running app or speakers. See
`desktop-README.md` for dependencies and endpoint definitions.

```bash
python scripts/perf/gapless-checks.py
flock -x /tmp/kainos-perf.lock python scripts/perf/gapless-bench.py --mode playback --runs 10 --variant 'baseline=["-Dkainos.perf.noMpvWarmup=true"]' --variant 'prewarm=[]' --out /tmp/new-warm-ab
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode startup --runs 10 --xvfb /tmp/kainos-desktop-tools/usr/bin/Xvfb --variant 'baseline=["-Dkainos.perf.noMpvWarmup=true"]' --variant 'prewarm=[]' --out /tmp/new-warm-startup
flock -x /tmp/kainos-perf.lock python scripts/perf/desktop-bench.py --mode library --runs 10 --xvfb /tmp/kainos-desktop-tools/usr/bin/Xvfb --variant 'baseline=["-Dkainos.perf.noMpvWarmup=true"]' --variant 'prewarm=[]' --out /tmp/new-warm-library
flock -x /tmp/kainos-perf.lock python scripts/perf/gapless-warmup-proof.py --out /tmp/new-warm-proof
flock -x /tmp/kainos-perf.lock python scripts/perf/gapless-warmup-fallback.py --out /tmp/new-warm-fallback
flock -x /tmp/kainos-perf.lock python scripts/perf/gapless-pcm.py --out /tmp/new-warm-pcm
flock -x /tmp/kainos-perf.lock python scripts/perf/gapless-e2e.py --app-dir "$PWD/desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app" --out /tmp/new-warm-e2e
```

`gapless-checks.py` acquires the shared lock itself and preserves Gradle's exit
code. Timing sessions take the exclusive lock and retain failures and samples in
`--out`. Use a new output directory. The PCM tool runs the existing ten-join
sample/phase proof once. No page-cache eviction is done.

GUI warm-up begins one second after the first content draw. Its process launch
and readiness checks run on a worker. Hub-only never calls it; disabled gapless or an earlier URL playback request skips it. Missing mpv or a
failed prewarm is silent. Playback before warm-up completes still works, though
its worker may wait for the in-progress readiness check. Only the first local
compatible gapless start consumes the idle process. Network/non-gapless playback
continues through its usual path. This is a first-play-after-warmup improvement,
not a promise for a press within the first second after drawing.

Both A/B arms use identical jars with the stdin lifetime pipe. The property only
disables warm-up. The playback probe waits for warm-up plus 300 ms before starting
its unchanged press timer. The endpoint is `PlayerSession.play` to externally
observed mpv `playback-restart`, not acoustic onset. Its baseline is the persistent
mpv implementation; the older 105.3 ms fresh-process result is historical.

The mpv manual documents that `--input-ipc-client=fd://N` accepts a pipe and quits
on disconnect. Every system mpv process receives fd 0, whose writer is held by the
JVM. SIGKILL closes it without relying on JVM shutdown hooks. Normal shutdown also
kills the idle player and removes its socket. The proof checks idle `current-ao`
and `audio-params` are unavailable, no Pulse sink input exists for that mpv PID,
five seconds consume zero CPU ticks, and SIGTERM/SIGKILL leave no live mpv.

The SQLite flake was `SQLITE_ERROR cannot start a transaction within a transaction`
in `ListeningRecorderWriteTest`. Its in-memory JDBC driver uses SQLDelight's
`StaticConnectionManager`, sharing one connection while storage transactions run
on IO workers. The production file driver selects `ThreadedConnectionManager`.
The affected traced fixtures now use temporary file databases in WAL mode,
matching production. Storage, transaction semantics and schema are unchanged.
The hub SIGTERM test's child-start, exit and cleanup bounds are now 30 seconds;
these failure-only limits do not delay healthy execution.

Keep harnesses, summaries and verification in git. Raw environments, manifests,
logs and per-run captures are regenerated in `/tmp`. Original removed captures
remain at `/tmp/gapless-trimmed-captures` for this session; that path is temporary.

Accepted first-play result: ten interleaved pairs gave 119.29 -> 24.13 ms median,
p90 121.36 -> 25.12 ms, ranges 115.30-121.65 and 22.99-27.08 ms. No failures.
This also beats the historical 105.29 ms baseline by 81.16 ms. The timer still
includes source resolution, duration reading, IPC setup/loadfile and mpv's event;
process launch/readiness has moved before the timer. It does not measure the
speaker or audio hardware. Warm-up is deliberately paid after the first draw.

With packaged AppCDS flags, first draw was 503.21 -> 502.52 ms and Library
interaction 693.39 -> 695.02 ms, ten interleaved pairs each, zero errors. These
are unchanged within noise. The 1679 ms baseline startup outlier is retained.
Without CDS the corresponding medians were 968.43 -> 964.04 ms and
1270.39 -> 1264.25 ms. Both arms have cold JVMs and a warm OS page cache.
Load was around 1 on 24 logical CPUs. Summaries are in `gapless-results/`;
raw evidence is `/tmp/gapless-warm-final-ab`, `/tmp/gapless-warm-cds-startup`,
`/tmp/gapless-warm-cds-library` and `/tmp/gapless-verified-idle`.

Sol correctness follow-up (2026-10-31): `gapless-stress.py` opts into
`GaplessBoundaryStressTest` with real mpv, synthetic 200 ms to 2 s WAVs,
fixed seed 20261031 and null audio output. It performs 240 queue edits, skips,
seeks, pauses and resumes. Two independent IPC clients require up to 30 ms
for event settlement. Samples with missing or negative audio-pts, paused or
non-playing state are counted separately, not represented as verified audio
identity. This is **not** a proof of identity at every instant or every sample.
`gapless-short-probe.py` reproduces the separate decoder/output timelines with
2 s + 50 ms WAVs. Run it under `flock -x /tmp/kainos-perf.lock`.
The short-successor engine test requires publication after the outgoing 2 s.
For a missing clock, the engine waits for mpv's idle/output-drained event;
if no evidence arrives within 2 s of unpaused waiting it discards lookahead and
ends the outgoing entry so the session resolves its successor normally.
A successor shorter than the output buffer may be identified only after drain.
S5 reloaded the owning entry on every gapless seek. The follow-up below
supersedes that path with successor removal and an owning-entry check.

### Seek ownership and lookahead recovery

The S5 unconditional restart is replaced by an IPC FIFO seek. It reads the
playlist, resolves the bound successor ID to its index, removes that entry, then
reads the playing entry ID again. Only the owning entry may receive `time-pos`.
With the successor removed, EOF can enter idle but cannot advance to another
file. Failed checks or IPC use the owning-file restart. The valid successor is
retained across internal teardown and re-prepared after startup. Explicit stop,
new playback, disabling gapless and `prepareNext(null)` invalidate it. A failed
same-file retry rebinds its generation. A separate seek sequence lets pause after
seek retain the last target while dropping superseded slider requests.

`GaplessPcmTest` now seeks to 500 ms while paused after preparing the successor,
then resumes and waits for the automatic boundary, with no caller re-preparation.
It requires one process, 154350 rendered samples and a phase-continuous join at
sample 66150. The real-mpv stress test includes seeks 1–39 ms before EOF.

Reproduce the seek comparison with immutable baseline/restart jars retained
before rebuilding the candidate. The original baseline needs its matching model
jar because EngineState gained fields since that build. All other dependencies
and the probe are identical. The harness records artifact hashes on new runs.

```bash
# Compile under the shared lock before timing. Use JDK 17.
flock -s /tmp/kainos-perf.lock ./gradlew :core:playback:jvmTestClasses :core:playback:jvmJar
flock -x /tmp/kainos-perf.lock python scripts/perf/seek-bench.py \
  --out /tmp/new-seek-comparison \
  --app-dir desktopApp/build/compose/binaries/main/app/KainosPlayer/lib/app \
  --baseline-jar /path/to/baseline/playback-jvm.jar \
  --baseline-model-jar /path/to/baseline/model-jvm.jar \
  --restart-jar /path/to/0e3bd0b/playback-jvm.jar \
  --head-jar core/playback/build/libs/playback-jvm.jar
```

The default measures the first seek in each fresh JVM. `--warm` adds one untimed
seek to every arm before preparing the successor, and reports steady use
separately. Both use ten interleaved runs per arm, warm OS page cache, a synthetic
10 s FLAC, and a private Pulse sink. The timer starts immediately before the
engine call and ends in the observer thread when mpv's `playback-restart` arrives;
`time-pos` must then match 4 s. No physical sound-device latency is measured.
PCM gaps are low-energy 1 ms blocks between the first and last audible block.
The storm sends twenty targets at 100 ms intervals, pauses immediately after the
last call and verifies that mpv ends at 3.9 s with no additional process.

Cold first-seek and warmed results are kept separately; no warmup is silently
discarded from the cold series. The baseline within-noise criterion is evaluated
against both, rather than treating zero PCM silence as proof of equal latency.

Final seek evidence: `gapless-results/seek-summary.json` retains all sixty samples,
medians, p90s, hashes and calibration failures. Cold seek median/p90 in ms:
baseline 2.42/2.75, `0e3bd0b` 58.59/60.05, candidate 5.12/5.45. Ranges are
2.20–3.03, 57.59–60.08 and 4.89–5.80. Cold PCM silence median/p90 is
0/0, 42/85 and 0/0 ms respectively. Additional process counts total 0, 10 and 0.
The twenty-seek storm spawns zero processes and pauses at 3.899977 s for 3.9 s.

The strict baseline latency target remains unmet. The warmed medians are
1.03, 55.72 and 2.26 ms; candidate and baseline ranges still do not overlap.
Do not label either series as baseline-equivalent. Warmed PCM measures the
whole run including its extra warmup seek, so only the cold capture isolates
one seek. The remaining latency cost has not been profiled separately.

Final correctness: stress passed 240 operations including near-EOF seeks,
257 resolved matches, 243 unresolved samples and zero settled mismatches.
Ten PCM joins after seek have zero sample or phase error and use one process.
Full checks passed with exit 0 through `gapless-checks.py`, which wraps the exact
requested Gradle tasks in the shared lock and supplies private Xvfb. A first
attempt without Xvfb failed UI initialization; it is retained, not hidden.
No schema change, user data, physical phone or user audio output was involved.
Raw evidence is `/tmp/kainos-seek-final-{cold,warm,pcm}`, with logs
`/tmp/kainos-seek-final-{checks,stress,pcm}.log`. The restart jar was rebuilt
from `0e3bd0b` and verified byte-identical to the measured arm.
