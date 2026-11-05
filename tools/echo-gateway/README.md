# Kainos Echo Gateway

Standalone service that plays a local music folder on Amazon Echo through a
private Alexa custom skill. It transcodes to Alexa-compatible AAC and behaves as
close to Spotify on Echo as a custom skill allows, for local files only.

Separate from Kainos `PlayerSession`, Home Sync, and the main app UI.

## Status

| Phase | Status |
| --- | --- |
| 1–2 Container + `/stream/test` FLAC→AAC-LC ADTS | Done |
| 3 Tailscale Funnel (host-side) | Scripts + docs |
| 4 Minimal Alexa skill (`POST /alexa`) | Done (dev skill setup manual) |
| Player parity: exact resume, shuffle, repeat, per-Echo sessions | Done, physical Echo test pending |
| Now-playing metadata and art, even volume | Done, physical Echo test pending |

## Voice and button controls

| Say or press | Result |
| --- | --- |
| "Alexa, lance personnel", or open the skill | Fresh shuffle of the whole folder |
| "Alexa, stop", pause button | Stop and save the exact position |
| "Alexa, reprends", play button | Same track, same position, same queue. Fresh shuffle if there is no session |
| "Alexa, suivant", "précédent", buttons | Walk the queue. Clears repeat-one |
| "Alexa, recommence" | Current track from 0 |
| "Alexa, active l'aléatoire" | Reorder the tracks after the current one. Playback continues |
| "Alexa, désactive l'aléatoire" | Continue in sorted library order from the current track |
| "Alexa, répète" | Current track from 0, then repeat it |
| Loop on | Repeat the list. This is the default, reshuffling at each wrap |
| Loop off | Stop at the end of the list, and clear repeat-one |

Amazon owns the built-in phrases, so the exact French wording may differ.

Amazon delivers Next, Previous, Shuffle, Loop, Repeat, StartOver and Cancel to a
skill that is playing even if they are missing from the deployed model. They are
in `fr-FR.json` anyway. Pause and Resume have no such exception.

## Sessions and resume

Each Echo has its own session, keyed by a hash of `deviceId`, in
`STATE_DIR/sessions/`. A session stores the track order, position, absolute
offset, shuffle and repeat state. Writes are atomic and sessions survive
container restarts. Tracks that left the library are dropped on load.

Resume uses a URL seek, not pre-transcoded files. `GET /stream/t/{token}/{id}?start_ms=N`
adds `-ss` before `-i`, and the Play directive keeps `offsetInMilliseconds: 0`.
Alexa then reports offsets relative to that stream, so the stream token carries
the base: `<track_id>.<base_ms>.<nonce>`. Absolute position is base + offset. The
nonce keeps ENQUEUE of the same track valid under repeat-one.

## Now playing for apps on this machine

A second listener in the same process serves apps on this machine, such as the
Kainos desktop app. Compose publishes it as `127.0.0.1:8788` only. It has no
token, so **never Funnel port 8788** and never publish it on another address.
`scripts/funnel-up.sh` maps only 8787.

| Route | Answer |
| --- | --- |
| `GET /now-playing` | Each Echo's state, track, position, modes and next five tracks (`Cache-Control: no-store`) |
| `GET /art/{track_id}.jpg` | The same art as the token route; 404 for unknown ids |
| `GET /health` | Same as on 8787 |

Port 8787 has no `/now-playing`, and port 8788 has no stream, art-token or Alexa
route, so it never shows the stream token or a stream URL.

```json
{"version": 1, "generated_at_ms": 1793925000000, "echoes": [{
  "device": "e56a4ca3", "state": "playing", "last_event_at_ms": 1793924990000,
  "track": {"id": "…", "title": "…", "artist": "…",
            "art_url": "http://127.0.0.1:8788/art/….jpg", "duration_ms": 201000},
  "position_ms": 12000, "position_at_ms": 1793924990000,
  "shuffle": true, "loop": true, "repeat_one": false,
  "up_next": [{"id": "…", "title": "…", "artist": "…", "duration_ms": 180000}]}]}
```

- `echoes` is newest first by `last_event_at_ms`. `device` is the first 8
  characters of the session file name, a hash of the Alexa device id.
- `state` is `playing`, `paused`, `stopped` or `idle` (no current track, so
  `track` is null). It comes from AudioPlayer events and from the device state
  Alexa attaches to every request. Sending a Play that replaces the stream
  counts as `playing` from its start position until the device reports.
- `position_ms` was true at `position_at_ms`. Add `now - position_at_ms` only
  while `state` is `playing`. It never exceeds `duration_ms`.
- An Echo that goes quiet sends nothing. `playing` turns into `stopped` once the
  track should have ended a minute ago, or 15 minutes after the last event when
  the duration is unknown.
- `up_next` is the order the session really plays. With repeat-one it holds the
  current track once. A shuffled list ends where it would reshuffle, since that
  order is drawn only when the list wraps.
- `duration_ms` comes from the scan (ffprobe `format.duration`) and is null
  until the scan reaches the track.
- `LOCAL_PORT` (default `8788`) and `LOCAL_API_BASE_URL` (default
  `http://127.0.0.1:8788`, used in `art_url`) configure it. If the port is busy,
  the gateway logs `local_api_failed category=bind` and keeps serving Alexa.

## Security for public exposure

Codex review required an unguessable token before Funnel:

- Public stream: `GET /stream/t/{PUBLIC_STREAM_TOKEN}/{track_id}` (constant-time compare)
- Public art: `GET /art/t/{PUBLIC_STREAM_TOKEN}/{track_id}.jpg`, same check, known tracks only
- `/stream/test` disabled when `ALLOW_OPEN_TEST_STREAM=false` (Funnel default)
- `POST /alexa` verifies Alexa `Signature-256` + cert URL + timestamp (+ optional skill ID)
- `POST /alexa` bodies are limited to 256 KiB
- Compose still binds **loopback only**; Funnel runs on the host
- The access log is off because request lines contain the token. Info logs hold no token, device id or file path

Never Funnel Home Sync (`43822`) or the now-playing API (`8788`).

The container does not mount `~/.universal-music-player`, which holds tokens.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `STATE_DIR` | `/state` | Sessions, `scan.json`, art cache. If unwritable, playback still works without persistence |
| `ECHO_STATE_DIR` | `./state` | Host folder bound to `/state` by Compose |
| `TARGET_LUFS` | `-11` | Loudness target. -11 matches Spotify Loud |
| `MAX_CONCURRENT_STREAMS` | `4` | Encoder cap. When full, new streams receive 503 with Retry-After; existing playback is protected |

The `state-init` service makes the state folder writable for container uid 10001
on a fresh checkout.

## Autostart

`docker.service` is not enabled on this machine, but `docker.socket` is. Boot
alone therefore starts no containers, even with `restart: unless-stopped`. A
systemd user unit runs `docker compose up -d`, which activates the socket:

```bash
./scripts/install-autostart.sh
systemctl --user start kainos-echo-gateway.service
```

The installer writes the unit with this checkout's absolute path and enables
linger, so the unit runs at boot without a login. Run it from the checkout that
holds `.env`.

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
  "$PUBLIC_BASE_URL/stream/t/$PUBLIC_STREAM_TOKEN"   # plays TEST_TRACK
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
2. “Alexa, lance personnel” → LaunchRequest → `AudioPlayer.Play` with the token HTTPS URL.
3. Echo pulls AAC from Funnel → gateway → FFmpeg.

After creating the skill, put `ALEXA_SKILL_ID` in `.env` and recreate the container.

## Encoding

```text
ffmpeg [-ss S] -i … [-af volume=GdB] -c:a aac -profile:a aac_low -b:a 256k -ac 2 -ar 44100 -f adts pipe:1
Content-Type: audio/aac
```

`Range: bytes=0-` returns the whole stream. Any other `Range` returns `416`.

A paused Echo may hold an encoder connection open. At the cap, new requests get
`503` with `Retry-After: 2`. The gateway cannot distinguish a paused connection
from a long mix under backpressure, so it never evicts a live encoder.

## Metadata and art

Every Play directive sends title, subtitle (artist), art and background image.

- Text comes from ffprobe tags. A missing field comes from the filename: it strips
  `y2mate.com - ` and `_<id>_320kbps`, then splits `Artist - Title`. There is no
  dependency on `kainos.db`.
- A track the scan has not reached uses filename metadata immediately. Play
  responses never wait for a probe.
- Art is the embedded picture, centre-cropped to 512×512 JPEG and cached in
  `STATE_DIR/art`. Tracks without art get `assets/default-art.jpg`.

## Even volume

A background task measures each track with FFmpeg `ebur128`, one at a time at
`nice 19`, and stores the result in `STATE_DIR/scan.json`, keyed by relative path,
size and mtime. It repeats hourly and reads only new or changed files, so new music
appears without a restart.

```text
gain_db = min(TARGET_LUFS - measured_I, -1.0 - measured_true_peak)
```

Tracks not yet scanned play at 0 dB. There is no limiter, so a track with little
headroom stays quieter than the target. On 36 real tracks, including the 18
loudest, AAC overshoot at the -1 dBTP ceiling clipped 14 samples in total, so a
lower ceiling is not worth the lost evenness. Unscanned tracks still play at unity gain.

`GET /health` reports `scan.scanned`, `scan.total` and `sessions`.

The same pass records each track's duration. Entries from a `scan.json` written
before durations existed get one from an ffprobe call each on the next pass,
without a new loudness measurement.

## Unreadable files

The container runs as uid 10001, so it can only read music files that are
world-readable on the host. A file with mode `600` is left out of the library.
`GET /health` reports the count as `unreadable_tracks`, and the log has a
`library_unreadable` warning. To list them, then fix them:

```bash
find ~/Music/Playlist -type f ! -perm -o=r
find ~/Music/Playlist -type f ! -perm -o=r -exec chmod o+r {} +
```

The hourly rescan picks the files up without a restart.

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

Alexa request IDs are cached per device for five minutes, up to 512 responses in
memory. Retries replay the response without changing the queue again. Restarting
the gateway clears this cache. Transient failures skip tracks without removing
them from the session queue; five consecutive failures stop recovery.
