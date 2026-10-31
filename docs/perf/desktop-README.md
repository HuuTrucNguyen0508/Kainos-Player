
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
