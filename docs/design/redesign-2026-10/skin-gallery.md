# Skin gallery

Run from the repository root with JDK 17:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
./gradlew :shared:skinGallery --console=plain
```

The task writes 49 PNGs under `shared/build/skin-gallery/`. It runs separately from `jvmTest`, which excludes `SkinGalleryTest`. The desktop native runtime is on the JVM test classpath only. No app state, network images or screens are loaded.

Each run clears old generated gallery files and writes `manifest.tsv`. The task disables the Gradle build cache because fonts come from the host.

The renderer uses `ImageComposeScene` at density 1 with frames at 0 and 1,000,000,000 nanoseconds. A test coroutine scheduler drains effects between repeated frames at each timestamp so animation startup cannot race the capture. The proof renders twice and compares the PNG bytes. Typography images must contain visible theme ink. The accent and Material sheets check visible swatch and fill pixels, and the two skin typography images must differ. System fonts can differ across machines, so pixel comparisons belong on the same host with the same fonts.

Each of these directories has `accents.png`, `typography.png`, `material.png`, `seek.png`, `marks.png` and `rows.png`:

- `folio-folio-light`
- `folio-folio-dark`
- `folio-catppuccin-dark`
- `folio-live-scheme-dark`
- `caelestia-folio-light`
- `caelestia-folio-dark`
- `caelestia-catppuccin-dark`
- `caelestia-live-scheme-dark`

`proof/swatch-row.png` is the initial render check. The accent sheet shows Decor, Mark and Text on ground, sheet and tile. The typography sheet covers all 15 M3 styles and a display-family time readout. The Material sheet covers buttons, switches, a slider, text fields and chips. The other sheets cover seek and volume tracks, semantic marks and badges, tiles, ledgers, track rows and the mini player.

The live-scheme case reads the committed `shared/src/jvmTest/resources/caelestia-scheme.json`, so it is repeatable. It applies that palette under both sets of skin tokens. It does not depend on the running shell.

Add a specimen to `galleryEntries` in `shared/src/jvmTest/kotlin/com/universalmusic/player/ui/theme/SkinGalleryTest.kt`:

```kotlin
GalleryEntry("seek-track") { SeekTrackSpecimens() },
```

Entries default to 1040 by 1000 pixels. Set `width` or `height` on an entry when needed. The composable runs under `KainosTheme` and a ground-coloured Material `Surface`. It can read `LocalKainosPalette` and `LocalSkinTokens` directly.

Verify the complete gallery twice without a display server:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
python3 scripts/verify-skin-gallery.py
```

The script compares the generated files with the manifest and every PNG's SHA256 across both runs. It writes logs and `logs/skin-gallery-verify.json`. The dedicated Gradle task sets `java.awt.headless=true`; the script also removes `DISPLAY` and `WAYLAND_DISPLAY`.

The gallery does not render `HomeContent` or `NowPlayingContent`. The current task explicitly excludes screen extraction; those screens remain in the later desktop verification pass. This host has Google Sans but lacks Google Sans Flex, so the desktop font fallback to Google Sans is the branch exercised here.

## Live desktop check

Run the app with isolated state and an overridden scheme file. Never rewrite the real shell file:

```bash
export JAVA_HOME="$HOME/.jdks/temurin-17"
KAINOS_DATA_DIR="$PWD/logs/redesign-smoke-data" \
KAINOS_INSTANCE=redesign KAINOS_HUB_PORT=43922 \
KAINOS_CAELESTIA_SCHEME="$PWD/logs/redesign-smoke-scheme.json" \
timeout 60 ./gradlew :desktopApp:run --console=plain > logs/redesign-desktop-recolour.log 2>&1
```

Prepare the isolated directory and scheme copy before launching. Its settings should select Caelestia, enable following, and set an explicitly empty local-folder list. Find the Java window PID with `hyprctl -j clients`, then capture it:

```bash
python3 scripts/capture-desktop-theme.py --pid <pid> \
  --scheme "$PWD/logs/redesign-smoke-scheme.json" \
  --output "$PWD/logs/redesign-desktop.png"
```

The capture helper targets this host's Lua Hyprland dispatcher. It needs `hyprctl`, `grim` and Python Pillow. It focuses the chosen Kainos window, captures only its bounds, restores focus, and writes JSON alongside the PNG. The assertions check actual background and surface-container pixels. Replace the overridden file atomically, then capture the same PID again to verify a live update.

Unit 1's checks are:

```bash
./gradlew :core:model:jvmTest :core:data:jvmTest :shared:jvmTest :shared:compileAndroidMain --console=plain
node scripts/generate-folio-tints.cjs
git diff --exit-code -- shared/src/commonMain/kotlin/com/universalmusic/player/ui/theme/FolioTints.kt
```
