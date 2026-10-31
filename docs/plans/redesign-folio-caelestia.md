# Folio and Caelestia redesign plan

Kainos Player gets two skins that share every screen, name and control. Folio is the Android default and matches the user's Folio launcher. Caelestia is the Linux desktop default and takes its colours live from the Caelestia shell. Both skins use many colour accents, drawn from a per-theme accent ramp, so colour carries meaning (source, state, section) instead of decoration.

Branch `feat/redesign-folio-caelestia` from `main` at `73a2178`. Worktree `.claude/worktrees/redesign`. Local commits only. No push, PR, release or phone install without the user's go.

Mockups (source of truth for look): `docs/design/redesign-2026-10/direction-5-folio.html` and `direction-6-caelestia.html`. The Folio tint table is copied verbatim from the launcher (`TINTS` in `direction-5-folio.html`). The Caelestia tokens (rounding, spacing, type scale, motion) are listed at the end of `direction-6-caelestia.html`.

## Data shape

Settings (`core/model/.../data/settings/AppSettings.kt`):

- `skin: SkinChoice = SkinChoice.PLATFORM_DEFAULT`, where `enum class SkinChoice { PLATFORM_DEFAULT, FOLIO, CAELESTIA }`. Platform default resolves to `FOLIO` on Android and `CAELESTIA` on Linux through the existing `platformLabel()` expect/actual, never through window width.
- `tint: FolioTint = FolioTint.FOLIO`, where `enum class FolioTint { FOLIO, EVERFOREST, CATPPUCCIN, GRUVBOX, NORD, ROSE_PINE, TOKYO_NIGHT }`. Used by Folio, and by Caelestia as the fallback when the shell scheme is missing or following is off.
- `followCaelestiaScheme: Boolean = true`. Only read on desktop.
- `themeMode` stays.
- Delete `colorScheme: AppColorScheme`, the `AppColorScheme` enum, `dynamicColor` (no consumer) and the ten frozen palettes in `ColorSchemes.kt`, in the same unit that migrates their callers (`App.kt:81`, `SettingsAppearanceSection.kt:64`, `ColorContrastTest.kt`).
- One settings decoder in `core:data`, used by both stores (desktop `PlatformCoredata.jvm.kt:181`, Android SharedPreferences `ump_settings` key `settings` at `PlatformCoredata.android.kt:135`). It parses to a `JsonObject` first, then: if `tint` is absent and a raw `colorScheme` string exists, map it once (CATPPUCCIN, NORD, GRUVBOX, ROSE_PINE, TOKYO_NIGHT, EVERFOREST by name, everything else to FOLIO); replace an unknown `skin` or `tint` string with its default; then decode the typed `AppSettings`. An unknown enum value never resets unrelated settings. Tests cover unknown legacy values, unknown new values, new-field precedence over legacy, and write/read stability.

Theme model (`shared/.../ui/theme/`):

```kotlin
enum class Skin { FOLIO, CAELESTIA }

data class AccentRamp(            // 8 hues plus grey, in this fixed order
    val red: Color, val orange: Color, val yellow: Color, val green: Color,
    val teal: Color, val blue: Color, val purple: Color, val pink: Color, val gray: Color,
) { fun forKey(key: String): Color }   // stable hash over the 8 chromatic hues

data class KainosPalette(
    val scheme: ColorScheme,      // full M3 roles, consumed through MaterialTheme
    val accents: AccentRamp,
    val ground: Color, val sheet: Color, val tile: Color, val hair: Color, val rule: Color, // Folio paper roles; Caelestia maps them to surface-container roles
    val isDark: Boolean,
)

data class SkinTokens(            // everything that differs per skin, besides colour
    val skin: Skin,
    val typography: Typography,
    val display: FontFamily,      // Folio serif, Caelestia sans
    val shapes: Shapes,
    val rounding: Rounding,       // named ladder: small, normal, large, full
    val seek: SeekStyle,          // RULED or WAVY
    val ground: GroundStyle,      // DOTTED_PAPER or PLAIN
)
```

`KainosTheme` installs `colorScheme`, `typography` and `shapes` into `MaterialTheme`, so stock M3 components (dialogs, text fields, menus, switches, snackbars, tooltips, sliders) take the skin without per-call overrides. `LocalKainosPalette` and `LocalSkinTokens` carry what M3 has no slot for (accent ramp, paper roles, seek style, ground, motion). Skin identity stays inside `ui/theme/` and the few primitives in `ui/components/` that draw differently; screens never branch on skin. Ordinary composables reading immutable tokens are enough; there is no runtime registry.

`Rounding` carries Caelestia's full ladder from the mockup (extraSmall 4, small 8, normal 12, large 16, extraLarge 20, 28, 32, 48, full) and Folio's equivalents. `Motion` carries Caelestia's curves and durations (standard, expressive spatial, fast spatial, effects) and Folio's quicker, flatter ones. The hard-coded shapes at `Components.kt:54,55,311`, `HomePinsSection.kt:113`, `HomeRecents.kt:79,91`, `HomeSessionCard.kt:67`, `NowPlayingArtwork.kt:25` and `SettingsAppearanceSection.kt:92` move onto these tokens.

Semantic accent map, defined once in `ui/theme/Accents.kt` and used everywhere:

- Sources. Spotify uses `green`, YouTube uses `red`, local files use `teal`, Kainos playlists use `purple`. Folder pins use `orange`.
- Availability. Local is `green`, cached is `teal`, downloading is `blue`, waiting is `yellow`, unavailable is `gray`, failed is `red`.
- Tabs. Home is `orange`, Search is `blue`, Library is `teal`, Settings is `purple`. Each tab's mark and active indicator use its hue.
- Now Playing. Heart is `pink`, shuffle on is `purple`, repeat on is `orange`, sleep timer is `yellow`, quality badge is `teal` for CD quality and `yellow` for hi-res, `blue` for lossy.
- Artwork placeholders draw from `forKey(id)` instead of the fixed olive generator in `Components.kt`.

Accent names are slots, not promises. Caelestia harmonises its named colours to the wallpaper (today its `green` is pale blue and its `red` is pink-purple), so meaning never rests on colour alone. Sources keep their icons, availability keeps its labels, active states keep a fill or indicator shape, and errors use the M3 `error` roles.

Accents have three uses, resolved by `accent(slot, use, on = background)`:

- `Decor`. The raw hue, always beside an icon or a label that carries the meaning (the tile underline mark, the tab mark, placeholder art). This keeps the launcher's full colour.
- `Mark`. A meaningful non-text mark (availability dot, active shuffle fill, playhead). It reaches 3:1 against the background it sits on.
- `Text`. Accent-coloured text (provider labels, quality badge text, chip labels). It reaches 4.5:1.

The resolver shifts lightness toward the ink colour until the target is met. Raw Folio yellow on light paper is about 1.48:1, so as a `Mark` or `Text` it darkens to amber. `AccentContrastTest` checks every tint, mode, slot and use against the real backgrounds (ground, sheet, tile, chip and button containers, surface containers, with alpha composited), keeps the existing `onPrimary`/`primary` check, and checks the live-scheme fixture.

## Caelestia live scheme

- Pure parser in `shared/src/commonMain/.../ui/theme/CaelestiaScheme.kt`. Input is the file's text. The file has a root `mode` (`dark` or `light`) and a nested `colours` object of unprefixed six-digit hex strings. Output is `CaelestiaScheme?` holding `isDark` and a `KainosPalette`.
  - Required keys, all consumed: every M3 role `ColorScheme` takes (`primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `inversePrimary`, the same four for `secondary`, `tertiary` and `error`, `background`, `onBackground`, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `surfaceTint`, `inverseSurface`, `inverseOnSurface`, `outline`, `outlineVariant`, `scrim`, `surfaceBright`, `surfaceDim`, and the five `surfaceContainer*` levels), plus the accent slots `red`, `peach`, `yellow`, `green`, `teal`, `mauve`, `pink`, `overlay1`, and `sapphire` (falling back to `blue` when `sapphire` is absent).
  - Unrelated keys (`primaryDim`, palette key colours, `term0`-`term15`, `k*`, `success*`) are ignored, so an unused malformed key never invalidates the palette. A missing or malformed consumed key, or a mode other than `dark`/`light`, returns null. This is the only validation point.
  - A sanitized copy of the real file shape is committed as the test fixture.
- Mode precedence. While following and the scheme is valid, the shell decides both palette and `isDark`, and the Appearance mode selector shows as disabled with "Caelestia decides light or dark". With following off, or no valid scheme, the saved `themeMode` and the tint apply. Album light and artwork treatment always read `isDark` from the resolved palette.
- Desktop source in `shared/src/jvmMain/`, on `Dispatchers.IO`. Read once immediately at start. Watch the directory `~/.local/state/caelestia/` with `WatchService` for create, modify, delete and overflow (the shell replaces the file, so watch the directory). Debounce 150 ms, then re-read only when the file's size or mtime changed. If the directory does not exist yet, watch for it from the nearest existing parent and register when it appears. A malformed update keeps the last valid palette. Deleting the file publishes null, which selects the fallback. Close the `WatchService` when the collecting scope is cancelled. A 5-second size/mtime check is the backstop for missed events and never re-parses unchanged content. Publish a `StateFlow<CaelestiaScheme?>`. On Android the actual is a constant `null` flow. `KAINOS_CAELESTIA_SCHEME` overrides the path for tests and screenshots.
- Album light under Caelestia stays inside the cover card, mixed 45% toward `primaryContainer`. Under Folio it becomes the ink wash behind the cover plus the coloured rule under it. `AlbumLight.kt` keeps its sampler; only its consumers change.

## Typography

- Folio uses `FontFamily.Serif` for display, titles and time readouts, italic serif for soft accents, and the platform sans for body. On Android that is Noto Serif and Roboto, the same faces the launcher renders. No new font files.
- Caelestia uses the system family `Google Sans Flex` (fall back to `Google Sans`, then Rubik, then the default sans) through the desktop `FontFamily(String)` lookup in a jvm actual. Android falls back to the default sans if a user picks Caelestia there.
- The existing unused Outfit and IBM Plex resources are deleted.

## Seek bar

Keep Material `Slider` and its interaction, focus and accessibility semantics, and replace only its `track` and `thumb` slots with skin drawings. `NowPlayingProgress.kt` keeps its behaviour unchanged (scrub per queue entry, commit on release, disabled while unresolved or buffering, quiet rail for unknown duration, existing progress helpers). The drawing primitive is `SeekTrack(fraction, scrubbing, durationMs, isPlaying, enabled)`, with the style read from `SkinTokens`. Volume keeps its wheel handling in `NowPlayingTransport.kt:226` and swaps only the drawing. `RULED` draws a baseline with 30-second ticks, taller minute ticks with serif minute numerals, the played part in ink and an accent dot at the playhead. `WAVY` draws Caelestia's slider with an animated wavy primary fill (2 s period, paused when not playing), 4 dp gaps, a 4 x 36 dp pill handle and a stop dot at the track end. The mini player uses the same primitive in a compact form. Volume uses the same style without ticks or waves.

## Work order

Each unit ends with a check before the next starts.

1. **Settings and theme core.** Settings fields, the shared decoder and its tests, theme model, tint table, accent resolver, Caelestia parser and watcher with tests, typography, `KainosTheme` installing M3 slots. Migrate every caller of `AppColorScheme` in this unit (`App.kt`, `SettingsAppearanceSection.kt` with the new skin picker, seven tints with swatches, Light/Dark/System with the disabled-while-following state, and "Follow Caelestia theme" on desktop), replace `ColorContrastTest` with `AccentContrastTest`, then delete the old palettes and enum. Check: `./gradlew :core:model:jvmTest :core:data:jvmTest :shared:jvmTest` passes and the desktop app launches and recolours from the live scheme.
2. **Gallery lever.** First prove one primitive renders through `ImageComposeScene` in `:shared` jvmTest at fixed size and density with a fixed animation clock, adding the desktop native runtime to the test classpath if needed. Then render the primitives and existing pure content composables only, per skin, tint and mode, into `shared/build/skin-gallery/`. Full screens are checked with real desktop screenshots, not by extracting screens for the gallery.
3. **Shared primitives.** One owner for `ui/theme/`, `ui/components/Components.kt`, `ui/App.kt` and `settings/SettingsComponents.kt`: `SeekTrack`, `LedgerRow`/`LedgerCell`, `IconTile` with underline mark, `Slip` card, `SourceMark`, `AvailabilityMark`, chips and buttons, tab bar and rail, `TrackRow`, `MiniPlayerBar`, the app chrome and the session handoff pill. Contract changes finish here before any screen group starts. Check: unit tests, gallery, desktop launch.
4. **Screens.** Restyle every screen through those primitives, keeping all behaviour, state and callbacks. Group A: Now Playing and Queue. Group B: Home and Search. Group C: Library, the rest of Settings, Downloads. Groups own only their `screens/<name>/` directories, may add private composables there, and route any shared-file change through the unit 3 owner. They may run in parallel.
5. **Verification.** Full CI command, gallery in Folio light, Folio dark, Catppuccin dark and the live-scheme fixture, the live behaviour checks below on desktop, Android `assembleDebug` and `lintDebug`.

## Tests

- `CaelestiaSchemeTest`. The user's real file shape parses, roles land on the right `ColorScheme` slots, a missing role returns null, a malformed hex returns null.
- `AccentContrastTest`. Replaces `ColorContrastTest`. Every tint, mode and accent role meets 3:1 against `ground` and `sheet`, and body text meets 4.5:1.
- Settings decode tests in `core/data` `FileStoresTest`. A legacy file with `"colorScheme":"DRACULA"` and `"dynamicColor":true` decodes without resetting other fields and yields `tint = FOLIO`. `"CATPPUCCIN"` yields `CATPPUCCIN`.
- `SkinResolutionTest`. `PLATFORM_DEFAULT` resolves per platform label. Following off or a null scheme falls back to the tint.
- Existing presenter and content tests stay green unchanged. That proves presenter behaviour only; UI behaviour is covered by the live checks.
- `CaelestiaSchemeWatcherTest` with temporary paths. Initial read, atomic replace, malformed update keeps the last palette, delete publishes null, directory created after start, deletion and recreation, cancellation closes the watcher.
- Mode precedence. Shell light/dark switch, invalid scheme, following off.

## Live checks

- Desktop. Launch with `scripts/kainos-player-dev`, keep the run log, screenshot the window on Hyprland (`hyprctl` window geometry plus `grim -g`), compare against the mockup. Change scheme through `KAINOS_CAELESTIA_SCHEME` pointing at a temp copy that a script rewrites, and confirm the window recolours without restart. Never edit the user's real `scheme.json`.
- Desktop behaviour, each a pass/fail line in the log. Type-to-search opens Library search pre-filled, Enter plays the top match, Escape clears. Typing a space inside every text field and dialog inserts a space and does not toggle playback. Global Space toggles play/pause. Escape and back dismiss Now Playing and Queue overlays and walk tab history. Library scroll is preserved returning from a song. A short Favorites-only list scrolls both ways. Toggling filters resets scroll to the top without the sticky-header jump. Resizing narrow to wide and switching skin while scrolled and playing keeps scroll and playback. The volume wheel changes volume. The handoff pill auto-dismisses and dismisses manually.
- Android. `assembleDebug` and `lintDebug` prove compilation only. Phone interaction stays pending until the user approves an install.

## Risks

- `Color(0x...)` literals outside the theme. Four exist (`Theme.kt` provider colours). They move into the accent map.
- Contrast of raw tint hues on paper. Handled by the resolver and enforced by the test.
- Compose `ImageComposeScene` may not render Coil images. Gallery fixtures use placeholder art from `forKey`.
- WatchService on a directory the shell recreates. The watcher re-registers when the directory disappears and polls every 5 s as a backstop.
- Session handoff pill and pairing UI from the Home sync program live in `App.kt` and Settings. They are restyled, not removed.
