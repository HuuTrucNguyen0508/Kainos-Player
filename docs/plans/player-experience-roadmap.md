# Player experience roadmap

## Goal

Make Kainos feel like one player across local files, Spotify, and YouTube. Prioritize returning to a listening session, make Now Playing feel intentional, and connect playlists, favorites, offline status, and Home without hiding which provider will supply a track.

This roadmap covers the proposed Now Playing redesign, playback session restore, Home pins, Kainos playlists, offline management, desktop layout, sleep timer, and lyrics.

## Product constraints

- Keep `PlayerSession` as the owner of queue, metadata, and transport behavior.
- Keep local audio locations on device. Never persist resolved streaming URLs.
- Do not cache protected Spotify audio. Offline audio remains limited to supported local files and the existing hearted YouTube cache and YouTube matches for Spotify favorites.
- Restore after process death in a paused state. Never start audio unexpectedly after an app or device restart.
- Preserve phone-initiated, foreground-only Home sync behavior. This roadmap does not add cloud storage or a phone background daemon.
- Shared playback and UI behavior belong in `shared`; platform storage, audio, and window behavior use existing Android and JVM actuals where needed.

## Delivery order

### Phase 0: Agree on the Now Playing direction

Create a compact phone and desktop screen prototype before implementation. Keep the current control set and audio details, but establish a clear hierarchy among artwork, track title, provider/quality, timeline, transport, and secondary actions. Test long titles, short screens, no artwork, buffering, loss of connection, and each color scheme.

**Acceptance:** phone controls fit on a typical screen without scrolling in the common state; smaller screens can scroll without clipping controls; desktop sizing uses available width; shuffle and repeat active states remain clear; provider and quality stay readable; artwork-derived colors respect the selected light/dark scheme.

### Phase 1: Persist and restore a listening session

Add a versioned session snapshot using the repository's existing JSON/file persistence patterns. Store queue entries and stable provider identities, current queue position, current track identity, playback position, repeat, shuffle, and shuffle order. Store local file identity/location only in the owning device's snapshot. Do not store signed or resolved stream URLs.

Restore the queue and metadata without starting audio. Re-resolve the current item when the user presses Play. If the item cannot be resolved or a local file is missing, preserve the queue and explain the state rather than discarding it. Define behavior for an expired provider login, removed playlist item, corrupt snapshot, and app update. Debounce writes and avoid writing on every position tick; persist position on a coarse interval and lifecycle/track transitions.

Keep notification tap behavior from the HyperOS work: it opens Now Playing for an active service. Session restoration is a separate cold-process path and must not be mistaken for Media3's in-memory session activity.

**Acceptance:** force-stop/relaunch restores the same queue order, current track, position within the chosen tolerance, repeat, and shuffle while paused; Play resolves the track and continues; missing local audio and unavailable providers show a useful status; a corrupt or old snapshot recovers safely; no stream URL or protected Spotify audio is written.

### Phase 2: Refine Now Playing

Implement the approved prototype in shared Compose. Use artwork as a restrained optional color source rather than replacing the user's selected theme. Keep the large play/pause and skip controls reachable, keep favorite, repeat, shuffle, queue, and volume easy to find, and retain the existing quality details behind a clear disclosure. Improve mini-player expansion and dismissal if they fit the same interaction model.

Avoid artwork or title movement when metadata refreshes. Keep playback transitions and the MediaSession metadata path stable so artwork remains consistent across Now Playing, notification, and island.

**Acceptance:** UI states from Phase 0 work on Android and desktop; transport remains bound to `PlayerSession`; screen reader labels express current action/state; the currently playing artwork agrees across the in-app screen and Android media controls; no clipping at compact phone dimensions or a narrow desktop window.

### Phase 3: Kainos playlists across providers

Add app-owned playlist models and JSON persistence. A playlist entry must reference a stable source identity and retain display metadata; it must not depend on a transient stream URL. Support create, rename, delete, reorder, add/remove tracks, and Save queue as playlist. Keep provider playlists distinct from Kainos playlists and label source availability clearly.

For matching identities, reuse app favorites and provider source metadata. Playlist playback resolves each entry through the existing provider routing rules. Define how duplicate tracks, removed local files, unavailable Spotify tracks, YouTube matches, and cross-device portable local identities behave. Extend Home sync only for portable playlist metadata after defining conflict and tombstone behavior; do not automatically copy audio beyond the existing vault confirmation flow.

**Acceptance:** playlists survive restart; mixed-provider queues preserve order and queue controls; removing a source item does not erase the playlist entry silently; missing items can be skipped or retried with an explanation; save-current-queue produces the same order and provider identities; optional sync merges edits predictably on two devices.

### Phase 4: Offline status and download management

**Status: implemented (2026-09-29).** Availability is separate from favorite state. Settings → Downloads manages progress/retry/remove/pin/storage; Now Playing exposes actual playback source and Spotify→YouTube match correction.

Represent availability separately from favorite state: available locally, cached, downloading, waiting for network, unavailable, and failed. Expose a per-track status where users choose music, plus a Downloads view for progress, retry, removal, and storage use. Keep the existing cache policy: hearted YouTube audio and supported YouTube matches can be cached; local files are playable from their selected folders; Spotify DRM audio remains streaming-only.

Expose the actual provider/source used for playback. Add a correction flow for a bad Spotify-to-YouTube match so users can select another candidate or clear it. Preserve the original Spotify identity and favorite even when the fallback changes. Make cache eviction visible and never evict pinned/actively playing items without clear policy.

**Acceptance:** availability is accurate after restart and loss of network; progress and failure actions work; a Spotify favorite clearly says when its playable offline audio is a YouTube match; users can change that match; local file deletion is reported; clearing cache updates status; protected Spotify audio is never presented as downloaded.

### Phase 5: Personalize Home

**Status: implemented (2026-09-29).** Pins persist in `user-library.json` (`homePins`). Home-sync: Kainos + Spotify playlist pins eligible; albums/folders device-local. Wire pins endpoint deferred.

Add explicit pins for Kainos playlists, provider playlists where supported, albums, and local folders. Keep Continue Listening as a separate recent-history area. Allow reorder/remove pins and provide sensible empty/loading/error states. Put high-use actions such as Play favorites and Resume near the top without turning Home into another dense library list.

Use the new Kainos playlist model from Phase 3. Store pin order in the existing settings/library persistence, and define which pin types can sync through Home sync.

**Acceptance:** pin order survives restart; removing a pin does not delete its playlist/album; Home opens and plays each supported item; empty, disconnected, and rate-limited provider states remain understandable; Home remains useful when no provider is connected.

### Phase 6: Adapt desktop layout

**Status: implemented (2026-09-29).** Collapsible/resizable Now Playing pane; Settings → Appearance compact desktop player; Queue beside content on wide (≥1200dp); narrow (<840dp) single pane + mini bar.

Make the Now Playing pane collapsible and resizable, with a compact player option and a queue view that can sit beside Library on wide windows. Preserve keyboard shortcuts, focus behavior, and scroll positions. At small desktop window widths, fall back to a single content pane without obscuring playback.

**Acceptance:** verify minimum supported window width, common laptop width, and wide desktop width; all controls remain available; resizing does not restart playback, reset library scroll, or lose queue edits; keyboard navigation remains usable.

### Phase 7: Sleep timer

Add a sleep timer with a duration and an “end of current track” mode. Show remaining time in Now Playing and let users cancel or adjust it. The timer pauses through `PlayerSession` so notification and Bluetooth state agree. Use a monotonic time source while the process is alive; define what happens across process death and device reboot. Initial behavior should cancel on process death unless reliable persistence can be added without surprising pauses after reboot.

**Acceptance:** timer pauses once at expiry; end-of-track mode waits for the current item and does not stop on a skip; cancel/reset work; queue continues normally before expiry; Android and desktop transport state reflects the pause.

### Phase 8: Lyrics investigation and display

First investigate lyric source availability, attribution, caching rights, API stability, and offline terms. Do not choose or ship a source before that check. If an acceptable source exists, add a provider interface that resolves lyrics by track identity and displays timed or plain lyrics in Now Playing. Keep fetching separate from playback resolution and handle no-match, instrumental, rate limit, and unavailable-network cases.

**Status (2026-09-29):** Investigation complete in `docs/plans/lyrics-source-investigation.md`. No acceptable online source with clear publisher/cache terms; **no lyrics provider shipped**. Local sidecar/embedded remains a possible later narrow enhancement only.

**Acceptance:** lyrics identify their source where required; timing follows playback and seek; fetching does not delay audio; unavailable results do not show guessed lyrics; cache behavior follows the chosen source's terms; Android and desktop show the same result. (N/A while no source is integrated.)

## Cross-cutting verification

- Keep shared queue/playback rules covered by focused common or JVM tests; add persistence round-trip and migration tests for the new snapshot and playlist formats.
- Build Android and desktop after changes to shared contracts.
- On Android, verify notification/island artwork and controls still agree with Now Playing after restore, provider change, rapid skip, and missing artwork.
- On desktop, keep playback headless and preserve run logs during manual testing.
- Use the Poco F7 Ultra for Android playback checks. Test local files first, then Spotify and YouTube when their live provider sessions are available.
- Do not publish a release as part of this roadmap unless requested separately.

## Main risks and decisions

- Session restore and queue persistence must follow current `QueueController` semantics, including queue-entry identity and shuffle order.
- Local paths and SAF URIs are device-specific. Cross-device playlists need portable identities and must remain useful when a file has not arrived yet.
- Offline availability differs by provider. Keep UI labels honest and never imply Spotify DRM downloads are supported.
- Cross-provider playlist sync needs explicit merge/delete rules before it is added to Home sync.
- Lyrics may be blocked by source terms or unstable APIs. The investigation can conclude with no lyrics integration.
- The roadmap is intentionally staged: session persistence establishes reliable state before Home pins and richer playlists depend on it; playlists establish the reusable collection model before Home and offline management build on it.

## Out of scope

- Cloud playback-state or music-library service.
- Protected Spotify offline audio.
- Automatic playback on relaunch.
- Full redesign of Library, Search, or Settings.
- HyperOS chrome restyling beyond supported MediaSession metadata, artwork, and commands.
