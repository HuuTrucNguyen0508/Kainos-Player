# Plan: Home LAN library + hearts sync

Status: **Phase 0–3 + pinned TLS** — leave firewall 43822 blocked until soak on HTTPS; APK when asked  
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
| Phase 3 polish (autostart hub, AES-GCM payloads, pairing URI, idle timeout, rematch) | Done |
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
| Hub idle timeout (30 min) | Done |
| `--hub-only` + login autostart desktop file | Done |
| Pairing URI `kainos-homesync:2?…` with mandatory `cert=` pin | Done (URI text; no in-app QR) |
| PIN verified on hub requests | Done |
| Vault payload AES-GCM (`aes-gcm-v1`) under TLS | Done |
| Local-heart rematch by filename after vault sync | Done |
| **HTTPS + pinned hub leaf cert (SHA-256 DER)** | Done |
| Cleartext `kainos-homesync:1` / HTTP | Rejected |
| In-app QR render + camera scan | Deferred |
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
- Syncing hearted audio cache binaries (`audio-cache/`) or librespot DRM cache
- Syncing resolved streaming URLs
- Syncing local-file hearts by path on the wire (provider hearts only; local rematch is post-vault)
- Syncing `recents` or non-favorite `remembered` history
- Multi-device simultaneous editing / CRDTs

## Architecture

### Roles

| Role | Device | Behavior |
|---|---|---|
| Hub | Linux PC | In-process listener inside `:desktopApp` (or `--hub-only`). Port `43822` HTTPS. Idle stop after 30 min. |
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
