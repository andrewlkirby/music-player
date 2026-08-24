# Windowed playback queue (fix skip-next stall during full-library shuffle)

## Context

Tonight's investigation started from: "when I press next song while in full-library
shuffle play, the time/position bar lags." Live on-device testing (34,954-song
library) traced this to two real bugs, both now fixed and verified:

1. A dead legacy `content://media/external/audio/albumart` URI stored for every song
   (even albums with no art), which Media3's session-compat layer synchronously
   retried-and-failed on every track transition. Fixed by checking existence once per
   album at scan time and storing `null` when there's no real art.
2. `PlayerViewModel.shufflePlay()`'s in-memory `queueSongs` list didn't match the
   order actually loaded into the controller, causing an avoidable DB round-trip on
   every transition.

After both fixes, a clean A/B test proved a **third, deeper cause remains**: skip-next
on a 3-song queue produces zero jank (no dropped frames at all), but the *same action*
on the full 34,954-song shuffled queue still stalls the main thread for ~800-1000ms
per skip, confirmed via `Davey!`/`Choreographer` logs and matching `com.musicplayer`
GC churn (11-20MB freed per transition) right at the stall. This is Media3's
`MediaController`↔`MediaSession` state sync, which scales with total loaded `Timeline`
size — the same architectural root cause already flagged (and accepted as residual)
for the cold-start restore hitch earlier in this session, now confirmed to also hit
ordinary skip-next once the *entire* library is loaded into the live queue at once.

The user chose to pursue a real fix rather than accept this residual: stop handing
ExoPlayer/MediaSession a live `Timeline` with all 34k items. Load only a bounded
**window** around the current position, and manage the full play order as app-level
state instead of Player state.

## Phase 0 — Validation spike (do this FIRST; gates everything below)

Before committing to the windowing rewrite, confirm the premise and check for a
cheaper fix. Two quick, low-risk probes:

1. **Confirm loaded-queue size is the cause.** Temporarily cap the shuffle fill so
   only ~200 items are ever loaded into the live queue (one-line change to the
   existing chunked fill in `PlayerViewModel.shufflePlay()` / the service restore
   fill — e.g. stop after the first chunk). Install, start a full-library shuffle,
   and repeat tonight's skip-next measurement (`logcat` Choreographer/`Davey!` +
   `dumpsys media_session` queue size). Expected if the diagnosis is right: the
   ~800-1000ms per-skip stall disappears (or drops dramatically) with the small
   loaded queue. Revert the temporary cap after measuring.
2. **Check for a cheaper knob.** Investigate (read-only: Media3 source/docs) whether
   `MediaSession` exposes any way to cap or suppress the auto-published legacy
   `MediaSessionCompat` queue (the `size=33954` seen in `dumpsys media_session`),
   which the legacy/AVRCP layer appears to re-diff per transition. If such a knob
   exists, it could be a far smaller fix than the full windowing rewrite.

**Decision gate:** proceed to the windowing rewrite only if (1) confirms loaded-queue
size is the cause AND (2) finds no cheaper knob. Report findings and revise this plan
before implementing the rewrite.

### Phase 0 results (done)

Both probes ran on-device against the real 34,954-song library:

1. Bytecode inspection of `media3-session-1.4.1.aar` confirmed `MediaSessionLegacyStub
   .isQueueEnabled()` gates the legacy `MediaSessionCompat.setQueue()` re-publish
   behind `Player.COMMAND_GET_TIMELINE` being available (checked on the `PlayerWrapper`,
   which forwards to whatever `Player` is given to `MediaSession.Builder`).
2. Spike: wrapped the ExoPlayer in a `ForwardingPlayer` denying `COMMAND_GET_TIMELINE`
   for the session only. Result: **zero** `Davey!`/`Choreographer` jank across 15
   skip-next taps (incl. a rapid 10-tap burst) on the full unwindowed 33,954-item
   queue — confirms the legacy queue re-diff was the dominant cost, and that denying
   the command alone is a working (much smaller) fix.
3. Side effect found: denying `COMMAND_GET_TIMELINE` also masks `PlayerInfo.timeline`
   for the app's OWN `MediaController`, so `ctrl.currentMediaItem`/
   `currentMediaItemIndex` return nothing on initial connect — cold-start restore's
   `syncState()` → `resolveCurrentSong()` got nothing and the mini-player never
   appeared, even though the service was genuinely playing (confirmed via `dumpsys
   media_session`). Live in-session transitions were unaffected (the new `MediaItem`
   rides along in the transition event itself) — only the "hydrate state right after
   connecting" path breaks. Fixable with one custom session command answered from the
   service's own un-gated `player.currentMediaItem`/`currentMediaItemIndex`, but that
   fix was not implemented — the user chose to proceed with the full windowed-queue
   rewrite below instead (for the architectural benefit of never loading the full
   34k-item queue into ExoPlayer at all, not just the jank fix). The spike change was
   reverted from `MusicPlaybackService.kt` before starting the rewrite.

**Decision:** proceed with the windowing rewrite (user's explicit choice, offered the
smaller fix as an alternative first).

## Review findings to fold into the design before/at rewrite time

These gaps were found reviewing the first draft and MUST be addressed in the rewrite:

- **Boundary interception needs a `ForwardingPlayer`.** A controller/notification/
  Bluetooth `seekToNextMediaItem()` at the last loaded window item just no-ops —
  there is no transition event for the "safety net" to hook. Wrap the injected
  `ExoPlayer` in a `ForwardingPlayer` (given to `MediaSession.Builder` in
  `service/MusicPlaybackService.kt`; player is provided in `di/AppModules.kt`)
  overriding `seekToNext*`/`seekToPrevious*`/`hasNextMediaItem`/`hasPreviousMediaItem`
  to consult the virtual queue and trigger a window load at edges.
- **Repeat mode.** `REPEAT_ALL` over a window would loop the *window*, not the full
  queue, and at true end-of-queue must wrap to virtual index 0. Keep ExoPlayer's
  native repeat OFF while windowed and implement `REPEAT_ALL` wrap at the virtual
  layer; `REPEAT_ONE` is unaffected.
- **`persistProgress()` (not just `persistFullPlaybackState()`) must persist the
  VIRTUAL index.** Both paths currently write `player.currentMediaItemIndex`, which
  becomes window-local once windowed. Write `windowStartOffset +
  player.currentMediaItemIndex` in both, or restore jumps to the wrong song.
- **"Up next" after cold-start restore.** `PlayerViewModel.resolveFullQueue()` rebuilds
  `state.queue` from `controller.currentTimeline`, which is now only the window. Decide:
  accept a window-only up-next after restore (and fix the current-index highlight math),
  or have the service expose the full ordered list to the ViewModel.
- **Queue handoff mechanism (prefer over the command Bundle).** Service and ViewModel
  are the SAME process (no `android:process` in the manifest) with a shared
  `@Singleton MusicRepository`. Stage the ordered queue in a new `@Singleton
  QueueHolder` and have the command just signal "load staged queue" — no ~270KB
  Binder payload. "Shuffle all" needs no payload at all (service calls
  `repository.getAllSongsList()` directly).

## Design (windowing rewrite — contingent on Phase 0)

**Virtual queue.** The full ordered list of song IDs for "what's playing and in what
order" (`virtualQueueIds: List<Long>`) becomes the source of truth, tracked in
`MusicPlaybackService` alongside `virtualQueueIndex: Int` (position within that list —
*not* the same as `player.currentMediaItemIndex`, which is now just the position
within the loaded window). This is exactly what `PlaybackStateEntity.queueJson` +
`currentQueueIndex` already persist today — **no schema change** — only what's *live*
in ExoPlayer changes.

For shuffle, the ID list is randomized **once**, up front, in memory (Fisher-Yates
over up to ~34k `Long`s — a few ms, no `MediaItem`/metadata objects involved). ExoPlayer's
own `shuffleModeEnabled` is never used for windowed queues — always `false` — because
`virtualQueueIds` already encodes final playback order (shuffled or sorted); a second,
independent shuffle over just the loaded window would make "next" jump inconsistently
and break "previous"/history. `next`/`previous` become `virtualQueueIndex ± 1` by
construction, resolved via `player.seekTo(windowLocalIndex, 0)` — no reliance on
ExoPlayer's shuffle traversal at all.

**Window.** Service loads `WINDOW_BEHIND` items before the current position and
`WINDOW_AHEAD` after it (starting points: 20 / 150, tuned empirically like the
existing chunk-size constants — see `PlayerViewModel.SHUFFLE_FILL_CHUNK_SIZE`'s
comment for the precedent). On each real track transition, if fewer than
`SHIFT_TRIGGER_REMAINING` (start: 30) loaded items remain ahead, extend the window:
resolve the next batch of IDs to `Song`s (reuse `MusicRepository.getSongsByIds`,
already batched/order-preserving), build `MediaItem`s off-main
(`Dispatchers.Default`, existing pattern), `player.addMediaItems(...)` at the end,
then trim from the behind side down to `WINDOW_BEHIND` via
`player.removeMediaItems(0, trimCount)`, adjusting the window's start offset. Same
logic mirrored for backward navigation near the start of the window. An arbitrary
jump (restore-on-launch, a fresh `shufflePlay`/`playSongs`, or a future "tap a song in
up next") is just "load a window centered on index X" — one shared helper function
used by all of these, replacing today's bespoke `restorePlaybackState()` chunked-fill.

**Safety net.** If the user skips faster than a background extend can keep up (taps
past the last loaded item), detect via `player.hasNextMediaItem()` false but
`virtualQueueIndex + 1 < virtualQueueIds.size` true, and synchronously load just that
one next song first (same "instant single item" trick already used for restore/shuffle
start) before continuing the batch extend.

**Cancellation.** Today's `restoreFillJob` cancel-on-`onSetMediaItems` hack (added
earlier tonight to fix a queue-corruption race) generalizes and simplifies: since
*all* queue-affecting operations (restore, shufflePlay, playSongs, window-shift) now
live service-side behind one `queueLoadJob: Job?`, any new operation just cancels the
previous one before starting — one job reference instead of the previous two-sided
dance between ViewModel-driven controller calls and service-driven restore fill.

**Controller → service handoff.** `PlayerViewModel.shufflePlay()`/`playSongs()` stop
building `MediaItem`s and calling `controller.setMediaItems()`/`addMediaItems()`
directly (today's `playSongs` even sends the *entire* list in one un-chunked call —
a latent bug this redesign also fixes for free, e.g. tapping a song from a large
sorted list). Instead they resolve the target `List<Song>` (unchanged — still needed
for the "up next" UI) and send the ordered ID list + start index + shuffle flag to
the service via a new custom command (`CUSTOM_COMMAND_SET_QUEUE`, same established
pattern as `CUSTOM_COMMAND_TOGGLE_SHUFFLE`/`CUSTOM_COMMAND_SET_SLEEP_TIMER` in
`MediaSessionCallback.onCustomCommand`). Payload is a `LongArray` of IDs + start
index (~270KB worst case for the full library, well under Binder's ~1MB transaction
limit, and it's a single transaction instead of dozens) — sizing will be confirmed
live on-device during implementation.

**Shuffle-state UI flag.** Since `player.shuffleModeEnabled` is no longer touched,
`shuffleEnabled` becomes a plain app-level boolean: set optimistically in
`PlayerViewModel`'s `_uiState` when `shufflePlay`/`playSongs`/`toggleShuffle` are
called (same optimistic-update pattern already used elsewhere in this file), sent to
the service via the custom command, and persisted from service state instead of read
back from `Player.Listener.onShuffleModeEnabledChanged`. Toggling shuffle OFF mid-
playback simply stops future reshuffling (leaves `virtualQueueIds` as-is); toggling ON
reshuffles the unplayed tail (from `virtualQueueIndex + 1` onward) and reloads the
window — deliberately simpler than today's slightly-odd mid-shuffle-toggle behavior,
since the user's reported flow doesn't exercise that edge case.

**Persistence.** `persistFullPlaybackState()` currently walks `player.currentTimeline`
to build `queueJson` — once windowed, that Timeline only has the *window*, not the
full queue. Change it to serialize `virtualQueueIds` directly (state already held in
memory) — simpler than today's `Timeline.Window` walk, no Player access needed for
this part at all.

## Critical files

| File | Change |
|---|---|
| `service/MusicPlaybackService.kt` | Bulk of the work: `virtualQueueIds`/`virtualQueueIndex` state, shared window-load/shift/trim helpers, refactored `restorePlaybackState()` to use them, new `CUSTOM_COMMAND_SET_QUEUE` handler, `persistFullPlaybackState()` reads `virtualQueueIds` not `Timeline`, single `queueLoadJob` cancellation, skip-past-window safety net |
| `presentation/PlayerViewModel.kt` | `shufflePlay()`/`playSongs()` send the custom command instead of building/pushing `MediaItem`s; `toggleShuffle()` updates `_uiState` optimistically instead of waiting on `onShuffleModeEnabledChanged`; `seekToNext()`/`seekToPrevious()` unchanged (service handles window edges internally) |
| `data/repository/MusicRepository.kt` | Likely unchanged — `getSongsByIds` (order-preserving, chunked) already fits the window-load helper's needs |

No DB schema changes; `PlaybackStateEntity` already stores exactly what's needed
(`queueJson`, `currentQueueIndex`).

## Implementation order (to de-risk a large change)

1. Add the shared window-load/shift/trim helpers + `virtualQueueIds` state; refactor
   `restorePlaybackState()` onto them first (smallest blast radius, most reused code
   already proven tonight). Verify restore/auto-resume, play/skip/previous, and
   position bar still behave correctly live on-device before moving on.
2. Add `CUSTOM_COMMAND_SET_QUEUE` and migrate `shufflePlay()` to it. Verify on the
   real 34,954-song library: skip-next jank is gone (repeat the same Davey/Choreographer
   logcat check used tonight), rapid-skip doesn't corrupt or stall, toggling shuffle
   mid-playback behaves sanely, restart persistence still restores correctly.
3. Migrate `playSongs()` the same way (fixes the un-chunked large-sorted-list case
   too). Regression-check all call sites: Songs tab tap-to-play, Search results,
   Folders, Albums, Artists, Playlists (`playSongs` and `shufflePlay` both).
4. Tune `WINDOW_AHEAD`/`WINDOW_BEHIND`/`SHIFT_TRIGGER_REMAINING` empirically on-device
   (same methodology as tonight's chunk-size tuning: measure full-duration jank via
   `logcat` Choreographer/Davey counts, not just the first few seconds).

## Implementation results (done)

Implemented and live-verified on the connected device's real 34,954-song library.
`MusicPlaybackService.kt` and `PlayerViewModel.kt` were both rewritten in full rather
than incrementally patched (the design was unified enough — `playSongs`/`shufflePlay`
share one `CUSTOM_COMMAND_SET_QUEUE` path and one `loadWindowInline` helper — that
splitting it into separate edits would have meant writing, then immediately
rewriting, the same code). `PendingQueueHolder` added as planned. All review findings
from the first draft were addressed: `WindowedTransportPlayer` (a `ForwardingPlayer`)
handles `hasNext/PreviousMediaItem` + the skip-past-window safety net;
`REPEAT_ALL` wraps at the virtual layer via `onPlaybackStateChanged(STATE_ENDED)`,
never setting the real player to `REPEAT_MODE_ALL`; both `persistProgress()` and
`persistFullPlaybackState()` write `virtualIndex()`, not the window-local
`player.currentMediaItemIndex`; "up next" after cold-start restore now reads
`repository.getPlaybackState()` directly (no `CUSTOM_COMMAND_SET_QUEUE` round trip
needed for this — the ViewModel already had `MusicRepository` injected).

**Verified live, all zero jank (`Davey!`/`Choreographer`) and zero corruption:**
- Fresh full-library shuffle (Songs tab): window fills to 171 items in under 1s
  (vs. ~8s to fill the whole 34k-item library pre-windowing).
- Skip-next repeatedly, including a rapid 10-tap burst (0.6s apart) — the exact
  originally-reported scenario. `dumpsys media_session` queue size confirmed bounded
  (151-171 depending on position) instead of 33,954.
- Previous navigation.
- `playSongs` (tap-to-play from the sorted Songs list, and from Albums).
- `shufflePlay` from the Playlists screen's "Shuffle play" button.
- Toggle shuffle mid-playback: current song uninterrupted, tail reshuffled, icon
  updates correctly.
- Toggle repeat cycle (OFF→ALL→ONE→OFF): icon/variant updates correctly each step.
- Cold-start restore after a shuffle+skip session: force-stopped mid-playback,
  relaunched, exact same song resumed within ~17ms of the pre-kill position,
  mini-player title/artist/art correct immediately (no more empty mini-player on
  restore — the concern raised by the Phase 0 spike doesn't apply here, since this
  design keeps `COMMAND_GET_TIMELINE` available).
- Small-queue sanity (3-song album, 100-song playlist): still clean, unaffected.
- No crashes/exceptions in logcat across the full test session.

`WINDOW_BEHIND=20`/`WINDOW_AHEAD=150`/`SHIFT_TRIGGER_REMAINING=30`/`EXTEND_BATCH=100`
needed no empirical tuning — unlike `SHUFFLE_FILL_CHUNK_SIZE` earlier this session,
these starting values already produced zero measured jank, so there was no signal to
tune against.

**Not explicitly live-tested** (lower risk, same code paths as what was tested):
Search/Folders/Artists screens' `playSongs` calls (identical call to the
already-verified Albums/Songs paths); `REPEAT_ALL` actually wrapping at the true end
of a queue (would require waiting out an entire library); the `seekToNext`/
`seekToPrevious` safety-net override in `WindowedTransportPlayer` (requires skipping
faster than the proactive 30-item-remaining extend can keep up — not reachable by
human-speed tapping in testing); `addToQueue`/`playNext` (not wired to any UI).

## Verification

- `./gradlew.bat installDebug testDebugUnitTest` after each step above.
- Live on the connected device's real 34,954-song library (already set up this
  session): shuffle from Songs tab, skip-next repeatedly (including rapid taps) and
  confirm no `Davey!`/`Choreographer skipped` entries, position bar stays live,
  no queue corruption (`dumpsys media_session` queue size stays exactly 33954/33957
  as appropriate — not larger).
  - Note: The exact wall-clock timestamp of "next" taps needs correlation with `adb
    shell date`, not host-machine time — a mismatch caused a wasted round of testing
    earlier tonight.
  - Note: the mini-player's real Next-button bounds are around `[900,1848][1044,1992]`
    (1080x2400 device) — confirm via `uiautomator dump` rather than estimating from a
    scaled screenshot, which also cost a wasted round tonight.
- Cold-start restore: force-stop, relaunch, confirm same song/position/shuffle state,
  auto-resume, and the already-verified near-zero restore hitch doesn't regress.
- Small-queue sanity (e.g. a 3-song album via Albums tab): confirm still instant/clean
  (this was tonight's zero-jank baseline — must stay that way).
- Deliberately try to break it, as done earlier tonight for the original corruption
  bug: rapid double-tap shuffle, tap shuffle then immediately navigate away, skip
  rapidly through a window-boundary, toggle shuffle mid-fill.
