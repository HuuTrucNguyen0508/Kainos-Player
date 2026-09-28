# Echo dual-hub plan

Codex draft, 2026-09-21. Saved from plan review session.

**Build one stable public Alexa endpoint that selects the PC first and the phone second.** Keep playback files and transcoding on the selected device. Use a small, always-on Linux relay to host `/alexa` and expose both devices’ streams through one Funnel URL.

This adds an infrastructure dependency, but avoids making Android Funnel support a prerequisite. A skill endpoint hosted only on the PC cannot select the phone after the PC switches off.

The first milestone is deliberately narrow: with the phone gateway enabled, saying “lance test” starts a fresh shuffled local queue on the PC when ready, otherwise on the phone. Switching hubs during an existing track is deferred.

1. **Establish the baseline and resolve hosting before implementation.**

   The PC path is already proven: Docker on loopback `:8787`, Funnel, the Amazon.fr `fr-FR` skill, signature-checked `POST /alexa`, shuffled `~/Music/Playlist`, and AAC-LC ADTS playback on Echo.

   Extend the existing architecture:

   | Existing file | Responsibility and required change |
   | --- | --- |
   | `main.py` | HTTP routes; separate public and private surfaces |
   | `library.py`, `paths.py` | Filesystem indexing and containment; add root-scoped identity and revalidate when opening |
   | `playlist.py` | Fresh shuffle already exists; replace the process-global queue with scoped sessions |
   | `alexa.py` | Existing launch, transport and enqueue behavior; add session-aware routing and idempotency |
   | `alexa_verify.py` | Existing verification; complete certificate-chain validation and require the configured skill ID |
   | `stream.py` | Retain the proven FFmpeg encoding and disconnect cleanup |

   The README understates current queue functionality and still contains pending device observations. Update it from recorded evidence rather than restarting the original phases.

   Proposed deployment:

   ```text
   Alexa requests ── public HTTPS ──┐
                                   v
                         Always-on Linux relay
                         Funnel → loopback HTTP
                         /alexa + capability streams
                                   |
                         authenticated tailnet calls
                           /                   \
                          v                     v
                  PC gateway                Phone gateway
                  filesystem                Android SAF
                  FFmpeg                    native transcoder
                           \                   /
                            ── AAC to relay ──
                                   |
                            public HTTPS
                                   v
                                  Echo
   ```

   The relay stores routing records and temporary playback credentials. It does not hold the music library, transcode audio, or become a Home Sync hub. Prefer an existing always-on Linux device; a small VPS is an alternative if its cost and audio transit are acceptable.

   Funnel requires a platform that can run the Tailscale CLI. [Tailscale Funnel requirements](https://tailscale.com/docs/features/tailscale-funnel)

   **Gate result (2026-09-21 — phone Funnel first):** **failed on stock Tailscale Android.**

   Evidence on this tailnet:

   - Official docs: Funnel needs the Tailscale CLI; Android/iOS have no CLI.
   - Open FR for Android Funnel remains unresolved (`tailscale/tailscale#17071`).
   - `POCO F7 Ultra` is online (`100.115.143.49`, MagicDNS `poco-f7-ultra.taila3d69a.ts.net`) and pingable over the tailnet, but CapMap has no Funnel attrs, and nothing listens on `:443` / `:8787`.
   - PC Funnel control still works: `https://theadenkingof.taila3d69a.ts.net/health` → 200.

   Unsupported workarounds (Termux userspace `tailscaled`, third-party TailSocks) are out of scope for a dependable Echo fallback. Do not design dual-hub around phone-hosted Funnel.

   **Next gate:** identify a Linux relay that stays available when the PC is off (always-on box or small VPS). Phone remains a private hub: SAF + transcoder over the tailnet, reached through the relay’s public Funnel.

2. **Define one versioned hub contract and harden the PC implementation.**

   Share protocol fixtures and behavior between Python and Kotlin. Do not embed the Python gateway in Android merely to reuse code.

   | Operation | Minimum contract |
   | --- | --- |
   | Readiness | Protocol version, stable `hubId`, process `epoch`, ready state, reason, supported encoding, available capacity and configured collection readiness |
   | Start queue | Idempotent command ID, authorized principal/device, logical collection, fresh shuffle; returns session and first playback entry |
   | Handle event/control | Session, playback-entry token, Alexa request ID and relevant event or transport action |
   | Open stream | Authorized session entry; returns AAC bytes or a bounded error |
   | End session | Idempotent release of reservations and resources |

   Distinguish three identities:

   - Hub-local track ID identifies a file.
   - Session ID identifies one shuffled queue.
   - Playback-entry token identifies one occurrence of a track in that session.

   Bind entries to the hub epoch and queue revision. Raw track IDs, basenames and Alexa conversational session IDs are insufficient for callback routing.

   Keep the selected hub as queue authority. The relay owns selection, routing and public credentials. `PlayerSession` continues to own ordinary in-app playback and receives no Echo state.

   Support one active Echo session initially. Scope it to the authorized account/device, serialize its mutations, and reject an additional device cleanly. A new invocation on the same device replaces its prior session.

   The relay’s public `/alexa` verifies and translates requests into the private hub contract. Preserve the PC’s standalone `/alexa` adapter for rollback. Android implements the same adapter over its local queue controller; in the recommended deployment it is private and accepts only authenticated relay traffic.

   Retain the proven stream format: AAC-LC, ADTS, `audio/aac`, 256 kbps, 44.1 kHz stereo. Keep bounded buffering and cancellation through every layer. Allow current playback plus one prefetched stream; reject additional work predictably.

   **Gate:** contract fixtures pass against the PC, including duplicate starts, delayed callbacks, stale epochs, concurrent transport requests and disconnect cleanup.

3. **Implement the stable endpoint and PC-first selection.**

   Use an explicit two-entry registry, not multicast discovery or a leader-election system:

   ```text
   pc     priority 1 → configured private tailnet endpoint
   phone  priority 2 → configured private tailnet endpoint
   ```

   Each entry includes its expected identity, credentials and protocol version. Configure one public Funnel base URL on the relay. Return URLs beneath that origin for both hubs; never accept an arbitrary upstream URL from a request.

   On each fresh voice invocation:

   1. Check PC readiness with a short deadline.
   2. Attempt an atomic queue start on the PC. Readiness alone cannot reserve capacity or prove the first source remains readable.
   3. If unavailable, incompatible, empty, busy or unable to prepare the first entry, try the phone once.
   4. Persist the winning route before returning the Alexa directive.
   5. If neither succeeds, respond in French with a useful unavailable message.

   Start with a one-second readiness deadline per candidate and a five-second total routing/preparation budget. Measure these on the actual network. Avoid synchronous library scans in this path.

   Cache the winning route and exact response by Alexa request ID and body digest. Retries must not produce another shuffle or switch owners. If a PC preparation times out and later completes, expire or cancel that abandoned reservation; its late response must never reach Alexa.

   Health must distinguish process liveness from playback readiness. Android is ready only when the gateway is enabled, its SAF grants work, its source collection is usable and transcoding capacity exists. A shared public endpoint outage makes both routes unavailable; switching hubs cannot repair it.

   Keep a small bounded, atomically written routing store. Persist active token mappings, request deduplication and expiry. A relay restart must not silently send existing callbacks to the other hub.

   | Situation | Required behavior |
   | --- | --- |
   | Both hubs ready | New invocation chooses PC |
   | PC asleep or preparation fails | New invocation chooses phone |
   | PC wakes during phone playback | Existing session stays on phone |
   | Next invocation after PC recovers | Chooses PC |
   | Selected hub dies mid-track | Session fails; next invocation performs selection again |
   | Neither hub ready | Explain unavailability; no retry loop |

   Do not redirect an old stream URL to another device. The devices may contain different music.

   **Gate:** use a second test gateway to exercise selection, deadlines and restart behavior before integrating Android. Retain the PC’s direct Funnel configuration as a documented rollback path until the relay works on a physical Echo.

4. **Build the Android gateway inside Kainos’s SAF permission boundary.**

   Add an Android-specific gateway service and source adapter. Keep them separate from `AndroidPlaybackEngine`, Media3’s playback command queue, the existing MediaSession and Home Sync.

   A minimal explicit “Enable phone Echo hub” control and persistent Stop notification are necessary here. The fuller Settings status interface can wait.

   The source pipeline is:

   ```text
   Persisted SAF tree grant
       → DocumentsContract index
       → opaque local track ID
       → ContentResolver file descriptor
       → decoder/resampler/AAC encoder
       → bounded ADTS stream
       → private tailnet connection
       → relay Funnel
       → Echo
   ```

   Reuse configured SAF roots and scan-cache information. Enumerate with `DocumentsContract` cursors rather than per-file `DocumentFile` calls. Open only selected tracks for playback.

   Use a persisted root ID plus provider/document identity for Android track IDs. Use root ID plus normalized relative path on PC. Neither identity needs to match across devices in this milestone. Duplicate filenames must remain distinct.

   Treat `content://` as a provider reference, never a filesystem path. Decode document IDs only where needed for display or relative-folder selection. Validate membership in an authorized tree and handle revoked grants, moved documents and provider failures explicitly.

   Start with local on-device SAF storage and representative FLAC files. Exclude cloud-backed document providers from fallback readiness.

   Prefer a pinned, minimal native FFmpeg library through JNI to preserve the proven output format and handle high-resolution input. Feed it through a file-descriptor/custom-I/O adapter. Resolve dependency maintenance, licensing, ABI packaging, cancellation and non-seekable descriptor behavior before committing to the library. Do not make Termux, Docker or broad storage permission part of the production design.

   Native transcoding must not acquire local audio focus or play through the phone speaker. Close descriptors and stop encoding when Echo disconnects, the relay cancels, or the service stops.

   First prove that the relay can reach a private listener through the stock Android Tailscale client. Bind narrowly where supported and authenticate every request. If inbound access cannot be reliably constrained or maintained, use an authenticated outbound phone-to-relay connection as a separate transport decision. Do not fall back to an unauthenticated LAN listener.

   Android currently targets SDK 36. Select an appropriate foreground-service type for the actual remote-device streaming behavior. `connectedDevice` is a candidate to validate; do not assume an indefinitely idle server qualifies for `mediaPlayback`. Android documents time limits for `mediaProcessing`, so it cannot be treated as an unlimited standby mechanism. [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

   Initially, the user arms the phone gateway while Kainos is visible. Force-stop and reboot require re-enabling it unless a later lifecycle design proves otherwise. Alexa cannot resurrect a killed phone service.

   **Gate:** with the PC powered off, play one SAF FLAC through the relay on Echo, then a shuffled queue for at least an hour with the Poco screen off and unplugged. Failure here blocks claims of dependable phone fallback.

5. **Secure public access before enabling dual-hub use.**

   | Surface | Exposure | Protection |
   | --- | --- | --- |
   | Relay `POST /alexa` | Public HTTPS | Alexa request verification, mandatory skill ID, allowed account/device policy |
   | Relay stream route | Public HTTPS | Expiring per-entry capability |
   | Optional relay liveness | Public | Minimal response, no library or device details |
   | Hub readiness, queue control, private `/alexa`, source streams | Tailnet only | Restricted ACLs plus separate per-hub credentials |
   | Home Sync `43822` | Existing private boundary | Existing pinned TLS; never Funnel |

   Expose a dedicated public application or strict route allowlist. Funnel currently forwards the gateway server as a whole; adding private routes to that listener would expose them too.

   Replace the static library-wide stream token with a high-entropy, expiring capability for one entry. An opaque random token backed by the relay store is sufficient for the first version. HMAC-signed URLs are an alternative if they retain session revocation and strict claims validation.

   Bind authorization to hub, epoch, session, entry and expiry. Validate before starting transcoding. Permit legitimate fetch retries; do not use single-use tokens. Mint only current and queued entries, with a lifetime covering expected prefetch delay and retry allowance. Enforce expiry on new requests without cutting an already authorized stream mid-track.

   Use separate secrets for each hub and for public capabilities. Reject arbitrary paths, SAF URIs and remote URLs in public requests. Recheck filesystem containment or SAF authorization when opening the source.

   Complete the existing Alexa verifier’s certificate-chain validation to a trusted root. Validate certificate-fetch redirects, enforce timestamp and payload limits, and disable verification-bypass settings on public deployments. Amazon requires more than checking the signing leaf’s SAN and request signature. [Alexa web-service verification requirements](https://www.developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html)

   Apply bounded concurrency and rate limits. Redact capability URLs, credentials, Alexa tokens, SAF URIs and filesystem paths from application and proxy logs. The current stderr truncation is not path redaction.

   **Gate:** externally test invalid, expired and revoked capabilities, unsigned Alexa requests, wrong skill/account, path traversal, private-route exposure and cancellation. Verify that no Funnel configuration reaches `43822`.

6. **Make shuffle and callback behavior explicit.**

   Configure the logical collection `playlist` independently on each hub. Initially map it to the PC’s existing Playlist mount and a user-selected phone SAF folder.

   Later add an explicit `library` collection covering configured local roots. Honor explicitly empty root settings. Never infer roots from Spotify/YouTube caches or silently scan all MediaStore content.

   A fresh Launch or explicit play/shuffle intent creates a fresh permutation and starts with `REPLACE_ALL`. A retried request returns the same result. Independent shuffles may coincidentally produce the same order; tests should verify fresh generation rather than forbid coincidence.

   | Action | Behavior |
   | --- | --- |
   | New invocation / “lance test” | Re-select hub and create a fresh shuffled queue |
   | Next / Previous | Walk the existing session order |
   | Start over | Restart the current track at zero |
   | Pause | Stop delivery and retain session state |
   | Resume | Restart the current track at zero, retaining shuffle order |
   | End of queue | Stop; no automatic repeat initially |
   | Unknown intent | Help response, not an unexpected shuffle |

   Live ADTS remains non-seekable in this milestone. Retain `offsetInMilliseconds: 0` and the existing Range rejection. Document resume honestly and verify it on Echo; byte-range and accurate-offset support require a separate design.

   Route callbacks using playback-entry tokens and authenticated device context. AudioPlayer callbacks can arrive without an Alexa conversational session. Reserve one successor on `PlaybackNearlyFinished`, return it consistently on retries, and commit playback position on `PlaybackStarted`. Use `expectedPreviousToken` for enqueueing. Distinguish a failed prefetched item from the item currently playing. [Alexa AudioPlayer contract](https://developer.amazon.com/en-US/docs/alexa/custom-skills/audioplayer-interface-reference.html)

   Serialize transitions and reject stale generations. Deduplicate both repeated request IDs and repeated lifecycle events. Distinct Next requests remain distinct user actions. Do not reshuffle on callbacks.

   Skip a small bounded number of unreadable entries on the same hub, then stop. Do not migrate the queue to the other hub after a playback failure.

   **Gate:** physical Echo tests cover fresh invocation, duplicate callbacks, rapid Next, Previous, pause/resume, end of queue, missing files and hub loss. Verify continued in-app playback separately.

7. **Qualify HyperOS behavior and add optional Settings status.**

   | Risk | Required mitigation or measurement |
   | --- | --- |
   | HyperOS suspends Kainos or Tailscale | Test both processes with screen off, battery saver and extended idle; document actual device settings needed |
   | Foreground-service restrictions | Validate service type and lifecycle on the installed Android version; report stopped state honestly |
   | Phone reboot or force-stop | Require explicit re-arming initially |
   | Wi-Fi changes or mobile-data handover | Cancel stalled streams, update readiness and recover on the next invocation |
   | CPU load and heat | Measure high-resolution FLAC transcoding, thermal throttling and battery drain |
   | Upload bandwidth | At 256 kbps, audio alone is about 115 MB/hour; allow extra bandwidth for overhead and prefetch |
   | Funnel throughput | Measure sustained playback; Funnel documents non-configurable bandwidth limits |
   | SAF grant loss or stale cache | Revalidate on source open and make the collection unavailable when necessary |
   | Relay outage | Report unavailable; it remains a deliberate single point of failure |

   Default phone availability to Wi-Fi. Require an explicit setting for mobile-data use. Avoid rapid background health polling and continuous wake locks while idle. Acquire any necessary locks only for measured active-stream requirements.

   Use the existing wireless adb workflow when device testing is authorized:

   ```bash
   adb devices -l
   adb -t <transport_id> install -r release/<apk>
   ```

   Select the current `_adb-tls-connect` transport rather than retaining a stale ID. Preserve in-place app data. Keep gateway logs and use the user’s shared phone logs/screenshots for remote diagnosis.

   Optional Settings can then show preferred hub, actual active hub, phone readiness and reason, selected Echo folders, relay reachability, last failure, and gateway enable/stop controls. Keep credentials masked and store Android credentials with platform protection.

   Do not add an Echo output picker or `AlexaPlaybackEngine`. Starting the service makes the phone available; it does not start Echo playback.

8. **Deliver in small commits with explicit stopping points.**

   | Commit | Deliverable |
   | --- | --- |
   | `docs(echo): define dual-hub topology and acceptance gates` | Hosting choice, contract and recorded baseline |
   | `echo: scope queues and tokens to playback sessions` | Session ownership, generations and callback idempotency |
   | `echo: separate private control and secure public streams` | Capability URLs, verifier hardening and route isolation |
   | `echo: add stable relay and prefer-PC routing` | Registry, deadlines, durable routing and simulated fallback |
   | `echo: validate relay playback on physical Echo` | PC through relay, migration and rollback instructions |
   | `android: add SAF-backed Echo source adapter` | Authorized source indexing and descriptor access |
   | `android: stream local FLAC as AAC for Echo` | Native transcoder and cancellation |
   | `android: add opt-in Echo gateway service` | Private protocol, queue behavior and lifecycle controls |
   | `echo: qualify PC-off phone fallback` | Physical-device acceptance results and operational notes |
   | `ui: show Echo hub availability` | Optional status interface after reliability is demonstrated |

   Each behavioral commit includes focused verification. Reuse language-neutral contract fixtures across Python and Kotlin. No build publication or phone installation is implied by this planning task.

   Explicit non-goals are Spotify/YouTube gateway playback, cloud music storage, automatic library copying, Home Sync changes, cross-device track matching, seamless mid-track failover, accurate seeking, multi-room playback, full-library voice search, Wake-on-LAN and silent app-to-Echo casting.

   This plan supersedes the original roadmap’s premature `AlexaPlaybackEngine` phase. Completion means the same voice invocation consistently chooses a ready PC, falls back to an armed phone when the PC is off, and returns to preferring the PC on the next invocation after recovery.
