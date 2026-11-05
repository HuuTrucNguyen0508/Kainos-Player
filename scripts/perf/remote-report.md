# Remote play/pause intent, part 2

Base: `b3a3d8000d76f0665509b86e19713ff7cee46aef`. Branch: `fix/remote-play-pause-intent-2`. Physical phone tests remain held.

## Behavior and guarantees

The real button sends the Play or Pause it shows. `RemoteControl` owns one pending play intent, immediately publishes it to the view, waits for an acknowledged matching newer status, and restores the real status after four seconds with existing failure feedback. A later press replaces the pending intent. Disagreeing pre-press snapshots do not undo it. Polls sent before a direct press and older command replies cannot overwrite that press's status. The host suppresses phone statuses whose acknowledgement precedes its queued explicit intent.

A press wakes reconnect backoff. If an ambiguous command failed while a poll was held, the next explicit press cancels that poll and requests a fresh status. Pending commands wait at most four seconds. Host-to-phone queue age includes time spent waiting for the phone to reconnect. The existing host-to-phone seq and epoch mechanism stays in place.

Direct retries require the optional `directIntentLease` in a poll reply. The enriched request carries command, random process sender ID, monotonic intent sequence, relative command age, and lease. The receiver serializes applications, remembers each sender's newest applied sequence, and returns current status for replayed, reordered, or expired requests. Only Play and Pause use this path. TogglePlay, Next, Previous, and other commands never retry. Old hubs omit the field and retain the bare-command path with retries disabled. Old clients still send the existing bare command to the new hub.

Age alone cannot bound unknown time in transit. A lease expires four seconds after the receiver issues it, before the sender dispatches the press. The receiver passes the earlier of that lease deadline and the remaining command-age budget to `RemotePlayer.apply`, which checks on the session thread before mutation. Cancellation cannot extend either deadline. A packet delayed beyond the original deadline is rejected even if its age field was encoded before that delay. Leases are peer-bound and capacity eviction fails closed. This conservative bound may refuse an intent earlier than four seconds when the prior lease is old. Capability-aware clients refresh held polls every two seconds to keep a usable lease. That increases idle polling on the wanted link compared with the former twenty-second hold. Legacy bare requests retain their existing arrival-based timeout; the stronger deadline guarantee applies to enriched requests.

No database schema, engine, `PlayerSession`, layout, colors, or typography changed. One ownership boundary remains: the raw app-visible transition enters through `AppContainer.kt`, outside this lane. Wire its rising edge to `remoteControl.onAppVisible()` in the integration lane. The derived `linkWanted` boolean cannot expose visibility changes during its ten-minute grace.

## Measurement and reproduction

`RemoteDirectFaultHarnessTest` runs identical source against the archived baseline and head. Pinned mutual TLS, real routes, and the real `RemoteLinkClient` run on loopback. The stale-display arm sends baseline UI TogglePlay or the new explicit Pause while the receiver is already paused. Other scenarios finish a two-press burst with Pause. HTTP faults duplicate the first command, lose its response after application, or delay an old explicit Play until after the newer Pause. The mixed-version arm removes the optional capability field and checks that no retry occurs. Each arm has ten fresh profiles, certificates, server ports, and HTTP pools per scenario. No warmup samples are discarded. Final-state correctness includes every trial. Application counts and original deadlines are checked separately, so a dropped command cannot earn a latency win.

`final_ms` starts before the first send and stops at the last actual receiver application. It includes the thirty-millisecond inter-press gap and any retry delay. The first direct request may open a second TLS connection while a poll holds the first; that roughly forty-millisecond setup dominates the healthy numbers. Compilation and fixture setup stay outside the timer. The 100 ms retry wait dominates ambiguous-reply recovery. Arms run in separate timing sessions under the exclusive perf lock on a shared machine, without alternating JVM warmup. Treat these times as descriptive fault outcomes, not a speedup claim or touch-to-audio latency.

```sh
# Build a fresh archive; production baseline sources stay unchanged.
remote_baseline=$(mktemp -d /tmp/kainos-remote-baseline.XXXXXX)
git archive b3a3d8000d76f0665509b86e19713ff7cee46aef | tar -x -C "$remote_baseline"
cp sync/src/jvmTest/kotlin/com/universalmusic/player/data/sync/RemoteDirectFaultHarnessTest.kt "$remote_baseline/sync/src/jvmTest/kotlin/com/universalmusic/player/data/sync/"
cp scripts/perf/remote-direct "$remote_baseline/scripts/perf/"
if test -f local.properties; then cp local.properties "$remote_baseline/"; fi
"$remote_baseline/scripts/perf/remote-direct" /tmp/remote2-baseline.tsv 1
scripts/perf/remote-direct /tmp/remote2-head2.tsv
python3 scripts/perf/remote-direct-summary.py /tmp/remote2-baseline.tsv /tmp/remote2-head2.tsv
# Run Gradle commands sequentially in this worktree, each under the shared perf lock.
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true JAVA_HOME=$HOME/.jdks/temurin-17 flock -s /tmp/kainos-perf.lock ./gradlew jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
# Fresh profiles only; substitute unused paths and ports.
KAINOS_REMOTE_DESKTOP_SETUP=/tmp/remote-proof-fresh KAINOS_REMOTE_DESKTOP_PORT=46481 JAVA_TOOL_OPTIONS=-Djava.awt.headless=true JAVA_HOME=$HOME/.jdks/temurin-17 flock -s /tmp/kainos-perf.lock ./gradlew :sync:jvmTest --tests '*RemoteDesktopFixtureTest' --rerun --console=plain
JAVA_TOOL_OPTIONS= flock -x /tmp/kainos-perf.lock python3 scripts/perf/remote-desktop-proof.py /tmp/remote-proof-fresh /tmp/kainos-desktop-tools/usr/bin/Xvfb
```

Focused tests cover optimistic rapid presses and timeout, pre-press status, immediate backoff wake, latest-only pending delivery, offline expiry, receiver ordering, duplicate suppression, wrong-peer leases, expired age, and expired receiver leases. Physical checks still held: installed old/new Android peers in both directions, HyperOS suspension and lock-screen eligibility, real Wi-Fi outages, DHCP and mDNS changes, visible-transition wake after integration wiring, and physical press-to-audio latency.

## Results

All five targeted final states improved from 0/10 to 10/10. Baseline application times below end in the wrong state and are not successful-response latencies. No speedup is claimed. Compact counts, ranges, and timings are retained in `remote-results/direct-summary.tsv`.

| Fault | Correct baseline / head | Baseline median / p90 ms | Head median / p90 ms | Trials per arm |
| --- | --- | --- | --- | --- |
| stale-display | 0/10 / 10/10 | 46.39 / 48.20 | 46.66 / 48.04 | 10 |
| double-delivery | 0/10 / 10/10 | 77.68 / 78.50 | 77.85 / 79.14 | 10 |
| ambiguous-reply | 0/10 / 10/10 | 44.20 / 44.92 | 177.71 / 178.75 | 10 |
| reordered-request | 0/10 / 10/10 | 201.35 / 201.39 | 74.10 / 75.41 | 10 |
| mixed-version | 0/10 / 10/10 | 44.00 / 44.85 | 547.56 / 548.25 | 10 |

Baseline load was 2.44/3.03/3.18 and head load was 2.37/2.50/2.64. The first head matrix passed its first four scenarios but failed the first mixed-version trial, because a disconnected command could stay behind a held poll. That partial run is preserved at `/tmp/remote2-head.tsv`; it is rejected, not merged into the successful matrix. The fresh complete rerun is `/tmp/remote2-head2.tsv`, with baseline `/tmp/remote2-baseline.tsv`. Summary validation checks exact application counts as well as final state.

The final serialized check passed 938 JVM tests with zero failures or errors and three skips, SQL migration verification, Android debug assembly, and desktop packaging. Log: `/tmp/remote2-final4.log`. Its successful output also contains a residual Android UTP/ddmlib `Stopwatch` class-loader message outside the requested tasks. No instrumentation or ADB command was run by this lane. Earlier unsuccessful build logs remain under `/tmp/remote2-*.log`, including the overlap that invalidated one missing-class run.

## Real desktop buttons

The two packaged GUI instances passed pause, resume, and a 100 ms double tap in both directions on private Xvfb displays. The sender stayed playing in each case. Each target logged exactly two remote `pauseTransport` and two remote `playTransport` calls. The sole `togglePlayPause` trace per process was its initial local Space press. Screenshots were inspected against the prior layout. The isolated mpv wrapper uses `--no-config --ao=null`. Owned Java, mpv, and Xvfb processes exited. Compact outcome: `remote-results/desktop-intent-proof.json`. Logs and screenshots: `/tmp/kainos-remote2-proof-r3`; driver output: `/tmp/remote2-gui2.log`.

The first GUI attempt inherited a Wayland environment into Java Robot capture and timed out in the portal. It was stopped, with logs retained at `/tmp/remote2-gui.log`. The final driver clears Wayland variables for X11 input and captures directly through ImageMagick on the private display. No actual phone or user player was controlled.

## Previous lane, historical results

The following findings and measurements predate part 2. Statements about missing intent, unchanged models, and absent direct deduplication describe that earlier implementation.

# Remote play/pause reliability investigation

Base: `5087402`, integration branch `feat/redesign-folio-caelestia`. No phone available.

## What the numbers measure

`scripts/perf/remote-faults /absolute/results.tsv [comma-separated-scenarios]` compiles under the shared perf lock and measures under the exclusive lock. Each row starts immediately before `RemoteLinkHost.send`, or `RemoteLinkClient.send` for `direct`, and stops inside `RemotePlayer.apply`, after checking its deadline. This is transport-to-player application latency, not physical touch-to-audible-pause latency. Players record every application and deliberately keep their status unchanged, so status reposts cannot mask transport behavior. Setup, certificate creation and TLS warmup are outside the timer. Every trial uses fresh temporary identities, server, HTTP pool and client. No real profile or audio device is used.

Nearest-rank p50, p90 and p99 include applied commands only. Failed/expired commands stay in the outcome denominator. Each scenario has ten independent trials; bursts have two or five presses per trial. Across a matrix the same single JVM runs these fresh fixtures, so later trials share JIT/SSL implementation warmup while every network connection and peer state is new. No samples are discarded for warmup. Loss2 has fifty trials. All samples contribute to the retained compact summaries, including the initial held-poll outlier. Raw trial TSVs are local artifacts. Baseline load average was 2.60/2.07/1.55. This is a deterministic fault experiment on a shared machine, not an Android radio benchmark. The 1-second artificial artwork upload is the dominant limiter in that scenario. With the independent worker, the remaining material-status repost gap can still cost up to 250 ms. A later run alternated between immediate and approximately 250 ms delivery, so the retained 251.47 ms median is a conservative scheduling observation, not a promise of sub-millisecond remote UI latency. p99 with ten trials is simply the slowest retained sample.

Fault wrappers surround the actual pinned-mutual-TLS transport, real hub routes, RemoteLinkHost and RemoteLinkClient. Request loss fails before HTTP; reply loss fails after the server has actually handled the request. Loss uses periodic 1-in-50 or 1-in-10 positions shifted by trial number, not independent random packet loss. Added latency is split across request and reply. Reply-flight and gap both delay a returned reply by 100 ms, so they exercise the same no-held-poll window and are not independent experiments. Repost changes material status; position changes only a small position tick. Duplicate repeats command envelopes in one reply. Wi-Fi blips suspend a request for 2 or 10 seconds then fail it. Suspension advances the receiver's injected monotonic clock by 60 seconds after receiving a command-bearing reply, simulating an already-buffered reply upon wake. It does not claim to reproduce Android Doze scheduling. Existing virtual-time client tests cover poll cancellation at 25 seconds.

## Answers from tracing

1. `NowPlayingRemote.kt:419` sends `TogglePlay`, forwarded unchanged by `RemoteControl.send`. There is no optimistic intent state. UI stale-state correctness and rapid-tap intent therefore remain separate from this transport measurement. Explicit Play/Pause based on displayed state plus optimistic reconcile is a follow-up; Compose UI is outside this lane. No toggle retry was introduced.
2. Normal short gaps are admitted by the 3-second grace. The 4-second expiry is enforced by host age and receiver monotonic transit accounting. Long gaps can still cause immediate refusal. Widening connection grace without evidence was rejected. Host-to-phone redelivery already uses seq/epoch dedupe; phone-to-hub commands have no client ID and are not safely retryable after an ambiguous response.
3. Baseline failure backoff started at 2 seconds, then 5/10/20/30 seconds. The accepted second candidate prepends one 200 ms retry. The 2-second outage median fell from 3951.93 to 2155.19 ms with 10/10 applications on both sides and unchanged deadlines. `send` refuses while disconnected and does not wake backoff. A visible transition can also be hidden because wanted remains true through its ten-minute grace. A press/visibility wakeup needs a lifecycle signal and a specified pending-intent policy. It is not implemented here.
4. Position-only ticks do not cancel the poll. Artwork PUT previously ran inline between polls for up to ten seconds. This is a reproduced serial dependency. Direct hub commands use a separate HTTP request on the same OkHttp client; the held-poll/direct scenario checks that one held poll does not exhaust the pool. No pool sizing change was made. Direct requests were only measured on a healthy link; the lossy matrix tests hub-to-phone delivery. Ambiguous direct-command failure/deduplication remains unproved.
5. Phone-to-hub `send` publishes the reply's status immediately. Hub-to-phone waits for the phone's subsequent status poll. Neither path optimistically changes the device view before confirmation. Artwork rendering is already independent in `RemoteControl.ArtworkFiles`.
6. A phone IP change does not change the paired identity; the next outbound client connection needs no stored phone address. PC address changes use `HubEndpointResolver`: stored probe up to 3 seconds, then mDNS browse up to 4 seconds. Reconnect backoff and a failed outstanding poll can add more time. Real mDNS/IP-change timings were not measured, so those bounds are source-level observations rather than benchmark results.
7. The harness rejects duplicate applications and applications after the command's original four-second deadline. The existing client tests separately cover epoch resets, replay across polls, cancellation while applying, and expiry behind an earlier command. Passing these is bounded evidence, not a proof over every network fault.

## Compatibility and scope

The accepted artwork scheduling candidate changes no serialized models, HTTP routes, timeout, seq, ack or epoch semantics. The loopback host and HttpRemoteLinkTransport source are byte-for-byte unchanged from `5087402`, so a new client talks to the baseline host implementation in this test. The unchanged host's baseline-client behavior was measured before the change. This is source-equivalent mixed-version coverage; no installed old APK or independently built old desktop was exercised. No database/schema change.

A sibling lane prepared a portable Xvfb at `/tmp/kainos-desktop-tools/usr/bin/Xvfb`. `RemoteDesktopFixtureTest` seeds two fresh profiles; `remote-desktops.py` launches real packaged GUIs on two owned Xvfb displays, distinct MPRIS names and ports, with a private `mpv --no-config --ao=null` wrapper. `remote-desktop-input.java` requires explicit X11 plus the private display identity, avoiding the Wayland portal. First attempt logs are retained at `/tmp/kainos-remote-desktop-proof-20261031`: the synthetic `file:/...` source was rejected by mpv, so profiles were reseeded with an absolute path at `/tmp/kainos-remote-desktop-proof-20261031-valid`. This corrected the fixture only; no engine code changed. No packages were installed and the real profile/player were untouched.

Verification: `jvmTest --rerun`, SQL migration verification, Android assembleDebug and desktop createDistributable passed with `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`: 930 JVM tests, zero failures/errors, three skips. Initial shared UI tests failed to initialize X11 without this explicit headless setting; both attempts are retained in `/tmp/remote-checks.log` and `/tmp/remote-final-checks.log`.

Real-phone checklist: new phone/old PC and old phone/new PC; locked playing, buffering and paused eligibility; visible transition during reconnect backoff; HyperOS suspension beyond 25 seconds; 2/10-second Wi-Fi loss; phone DHCP and PC DHCP changes with actual mDNS; repeated rapid presses and displayed play-state reconciliation; artwork changes while pressing pause; physical press-to-audio latency; no delayed Play after reconnect; old/new installed desktop binaries beyond the source-equivalent loopback test. The two current private-display desktop instances with null audio passed.

## Re-run commands

```sh
scripts/perf/remote-faults /tmp/remote-full.tsv
scripts/perf/remote-faults /tmp/remote-restart.tsv restart
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true JAVA_HOME=$HOME/.jdks/temurin-17 flock -s /tmp/kainos-perf.lock ./gradlew jvmTest --rerun :core:data:verifyCommonMainKainosDatabaseMigration :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
# Choose a fresh profile root; do not reuse an existing profile for this setup test.
KAINOS_REMOTE_DESKTOP_SETUP=/tmp/remote-proof-fresh KAINOS_REMOTE_DESKTOP_PORT=46481 JAVA_HOME=$HOME/.jdks/temurin-17 flock -s /tmp/kainos-perf.lock ./gradlew :sync:jvmTest --tests '*RemoteDesktopFixtureTest' --rerun --console=plain
flock -x /tmp/kainos-perf.lock python3 scripts/perf/remote-desktops.py /tmp/remote-proof-fresh /tmp/kainos-desktop-tools/usr/bin/Xvfb
# In a second terminal use the owned display number printed above, with XDG_SESSION_TYPE=x11.
# The Java helper supports capture <path>, click <x> <y>, and space.
```

`remote-results/artwork-worker-summary.tsv` retains the first matrix summary. Its initial restart timing was too weak: the command had already applied before restart. `fast-retry-summary.tsv` added a reply delay but collected before waiting for its buffered response. Neither early restart batch proves mid-flight epoch behavior; use the corrected `restart` harness, which gates restart on a command-bearing reply and observes for another 500 ms. The corrected restart result is recorded separately. This limitation is retained rather than silently discarding the earlier runs.

## Live desktop outcome

The corrected two-GUI proof passed against production commit `61c6dc2`. Both real mpv children were inspected with `--no-config --ao=null` and the absolute fixture WAV. Each real remote button paused and resumed the other process without changing the sender's local transport. Double taps at 100 ms and five taps at 200 ms produced the expected parity in both directions. Each target recorded exactly nine `togglePlayPause` applications for its nine remote presses. Final MPRIS states were paused. Owned Java, mpv and Xvfb processes exited; no test media players remain. Logs and screenshots are retained at `/tmp/kainos-remote-desktop-proof-20261031-valid`, and `remote-results/desktop-proof.json` records the outcomes. These actions prove the current UI still sends TogglePlay, not explicit Play/Pause, and do not fix the stale-display intent problem.

## Restart boundary

The corrected command-bearing reply test returned STOPPED on all ten host sends while the already-fetched old-epoch command applied once, about 202 ms after the original press, on every receiver. Subsequent new-epoch polls did not replay it. This is an explicit limit: restart empties the server outbox but cannot revoke a packet already in transit before the receiver learns the new epoch. All applications remained below four seconds. A strict promise that no previously fetched command can execute after a restart is not proven or implemented by this protocol. The earlier two restart batch summaries remain with their limitations documented above.

Protocol/model files and host routes are unchanged. Physical phone tests, actual address changes, stale-UI explicit intent and ambiguous phone-to-hub command replay remain outside the completed proof. No database schema change, push, PR or release.
