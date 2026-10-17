# Home sync identity and now-playing handoff plan

This plan changes how the phone and the PC find and trust each other, then adds automatic "continue where you left off" between them.
The phone finds the PC hub by mDNS when the stored address fails, so a new DHCP address no longer breaks sync.
Each device gets its own key pair, and connections use mutual TLS with pinned fingerprints, so no shared secret is copied, stored on both sides, or sent.
Pairing becomes "tap the PC, compare a 6-digit code, confirm on both screens".
Each device then publishes its own small now-playing row, the phone exchanges rows with the hub, and the device you pick up loads the other device's session paused.
The rules the program enforces are that the address never decides trust, only a pinned fingerprint does, and that nothing ever autoplays.
The PRs in order are HS0, HS1, HS2, HS3, HS4, HS5, HS6, and HS7.

## How to read this

One box is one unit of work. Every box names the evidence that checks it. A nested box is a sub-step of the box above it. Check a box only when its evidence exists, a file, a log line, a screenshot, a test run, or a SHA. The body is a how-to. The appendices explain and record.

The program runs `skills/poteto-mode/playbooks/autopilot-stack.md` from the installed plugin. The operator lands the stack. Every PR stops at stack-ready, and HS3 and HS7 also wait for the operator's review in chat because they change an interaction.

Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

## Program checklist

### Arm the program

- [ ] State the protocol and this plan to the operator, then stop. Start execution only on the operator's explicit go.
- [ ] Ask the operator, in the same message, for the go on pushing branches and opening PRs. Both are one-way doors under the operator's global rules. Without that go, owners commit locally on their branches and the stack is delivered as local branches.
- [ ] On the operator's go, write this exact text into the standing orders and restate it in your todolist. "Plan `docs/plans/home-sync-identity-and-handoff.md`. PRs in order HS0, HS1, HS2, HS3, HS4, HS5, HS6, HS7, stacked on `feat/home-sync-identity-handoff`. Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. The operator lands the stack. Done when every PR is stack-ready with a clean verdict at its head SHA."
- [ ] Read these from the installed plugin at program start. Re-read them at every tick.
  - [ ] `skills/poteto-mode/playbooks/autopilot-stack.md`
  - [ ] `skills/swarm/SKILL.md`
  - [ ] The built-in `run` skill. It is the driver together with the boot recipe commands, because the repo has no `.claude/skills/verify/`.
  - [ ] `skills/poteto-mode/playbooks/opening-a-pr.md`
  - [ ] `skills/interrogate/SKILL.md`, `skills/show-me-your-work/SKILL.md`, `skills/deslop/SKILL.md`, and `skills/no-comments/SKILL.md`
- [ ] Before every delegated owner or lane, call the model controller (`model_controller_decide` with `project` set to `/home/theadenkingof/Documents/Code/Kainos-Player`) and follow its route. Owners that write code pin `claudeAgent/claude-opus-5-5`. Debugging and review-finding fixes pin `codex-pro`. Phone lanes use kind `computer-use`.
- [ ] Arm the hourly audit tick as a real `/loop` in dynamic mode, which schedules its own wake-up rather than blocking on a sleep. Never leave the cadence to memory.
- [ ] Use this tick prompt, verbatim. "Re-read the execution playbook from the installed plugin and the standing objective. Audit the operation against both and fix drift in this tick. Probe every active lane and judge progress by side effects only. Stand down a stuck lane and dispatch its replacement now. Then post a short status message to the operator in chat only when the audit found a tracked change that no earlier status message reported, such as a PR opened, a code-ready head, a round launched or closed, a verdict, a merge, a stuck agent and the action taken, a blocker added or cleared, or a decision only the operator can make. Name every such change and nothing else. Do not repeat a table, the merged list, or an unchanged blocker. If the audit found none, end the turn with no reply text. Either way, log this tick's row in your decision trail. The row names the items reported, or none."
- [ ] On the operator's hold or stand-down, send every owner a zero-writes order at once.

### Spawn owners

- [ ] Spawn one owner per PR with the full lifecycle the execution playbook names. Every owner brief opens with the absolute path of `skills/poteto-mode/SKILL.md` and names its controller reservation ID.
- [ ] Follow this dependency graph. The stack is linear, so each branch starts from its parent branch.
  - [ ] HS0 is first and branches from `feat/home-sync-identity-handoff`.
  - [ ] HS1 after HS0.
  - [ ] HS2 after HS1.
  - [ ] HS3 after HS2.
  - [ ] HS4 after HS3.
  - [ ] HS5 after HS4. Its migration file is the only new migration in the program.
  - [ ] HS6 after HS5.
  - [ ] HS7 after HS6.
- [ ] Hold the file boundaries. Each PR's **Files.** block lists every file it may touch. A file outside that list needs the root's approval, recorded in the trail.
- [ ] Hold the review gate. HS3 and HS7 change an interaction. They wait for the operator's review in chat with screenshots and a video before merge.
- [ ] Every owner of HS3 or HS7 invokes `/frontend-design` before writing UI code, per the operator's global rules.

### PR mechanics, for every PR

- [ ] Resolve the forge once. Default to `gh`; if `command -v origin` succeeds and Origin can resolve the repository, use `origin pr` for every PR operation. Record any fallback to `gh`. Never require `gt`.
- [ ] Open the PR ready, never draft, per **Opening a PR**, only after the operator's go on pushing. Use `gh pr create --base <parent-branch>`. A stack child targets its parent branch. Link every PR with `link_pull_request`.
- [ ] Run `./gradlew jvmTest`, `./gradlew :core:data:verifyCommonMainKainosDatabaseMigration`, and `./gradlew :androidApp:lintDebug` once before the PR-facing push. Push with hooks on.
- [ ] Run `/deslop` before each commit and `/no-comments` before review.
- [ ] Triage every review-bot and security-reviewer comment per `../references/bugbot-triage.md`.
- [ ] Before the code-ready report and babysit, and again after merge prep, record the base and head SHAs prepared by the topology owner under the execution playbook.

### Verdict and merge, for every PR

- [ ] At the code-ready head SHA and at each later push that changes the patch, run the swarm per `skills/swarm/SKILL.md`. One gates lane. The ten live lanes from the PR's **Verify, live** block. The perf lane from its **Verify, perf** block. Two or more audit lanes, each with its own focus, that read the diff and the receipts and distrust the PR body. The root audits the receipts in the merge-ready report before the verdict.
- [ ] Run `skills/interrogate/SKILL.md` on the diff with the `interrogate reviewers` panel (Claude Opus 5.5 and Codex Pro `gpt-6.1-sol`) before stack-ready. HS3 also gets a security-focused audit lane, because it changes who the hub trusts.
- [ ] Clean only when every lane is `PASS`. A lane that records a platform limit, such as HyperOS stopping a worker, reports `LIMIT` with the evidence, and the root reports it to the operator. `LIMIT` is never `PASS`. Findings go back to the owner, including a defect that a lane filed as a note. A new head gets a fresh swarm and a fresh verdict, except for results that stay valid under the patch-id rule in `playbooks/shipping.md`.
- [ ] A clean verdict appends the PR to the one linear base-branch stack, in the order HS0 to HS7. No owner merges. The patch-id rule in `playbooks/shipping.md` decides which verdicts survive a rebase.

### Boot recipe, for every live lane

Each live lane runs in its own worktree at the PR head. Drive through the skill path or exact commands recorded in this boot recipe.

- [ ] `git fetch origin <head-branch> && git checkout <head SHA>`. Without pushed branches, use `git worktree add .claude/worktrees/<lane> <head SHA>`.
- [ ] Start a desktop instance with its own data directory, hub port, and MPRIS name, using the HS0 overrides. Run `KAINOS_DATA_DIR=/tmp/swarm-<pr-id>/worker-<n>/data KAINOS_HUB_PORT=$((44000 + <n>)) KAINOS_INSTANCE=<n> JAVA_HOME=~/.jdks/temurin-17 ./gradlew :desktopApp:run > /tmp/swarm-<pr-id>/worker-<n>/desktop.log 2>&1`. Wait until the log shows the first `TRACE` line. Before HS0 exists, desktop lanes run one at a time against a copy of `~/.universal-music-player` made with `cp -a`.
- [ ] Phone lanes use the Poco F7 Ultra over wireless adb. Find the transport with `adb devices -l`, install with `adb -t <transport_id> install -r <apk>`, and read state with `adb -t <transport_id> logcat -s KainosTrace` and `adb -t <transport_id> exec-out screencap -p`. Phone lanes run one at a time, because there is one phone. A phone lane pairs with a desktop instance that listens on the default port 43822, so that instance also runs one at a time.
- [ ] Deliver input only through the app UI, `playerctl -p kainosplayer.instance<n>` for a desktop instance, and `adb shell input` for the phone. Read-only diagnostics are the run log, `logcat`, `sqlite3 <data dir>/kainos.db`, and `avahi-browse -rtp _kainos-sync._tcp`.
- [ ] Save every screenshot to `/tmp/swarm-<pr-id>/worker-<n>/<slug>.png` and return the paths with the report. Take desktop screenshots with `grim -g "$(hyprctl -j activewindow | jq -r '"\(.at[0]),\(.at[1]) \(.size[0])x\(.size[1])"')"`.

## Add data-directory, hub-port, and instance overrides for isolated runs (HS0)

**Depends on.** None.

**Files.**

- [ ] Edit `core/data/src/jvmMain/kotlin/com/universalmusic/player/platform/PlatformCoredata.jvm.kt`.
- [ ] Edit `core/playback/src/jvmMain/kotlin/com/universalmusic/player/platform/MprisController.kt`.
- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HomeLanSyncService.kt`.
- [ ] Edit `README.md` to document the three variables.

**Build.**

- [ ] `KAINOS_DATA_DIR` replaces `user.home/.universal-music-player` when set. `KAINOS_HUB_PORT` replaces the hub port when set. `KAINOS_INSTANCE` appends `.instance<value>` to the MPRIS bus name `org.mpris.MediaPlayer2.kainosplayer`. Each variable is read once at startup, at the boundary.

**You see.**

- [ ] Two desktop instances run at the same time with different `KAINOS_DATA_DIR` values. `playerctl -l` lists both, and `~/.universal-music-player/kainos.db` keeps its modification time.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `PlatformCoredataJvmTest` asserts the data directory for an unset variable and for `KAINOS_DATA_DIR=/tmp/x`, with literal paths. Run `./gradlew :core:data:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Start the app with no variables at trunk and at head on a copy of the real data directory. Save `default-start.png`. Pass when both restore the same library count and paused session.
- [ ] Lane 2. Start with `KAINOS_DATA_DIR` set to an empty directory. Save `empty-data.png`. Pass when the app starts empty and creates `kainos.db` inside that directory.
- [ ] Lane 3. Start two instances with different data directories. Save `two-instances.png`. Pass when both windows run and `playerctl -l` lists two names.
- [ ] Lane 4. Send `playerctl -p kainosplayer.instance2 play-pause`. Save `mpris-target.png`. Pass when only instance 2 changes state.
- [ ] Lane 5. Start a hub with `KAINOS_HUB_PORT=44005`. Save `hub-port.png`. Pass when `ss -ltn` shows 44005 and not 43822.
- [ ] Lane 6. Start without `KAINOS_INSTANCE`. Save `mpris-default.png`. Pass when the bus name is `org.mpris.MediaPlayer2.kainosplayer`.
- [ ] Lane 7. Set `KAINOS_DATA_DIR` to a path that cannot be created. Save `bad-dir.png`. Pass when the app exits with a message that names the path.
- [ ] Lane 8. Run `kainos-player --hub-only` with `KAINOS_DATA_DIR`. Save `hub-only-dir.png`. Pass when the hub reads pairing from that directory.
- [ ] Lane 9. Run the packaged launcher `kainos-player --rebuild` with no variables. Save `launcher.png`. Pass when it uses the real data directory.
- [ ] Lane 10. Read `README.md`. Save `readme.png`. Pass when the three variables and their defaults are documented.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Desktop cold start time to the first frame.
- [ ] Probe. Ten interleaved cold starts at trunk and head, timed from process start to the first `TRACE` line.
- [ ] Baseline. Record the trunk median first.
- [ ] Rule. Head median must be within trunk plus 50 ms.

**Review gate.** None. HS0 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS0 to the base-branch stack and the operator lands it bottom-up.

## Write only the session state row when the queue is unchanged (HS1)

**Depends on.** HS0.

**Files.**

- [ ] Edit `core/data/src/commonMain/kotlin/com/universalmusic/player/data/db/SessionDb.kt`.
- [ ] Edit `core/data/src/commonMain/sqldelight/com/universalmusic/player/data/db/ListeningSession.sq`.
- [ ] Edit `core/data/src/jvmTest/kotlin/com/universalmusic/player/data/db/DatabaseRoundTripTest.kt`.

**Build.**

- [ ] In `writeSessionSnapshot`, compare the new queue with the stored `session_queue_item` rows by `item_id`, `canonical_id`, and `track_json` with `cachedAtMs` removed before comparison, because `cachedAtMs` changes on every snapshot. When the queue matches, call only `upsertState`. Otherwise keep the current clear and insert path inside the same transaction.

**You see.**

- [ ] Skipping a track in a 500-entry queue runs one `INSERT OR REPLACE` on `session_state` and no `DELETE` or `INSERT` on `session_queue_item`, shown by the SQLite statement trace in the perf harness.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `DatabaseRoundTripTest` gains a case that writes a snapshot, writes it again with a new `currentIndex` and new `cachedAtMs` values, and asserts that the `total_changes()` delta across the second write is 1. It also asserts the read-back snapshot has the second `currentIndex` and the first write's `cachedAtMs` values, because the queue rows were kept. Run `./gradlew :core:data:jvmTest`.
- [ ] A second case changes one track title and asserts the `total_changes()` delta equals 2 times the queue size plus 1. Run `./gradlew :core:data:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Load a 500-track local folder, play, skip five times, and quit, at trunk and at head. Save `restore-trunk-vs-head.png`. Pass when both restart paused on the same track and position bucket.
- [ ] Lane 2. Skip ten times within 5 seconds on desktop. Save `rapid-skip.png`. Pass when the restored track after restart is the last one played.
- [ ] Lane 3. Reorder the queue by drag, then restart. Save `reorder-restore.png`. Pass when the restored order matches the reordered order.
- [ ] Lane 4. Play next on a track, then restart. Save `play-next-restore.png`. Pass when the inserted entry is present after restart.
- [ ] Lane 5. Turn shuffle on, skip, then restart. Save `shuffle-restore.png`. Pass when shuffle is on and the current track matches.
- [ ] Lane 6. Seek to 2:00 and pause, then restart. Save `seek-restore.png`. Pass when the position restores within the 10-second bucket.
- [ ] Lane 7. Clear the queue, then restart. Save `empty-restore.png`. Pass when the app starts with an empty queue and no error in the log.
- [ ] Lane 8. On the phone, skip three tracks, swipe the app away, and reopen. Save `phone-restore.png`. Pass when the phone restores paused on the third track.
- [ ] Lane 9. Learn a new duration by playing a track whose stored duration is 0, then restart. Save `learned-duration.png`. Pass when the learned duration survives restart.
- [ ] Lane 10. Kill the desktop JVM with `kill -9` during playback, then restart. Save `crash-restore.png`. Pass when `PRAGMA integrity_check` returns `ok` and the session restores.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Rows changed per session write after a track skip with a 500-entry queue, read as the `total_changes()` delta across the whole transaction, and the median write time.
- [ ] Probe. A JVM harness in `core/data/src/jvmTest` writes the same snapshot 200 times with only `currentIndex` changing, against trunk and head builds, interleaved.
- [ ] Baseline. Record the trunk delta and median write time first.
- [ ] Rule. The head delta must be 1. The head median write time must be at most one third of trunk (root countersign retained in `.audit/hs-program.decisions.tsv`; relaxed from one fifth to preserve the verified row-update correctness). A head median above trunk fails.

**Review gate.** None. HS1 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS1 to the base-branch stack and the operator lands it bottom-up.

## Find the hub by mDNS and trust only the certificate pin (HS2)

**Depends on.** HS1.

**Files.**

- [ ] Create `sync/src/jvmMain/kotlin/com/universalmusic/player/data/sync/AvahiHubAdvertiser.kt`.
- [ ] Create `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/LanInterfaces.kt`.
- [ ] Create `sync/src/androidMain/kotlin/com/universalmusic/player/data/sync/NsdHubResolver.kt`.
- [ ] Create `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HubEndpointResolver.kt`.
- [ ] Edit `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/HomeLanTls.jvm.kt` and `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/PlatformSync.jvm.kt`.
- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HttpHomeLanSyncClient.kt` and `HomeLanSyncService.kt`.
- [ ] Edit `core/model/src/commonMain/kotlin/com/universalmusic/player/data/sync/HomeLanSyncModels.kt`.
- [ ] Edit `sync/build.gradle.kts` to add `dbus-java-core` and `dbus-java-transport-native-unixsocket` to the JVM dependencies and to move the JVM client from `ktor-client-cio` to `ktor-client-okhttp`.

**Build.**

- [ ] `LanInterfaces` selects eligible interfaces explicitly. An eligible interface is up, not loopback, not virtual, and has a private IPv4 address. Virtual means a name that starts with `docker`, `br-`, `veth`, `virbr`, `tailscale`, `tun`, `tap`, or `wg`. When several interfaces are eligible, the one that carries the default route wins.
- [ ] The hub creates an Avahi entry group (`EntryGroupNew`, `AddService`, `Commit`) for `_kainos-sync._tcp` on each eligible interface with protocol IPv4, instance name `Kainos on <hostname>`, and TXT records `fp=<64 hex hub certificate SHA-256>`, `id=<hub deviceId>`, and `v=3`. It checks the eligible address set every 30 seconds and publishes again when the set changes. It calls `Free()` when the hub stops. If the Avahi D-Bus service is missing, the hub logs `Avahi unavailable, not advertising` and keeps listening.
- [ ] `HubEndpointResolver` tries the stored `hubHost` first. The whole probe, connect plus TLS plus the `/kainos-sync/v1/health` request, has a 3-second limit. On failure it browses for 4 seconds, keeps only services whose TXT `fp` equals the pinned fingerprint, and probes each with the same pinned TLS. It accepts the first that answers and stores that address as the new `hubHost`. A TXT `fp` alone never makes an endpoint trusted.
- [ ] The Android resolver runs one `resolveService` call at a time, because Android before API 34 fails a second concurrent resolve.
- [ ] The desktop client moves from Ktor CIO to the Ktor OkHttp engine with the same `SSLContext`, pin trust manager, and `hostnameVerifier { _, _ -> true }` as Android. Appendix A shows that CIO checks hostnames after the pin, which fails for any address outside the certificate SANs.
- [ ] `HttpHomeLanSyncClient.baseUrl` brackets IPv6 literals.

**You see.**

- [ ] After the PC's Wi-Fi address changes, the phone's next sync logs `HomeSync resolved hub via mDNS <new address>` and succeeds without re-pairing.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `HubEndpointResolverTest` in `sync/src/commonTest` covers these cases with a fake browser and a fake prober. The stored address works. The stored address fails and discovery finds the pinned hub. Discovery finds only a hub with a different `fp`, and the resolver returns `HubNotFound`. A service advertises the right `fp` but fails the pinned TLS probe, and the resolver returns `HubNotFound`. Discovery times out. Run `./gradlew :sync:jvmTest`.
- [ ] `LanInterfacesTest` feeds literal interface lists and asserts the selection, including a machine whose default route is `tailscale0`. Run `./gradlew :sync:jvmTest`.
- [ ] `HomeLanProtocolCompatibilityTest` gains a case that connects through `127.0.0.2`, which is absent from the certificate SANs, and asserts the sync succeeds. Run `./gradlew :sync:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Pair the phone, change the PC's Wi-Fi address with `nmcli`, then press Sync now on the phone, at trunk and at head. Trunk fails with a timeout, which the lane records. Save `ip-change-trunk-vs-head.png`. Pass when head syncs within 10 seconds of the press without re-pairing.
- [ ] Lane 2. Run `avahi-browse -rtp _kainos-sync._tcp` with the desktop hub running. Save `avahi-browse.png`. Pass when exactly one record appears, on the Wi-Fi address, with the `fp` TXT value equal to the pin in Settings.
- [ ] Lane 3. Start Docker so a bridge interface exists, then repeat lane 2. Save `docker-bridge.png`. Pass when no record carries a `172.` address or a `100.` address.
- [ ] Lane 4. Stop the hub. Save `hub-stopped.png`. Pass when `avahi-browse` shows no record within 10 seconds.
- [ ] Lane 5. Advertise a fake service with `avahi-publish -s Fake _kainos-sync._tcp 44099 fp=<64 zeros>` and press Sync now on the phone with a stale stored address. Save `fake-hub.png`. Pass when the phone ignores the fake service and syncs with the real hub.
- [ ] Lane 6. Advertise a fake service that copies the real `fp` but points at `openssl s_server` with a different certificate. Save `pin-mismatch.png`. Pass when the phone rejects it with `Hub certificate pin mismatch` and no data moves.
- [ ] Lane 7. Turn the phone's Wi-Fi off and on, then open the app. Save `wifi-toggle.png`. Pass when foreground auto-sync succeeds.
- [ ] Lane 8. With the stored address still valid, press Sync now five times. Save `fast-path.png`. Pass when no sync logs a discovery browse.
- [ ] Lane 9. Use Sync now on the PC as client to its own hub through a non-SAN address. Save `pc-client.png`. Pass when it succeeds.
- [ ] Lane 10. Disconnect and reconnect the PC's Wi-Fi while the hub runs. Save `republish.png`. Pass when `avahi-browse` shows the record again within 40 seconds of reconnecting.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Time from Sync now to the first successful request on the phone, with the stored address valid, and separately with the stored address stale. Trunk lacks the stale-address case, so that case gets an absolute budget.
- [ ] Probe. Twenty interleaved Sync now presses at trunk and head with a valid address, timed from the `KainosTrace` log. Then twenty presses at head with a stale stored address.
- [ ] Baseline. Record the trunk median for the valid-address case first.
- [ ] Rule. Head valid-address median must be within trunk plus 100 ms. Head stale-address median must be at most 5 seconds, and any single run above 10 seconds fails.

**Review gate.** None. HS2 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS2 to the base-branch stack and the operator lands it bottom-up.

## Pair with per-device keys over mutual TLS and delete the shared secret (HS3)

**Depends on.** HS2.

**Files.**

- [ ] Create `core/model/src/commonMain/kotlin/com/universalmusic/player/data/sync/DeviceTrust.kt` with `CertFingerprint`, `PairedPeer`, `PairingAttempt`, and `PairingCode`.
- [ ] Create `sync/src/androidMain/kotlin/com/universalmusic/player/platform/AndroidDeviceIdentity.kt` and `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/JvmDeviceIdentity.kt`.
- [ ] Create `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/PairingProtocol.kt`.
- [ ] Edit `sync/src/jvmMain/kotlin/com/universalmusic/player/data/sync/DesktopHomeLanSyncHub.kt` and `HomeLanHubTls.kt`.
- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/platform/HomeLanTlsPlatform.kt`, `sync/src/androidMain/kotlin/com/universalmusic/player/platform/HomeLanTls.android.kt`, and `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/HomeLanTls.jvm.kt`.
- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HttpHomeLanSyncClient.kt` and `HomeLanSyncService.kt`.
- [ ] Edit `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/PlatformSync.jvm.kt`, which reads `sharedSecretHex` today.
- [ ] Edit `shared/src/commonMain/kotlin/com/universalmusic/player/app/AppContainer.kt`, which holds the `kainos-homesync:` string today.
- [ ] Edit `core/model/src/commonMain/kotlin/com/universalmusic/player/data/sync/HomeLanSyncModels.kt` and `core/model/src/commonMain/kotlin/com/universalmusic/player/data/settings/AppSettings.kt`.
- [ ] Edit the tests that use the deleted fields and crypto. They are `sync/src/jvmTest/kotlin/com/universalmusic/player/data/sync/HomeLanHubAuthTest.kt`, `HomeLanProtocolCompatibilityTest.kt`, `VaultStagedTransferTest.kt`, `sync/src/commonTest/kotlin/com/universalmusic/player/data/sync/HomeLanPairingAndCryptoTest.kt`, and `HttpHomeLanSyncClientTest.kt`. Each one moves to certificate authentication in this PR.
- [ ] Edit `shared/src/commonMain/kotlin/com/universalmusic/player/ui/screens/settings/SettingsDevicesSection.kt`, `SettingsPresenters.kt`, and `SettingsAdvancedSection.kt`.
- [ ] Delete `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/SyncPayloadCrypto.kt` and the crypto cases in `HomeLanPairingAndCryptoTest.kt`.

**Build.**

- [ ] `CertFingerprint` is a value class that parses 64 lowercase hex characters once, at the boundary. `PairedPeer` holds `deviceId`, `displayName`, `fingerprint`, `lastKnownHost`, and `pairedAtMs`.
- [ ] The phone creates an EC P-256 key in Android Keystore under alias `kainos-home-sync`, with the self-signed certificate Keystore generates. The key never leaves Keystore. The PC creates an EC P-256 key and certificate in `<data dir>/home-lan/device.p12` with mode 0600 and a random password in a separate 0600 file. The password derived from the shared secret is deleted.
- [ ] Both clients present their certificate. The hub turns HTTP/2 off (`enableHttp2 = false`) and replaces Ktor's `ssl` handler in `channelPipelineConfig` with a Netty `SslHandler` built from `SslContextBuilder.forServer(...)`, a trust manager that accepts any certificate, and `ClientAuth.OPTIONAL`. Appendix A shows why each of the three settings is needed.
- [ ] The hub authorizes each request in one function. It reads the peer leaf certificate from the call's Netty `SslHandler` session and computes its fingerprint. A paired fingerprint passes every route, and the function returns that fingerprint's `PairedPeer`. Every route takes the peer's device ID from that `PairedPeer`, never from the request body. An unpaired fingerprint passes only the pairing routes. A request with no certificate gets 401 on every route, pairing included.
- [ ] Pairing is one attempt at a time, bound to the TLS identities each side observed.
  - [ ] The phone sends `POST /kainos-sync/v3/pair/start` with its display name and `commitP = SHA-256(nonceP)`, where `nonceP` is 32 random bytes. The hub records an attempt with a random 128-bit `attemptId`, the client fingerprint from this TLS session, its own fingerprint, `commitP`, and a 32-byte random `nonceH`. It answers with `attemptId` and `nonceH`. The phone records the hub fingerprint from its own TLS session, never from the response body.
  - [ ] While an attempt is pending, the hub answers every other `pair/start` with 409. The hub accepts at most 5 starts per minute in total. Every later pairing request must carry the same `attemptId` from the same client fingerprint, or the hub answers 403.
  - [ ] The phone sends `POST /kainos-sync/v3/pair/reveal` with `nonceP`. The hub checks `SHA-256(nonceP)` against `commitP`. A mismatch ends the attempt.
  - [ ] Both sides compute the code from `SHA-256("kainos-pair-v1" || fpHub || fpPhone || nonceH || nonceP)`. The code is the first 4 bytes read as an unsigned big-endian integer, modulo 1,000,000, printed with leading zeros to 6 digits.
  - [ ] Trust needs both confirmations. The PC's Confirm button is bound to its `attemptId`. The phone sends `POST /kainos-sync/v3/pair/confirm` after its own Confirm. The hub stores the phone as a `PairedPeer` only when both confirmations exist, and then answers `paired` to the phone's `GET /kainos-sync/v3/pair/status`. The phone stores the hub only after that answer.
  - [ ] Cancel on either side, a reveal mismatch, or 2 minutes without both confirmations ends the attempt. An ended attempt never resumes. The user starts a new one.
- [ ] Settings replaces Rotate pairing with two explicit actions. Remove device deletes one `PairedPeer`. Reset this PC's identity creates a new key, deletes every `PairedPeer`, restarts the hub's TLS, and publishes the new `fp`. A phone that meets the new fingerprint shows "This PC's identity changed. Pair again." On the phone, Reset this phone's identity deletes the Keystore key and the stored hub.
- [ ] The pairing screen state is one sealed type, `PairingUiState`, with the variants `Idle`, `Searching`, `Found(hubs)`, `Confirming(attemptId, hub, code)`, `WaitingForPc`, `Paired(peer)`, and `Failed(reason)`. The PC shows the incoming request with the phone's name, the code, and Confirm and Reject buttons.
- [ ] Delete `sharedSecretHex`, `pairingPin`, `encryptPayloads`, the `kainos-homesync:` URI format and parser, the copy-link and paste-URI controls, the manual host, secret, and PIN fields in Advanced, the bearer header, the payload encryption in `HttpHomeLanSyncClient` and `DesktopHomeLanSyncHub`, and `SyncPayloadCrypto`. Settings that hold an old pairing load with no pairing and show "Pair again to keep syncing".

**You see.**

- [ ] On the phone, Settings, then Devices and sync, then Pair with a PC, lists `Kainos on <hostname>`. Tapping it shows a 6-digit code that matches the code on the PC. After Confirm on both screens, Sync now succeeds with no `Authorization` header in the hub log.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `PairingProtocolTest` asserts the code for fixed fingerprints and nonces equals a literal 6-digit string, including a case whose code has a leading zero. It also asserts that a mismatched reveal ends the attempt, a second start gets 409, a request with another client fingerprint gets 403, a sixth start in one minute is refused, an expired attempt fails, and trust is stored only after both confirmations. Run `./gradlew :sync:jvmTest`.
- [ ] `HomeLanMutualTlsTest` starts the Netty hub and asserts that a paired client succeeds, an unpaired client gets 401 on `/kainos-sync/v2/hearts`, a client without a certificate gets 401 on `pair/start`, and a client that pins the wrong hub fingerprint fails the handshake. Run `./gradlew :sync:jvmTest`.
- [ ] `SettingsSyncPresenterTest` in `shared/src/jvmTest` drives the presenter from `Searching` to `Paired` and from `Confirming` to `Failed` on cancel, with a fake service, asserting each state with literal values. Run `./gradlew :shared:jvmTest`.
- [ ] `HomeLanSettingsMigrationTest` loads a settings JSON that has `sharedSecretHex` and asserts the loaded settings have no pairing and the re-pair notice flag is set. Run `./gradlew :sync:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Pair the phone with the PC at trunk by copying the URI, and at head by tapping the PC. Save `pairing-trunk-vs-head.png`. Pass when head pairs without any copy or paste, the first Sync now succeeds, and `logcat` shows the Keystore key with `isInsideSecureHardware=true`.
- [ ] Lane 2. Upgrade both apps from a trunk pairing to head. Save `upgrade-repair.png`. Pass when both show "Pair again to keep syncing" and nothing syncs until re-paired.
- [ ] Lane 3. Reject the request on the PC. Save `reject.png`. Pass when the phone shows a rejected state and neither side stores a peer.
- [ ] Lane 4. Confirm on the PC but cancel on the phone. Save `phone-cancel.png`. Pass when neither side stores a peer.
- [ ] Lane 5. Send `curl -k https://<hub>:43822/kainos-sync/v2/hearts` with no client certificate, then with a fresh self-signed certificate. Save `curl-401.png`. Pass when both get 401.
- [ ] Lane 6. Start a pairing from the phone, then send `pair/start` from `curl` with another certificate. Save `second-start.png`. Pass when `curl` gets 409 and the phone's pairing completes.
- [ ] Lane 7. Remove the phone from the PC's device list, then press Sync now on the phone. Save `removed.png`. Pass when the phone shows that the PC no longer trusts it.
- [ ] Lane 8. Reset the PC's identity, then press Sync now on the phone. Save `reset-identity.png`. Pass when the phone shows "This PC's identity changed. Pair again." and `avahi-browse` shows the new `fp`.
- [ ] Lane 9. Use the pairing screens in dark and light schemes and in a narrow desktop window. Save `schemes-narrow.png`. Pass when the code and both buttons are visible and legible without scrolling.
- [ ] Lane 10. Grep the head tree for `sharedSecretHex`, `kainos-homesync:`, `bearerAuth`, and `SyncPayloadCrypto`, and read the key file modes. Save `grep-perms.png`. Pass when no match remains outside `HomeLanSettingsMigrationTest` and both key files are mode 0600.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Median time for one `/kainos-sync/v1/health` request including a fresh TLS handshake on loopback, and the time from tapping the PC on the phone to the code showing on both screens. Trunk has no pairing flow, so the second metric is an absolute budget.
- [ ] Probe. A JVM harness makes 50 health requests with a new client each time, interleaved between trunk and head hubs. Ten pairings at head on home Wi-Fi, timed from `KainosTrace` and the desktop run log.
- [ ] Baseline. Record the trunk health median first.
- [ ] Rule. The head health median must be within trunk plus 50 ms. The code must show within 3 seconds in every pairing run.

**Review gate.** The operator reviews before merge.

- [ ] Copy lane 1, lane 3, and lane 9 screenshots into `docs/plans/media/HS3-review-<slug>.png`.
- [ ] Record a 30 to 60 second video of pairing the phone with the PC. Save it as `docs/plans/media/HS3-review.mp4`.
- [ ] Post the screenshots and the video in chat for the operator. Stop at merge-ready. Wait for the operator's click.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS3 to the base-branch stack and the operator lands it bottom-up.

## Keep one hub listening for as long as its process runs (HS4)

**Depends on.** HS3.

**Files.**

- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HomeLanSyncService.kt`.
- [ ] Create `sync/src/jvmMain/kotlin/com/universalmusic/player/data/sync/HubOwnerLock.kt`.
- [ ] Edit `sync/src/jvmMain/kotlin/com/universalmusic/player/platform/PlatformSync.jvm.kt`, which writes the login autostart entry today.
- [ ] Edit `desktopApp/src/jvmMain/kotlin/com/universalmusic/player/desktop/Main.kt`.

**Build.**

- [ ] Delete the 30-minute idle stop at `HomeLanSyncService.kt:537`. The hub listens until its owning process stops it.
- [ ] A process listens only while it holds an exclusive `FileChannel.lock` on `<data dir>/home-lan/hub.lock`. The operating system drops the lock when the process dies, so at most one listener exists and a crash never leaves a stale owner.
- [ ] Login autostart becomes a systemd user unit, `~/.config/systemd/user/kainos-hub.service`, with `ExecStart=<launcher> --hub-only`, `Restart=on-failure`, and `RestartSec=5`, enabled for `graphical-session.target`. The unit name and its `Environment=` lines follow the HS0 overrides. Without `KAINOS_INSTANCE` the unit is `kainos-hub.service`. With `KAINOS_INSTANCE=<n>` it is `kainos-hub-<n>.service`, and its `Environment=` lines repeat `KAINOS_DATA_DIR`, `KAINOS_HUB_PORT`, and `KAINOS_INSTANCE`, so an isolated instance only ever starts, stops, or rewrites its own unit. Turning autostart on writes and enables the unit and deletes the old `~/.config/autostart/kainos-home-sync-hub.desktop`. systemd supervises the process, so the app never sends signals to a PID it read from a file.
- [ ] Hub ownership follows one sequence.
  - [ ] When the GUI starts and the unit is enabled, the GUI runs `systemctl --user stop kainos-hub.service`, which returns after the process has exited. The GUI then takes the lock and listens.
  - [ ] When the GUI cannot take the lock within 5 seconds, another GUI owns the hub. This GUI does not listen and logs `Home sync hub owned by another instance`.
  - [ ] When the GUI quits and the unit is enabled, the GUI stops its hub, releases the lock, and then runs `systemctl --user start --no-block kainos-hub.service`.
  - [ ] A `--hub-only` process waits up to 30 seconds for the lock. Without the lock it exits with code 75, and systemd starts it again after 5 seconds. With the lock it listens and logs `Kainos home sync hub listening`.
- [ ] Both processes write received peer data into `kainos.db`, which runs in WAL mode, so a GUI that starts later reads what the `--hub-only` process stored.

**You see.**

- [ ] With login autostart on, `ss -ltnp` never shows more than one listener on 43822 across this sequence, and every gap without a listener is shorter than 6 seconds. Log in, open the GUI, quit the GUI, and wait 40 minutes.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `HubOwnerLockTest` asserts that a second process cannot take the lock while the first holds it, that it takes the lock within 1 second after the first releases it, and that it takes the lock after the first process is killed with `kill -9`. The test starts real child JVM processes. Run `./gradlew :sync:jvmTest`.
- [ ] `HubOwnershipTest` drives the GUI start and quit sequence with a fake `systemctl` and asserts the exact command order, including that the lock is released before `start --no-block`. It also asserts that instances `3` and `4` name only `kainos-hub-3.service` and `kainos-hub-4.service`, and that an instance without `KAINOS_INSTANCE` names only `kainos-hub.service`. Run `./gradlew :sync:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Start the GUI with a paired hub, wait 35 minutes with no sync, then probe the port, at trunk and at head. Trunk stops listening, which the lane records. Save `idle-35min.png`. Pass when head still answers `/kainos-sync/v1/health` with a paired client.
- [ ] Lane 2. With the unit running, open the GUI. Save `handover-in.png`. Pass when `systemctl --user is-active kainos-hub.service` reports `inactive` and the GUI listens within 6 seconds.
- [ ] Lane 3. Quit the GUI with autostart on. Save `handover-out.png`. Pass when the unit is `active` and listens within 6 seconds of the GUI exiting.
- [ ] Lane 4. Quit the GUI with autostart off. Save `no-relaunch.png`. Pass when nothing listens.
- [ ] Lane 5. Kill the `--hub-only` process with `kill -9`. Save `restart.png`. Pass when systemd restarts it and it listens within 10 seconds.
- [ ] Lane 6. Start two GUIs on the same data directory within 1 second. Save `two-guis.png`. Pass when exactly one listens and the other logs `Home sync hub owned by another instance`.
- [ ] Lane 7. Sync from the phone while only the unit runs, then open the GUI. Save `data-visible.png`. Pass when the hearts synced through the unit appear in the GUI.
- [ ] Lane 8. Log out and in with autostart on. Save `login.png`. Pass when the unit listens after login without the GUI.
- [ ] Lane 9. Turn autostart on in Settings on a copy of a data directory whose user has the old `.desktop` entry, with `KAINOS_INSTANCE=9`. Save `migrate-autostart.png`. Pass when `kainos-hub-9.service` is enabled with the instance's `Environment=` lines, and `kainos-hub.service` and the real `.desktop` entry are unchanged.
- [ ] Lane 10. Watch `avahi-browse` across lanes 2 and 3. Save `advert-handover.png`. Pass when the record is present after each handover within 10 seconds.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Resident memory and CPU time of the `--hub-only` process over 60 idle minutes, and the gap without a listener during a GUI handover.
- [ ] Probe. `pidstat -r -u -p <pid> 60 60` on the `--hub-only` process at trunk and head, and ten handovers in each direction at head timed by polling the port every 100 ms.
- [ ] Baseline. Record the trunk `--hub-only` memory and CPU time first.
- [ ] Rule. Head memory must stay within trunk plus 10 percent, head CPU time within trunk plus 1 second per hour, and every handover gap below 6 seconds. Two listeners at any poll fails.

**Review gate.** None. HS4 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS4 to the base-branch stack and the operator lands it bottom-up.

## Record each device's own listening state (HS5)

**Depends on.** HS4.

**Files.**

- [ ] Create `core/data/src/commonMain/sqldelight/com/universalmusic/player/data/db/NowPlaying.sq`.
- [ ] Create `core/data/src/commonMain/sqldelight/com/universalmusic/player/data/db/2.sqm` and the schema snapshot `core/data/src/commonMain/sqldelight/databases/2.db`.
- [ ] Create `core/model/src/commonMain/kotlin/com/universalmusic/player/data/session/NowPlayingModels.kt`.
- [ ] Create `core/playback/src/commonMain/kotlin/com/universalmusic/player/domain/playback/NowPlayingRecorder.kt`.
- [ ] Edit `core/playback/src/commonMain/kotlin/com/universalmusic/player/domain/playback/PlayerSession.kt`.
- [ ] Edit `shared/src/commonMain/kotlin/com/universalmusic/player/app/AppContainer.kt`.

**Build.**

- [ ] `PlayerSession` owns `localRevision`, a guard scoped to that session instance and lifetime, which it increases on the session thread in the same step as every recorded change. The database write that follows runs off the session thread, so a check of `localRevision` on the session thread always sees the latest change even when the write has not finished.
- [ ] Each device publishes only its own state. The table `local_listening` has one row with `revision`, a separate durable publication revision allocated in the same SQLite transaction as the row. It strictly increases across restarts and concurrent GUI writers; it is not the process-local session guard. Only committed publications are exchanged. It also holds `last_active_at_ms` on this device's clock, `queue_item_id`, the portable `PersistedTrack` from `forPlaylistSync()`, `position_ms`, `position_at_ms`, `is_playing`, and `queue_hash`.
- [ ] `last_active_at_ms` is the last moment this device was playing or the user acted on it. `NowPlayingRecorder` writes a new revision on a track change of any cause, on play, on pause of any cause, on seek, and on a queue change. While playing, it also writes every 60 seconds so `last_active_at_ms` and the position stay current.
- [ ] The table `peer_listening` stores the latest state per peer device. Its columns are `device_id` as the primary key, `revision`, `last_active_at_ms` converted to this device's clock, `state_json`, `queue_hash`, and `queue_json`.
- [ ] The table `handoff_applied` stores, per peer device, the highest applied peer `revision` and the durable local activity generation at the moment of the load. Its `local_revision` is a durable device reference, not a session guard from a previous process. The applied transaction rechecks durable activity; the session separately rechecks its live guard.
- [ ] A failed portable projection persists current export unavailability and local activity atomically. An earlier valid row may remain for diagnostics but is never exchanged as current. Playback and ordinary startup continue; no exclusive playback lock, revision-range reservation, or new player-unready policy is introduced.
- [ ] Loading a remote session does not write `local_listening` as new activity. It keeps `last_active_at_ms` unchanged until the user plays, seeks, or edits the queue on this device.
- [ ] `queue_hash` is the SHA-256 of the canonical JSON array of `[itemId, portable track identity]` pairs in queue order, with `cachedAtMs` and artwork removed. The recorder computes it off the session thread.

**You see.**

- [ ] `sqlite3 kainos.db 'select revision, is_playing, position_ms from local_listening'` shows the current track, and the row changes on pause, on skip, on a natural track end, and every 60 seconds during playback.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `NowPlayingRecorderTest` drives a fake session through play, a natural track end, a sleep-timer pause, a seek, and a remote load, and asserts the exact rows, including that the remote load leaves `last_active_at_ms` unchanged. A second case blocks the database writer and asserts that `localRevision` already increased on the session thread after a paused seek. Run `./gradlew :core:playback:jvmTest`.
- [ ] `QueueHashTest` asserts a literal hash for a fixed queue, the same hash after `cachedAtMs` changes, and a different hash after a reorder. Run `./gradlew :core:model:jvmTest`.
- [ ] `DatabaseRoundTripTest` gains the 2 to 3 migration case on a copy of `databases/1.db` upgraded through 2. Run `./gradlew :core:data:jvmTest :core:data:verifyCommonMainKainosDatabaseMigration`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Trunk has no `local_listening` table, which the lane records. At head, play, pause, and skip on desktop. Save `row-desktop.png`. Pass when the row matches the visible track after each action.
- [ ] Lane 2. Upgrade a desktop data directory copied from trunk. Save `migration.png`. Pass when the app opens, the library and session restore, and `PRAGMA integrity_check` returns `ok`.
- [ ] Lane 3. Upgrade the phone with `install -r`. Save `phone-migration.png`. Pass when hearts, playlists, and the session survive and the row appears.
- [ ] Lane 4. Let a track end and the next one start. Save `natural-advance.png`. Pass when the row shows the new track within 2 seconds.
- [ ] Lane 5. Skip with the notification controls on the phone. Save `notification-skip.png`. Pass when the row changes.
- [ ] Lane 6. Skip with `playerctl -p kainosplayer.instance<n> next`. Save `mpris-skip.png`. Pass when the row changes.
- [ ] Lane 7. Play a local file. Save `local-row.png`. Pass when the row holds a `localkey:` or `localfile:` identity and no file path.
- [ ] Lane 8. Play a Spotify track and then a YouTube track. Save `provider-rows.png`. Pass when the rows hold `spotify:<id>` and `yt:<id>` and no stream URL.
- [ ] Lane 9. Let the sleep timer pause playback. Save `sleep-timer.png`. Pass when the row shows `is_playing = 0` with the pause position.
- [ ] Lane 10. Play for 5 minutes. Save `periodic.png`. Pass when the row's `position_ms` is within 60 seconds of the real position at every check.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Median time on the session thread per recorded change, and the total session write time per skip measured as in HS1.
- [ ] Probe. A JVM harness performs 200 skips at HS4 head and at HS5 head, interleaved, and reads both timings.
- [ ] Baseline. Record the HS4 head session write time first. Trunk lacks the recorder, so its write gets an absolute budget.
- [ ] Rule. The recorder must add at most 1 ms on the session thread per change, and its database write must run off that thread with a median of at most 5 ms. The session write time per skip must stay within the HS4 head value plus 10 percent.

**Review gate.** None. HS5 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS5 to the base-branch stack and the operator lands it bottom-up.

## Exchange listening states with the hub and deliver them late (HS6)

**Depends on.** HS5.

**Files.**

- [ ] Edit `sync/src/jvmMain/kotlin/com/universalmusic/player/data/sync/DesktopHomeLanSyncHub.kt`.
- [ ] Edit `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/HttpHomeLanSyncClient.kt` and `HomeLanSyncService.kt`.
- [ ] Create `sync/src/commonMain/kotlin/com/universalmusic/player/data/sync/ListeningExchange.kt`.
- [ ] Create `sync/src/androidMain/kotlin/com/universalmusic/player/data/sync/ListeningDeliveryWorker.kt`.
- [ ] Edit `sync/build.gradle.kts` to add `androidx.work:work-runtime-ktx` to the Android dependencies, and `gradle/libs.versions.toml` for its version.

**Build.**

- [ ] `POST /kainos-sync/v3/listening` carries the phone's latest `local_listening` row with `activeAgeMs`, the time since `last_active_at_ms` measured on the phone. The hub stores `last_active_at_ms = hubNow - activeAgeMs`, so clock skew between devices never decides which state is newer. The reply carries the PC's own row with its `activeAgeMs` and `needQueue`.
- [ ] `needQueue` is true whenever the hub has no verified queue for the posted `queue_hash`, including for a repeated revision. `PUT /kainos-sync/v3/queue/<queueHash>` uploads the phone's portable queue. The hub recomputes the hash, rejects a mismatch with 400, and stores the queue and the matching `peer_listening` row in one transaction. `GET /kainos-sync/v3/queue/<queueHash>` returns the PC's queue for that hash.
- [ ] One delivery loop runs per process, behind a mutex, and the foreground retry and the worker both call it. Each pass reads the current `local_listening` row, never the revision it was scheduled for. It posts the row. When the reply says `needQueue = true`, it uploads the queue and posts the row again. The pass ends when the hub answers `needQueue = false` for that revision.
- [ ] The phone stores `delivered_revision` in the database and sets it with a compare step, so it only ever increases. A late answer for revision N never marks revision N+1 as delivered. The phone has a pending row whenever `local_listening.revision` is greater than `delivered_revision`.
- [ ] On the phone, a pending row is retried every 30 seconds while `AndroidPlaybackService` is in the foreground. When the app goes to the background with a pending row, the phone enqueues unique work `listening-delivery` with a network constraint and exponential backoff starting at 30 seconds. The worker reads the row from the database and posts it with the Keystore identity. Opening the app sends at once.
- [ ] The hub keeps the newest revision per device and ignores a revision it already holds, so repeated delivery is harmless. The hub files every row under the device ID of the authenticated `PairedPeer`, and it answers 403 when the body names another device ID.

**You see.**

- [ ] With the PC off, play on the phone, then turn the PC on. Within 35 seconds of the hub listening, the desktop log shows `HomeSync listening from <phone name>` with the phone's current track.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `ListeningExchangeTest` asserts age-based ordering with a phone clock 10 minutes ahead and 10 minutes behind, a repeated revision that still returns `needQueue = true` until the queue arrives, a queue upload whose hash does not match, and a body that names another paired device's ID, with literal expected rows. Run `./gradlew :sync:jvmTest`.
- [ ] `ListeningDeliveryLoopTest` asserts that a late answer for revision N leaves revision N+1 pending, and that the foreground retry and the worker running together post each revision at most once per pass. Run `./gradlew :sync:jvmTest`.
- [ ] `ListeningDeliveryWorkerTest` in `sync/src/androidUnitTest` runs the worker with WorkManager's test driver against a fake client and asserts a retry after a failure and success on the second attempt. Run `./gradlew :sync:testDebugUnitTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Trunk has no exchange, which the lane records. At head, play on the phone with the PC hub running. Save `live-exchange.png`. Pass when the hub's `peer_listening` row matches the phone's track within 5 seconds of each skip.
- [ ] Lane 2. Play on the phone with the PC off, then start the PC hub. Save `pc-late-start.png`. Pass when the hub stores the row within 35 seconds.
- [ ] Lane 3. Pause on the phone, lock it, wait 1 minute, then start the PC hub. Save `paused-worker.png`. Pass when the worker delivers within 15 minutes. Otherwise report `LIMIT` with the observed delay and the HyperOS battery setting.
- [ ] Lane 4. Swipe the app away after pausing with the PC off, then start the PC hub. Save `swiped.png`. Pass when the worker delivers within 15 minutes. Otherwise report `LIMIT` with the evidence.
- [ ] Lane 5. Play the same queue on the phone for 10 skips. Save `queue-once.png`. Pass when the hub log shows one queue upload.
- [ ] Lane 6. Set the phone clock 10 minutes ahead and play. Save `clock-skew.png`. Pass when the hub's stored `last_active_at_ms` is within 5 seconds of the hub's own clock.
- [ ] Lane 7. Cut Wi-Fi during a queue upload. Save `upload-cut.png`. Pass when the hub stores no partial queue and the next delivery completes the upload.
- [ ] Lane 8. Remove the phone from the PC's trusted devices, then play. Save `untrusted.png`. Pass when the hub answers 401 and the phone stops retrying until re-paired.
- [ ] Lane 9. Run only `kainos-player --hub-only` and play on the phone. Save `hub-only.png`. Pass when the hub stores the row and a GUI opened later reads it.
- [ ] Lane 10. Measure request size with the queue unchanged. Save `payload.png`. Pass when each request is below 4 KB.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Delivery latency from the hub listening to the row stored while the phone plays, the request size without a queue upload, and the phone's battery drain over 30 minutes of playback. Trunk lacks the exchange, so the first two get absolute budgets.
- [ ] Probe. Ten runs of lane 2 timed from the hub start log line to the store log line. Request sizes from the hub access log. Battery drain from `adb shell dumpsys batterystats` over 30 minutes of playback at HS5 head and HS6 head.
- [ ] Baseline. Record the HS5 head battery drain first.
- [ ] Rule. Every delivery must land within 35 seconds. Every request without a queue must be below 4 KB. Battery drain must stay within the HS5 head value plus 1 percentage point.

**Review gate.** None. HS6 is not review-gated.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS6 to the base-branch stack and the operator lands it bottom-up.

## Continue the other device's session automatically, paused (HS7)

**Depends on.** HS6.

**Files.**

- [ ] Create `core/playback/src/commonMain/kotlin/com/universalmusic/player/domain/playback/HandoffDecision.kt`.
- [ ] Edit `core/playback/src/commonMain/kotlin/com/universalmusic/player/domain/playback/PlayerSession.kt`.
- [ ] Edit `shared/src/commonMain/kotlin/com/universalmusic/player/app/AppContainer.kt`.
- [ ] Edit `shared/src/commonMain/kotlin/com/universalmusic/player/ui/App.kt`.
- [ ] Edit `androidApp/src/main/kotlin/com/universalmusic/player/MainActivity.kt`.
- [ ] Edit `desktopApp/src/jvmMain/kotlin/com/universalmusic/player/desktop/Main.kt`.

**Build.**

- [ ] `decideHandoff(local, peer, applied, nowMs)` is a pure function that returns `HandoffDecision.Load(peer)` or `HandoffDecision.Skip(reason)`. It loads only when the peer's `last_active_at_ms` is later than the local one, the peer state is younger than 24 hours, this device is neither playing nor buffering, and the peer revision is greater than the one in `handoff_applied`. `Skip` reasons form an enum, so the log names them.
- [ ] `PlayerSession.loadRemotePaused(snapshot, peer, expectedLocalRevision)` runs on the session thread. It rechecks every condition there. The same session instance must still be active and its `localRevision` must equal `expectedLocalRevision`, so a Play, seek, or queue edit during the queue fetch wins. This process-local guard is separate from the durable publication/activity revisions; the applied write also rechecks the durable local generation. The fetched peer revision must still equal the newest stored peer revision and be greater than the applied one, so an older fetch that finishes late never replaces a newer load. It then resets the engine with `stopForTransition()`, restores paused, and writes `handoff_applied`.
- [ ] Local entries rematch through `LocalSyncIdentityIndex.match`. An entry with no local match stays in the queue and is skipped on play by the existing failure path.
- [ ] The decision runs only after the startup paused restore at `AppContainer.kt:391` finishes. The phone runs it after the foreground exchange in `MainActivity.onResume`. The PC runs it at startup, and while the window is open and idle whenever a new phone row arrives.
- [ ] After a load, the root scaffold shows a short notice that reads "Continued from <device name>". The app never starts playback and never stops the other device.

**You see.**

- [ ] Play on the phone, pause, and open the PC app. The PC shows the phone's track at the phone's position, paused, with the notice "Continued from Poco F7 Ultra". Space starts playback.

**Verify, unit.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] `HandoffDecisionTest` covers each `Skip` reason and the `Load` case with literal states. Run `./gradlew :core:playback:jvmTest`.
- [ ] `PlayerSessionRemoteLoadTest` asserts that a remote load while playing or buffering does nothing, that a load whose expected revision is stale does nothing, that a fetch of revision 10 finishing after revision 11 was loaded does nothing, that a load while paused replaces the queue and the position, and that Play afterwards resolves the new track and not the engine's old one. Run `./gradlew :core:playback:jvmTest`.

**Verify, live.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked. Ten lanes on the configured `swarm workers` model at the PR head, per the boot recipe.

- [ ] Lane 1. Regression lane against trunk. Play on the phone, pause, then open the PC app, at trunk and at head. Trunk restores the PC's own old session, which the lane records. Save `continue-from-phone.png`. Pass when head shows the phone's track and position, paused, with the notice.
- [ ] Lane 2. Play on the PC, quit it, then open the phone app. Save `continue-from-pc.png`. Pass when the phone shows the PC's track, paused, with the notice.
- [ ] Lane 3. Play on the PC, then open the phone app while it plays something else. Save `phone-playing.png`. Pass when the phone keeps playing its own track and logs `Skip(LOCAL_PLAYING)`.
- [ ] Lane 4. Open the PC app twice in a row with no new phone row. Save `no-repeat.png`. Pass when the second open logs `Skip(ALREADY_APPLIED)`.
- [ ] Lane 5. Use a phone row older than 24 hours. Save `stale.png`. Pass when the PC logs `Skip(TOO_OLD)`.
- [ ] Lane 6. Continue a queue with a local file that the PC lacks. Save `missing-local.png`. Pass when the entry shows as unavailable and Play skips it.
- [ ] Lane 7. Continue a Spotify track on the PC. Save `spotify-continue.png`. Pass when Play starts the same track through librespot at the stored position within 10 seconds.
- [ ] Lane 8. Leave the PC app open and idle while playing on the phone. Save `follow.png`. Pass when the PC follows the phone's track, paused, within 35 seconds of each skip.
- [ ] Lane 9. Press Play on the PC at the moment a phone row arrives. Save `play-race.png`. Pass when the PC plays its own track and logs `Skip(LOCAL_PLAYING)` or `Skip(LOCAL_CHANGED)`.
- [ ] Lane 10. Continue from the phone on the PC, play on the PC, then open the phone. Save `ping-pong.png`. Pass when the phone shows the PC's newer state and not its own old one.

**Verify, perf.** Tests alone are not sufficient verification. A PR is verified only when its unit, live, and perf boxes are all checked.

- [ ] Metric. Time from app foreground to the remote session shown paused, on the phone and on the PC, and cold start to the first frame. Trunk lacks the handoff, so the first metric is an absolute budget.
- [ ] Probe. Ten interleaved cold starts at HS6 head and HS7 head on each device, timed from the run log and `KainosTrace`.
- [ ] Baseline. Record the HS6 head cold start to the first frame first.
- [ ] Rule. The remote session must show within 3 seconds on Wi-Fi. Cold start to the first frame must stay within the HS6 head value plus 100 ms.

**Review gate.** The operator reviews before merge.

- [ ] Copy lane 1, lane 2, and lane 8 screenshots into `docs/plans/media/HS7-review-<slug>.png`.
- [ ] Record a 30 to 60 second video of continuing from the phone on the PC. Save it as `docs/plans/media/HS7-review.mp4`.
- [ ] Post the screenshots and the video in chat for the operator. Stop at merge-ready. Wait for the operator's click.

**Merge.**

- [ ] Root's clean verdict at the exact head SHA.
- [ ] Bugbot triage done.
- [ ] Base and verdict are current under the execution playbook and the patch-id rule in `playbooks/shipping.md`.
- [ ] The root appends HS7 to the base-branch stack and the operator lands it bottom-up.

## Close the program

- [ ] Every box above is checked with its evidence.
- [ ] Update `docs/CURRENT_STATE.md`, `docs/plans/home-lan-library-sync.md`, and `AGENTS.md` for the new pairing, discovery, hub lifetime, and handoff behavior.
- [ ] Reply to the operator with the report the execution playbook names.

## Appendix A. Prototype evidence

The prototype ran on branch `proto/home-sync-mtls` at `34296f0`, in the worktree `.claude/worktrees/proto-mtls`. The scratch tests are `sync/src/jvmTest/kotlin/proto/MtlsProtoTest.kt`, `MdnsProtoTest.kt`, and `AvahiDbusProtoTest.kt`. Raw logs are `/tmp/proto-mtls.log`, `/tmp/proto-mdns.log`, and `/tmp/avahi-*.log`. The prototype is throwaway, and no code from it ships.

### The Netty hub can accept any client certificate and read it per request

Ktor's `sslConnector` offers only a trust store, which forces `needClientAuth` with PKIX validation, so a self-signed phone certificate would fail and there is no optional mode. Replacing the `ssl` handler in `channelPipelineConfig` with a handler from `SslContextBuilder.forServer(kmf).trustManager(acceptAnyTm).clientAuth(ClientAuth.OPTIONAL)` works. The route reads the certificate through `NettyApplicationCall.context.channel().pipeline().get(SslHandler).engine().session.peerCertificates`. The handler saw the same fingerprint (`270ec14e...`) that the trust manager logged.

This works only with HTTP/2 off. With Ktor's default HTTP/2, ALPN sets up the pipeline after the handshake, the log showed `sslHandshakeDone=true` before the swap, and every client reached the handler with `no-peer-cert`. With `enableHttp2 = false` the swap ran before the handshake. Under `OPTIONAL`, a client without a certificate completes the handshake and the handler sees `SSLPeerUnverifiedException`, which the pairing route needs. Under `REQUIRE`, such a client fails with `certificate_required`, which would block pairing.

### Ktor CIO cannot be the client, and OkHttp can

CIO checks the hostname after the pin trust manager. Connecting by IP reverse-resolved the address and failed with `No server host: TheAdenKingof.lan in the server certificate` on `192.168.1.128` and with the Tailscale name on `100.82.114.81`. CIO also sent no certificate for an EC P-256 client key, and under `REQUIRE` it reconnected in a loop, 555 to 8,949 TLS setups for one request. The Ktor OkHttp engine on the JVM, with `SSLContext.init(keyManagers, pinTm)` and `hostnameVerifier { _, _ -> true }`, succeeded on `127.0.0.1`, `localhost`, `192.168.1.128`, and `100.82.114.81`, with EC and RSA client keys, over TLS 1.3. That is why HS2 moves the desktop client to OkHttp and why HS3 can use EC P-256 keys.

### Avahi over D-Bus advertises only on the Wi-Fi interface

JmDNS 3.6.4 bound to `192.168.1.128` worked and did not conflict with `avahi-daemon` on port 5353. After `kill -9`, its record stayed in Avahi's cache for at least 93 seconds. The `avahi-publish` command advertised on `lo`, `wlan0`, and `tailscale0`, with IPv6 too, and has no interface option. Avahi's D-Bus API through the existing `dbus-java` 5.1.1, with the `wlan0` interface index and IPv4, published one record, `=;wlan0;IPv4;...;192.168.1.128;43822`, and Avahi removed it within 3 seconds of a `kill -9` because it frees a client's entry groups when the D-Bus connection drops. HS2 uses the D-Bus path and adds no JmDNS dependency.

### Still unproven

Android Keystore EC keys as TLS client keys on HyperOS are unproven. HS3 lane 1 proves them on the phone. `NsdManager` resolution on HyperOS is unproven. HS2 lane 1 proves it. The prototype could not run on the phone, so the Android constraints in HS2 come from the platform documentation, not from a run.

## Appendix B. Alternatives rejected

A permanent server on the phone lost because Android freezes a paused background app, so the PC could not reach it when it matters. It would also need a notification that never goes away and a `specialUse` foreground service.

Turso lost because the payload is one row and one queue, the hard part is delivery while a device is off or frozen, and Turso Sync does not solve delivery. `docs/plans/sqlite-vs-turso.md` records the general assessment.

Firebase Cloud Messaging for a true push to an idle phone lost because it routes through Google's servers, and the operator ruled out cloud sync.

Keeping the shared secret for one release lost to the migrate-callers principle. Both devices belong to one person, who installs both builds, so one re-pair costs less than a second authentication path. The first plan split the key work and the pairing screen into two PRs. Review round 1 found that the first of them would reject every existing pairing before any new pairing screen existed, so HS3 now carries both.

A plain 6-digit code over the two fingerprints lost because a man in the middle can grind a key until the codes match, which takes about a million tries. Commit and reveal, one pending attempt at a time, and a start limit leave it one guess per attempt.

Absolute timestamps lost to ages measured on each device, because the phone and the PC clocks can differ by minutes.

Recording only user actions lost because the stored track would stop matching what plays after a natural track change. Each device now records every track change and pause, and writes a checkpoint every 60 seconds while playing.

JmDNS lost to Avahi over D-Bus because a killed JmDNS process left its record in Avahi's cache for at least 93 seconds, while Avahi removed a D-Bus client's record within 3 seconds. `avahi-publish` lost because it advertised on `lo` and `tailscale0` too.

## Appendix C. Risks

Android Keystore EC keys as TLS client keys through OkHttp are not proven on HyperOS, although Appendix A proves EC P-256 client keys with OkHttp on the JVM. HS3 lane 1 proves it. If it fails, the fallback is a software key wrapped by a Keystore AES key.

HyperOS may delay or kill the WorkManager delivery after a swipe. HS6 lanes 3 and 4 report `LIMIT` with evidence in that case, and the design still delivers when the app opens.

mDNS can fail on a router with client isolation or multicast filtering. HS2 keeps the stored address as the first try, so a stable address still works.

Turning HTTP/2 off on the hub is required for client certificates. The hub serves small JSON and single file transfers, so HTTP/1.1 costs nothing measurable. HS3's perf block checks the handshake cost.

A JVM test that runs the Avahi advertiser needs `avahi-daemon`. The unit tests use a fake advertiser, and only HS2 lanes 2 to 4 and 10 touch the real daemon.

Phone lanes run one at a time on the single Poco, and the desktop instance they pair with listens on 43822, so those lanes serialize. Desktop-only lanes run in parallel with the HS0 overrides.

The repo has no project verify skill. Lanes drive the real app through the boot recipe commands, and `/create-verification-skill` is a candidate follow-up.

HS4 moves login autostart from an XDG `.desktop` entry to a systemd user unit, so the hub depends on a systemd user session. CachyOS runs one. HS4 also deletes the 30-minute idle stop, which `AGENTS.md` and `docs/plans/home-lan-library-sync.md` describe. The hub then listens for as long as its process runs, which is what delivery after the PC starts needs. The close step updates both documents.

Media3 keeps the playback service in the foreground for up to 10 minutes after pause. The 30-second retry loop stops when the service leaves the foreground, and WorkManager takes over.

## Appendix D. Links and reading list

Read `docs/plans/home-lan-library-sync.md`, `docs/ARCHITECTURE.md`, and `HANDOVER.md` before editing.
HS3, HS4, HS6, and HS7 get `skills/how/SKILL.md` before the build and `skills/interrogate/SKILL.md` before stack-ready.
Every owner keeps a trail per `skills/show-me-your-work/SKILL.md`.
The explorer reports and plan review round 1 are in the T3 child threads of the planning thread, all run on Codex Pro `gpt-6.1-sol`. The prototype ran on Claude Opus 5.5.
