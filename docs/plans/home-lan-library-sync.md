# Plan: Home LAN library + hearts sync

Status: **Phase 0–3 + pinned TLS shipped; extended to v3 routes** (pairing, listening, remote control, pins, cached audio, loudness). Checked against `main` at `9c1fad7` on 2026-11-11. Leave firewall 43822 blocked until soak on HTTPS; APK when asked

Date: 2026-09-16  
Handoff: Cleartext HTTP removed; hub uses self-signed TLS with SHA-256 pin in `kainos-homesync:2` URI

## Continue next time (start here)

### Done

| Slice | Status |
|---|---|
| Phase 0a heart-ops + `mergeAndPersistSyncState` + JVM tests | Done |
| Phase 0b vault union / tombstone / conflict pure types + tests | Done |
| Phase 1 pairing + LAN hearts hub/client + Settings UI | Done |
| Phase 2 music file transfer (SAF write, FGS, blob copy) | Done |
| Phase 3 polish (autostart hub, AES-GCM payloads, pairing URI, rematch) | Done; the idle timeout it listed was later removed |
| Pinned TLS (`kainos-homesync:2` + hub PKCS12 + HTTPS-only client) | Done |

### Manual soak (before trusting a release / opening the firewall)

1. PC: Settings → Home sync → Pick vault folder → Enable → **Start hub pairing** (generates cert + pin)  
2. Copy **pairing URI** (`kainos-homesync:2?…&cert=…`); hub port **43822** over **HTTPS**  
3. Phone: Pick vault folder (READ+WRITE SAF) → Pair from URI (or manual + 64-hex cert pin) → Sync now  
4. Hearts / vault / tombstone / conflict checks as before  
5. Only then consider a narrow UFW allow for 43822 from the phone (still prefer phone IP over whole subnet)

### Phase 3 + TLS notes

| Item | Status |
|---|---|
| Hub idle timeout (30 min) | Removed: the hub listens until its owning process stops (a systemd user unit or the GUI) |
| `--hub-only` + login autostart desktop file | Done |
| Pairing URI `kainos-homesync:2?…` with mandatory `cert=` pin | Done (URI text; no in-app QR) |
| PIN verified on hub requests | Done |
| Vault payload AES-GCM (`aes-gcm-v1`) under TLS | Done |
| Local-heart rematch by filename after vault sync | Done |
| **HTTPS + pinned hub leaf cert (SHA-256 DER)** | Done |
| Cleartext `kainos-homesync:1` / HTTP | Rejected |
| In-app QR render + camera scan | Done (`kainos-pair:3` link with the hub pin; CameraX + ZXing scan, then the same code compare) |
| Content-hash local canonical ids | Deferred |

### Security floor (current)

| | Current |
|---|---|
| Transport | HTTPS only (desktop hub `sslConnector`; clients pin leaf SHA-256) |
| Auth | Bearer shared secret + pairing PIN header (over TLS) |
| Extra | AES-GCM on vault index/blob bodies |
| Bind | `0.0.0.0:43822` — keep host firewall blocked until soak |

### Key files

| Area | Path |
|---|---|
| Plan | `docs/plans/home-lan-library-sync.md` |
| Hub TLS | `HomeLanHubTls.kt`, `HomeLanHubTlsMaterial.kt`, `HomeLanTls*.kt` |
| Vault I/O | `HomeLanVaultStore.kt`, `JvmHomeLanVaultStore.kt`, `AndroidHomeLanVaultStore.kt` |
| Transfer | `VaultTransfer.kt` |
| Crypto | `SyncPayloadCrypto.kt` (+ jvm/android actuals) |
| Hub | `DesktopHomeLanSyncHub.kt` |
| Client | `HttpHomeLanSyncClient.kt` |
| Coordinator | `HomeLanSyncService.kt` |
| Android FGS | `AndroidVaultSyncForeground.kt` / `HomeLanVaultSyncService` |
| Settings UI | `SettingsScreen.kt` (Home sync section) |
| Hub-only | `desktopApp/.../Main.kt` (`--hub-only`) |

### Verify commands

```bash
./gradlew :shared:jvmTest --tests 'com.universalmusic.player.data.sync.*' --tests 'com.universalmusic.player.data.library.*'
./gradlew :desktopApp:compileKotlinJvm :androidApp:compileDebugKotlin
```

---

## Goal

Sync **app-hearted tracks** and **local music files** between Android and Linux desktop when the user gets home, without Proton Drive, without Tailscale as a daily toggle, and without an always-on phone daemon.

## User constraints (accepted)

- ~50GB music library; Proton subscription exists but Proton+rsync is disliked
- Kainos is often still open on the phone when arriving home
- Phone can always join home Wi‑Fi
- Devices are **not used at the same time** (single active device between syncs)
- Always-on is acceptable **on PC only**; process may quit after sync
- No per-sync manual Tailscale toggle

## Non-goals (v1)

- Cloud / Proton Drive / object-storage vault
- Always-on phone sync service
- Off-home sync (LTE ↔ sleeping PC) without a tunnel
- Writing app hearts back to Spotify Liked Songs
- Syncing the librespot audio cache. (Hearted `audio-cache/` copies were a v1 non-goal too; they shipped 2026-11-04 as pending Transfer / Skip items, see below.)
- Syncing resolved streaming URLs
- Syncing `recents` or non-favorite `remembered` history
- Multi-device simultaneous editing / CRDTs
- Auto-copying vault blobs without user confirm (missing files become pending Transfer/Skip)

## Local hearts (post Phase 3)

- Wire id: `localkey:lc1:<hash>` when known; `localfile:<basename>` (lowercase) remains the fallback. Device `local:` ids stay on-device.
- Home-LAN heart ops: YouTube, localfile, and Spotify-scoped app hearts. Spotify ops cross only when both documents carry the same `spotifyAccountId`; the hub reply and the v1 downgrade omit them for any other peer.
- When the basename is missing on a peer, Sync now lists pending transfers; confirm copies the blob, then rematch hearts.

## Architecture

### Roles

| Role | Device | Behavior |
|---|---|---|
| Hub | Linux PC | In-process listener inside `:desktopApp` (or `--hub-only`, supervised by a systemd user unit). Port `43822` HTTPS. No idle stop: it listens until its process stops. |
| Client | Android phone | Connect when app open + hub reachable; Sync now; vault transfer under `dataSync` FGS |

### Channels

1. **Hearts** — `/kainos-sync/v1/health`, `/kainos-sync/v1/hearts`
2. **Vault** — `/kainos-sync/v1/vault/index`, `/kainos-sync/v1/vault/blob` (Range / offset resume)

## Phase checklist

### Phase 0 — Spec + tests — DONE

### Phase 1 — Hearts over LAN — DONE

### Phase 2 — Music files — DONE

### Phase 3 — Polish — DONE

### TLS pin — DONE (firewall stay closed until soak)

## Acceptance criteria

Phase 1–2 criteria remain; confirm on soak. Pairing URI includes cert pin; clients refuse HTTP and unpinned hubs.

## Review history

- Codex + GPT-5.6 Sol: approve-with-changes (2026-09-14)
- Fable 5.1: ship-with-fixes; plan body reconciled (2026-09-15)
- Implementation: Phase 0+1 (2026-09-15); Phase 2+3 (2026-09-15 evening)
- Security: cleartext flagged; pinned TLS landed (2026-09-16)

## Decision summary

Home-Wi‑Fi, phone-initiated, PC-listening sync.  
Hearts: heart-ops + merge.  
Files: vault union + tombstones + chunked blob copy.  
Wire: HTTPS with hub leaf pin from pairing; AES-GCM still on vault bodies. QR camera still deferred.

## Storage and identity redesign progress, 2026-10-02

SQLDelight stores now replace the legacy library, playlist, session and local scan JSON snapshots, retaining migrated originals. Local keys use `lc1:` plus SHA-256 of an eight-byte big-endian file size and the final 64 KiB. Desktop computes them during changed scans; Android computes them after scanning and logs batch timing. Known keys detect same-size vault conflicts, while older peers keep size fallback. Local unheart operations now propagate after rematch and restart.

The hub serves `/kainos-sync/v2/{hearts,playlists,vault/index}` alongside v1. Hearts and playlists export `localkey:` when keys are known, using current scan metadata even when a heart/playlist predates the key pass. Rematching uses keys first and unique basename only when no known key contradicts the match. Unambiguous v1/v2 heart aliases collapse; unheart operations retain identity through restart. Version 2 of the database stores nullable heart-operation identity JSON. Indexed database lookup supports portable-key provider resolution; bulk rematch uses the hydrated catalog's key index.

Clients try v2 first and retry v1 only on 404. The pinned client's normal error validation is disabled for these versioned POST requests so 404 can reach the fallback decision; explicit status checks still reject auth/server failures. v1 bodies/responses restore basename IDs and clear keys. Ambiguous heart basenames with distinct known keys are omitted from v1 rather than merged. The pairing URI and health/blob routes are unchanged. Keep v1 for one release after this cutover, then remove it.

The full JVM test suite passes across the extracted modules. Live loopback HTTPS tests cover new hub/v2 clients, old-client v1 routes, new pinned-client fallback to a v1-only hub, encrypted vault indexes and blob transfer. These fixtures use temporary certificates/data and do not use the user's pairing. Actual old/new APK and desktop interoperability, Android SAF cost and manual playback/UI checks remain with the user. Do not downgrade the user's installed app merely to run a compatibility test; use an isolated old-build profile/device.

## Home pins, 2026-11-04

`POST /kainos-sync/v3/pins` exchanges a `HomePinsSyncDocument`: live portable pins in Home order (Kainos playlists, and Spotify playlists under a known `spotifyAccountId`), unpin tombstones, and the stamp of the last reorder. Each pin and tombstone carries a `(revision, deviceId)` stamp and the newest wins per kind and target. The order of shared pins follows the newer reorder stamp; adding or removing a pin never bumps it, so a pin added on one device does not undo a reorder made on the other. Album and folder pins never travel and keep their slots. A receiver applies Spotify pins only when both documents carry the same account, and the hub strips them from replies to other accounts. A Kainos pin whose playlist is missing on the receiver is skipped without a tombstone and adopted on a later sync once the playlist exists. Hubs without the route answer 404 and the client skips pins; the v1 and v2 routes are unchanged. Schema 8 adds `home_pin_tombstone` and the order stamp columns on `library_meta`.

## Cached audio, 2026-11-04

`POST /kainos-sync/v3/cached-audio` exchanges a `CachedAudioSyncDocument`: this device's offers (hearted `audio-cache/` entries named by their immutable cache file, the `fileToken`), the owners it already holds, and its `spotifyAccountId`. The hub remembers the client's offers and replies with its own. The client plans a download for each hub offer it hearts and lacks, and an upload for each of its offers the hub lacks; a `spotify:` owner (a YouTube match) is offered only when both devices carry the same known account. Spotify audio is never part of the Downloads cache, so it is never offered. `GET …/cached-audio/blob?owner&token` serves 206 ranges of the file the hub's index names for that owner (never a path), `GET …/cached-audio/hash` its SHA-256, and `PUT …/cached-audio/blob?owner&token&offset&total&sha256` accepts chunks of an offered file only. Both directions stage under `audio-cache/incoming/` (outside the budget), check the destination budget against its pinned and now-playing downloads before a byte moves, publish only when the staged bytes match the SHA-256, and then enter the cache through the normal put, which evicts the oldest unpinned download if needed. Any failure deletes the staged file; Transfer again restarts. Hubs without the route answer 404 and the client skips cached audio.

## Loudness, 2026-11-11

`POST /kainos-sync/v3/loudness` exchanges the PC's ffmpeg EBU R128 measurements of local files (integrated LUFS, true peak, and the album aggregate) so the phone, which cannot measure, can level files that have no ReplayGain tags. Entries match by content key first and by unique basename only when no known key contradicts it. The exporter computes basename uniqueness over every subject, measured or not. Hubs without the route answer 404 and the client skips loudness.

## Hub process, 2026-11-11

A `--hub-only` process binds no media controls, restores no session and starts no downloads, and it exits 0 only when another process holds the hub's owner lock; a real failure to listen still exits 1. The systemd unit has `StartLimitIntervalSec=600` and `StartLimitBurst=5`. A GUI that stops the enabled unit refreshes a stale unit file before the unit restarts on close.

See [module ownership](../ARCHITECTURE.md) for the units, and the root `HANDOVER.md` for the current state and what remains to test.
