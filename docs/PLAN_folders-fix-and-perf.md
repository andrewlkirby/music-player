# Fix Folders Tab + High-Impact Performance Wins

## Context

Two goals for this Jetpack Compose / MVVM / Room music player:

1. **Bug:** The Folders tab shows four bogus folders — `stor`, `stora`, `storag`, `storage` — instead of a browsable directory tree. Root cause is a single broken SQL expression that returns a *prefix* of each path instead of its parent directory.
2. **Performance:** Apply a focused set of high-impact optimizations (Room indices, off-main-thread work, a Folders O(n²)/leak fix, gating the position-update loop, a folder song-count correctness bug, and a scan-worker query that fails on large libraries). Scope confirmed as **high-impact wins only**; DB schema change handled via a **version bump + destructive rebuild** (re-scan repopulates; playlists/favorites/play-counts are wiped — acceptable pre-release).

---

## Part 1 — Folders tab bug (primary)

### Root cause
`app/src/main/kotlin/com/musicplayer/data/local/dao/Daos.kt:42`

```kotlin
@Query("SELECT DISTINCT substr(path, 1, length(path) - length(replace(path, '/', '')) ) as folder FROM songs ORDER BY folder ASC")
suspend fun getAllFolderPaths(): List<String>
```

`length(path) - length(replace(path, '/', ''))` **counts the `/` characters**; that count is misused as the `substr` *length*, so the query returns the first N chars of the path (N = slash count). For paths under `/storage/emulated/0/...` this yields `/stor`, `/stora`, `/storag`, `/storage`; downstream `dirPath.split("/").last()` (`FoldersScreen.kt:87`) strips the slash to the names seen. `DISTINCT` collapses each depth into one bogus entry.

### Fix
Replace the `substr(...)` expression with SQLite's dependency-free parent-directory idiom (strip the basename, then the trailing slash):

```kotlin
@Query("SELECT DISTINCT RTRIM(RTRIM(path, REPLACE(path, '/', '')), '/') as folder FROM songs ORDER BY folder ASC")
suspend fun getAllFolderPaths(): List<String>
```

- Inner `RTRIM(path, REPLACE(path,'/',''))` removes trailing non-slash chars (the filename), stopping at the last `/` → `/storage/emulated/0/Music/`.
- Outer `RTRIM(..., '/')` drops the trailing slash → `/storage/emulated/0/Music`, matching how `FoldersScreen.navigateTo` builds `fullPath` (no trailing slash) and avoiding phantom empty-named leaf folders. Everything downstream in `FoldersScreen.kt` already works correctly once real directory paths are returned.

### Status: implemented and verified on device
Fix applied to `Daos.kt:42`. Verified on Andrew's S20 FE (`com.musicplayer`, debug build): Folders tab now shows a real tree (`storage` → `6566-3438` / `emulated` → ...), breadcrumb navigation works, no crashes. Build (`compileDebugKotlin`, `assembleDebug`) succeeds.

### Related bug found during device verification — folder song counts are wrong (fold into Part 2)
`FolderItem.songCount` (`FoldersScreen.kt:76-93`) is mislabeled — it's not a track count:

```kotlin
.map { dirPath ->
    val count = allPaths.count { it.startsWith(dirPath) }   // counts entries in allPaths...
    FolderItem(..., songCount = count)                       // ...but allPaths is DISTINCT FOLDER paths, not songs
}
```

`allPaths` is one row per **distinct directory** (from `getAllFolderPaths()`), so `count` is the number of descendant directories, not songs. Confirmed by pulling the live DB off the S20 FE and querying directly: the `6566-3438` folder showed "3982 tracks" in the UI but actually contains **33,914** song rows (`path LIKE '%/6566-3438/%'`); 3982 is the count of distinct subfolders under it. (`storage` showed 3984 = 3982 + 2, the `emulated` folder's count — confirming the label is literally a folder tally, not a song tally, since it partitions cleanly along `allPaths` rather than actual track rows.)

**Fix (P5 below):** compute the real per-folder song count from the `songs` table (e.g. `SELECT COUNT(*) FROM songs WHERE path LIKE :dirPath || '/%'`, the same pattern `getSongsByFolder` already uses) instead of counting `allPaths` entries.

---

## Part 2 — High-impact performance wins (Status: implemented and verified on device)
All P1–P6 items implemented, build/tests pass, and re-verified on Andrew's S20 FE:
- Indices + `user_version=2` confirmed via a pulled DB (`sqlite_master` listing, `pragma_user_version`).
- `MediaScanWorker` now reports `SUCCESS` on first run (previously retried indefinitely on the `deleteObsolete` SQL-variable-limit error).
- Folders tab now shows real per-folder song counts: `storage` = 33916 (matches total), `6566-3438` = 33914 — matching direct DB queries, not the old subfolder tally.
- Position updater confirmed gated: progress bar frozen while paused, resumes advancing on play.
- No crashes/errors in logcat across the full session (aside from a pre-existing, unrelated `libpenguin.so` system warning).

### P1. Add Room indices (biggest DB win)
`app/src/main/kotlin/com/musicplayer/data/local/entities/Entities.kt` — `SongEntity` (lines 8–28) has **no `@Index`**, yet queries filter/sort on `albumId`, `artistId`, `path`, `isFavorite`, `dateAdded`, `playCount` (`Daos.kt:16,19,30,33,36,39`). Add:

```kotlin
@Entity(
    tableName = "songs",
    indices = [
        Index("albumId"), Index("artistId"), Index("path"),
        Index("isFavorite"), Index("dateAdded"), Index("playCount")
    ]
)
data class SongEntity( ... )
```

Also add `Index("artistId")` to `AlbumEntity` (used by `Daos.kt:79`).

**Schema change → bump DB version:** `app/src/main/kotlin/com/musicplayer/data/local/MusicDatabase.kt:17` change `version = 1` to `version = 2`. `AppModules.kt:26` already calls `.fallbackToDestructiveMigration()`, so the DB rebuilds and `MediaScanWorker` repopulates on next launch — no migration code needed.

### P2. Move sorting + entity→domain mapping off the main thread
- `MusicRepository.kt` — the domain-mapping flows (e.g. `getAllSongs`, `getAllAlbums`, `getSongsByFolder` at `:50`, and siblings `:41–83`) do `.map { list.map { it.toDomain() } }` with **no `flowOn`**, so `toDomain()` (two `Uri.parse` calls each — `Mappers.kt:19,24`) runs on the collector (Main). Append `.flowOn(Dispatchers.Default)` to these hot mapping flows.
- `SongsViewModel.kt:28–45` re-sorts the whole list inside the flow `map` on `viewModelScope` (Main), and `SortOrder.TitleAsc` **duplicates** the DAO's existing `ORDER BY title ASC` (`Daos.kt:10`). Wrap the sort/map body in `flowOn(Dispatchers.Default)` and skip re-sorting when the order already matches the DAO's default. Same pattern in the Albums ViewModel (`AlbumsScreen.kt` ~:45–55).

### P3. Fix `FoldersViewModel.navigateTo` — O(n²) scan + leaked collector
`app/src/main/kotlin/com/musicplayer/presentation/browse/folders/FoldersScreen.kt`
- **Leaked collector (`:102–127`):** `songsFlow.collect { ... return@collect }` — `return@collect` exits only the lambda, it does **not** cancel the collection, so every navigation leaves a live, never-cancelled collector. Replace with a single-shot read: `val songs = songsFlow.first()` (import `kotlinx.coroutines.flow.first`) and drop the `return@collect`.
- **O(n²) (`:76–92`):** building `subDirs` runs `allPaths.count { it.startsWith(dirPath) }` nested inside a map over candidate dirs. Compute child directories in one pass (e.g. group `allPaths` by next segment after `prefix` and size each group) instead of a nested scan per candidate.

### P4. Gate the 500ms position updater on playback
`app/src/main/kotlin/com/musicplayer/presentation/PlayerViewModel.kt:132–140` — `while (true) { delay(500); ... }` runs forever, even when paused or with no controller, causing needless recompositions/wakeups. Only poll while `controller?.isPlaying == true` (e.g. check inside the loop and skip the state update when not playing, or drive the loop from an `isPlaying` signal).

### P5. Fix folder song counts (found during device verification)
`FoldersScreen.kt:76–93` — replace the `allPaths.count { it.startsWith(dirPath) }` folder tally with a real song count. Add a DAO method:

```kotlin
@Query("SELECT COUNT(*) FROM songs WHERE path LIKE :folderPath || '/%'")
suspend fun getSongCountInFolder(folderPath: String): Int
```

and call it (via the repository) when building each `FolderItem`, instead of counting `allPaths` entries. Naturally folds into the same pass as P3 since both touch `navigateTo`'s folder-building logic.

### P6. Fix `deleteObsolete` — exceeds SQLite's bound-variable limit on large libraries
`Daos.kt:60` — `DELETE FROM songs WHERE id NOT IN (:validIds)` binds one `?` per id. SQLite's default limit is ~999 bound variables; with Andrew's ~34k-song library this always fails (`SQLiteLog: (1) too many SQL variables`, confirmed in logcat during device testing), so `MediaScanWorker` retries indefinitely (every ~2 min) and stale rows for deleted files are never pruned. Fix by batching the delete — chunk `validIds` into groups of e.g. 900 and issue one `DELETE ... WHERE id NOT IN (chunk)` won't work directly (NOT IN needs the *complement*); instead switch to deleting by explicit obsolete-id chunks: have the repository compute `obsoleteIds = existingIds - validIds` and call a chunked `deleteByIds(ids: List<Long>)` (`DELETE FROM songs WHERE id IN (:ids)`, chunked to ≤900 per call) rather than a single `NOT IN` query.

---

## Files to modify
- `data/local/dao/Daos.kt` — fix `getAllFolderPaths` query (line 42, **done**); add `getSongCountInFolder` (P5); replace `deleteObsolete` with chunked `deleteByIds` (P6).
- `data/local/entities/Entities.kt` — add `@Index`es to `SongEntity` + `AlbumEntity`.
- `data/local/MusicDatabase.kt` — `version = 2`.
- `data/repository/MusicRepository.kt` — add `flowOn(Dispatchers.Default)` to domain-mapping flows; add folder-count + chunked-delete wrappers; compute `obsoleteIds` in the scan path that currently calls `deleteObsolete`.
- `presentation/browse/songs/SongsViewModel.kt` (+ Albums ViewModel in `AlbumsScreen.kt`) — off-thread sort, drop redundant sort.
- `presentation/browse/folders/FoldersScreen.kt` — single-shot flow read, one-pass child grouping, real per-folder song counts.
- `presentation/PlayerViewModel.kt` — gate position loop on `isPlaying`.

## Verification
1. **Build:** `./gradlew assembleDebug` (or Android Studio build) — must compile; confirm Room accepts the new indices/version and KSP regenerates cleanly. *(Already passing for Part 1.)*
2. **Folders tab (manual, on device):** ✅ done — verified on Andrew's S20 FE: real tree (`storage → 6566-3438/emulated → ...`), breadcrumb nav, no crashes.
3. **Folder counts:** after P5, re-check the same device — `6566-3438` should show its true song count (33,914, not 3982); parent/child counts should no longer look like a folder tally.
4. **No collector leak:** navigate in/out of several folders repeatedly; behavior stays correct and responsive (previously each navigation leaked a collector).
5. **Perf sanity:** with the 34k-track library, scrolling Songs/Albums and switching sort orders stays smooth (mapping/sort now off-main); confirm album art still loads.
6. **Position loop:** play a track (position advances), pause (updates stop), resume (updates continue).
7. **Scan/delete fix:** delete or move a scanned file, trigger a rescan, confirm via logcat that `SQLiteLog: too many SQL variables` no longer appears and the obsolete row is actually removed (previously this error repeated every ~2 min per the S20 FE logcat capture).
8. **Tests:** run `./gradlew testDebugUnitTest`; existing `MusicRepositoryTest` should still pass.
