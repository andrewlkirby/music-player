# Resume-where-I-left-off + startup optimization

## Context

The user wants the app to reopen "as if it were never closed" — same song, same
playback position, same shuffle/repeat, and to **resume playing**. They also want
faster startup.

Good news from exploration: most of this **already works**. `MusicPlaybackService`
persists a single-row `playback_state` table (current song, position, shuffle,
repeat, full queue, queue index) and restores it on startup. Theme, background
image, opacity, background position, watched folders, playlists and favorites also
already survive restart.

What's missing / worth improving, per the user's chosen scope (auto-resume playing;
leave equalizer alone; startup = quick wins **plus** Songs-list paging):

1. **Restore currently starts paused** — it prepares but never calls `play()`.
2. **Position isn't saved periodically while a song plays** — only on play/pause,
   track change, shuffle/repeat toggle, and a flush on clean service destroy. A hard
   process kill mid-song can lose the last position. This matters more once we
   auto-resume.
3. **Startup**: the Songs start screen loads the *entire* songs table and sorts every
   row in memory on each launch; a redundant `refresh()` re-runs that work; and the
   playback queue is restored one song at a time instead of in a batch.

**Design decision — no database schema change.** The DB uses
`fallbackToDestructiveMigration()`, so bumping the schema version would wipe all
tables, including user-created **playlists and favorites** (which the media scan does
*not* rebuild). To avoid that, this plan deliberately adds **no columns and no
indexes**: auto-resume is done by calling `play()` unconditionally on restore, and
paging sorts at query time. (A collated `title` index for faster deep-scroll on very
large libraries is possible later, but only via a proper non-destructive `Migration`.)

---

## Part 1 — Auto-resume playback on reopen

File: `app/src/main/kotlin/com/musicplayer/service/MusicPlaybackService.kt`

In `restorePlaybackState()` (currently ends at `player.prepare()` with a
"Don't autoplay" comment, ~lines 180-182), start playback after prepare:

```kotlin
player.prepare()
player.play()   // auto-resume where the user left off
```

- Audio focus is already handled (`ExoPlayer.Builder(...).setAudioAttributes(attrs, handleAudioFocus = true)` in `di/AppModules.kt`), so this is safe.
- This runs inside the existing `serviceScope.launch { ... }` after media items + seek
  position are set, so it resumes the correct track at the correct position.
- Update the now-stale `// Don't autoplay on restore` comment.

## Part 2 — Make the saved position reliable

File: `app/src/main/kotlin/com/musicplayer/service/MusicPlaybackService.kt`

Add a lightweight periodic position write while playing, so a process kill loses at
most a few seconds. Reuse the existing `persistProgress()` (the cheap UPDATE that
doesn't touch `queueJson`).

- Add a `private var periodicPersistJob: Job?`.
- In the `Player.Listener.onIsPlayingChanged` callback, start the loop when
  `isPlaying == true` and cancel it when `false`:

```kotlin
override fun onIsPlayingChanged(isPlaying: Boolean) {
    schedulePersistProgress()
    if (isPlaying) startPeriodicPersist() else periodicPersistJob?.cancel()
}
```

```kotlin
private fun startPeriodicPersist() {
    periodicPersistJob?.cancel()
    periodicPersistJob = serviceScope.launch {
        while (true) {
            delay(10_000L)          // ~10s cadence
            persistProgress()
        }
    }
}
```

- Cancel `periodicPersistJob` in `onDestroy()` alongside the existing
  `progressPersistJob?.cancel()` (the synchronous `runBlocking { persistProgress() }`
  flush already there still captures the final position).

## Part 3 — Startup quick wins

**3a. Batch the queue restore** — `MusicPlaybackService.restorePlaybackState()`
currently does `songIds.mapNotNull { repository.getSongById(it) }` (one DB round-trip
per song). Replace with the existing order-preserving batch query:

```kotlin
val songs = repository.getSongsByIds(songIds)   // already implemented, chunked IN queries
```

`MusicRepository.getSongsByIds()` (lines 61-66) already preserves the input order and
handles SQLite's bind-variable limit.

**3b. Remove the redundant refresh** — delete the `LaunchedEffect(Unit) { viewModel.refresh() }`
in `SongsScreen.kt` (lines 37-39) and the now-unused `refresh()` in `SongsViewModel.kt`
(lines 51-54). The flow already emits without it.

## Part 4 — Page the Songs list

Goal: stop loading + sorting the whole songs table on every launch; load only what's
on screen. This is the largest startup + memory win for big libraries.

**Dependencies** — add to `gradle/libs.versions.toml` and `app/build.gradle.kts`:
- `androidx.room:room-paging` (matches Room 2.6.1)
- `androidx.paging:paging-compose` (3.3.x — pulls in paging-runtime)

**DAO** — `data/local/dao/Daos.kt`, in `SongDao`, add `PagingSource` queries per sort
order (returning `PagingSource<Int, SongEntity>`), e.g.:

```kotlin
@Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
fun pagingTitleAsc(): PagingSource<Int, SongEntity>
// ...Desc, artist ASC, dateAdded DESC, playCount DESC
```
Also add a reactive count for the header:
```kotlin
@Query("SELECT COUNT(*) FROM songs") fun getSongCountFlow(): Flow<Int>
```
And a suspend, order-preserving full list used only to build the play queue on tap
(see below), e.g. `pagedListForPlayback` variants or one `@RawQuery`-free set of
`suspend fun sortedSongsTitleAsc(): List<SongEntity>` etc. Prefer discrete functions
over `@RawQuery` to avoid hand-built SQL.

**Repository** — `data/repository/MusicRepository.kt`:
- `fun pagedSongs(order: SortOrder): PagingSource<Int, SongEntity>` selecting the right
  DAO query (map the entity→domain inside the Pager flow, not in the DAO).
- `suspend fun getSortedSongs(order: SortOrder): List<Song>` for the tap handler.
- `fun getSongCount(): Flow<Int>`.

**ViewModel** — `SongsViewModel.kt`:
- Replace the `stateIn` `uiState` list with:
  ```kotlin
  val songs: Flow<PagingData<Song>> = sortOrder.flatMapLatest { order ->
      Pager(PagingConfig(pageSize = 100, enablePlaceholders = false)) {
          repository.pagedSongs(order)
      }.flow.map { it.map(SongEntity::toDomain) }
  }.cachedIn(viewModelScope)
  ```
- Expose `sortOrder` and `totalCount` (from `getSongCount()`) as small separate flows.
- Add `suspend fun buildQueue(): List<Song> = repository.getSortedSongs(sortOrder.value)`
  for playback.
- Delete `refresh()` (Part 3b).

**Screen** — `SongsScreen.kt`:
- Collect via `val songs = viewModel.songs.collectAsLazyPagingItems()`; render with
  `items(songs.itemCount)` / paging's `items(...)`.
- Drive the loading spinner from `songs.loadState.refresh is LoadState.Loading`.
- **Play-on-tap must not use the paged (partial) list.** Replace
  `onClick = { playerViewModel.playSongs(state.songs, index) }` with a coroutine that
  fetches the full sorted list, then starts at the tapped song:
  ```kotlin
  val scope = rememberCoroutineScope()
  onClick = {
      scope.launch {
          val full = viewModel.buildQueue()
          val start = full.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
          playerViewModel.playSongs(full, start)
      }
  }
  ```
  `PlayerViewModel.playSongs` already updates the mini-player immediately and builds
  MediaItems off-main, so the brief fetch stays smooth.

**Note / tradeoff:** paging uses LIMIT/OFFSET with a query-time `ORDER BY` (no index,
per the schema decision). This is off-main via Room and fine for typical libraries;
very deep scrolling in a 30k-song list re-sorts per page. If that ever shows, add a
collated `title` index through a proper `Migration` — out of scope here.

## Part 5 — One-tap shuffle on the Songs tab (+ consistency fix)

The user's current flow is Songs → tap a song → open Now Playing → toggle shuffle →
skip: 3-4 taps. Shuffle is currently only reachable from `NowPlayingScreen`
(`onToggleShuffle`, line ~251). Meanwhile `PlaylistsScreen` (line 282) has a
"Shuffle play" button that uses a *different* mechanism — `playSongs(orderedSongs.shuffled(), 0)`
with ExoPlayer's `shuffleModeEnabled` left **off** — so the Now Playing shuffle icon
wrongly shows OFF. We'll unify on ExoPlayer's real shuffle mode.

**5a. Add `shufflePlay(songs)` to `PlayerViewModel` — instant start, background fill.**

Efficiency is the point here. The existing `playSongs` maps MediaItems off-main but
still ships the *entire* 30k-item list in one `setMediaItems` before the first note
plays, so time-to-first-sound grows with library size and it's one very large
`MediaController`→service IPC transaction. For shuffle we start on the random track
immediately, then stream the rest in the background:

```kotlin
fun shufflePlay(songs: List<Song>) {
    if (songs.isEmpty()) return
    queueSongs.clear(); queueSongs.addAll(songs)
    val start = songs.indices.random()                 // random first track
    // UI (mini player / now playing) updates instantly.
    _uiState.update { it.copy(queue = songs, currentQueueIndex = start,
                              currentSong = songs[start], shuffleEnabled = true) }
    viewModelScope.launch {
        // 1) Start playback on ONLY the first track → first sound is instant,
        //    independent of library size.
        controller?.shuffleModeEnabled = true          // standard player command; no custom cmd
        controller?.setMediaItems(listOf(songs[start].toMediaItem()), 0, 0)
        controller?.prepare(); controller?.play()
        // 2) Map + append the remaining tracks off-main, in chunks, so each IPC
        //    transaction stays small (avoids jank / TransactionTooLarge on huge
        //    libraries). Shuffle order absorbs the appended items.
        val rest = songs.filterIndexed { i, _ -> i != start }
        rest.chunked(500).forEach { chunk ->
            val items = withContext(Dispatchers.Default) { chunk.map { it.toMediaItem() } }
            controller?.addMediaItems(items)
        }
    }
}
```
- **Time-to-first-sound is constant** regardless of whether the library is 50 or
  50,000 songs.
- Setting `shuffleModeEnabled` then the item changes fire the service's
  `onTimelineChanged` → `persistFullPlaybackState()`, so shuffle state is saved and
  survives restart (ties into Parts 1-2). (The chunked adds each re-persist; that's the
  existing debounced/full-state write path and is fine.)
- Same pattern can later be applied to `playSongs` (tap-a-song) to make large-list
  playback start instant too — noted as an optional follow-up, not required here.

**5b. Add a Shuffle action to the Songs top bar** (`SongsScreen.kt`, next to the
existing Search/Sort `IconButton`s):
```kotlin
IconButton(onClick = {
    scope.launch { playerViewModel.shufflePlay(viewModel.songsForShuffle()) }
}) { Icon(AppIcons.Shuffle, "Shuffle all") }
```
Because shuffle randomizes order, use an **unsorted** full fetch rather than the
sorted `buildQueue()` — no `ORDER BY` work. Add to `SongDao`/repository:
```kotlin
@Query("SELECT * FROM songs") suspend fun getAllSongsList(): List<SongEntity>
```
`SongsViewModel.songsForShuffle()` = `repository.getAllSongsList()` mapped to domain on
`Dispatchers.Default`. One tap now = shuffle the whole library and start playing.

**5c. Consistency fix (recommended):** change `PlaylistsScreen.kt` line 282 from
`playerViewModel.playSongs(orderedSongs.shuffled(), 0)` to
`playerViewModel.shufflePlay(orderedSongs)` so both shuffle entry points use real
shuffle mode and the Now Playing icon reflects it.

Note: the user's existing "tap a song, then enable shuffle" path already behaves
correctly (ExoPlayer keeps the tapped song, then plays random) — no change needed
there; Part 5 just makes it a single tap.

---

## Critical files

| File | Change |
|---|---|
| `service/MusicPlaybackService.kt` | auto-resume `play()`; periodic position save; batch queue restore |
| `presentation/PlayerViewModel.kt` | add `shufflePlay(songs)` |
| `presentation/browse/songs/SongsViewModel.kt` | `PagingData` flow, count flow, `buildQueue()`, drop `refresh()` |
| `presentation/browse/songs/SongsScreen.kt` | `collectAsLazyPagingItems`, paged list, coroutine play-on-tap, Shuffle top-bar action, drop `refresh()` effect |
| `presentation/browse/playlists/PlaylistsScreen.kt` | route "Shuffle play" through `shufflePlay()` (consistency) |
| `data/local/dao/Daos.kt` | `PagingSource` queries per sort, count flow, sorted-list queries, `getAllSongsList()` |
| `data/repository/MusicRepository.kt` | `pagedSongs`, `getSortedSongs`, `getSongCount`, `getAllSongsList` |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | add `room-paging`, `paging-compose` |

No entity/schema changes; DB stays at version 3.

## Verification

1. **Build:** `./gradlew assembleDebug` (Windows: `.\gradlew.bat assembleDebug`).
   Unit tests: `./gradlew testDebugUnitTest` (existing `MusicRepositoryTest`).
2. **Resume playing:** play a song to ~1:30, swipe the app away, reopen → same song,
   ~same position, and it is **playing** (not paused). Shuffle/repeat match.
3. **Position reliability:** play a song, force-stop the app from Settings mid-track,
   reopen → resumes within ~10s of where it was.
4. **Shuffle/repeat/queue:** toggle shuffle on, build a queue, reopen → shuffle still
   on, queue and "up next" intact.
5. **Startup / paging:** on a large library, confirm the Songs list appears quickly,
   scrolls smoothly, the "N tracks" count is correct, each sort order works, and
   tapping a song plays it with the rest of the sorted library queued after it.
6. **Shuffle convenience + efficiency:** from the Songs tab, tap the new Shuffle action
   once → playback starts on a random song **immediately** (first sound should be near
   instant even on a very large library, since only the first track is loaded before
   play), with the whole library filling into the queue, the Now Playing shuffle icon
   lit, and skipping next staying random. Test on the largest available library and
   confirm no UI freeze / ANR at shuffle start. Confirm shuffle state persists across a
   restart (Part 1). Verify the Playlists "Shuffle play" button now also lights the
   shuffle icon.
7. **Regression:** theme, background image, opacity, watched folders, playlists and
   favorites all still restore as before; tapping a single song (shuffle off) still
   plays it and continues in sorted order.
