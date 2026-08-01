# Music Player — Android

A full-featured Android music player built with Kotlin, Jetpack Compose, Media3/ExoPlayer, Room, and Hilt.

---

## Feature Summary

| Area | Details |
|---|---|
| **Browse** | Songs, Albums (grid), Artists, Folders (breadcrumb), Playlists |
| **Smart Playlists** | Recently Added, Most Played, Favorites |
| **Now Playing** | Full-screen art, seek bar, skip/prev, play/pause, shuffle, repeat (off/one/all), queue view |
| **Mini Player** | Persistent bottom dock on every screen; tap to expand |
| **Media Scanner** | MediaStore query + JAudioTagger tag reading, Room cache, WorkManager background job |
| **Playback** | Media3 foreground service, audio focus, gapless, state persistence across restarts |
| **Formats** | MP3, M4A/AAC, FLAC, OGG, WAV, OPUS (ExoPlayer native) |
| **Notifications** | Media-style with album art + controls; lock screen controls |
| **Search** | Full-text across title / artist / album / filename |
| **Equalizer** | AudioEffect API with device presets + per-band sliders |
| **Sleep Timer** | Configurable fade-out timer (5 – 90 min) |
| **Bluetooth** | AVRCP via MediaSession |
| **Themes** | Material You dynamic colour; dark & light |
| **Permissions** | Android 13+ `READ_MEDIA_AUDIO`; legacy `READ_EXTERNAL_STORAGE` |

---

## Quick Install (no build tools required)

The fastest way to get the app on your phone — no Android Studio, no ADB, no command line.

1. Go to the [**Releases**](../../releases) page of this repo and download the latest `app-debug.apk`.
   (Every push to `main` also builds an APK automatically — grab it from the **Actions** tab → latest run → **Artifacts** if you want a build that isn't tagged as a release yet.)
2. Open the downloaded `.apk` file on your phone (e.g. tap it in your Downloads app or notification shade).
3. Android will prompt **"Install unknown apps"** the first time — allow it for the app you used to open the file (Files, Chrome, etc.). This only grants permission for that one app, not a system-wide setting.
4. Tap **Install**, then **Open**.
5. On first launch, grant the **music/storage permission** when prompted so the app can find your library.

That's it — no signing keys or developer options needed. This installs the debug build, which is functionally identical to release except for a larger file size and a debug-only signature (Android will just warn that the source isn't a store, which is expected for sideloaded apps).

If you'd rather build it yourself (e.g. to customize the code) or sideload via ADB, see the sections below.

---

## Prerequisites

| Tool | Minimum Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 35 (compile) / API 26 (min) |
| Gradle | 8.7 (wrapper included) |
| Android device or emulator | Android 8.0+ (API 26+) |

---

## Step-by-Step: Build the APK in Android Studio

### 1. Clone / open the project

```bash
# If you have the zip, unzip it first:
unzip MusicPlayer.zip -d MusicPlayer
```

Open **Android Studio → File → Open** and select the `MusicPlayer/` folder (the one containing `settings.gradle.kts`).

### 2. Sync Gradle

Android Studio will prompt you to sync automatically. If it does not:

**File → Sync Project with Gradle Files**

The first sync downloads ~500 MB of dependencies. Ensure you have an internet connection.

### 3. Accept SDK licences (if prompted)

```bash
# From a terminal with the Android SDK on your PATH:
yes | sdkmanager --licenses
```

Or follow the SDK Manager dialog inside Android Studio (**Tools → SDK Manager**).

### 4. Download the Gradle wrapper JAR (one-time)

The wrapper JAR is not included in the zip. Android Studio downloads it automatically during sync. If you prefer the command line:

```bash
cd MusicPlayer
gradle wrapper --gradle-version 8.7
```

### 5. Build a debug APK

**Option A — Android Studio GUI**

1. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for the build to complete (first build: ~3-5 min)
3. Click **locate** in the notification toast, or find it at:
   `app/build/outputs/apk/debug/app-debug.apk`

**Option B — Command line**

```bash
cd MusicPlayer
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### 6. Build a release APK (optional)

For a release build you need a signing keystore:

```bash
# 1. Generate a keystore (one-time):
keytool -genkey -v -keystore my-release-key.jks \
  -alias musicplayer -keyalg RSA -keysize 2048 -validity 10000

# 2. Add signing config to app/build.gradle.kts signingConfigs block,
#    or pass via command line:
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=my-release-key.jks \
  -Pandroid.injected.signing.store.password=YOUR_STORE_PASS \
  -Pandroid.injected.signing.key.alias=musicplayer \
  -Pandroid.injected.signing.key.password=YOUR_KEY_PASS
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## Sideloading via ADB

### 1. Enable Developer Options on your Android phone

1. Go to **Settings → About phone**
2. Tap **Build number** 7 times until "You are now a developer" appears
3. Go to **Settings → Developer options**
4. Enable **USB debugging**

### 2. Connect your phone

Connect via USB. Accept the "Allow USB debugging?" prompt on your phone.

Verify the device is detected:

```bash
adb devices
# List of devices attached
# XXXXXXXX   device
```

If you see `unauthorized`, unlock your phone and tap **Allow** on the dialog.

### 3. Install the APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Flags:
- `-r` — reinstall, keeping data (safe to use on updates)
- `-d` — allow version downgrade (add if needed)

### 4. Launch the app

```bash
adb shell am start -n com.musicplayer/.presentation.MainActivity
```

Or simply tap the **Music Player** icon on your home screen.

### 5. Grant permissions on first launch

The app requests **Read Audio Files** (Android 13+) or **Read External Storage** (Android 12 and below). Tap **Allow** when prompted.

---

## Wireless ADB (Android 11+)

```bash
# On your phone: Developer options → Wireless debugging → Enable
# Note the IP:PORT shown (e.g. 192.168.1.50:37123)

adb connect 192.168.1.50:37123
adb install -r app-debug.apk
```

---

## Project Structure

```
MusicPlayer/
├── app/
│   ├── build.gradle.kts               # App-level Gradle config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/musicplayer/
│       │   ├── MusicPlayerApp.kt      # Application class (Hilt + WorkManager)
│       │   ├── data/
│       │   │   ├── local/             # Room DB, DAOs, Entities, Mappers
│       │   │   └── repository/        # MusicRepository
│       │   ├── di/                    # Hilt modules (DB, Player)
│       │   ├── domain/model/          # Pure domain models
│       │   ├── presentation/
│       │   │   ├── MainActivity.kt
│       │   │   ├── Navigation.kt
│       │   │   ├── PlayerViewModel.kt # Central playback state (MediaController)
│       │   │   ├── MiniPlayer.kt
│       │   │   ├── browse/
│       │   │   │   ├── albums/        # AlbumsScreen + AlbumDetailScreen
│       │   │   │   ├── artists/       # ArtistsScreen + ArtistDetailScreen
│       │   │   │   ├── folders/       # FoldersScreen (breadcrumb nav)
│       │   │   │   ├── playlists/     # PlaylistsScreen + PlaylistDetailScreen
│       │   │   │   └── songs/         # SongsScreen
│       │   │   ├── equalizer/         # EqualizerScreen
│       │   │   ├── nowplaying/        # NowPlayingScreen + QueueView
│       │   │   ├── search/            # SearchScreen
│       │   │   └── theme/             # Material You theme
│       │   ├── service/               # MusicPlaybackService (Media3)
│       │   └── worker/                # MediaScanWorker (WorkManager)
│       └── res/
│           ├── drawable/              # Vector launcher icon
│           ├── mipmap-anydpi-v26/     # Adaptive icon XMLs
│           ├── values/                # strings, colors, themes
│           ├── values-night/          # Dark mode theme override
│           └── xml/                   # backup_rules, data_extraction_rules
├── gradle/
│   ├── libs.versions.toml             # Version catalog
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts                   # Root Gradle config
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

---

## Frequently Asked Questions

**Q: The build fails with "SDK location not found".**  
A: Create a `local.properties` file in the project root:
```
sdk.dir=/path/to/your/Android/sdk
# Example on macOS: sdk.dir=/Users/you/Library/Android/sdk
# Example on Windows: sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

**Q: `adb` is not found.**  
A: Add the Android SDK platform-tools to your PATH:
```bash
# macOS / Linux (add to ~/.zshrc or ~/.bashrc):
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"

# Windows: add C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools to System PATH
```

**Q: The app shows no music after installing.**  
A: Grant the storage permission when prompted, then wait ~10 seconds for the background WorkManager scan to complete. You can trigger a manual re-scan by force-stopping and reopening the app.

**Q: Equalizer has no effect.**  
A: Some Android OEM builds (Samsung, Huawei) lock global audio sessions. The equalizer targets audio session 0 (global). If unsupported, the Equalizer screen will display an informational message.

**Q: Build fails on "Duplicate class" errors.**  
A: Run `./gradlew dependencies` to find conflicting transitive deps. Usually resolved by upgrading `compose-bom` or pinning `kotlin-stdlib`.

---

## Extending the App

- **MusicBrainz artwork fallback** — add an OkHttp call in `MusicRepository` when `artworkPath` is null, query `https://coverartarchive.org/release-group/<mbid>/front` and cache with Coil's disk cache.
- **SAF folder picker** — launch `Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)` from a settings screen and persist the URI with `contentResolver.takePersistableUriPermission`. Pass the URI to a new `SAFScanWorker`.
- **FileObserver incremental rescan** — instantiate `FileObserver` on the music directories, emit events to a Channel, debounce, then call `repository.scanMediaStore()`.
- **Car / Android Auto** — implement `MediaBrowserServiceCompat` delegation from `MusicPlaybackService` and add the `android.media.browse.MediaBrowserService` intent filter (already present in the manifest).

---

## Licence

MIT — free to use, modify, and distribute.
