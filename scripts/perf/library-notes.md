# Library measurements

Run `scripts/perf/library-run`; summarize with `scripts/perf/library-summary.py <log> ...`.
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
and stable sorting of matching songs. Core model FuzzyMatcher/TrackSort are outside
this lane's ownership. The optimization keeps those algorithms and ranking semantics.
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

## Final observations and remaining work

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

The full lane is **not complete**: first-keystroke/frame latency, full-app playback
recomposition, and real next-track press-to-sound remain open. These results are JVM-only;
no physical phone, real desktop app, Spotify endpoint or user data was touched.

Render sizes run ascending in one JVM; later sizes benefit from native renderer/JIT
warmup. Do not interpret the smaller 100k render time as a scaling improvement. The
fixture counts rows actually composed in each batch and verifies the list moved.
The five explicit benchmark/correctness tests all passed. Regular tests exclude these
sources unless the init script is supplied. Compiler report generation is optional via
`-Pkainos.libraryReports=true` with the same init script.
