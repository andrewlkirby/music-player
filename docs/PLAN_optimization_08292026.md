# Efficiency / Battery Optimization Pass

## Context

The user wants the music player to run as efficiently as possible without draining
CPU/battery during normal operation. A three-part audit (playback service, Compose/UI,
data layer) found that the UI is already lean — **no** continuous animations, blur,
spectrum/waveform visualizations, or main-thread bitmap work exist. The real costs
cluster into four tiers, addressed below. Scope confirmed with the user: **do everything**,
and for the DB-index change **accept the one-time destructive rescan** (the app uses
`fallbackToDestructiveMigration`).

A notable correctness bug surfaced during the audit and is folded into Tier 3 (any rescan
silently wipes play counts and favorites). Per the user, the automatic 6-hour background
rescan is being removed entirely in favor of the existing manual rescan button — they rarely
add songs, so on-demand scanning is sufficient and avoids the recurring background cost.

Environment note (see `memory/env_paths.md`): `adb` and `JAVA_HOME` are not on PATH.
Build with `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug`.

---

## Tier 1 — Continuous cost while playing (highest value, low risk)

The only recurring CPU cost during playback is a **500 ms position poll** that rewrites a
**monolithic** `PlayerUiState`, fanning a recomposition out to every collector ~2×/sec.

### 1a. Split `position` out of the monolithic UI state
- **Problem:** `PlayerViewModel.startPositionUpdater()` (`PlayerViewModel.kt:210-221`) does
  `_uiState.update { it.copy(position = pos) }` every 500 ms. `position` lives inside
  `PlayerUiState` (`PlayerViewModel.kt:33-43`) alongside `queue`, `currentSong`, etc., so
  **every** `collectAsState` site recomposes on each tick — including `MainActivity.kt:55`
  (whole Scaffold/bottomBar subtree) and the entire `QueueView`.
- **Fix:** Add a dedicated high-frequency flow in `PlayerViewModel`:
  `private val _position = MutableStateFlow(0L)` / `val position: StateFlow<Long>`. The
  500 ms loop updates **only** `_position`. Remove `position` from `PlayerUiState` (leave
  `duration` there — it changes once per track, not per tick).
- **Consumers** read position from the new flow instead of `state.position`:
  - `NowPlayingScreen.kt:218-238` — seek `Slider` value + the two time `Text`s.
  - `MiniPlayer.kt:41-49` — `LinearProgressIndicator` progress.
  - Each wraps just its progress widget so only that widget recomposes per tick.
- **Result:** the 500 ms tick recomposes ~3 small widgets instead of the whole tree; the
  queue panel and app Scaffold stop recomposing on every tick.

### 1b. Remove O(n) `indexOf` per queue row
- **Problem:** `NowPlayingScreen.kt:326-327` — inside `itemsIndexed(...) { _, song -> }` the
  index is discarded (`_`) and then recomputed with `state.queue.indexOf(song)`, an O(n)
  linear scan per visible row, per recomposition.
- **Fix:** Use the index the lambda already provides:
  `itemsIndexed(state.queue, ...) { index, song -> val indexInQueue = index }`. Drop the
  `indexOf` call. (Combined with 1a, `QueueView` no longer recomposes on position ticks at all.)

---

## Tier 2 — Per-track-transition DB fan-out

- **Problem:** `MusicPlaybackService.kt:155-160` calls `repository.incrementPlayCount(songId)`
  on **every** `onMediaItemTransition`. That `UPDATE songs SET playCount = playCount + 1`
  (`Daos.kt:55-56`) invalidates the entire `songs` table, forcing every active
  `Flow<List<SongEntity>>` (`getAllSongs`, `getFavorites`, `getMostPlayed`,
  `getRecentlyAdded`, active album/artist/folder queries, and the `pagingPlayCountDesc`
  source) to re-query and re-map the whole result to domain on `Dispatchers.Default`. Worst
  during skip-storms — one full invalidation per skip.
- **Fix:** Only count a song once it has actually been *listened to*, not on every
  transition. Track the current song id and schedule a **tracked, cancel-on-next-transition**
  increment (e.g. `playCountJob = serviceScope.launch { delay(THRESHOLD); increment(id) }`),
  cancelling the prior job at the top of `onMediaItemTransition`. Rapid skips cancel before
  the threshold fires → **zero** invalidations during skip-storms; normal listening fires
  exactly once per song. This is also semantically more correct (a skipped song isn't a play).
- Reuse the existing `serviceScope` and the job-tracking pattern already used for
  `queueLoadJob`/`periodicPersistJob` (`MusicPlaybackService.kt:35-37,61`).

---

## Tier 3 — Library scan: make it manual + stop it wiping user data

The device library is scanned from MediaStore (`IS_MUSIC != 0` — all music files on the
device) and the app DB is rebuilt from it. Per the user (who rarely adds songs), the biggest
win is to **stop the automatic background rescan** and rely on the existing manual button.

### 3a. Remove the automatic 6-hour periodic scan *(primary fix)*
- **Problem:** `MusicPlayerApp.onCreate` enqueues a `PeriodicWorkRequest(6h)` scan on every
  launch (`MusicPlayerApp.kt:62`, `MediaScanWorker.enqueuePeriodicScan`). It runs a full
  MediaStore rescan in the background every 6 hours — recurring CPU/IO/battery cost, and (via
  3b) the thing that silently wipes play counts/favorites on its own schedule.
- **Fix:** Delete the `MediaScanWorker.enqueuePeriodicScan(wm)` call at `MusicPlayerApp.kt:62`
  (and, if nothing else references it, the `enqueuePeriodicScan` helper in `MediaScanWorker`).
  **Keep** the one-time first-launch scan (`enqueueInitialScan`, `MusicPlayerApp.kt:61`) so a
  fresh install still populates, and **keep** the manual rescan button in Settings
  (`enqueueManualScan`, `SettingsScreen.kt:189`) and the SD-card scan.
- **Tradeoff (accepted):** songs added via other apps won't appear until the user taps rescan.
- **Result:** zero background scanning; scanning only happens on first launch or on demand.

### 3b. Stop a rescan from wiping play counts / favorites *(bug fix, lower priority)*
- **Problem:** `SongEntity` defaults `playCount = 0` and `isFavorite = false`
  (`Entities.kt:39-40`). `scanMediaStore()` builds each row fresh **without reading existing
  values** (`MusicRepository.kt:303-321`) and persists via `insertSongs` with
  `OnConflictStrategy.REPLACE` (`Daos.kt:46-47`, `MusicRepository.kt:369`). So **any** rescan
  resets play counts and favorites for all MediaStore songs. With 3a this can no longer happen
  on a background timer, but a **manual** rescan would still wipe them — which it shouldn't.
- **Fix:** Before the cursor loop, fetch existing rows into a map
  `id -> (lastModified, playCount, isFavorite)`. When building each `SongEntity`, **preserve**
  the existing `playCount`/`isFavorite` (default 0/false only when the id is genuinely new).
  Optionally also skip rebuilding rows whose MediaStore `DATE_MODIFIED` (`lastModified`,
  `MusicRepository.kt:288`) is unchanged, to make manual scans faster — carry the existing
  entity forward into `songs`/`validIds` so obsolete-row cleanup still works.
- **Result:** a manual rescan updates the library without losing the user's favorites/counts.
- **Found during verification:** `SdCardScanWorker.kt` has the identical bug on its own
  independent scan path — for SD-card files already indexed by MediaStore it deliberately
  reuses MediaStore's numeric `_ID` (`SdCardScanWorker.kt`, "fast path" comment: "Uses
  MediaStore's own _ID as the primary key"), so its REPLACE-insert can silently wipe the
  exact row scanMediaStore() just fixed. Patched identically: fetch
  `repository.getAllSongsList().associateBy { it.id }` once before the scan loop and carry
  `playCount`/`isFavorite` forward into each constructed `SongEntity`. Confirmed live: favorited
  a song (isFavorite=1, playCount=2), ran "Rescan All Folders" (which fires both
  MediaScanWorker and SdCardScanWorker), and both values survived after both workers
  completed.

### 3c. Add missing sort indices (accepted one-time rescan)
- **Problem:** `songs` is indexed on `albumId, artistId, path, isFavorite, dateAdded,
  playCount, source` (`Entities.kt:19-22`) but **not** on the columns actually sorted on —
  `title` and `artist` — so every paged/sorted fetch (`getAllSongs`, `pagingTitleAsc/Desc`,
  `pagingArtistAsc`, `sortedSongs*`, `Daos.kt:11,89-124`) builds a transient sort b-tree.
  `albums.name` and `artists.name` are likewise unindexed despite `ORDER BY name`
  (`Daos.kt:135,157`).
- **Fix:** Add indices to the entities. Sort queries use `COLLATE NOCASE`, so the indices
  must match to be usable:
  - `songs`: `Index(value=["title"])`, `Index(value=["artist"])`
  - `albums`: `Index(value=["name"])`
  - `artists`: `Index(value=["name"])`
  - Use `@Index` with matching `NOCASE` collation where Room supports it; otherwise pair with
    a hand-written `CREATE INDEX ... COLLATE NOCASE`.
- **Migration:** Bump the Room schema version. Per the user's choice, rely on the existing
  `fallbackToDestructiveMigration()` (`AppModules.kt:29`) — the DB is dropped and repopulated
  by one full rescan on next launch. **Caveat surfaced to user:** this drops current
  favorites/playCount/playlists once. (Note: with 3b landed, playCount/favorites stop being
  wiped on future rescans, but this one-time drop still happens on the version bump. If the
  user later wants to avoid it, a proper `Migration` doing `CREATE INDEX` without dropping data
  is the alternative — out of scope given their choice.)

---

## Tier 4 — Small leaks / config cleanup

### 4a. Track and cancel the sleep-timer coroutine
- **Problem:** `MusicPlaybackService.kt:573-581` launches
  `serviceScope.launch { delay(minutes*60_000L); player.pause() }` **untracked**. Setting a
  new timer does not cancel the previous one — concurrent sleep-timer coroutines accumulate,
  and a set timer can't be cancelled short of service destruction.
- **Fix:** Store it in a `sleepTimerJob: Job?` field; `sleepTimerJob?.cancel()` before
  launching a new one. Cancel it in `onDestroy` alongside the other tracked jobs
  (`MusicPlaybackService.kt:129-132`). Optionally expose a cancel path for a "0 minutes"/off
  command.

### 4b. Remove redundant WorkManager initialization
- **Problem:** `MusicPlayerApp` implements `Configuration.Provider` (`MusicPlayerApp.kt:20-23`,
  the on-demand init path Hilt/WorkManager expects) **and also** calls
  `WorkManager.initialize(...)` manually in `onCreate` (`MusicPlayerApp.kt:52-57`) with a second
  `Configuration.Builder()` — a redundant dual-init.
- **Fix:** Delete the manual `WorkManager.initialize(...)` block (lines 51-57); keep the
  `Configuration.Provider` override. `WorkManager.getInstance(this)` at `:60` then uses the
  provider-supplied config. Verify the default `WorkManagerInitializer` is not disabled in the
  manifest in a way that would require the manual call (it isn't, given the provider is present).

---

## Out of scope (intentionally not changed)
- **No wake lock is acquired** despite the `WAKE_LOCK` permission; `setWakeMode` is never
  called (`AppModules.kt:78-81`). Background playback relies on the foreground
  `MediaSessionService` + OS audio pipeline, which is the correct low-power default. Adding a
  wake lock would *increase* battery use — leave as-is.
- Coil cache config (`MusicPlayerApp.kt:30-46`) is already well-tuned (512 MB disk,
  `respectCacheHeaders(false)`, downscaled decodes) — no change.
- Full-library loads for shuffle/queue (`getAllSongsList`/`sortedSongs*`) are one-shot on user
  tap, not periodic — acceptable; not touched in this pass.

---

## Verification

Build & install the debug APK, then confirm each tier on-device (serial `RFCTC0W6GBW`):

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Tier 1 (recomposition):** Enable Layout Inspector / a temporary recomposition counter, play
  a track, open the queue panel. Confirm only the seek bar + time labels + mini-player progress
  update on the 500 ms cadence; the queue list and Scaffold no longer recompose per tick. Seek
  bar must still advance smoothly.
- **Tier 2 (playCount):** Rapidly skip through ~20 songs, then query the DB via
  `adb shell "run-as com.musicplayer sqlite3 databases/<db> 'SELECT id,playCount FROM songs ORDER BY playCount DESC LIMIT 5'"`
  — skipped songs' counts must **not** increment; a song left playing past the threshold must
  increment exactly once.
- **Tier 3a (no auto-scan):** Leave the app installed/idle and confirm via logcat / `adb shell
  dumpsys jobscheduler` that no periodic MediaScanWorker fires; the manual Settings rescan and
  first-launch scan still work.
- **Tier 3b (data preserved):** Favorite a few songs and note their playCount. Trigger a manual
  rescan (Settings) and re-query — favorites/playCount **preserved**.
- **Tier 3c (indices):** After the one-time rescan, sort the Songs list by title/artist and scroll
  — correct order, no regression. Optionally verify index use with
  `EXPLAIN QUERY PLAN SELECT * FROM songs ORDER BY title COLLATE NOCASE`.
- **Tier 4a (sleep timer):** Set a sleep timer twice in a row; confirm only one pause fires (add a
  temporary log, or observe a single pause at the latest timer's expiry).
- **Tier 4b (WorkManager):** App launches with no WorkManager double-init warning in logcat; the
  initial + periodic scans still enqueue and run.
- **Overall battery sanity:** `adb shell dumpsys batterystats --reset`, play for a fixed interval,
  then inspect the app's CPU/wakelock share vs. the pre-change build.
```
