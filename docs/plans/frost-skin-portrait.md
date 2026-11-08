# Frost appearance and portrait layout (brief, 2026-11-07)

Status: built on `feat/frost-skin-portrait` (user chose "Build it here now" over the controller queue). The model controller queued the work on 2026-11-07 01:30
(`NO_ELIGIBLE_CAPACITY`: Claude weekly 87 %, Codex 5-hour 94 %).

## Goal (user's words)

"add a new appearance to kainos player so that it follows current theme created by [Minimalist
Wallpaper Design Ideas thread]. Also, I need you to adapt the player to my new vertical asus."

## 1. Frost skin

The desktop shell now runs the Caelestia **Frost** style: grey glass over the misty-peak wallpaper,
no colour accent, light (Mist) or dark (Smoke) glass following the scheme mode.

Sources of truth (read, do not edit):
- Shell tokens: `~/orca/projects/ws-worktrees/frost-pills-dark/shell/modules/styles/frost/Tokens.qml`
  (live shell is `~/.config/quickshell/caelestia` -> that worktree).
- Style manifest: `.../styles/frost/style.json`. A style is active when its `wallpapers` list
  contains `~/.local/state/caelestia/wallpaper/path.txt`. `desktop-env` still says `folio`.
- Mockups: `Kainos Rice` repo, branch `folio-walls`, `mockups/folio-walls/neutral-mist-peak/`
  (`a-panels-dashboard-pages.png` Media page is the closest reference for Now Playing).
- Scheme mode: `~/.local/state/caelestia/scheme.json` `mode` (currently `dark`).

Player code: `shared/src/commonMain/kotlin/com/universalmusic/player/ui/theme/`
(`Skin.kt`, `Theme.kt`, `DesktopEnv.kt`, jvm `DesktopEnv.jvm.kt`, `StateFileWatcher.jvm.kt`),
`SkinChoice` in `core/model/.../data/settings/AppSettings.kt`, Settings ->
`SettingsAppearanceSection.kt`. Skin branch points: `AlbumLight.kt`, `Components.kt`, `Kit.kt`,
`Navigation.kt`, `SeekTrack.kt`, `HomePinsSection.kt`, `LibraryFoldersTab.kt`.

Work:
- `Skin.FROST` + `SkinChoice.FROST`; "Match desktop" resolves to FROST when the active wallpaper is
  claimed by the `frost` style (watch `wallpaper/path.txt` + read
  `~/.config/quickshell/caelestia/modules/styles/*/style.json`; keep `desktop-env` for folio vs
  caelestia otherwise). Backup model and settings decoder must accept the new value.
- Ground: the current wallpaper, cover-scaled, under a veil, as the window background (no window
  transparency: Skiko transparent windows are unreliable on Linux and the skin switches live).
  Fallback when no wallpaper (Android, missing file): vertical mist gradient. Lightly blur it.
- Glass slabs: vertical gradient fills from Tokens.qml (`fogTop/fogBot`; `reading*` for dense text in
  dark), 1 px `rim` top edge, `edge` outline, specular radial highlight (70 % x 55 % at 18 %/0 %,
  alpha 0.42 light / 0.10 dark). No drop shadows.
- Ink: light `#1c1e21 / #33363b / #40444a`; dark `#f4f4f3` at 1 / 0.86 / 0.74. No accent hue: emphasis
  inverts ink and ground (play button is an ink-filled circle with inverse glyph, selected tab is an
  ink underline). Hairline/track alphas from Tokens.qml. All text >= 4.5:1 measured on the real
  wallpaper, both modes.
- Type: Fira Sans (Regular/Medium), big figures (time, track title on large layouts) in Fira Sans
  ExtraLight/Light; installed under `/usr/share/fonts/TTF/`. Fallback to desktop sans.
- Radii: slab 22, popout 18, tile 16, row 14, cover 12 / large cover 16, field 28, pills full.
- Missing cover: plate gradient `#a9abad -> #4b4d50` with a white 0.55 highlight.
- Album light: off or reduced to a neutral grey for Frost (no colour wash).
- Mode: follow Caelestia scheme mode when following the desktop, else Theme mode setting.

## 2. Portrait layout (Asus VG27WQ on DP-3)

DP-3 is 2560x1440 rotated (transform 3), so a tiled window is about 1420 x 2490 px at scale 1.
Today that width selects `DesktopLayoutBand.Wide` (`ui/DesktopLayout.kt`), which puts Now Playing in
a side column and leaves the tall height mostly empty.

Add a portrait band chosen by aspect, not by monitor: width >= 840 dp and height >= 1.3 x width.

```
+-----------------------------------------------+
| rail |  Now Playing band (glass slab)          |
|      |  [ cover ~ 40% width ]  title (thin)    |
|      |                          artist, album   |
|      |                          seek            |
|      |                          shuffle prev [play] next repeat |
|      +-----------------------------------------+
|      |  content (Home / Library / Search)  | Queue (toggle, 320dp) |
|      |                                     |                       |
+-----------------------------------------------+
```

- Now Playing docks at the bottom (user request 2026-11-07), full content width, collapsible to the mini bar; height about a third
  of the window, cover capped so the content area keeps at least half the height.
- Queue sits beside content when width allows (existing `DesktopQueueBesideWidth`), else overlay.
- Half-height splits on the portrait monitor fall back to the existing bands automatically.
- Must not wipe scroll or restart playback when the band changes (existing rule).
- Home hides its session card while the band is visible (same as `playerPaneVisible`).

## Checks

- `./gradlew jvmTest` (root), Android assembly still compiles.
- Tests: skin resolution from wallpaper/style manifest; layout band selection for 1420x2490,
  1420x1200, 3440x1400.
- Headless screenshots (never a visible window) at 1420x2490 and 3440x1400, Frost light and dark,
  with contrast measured on the render.
- Desktop launcher runs from `.claude/worktrees/radio-eot`; rebuild and relaunch with
  `kainos-player --rebuild`, logs under `logs/desktop-run-*.log`.
