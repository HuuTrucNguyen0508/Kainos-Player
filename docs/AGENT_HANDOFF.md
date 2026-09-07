# Agent handoff

Updated 2026-09-07 after finishing Android Spotify Connect routing.

## Latest user request

Finish Android Spotify where no sound came out, and let the user choose a different Connect device when Spotify is not launched on the phone.

## What shipped in the working tree

- Android requires an explicit Connect device (`requiresExplicitSpotifyDevice()`).
- Settings → Spotify output: refresh devices, open Spotify app, pick any online device (phone / computer / speaker).
- Now Playing shows the same device picker while a Spotify track is active.
- Playback confirms `/me/player` device + track + `is_playing`, and rejects Spotify volume 0.
- If the saved phone device is offline but other devices are online, play fails fast and lists alternatives (no 12s wait, no silent transfer to the active kitchen speaker).
- If nothing is online, Kainos launches the Spotify app and waits briefly for that saved device.

## Validation

- `logs/android-spotify-route-tests.log`: `SpotifyProviderTest` PASS
- Compile: shared JVM + androidApp debug Kotlin PASS

## Remaining gaps

- Physical-phone Premium audible confirmation (no adb phone in this session).
- Android Spotify remains Connect-only (no in-app PCM decoder).
- Remote Spotify state sync / queue end from Connect still limited.

## How to verify on a phone

1. Connect Spotify in Settings.
2. For phone speakers: Open Spotify app → Refresh devices → select this phone → play. Confirm Spotify UI shows this device and you hear audio.
3. With Spotify closed on the phone: Refresh devices → select a computer/speaker that is online → play. Sound should come from that device, not silent phone speakers.
