
### Review fixes, 2026-10-31

S1 compared `--variant='baseline=[]'` with
`--variant='verify=["-XX:+VerifySharedSpaces"]'` using `desktop-bench.py`,
`--mode library --runs 10`, the same packaged archive through `--jvm-flag`,
and private Xvfb. Raw data: `/tmp/kainos-s1-verify-ab`; load 0.93 on 24 CPUs,
exclusive lock, fresh isolated JVMs, warm OS cache, zero errors.
First draw was 502.84 -> 519.50 ms, p90 510.81 -> 533.12;
Library interaction 693.76 -> 704.79 ms, p90 702.51 -> 718.27.
The first-draw cost exceeds 15 ms, so verification does not run on every launch.
The archive trainer now writes a SHA-256 manifest. The launcher checks it in a
background process and removes a mismatching archive. The first corrupt launch
may fail; subsequent launches omit the archive. Packages without a digest also
omit CDS. `desktop-cds-fallbacks.py` now corrupts a same-size cloned archive,
waits for its removal, and requires a complete real-JVM playback launch afterward.
No user package or data is changed by that test. Launcher unit tests also cover
valid archives and removal/fallback after same-size corruption.

S2 keeps the fresh duration shortcut but validates STREAMINFO field ranges,
walks metadata headers with seeks over payloads, and requires audio sync at the
first frame offset. ID3-prefixed files still fall back to ffprobe. Only the
probe function changed in DesktopPlaybackEngine.kt. Six focused tests pass;
five failed on the old implementation, including all three requested cases.

Ten interleaved normal-FLAC playback runs per arm measured press-to-start
98.55 -> 95.91 ms, p90 101.33 -> 99.09, ranges 93.47–103.43 and 93.34–99.51.
This establishes no measurable regression, rather than a new speedup: the
ranges overlap. Both arms spawned zero ffprobe processes and completed real
mpv playback with null output. The earlier 132.74 -> 101.69 ms gain survives.
Raw data: `/tmp/kainos-s2-probe-ab`; compact summaries of both review measurements
are in `desktop-review-results.json`. Reproduce with `desktop-bench.py --mode
playback --runs 10 --compare-app-dir <package-with-old-playback-jar> --out <path>`.
All other packaged jars were identical; exclusive-lock load was 2.51 on 24 CPUs.

The requested shared-lock Gradle run passed with exit code 0: `jvmTest`,
`:core:data:verifyCommonMainKainosDatabaseMigration`, `:androidApp:assembleDebug`,
and `:desktopApp:createDistributable`. XML results contain 954 tests, zero
failures/errors and three skips. Launcher suites passed 8 and 11 tests.
Logs: `/tmp/kainos-review-final-checks.log`, `/tmp/kainos-s2-red.log`,
`/tmp/kainos-review-timing-fallbacks.log`. No schema change or phone testing.
Real-JVM fallback checks passed for missing, stale and same-size-corrupted
archives. The corrupt launch exited 0 in this run; the background checker
removed its archive, and the next launch completed playback without it.
