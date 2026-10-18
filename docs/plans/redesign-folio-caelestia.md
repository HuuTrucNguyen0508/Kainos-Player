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
- Delete `colorScheme: AppColorScheme`, the `AppColorScheme` enum, `dynamicColor` (no consumer) and the ten frozen palettes in `ColorSchemes.kt`. Old JSON keeps decoding because unknown keys are ignored. A legacy `"colorScheme"` value maps once to the nearest tint (CATPPUCCIN, NORD, GRUVBOX, ROSE_PINE, TOKYO_NIGHT, EVERFOREST map by name; everything else maps to FOLIO). Do this in the settings decode boundary, not in UI code.

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

`LocalKainosPalette` and `LocalSkinTokens` CompositionLocals sit beside `MaterialTheme` in `KainosTheme`. Screens never branch on skin. Only the primitives in `ui/components/` read `SkinTokens` and pick a drawing (the registry). A new skin difference goes into `SkinTokens`, not into a screen `if`.

Semantic accent map, defined once in `ui/theme/Accents.kt` and used everywhere:

- Sources. Spotify uses `green`, YouTube uses `red`, local files use `teal`, Kainos playlists use `purple`. Folder pins use `orange`.
- Availability. Local is `green`, cached is `teal`, downloading is `blue`, waiting is `yellow`, unavailable is `gray`, failed is `red`.
- Tabs. Home is `orange`, Search is `blue`, Library is `teal`, Settings is `purple`. Each tab's mark and active indicator use its hue.
- Now Playing. Heart is `pink`, shuffle on is `purple`, repeat on is `orange`, sleep timer is `yellow`, quality badge is `teal` for CD quality and `yellow` for hi-res, `blue` for lossy.
- Artwork placeholders draw from `forKey(id)` instead of the fixed olive generator in `Components.kt`.

Every accent must reach 3:1 against its background in every tint and mode. The test enforces it, and the accent resolver darkens or lightens toward the ink colour when a raw hue fails.

## Caelestia live scheme

- Pure parser in `shared/src/commonMain/.../ui/theme/CaelestiaScheme.kt`. Input is the file's text. Output is `CaelestiaScheme?` holding `mode` and a `KainosPalette`. Map M3 role keys 1:1 onto `ColorScheme`. Build the accent ramp from the Catppuccin-style named keys the shell writes (`red`, `peach`, `yellow`, `green`, `teal`, `sapphire`/`blue`, `mauve`, `pink`, `overlay1`). Reject the whole file (return null) when a required role is missing or a hex is malformed. This is the only validation point.
- Desktop source in `shared/src/jvmMain/`. Watch the directory `~/.local/state/caelestia/` with `WatchService` (the shell replaces the file, so watch the directory, not the file), debounce 150 ms, re-read, and publish a `StateFlow<CaelestiaScheme?>`. On Android the actual is a constant `null` flow. `KAINOS_CAELESTIA_SCHEME` overrides the path for tests and screenshots.
- Album light under Caelestia stays inside the cover card, mixed 45% toward `primaryContainer`. Under Folio it becomes the ink wash behind the cover plus the coloured rule under it. `AlbumLight.kt` keeps its sampler; only its consumers change.

## Typography

- Folio uses `FontFamily.Serif` for display, titles and time readouts, italic serif for soft accents, and the platform sans for body. On Android that is Noto Serif and Roboto, the same faces the launcher renders. No new font files.
- Caelestia uses the system family `Google Sans Flex` (fall back to `Google Sans`, then Rubik, then the default sans) through the desktop `FontFamily(String)` lookup in a jvm actual. Android falls back to the default sans if a user picks Caelestia there.
- The existing unused Outfit and IBM Plex resources are deleted.

## Seek bar

Split `NowPlayingProgress.kt` into its current behaviour (scrub per queue entry, commit on release, disabled while unresolved or buffering, quiet rail for unknown duration) and a drawing primitive `SeekTrack(fraction, scrubbing, durationMs, style)`. `RULED` draws a baseline with 30-second ticks, taller minute ticks with serif minute numerals, the played part in ink and an accent dot at the playhead. `WAVY` draws Caelestia's slider with an animated wavy primary fill (2 s period, paused when not playing), 4 dp gaps, a 4 x 36 dp pill handle and a stop dot at the track end. The mini player uses the same primitive in a compact form. Volume uses the same style without ticks or waves.

## Work order

Each unit ends with a check before the next starts.

1. **Foundation.** Settings fields and legacy mapping, theme model, tint table, Caelestia parser and desktop watcher, typography, `KainosTheme`, the accent map, ground drawing (dotted paper), and the primitives in `ui/components/`: `SeekTrack`, `LedgerRow`/`LedgerCell`, `IconTile` with underline mark, `Slip` card, `SourceMark`, `AvailabilityMark`, `SkinChip`, `SkinButton`, tab bar/rail. Delete `ColorSchemes.kt` palettes and `AppColorScheme`. Check: `./gradlew :core:model:jvmTest :core:data:jvmTest :shared:jvmTest` passes, plus the skin gallery below renders.
2. **Skin gallery lever.** A jvmTest (or `:shared` task) that renders the primitives and each screen's content composable with fixture state through Compose's `ImageComposeScene` into PNGs under `shared/build/skin-gallery/<skin>-<tint>-<mode>/`. It is the repeatable visual check for every later unit and for review.
3. **Screens.** Restyle every screen through the primitives, keeping all behaviour, state and callbacks unchanged. Group A: Now Playing, Queue, mini player and the app chrome in `App.kt` (including the session handoff pill). Group B: Home and Search. Group C: Library, Settings (Appearance gains the skin picker, the seven tints with swatches, Light/Dark/System and "Follow Caelestia theme" on desktop), Downloads. Groups touch disjoint files and may run in parallel after unit 1 lands.
4. **Verification.** Full CI command, gallery for both skins in at least Folio light, Folio dark, Catppuccin dark and the live Caelestia scheme, a desktop run on the real shell scheme, Android `assembleDebug` and `lintDebug`.

## Tests

- `CaelestiaSchemeTest`. The user's real file shape parses, roles land on the right `ColorScheme` slots, a missing role returns null, a malformed hex returns null.
- `AccentContrastTest`. Replaces `ColorContrastTest`. Every tint, mode and accent role meets 3:1 against `ground` and `sheet`, and body text meets 4.5:1.
- Settings decode tests in `core/data` `FileStoresTest`. A legacy file with `"colorScheme":"DRACULA"` and `"dynamicColor":true` decodes without resetting other fields and yields `tint = FOLIO`. `"CATPPUCCIN"` yields `CATPPUCCIN`.
- `SkinResolutionTest`. `PLATFORM_DEFAULT` resolves per platform label. Following off or a null scheme falls back to the tint.
- Existing presenter and content tests stay green unchanged, which proves behaviour did not move.

## Live checks

- Desktop. Launch with `scripts/kainos-player-dev`, keep the run log, screenshot the window on Hyprland (`hyprctl` window geometry plus `grim -g`), compare against the mockup. Change scheme through `KAINOS_CAELESTIA_SCHEME` pointing at a temp copy that a script rewrites, and confirm the window recolours without restart. Never edit the user's real `scheme.json`.
- Android. `assembleDebug` and `lintDebug`. Installing on the phone waits for the user.

## Risks

- `Color(0x...)` literals outside the theme. Four exist (`Theme.kt` provider colours). They move into the accent map.
- Contrast of raw tint hues on paper. Handled by the resolver and enforced by the test.
- Compose `ImageComposeScene` may not render Coil images. Gallery fixtures use placeholder art from `forKey`.
- WatchService on a directory the shell recreates. The watcher re-registers when the directory disappears and polls every 5 s as a backstop.
- Session handoff pill and pairing UI from the Home sync program live in `App.kt` and Settings. They are restyled, not removed.
