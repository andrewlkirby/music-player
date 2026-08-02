# Per-Theme Fonts + Icon Styles

## Context

The 7-theme feature (Dark, Light, Ancient Egypt/Greece/Rome/China, Space Goth — each with its own color palette + optional background image) is already implemented and verified on-device. Each theme currently differs only in **color**. The user wants each theme to also have a **matching font** and **matching icon style**, so switching themes changes the whole typographic + iconographic feel, not just colors.

Two decisions confirmed with the user:
- **Icons** → reuse the existing Material icons but switch their **style variant** per theme (Filled / Outlined / Rounded / Sharp). NOT bespoke custom icon art.
- **Fonts** → **bundle** `.ttf` files in `res/font/` (works fully offline, renders instantly). NOT downloadable Google Fonts.

## Current state (from inventory)

- **Typography is 100% Material3 default**: `Theme.kt:41` passes `typography = Typography()`. No `res/font/`, no `Type.kt`, no custom `FontFamily`, no downloadable-fonts usage anywhere — greenfield.
- `MusicPlayerTheme(appTheme, backgroundPath, content)` (Theme.kt:15-19) already resolves `colorSchemeFor(appTheme)` and `appTheme.isDark`; `AppTheme`/`colorSchemeFor`/`isDark` live in `presentation/theme/Palettes.kt`.
- **Icons are all Filled**, referenced inline (no central accessor). 41 distinct: 36 `Icons.Default.*` + 5 `Icons.AutoMirrored.Filled.*` (ArrowBack, Sort, QueueMusic, VolumeUp, TrendingUp). Spread across **11 files** under `presentation/`: `MainActivity.kt`, `MiniPlayer.kt`, `nowplaying/NowPlayingScreen.kt`, `settings/SettingsScreen.kt`, `search/SearchScreen.kt`, `browse/{albums,songs,folders,playlists,artists}/*Screen.kt`, `equalizer/EqualizerScreen.kt`.
- `material-icons-extended` is already a dependency (`app/build.gradle.kts:82`) — it ships Filled/Outlined/Rounded/Sharp/TwoTone for every icon (and the mirrored variants for the 5 AutoMirrored ones), so per-theme style switching needs **no new dependency**.

## Design

### 1. Icon style per theme — central `AppIcons` accessor
- **`Palettes.kt`**: add `enum class IconStyle { FILLED, OUTLINED, ROUNDED, SHARP }` and `fun iconStyleFor(theme: AppTheme): IconStyle`.
- **New `presentation/theme/AppIcons.kt`**:
  - `val LocalIconStyle = staticCompositionLocalOf { IconStyle.FILLED }` (theme-scoped local, same pattern MaterialTheme uses).
  - `object AppIcons` exposing every icon the app uses as a `@Composable`-resolved `ImageVector`, e.g.:
    ```kotlin
    val MusicNote: ImageVector @Composable get() = when (LocalIconStyle.current) {
        IconStyle.FILLED -> Icons.Filled.MusicNote
        IconStyle.OUTLINED -> Icons.Outlined.MusicNote
        IconStyle.ROUNDED -> Icons.Rounded.MusicNote
        IconStyle.SHARP -> Icons.Sharp.MusicNote
    }
    ```
  - Uses 8 wildcard imports (`androidx.compose.material.icons.{filled,outlined,rounded,sharp}.*` + the 4 `automirrored.*` equivalents) so `Icons.Filled.X`/`Icons.Sharp.X`/`Icons.AutoMirrored.Rounded.X` all resolve without 160+ explicit imports. ~41 `when` blocks, one file.
- **Refactor the 11 files**: replace each `Icons.Default.X` / `Icons.AutoMirrored.Filled.X` with `AppIcons.X`. All usages are already inside `@Composable` scope (including `MainActivity`'s `bottomNavItems` list, built inside the theme content lambda), so the composable-getter resolves fine and re-resolves when the theme changes. Two-icon toggles (Pause/PlayArrow, Favorite/FavoriteBorder, RepeatOne/Repeat) stay conditional at the call site: `if (playing) AppIcons.Pause else AppIcons.PlayArrow`. Drop the now-unused `Icons.*` imports per file.
- **Proposed style map (tunable):** DARK→Filled, LIGHT→Outlined, EGYPT→Sharp (carved stone), GREECE→Outlined (pottery line-art), ROME→Filled (imperial solid), CHINA→Rounded (ink/brush softness), SPACE_GOTH→Sharp (angular/techno).

### 2. Fonts per theme — bundled `res/font/` + `typographyFor`
- Add OFL `.ttf` files under `app/src/main/res/font/` (lowercase names, e.g. `cinzel_regular.ttf`). Implementation step: obtain the chosen families from Google Fonts (OFL) and drop the static TTFs in.
- **New `presentation/theme/Fonts.kt`**:
  - One `FontFamily` per bundled font (`FontFamily(Font(R.font.x_regular), Font(R.font.x_bold, FontWeight.Bold), …)`).
  - `fun fontsFor(theme: AppTheme): Pair<FontFamily, FontFamily>` → (displayFamily, bodyFamily).
  - `fun typographyFor(theme: AppTheme): Typography` → start from `Typography()` and `.copy()` each style: **display\*, headline\*, titleLarge → display family**; **everything else (titleMedium/Small, body\*, label\*) → body family**. This keeps small/list text on a readable body font even when the display font is decorative.
- **Proposed font map (all OFL; display / body — tunable):**
  | Theme | Display | Body |
  |---|---|---|
  | Dark | Space Grotesk | Inter |
  | Light | Poppins | Inter |
  | Egypt | Rokkitt (Egyptian slab) | Inter |
  | Greece | Marcellus | Cormorant Garamond |
  | Rome | Cinzel (Roman caps) | EB Garamond |
  | China | Ma Shan Zheng (brush) | Noto Serif |
  | Space Goth | Orbitron | Rajdhani |
  - Display-only faces (Cinzel, Orbitron, Ma Shan Zheng) are never used for body text — always paired with a readable body family above.
  - **APK-size caution:** full CJK fonts (Ma Shan Zheng, Noto Serif SC) can be multi-MB. The app UI is English-only, so use the **Latin-subset** static TTF (Google Fonts' Latin download, or `pyftsubset --unicodes=U+0000-024F`) for the China display font to keep it small. Reuse `Inter` as the shared body family across several themes to limit the number of bundled files.

### 3. Wire into `MusicPlayerTheme` (`Theme.kt`)
- Pass `typography = typographyFor(appTheme)` to `MaterialTheme` (replacing `Typography()`).
- Wrap `MaterialTheme(...)` in `CompositionLocalProvider(LocalIconStyle provides iconStyleFor(appTheme)) { … }` so all `AppIcons.*` reads pick up the theme's icon style.
- No signature change; existing color / background-image / status-bar logic untouched.

### Files
- New: `app/src/main/res/font/*.ttf`, `presentation/theme/Fonts.kt`, `presentation/theme/AppIcons.kt`.
- Edit: `presentation/theme/Palettes.kt` (add `IconStyle` + `iconStyleFor`), `presentation/theme/Theme.kt` (typography + `LocalIconStyle` provider), and the **11 icon-using files** (swap `Icons.*` → `AppIcons.*`).

## Verification
1. `./gradlew assembleDebug` compiles; confirm all 41 `AppIcons.*` resolve and no stale `Icons.*` imports remain.
2. On device: switch through all 7 themes → each shows a **distinct font** (headers especially) **and** a distinct icon style (e.g. Space Goth sharp/angular vs. China rounded). Check bottom nav, top bars, Now Playing controls, list items.
3. **Readability**: list-item titles/subtitles and small labels stay legible in every theme (body font, not the decorative display font).
4. **Offline**: enable airplane mode, cold-start the app → fonts still render (bundled, no network) in every theme.
5. **Persistence**: force-stop + relaunch → selected theme keeps its font + icon style.
6. `./gradlew testDebugUnitTest` still passes.
