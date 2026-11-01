# Kainos Player current state

What the app does today, what it does not do, and how each claim was checked. Dated history lives in git.

## What works

**First run.** Home asks where the listener's music lives and shows each source's real state, with one action that goes to the fix. Local files lead because they need no account. The screen leaves once music exists. Nothing blocks the rest of the app. There is no sample content.

**Local files.** Choose folders on either platform. Linux scans `~/Music` until folders are chosen, and an explicitly empty list stays empty. Android uses persisted SAF folder access, with MediaStore as an optional extra source that the first chosen folder turns off. Both platforms load a saved scan at once and rescan in the background. Linux plays through headless mpv, which must be installed. Android plays through Media3 in a foreground service.

**Spotify.** Search, liked songs and playlists use the Web API with the listener's own Client ID. Playback uses librespot as a Connect receiver: a helper process on Linux, in-process on Android. It needs Spotify Premium and a separate one-time sign-in. Spotify Loud normalization runs in the receiver at +3 dB.

**YouTube.** Search uses the listener's own YouTube Data API key. Audio comes from yt-dlp and mpv on Linux, and NewPipe Extractor and ExoPlayer on Android. Hearted tracks can cache audio for offline play.

**Search.** Spotify and YouTube only. Library has its own fuzzy search for everything else. Starting a Search result plays that track and then its own source's radio: Spotify radio through librespot, or the video's YouTube Mix. Radio never mixes sources and never falls back to a name search or a Liked Songs shuffle. Settings → Playback can turn it off. On Linux, Spotify radio needs the optional `kainos-discover-weekly` helper.

**Library, queue and playlists.** Songs, albums, artists and playlists, with Local files only and Favorites only filters. App hearts stay separate from Spotify Liked Songs. Hearts are set from Now Playing. Queue supports shuffle, repeat, reordering, removal with undo, and saving as a Kainos playlist.

**Now Playing.** Seek, volume, heart, sleep timer, source and quality details, retry and another-source fallback, and changing a Spotify track's YouTube cache match.

**Gapless.** Local files play gaplessly on Linux, including across a seek. Spotify and YouTube tracks have a short gap on Linux. Android has a short gap between all tracks.

**Offline.** Availability is separate from hearts: local, cached, downloading, waiting, unavailable or failed. Settings → Downloads shows progress, retry, removal, pinning and storage use, against a 2 GiB budget. Spotify audio is never downloaded. A Spotify heart can cache a matched YouTube recording, labeled as a match.

**Session.** The queue, shuffle, repeat and position restore paused after a restart. Stream URLs are never stored.

**System controls.** MPRIS and media keys on Linux. Notification, lock screen and Bluetooth transport on Android, where tapping the notification opens Now Playing.

**Home sync.** The phone finds the PC on the local network. Both screens show a code to compare and confirm, and pairing pins the hub's certificate over HTTPS on port `43822`. YouTube and local-file hearts merge both ways, Kainos playlists sync, and vault audio moves only after confirmation. Paired devices can see and control each other from Now Playing, and Play here moves the other device's queue to this one. Sync runs while the phone app is open. The PC hub can start at login.

**Appearance.** Folio and Caelestia skins, seven tints, and light, dark or system mode. Caelestia follows the shell's live colours on Linux. Desktop has a collapsible, resizable Now Playing pane that waits for the first track, a compact player, and Queue beside content on wide windows.

**Echo.** `tools/echo-gateway` is a separate service that plays shuffled local folders on an Echo. It has its own queue and does not touch the player.

## What does not work

- No volume normalization for local files or YouTube. Spotify's is built into the receiver and has no switch.
- No gapless on Android, and none for Spotify or YouTube on Linux. No crossfade, EQ or DSP.
- No lyrics, until a source with clear terms exists. See [the investigation](plans/lyrics-source-investigation.md).
- No Play next or Add to queue in the Search or Library UI, though the session supports them.
- No hearting from a Library or Search row.
- No YouTube Music account library.
- No sync of Spotify app hearts, YouTube cached audio or Home pins between devices.
- No QR pairing, and no sync while the phone app is closed.
- Spotify Discover Weekly cannot be listed through the Web API for most developer apps. Paste its share link in Settings → Music sources.
- Spotify playback rests on unofficial librespot, so a protocol change can break it. Web API quotas can block search and library loading even while audio works.

More detail is in [provider limitations](LIMITATIONS.md).

## How this was verified

**Automated, on every change.** `./gradlew jvmTest` runs the unit tests of every module and the Compose UI tests in `shared/src/jvmTest`, which drive real screens with clicks and mouse-wheel input without a display. CI also runs `:core:data:verifyCommonMainKainosDatabaseMigration`, `:desktopApp:createDistributable`, `:androidApp:assembleDebug`, `:androidApp:assembleOptimized`, `:androidApp:lintDebug` and the gapless stress test against real mpv. Other tests that need real tools or a harness report as skipped, not passed. There is no emulator job.

**Automated, with a device attached.** `./gradlew :androidApp:connectedDebugAndroidTest` covers local scanning and playback, controls, completion, missing-file recovery, background service behaviour and simulated Spotify-to-local transitions.

**Release gate.** `scripts/android-release-gate.py --serial <emulator>` builds the optimized APK, verifies its signature and contents, smoke-tests that exact APK, runs the instrumented classes and the first-run journey against an R8 build, and lists the manual checks it cannot do. Releases still publish the debug APK: the optimized build becomes the default only after the owner has run that list on the phone. `androidApp/README.md` covers versions, rollback, signing and adopting a release key.

**By hand on real hardware.** On a Poco F7 Ultra running HyperOS: local playback, embedded artwork, notification actions, cold launch from the notification, and media island behaviour. On Linux: librespot sign-in, headless restart and audio start. Legacy JSON data imported into the database with matching counts.

**Not verified on real hardware.** Bluetooth transport, live Spotify and YouTube notification behaviour, lock screen controls, rapid skips on HyperOS, Spotify background survival on Android, and old-to-new phone and PC sync.

Run the checks with the commands under **Development and diagnostics** in the [README](../README.md).
