# Kainos Echo Gateway

Standalone service that transcodes one local FLAC to Alexa-compatible AAC and
hosts a minimal Alexa custom-skill endpoint.

Separate from Kainos `PlayerSession`, Home Sync, and the main app UI.

## Status

| Phase | Status |
| --- | --- |
| 1–2 Container + `/stream/test` FLAC→AAC-LC ADTS | Done |
| 3 Tailscale Funnel (host-side) | Scripts + docs |
| 4 Minimal Alexa skill (`POST /alexa`) | Done (dev skill setup manual) |

## Security for public exposure

Codex review required an unguessable token before Funnel:

- Public stream: `GET /stream/t/{PUBLIC_STREAM_TOKEN}` (constant-time compare)
- `/stream/test` disabled when `ALLOW_OPEN_TEST_STREAM=false` (Funnel default)
- `POST /alexa` verifies Alexa `Signature-256` + cert URL + timestamp (+ optional skill ID)
- Compose still binds **loopback only**; Funnel runs on the host

Never Funnel Home Sync (`43822`).

## Quick start (local only)

```bash
cd tools/echo-gateway
cp .env.example .env   # set PUBLIC_STREAM_TOKEN
docker compose up --build
curl -sS http://127.0.0.1:8787/health
```

## Funnel (Phase 3)

```bash
cd tools/echo-gateway
# Ensure .env has PUBLIC_BASE_URL=https://<machine>.<tailnet>.ts.net
# and a long PUBLIC_STREAM_TOKEN; ALLOW_OPEN_TEST_STREAM=false
chmod +x scripts/*.sh
./scripts/funnel-up.sh
```

Verify from a network outside the tailnet (phone cellular is ideal):

```bash
curl -sS "$PUBLIC_BASE_URL/health"
curl -sS -o /tmp/kainos-echo-test.aac \
  "$PUBLIC_BASE_URL/stream/t/$PUBLIC_STREAM_TOKEN"
ffprobe -hide_banner /tmp/kainos-echo-test.aac
```

Tear down:

```bash
./scripts/funnel-down.sh
```

### Manual Funnel commands

```bash
tailscale funnel --bg --yes 8787
tailscale funnel status
tailscale funnel reset
```

## Alexa skill (Phase 4)

See [`alexa-skill/README.md`](alexa-skill/README.md).

Flow:

1. Funnel exposes `https://<host>/alexa` and the token stream.
2. “Alexa, open Kainos test” → LaunchRequest → `AudioPlayer.Play` with the token HTTPS URL.
3. Echo pulls AAC from Funnel → gateway → FFmpeg.

After creating the skill, put `ALEXA_SKILL_ID` in `.env` and recreate the container.

## Encoding

```text
ffmpeg … -c:a aac -profile:a aac_low -b:a 256k -ac 2 -ar 44100 -f adts pipe:1
Content-Type: audio/aac
```

HTTP Range is rejected (`416`). Resume currently restarts from offset 0.

## Tests

```bash
cd tools/echo-gateway
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest -q
```

## Observed Echo behavior

Record after physical-device tests:

- Codec / container accepted: _pending_
- Startup latency: _pending_
- Premature end / buffering: _pending_
- Pause / resume: _pending_
