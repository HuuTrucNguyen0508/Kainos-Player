# Home sync remote control

Status: in progress, branch `feat/home-sync-remote-control`, based on `feat/redesign-folio-caelestia` at `53eff8f`.

## Goal

The user asked, in their words: "Since we can now resume the track through the 2 devices, how about controlling too? Play, pause and all", and "even if the app is open, if I lock my phone, the pc client won't get the updated track. I am thinking of something like the pc client after opening up, will ask the app on the phone even though it is in the background".

1. The PC shows what the phone is playing, live, while the phone is locked and Kainos plays in the background. When the PC app opens, it learns the phone's state within seconds without anyone touching the phone.
2. Each device can control the other over the existing Home sync pairing: play/pause, next/previous, seek, volume, shuffle, repeat, view the queue, play a queue entry, and move a queue entry to play next.
3. Now Playing has a device picker ("This PC" / "This phone" and the paired device). Controlling another device keeps the sound there. "Play here" explicitly moves playback to this device.
4. When the other device is unreachable, the UI says so at once and controls are disabled. A command that cannot be delivered within a few seconds is discarded, so an old Play never fires later.

## Constraints that shape the design

- The PC is the hub (HTTPS server on port 43822, Netty, mutual TLS with pinned certificates). The phone is a client only. A permanent server on the phone was rejected (`docs/plans/home-sync-identity-and-handoff.md`, Appendix B): Android freezes background apps.
- So the PC cannot open a connection to the phone. The phone keeps a **long-poll link** open to the hub while it is worth reaching: the app is visible, or it is playing, or it paused less than 10 minutes ago (Media3 keeps the playback service foreground for a while after pause). A foreground media service is exempt from Doze network restrictions, so a locked, playing phone keeps the link.
- While the PC GUI runs, it owns the hub (`HubOwnership`, GUI stops the systemd `--hub-only` service). The GUI process therefore applies phone commands to its own `PlayerSession` directly. A `--hub-only` process has no player: it advertises no hub status and answers commands with 409.
- An idle, frozen phone cannot be woken without a cloud push, which the user ruled out. The PC shows it as unreachable.

## Protocol (v3, pinned mutual TLS, paired callers only)

All bodies are JSON. DTOs live in `core:model` (package `com.universalmusic.player.data.sync`, file `RemoteControlModels.kt`).

```kotlin
@Serializable data class RemoteStatus(
    val title: String?, val artist: String?, val album: String?,
    val artworkUrl: String?,          // http(s) only; never file:/content:
    val artworkKey: String?,          // set when local artwork bytes are available via the artwork route
    val durationMs: Long?, val positionMs: Long, val positionAgeMs: Long,
    val isPlaying: Boolean, val buffering: Boolean,
    val shuffle: Boolean, val repeat: RemoteRepeat, val volume: Float,
    val currentItemId: String?,
    val queue: List<RemoteQueueEntry>,  // playback order starting at the current entry, capped at 200
    val canSkipPrevious: Boolean, val canSkipNext: Boolean,
)
@Serializable data class RemoteQueueEntry(val itemId: String, val title: String, val artist: String?, val durationMs: Long?)
@Serializable enum class RemoteRepeat { OFF, ALL, ONE }
@Serializable sealed interface RemoteCommand {  // class discriminator "type"
    TogglePlay, Play, Pause, Next, Previous, SeekTo(positionMs), SetVolume(volume 0..1),
    SetShuffle(enabled), SetRepeat(mode), PlayItem(itemId), PlayNext(itemId)
}
```

Positions are sent with an age measured on the sender, never an absolute timestamp (the clocks differ), as `ListeningReport` already does.

### Long poll: `POST /kainos-sync/v3/remote/poll`

Request `RemotePollRequest(deviceId, status: RemoteStatus?, ackSeq: Long, knownHubVersion: Long)`.
Reply `RemotePollReply(commands: List<RemoteEnvelope(seq, command)>, hubStatus: RemoteStatus?, hubVersion: Long, hubName: String)`.

Hub (`RemoteLinkHost`, `sync` commonMain, pure and clock-injected):
- Rejects a `deviceId` that is not the caller (403), like `ListeningExchangeHost.report`.
- Stores the caller's status with the receive time and drops outbox commands with `seq <= ackSeq`.
- Replies at once if the outbox has unexpired commands or `knownHubVersion != hubVersion`. Otherwise it holds the request up to 20 s, and replies early when a command is queued or the hub status changes. One held poll per device; a newer poll completes the older one with an empty reply.
- `hubVersion` increases on every hub status change that is not a mere position tick (track, play state, shuffle, repeat, volume, queue, or a seek: a position jump of more than 2 s from extrapolation).
- The peer is **connected** while a poll is held, or for 3 s after a reply was sent. Otherwise it is unreachable.
- `send(deviceId, command)`: refuses at once when the peer is unreachable. Otherwise it assigns the next seq and wakes the held poll. A command not acknowledged within 4 s of hub time expires: it is removed and never delivered later, and the sender's UI reports "<device> didn't respond".

Phone (`RemoteLinkClient`, `sync` commonMain): a loop in the app scope.
- Runs while Home sync is enabled, this device is a paired client, and the link is wanted (visible, playing, or paused < 10 min). It stops otherwise and the hub sees it go away.
- Each round posts the current status and `ackSeq`, executes the reply's commands in seq order exactly once (skips `seq <= lastExecuted`), stores `hubStatus` and `hubVersion`, and polls again at once.
- A local status change that is not a position tick cancels the held poll and posts a new one within 250 ms. The hub has not delivered anything unacknowledged, so it is safe.
- Failure: mark the PC unreachable at once, back off 2, 5, 10, 20, then 30 s. A link that (re)connects asks for a listening delivery (`HomeLanSyncService.requestListeningDelivery`), so the handoff store also catches up when the PC opens.
- Uses the existing endpoint resolution (`HubEndpointResolver`: stored address, then mDNS) and the pinned client. The HTTP read timeout must exceed the 20 s hold.

### Commands to the hub: `POST /kainos-sync/v3/remote/command`

Body `RemoteCommand`. The hub applies it to its `PlayerSession` and replies with the new `RemotePollReply` (no commands). It answers 409 when this hub process has no player (`--hub-only`). The phone never retries a failed command.

### Artwork: `GET` and `PUT /kainos-sync/v3/remote/artwork/{key}`

Remote art must look like local art. An http(s) URL travels in `artworkUrl`. Local artwork (file path, `content:` URI, `meta-cache/local-art/`) travels as bytes, 1 MiB at most. `artworkKey` is a hash of the track's canonical id and its art location. The hub serves its own current art on GET. The phone uploads its current art with PUT once per key change, and the hub keeps only the latest per device. Larger or unreadable art sends no key, and the UI shows the placeholder.

## Player adapter (`core:playback`)

`RemotePlayerAdapter(session: PlayerSession)`: maps `nowPlaying`, `queueState`, `volume` to a `StateFlow<RemoteStatus?>`, and applies a `RemoteCommand` through the existing public `PlayerSession` calls (`togglePlayPause`, `playTransport`, `pauseTransport`, `skipToNext`, `skipToPrevious`, `seekTo`, `setVolume`, `toggleShuffle`, `cycleRepeat`, `playQueueIndex`, `moveInPlaybackOrder`). It finds entries by item id, not index, so a stale index never plays the wrong track. An unknown item id is a no-op.

## Play here

The listening exchange already carries the full portable queue. "Play here" on device X, while controlling device Y:

1. If X is playing, X pauses locally.
2. X tells Y to pause. On the hub side, a pause through `/remote/command` responds only after Y's listening state is flushed (`flushPersistence`).
3. X refreshes Y's listening state: the phone runs a foreground listening exchange; the PC waits up to 5 s for the phone's delivery to store a peer revision whose current item matches the remote status.
4. X loads it with an explicit `ListeningHandoff` path that skips the recency and not-playing checks (the user asked for it) but keeps the revision and session guards, then plays.
5. If Y's queue cannot be projected (`LocalListeningExport.Unavailable`), X shows "This queue can't move here" and Y stays paused.

## UI

`/frontend-design` runs before any UI code. Now Playing gets a device chip ("Playing on This PC ▾"). The picker lists this device and the paired device with its connected state. While a remote device is selected, Now Playing shows that device's artwork, title and artist, progress with seek, previous / play-pause / next, shuffle, repeat, volume, its queue (tap to play, "Play next" action), and a prominent "Play here" button. Unreachable shows a banner and disabled controls. Desktop Space, MPRIS and the Android media session keep controlling local playback.

## Verification

- JVM tests: `RemoteLinkHost` (immediate reply, hold and wake, ack drop, 4 s expiry, refusal when unreachable, newer poll supersedes, forged deviceId), `RemoteLinkClient` with a fake transport (exactly-once execution, cancel-and-repost on status change, backoff, unreachable flag), `RemotePlayerAdapter` (each command, unknown item id), and a loopback pinned-TLS test against `DesktopHomeLanSyncHub` for poll, command and artwork routes.
- `./gradlew jvmTest :core:data:verifyCommonMainKainosDatabaseMigration :desktopApp:createDistributable :androidApp:assembleDebug :androidApp:lintDebug`.
- Live: two isolated desktop instances (hub GUI + client GUI using the HS0 isolation overrides) prove both directions, unreachable state and Play here on real UI. Phone: a locked, playing Poco shows live on the PC.
