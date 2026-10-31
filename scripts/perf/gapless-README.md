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
Gapless seeks reload the owning entry because JSON IPC cannot atomically guard
`time-pos` by playlist entry. This trades seek startup work for file ownership.
