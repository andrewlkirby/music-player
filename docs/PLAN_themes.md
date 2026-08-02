# Selectable Themes (Dark, Light, 4 Ancient-Civilization themes, Space Goth) + Custom Background Images

## Context

Settings currently has no theme picker at all — `MusicPlayerTheme` (`presentation/theme/Theme.kt`) just follows `isSystemInDarkTheme()` and Material You dynamic color, with no user override and no way to persist a choice. The user wants an explicit "Theme" section in Settings offering: the existing **Dark** theme, a new **Light** theme, four new curated palettes — **Ancient Egypt, Ancient Greece, Ancient Rome, Ancient China** — and a **Space Goth** theme (black/void with neon violet-magenta-cyan accents). Every theme (confirmed with the user) should support an optional custom background image, rendered app-wide behind all screens.

## Current state (from research)

- `Theme.kt` (91 lines): `DarkColorScheme`/`LightColorScheme` already exist as Material3 `ColorScheme`s; `MusicPlayerTheme(darkTheme, dynamicColor, content)` is applied once at the root in `MainActivity.kt:41` (`presentation/MainActivity.kt`). It also sets the status-bar icon appearance via `isAppearanceLightStatusBars = !darkTheme` and transparent status bar — that edge-to-edge behavior must be preserved.
- **Every screen paints an opaque background.** The root `Scaffold` in `MainActivity.kt:70` sets no `containerColor`, so it defaults to the opaque `colorScheme.background`. Each child screen (`SongsScreen.kt:38`, `AlbumsScreen`, `FoldersScreen`, `SettingsScreen`, …) has *its own* `Scaffold`, also defaulting to `colorScheme.background`; `NowPlayingScreen.kt:75` sets `containerColor = MaterialTheme.colorScheme.background` explicitly. **Consequence:** naively placing an image in a `Box` behind the root Scaffold renders it invisible — the opaque Scaffolds paint over it. See "Rendering the background image" below for the fix.
- Settings persistence is ad-hoc Jetpack DataStore: `Context.settingsDataStore` + `KEY_WATCHED_URIS`, declared as top-level vals directly in `SettingsScreen.kt:64-66`. No repository layer exists yet for settings.
- `SettingsScreen.kt` `LazyColumn` uses a consistent section pattern (label header → `Card`/`item{}` blocks → `HorizontalDivider`), e.g. "Music Folders" at line 352, "About" at line 542 — a new "Theme" section fits between them.
- Only existing picker is SAF `ActivityResultContracts.OpenDocumentTree()` (`SettingsScreen.kt:299-306`). For images, the system Photo Picker `ActivityResultContracts.PickVisualMedia()` is the better UX (no runtime permission, works minSdk 26+ via the Play-services shim). **But its read grant is temporary** — not guaranteed to survive process death / reboot — so we must **not** persist the raw picker URI. Instead, copy the chosen image into app-internal storage and persist the local path (see `ThemeRepository`). This also sidesteps `takePersistableUriPermission` entirely.
- Coil (`AsyncImage`, `coil.compose.AsyncImage`) is already a dependency (`app/build.gradle.kts:107`) and already used in `NowPlayingScreen.kt:25` — reuse it for the background image.
- `MainActivity` is a Hilt entry point (`@AndroidEntryPoint`), so a small root-level `@HiltViewModel` is the natural way to feed theme state into `setContent`.

## Design

### 1. Theme model — new `presentation/theme/Palettes.kt`
```kotlin
enum class AppTheme(val displayName: String, val isDark: Boolean) {
    DARK("Dark", true), LIGHT("Light", false),
    EGYPT("Ancient Egypt", true), GREECE("Ancient Greece", false),
    ROME("Ancient Rome", false), CHINA("Ancient China", true),
    SPACE_GOTH("Space Goth", true)
}
fun colorSchemeFor(theme: AppTheme): ColorScheme   // pure, no Context needed
```
- `isDark` per theme drives the status-bar icon contrast (`isAppearanceLightStatusBars = !theme.isDark`), replacing today's `!darkTheme`.
- `DARK`/`LIGHT` reuse the existing `DarkColorScheme`/`LightColorScheme` (moved here from `Theme.kt`, unchanged).
- Each of the other 5 themes gets its own hand-picked `darkColorScheme()`/`lightColorScheme()` (matching its `isDark`), reusing the same Material3 builder already used today:
  - **Egypt** (dark) — gold + lapis blue on sandstone/desert-night surfaces.
  - **Greece** (light) — Aegean blue + terracotta on white/marble surfaces.
  - **Rome** (light) — imperial purple + deep red on cream/marble surfaces.
  - **China** (dark) — lacquer red + gold on near-black surfaces, jade accent.
  - **Space Goth** (dark) — near-black void background, neon violet/magenta primary, electric cyan accent, chrome/silver-grey surfaces.
- **Dynamic color (Material You) is dropped.** Today it's `true` by default, which on API 31+ overrides the curated palette with wallpaper-derived colors — that would make the Settings swatch lie about what the user actually gets. With explicit theme selection, `colorSchemeFor` always returns the curated scheme so *what the swatch shows is what renders*. (This is a deliberate behavior change from today's dynamic default.)

### 2. Rendering the background image (the key mechanism)
Because every `Scaffold` defaults its container to `colorScheme.background`, the cleanest, least-invasive way to reveal an image app-wide is to **make the effective `background` transparent when an image is active**, so all Scaffolds inherit transparency and the image shows through — no need to edit each screen.
- In `MusicPlayerTheme`, after picking `colorSchemeFor(appTheme)`, when a background image is present derive `colorScheme = base.copy(background = Color.Transparent)`. Keep `surface`/`surfaceVariant`/`surfaceContainer` opaque so `TopAppBar`, `NavigationBar`, `Card`s, dialogs and menus stay legible over the image (glassy look is out of scope; readability first).
- In `MainActivity`, wrap the root `Scaffold` in a `Box`:
  - Layer 0: `AsyncImage(model = backgroundPath, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())` — composed only when a path is set.
  - Layer 1: a scrim `Box(Modifier.fillMaxSize().background(base.background.copy(alpha = 0.55f)))` for legibility of content that sits directly on the (now-transparent) background.
  - Layer 2: the existing `Scaffold(containerColor = if (hasImage) Color.Transparent else colorScheme.background) { … }` — content otherwise unchanged.
- `NowPlayingScreen.kt:75` reads `colorScheme.background` directly, so it becomes transparent automatically under this scheme — no per-screen edit required. Its own full-bleed album-art layout still composes fine on top of the global image + scrim.

### 3. Persistence + `ThemeRepository` — new `data/repository/ThemeRepository.kt`
- `@Singleton class ThemeRepository @Inject constructor(@ApplicationContext context: Context)` — reuses the existing `Context.settingsDataStore` instance (no new Hilt module needed).
- Keys: `theme` (string `AppTheme.name`, default `DARK`) and one `bg_path_<THEME>` string per theme, so each theme remembers its own image independently.
- `val state: Flow<ThemeUiState>` = current `AppTheme` + resolved background file path (`String?`) for that theme.
- `suspend fun setTheme(theme: AppTheme)`.
- `suspend fun setBackgroundImage(theme: AppTheme, uri: Uri?)`: on non-null, **copy** the picked content into `filesDir/backgrounds/<theme>.jpg` off the main thread (`Dispatchers.IO`, `contentResolver.openInputStream` → file), then store that stable local path; on null, delete the file and clear the key. This guarantees the image survives force-stop/reboot (verification step 4).

### 4. Root wiring — new `presentation/theme/ThemeViewModel.kt`, edit `MainActivity.kt` + `Theme.kt`
- `ThemeViewModel` (`@HiltViewModel`) exposes `ThemeRepository.state` as a `StateFlow`, mirroring `SettingsViewModel`.
- `MainActivity`: `val themeViewModel: ThemeViewModel by viewModels()`; collect its state in `setContent`, pass `appTheme` + background path into `MusicPlayerTheme(...)` and the root `Box`/`Scaffold` as in §2.
- `MusicPlayerTheme` signature: `(appTheme: AppTheme, backgroundPath: String?, content)` — replaces `(darkTheme, dynamicColor, content)`. Preserves the existing status-bar `SideEffect` (now keyed off `appTheme.isDark`).

### 5. Settings UI — edit `SettingsScreen.kt`
- New "Theme" section in the `LazyColumn`, between "Music Folders" and "About", following the existing header/`Card`/`HorizontalDivider` convention.
- A grid (2 columns) of the 7 themes; each is a selectable `Card` showing a small swatch of that theme's primary/secondary/tertiary + display name + a selected check; tapping calls `viewModel.setTheme(theme)`.
- Below the grid: a background-image row for the **currently selected** theme — `AsyncImage` thumbnail (placeholder if none), a "Choose Image" button launching `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(...))` with `PickVisualMediaRequest(ImageOnly)`, and a "Remove" action when set.
- `SettingsViewModel`: inject `ThemeRepository`, expose `themeState: StateFlow<ThemeUiState>`, add `setTheme(theme)` / `setBackgroundImage(uri)` delegating to the repository.

### Files
- New: `presentation/theme/Palettes.kt`, `data/repository/ThemeRepository.kt`, `presentation/theme/ThemeViewModel.kt`
- Edit: `presentation/theme/Theme.kt` (new signature, palette lookup, transparent-background override, `isDark` status bar), `presentation/MainActivity.kt` (root `Box` + `AsyncImage`/scrim + transparent Scaffold container + feed theme state), `presentation/settings/SettingsScreen.kt` (Theme section UI + `SettingsViewModel` additions)

## Verification
1. `./gradlew assembleDebug` compiles cleanly.
2. On device: Settings → Theme shows all 7 swatches; tapping each recolors the whole app immediately (check Songs, Albums, Folders, Now Playing, Settings). Confirm the rendered colors **match the swatch** (dynamic color no longer overrides).
3. Pick a background image for Dark → it appears behind **every** screen (not just one) with top bar / nav bar / cards still legible; switch to Light (no image) → plain background, proving images are per-theme; switch back to Dark → image reappears.
4. **Force-stop and relaunch** → last-selected theme *and* its background image both persist (this exercises the internal-storage copy, not a temporary URI grant).
5. `./gradlew testDebugUnitTest` still passes.
