# Handover: Home sync identity + now-playing handoff program (HS0-HS7)

Written 2026-10-18 by the Opus coordinator when the Claude five-hour window reached 99% (98% rule). Detailed state lives in `.audit/` (ignored, local): read `.audit/controller-handoff.md`, then `.audit/hs-program.todo.md` (newest checkpoints at the bottom) and `.audit/hs-program.decisions.tsv`.

## Goal
User, in their words: make Home sync "sync but still keep it safe" (current pairing "quite a handful"), survive IP changes, use a public/private key approach "that hides the secret", and auto-continue the current track: "continue from PC" automatic on the phone and "continue from phone" when the PC turns on, always loaded PAUSED. Plan: `docs/plans/home-sync-identity-and-handoff.md` (committed on `feat/home-sync-identity-handoff`, latest plan commit 3722922). Execution playbook: autopilot-stack (local stacked branches; the operator lands). Operator said "go use /t3-pstack:poteto-mode to implement the feature". No go on push/PR/merge/release/phone install.

## Status
- HS0 `hs/0-isolation-overrides` 91d315a, HS1 `hs/1-session-writes` 8b1f3bb, HS2 `hs/2-mdns-pin` d2774b1: built, reviewed, fixes accepted, restacked, full JVM suites pass.
- HS3 `hs/3-mtls-pairing` ee0e459: built + operator-authorized integration repair; scoped review clean; desktop live PASS (pairing, CLIENT cold-start sync, settings persistence, resets); stale-client cleanup/contention CNR (unit-backed).
- HS4 `hs/4-hub-owner` fe85959 (NEW, this session): operator-authorized extra repair "preserve login startup preference when management is unavailable" landed by Codex task f44a6517 (3 red->green tests, 594 JVM tests 0 fail, Android assemble/lint pass). NEEDS: independent fix-diff review (Opus queued PINNED_ROUTE_UNAVAILABLE because Claude is limited; use Codex Sol if Claude stays limited, noting same-family) and an isolated packaged-process recheck of the startup-preference bug (generated default unit child + port-only profile).
- HS5 `hs/5-listening-state` 308c8b7 (on old HS4 f15ec51, NOT yet restacked onto HS4 tip fe85959): operator-authorized extra repair DONE by Codex 387c7218 (reservation completed). Latest accepted seek now carries through startup and fallback; 6 red->green tests; 644 JVM tests 0 fail; Android assemble/lint pass. Report: `.claude/worktrees/hs5/.audit/hs5-extra-owner-report.txt`. NEEDS: fresh fix-diff review (26f8d91..308c8b7) and packaged-artifact recheck of C180/D600 lanes.
- Open HS5 live finding (cause unproven): 600 s track, idle 120 s seek/resume published 120000 but mpv played from ~0.
- HS6, HS7: not started.
- HS1 comparative timing series reservation 2c82aa10: recorded "dispatching" by the previous Sol coordinator, apparently sent to the existing preflight child (5a9cf6e7) by thread-send. task_status on the guessed id says not found in this thread. Verify via t3_thread_list/t3_thread_read before relaunching; never duplicate.

## Changes this session (Opus coordinator)
- Root restacked HS4 onto HS3 ee0e459 (f15ec51), applying resolver 69bb920c copies; removed obsolete HS0 HubAutostartIsolationTest at that replay; found and routed the isolated-unit safety defect (fixed in e0b7088 by a prior round).
- Created worktree `.claude/worktrees/hs5` from f15ec51; HS5 Opus owner 855f9cb7 completed earlier rounds (see todo).
- Wrote briefs `.audit/briefs/fix-hs4-extra-authorized.md`, `.audit/briefs/fix-hs5-extra-authorized.md`; authorization record `.audit/hs4-hs5-extra-repair-authorization.json` (operator answer "Both (Recommended)": exactly one extra bounded round each, nothing more).
- No uncommitted tracked work in any worktree (hs4 clean at fe85959; hs5 being edited by 387c7218).

## Current state
- Quotas at stop: Claude five-hour 99% (STOP), weekly 8%; Codex Pro weekly 15%.
- Running: nothing. Hourly audit schedule `kainos-hs-program-sol-tick-20261017` is bound to this thread.
- Model controller: manual 35% Codex target; use the absolute CLI `node '/home/theadenkingof/Documents/Code/model controller/dist/src/cli.js'` for status/decide/record. Never Astra for anything except where the operator later allowed it for computer use (see thread history); computer use otherwise codex-pro/gpt-6.1-sol.

## Open problems
- HS4 fe85959 lacks independent review + live recheck.
- HS5 needs: repair result, fix-diff review, restack onto HS4 tip (root only; expect AppContainer / HomeLanSyncService overlaps), the 600 s resume-position live failure investigated, then live/perf gates.
- No PR is stack-ready: phone (Poco/HyperOS Keystore, NSD, Wi-Fi timing), untouched-trunk comparisons, real logout/login, operator video review gates remain open.

## Next steps
1. Recheck quotas. While Claude is limited, run the coordinator on codex-pro/gpt-6.1-sol (operator did this before) or wait for the Claude reset; do not invoke Claude while limited.
2. HS5 repair 387c7218 is done (308c8b7); schedule its fix-diff review and an exact-head live recheck.
3. Dispatch HS4 fe85959 fix-diff review (Sol if Claude limited) and isolated packaged-process recheck of the startup-preference case.
4. Resolve the HS1 timing lane identity (2c82aa10) without duplication.
5. Root restacks HS5 onto HS4 tip; validate; then HS6 then HS7 per plan, each: Opus owner (frontend-design for UI), Codex review/fix, live lanes on isolated instances, perf, operator review gates for HS7.
6. Keep everything local; ask the operator before any push/PR.

## Key context
- Accepted HS5 contract: `.audit/hs5-accepted-contract.md` + authoritative `.audit/hs5-plan-review.md`.
- HS1 perf budget countersigned by root: delta 1 row and median <= 1/3 trunk (plan commit 3722922).
- JDK 17: `~/.jdks/temurin-17`; Gradle `./gradlew`; JS via pnpm only.
- Never run the app against `~/.universal-music-player`; isolate with absolute `KAINOS_DATA_DIR`, `KAINOS_HUB_PORT`, `KAINOS_INSTANCE`. Never run systemctl against real units. Never touch the phone without operator go.

## Sol audit correction 2026-10-18T00:36:46.312361+00:00

Codex continues under the explicit Claude-only quota fallback, with fresh weekly15% verified. Claude remains99% and must not be invoked. Astra is prohibited for every task, including computer use; the older exception wording above is superseded. No additional repair was authorized beyond the one extra each already spent.

HS1 finite timing receipts were recovered; its stalled reporting task was stopped and a fresh read-only Sol reviewer24d22260 is validating the existing data without reruns. Both HS4fe85959 and HS5308c8b7 received clean scoped Sol fix reviews. HS4 actual startup recheckfcd325c8 runs in its own bound checkout. Canonical metadata is .audit/restack-current.json and current todo table. HS5 restack and actual C180/D600 proof follow HS4 parent verification. This replaces the earlier claim that no work is running.

Sol continuation 2026-10-18T00:40:50.028476+00:00. HS4 exactfe85959 bounded actual startup recheck passed and cleaned up. Root restacked HS5 locally ontofe85959; current integrated head28589bf2b0f12f5a4300d68c0674ac8466f09672, feature patch unchanged and all17range-diff commits equal. Source/live validator e1e83f73 runs in t3-972d4e0c. HS1 evidence reviewer24d22260 remains active. No further source repair or external publication is authorized.

Completion update 2026-10-18T00:43:54Z. HS1 independent Sol receipt review24d22260 is completed/no pending children and its reservation completed. Root independently recomputed raw reported/sensitivity data. Benchmark correctness PASS; qualified median measurement PASS; unchanged one-third latency budget FAIL, ratio0.586470545. See .audit/hs1-comparison-measurement-verdict.json and full independent report. No rerun, repair or budget change. HS5 integrated verifiere1e83f73 remains active. Fresh Claude quota available five-hour0%, weekly8%; Codex Pro weekly16%. Keep coordinator Sol per latest operator directive, preserve running worker.

HS5 completion 2026-10-18T00:46:37.226595+00:00. Integrated software/package PASS at28589bf,650passed3skip. GUI C180/D600 remainsCNR due sharedinput uncertainty and disclosed wrong retry; bothclicks excluded. Cleanupconfirmed. FreshSolcomputer-use433d591b active in t3-c072d459 branchverify/hs5-private-display exact28589bf; readiness .audit/hs5-private-display-ready.json. Firstproveownedprivatedisplay; neverhostinput/install/build/sourcefix. Source and furtherrepairhold unchanged.

Private-display completion 2026-10-18T00:50:12.479933+00:00. Sol433d591b terminal/no pending children and reservationcompleted. No program child remains. Private displayCNR, no launch/input/runtime resources. HS5 GUI unverified; existing softwarePASS retained. Operator authorization requested in .audit/hs5-private-display-setup-proposal.md for exactly Xvfb+xdotool package additions and bounded live retry. No packages/downloads/refresh/sourceedits performed. Codexweekly17%, Claude0%five-hour/8%weekly. Do not repeat failed shared input or invent GUI pass.

Audit 2026-10-18T01:31:30.515857+00:00. Operator stopped further HS5 long-track resume debugging at user item72cb7ac9 after approving packages. No program worker active. Both packages still absent because sudo blocked; do not ask again or restart the debug lane. Phone already rebuilt/installed28589bf in run116; release canonicalAPK hash7ab1af5ddb2699132795f662448eef2672e51613f8ff95021fc6cc652184917f matches. HS4fe is already included. Desktoplauncher proposal still unaccepted. Existing tests/live/perf verdicts retained, no silent live or budget waiver. Single Sol audit timer remains. LatestCodexweekly18%, Claude5hour23%/weekly10%; no Claude invocation. Tick reportednone.
