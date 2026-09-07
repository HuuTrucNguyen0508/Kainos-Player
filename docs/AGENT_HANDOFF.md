# Agent handoff

Updated 2026-09-07 after live YouTube smoke and APK packaging. Never include account tokens or credentials.

## Latest outcome

- Live Android YouTube smoke PASS on Pixel_10_Pro API 37: NewPipe resolved `jNQXAC9IVRw`, ExoPlayer reached PLAYING with advancing position, then pause. Evidence: `logs/android-youtube-live-smoke.log` and connected test XML (`failures="0"`).
- Debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- User asked to push the APK to GitHub after a good smoke. Emulator stopped after packaging.

## Remaining gaps

- Android Spotify still Connect-only (no librespot).
- Physical-device / Bluetooth not verified.
- Linux audible Spotify end-to-end not verified.
- NewPipe without poToken may miss some formats on some videos.
