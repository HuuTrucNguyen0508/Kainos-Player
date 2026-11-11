# Kainos Player current state

What the app does today, what it does not do, and how each claim was checked. Dated history lives in git.

## What works

**First run.** Home asks where the listener's music lives and shows each source's real state, with one action that goes to the fix. Local files lead because they need no account. The screen leaves once music exists. Nothing blocks the rest of the app. There is no sample content.

**Local files.** Choose folders on either platform. Linux scans `~/Music` until folders are chosen, and an explicitly empty list stays empty. Android uses persisted SAF folder access, with MediaStore as an optional extra source that the first chosen folder turns off. Both platforms load a saved scan at once and rescan in the background. Linux plays through headless mpv, which must be installed. Android plays through Media3 in a foreground service.

**Spotify.** Search, liked songs and playlists use the Web API with the listener's own Client ID. Playback uses librespot as a Connect receiver: a helper process on Linux, in-process on Android. It needs Spotify Premium and a separate one-time sign-in. Spotify Loud normalization runs in the receiver at +3 dB.

**YouTube.** Search uses the listener's own YouTube Data API key. Audio comes from yt-dlp and mpv on Linux, and NewPipe Extractor and ExoPlayer on Android. Hearted tracks can cache audio for offline play.

**Search.** Spotify and YouTube only. Library has its own fuzzy search for everything else. Starting a Search result plays that track and then its own source's radio: Spotify radio through librespot, or the video's YouTube Mix. Radio never mixes sources and never falls back to a name search or a Liked Songs shuffle. Settings → Playback can turn it off. On Linux, Spotify radio needs the optional `kainos-discover-weekly` helper. Spotify and YouTube playlists in the results open as an in-app page: their songs load 50 at a time as the list scrolls, Play and Shuffle load the rest and queue the whole playlist, each row has the song menu, and the page's ⋮ menu still opens the playlist on Spotify or YouTube. Back or Escape returns to the results where they were. Spotify playlists the Web API refuses load through librespot instead.

**Library, queue and playlists.** Songs, albums, artists and playlists, with Local files only and Favorites only filters. App hearts stay separate from Spotify Liked Songs. Every song row in Library, Search and Home's Continue listening and history shelves has a menu (the ⋮ button, a long press or a right click) with Play next, Add to queue, Heart and Add to playlist; Search rows that cannot play in the app skip the queue actions. Hearts can also be set from Now Playing. Queue supports shuffle, repeat, reordering, removal with undo, and saving as a Kainos playlist.

**Listening history.** Each device keeps its own play history in `kainos.db`: one row per listen with the track, start time, time heard and whether it finished. History is never synced and never leaves the device, and events older than a year are pruned. Home shows up to three shelves from it, each hidden when empty: On repeat (at least two finished or 30-second plays in the last 30 days), Rediscover (hearted songs last played over 60 days ago) and Recently added (local files by modification time). Tapping a row plays that shelf from there. A listen still in progress when the app quits is not recorded.

**Now Playing.** Seek, volume, heart, sleep timer, source and quality details, retry and another-source fallback, and changing a Spotify track's YouTube cache match.

**Gapless.** Local files play gaplessly on both platforms, including across a seek. On Android the next local file waits in ExoPlayer behind the playing one, and the session swaps it when a skip, Play next, shuffle or repeat change picks another. Spotify and YouTube tracks have a short gap.

**Offline.** Availability is separate from hearts: local, cached, downloading, waiting, unavailable or failed. Settings → Downloads shows progress, retry, removal, pinning and storage use, against a 2 GiB budget. Spotify audio is never downloaded. A Spotify heart can cache a matched YouTube recording, labeled as a match.

**Session.** The queue, shuffle, repeat and position restore paused after a restart. Stream URLs are never stored.

**System controls.** MPRIS and media keys on Linux. Notification, lock screen and Bluetooth transport on Android, where tapping the notification opens Now Playing.

**Home sync.** The phone finds the PC on the local network. Both screens show a code to compare and confirm, and pairing pins the hub's certificate over HTTPS on port `43822`. YouTube and local-file hearts merge both ways. Spotify app hearts merge too, but only between devices signed into the same Spotify account, and never touch Spotify Liked Songs. Kainos playlists sync, and vault audio moves only after confirmation. Downloaded offline copies of hearted YouTube tracks are offered the same way when one device has the file and the other lacks it; a Spotify heart's YouTube match crosses only between devices on the same Spotify account. A copy is checked by size and SHA-256 before it enters the receiver's cache, respects that cache's budget and pins, and plays offline there as Cached. An interrupted copy leaves nothing behind and starts over on the next Transfer. Home pins for Kainos playlists and Spotify playlists sync with their order and unpins: the newest pin or unpin of a playlist wins, the most recent reorder decides the order, and Spotify pins cross only between devices on the same Spotify account. A pin for a Kainos playlist the other device does not have yet appears there once that playlist arrives. Album and folder pins stay on their device. A hub from before pin sync leaves pins alone. Paired devices can see and control each other from Now Playing, and Play here moves the other device's queue to this one. Sync runs while the phone app is open. The PC hub can start at login.

**Backup.** Settings → Backup exports one JSON file (`kainos-backup` version 1) through the platform save picker: hearts, Kainos playlists, Home pins for playlists, and appearance, playback and Library preferences. It never holds sign-ins, keys, pairing, stream URLs, folder access or paths; local songs travel as content keys and file names. Restoring shows what the file would add, then adds it and removes nothing: missing hearts come back, the newer copy of a playlist wins, a playlist deleted here comes back, and Spotify hearts and pins apply only on the same Spotify account. Restoring the same file again changes nothing. Audio files are not included.

**Appearance.** Folio and Caelestia skins, seven tints, and light, dark or system mode. Caelestia follows the shell's live colours on Linux. Desktop has a collapsible, resizable Now Playing pane that waits for the first track, a compact player, and Queue beside content on wide windows.

**Echo.** `tools/echo-gateway` is a separate service that plays shuffled local folders on an Echo. It has its own queue and does not touch the player.

## What does not work

- Volume levelling (Settings → Playback, on by default) uses a local file's ReplayGain tags, or else its measured EBU R128 loudness. The desktop measures new and changed library files with ffmpeg after each scan (about 0.4 s per track at nice 19; roughly 4 minutes for the 561-file library the first time) and hearted YouTube downloads when they finish. Phones cannot measure: they use the PC's values received over Home sync (`/kainos-sync/v3/loudness`, matched by content key, then unique basename), so a phone file the PC never measured stays unlevelled. The target is Spotify Loud (-11 LUFS); a boost stops 1 dB under the measured true peak, and a file already peaking above that plays at unity rather than being cut. Inside an album played in order, a gapless join uses the album gain. Both platforms change the gain on the first sample of the next file. Linux skips Opus and Matroska tags. YouTube streams (not cached) play at their own level. Spotify's normalization is built into the receiver and has no switch. Android levelling of analysed files is not yet verified on the phone.
- No gapless for Spotify or YouTube. No crossfade, EQ or DSP.
- No lyrics, until a source with clear terms exists. See [the investigation](plans/lyrics-source-investigation.md).
- No YouTube Music account library.
- Cached audio copies only while Sync now's Transfer runs in the open app. An interrupted copy restarts from the beginning.
- No sync while the phone app is closed. Pairing can start from the QR code the PC shows; the phone still compares the 6-digit code.
- Spotify Discover Weekly cannot be listed through the Web API for most developer apps. Paste its share link in Settings → Music sources.
- Spotify playback rests on unofficial librespot, so a protocol change can break it. Web API quotas can block search and library loading even while audio works.

More detail is in [provider limitations](LIMITATIONS.md).

## How this was verified

**Automated, on every change.** `./gradlew jvmTest` runs the unit tests of every module and the Compose UI tests in `shared/src/jvmTest`, which drive real screens with clicks and mouse-wheel input without a display. CI also runs `:core:data:verifyCommonMainKainosDatabaseMigration`, `:desktopApp:createDistributable`, `:androidApp:assembleDebug`, `:androidApp:assembleOptimized`, `:androidApp:lintDebug` and the gapless stress test against real mpv. Other tests that need real tools or a harness report as skipped, not passed. There is no emulator job.

**Automated, with a device attached.** `./gradlew :androidApp:connectedDebugAndroidTest` covers local scanning and playback, controls, completion, missing-file recovery, background service behaviour and simulated Spotify-to-local transitions.

**Release gate.** `scripts/android-release-gate.py --serial <emulator>` builds the optimized APK, verifies its signature and contents, smoke-tests that exact APK, drives it black-box through the phone paths (media keys as a headset or lock screen sends them, the media notification, ten rapid skips, 50 s of playback with the screen off across a natural track end, a background foreground-service start with every media exemption stripped, the notification opening Now Playing, restart restoring paused, and the track menu's Play next), runs the instrumented classes and the first-run journey against an R8 build, and prints the manual checks it cannot do as an unticked list. `--phone-paths <apk>` runs only the phone paths, in about two minutes. Releases still publish the debug APK: the optimized build becomes the default only after the owner has run that list on the phone. `androidApp/README.md` covers versions, rollback, signing and adopting a release key.

**By hand on real hardware.** On a Poco F7 Ultra running HyperOS: local playback, embedded artwork, notification actions, cold launch from the notification, and media island behaviour. On Linux: librespot sign-in, headless restart and audio start. Legacy JSON data imported into the database with matching counts.

**Not verified on real hardware.** Bluetooth transport, live Spotify and YouTube notification behaviour, lock screen controls, rapid skips on HyperOS, Spotify background survival on Android, the 2026-11-02 Spotify reconnect and foreground-denial fixes, the track menu, Ready offline, sleep fade, listening history and volume levelling on Android, and old-to-new phone and PC sync. The release gate proves the stock-Android half of the transport, lock screen, rapid skip and background paths on the emulator, and lists the rest as manual checks. On the emulator Android logs the stripped background start as denied by policy yet admits it, so the app's denial handler still has not run on a device.

Run the checks with the commands under **Development and diagnostics** in the [README](../README.md).
