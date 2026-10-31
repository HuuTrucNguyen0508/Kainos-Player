# GUI takeover SIGTERM test race

The failure is a test defect. `HubLockChild` waits for stdin while holding its
lock. JDK 17's `java.lang.ProcessImpl.destroy(boolean)` signals the child, then
closes stdin, stdout and stderr. The child's main thread can observe EOF and
return normally before the JVM signal dispatcher handles SIGTERM. Exit 0 is
valid in that scenario. The test expected 143 despite introducing a second exit
path. GUI takeover and lock release had already succeeded.

The replacement `process.toHandle().destroy()` sends SIGTERM without closing
pipes, matching systemctl stop. The assertion remains strictly exit 143 and the
generated unit must still declare only `SuccessExitStatus=143`. No production
code changes or timeout increases are needed.

## Evidence

- Read failed logs of CI runs 41218491219 and 41208874263. Both report fixture
  line 653, which is the suspend lambda invocation, not the underlying assertion.
- On unchanged integration base b4cc9f7, 100 JUnit invocations with CPU affinity
  0,1 failed 14 times. Every failure was `expected:<143> but was:<0>` at the
  exit assertion, line 260. No readiness or ownership failures occurred.
- The JDK 17 local source in `lib/src.zip`,
  `java.base/java/lang/ProcessImpl.java`, confirms the stdin closure after signal.
- An initial fixed-code stress run is invalid: switching revisions and rebuilding
  while its loop still ran removed its class files. It produced 29 readiness
  failures. This run is excluded, and serial runs below replace it.

PR #14's exact head is 7630f14. Its changes do not alter sync's JVM runtime
dependencies or test JVM settings. Scheduling changes can expose the EOF versus
SIGTERM race; the historical CI logs cannot prove which scheduling difference
made that PR fail more frequently. A longer timeout cannot fix a valid exit 0.

## Reproduce

`JAVA_HOME=~/.jdks/temurin-17 scripts/perf/hub-takeover-stress 100`

This builds the actual test, then repeats it on two CPUs with two competing
`yes` processes. It holds the shared machine lock and kills only its own load
processes. `KAINOS_STRESS_CPUS` overrides affinity. Each test uses temporary
storage and a fake systemctl runner. It never starts a listener or uses the
user's systemd units. Do not switch revisions or rebuild during a running loop.

## Verification results

- Final integration base b4cc9f7 plus patch: 100/100 constrained-load runs passed.
- Final integration root JVM tests and database migration verification passed
  with headless AWT.

- PR #14 head 7630f14 plus fix: 100/100 constrained-load runs passed.
- After adding child-output diagnostics, the same PR head plus final patch:
  another 30/30 constrained-load runs passed.
- PR #14 plus final patch: the CI-equivalent JVM tests, migration verification,
  desktop distributable, Android debug assembly and Android lint passed with
  `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`.
- The first CI-equivalent invocation omitted that flag and failed eight shared
  UI tests because AWT could not connect to X11. This is an existing local
  environment requirement, documented in `scripts/perf/remote-report.md`.

Logs retained under `/tmp/kainos-hub-`: `repeat-before.log`,
`fixed-stress.log` (invalid run), `pr14-fixed-stress.log`,
`pr14-final-stress.log`, `pr14-ci.log` (X11 failure),
`pr14-ci-headless.log`, `final-stress.log` and `final-checks.log`.
