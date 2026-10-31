# Echo gateway plan

Source plan: `~/Downloads/KAINOS_ECHO_GATEWAY_IMPLEMENTATION_PLAN.md` (2026-09-21).

Standalone service that transcodes local FLAC for Alexa `AudioPlayer` over public HTTPS (Tailscale Funnel later). Kept separate from Home Sync and `PlayerSession`.

## Codex review (2026-09-21)

**Verdict:** REQUEST CHANGES for the full roadmap. **GO** for Phases 1–2 only.

Blocking for later phases (do not skip):

1. App→Echo control is unproven. Custom skills return directives in response to Alexa requests; they are not Spotify Connect-style targets.
2. Skill backend hosting, Alexa request signature verification, and how the skill reaches the private control API must be specified before Phase 4+.
3. Do not Funnel `/stream/test` before signed URLs (or an equivalent unguessable token plus rate limits).
4. AAC must name muxer + MIME (ADTS + `audio/aac` chosen for the spike). Ordinary MP4 is unsuitable for a live pipe.
5. Resume / `offsetInMilliseconds` / Range / URL renewal must be designed before queue work.
6. Choose one queue authority (gateway vs `PlayerSession`) before Phases 8–10.
7. Android SAF → gateway path mapping is unresolved before the Kainos client.

## Implemented

Phases 1–2 spike in `tools/echo-gateway/`:

- Compose on `127.0.0.1:8787` only
- Read-only `/music`, non-root container
- `GET /health`, `GET /stream/test` (AAC-LC ADTS, 256 kbps, 44.1 kHz stereo)
- Concurrent stream cap, path containment, FFmpeg kill/reap on disconnect

Phases 3–4 (2026-09-21):

- Token stream `GET /stream/t/{PUBLIC_STREAM_TOKEN}` before Funnel (open `/stream/test` off by default in `.env`)
- Host scripts: `scripts/funnel-up.sh` / `funnel-down.sh`
- `POST /alexa` skill backend with Signature-256 verification + AudioPlayer.Play
- Skill package + setup notes under `alexa-skill/`

Funnel requires the tailnet admin to enable Funnel once via Tailscale login. Until then the gateway stays local-only.

See `tools/echo-gateway/README.md` for run/test commands.

## Product decision — dual hub (2026-09-21)

User goal: **local files only** on Echo (not Spotify/YouTube).

Hubs (both on Tailscale):

| Priority | Node | Role |
| --- | --- | --- |
| 1 (preferred) | Desktop PC | `tools/echo-gateway` when awake (private hub) |
| 2 (fallback) | Poco F7 Ultra | Phone-side gateway over SAF when PC is offline (private hub) |
| Public | Always-on Linux relay | Single Funnel URL for `/alexa` + streams; picks PC then phone |

**Phone Funnel gate (2026-09-21):** stock Tailscale Android cannot host Funnel (no CLI). Do not plan on Poco-as-public-endpoint. Alexa still needs public HTTPS on the relay; Tailscale alone is not enough for AudioPlayer.

Not in scope: casting Spotify/YouTube to Echo; treating Funnel hostname as auth.

Full dual-hub implementation plan (Codex draft): `docs/plans/echo-dual-hub.md`.

## Player parity (2026-10-31)

Goal: Echo should behave as close as possible to Spotify on Echo, for local files only.

Done in `tools/echo-gateway/`:

- Per-Echo sessions in `STATE_DIR`, surviving restarts. This settles item 6 above: the gateway is the queue authority.
- Exact resume by URL seek (`?start_ms=N`). The stream token is `<track_id>.<base_ms>.<nonce>`, which settles item 5. `offsetInMilliseconds` stays 0 and Range stays unsupported apart from `bytes=0-`.
- Shuffle on/off during playback, repeat-all by default, repeat-one, loop off. Unknown intents no longer touch playback.
- Responses carry only what each request type allows.
- ffprobe metadata with filename fallback, a token-gated art route, and per-track gain from an `ebur128` scan targeting -11 LUFS.
- Autostart through a systemd user unit, because `docker.service` is disabled and only `docker.socket` is enabled.

Two findings changed the design:

- Before this work a disconnected stream could hold its slot until a garbage-collection pass, or lose it permanently, because Starlette abandons the body iterator and cancels cleanup awaits. The response now closes the encoder itself and releases the slot synchronously. At the cap, the oldest encoder is killed instead of returning `503`.
- `expectedPreviousToken` is an error on `REPLACE_ENQUEUED`. The gateway sends `REPLACE_ENQUEUED` only when a stream is already queued, so a mode change before `PlaybackNearlyFinished` sends no directive and the new order applies when Alexa asks for the next track.

Open: behaviour on a physical Echo. See `tools/echo-gateway/README.md`.
