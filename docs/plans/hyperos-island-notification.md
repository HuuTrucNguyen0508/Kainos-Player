# HyperOS media island and notification

## Goal

Make taps on the Android media notification and HyperOS media pill open the app's Now Playing screen. Improve the stock Media3 media controls with reliable artwork and optional favorite and repeat actions. Investigate HyperOS Focus Notifications only after the standard media path works.

HyperOS owns the layout, blur, progress treatment, and most action icons for a MediaStyle notification. The app supplies the MediaSession activity, media metadata, artwork, playback state, and custom commands. Xiaomi may hide custom actions that do not use its approved assets or whitelist.

References:

- [Xiaomi HyperOS media notification guidance](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2161)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [Media3 mobile media controls](https://developer.android.com/media/implement/surfaces/mobile)
- [HyperIsland ToolKit](https://hyperisland.d4viddf.com/docs/getting-started/)

## 1. Make notification and island taps open Now Playing

In `AndroidPlaybackService.onCreate`, create an immutable `PendingIntent` targeting `MainActivity` and pass it to `MediaSession.Builder.setSessionActivity`. Use the existing `singleTask` activity configuration. Put an explicit `OPEN_NOW_PLAYING` extra in the intent. Use a stable request code and `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` so the intent remains usable after the service and activity have different lifetimes.

In `MainActivity`, handle the intent in both `onCreate` and `onNewIntent`. Accept the explicit extra, and optionally a `universalmusic://now-playing` VIEW URI if that is useful for later integrations. Consume the marker after reading it so rotation or a repeated lifecycle callback cannot reopen the overlay.

The current `UiRequest` flow is a non-replaying `SharedFlow`. A request emitted before `UniversalMusicApp` starts collecting would be lost. Add a small pending navigation state in `AppContainer`, or another replay-safe one-shot mechanism, so the activity can enqueue `OPEN_NOW_PLAYING` before Compose is ready and the UI consumes it exactly once. Do not rely on `extraBufferCapacity` for delivery.

Add `UiRequest.OPEN_NOW_PLAYING`. In `App.kt`, handle it by closing the queue overlay and setting `showNowPlaying = true`. Queue must not continue to win the rendering branch when the user tapped the media notification.

## 2. Publish artwork that HyperOS can tint

Keep `artworkUri` for normal Media3 behavior. Also load bounded artwork bytes and publish `MediaMetadata.artworkData` when available. Reuse the existing artwork/cache path rather than introducing a second cache. Decode or scale on a background dispatcher to a reasonable maximum dimension, then apply the result on the main looper.

Every artwork request must carry both the artwork identity and the current media identity. Before applying an asynchronous result, discard it if either identity no longer matches. Clear old `artworkData` when a track has no artwork. This prevents a rapid skip from attaching the previous track's image to the new item and prevents unbounded bitmap memory use.

Keep the existing rule that position ticks update the overlay without replacing the MediaItem. Metadata replacement is allowed when the track identity is unchanged and only artwork becomes available.

## 3. Add favorite and repeat media commands

Use Media3 `CommandButton` and `SessionCommand` APIs. Define commands for:

- Toggle the current app favorite.
- Cycle repeat mode.

In the session callback, add these commands to the accepted session commands for the media notification controller and handle them in `onCustomCommand`. Route favorite changes through the existing library and `PlayerSession` state. Route repeat changes through `PlayerSession.cycleRepeat()`.

Expose the buttons in overflow slots after the standard play, previous, and next controls. Use the filled or unfilled heart icon that Media3 provides where possible. HyperOS may still hide these buttons until Xiaomi's approved media-key assets or partner enablement are available. A hidden button must not affect transport controls on AOSP, Bluetooth, or other OEMs.

Include favorite state and repeat mode in the metadata or command-state identity used to refresh the session. A title or artwork update alone must not be the only event that changes the displayed heart or repeat state.

## 4. Keep the Focus Notification experiment gated

Do not add a second standalone notification. First ship and test the normal Media3 notification.

If the standard path is correct but the Poco still needs investigation, add an opt-in experiment behind a build or developer setting. Check manufacturer, HyperOS support, and the required status-bar permission before attempting it. Prefer a small adapter around `MediaNotification.Provider` or the HyperIsland toolkit that preserves the existing MediaStyle notification, session token, content intent, and transport actions.

Treat the toolkit's `miui.focus.*` payload as undocumented and device-specific. Do not let it run by default. If it duplicates the media pill, breaks transport, or changes notification behavior on non-Xiaomi devices, remove the extras and retain the standard Media3 path. Record the device result in `docs/CURRENT_STATE.md`.

Do not promise a full Obsidian-style redesign. HyperOS controls that chrome.

## 5. Clarify process-death behavior

Test two cases separately:

1. The activity is gone but the playback service is still alive. The session `PendingIntent` should recreate the activity and open Now Playing for the current track.
2. The whole app process and service are gone. Opening Now Playing requires persisted playback state and Media3 playback resumption. It is not provided by `setSessionActivity` alone.

If full process-death resumption is required, add a separate persistence task. Store the current queue, track identity, position, repeat, and shuffle state, then implement `MediaSession.Callback.onPlaybackResumption` with locally available metadata and artwork. Keep that work separate from the tap fix.

## 6. Verification

Build and install a debug APK with wireless adb. On the Poco F7 Ultra, verify local, Spotify, and YouTube playback.

- Tapping the HyperOS pill opens Now Playing.
- Tapping the expanded media notification opens Now Playing.
- A cold activity start opens Now Playing when the service still has a current track.
- Rotation and repeated `onNewIntent` calls do not reopen the overlay unexpectedly.
- If Queue is open, the tap shows Now Playing instead of leaving Queue on top.
- Rapid skips never show stale title or artwork.
- Missing artwork clears the previous cover.
- Heart changes the app favorite and updates the in-app state.
- Repeat cycles through the same modes as the in-app control.
- Play, pause, previous, next, seek, Bluetooth controls, and Spotify transitions continue to work.
- The experimental Focus Notification path is disabled by default and produces no duplicate notification.

Capture a screenshot and playback trace for the standard pill. If the optional experiment is enabled, record whether it changes the pill, duplicates it, hides actions, or breaks transport, then update `docs/CURRENT_STATE.md`.

## Files likely to change

- `shared/src/androidMain/kotlin/com/universalmusic/player/platform/AndroidPlaybackService.kt`
- `shared/src/androidMain/kotlin/com/universalmusic/player/platform/AndroidMediaControls.kt`
- `shared/src/androidMain/kotlin/com/universalmusic/player/platform/KainosForwardingPlayer.kt`
- `androidApp/src/main/kotlin/com/universalmusic/player/MainActivity.kt`
- `shared/src/commonMain/kotlin/com/universalmusic/player/app/AppContainer.kt` and `shared/src/commonMain/kotlin/com/universalmusic/player/ui/App.kt` for the navigation request
- `docs/CURRENT_STATE.md` after device verification

Add a Gradle dependency only if the gated Focus Notification experiment survives the standard-path tests. Do not add the HyperIsland toolkit as part of the initial tap, artwork, or media-command implementation.

## Out of scope

- Reworking the in-app Now Playing design.
- Reproducing Xiaomi's system-owned pill chrome inside Compose.
- Xiaomi partner whitelist or official media-key asset submission.
- Full playback resumption after process death unless it is separately approved and planned.
