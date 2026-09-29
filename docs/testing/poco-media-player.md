# Poco media controls and Now Playing validation

The Android Media3 session now supplies an immutable activity PendingIntent for
Kainos's launcher, with a dedicated SHOW_PLAYER action. Both initial launches and
onNewIntent deliveries request Now Playing. This supplies a launch target for
Android notification and OEM media surfaces; it cannot change a notification
owned by the Spotify app or guarantee how a particular HyperOS build renders its island.

## Build and automated checks

```sh
./gradlew :shared:jvmTest :desktopApp:compileKotlinJvm :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug
```

CI compiles the instrumented tests but does not run them on a device. On a connected
test device, run the two focused platform-session tests:

```sh
./gradlew :androidApp:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.universalmusic.player.AndroidPlaybackTest#playbackSurvivesBackgroundAndRespondsToSystemPause,com.universalmusic.player.AndroidPlaybackTest#switchingToSpotifyKeepsMediaSessionForNotification'
```

These tests check the platform session's PendingIntent owner and activity type,
send that intent, and check that MainActivity receives the Now Playing action.
The Spotify test uses a fake transport, so real Premium playback still needs a phone check.

## Physical Poco F7 Ultra checks

Install the rebuilt APK; merging source does not update the installed app.
Record the HyperOS version and APK commit when reporting results.

1. Play a local file with Spotify stopped. Background Kainos, tap its notification,
   and confirm that Now Playing opens without restarting the song. Close Now Playing
   and repeat the tap to verify repeat deliveries.
2. Repeat with YouTube and Spotify in-app playback. Check which app owns each media
   card if Spotify is installed. Kainos cannot redirect taps on Spotify's own card.
3. Test island tap/expand, then play/pause, next, previous and seek where the system
   offers those controls. Confirm artwork, title and progress match Kainos.
4. Repeat while the screen is locked and after dismissing Kainos from recents while
   playback continues. Reopening should bring back the existing playback session.
5. Rotate with Now Playing open, then close it and rotate again. The screen must not
   reopen solely because an earlier notification intent was retained.
6. Inspect the cream/olive layout with long titles, missing artists, small screens,
   and large font settings. Artwork must stay square without overlapping its label;
   all controls remain reachable by scrolling.

If the island still ignores taps, capture these while audio is playing and share
Settings > Advanced > Playback log immediately after trying the controls:

```sh
adb shell dumpsys media_session
adb shell dumpsys activity activities
```

Check for a Kainos session and its activity PendingIntent. The trace tags Session3
and Forwarding show whether a system controller delivered transport requests. Avoid
assuming a Spotify-owned card is a broken Kainos notification.

## References

- https://developer.android.com/reference/androidx/media3/session/MediaSession.Builder#setSessionActivity(android.app.PendingIntent)
- https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1602
