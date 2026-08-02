# Album Art Loading + Song-Change Performance

## Context

Two user-reported symptoms on a ~30,000-song library (SD card):

1. **Album art takes a long time to load** after a scan, and re-loads sluggishly while scrolling.
2. **Changing songs can be slow** — noticeable lag when starting playback from a big list and when skipping tracks.

This plan is the result of an inspection pass. It is **scoped to these two symptoms** and ordered by impact. The earlier `PLAN_folders-fix-and-perf.md` covered DB indices, off-main mapping, and the scan pipeline — that work is done and not repeated here.

### Status: implemented and verified on device (2026-08-01, follow-up 2026-08-02)
A4, A1, A3, B1, B2 are implemented and verified on Andrew's S20 FE (Android 13, `com.musicplayer` debug build, ~34k-track library after a full SD-card rescan). B3 was not implemented (low priority, no user-visible effect). A2 was deliberately skipped per its own gating condition — see the A2 write-up below, which now includes an on-device finding that changes its priority. See **Verification results** at the bottom for what was actually observed on the device.

Two pre-existing bugs found along the way (mini player empty on cold start; SD-card-only songs pruned by the MediaStore scan) were fixed and verified on 2026-08-01. A third pre-existing bug (full-list playback crashing due to playlist files being scanned in as songs) was found and fixed on 2026-08-02 — see **Bugs found along the way** below.

---

## Findings — Album art

### A1. No custom Coil `ImageLoader` (biggest art win) — `MusicPlayerApp.kt` — ✅ implemented, verified
`MusicPlayerApp` does **not** implement `ImageLoaderFactory`, so Coil 2.7.0 runs with defaults:
- **Disk cache: 2% of free space, capped at 250 MB.** With hundreds–thousands of distinct album arts, the cache thrashes: art evicted between sessions (and even within a long scroll), forcing a re-decode from the slow albumart provider (A2) every cold start and every scroll-back.
- **Memory cache: 25% of app heap** — fine, but not tuned alongside disk.
- **Crossfade default is off**, but list items don't disable it explicitly and don't request a fixed size (A3).

**Fix:** make `MusicPlayerApp` implement `ImageLoaderFactory` and provide a tuned loader:
```kotlin
override fun newImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(512L * 1024 * 1024)   // 512 MB — sized for a large library
                .build()
        }
        .respectCacheHeaders(false)  // content:// has none; avoids needless re-fetch
        .build()
```
This is the single highest-leverage change for the "art is slow to load" complaint.

**Verified:** app runs with the custom loader with no crashes; art renders correctly across Songs, Albums, and the mini player wherever the underlying provider has data (see A2 finding below for the caveat on *which* albums have data).

### A2. Deprecated/slow `content://media/external/audio/albumart` provider — `MusicRepository.kt:220`
`scanMediaStore()` stores each song's/album's art as
`content://media/external/audio/albumart/<albumId>`. This legacy provider does a per-request DB lookup + full-file read and is unofficial on Android 10+. It's keyed by `albumId` (so distinct images ≈ album count, which caches well **once A1 is fixed**), but cold decodes are expensive.

**Options (pick one):**
- **Keep the URI** (lowest risk) and rely on A1 + A3 to make cold loads rare and cheap.
- **Switch to `ContentResolver.loadThumbnail(songUri, Size(512,512), null)`** (API 29+) via a small custom Coil `Fetcher`/`Mapper` keyed by `albumId`. This is the modern, supported path and returns a right-sized thumbnail directly. More work; do only if A1+A3 prove insufficient on-device.

**On-device finding (2026-08-01), revises this item's priority:** direct-probed the legacy provider on the S20 FE (Android 13) for several real `albumId`s from the live DB (`adb shell content read --uri content://media/external/audio/albumart/<id>`). Result: **~40–60% of sampled albums return `FileNotFoundException: No album art found`** even though `artworkPath` is correctly populated (A4) — the provider itself has no data for those albums, most likely because the source files have no embedded art and MediaStore never extracted/cached a thumbnail for them. This is **not a caching problem A1 can fix** — it's an absence of source data via this API. Confirmed the *other* ~40–60% do return valid JFIF bytes and render correctly (Albums grid, mini player, Songs list all showed real covers for those). Net effect: some blank rows are expected/correct, not a bug — but if "more albums should show art" remains a live complaint after this pass, A2's `loadThumbnail()` (which can extract embedded ID3/FLAC art directly rather than relying on MediaStore's legacy cache) is the real next lever, not further Coil tuning. Still not implemented.

### A3. List/grid items decode full-resolution art — `SongsScreen.kt:121`, `AlbumsScreen.kt:142/211`, `MiniPlayer.kt:56` — ✅ implemented, verified
`AsyncImage(model = song.artworkUri, …)` has no explicit request size. Coil sizes the *output* to the 48 dp target, but the *source decode* still reads the full JPEG (often ≥1000×1000). Across a fast scroll of a 30k list that's a lot of wasted decode.

**Fix:** build an `ImageRequest` with an explicit small size and disabled crossfade for list rows:
```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(song.artworkUri)
        .size(128)            // ~2–3x the 48dp slot; downsamples at decode
        .crossfade(false)
        .build(),
    …
)
```
Leave the full-size decode only for `NowPlayingScreen` (large art). Consider a shared placeholder/error drawable so empty slots don't flash.

**Verified:** sized requests render correctly and cropped in the Songs list, Albums grid, and mini player on-device; no visual regressions.

### A4. SD-scanned songs get **no** artwork at all (real bug) — `SdCardScanWorker.kt:245,259` — ✅ implemented, verified
The SD worker writes `artworkPath = null` for every song and album, even though on the fast path it already holds the MediaStore `albumId` in `msRow`. Because `insertSongs`/`insertAlbums` use `OnConflictStrategy.REPLACE` (`Daos.kt:45,85`), whichever scan runs **last wins** — so an SD scan can overwrite the MediaStore scan's valid art back to `null`. For a library added primarily via the SD-card flow, this leaves much/all art blank regardless of A1–A3.

**Fix:** in `SdCardScanWorker`, when `msRow != null`, set
`artworkPath = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), msRow.albumId).toString()`
for both the `SongEntity` and the `AlbumEntity` (mirroring `scanMediaStore`). Leave `null` only on the JAudioTagger slow path where no `albumId` exists. This is likely a large part of why art "doesn't load" — for many rows there is nothing to load.

**Verified:** pulled the live DB after a full SD-card rescan — 33,916 of 33,996 songs now have a populated `artworkPath` (up from 0 before the fix); the remaining 80 are exactly the JAudioTagger-fallback songs, which correctly stay `null` (no real `albumId` to build a URI from). Confirmed several of those URIs resolve to real image bytes via `adb shell content read`.

---

## Findings — Song changes

### B1. `playSongs` ships the entire 30k-song queue synchronously (primary cause) — `SongsScreen.kt:88` → `PlayerViewModel.kt:150` — ✅ implemented (partial — see below), verified
Tapping a row calls `playerViewModel.playSongs(state.songs, index)` with the **whole list**. Inside `playSongs`:
- `songs.map { it.toMediaItem() }` builds ~30k `MediaItem`s (each with metadata + `Uri`) **on the main thread**.
- `controller.setMediaItems(mediaItems, startIndex, 0)` serializes all 30k items across the **Binder IPC** to the playback service — a huge transaction that janks the UI and risks `TransactionTooLargeException`.

**Fix (in order of preference):**
1. Build the `MediaItem` list **off the main thread** (`withContext(Dispatchers.Default)`) before calling the controller.
2. **Window the queue:** set only a slice around `startIndex` (e.g. current ±250) for instant start, then append the rest asynchronously — or set the full list but off-main. Avoids the giant synchronous IPC.
3. Longer term: pass song IDs and let the service resolve `MediaItem`s from the repository, keeping the large payload out of the Binder call from the UI process.

**Implemented option 1 only** (`uiState` now updates immediately on tap, then `songs.map { it.toMediaItem() }` runs on `Dispatchers.Default` before the controller calls). Option 2 (windowing) was deliberately **not** implemented — it changes queue index semantics (`PlayerViewModel`'s `onMediaItemTransition` indexes into `queueSongs` using `controller.currentMediaItemIndex`, which would be window-relative rather than absolute during backfill) and needs on-device verification of ExoPlayer's index-shifting behavior to get right, which wasn't done here.

**Verified:** flung to a song thousands of rows deep in the 34k list and tapped it. Logcat showed **one** `Skipped 32 frames!` warning (~530 ms of jank) around the tap, but **no ANR, no crash, no `TransactionTooLargeException`**, and audio decoding started and continued cleanly afterward. This is a real improvement over the old fully-synchronous main-thread path, but the jank isn't zero — the remaining cost is the Binder marshalling of ~34k `MediaItem`s in the `setMediaItems` call itself, which building off-main doesn't eliminate. **If eliminating that residual ~500ms jank matters, windowing (option 2) is the next step** — flagged here for a future pass, not done now.

### B2. Full queue re-serialized to the DB on every transition — `MusicPlaybackService.kt:90` (`persistPlaybackState`) — ✅ implemented, verified
`persistPlaybackState()` is called from **both** `onIsPlayingChanged` and `onMediaItemTransition` (`:75–86`). Each call walks all `player.mediaItemCount` items, joins ~30k IDs into a JSON string, and upserts a ~200 KB row. So **every** track change and every play/pause triggers a large string-build + DB write — a direct contributor to skip lag.

**Fix:**
- Persist the **full queue only when it actually changes** (i.e. when `setMediaItems`/add is called), not on every transition.
- On transition/play-pause, persist only the cheap fields (`currentSongId`, `position`, `currentQueueIndex`, shuffle/repeat) — split into a lightweight update, or skip re-encoding `queueJson` when the queue is unchanged.
- **Debounce** persistence (e.g. collapse rapid skips into one write after ~500 ms of quiet).

**Implemented as:** `onTimelineChanged` (fires only when the queue's contents actually change) triggers the full `queueJson` rewrite; `onIsPlayingChanged`/`onMediaItemTransition` trigger a new cheap `updatePlaybackProgress()` DAO query (no `queueJson`), debounced 500ms via a cancel-and-relaunch coroutine. `onDestroy` does a synchronous final flush of any pending debounced write.

**Verified:** rapid-tapped Next 3× in a row — no crashes, no `SQLiteException`, UI stayed responsive. Confirmed via DB pull that the persisted `playback_state` row stays valid and queue length-consistent throughout. **One caveat found:** `adb shell am force-stop` (a hard process kill) bypasses `onDestroy` entirely on Android, so the final-flush safeguard doesn't run in that specific scenario — after a force-stop immediately following the 3 skips, the DB still had the *previous* debounce-cycle's state (a still-valid, non-corrupted song a few tracks back), not the very latest skip. This matches the documented tradeoff ("a hard kill mid-debounce could still lose the last update") and isn't a regression — no data corruption occurred, and the app relaunched cleanly. A real user swiping the app from Recents does *not* hit this path (the foreground media service keeps running and `onDestroy` fires normally later); `force-stop` is a harsher edge case than that.

### B3. (Minor) Redundant in-memory sort on every emission — `SongsViewModel.kt:31–38` — not implemented
`SortOrder.TitleAsc` re-sorts a 30k list that the DAO already returns as `ORDER BY title ASC` (`Daos.kt:10`). Already `flowOn(Dispatchers.Default)`, so it's off-main, but it's wasted work on the default order. Skip the re-sort when `order == TitleAsc`. Low priority; not on the hot path for song changes. Skipped in this pass — genuinely low-impact and off the main thread already.

---

## Priority / sequencing

| # | Change | Impact | Effort | Status |
|---|--------|--------|--------|--------|
| A4 | SD worker: populate `artworkPath` from `msRow.albumId` | High (art missing) | Low | ✅ Done, verified |
| A1 | Custom tuned Coil `ImageLoader` | High | Low | ✅ Done, verified |
| B2 | Stop rewriting full queue JSON every transition + debounce | High | Low–Med | ✅ Done, verified |
| B1 | Off-main `MediaItem` build / windowed queue | High | Med | ✅ Off-main done, verified; windowing not done |
| A3 | Explicit small request size + crossfade(false) in lists | Medium | Low | ✅ Done, verified |
| A2 | (Optional) `loadThumbnail` fetcher instead of legacy provider | Medium→**reconsider**, see finding | Med | Not done |
| B3 | Skip redundant sort on default order | Low | Low | Not done |

Recommended first pass: **A4 + A1 + B2** (all low-effort, high-impact) — these should visibly fix both complaints. Then A3 and B1. Hold A2 unless on-device profiling still shows slow cold art loads after A1. *(All of the above except A2/B3 are now done — see Verification results.)*

---

## Files to modify
- `MusicPlayerApp.kt` — implement `ImageLoaderFactory.newImageLoader()` (A1).
- `worker/SdCardScanWorker.kt` — set `artworkPath` from `msRow.albumId` for song + album on the fast path (A4).
- `presentation/PlayerViewModel.kt` — build `MediaItem`s off-main / window the queue in `playSongs` (B1).
- `service/MusicPlaybackService.kt` — split cheap vs. full-queue persistence + debounce (B2).
- `presentation/browse/songs/SongsScreen.kt`, `browse/albums/AlbumsScreen.kt`, `MiniPlayer.kt` — sized `ImageRequest` + `crossfade(false)` (A3).
- `presentation/browse/songs/SongsViewModel.kt` — skip redundant default sort (B3).
- *(A2, optional)* new `data/…/AlbumArtFetcher.kt` + register in the `ImageLoader`.

## Verification plan (as originally written)
1. **Build:** `./gradlew assembleDebug` compiles; `./gradlew testDebugUnitTest` still passes.
2. **A4/A1 — art:** fresh scan via the SD-card flow, then open Songs/Albums. Art now appears for SD-scanned rows (was blank), and populates quickly on first scroll. Kill and relaunch the app — art loads near-instantly from disk cache (validates A1 sizing).
3. **A3 — scroll:** fling the 30k Songs list; frame times stay smooth, no full-res decode stalls (check with Layout Inspector / `adb shell dumpsys gfxinfo`).
4. **B1 — first play:** tap a song deep in the list; playback starts without a visible UI freeze (previously janked building/IPC-ing 30k items). No `TransactionTooLargeException` in logcat.
5. **B2 — skip lag:** rapidly skip several tracks; confirm via logcat that a full `queueJson` write no longer fires on every transition (only on queue changes) and skips feel immediate. Reopen the app — queue/position still restore correctly.

---

## Verification results (2026-08-01, Andrew's S20 FE, Android 13, `com.musicplayer` debug build)

Ran against the real library after triggering "Rescan All Folders" from Settings (SD-card flow): 33,996 tracks post-rescan, 33,916 after the next MediaStore periodic sync (see caveat below — pre-existing, unrelated behavior).

1. **Build:** `assembleDebug` + `testDebugUnitTest` — both pass. ✅
2. **A4/A1 — art:** DB-verified 33,916/33,996 songs got a populated `artworkPath` after the SD rescan (was 0 before the fix). Album grid, mini player, and Songs list all render real cover art wherever the source has it. ✅ — with a real finding: ~40–60% of albums in this library have **no** data behind the legacy `content://media/external/audio/albumart` URI at all (confirmed via direct `adb shell content read` probing — `FileNotFoundException: No album art found`), independent of A1/A4. Those rows are correctly blank, not buggy. See A2 write-up.
3. **A3 — scroll:** flung the Songs list (100+ synthetic fling gestures) deep into the 34k-item library; sized art rendered correctly and cropped throughout, no visual glitches. Did not capture a formal `dumpsys gfxinfo` frame-timing trace. ✅ (qualitative)
4. **B1 — first play:** tapped a song thousands of rows deep. One `Skipped 32 frames!` logcat warning (~530ms) around the tap; no ANR, no crash, no `TransactionTooLargeException`; playback started and decoded cleanly afterward. ✅ better than before, not fully jank-free — windowing (B1 option 2) remains the lever if that residual stutter matters.
5. **B2 — skip lag:** rapid-tapped Next 3×; no crashes/`SQLiteException`; DB stayed valid and consistent. Found that `am force-stop` (not a normal task-swipe) bypasses the `onDestroy` flush and can lose the last <500ms debounce window — documented as an accepted tradeoff, not a regression (see B2 write-up). ✅

### Bugs found along the way — pre-existing, unrelated to art/playback-perf scope, all fixed
Three issues surfaced during testing that predate this work. All three have been fixed and verified on-device rather than just flagged:

- **Mini player sometimes doesn't appear on a fresh cold start.** ✅ fixed 2026-08-01. `PlayerViewModel.syncState()` (called once the `MediaController` connects) copied `isPlaying`/`shuffleEnabled`/`repeatMode`/`duration` from the controller but never initialized `currentSong`/`currentQueueIndex` from the controller's already-restored state — it relied entirely on the `onMediaItemTransition` event, which can fire (during `MusicPlaybackService.restorePlaybackState()`) before the UI's controller finishes connecting. Fixed by resolving `currentSong` and the full queue directly from the controller's state in `syncState()` (`resolveCurrentSong`/`resolveFullQueue`), plus an `onTimelineChanged` listener that retries queue resolution once restoration actually completes (closes a race where the first attempt could see an empty timeline). Verified live: force-stop mid-playback → relaunch → mini player, Now Playing screen, and "Up Next" queue all correctly show the restored song/queue immediately, matching `dumpsys media_session` ground truth.

- **SD-card-only songs get pruned by the next MediaStore scan.** ✅ fixed 2026-08-01. `MusicRepository.scanMediaStore()`'s obsolete-cleanup computed `obsoleteIds` against *all* existing song ids, so songs found exclusively via `SdCardScanWorker` (not present in MediaStore, e.g. very recently added files) got deleted on the next periodic/initial MediaStore sync. Fixed by scoping the cleanup to `source = mediastore` rows only (new `SongEntity.source` column, `SongDao.getSongIdsBySource()`). Verified via direct MediaStore query vs. DB comparison: live `content://media/external/audio/media` query returned 33,916 rows, matching `source='mediastore'` count exactly (33,916), with the 80 (later 38, see below) `source='saf'` rows correctly left untouched.

- **Full-list playback crashes with `ClassNotFoundException: HlsMediaSource$Factory`.** ✅ fixed 2026-08-02. Tapping any song in the full ~34k-song list (or any action that calls `PlayerViewModel.playSongs()` with the whole library) silently failed to play — `dumpsys media_session` showed `state=STOPPED` with no error surfaced in the UI. Root cause, confirmed by pulling and inspecting the on-device DB: `SdCardScanWorker.isAudioMime()` accepted any SAF-reported MIME starting with `"audio/"`, and Android's `MimeTypeMap` classifies `.m3u`/`.m3u8` playlist files as `audio/x-mpegurl` — so 42 playlist files sitting in music folders had been scanned in as if they were songs (all `source=saf`). `Song.toMediaItem()` never set an explicit MIME type, so when one of these `.m3u8` "songs" reached the queue, ExoPlayer's `DefaultMediaSourceFactory` inferred HLS content type from the URI extension and reflectively loaded `androidx.media3.exoplayer.hls.HlsMediaSource$Factory` — a class this app doesn't ship (only depends on `media3-exoplayer` core, not the HLS extension). The resulting `ClassNotFoundException` is thrown synchronously inside `ExoPlayerImpl.createMediaSources()`, which aborts `setMediaItems()` for the *entire* batch, not just the one bad item — so any full-list play action failed outright. Fixed three ways:
  1. `SdCardScanWorker.isAudioMime()` now excludes `m3u`/`m3u8`/`pls`/`cue` extensions regardless of reported MIME type, so future scans don't re-introduce playlist files as songs.
  2. `Song.toMediaItem()` now sets an explicit audio MIME type (from a file-extension map) on every `MediaItem`, so `DefaultMediaSourceFactory` never falls through to extension-based content-type sniffing at all — defends against this whole class of bug, not just `.m3u8`.
  3. One-time cleanup: `SongDao.deleteNonAudioPlaylistFiles()`, run at the start of every `SdCardScanWorker.doWork()`, purges any already-scanned playlist-file rows from prior scans.

  Verified on-device: DB pre-fix had exactly 42 `.m3u`/`.m3u8` rows (all `source=saf`); a rescan after the fix dropped the total from 33,996 → 33,954 tracks (`source=saf` 80 → 38, `source=mediastore` unchanged at 33,916) with zero playlist-file rows remaining. Tapped a song at the top of the full 33,954-song Songs list to trigger `playSongs()` with the entire library: no `ClassNotFoundException`/`ExoPlaybackException` in logcat, and `dumpsys media_session` confirmed `state=PLAYING(3)` with `queueTitle=null, size=33954` and correct metadata — full-list playback now works end-to-end.
