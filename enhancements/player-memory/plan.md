# Player memory: plan

How `spec.md` (AC-1..AC-8) is built. Paths are relative to
`app/src/main/java/org/schabi/newpipe/`; line numbers are those of `dev` at `cd87fc7`. `PM` stands
for the new package `org.schabi.newpipe.enhancements.playermemory` (`enhancements/playermemory/`);
its tests live in the same package under `app/src/test/java/`. Marker: `ENH:player-memory`.

## 0. Shape of the change

| Step | Finding | New files in `PM` | Upstream lines (all marked) |
|---|---|---|---|
| 1 | M8 | none | `player/Player.java` 1 added; `player/PlayerStartController.kt` 1 changed |
| 2 | M5 | `LoadWindow.kt`, `SourceUnloader.kt` | `player/playback/MediaSourceManager.java` 1 added |
| 3 | M4 | `SeekbarFrameTable.kt`, `ByteBoundedCache.kt`, `PreviewLoadState.kt`, `LazySeekbarPreviews.kt` | `player/PlayerProgressController.kt` 2 changed, 1 added |

6 marked lines in 4 upstream files, none removed. No setting, string or build file changes (AC-8).

## 1. M8 media session (AC-6): confirmed

**Today.** `Player.setupMediaSession()` (`player/Player.java:377-382`) assigns a new
`MediaSessionManager` to the field without disposing the old one. It is called from `initPlayer`
(`Player.java:353`) and again from `initPlayback` (`player/PlayerStartController.kt:217`), which
runs `destroyPlayer()`, `initPlayer()` (`:203-204`) first. `destroyPlayer` disposes only the
manager in the field (`Player.java:441-444`), so the one made by `initPlayer` is lost on every
playback start.
- `PlayerService` (`getMediaSession()` returns null, `player/PlayerService.java:273-275`): each
  manager creates its own `MediaSessionCompat` and sets it active
  (`player/helper/MediaSessionManager.java:75-77`). The lost one is never released: one active
  system session, its connector, the forwarding player with the already released ExoPlayer and
  the `PlayerMediaSession` (which holds the `Player`) stay **until the process dies**.
- `PlayerServiceForAuto` (`getMediaSession()` returns the service's session,
  `player/PlayerServiceForAuto.java:320-322`): no session leaks (`isExternalSession`), but two
  `MediaSessionConnector`s drive the same session. The first is never detached; it lives **until
  that ExoPlayer is released**, and its metadata provider has no player
  (`MediaSessionManager.java:245-251`), so it publishes an empty title next to the real one.

**Fix: the call in `initPlayback` goes, and `setupMediaSession` disposes what it replaces.**
- `player/PlayerStartController.kt:217`, `player.setupMediaSession()` is replaced in place by
  `// ENH:player-memory the media session was created by initPlayer() above`.
- `player/Player.java`, new first statement of `setupMediaSession()` (before `:378`):
  `if (mediaSessionManager != null) { mediaSessionManager.dispose(); } // ENH:player-memory`.
  A guard for any later second call; with the first edit it never fires in today's paths.

Result for both services: one manager per ExoPlayer, created in `initPlayer`, disposed in
`destroyPlayer`. `PlayerService`: one `MediaSessionCompat` per start, the previous one released by
the `destroyPlayer()` that opens `initPlayback`. `PlayerServiceForAuto`: one connector on the
service's session, detached by `dispose()` (`MediaSessionManager.java:290-297`). The call at
`Player.java:353` is kept, not the other one, because `setupFromView` (`Player.java:317`) reaches
`initPlayer` without `initPlayback`, and because `feature/crash-fixes` adds a line directly above
`:353` (section 6).

**Could regress.** The second call ran after `setShuffleModeEnabled` (`:216`), so the
notification's play-mode action was computed late. It is still correct: the action's mode is
refreshed by `Player.onShuffleOrRepeatModeChanged()` (`Player.java:890-896`) on every shuffle or
repeat change of the new ExoPlayer, and by play/pause
(`player/playback/PlayerMediaSession.java:113-124`). Device D1 checks it with a shuffled queue.

## 2. M5 loaded queue items (AC-4, AC-5): confirmed

**Today.** `MediaSourceManager.onMediaSourceReceived` (`player/playback/MediaSourceManager.java:469-488`)
puts each `LoadedMediaSource` into `ManagedMediaSourcePlaylist.managedSources`
(`player/mediasource/ManagedMediaSourcePlaylist.java:30`) and into the non-lazy
`ConcatenatingMediaSource` (`:33`, so every added source is prepared at once). Only the current
index is ever swapped back to a placeholder, when expired (`MediaSourceManager.java:523-541`).
Each loaded item keeps its media source, its `PlayerMediaItem` with the full `StreamInfo`
(`player/mediasource/LoadedMediaSource.java:43`), and for SABR the `SabrDashMediaSource` with
session, manifest and a prepared bridge (`player/datasource/SabrDashMediaSource.java:55-58`,
`:113-126`). Lifetime: **until the playlist is reset** (`resetSources`, `:559-564`, on INIT/ERROR)
or the manager is replaced by `Player.reloadPlayQueueManager()`. The loader itself only loads the
previous item, the current one and the next one, wrapping to index 0 at the end (`:65`,
`:580-606`); after a shuffle, positions outside that set keep sources of other items.

**Unloading is safe with this source management**, because it is the existing replace operation:
`ManagedMediaSourcePlaylist.invalidate(index, null, null)` (`:139-146`) swaps a source for the
shared placeholder, exactly the state of an item that was never loaded, and
`ConcatenatingMediaSource` releases the removed child on the playback thread (for SABR that stops
the bridge, `SabrDashMediaSource.java:157-170`). Replacing an index before the current one already
happens today whenever the previous item finishes loading after a jump.

**Fix.**
- `PM/LoadWindow.kt` (plain Kotlin):
  ```kotlin
  object LoadWindow {
      const val KEEP_BEHIND = 1
      const val KEEP_AHEAD = 2
      /** Whether the item at [index] stays loaded while [currentIndex] is current. */
      @JvmStatic fun keeps(index: Int, currentIndex: Int, queueSize: Int): Boolean
  }
  ```
  `keeps` is true for `currentIndex`, for `currentIndex - 1` if it is `>= 0`, and when
  `(index - currentIndex) mod queueSize` is 1 or 2 (forward, wrapping, as the loader wraps).
  False for an index outside `0 until queueSize`. This set always contains what the loader loads.
- `PM/SourceUnloader.kt` (glue, not unit tested):
  ```kotlin
  object SourceUnloader {
      /** Replaces every LoadedMediaSource outside the window by the placeholder; returns how many. */
      @JvmStatic fun unloadOutsideWindow(playlist: ManagedMediaSourcePlaylist, currentIndex: Int): Int
  }
  ```
  For `i in 0 until playlist.size()`: if `playlist.get(i) is LoadedMediaSource` and
  `!LoadWindow.keeps(i, currentIndex, playlist.size())`, call `playlist.invalidate(i, null, null)`.
  Failed sources and placeholders are left alone. Logs the count with tag `PlayerMemory` when
  `PlayQueue.DEBUG`.
- Hook, `MediaSourceManager.java`, new line after `:368` `maybeSync(isBlockReleased);`, inside the `if`:
  `org.schabi.newpipe.enhancements.playermemory.SourceUnloader.unloadOutsideWindow(playlist, playQueue.getIndex()); // ENH:player-memory`

**Why there and nowhere else.** `maybeSynchronizePlayer` reaches that line only when the source
of the queue's current item is loaded and the player was just synchronized to it: if ExoPlayer
was on another item, `PlayerTransportController.kt:259-281` has issued the seek, and the seek
reaches the playback thread before the removals (same looper, posted earlier). So the item
ExoPlayer plays is never unloaded. Unloading from `loadImmediate` would be wrong: after a far
jump the queue index moves at once while ExoPlayer plays the old item until the new one is
loaded; removing the playing source raises a REMOVE discontinuity and `Player.java:993-996` would
set the queue index back.

| Topic | Behaviour |
|---|---|
| Loads again | The slot holds the placeholder, so `isCorrectionNeeded` (`:505-510`) is true and the loader loads it when it enters the load set, through the same code as a first load (AC-4). `StreamInfo` comes from `InfoCache` while it is cached (60 entries, own expiry). |
| Gapless transitions, speed (AC-5) | The load set (previous, current, next) is unchanged and inside the kept window, so the source ExoPlayer pre-buffers for the next item is never touched; next and previous find the same loaded sources as today. Only a pick further away of an item played earlier is slower than today (see Q3). |
| Expiry | Unchanged: the checks in `LoadedMediaSource.shouldBeReplacedWith` and `maybeRenewCurrentIndex` still run for the kept items; an unloaded item gets a new expiry when it loads again. |
| SABR | The unloaded source is released by ExoPlayer; loading again builds a new session and manifest as a first load does (`player/datasource/SabrSessionHelper.java:72-90`). The current item's session is never touched. |
| Shuffle | REORDER moves the current item to index 0 and reloads its neighbours (`:260-266`, `:274`); the first synchronization after it unloads every other position, also those holding sources of other items. ExoPlayer's order stays unshuffled (`ManagedMediaSourcePlaylist.java:33-34`), so "next" is index + 1. |
| Repeat | One: no index change, nothing is unloaded. All: at the last item the loader preloads index 0, which `keeps` covers by wrapping; after the wrap the last item is unloaded. |
| Queue edits | REMOVE/MOVE/APPEND keep their code. Items an edit pushes out of the window stay loaded until the next synchronization (at most the 4 that were inside). |
| Reload, player switch | A new `MediaSourceManager` starts from placeholders as today. |

**Could regress.** (a) An untested ordering in which ExoPlayer is not on the queue's current item
when the hook runs: playback would jump back to the old index (D3 exercises far jumps and fast
skipping). (b) One add + remove message pair per unloaded item on the playback thread, typically
one item per transition. (c) A permanently failing far item is retried when reached again only if
it was a `LoadedMediaSource`; failed sources are not unloaded, so error behaviour is unchanged.

## 3. M4 seekbar previews (AC-1, AC-2, AC-3, AC-7): confirmed, one detail corrected

**Today.** `PlayerMetadataController.kt:124` calls `PlayerProgressController.resetPreviewThumbnails`
(`player/PlayerProgressController.kt:43-45`) on every metadata change, for every player type. It
starts `SeekbarPreviewThumbnailHolder.resetFrom` (`player/seekbarpreview/SeekbarPreviewThumbnailHolder.java:45-62`),
which downloads up to `MAX_STORYBOARD_BITMAP_PAGES = 48` sheets (`:34`, `:113-123`) one after the
other with a blocking Picasso `get()` (`:221`) and keeps every decoded sheet referenced by the
frame suppliers (`:145-146`, `:155-157`). Default quality is the largest frameset
(`player/seekbarpreview/SeekbarPreviewThumbnailHelper.java:55-65`). Lifetime: **until the next
item's reset clears the map** (`:67-69`), or as long as the `Player` lives if nothing follows.
Corrected: a load for the old item is not unstoppable; it checks a request id after every sheet
(`:152-161`), so it stops after the sheet in flight (up to the 15 s call timeout). The limit of 48
is a page count, not a size: with sheets of 960x540 in RGB_565 (1,036,800 bytes; the size is the
server's, not fixed in code) it is about 47 MiB per item.

**Fix: previews are loaded sheet by sheet, on demand, into a byte-bounded cache.** The upstream
holder stays in the tree, unused.

Plain Kotlin, unit tested:
```kotlin
// SeekbarFrameTable.kt
data class FramesetShape(val pageCount: Int, val totalCount: Int, val durationPerFrameMs: Int,
                         val framesPerPageX: Int, val framesPerPageY: Int)
data class FrameRef(val pageIndex: Int, val positionMs: Int)
class SeekbarFrameTable(shape: FramesetShape, maxPages: Int = MAX_PAGES) {
    val pageStep: Int                    // max(1, ceil(pageCount / maxPages)), as upstream :115-116
    val sampledPages: List<Int>          // 0, pageStep, 2 * pageStep, ... below pageCount
    fun nearest(positionMs: Int): FrameRef?                             // all sampled pages
    fun nearestAmong(positionMs: Int, pages: Set<Int>): FrameRef?       // only these pages
    companion object { const val MAX_PAGES = 48 }
}
// ByteBoundedCache.kt
class ByteBoundedCache<K : Any, V : Any>(private val maxBytes: Long,
        private val sizeOf: (V) -> Long, private val onEvicted: (V) -> Unit) {
    val bytes: Long
    val keys: Set<K>
    operator fun get(key: K): V?         // marks the entry most recently used
    fun put(key: K, value: V)            // evicts least recently used first, never the entry just put
    fun clear()                          // onEvicted for every entry
}
// PreviewLoadState.kt
class PreviewLoadState {
    val generation: Int
    val armed: Boolean
    fun onNewItem(): Int                 // generation + 1; disarms; forgets in-flight and pending page
    fun arm(canShow: Boolean): Boolean   // armed = armed || canShow; returns armed
    fun request(page: Int, cached: Boolean): Int?   // page to start now, or null
    fun onLoaded(generation: Int, page: Int): Result
    data class Result(val accept: Boolean, val next: Int?)
}
```
- `SeekbarFrameTable` holds the frames upstream would have generated (`:123-150`): for each
  sampled page `p` and `i in 0 until framesPerPageX * framesPerPageY`, frame number
  `n = p * perPage + i`, kept while `n + 1 <= totalCount` and `n * durationPerFrameMs` fits an
  `Int`; key `n * durationPerFrameMs`. `nearest*` returns the key with the smallest distance to
  the position, the lower key on a tie (upstream `:240-255`), or null when there is none.
- `ByteBoundedCache.put` replaces an existing key (old value evicted), then evicts until
  `bytes <= maxBytes` or only the new entry is left.
- `PreviewLoadState.request`: null when not armed, when `cached`, or when `page` is the page in
  flight. If nothing is in flight, `page` becomes in flight and is returned. Otherwise `page`
  replaces the single pending page (latest wins) and null is returned. `onLoaded`: a generation
  other than the current one gives `Result(false, null)`; else in flight is cleared, the pending
  page (if any) becomes in flight and is returned as `next`, `accept` is true.

Glue, `PM/LazySeekbarPreviews.kt` (main thread only, except the download):
```kotlin
class LazySeekbarPreviews(private val player: Player,
                          private val refresh: (SeekBar, Int, Boolean) -> Unit) {
    fun resetFrom(context: Context, framesets: List<Frameset>)   // new item
    fun onSeekbarTouched(positionMs: Int)
    fun getBitmapAt(positionInMs: Int): Optional<Bitmap>
    companion object { const val MAX_SHEET_BYTES = 8L * 1024 * 1024 }
}
```
- `resetFrom`: `state.onNewItem()`; cancels the running download with `Future.cancel(true)`;
  `cache.clear()`; picks the frameset by the setting as upstream `:94-107` (NONE or no frameset:
  no table); builds the table. No network (AC-1, AC-2).
- `onSeekbarTouched`: `state.arm(!player.audioPlayerSelected())`, then requests the page of
  `table.nearest(positionMs)`. `getBitmapAt` arms the same way (it is only called for a change
  made by the user, `PlayerProgressController.kt:185-199`; this covers D-pad seeking), requests
  that page, and returns the cut-out of `table.nearestAmong(positionInMs, cache.keys)`: bounds
  from `frameset.getFrameBoundsAt(ref.positionMs)`, then exactly upstream `:177-204` (null or
  recycled sheet gives empty, bounds clamped, copy when the cut equals the sheet). Empty when not
  armed or nothing is cached (AC-7).
- A request that `state.request` returns runs on one single-thread executor owned by the object:
  `PicassoHelper.loadSeekbarThumbnailPreview(url).get()`, result posted to the main thread with
  its generation. `accept == false`: the bitmap is recycled. Otherwise `cache.put(page, bitmap)`,
  `next` is started, and if `player.currentState.isPausedSeek` the preview is redrawn with
  `refresh(player.binding.playbackSeekBar, progress, true)`. Each download is logged with tag
  `PlayerMemory` when `Player.DEBUG`.
- Cache: `ByteBoundedCache<Int, Bitmap>(MAX_SHEET_BYTES, { it.allocationByteCount.toLong() }, { it.recycle() })`.

Hooks, `player/PlayerProgressController.kt`:
- `:18` import replaced by `import org.schabi.newpipe.enhancements.playermemory.LazySeekbarPreviews // ENH:player-memory`.
- `:36` becomes `private val seekbarPreviewThumbnailHolder = LazySeekbarPreviews(player, ::onProgressChanged) // ENH:player-memory`.
- New line after `:260` `saveWasPlaying()`:
  `seekbarPreviewThumbnailHolder.onSeekbarTouched(seekBar.progress) // ENH:player-memory`.
- `:44` and `:199` are unchanged: the new class has the same two method names and types.

**Answers.**
- *First touch* is observed in `onStartTrackingTouch` (`:252`) of the player's own seekbar, and in
  `getBitmapAt`. The background player has no attached player view
  (`PlayerStartController.kt:169-170`) and the queue screen has its own seekbar listener
  (`player/PlayQueueActivity.java:422-432`), so neither arms it; `audioPlayerSelected()` is checked too.
- *A running load for the previous item* is stopped by `resetFrom`: the download thread is
  interrupted, the pending page dropped, and a result that still arrives is discarded and recycled.
- *Limit*: **8 MiB of decoded sheets per item**, measured with `allocationByteCount`, enforced by
  `ByteBoundedCache` on every insert; a single larger sheet is kept alone. One more sheet may be
  in decode. Basis: 8 sheets of 960x540 RGB_565, about a sixth of today's 48, enough for the
  sheet under the thumb and its neighbours. The frames a position maps to are the same sampled
  frames as today, so nothing gets coarser (AC-7).

**Could regress.** The first preview of an item, and of each newly reached part of a long video,
appears one download later than today (Q2); until then the nearest cached frame is shown, as
today during the initial load. A reload of the same item (quality change, player switch) clears
the cache. After a switch to audio-only without reload the cache (at most the limit) stays until
the item changes. After `Player.destroy()` one sheet in flight may still finish and is dropped.

## 4. Verification

**JVM unit tests (JUnit 4, package `enhancements.playermemory`).** Honest scope: only the four
plain classes. Bitmaps, Picasso, `MediaSourceManager`, ExoPlayer and media sessions are not
testable without Robolectric or a device, so M8 has no unit test at all.

| Test class | Cases |
|---|---|
| `LoadWindowTest` | AC-4: for sizes 1..40 and every current index, exactly the previous one, the current and the next two (wrapping) are kept; nothing outside `0 until size`. AC-5: every index the upstream loader loads (previous if any, current, next, and index 0 when current is last; restated in the test from `MediaSourceManager.java:591-602`) is kept. |
| `SeekbarFrameTableTest` | AC-7: against a reference written in the test from upstream `:113-150` and `:240-255` (build all keys, linear scan), `nearest` agrees for page counts 1, 48, 49, 120, 500, several grids, partial last page, positions below 0, between keys, on a tie, beyond the end; `nearestAmong` agrees with the scan restricted to those pages; empty set and `totalCount = 0` give null. |
| `ByteBoundedCacheTest` | AC-3: `bytes` never exceeds the limit after any `put` sequence, except a single oversized entry kept alone; eviction order is least recently used and `get` refreshes; every evicted or cleared value is reported once. |
| `PreviewLoadStateTest` | AC-1: nothing is returned before `arm(true)`; `arm(false)` does not arm. AC-2: `onNewItem` disarms, and a result with the old generation is not accepted and starts nothing. Latest-wins pending page; cached and in-flight pages start nothing. |

**Device script.** Debug build; `PKG=$(adb shell pm list packages | grep -i NewPipeEnhanced | head -1 | cut -d: -f2)`.
Run D2 once on a build of `origin/dev` and once on the fix, same queue and network.
- **D1 (AC-6).** Start a video in the main player, switch to background, start another video, 5
  times. `adb shell dumpsys media_session | grep -c "$PKG/MediaSessionManager"` prints 1 (today:
  one more per start). Lock screen, notification buttons, a wired or Bluetooth headset button and
  Bluetooth title display work; start a shuffled queue and one with repeat: the notification's
  play-mode button shows the right mode. In Android Auto (or the Desktop Head Unit): browse,
  start, skip, title shown once and not blank.
- **D2 (AC-1, AC-4, memory).** Enqueue a playlist of 30 videos of 20+ minutes in the background
  player, seekbar preview setting at its default. Every 30 s:
  `adb shell input keyevent KEYCODE_MEDIA_NEXT`. At items 1, 10, 20 and 30, 20 s after the skip:
  `adb shell run-as $PKG kill -10 $(adb shell pidof $PKG)` (forces a GC), wait 3 s, then
  `adb shell dumpsys meminfo $PKG` and note `TOTAL PSS`, `Java Heap` and `Native Heap` from the
  App Summary. Expected on `dev`: Native Heap tens of MB above the fix (preview sheets), Java
  Heap rising from item 1 to 30. Expected with the fix: items 10, 20 and 30 within noise of each
  other. For proof instead of a trend, Android Studio > Profiler > Memory > Capture heap dump at
  item 30 and filter by class: `LoadedMediaSource` at most 4 (30 on `dev`), `SabrDashMediaSource`
  at most 4, no `Bitmap` of sheet size, `MediaSessionCompat` 1.
  `adb logcat -s PlayerMemory` shows one unload per skip.
- **D3 (AC-4, AC-5).** Same queue in the main player: next and previous start as fast as on
  `dev` and an item played to its end continues gaplessly into the next; pick item 25 while 3
  plays, then item 3 again: both load and play, the selection never jumps back; press next five
  times quickly; shuffle on and off; repeat all across the end of a 3-item queue; remove and drag
  items around the current one; leave an item paused beyond the expiry time, then play.
- **D4 (AC-1, AC-2, AC-3, AC-7).** Main and popup player, a 2-hour video: before touching the
  seekbar `adb logcat -s PlayerMemory` shows no sheet download (on `dev`,
  `adb logcat -s SeekbarPrevThumbHolder` shows them at item start, also in the background player);
  drag across the whole bar: previews appear, match the place, and look as on `dev` (size,
  position of the box, sharpness); the profiler's native size stays within about 10 MB of the
  value before the drag; skip to the next item while dragging: the old previews are gone. Repeat
  with the setting on low quality and on "none" (no preview), and with D-pad seeking if available.

## 5. AC coverage

| AC | Piece | Verified by |
|---|---|---|
| AC-1 | `PreviewLoadState.arm`/`request`; hooks at `:36`, after `:260`; `resetFrom` without network | `PreviewLoadStateTest`; D2, D4 |
| AC-2 | `LazySeekbarPreviews.resetFrom`: clear, cancel, generation | `PreviewLoadStateTest`, `ByteBoundedCacheTest` (clear); D4 |
| AC-3 | `ByteBoundedCache`, `MAX_SHEET_BYTES` = 8 MiB | `ByteBoundedCacheTest`; D4 |
| AC-4 | `LoadWindow.keeps`, `SourceUnloader`, hook in `maybeSynchronizePlayer` | `LoadWindowTest`; D2, D3 |
| AC-5 | load set unchanged and inside the kept window | `LoadWindowTest`; D3 |
| AC-6 | section 1, both edits | D1 |
| AC-7 | `SeekbarFrameTable`; cut-out copied from upstream `:177-204` | `SeekbarFrameTableTest`; D4 |
| AC-8 | no resource or settings file touched | review of the diff |

## 6. Overlap with other planned changes

| Change | Its edits | This plan | Conflict |
|---|---|---|---|
| `feature/keep-position` | `Player.java:501` (H1), `:1227` (H2); `PlayerStartController.kt:114` (H5); `PlayerTransportController.kt:240`, `:272` (H3, H4) | `Player.java` before `:378`; `PlayerStartController.kt:217`; `PlayerTransportController.kt` untouched | None textually (separate hunks). |
| `feature/crash-fixes` | `Player.java` one line after `:352`; `player/helper/AudioReactor.java`; `PlayerBroadcastReceiver.kt:41` | `Player.java:353` deliberately untouched, edit is 25 lines below | None textually. |

Keep-position's two assumptions hold: this plan adds **no reload path** (it never calls
`reloadPlayQueueManager()`, never creates a `MediaSourceManager`), and it never unloads the
current item, so the player's current item is cleared only where it is today
(`PlayerPlaybackStateController.kt:125`). An unloaded item that is reached again is a start of
another item inside the running manager, the same case as an item never loaded: it carries no
reload mark and follows the start rules. The unload hook sits after `maybeSync`, so H3/H4 have
run before anything is unloaded. To stay conflict-free the Code layer must not reformat the
neighbouring lines, and must not move the unload into `PlayerTransportController.kt`.

## 7. Order of work

Tests first (one `test(player-memory):` commit with the four classes), then three
`code(player-memory):` commits, each revertable on its own:
1. **M8**: the two lines of section 1. Then D1.
2. **M5**: `LoadWindow.kt`, `SourceUnloader.kt`, the hook. `LoadWindowTest` green. Then D2 (heap), D3.
3. **M4**: the four files of section 3, the three hook lines. Remaining tests green. Then D2, D4.

After each: `./gradlew :app:testDebugUnitTest -DskipFormatKtlint`,
`enhancements/tools/check-layers.sh origin/dev`; at the end `git grep "ENH:player-memory"` lists
6 lines.

## 8. Assumptions and risks

Planned with the assumptions stated in the open questions Q2 (on-demand previews, 8 MiB) and Q3
(window wraps at the queue's end; unloading at the next synchronization; a far pick loads again).

Risks:
- M5 rests on the ordering argument in section 2 (seek before removal). If D3 shows the selection
  jumping back, step 2 is reverted alone; the fallback is to hand ExoPlayer's own window index to
  `unloadOutsideWindow` as a second protected index (one more method on `PlaybackListener`, about
  6 marked lines in 3 upstream files).
- M5 frees the source graph; `StreamInfo` objects also live in `InfoCache` (at most 60), so the
  Java heap gain per item is smaller for non-SABR items than for SABR ones.
- Sheet size is the server's: if sheets are larger than 8 MiB each the cache holds one sheet and
  the limit is that sheet's size.
- Interrupting Picasso's blocking `get()` relies on OkHttp aborting on thread interrupt; if it
  does not, the old download finishes (at most 15 s, one sheet) and is discarded, as today.
- `PlayerServiceForAuto` cannot be checked without Android Auto or the Desktop Head Unit.
- Numbers in D2 are trends, not thresholds: PSS varies by device; the heap dump counts are the
  pass criterion.
