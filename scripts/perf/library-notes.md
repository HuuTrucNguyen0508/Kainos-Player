# Library measurements

Run `scripts/perf/library-run > /tmp/library-run.log`; summarize with
`scripts/perf/library-summary.py /tmp/library-run.log`. Earlier raw output was replaced
by `scripts/perf/library-previous-summary.tsv`; raw `scripts/perf/library-*.log` files are ignored.
Uses JDK 17, the real JVM shared module, Compose compiler, Kotlin/JUnit test executor.
The init script adds benchmark sources only when explicitly supplied. Default tests
never run the benchmarks. No user data, windows, audio, or network calls are needed.
Compilation takes a shared machine lock; the test invocation takes the exclusive lock.
The logs record load and CPU count. Other desktop processes remain running.

## Query comparison

Baseline is integration commit 5087402. Runs alternate baseline/index/baseline/index.
Each process generates deterministic 2k, 20k and 100k catalogs with title case,
diacritics, artists and albums. Each size warms five complete typed-query sequences,
then records ten sequences of ten query prefixes/multi-token/subsequence/missing queries.
No samples are discarded. Timers cover the actual `LibraryUiState.withQuery` path,
including song ranking and album/artist filtering; results and queue identity are checked.
The benchmark consumes result sizes and checks equality of the play queue.
Results are warm-index CPU derivation times, **not keystroke-to-painted-frame latency**.
Cold construction/query is measured separately. No debounce is added.

The limiter removed is repeated metadata normalization and album/artist regrouping.
Remaining work includes FuzzyMatcher tokenization, matching each song and album member,
and stable sorting of matching songs. Core model FuzzyMatcher/TrackSort were outside
the first pass's ownership. The optimization keeps those algorithms and ranking semantics.
The same reference helpers check all result lists for every sort and heart filter.
At 100k the p99 still exceeds 16 ms. Android timing has not been measured.
Small-catalog derive times differ between processes, where radically different amounts
of warmup work can affect JIT compilation. No hydration/sorting speedup is claimed.

The index is scoped to the catalog snapshot. Each input/filter/sort update replaces it,
so metadata/artwork changes cannot leave stale keys. It retains folded strings and
album/artist lists until that snapshot is discarded. Keys are lazy, so empty-query
hydration does not normalize all titles. First-query cost must be reported separately.
The presenter now derives the catalog independently of query input, and the screen
remembers synchronous query results across unrelated recompositions. This preserves
Enter's current top result and chips defining the queue, without asynchronous stale results.
No layout, scrolling, typography or color changes were made.

## Other paths

Hydration closes/reopens a temporary synthetic `kainos.db` for every sample, reads the
real scan cache, applies it through LocalMusicProvider and derives a populated UI state.
It includes connection opening, row/JSON decoding, domain conversion, grouping and sort.
The OS page cache is **not dropped**; this is process-object cold, filesystem-cache warm.
It stops before the first rendered frame and is not complete app cold-start latency.

The render fixture uses real TrackRow, LazyColumn, Compose/Skia, and Coil file artwork
on a headless scene. Ten measured batches each move 100 rows over ten frames, after
ten warmup frames. It is a CPU render fixture, not desktop GPU presentation or the full
Library screen. A shared generated cover is a warm image workload, not cold album art.
Idle recomposition counts concern fixture rows with no PlayerSession and cannot establish
whole-app recompositions during playback or AlbumLight invalidation.

Session inspection: NowPlayingRecorder already has identity caches for persisted items
and portable queue projections. SessionDb reuses queue JSON when QueueContent is equal.
An existing SessionWritePerfTest asserts skips do not rewrite either queue table.
No redundant queue-serialization fix was made on that evidence alone.

Next-track pre-resolution, real press-to-sound, full-app playback recomposition counts,
and real desktop artwork/frame measurements remain unproven by this fixture.
No speculative pre-resolution, stability annotation, schema migration or position-flow
change is included without the corresponding measurement and regression coverage.

## Verification environment

The first full check failed eight Compose UI tests because `DISPLAY=:0` was not
accessible. Rerun with `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`; no user display
or running app is accessed. One initial expanded-harness compile also used a wrong
PlayerSession method name, corrected to `play` before rerunning. Neither failure
was a production playback/search regression. Final results are recorded below.

Readiness fixture measures PlayerSession play/skip to a mock engine admitting a URL,
with a 40 ms mock extractor versus an already-available URL. This is an upper-bound
opportunity measurement, not an implemented preload comparison or actual sound latency.
It makes no YouTube requests and does not measure decoding, audio focus or output.

Next sorting candidate needs a separate ownership grant for
`core/model/src/commonMain/kotlin/com/universalmusic/player/domain/model/TrackSort.kt`.
Its comparator calls `trim().lowercase()` on both operands for each comparison.
A decorated sort could normalize each title once, but duplicating that canonical
comparator in UI code would create two rule sets. This branch leaves it unchanged.
FuzzyMatcher also lives in core:model; parsing query tokens once per query belongs there.

Full verification passed with headless AWT: root jvmTest, database migration verification,
desktop createDistributable, Android assembleDebug and Android lintDebug. The XML reports
contain 927 tests, zero failures/errors, three existing skips. The first display-dependent
run's failures disappeared with headless AWT; no test assertions were relaxed.

## First-pass observations and remaining work

At 20k, cold index+query p50/p90 is 40.166/51.004 ms. At 100k it is
196.196/205.355 ms. Thus the warm-query target passes, but the first query still
blocks long enough to miss a frame. Move initial indexing/ranking and catalog
sort/group derivation off the UI dispatcher in a follow-up, with a test ensuring
Enter waits for the latest query rather than playing stale visible results.
This branch deliberately retains synchronous matching to preserve existing Enter semantics.

Hydration p50 at 2k/20k/100k is 13.654/70.995/375.867 ms; no before/after claim.
Headless scroll ten-frame batch p50 is 84.524/50.153/41.761 ms respectively.
A blue-pixel assertion proves the generated PNG reached Coil and the rendered image.
All three idle row fixtures recompose zero times over 60 simulated frames without
playback. Those fixtures cannot answer full-app tick invalidation; App.kt still
collects whole NowPlayingState at the root, and that needs a real playback fixture.
The mock cold/ready engine-admission medians are 40.843/0.309 ms, driven by the
explicitly configured 40 ms mock extractor delay. They prove no real resolver/network
speedup and justify no prefetch implementation on their own.

At the end of the first pass the lane was incomplete: first-keystroke/frame latency, full-app playback
recomposition, and real next-track press-to-sound remain open. These results are JVM-only;
no physical phone, real desktop app, Spotify endpoint or user data was touched.

Render sizes run ascending in one JVM; later sizes benefit from native renderer/JIT
warmup. Do not interpret the smaller 100k render time as a scaling improvement. The
fixture counts rows actually composed in each batch and verifies the list moved.
The five explicit benchmark/correctness tests all passed. Regular tests exclude these
sources unless the init script is supplied. Compiler report generation is optional via
`-Pkainos.libraryReports=true` with the same init script.

Compiler reports were not generated in this lane: the final compile waited over seven
minutes for the machine lock behind another lane's fault-test run, then only this
lane's queued compile was cancelled. No other process was stopped. Rerun when free:

```bash
flock -s /tmp/kainos-perf.lock env JAVA_HOME="$HOME/.jdks/temurin-17" ./gradlew -I scripts/perf/library-bench.init.gradle -Pkainos.libraryReports=true :shared:compileKotlinJvm --console=plain
```

Reports go to `shared/build/library-compose-reports`. Do not infer skip behavior from
missing annotations alone; inspect the actual strong-skipping compiler output.

## Session 2, current results

Base cd48f003386ee36975b3fd3c704022eeadc24358, branch perf/library-session-2.
All numbers are JVM-only. The 24-core host had load averages around 8 in the final run.
Shared build and exclusive timing locks were used; normal desktop activity stayed running.

- Scaffold: actual headless Compose fixture, ten four-tick simulated seconds. Whole-state
  observation recomposes its scope 4 times, the production track-only helper 0 times.
  Progress remains 4 in both arms. These are observation-scope counts, not a full App/
  Now Playing tree profile or GPU frames. Compiler reports show strong skipping already
  makes scaffold, rows, Now Playing and mini player skippable. Blanket annotations rejected.
- Prefetch: one YouTube-only next entry after a second of successful playback. Resolve/
  enrichment run in workContext and publish on the session dispatcher after generation,
  queue and preference checks. Queue changes/transitions cancel it. Consumption checks
  entry id, original track, preferences and age. TTL is at most 30 seconds; signed URL
  expiry leaves another 30-second margin. URLs never enter queue persistence. No Spotify
  or mixed-provider prefetch. The 40 ms mock extractor gives 41.35/0.62 ms cold/ready,
  repeated 41.04/0.57 ms. Timer ends at correct engine URL admission, before audio output.
  Tests prove single extraction, expiry re-resolution, and no stale engine starts after
  queue replacement even when a non-cancellable extractor finishes. Two real yt-dlp
  checks: BaW_jenozKc unavailable; jNQXAC9IVRw resolves with signed expiry. No real network
  latency or audible press-to-sound speedup is claimed. No resolved URLs were retained.
- Index: app-lifetime presenter derives/warms on Default after hydration, before Library
  opens. Complete indices are published; matching stays synchronous for Enter. Catalog
  changes create a new index. Alternating lazy/prewarmed query at 20k is 24.10/2.60 ms,
  at 100k 137.39/11.24 ms. Ten samples per arm after three warmups. Work moved before the
  keystroke; prewarm CPU/memory and painted-frame latency are not included in that timer.
- Sort: normalize once per track; alternating original comparator and production code
  check exact equality for all four sorts and missing/nonpositive durations. At 20k,
  title ascending is 5.40/1.76 ms, duration ascending 3.96/2.24 ms. Ten samples after five
  warmups. Small 2k title ascending is noisy: first run 0.66/0.75 ms, repeat 0.91/0.81 ms.
  No 2k ascending-title gain is claimed.
- Artwork: prioritize local/YouTube previous/next covers after 1.2 seconds playing.
  Reuse local embedded cache and hydrated metadata, then Coil's display loader. Pause
  and queue changes cancel it. Generated 1024px PNG decoded at 512px: 5.05/0.064 ms,
  repeat 5.46/0.093 ms. Ten samples per arm after three warmups, alternating order, unique
  files. Filesystem-cache warm decode/cache admission, not embedded extraction, network
  or painted frames. No extra metadata fetching over the hydrated scan was justified.
- Fast skip: explicit SessionWritePerfTest with -Dkainos.perf=true, 200 writes on a
  500-entry queue. Median 0.692 ms, two metadata statements, zero queue-table writes.
  Recorder identity caches and SessionDb QueueContent reuse avoid re-encoding unchanged
  track payloads. Listening commit p50 0.789 ms, portable projection500 0.089 ms.
  Added coalescing rejected; this is observation, not a speedup.

Raw first-pass logs are replaced with library-previous-summary.tsv. Session 2, final and
artwork summary TSVs retain all compact statistics. The summary helper's ms column is
counts for recomposition metrics. Timing samples are consumed and checked; none discarded.

Final production verification passed: root jvmTest, SQL migration verification, desktop
createDistributable, Android assembleDebug and lintDebug. Explicit perf/correctness tests
also pass. Controller logs: /tmp/library2-all-final.log, /tmp/library2-perf-final.log,
/tmp/library2-writes.log. Early counter fixture missed snapshot notifications; explicit
flushes fixed it. Early desktop smoke raced packaging, then paused a buffering skip.
The final script waits for playback admission. No production checks were relaxed.

Run scripts/perf/library-desktop-smoke after packaging. Private Xvfb, private D-Bus,
synthetic WAVs, empty home/data, null-audio mpv. Successful artifact directory:
/tmp/kainos-library-desktop-7fmspjrk/, with search/Now Playing/mini PNGs and run.log.
Passed type-to-search, Enter on Alpha, three-track chip queue despite one search match,
Beta/Gamma MPRIS skips, pause/resume and no exceptions. Its processes are stopped; data
and logs retained. The user's app, app data and phone were untouched. No schema change.
Real phone latency, Android rendering and media-session behavior remain unmeasured.
