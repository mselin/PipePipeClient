# Queue guard: plan

How `spec.md` (AC-1..AC-19) is built. Paths are relative to
`app/src/main/java/org/schabi/newpipe/` unless they start with `res/` (`app/src/main/res/`); a
file cited by name alone is the only one of that name. Line numbers are those of `dev` at
`cd87fc7`. `QG` stands for `org.schabi.newpipe.enhancements.queueguard.QueueGuard`; hooks spell
it out in full so that no import line is added.

**Changed 2026-11-15, after answers A11 to A14 (spec commits `00302ba`, `ddb29b0`).** The guard
asks only for "a queue worth guarding": in the main player, a queue with at most one video the
user put there is replaced as upstream does, and videos the player added by itself through
"Auto-enqueue next stream" never count, played or not. What changed in this plan:

- 3.1: `PlayerState` gets `autoQueuedCount` (last, default `0`) and the derived `worthGuarding`;
  new `AutoQueuedRecord`, the fork's own record of the videos the player added by itself.
  `somethingPlaying` and `isCurrent` keep their meaning.
- 3.2: the first row of `onTap` and of `onListPlay` tests `worthGuarding` instead of
  `somethingPlaying` (AC-1, AC-7, AC-10, AC-14, AC-16). No other row, no signature and nothing in
  `QueueGuardController` changes.
- 2 and 3.3: one new hook, Q1, an added call in `PlayQueue.java` that reports every auto-enqueued
  video; `HolderPlayerStateSource` counts them in the queue. Now 9 upstream files and 24 marked
  lines. The accessor hook planned after A11 is gone: upstream's own mark names only the latest
  auto-enqueued video, which A13 makes insufficient, and the record makes it unnecessary.
- 4: the summary of the switch. 5: AC rows 1, 7, 10, 14, 16, 17 and a list of what the Tests
  layer changes. 6: what follows for default-open-action (A14). 8, 9: new risks; Q12 to Q14.

## 1. Shape

Hooks call `QueueGuard` (Android glue). It asks `QueueGuardController` (plain Kotlin), which
consults `GuardPolicy` (the rules) and calls back through small interfaces. Effects reuse
upstream code instead of copying it: the long-press entries' own actions, `enqueueOnPlayer`, and
"as today" is always the untouched upstream statement.

## 2. Hook points

9 upstream files, 24 lines marked `ENH:queue-guard`: 7 added, 17 changed in place.

**Form A, added line.** The original statements stay byte-identical and are skipped when the
hook returns `true`. `replay` re-enters the same handler; the controller lets that one call
through (3.2), so "as today" runs the original code.

| # | Where | Added line |
|---|---|---|
| T1 | `fragments/list/BaseListFragment.java:660` `onStreamSelected`, after `:661` | `if (QG.onVideoTap(this, selectedItem, false, () -> onStreamSelected(selectedItem))) return; // ENH:queue-guard` |
| T2 | `local/playlist/LocalPlaylistFragment.java:272` `selected`, after `:276`, before both branches `:279-289` | `if (QG.onVideoTap(LocalPlaylistFragment.this, ((PlaylistStreamEntry) selectedItem).toStreamInfoItem(), autoBackgroundPlaying, () -> selected(selectedItem))) return; // ENH:queue-guard` |
| T3 | `local/history/StatisticsPlaylistFragment.java:186` `selected`, after `:190` | `if (QG.onVideoTap(StatisticsPlaylistFragment.this, ((StreamStatisticsEntry) selectedItem).toStreamInfoItem(), false, () -> selected(selectedItem))) return; // ENH:queue-guard` |
| T4 | `local/feed/FeedFragment.kt:775` `onItemClick`, after `:781` | `if (QG.onVideoTap(this@FeedFragment, stream.toStreamInfoItem(), autoBackgroundPlaying) { onItemClick(item, view) }) return // ENH:queue-guard` |
| M1 | `info_list/dialog/InfoItemDialog.java:346` `Builder.create()`, before the `return` at `:350` | `QG.addStreamDetailsEntry(fragment, entries); // ENH:queue-guard` (skips nothing) |
| S1 | `res/xml/video_audio_settings.xml`, after `:62` (end of "Preferred 'open' action") | `<SwitchPreferenceCompat android:defaultValue="true" android:key="@string/enh_queue_guard_ask_key" android:title="@string/enh_queue_guard_ask_title" android:summary="@string/enh_queue_guard_ask_summary" app:singleLineTitle="false" app:iconSpaceReserved="false" /> <!-- ENH:queue-guard -->` |

T1-T4 pass the fragment, the tapped item (converted as that fragment's long-press does), whether
the tap starts the list in the background (Music Player Mode; always false in the feed,
`FeedFragment.kt:161`) and the replay. S1 is XML and cannot call code: one line that uses only
resources from section 4. T1 covers search, trending, channel videos and tabs, remote playlists
and related videos: `BaseListFragment.java:474` is the only `setOnStreamSelectedListener` call,
and Compose holders use it too (`ComposeInfoItemHolder.kt:43`). T1 sits after `onItemSelected` so
the search keyboard closes before the dialog; the replay calls it a second time, harmlessly
(`SearchFragment.java:1109` is the only override).

**Form B, receiver swap: these hooks cannot be added lines.** 11 of the 15 play-button
listeners are expression lambdas (`view -> NavigationHelper.playOn…(…)`) with no place for a
guard line, and a view's click listener cannot be read back to wrap it later. Smallest
alternative: on the line that holds the receiver, `NavigationHelper` becomes `QG` and
`// ENH:queue-guard` is appended; method name and arguments stay. `QG` has the same four
signatures and calls the `NavigationHelper` method with the same arguments when nothing is to be
asked and for *Replace queue*. All 15 listeners use this form, to have one mechanism.

| # | File | Lines changed |
|---|---|---|
| B1 | `fragments/list/playlist/PlaylistFragment.java` | `:370` main, `:372` popup, `:374` background (name on `:375`) |
| B2 | `fragments/list/channel/ChannelVideosFragment.java` | `:706` main, `:709` popup, `:712` background (names on the next lines) |
| B3 | `local/playlist/LocalPlaylistFragment.java` | `:969` main, `:971` popup, `:979` background shuffled, `:981` background |
| B4 | `local/history/StatisticsPlaylistFragment.java` | `:387` main, `:389` popup, `:391` background |
| B5 | `local/feed/FeedFragment.kt` | `:331` main, `:338` popup, `:347` background shuffled, `:349` background |

**Form A again, for the queue: one added line that skips nothing.** "A queue worth guarding" does
not count the videos the player added by itself, also after they have played (A13). Upstream
knows only the latest one (`PlayQueue.autoQueuedUuid`, `player/playqueue/PlayQueue.java:70`,
private, overwritten by the next auto-enqueue `:339-341`), and no public member tells an
auto-enqueued video from one the user enqueued: `append` and `appendAutoQueued` (`:309`, `:319`)
emit the same `AppendEvent` (`:343`) and `PlayerMediaItem` carries no flag. So the fork keeps its
own record, fed where every auto-enqueue passes:

| # | Where | Added line |
|---|---|---|
| Q1 | `player/playqueue/PlayQueue.java:323` `appendInternal`, after `:325` (`itemList` is built) | `if (autoQueued) org.schabi.newpipe.enhancements.queueguard.QueueGuard.noteAutoQueued(itemList); // ENH:queue-guard` |

Both callers of `appendAutoQueued` (`player/AutoQueueController.kt:48`, the next part of a
multi-part video, and `:66`, the related video) and any later one go through this line. It is an
added call into `enhancements/`, a form PROCESS.md lists. Rejected: redirecting the two calls in
`AutoQueueController.kt` (2 lines, and a third caller would be missed); the accessor for
`autoQueuedUuid` that A12 accepted (it cannot name a video that has already played, and with the
record it adds nothing); a field or set inside `PlayQueue` (several upstream lines); reading the
private field by reflection (the release build is minified, `app/build.gradle:86-88`).

**Why not `NavigationHelper`** (the hook PROCESS.md suggests). `openVideoDetailFragment` and
`playOn*Player` cannot tell a tap or a play button from their other callers, which pass the same
arguments (`LocalPlaylistFragment.java:1378`, `VideoDetailFragment.kt:342`,
`StreamSelectionActions.java:58`, `InternalUrlsHandler.java:186`). The Music Player Mode tap never
reaches `openVideoDetailFragment`, and there the item is reduced to id, url and title, so the
long-press actions could not be reused. Also rejected: a button class of our own in
`res/layout/playlist_control.xml` with capture hooks in `NavigationHelper` (as many files, hidden
state), and setting the listeners again from one added line (copies upstream behaviour).

**Callers left alone on purpose**

| Caller | Why |
|---|---|
| `MainActivity.java:866` and what reaches it: `RouterActivity.java:806-810`, `QueueItemMenuUtil.java:56`, `PlayQueueActivity.java:142-154`, `PlayerClickController.kt:129`, `Player.java:1388`, `InternalUrlsHandler.java:186,222`, `CastReceiver.kt:215` | links from other apps, queue screen, player switching, timestamp links, casting: "Not covered" |
| `VideoDetailFragment.kt:342`, `:531`, `:550` | reCAPTCHA reload; the page's own background and popup buttons |
| `LocalPlaylistFragment.java:1378` | the existing "Show stream details" (AC-17: unchanged) |
| `StreamDialogDefaultEntry.java:73,83,89,95`; overrides at `PlaylistFragment.java:163`, `LocalPlaylistFragment.java:1356`, `StatisticsPlaylistFragment.java:501` | long-press actions, already explicit |
| `LocalPlaylistFragment.java:1150`, `StreamSelectionActions.java:55,58` | swipe-to-enqueue and multi-select toolbar: explicit, neither video tap nor play button |
| `MediaBrowserPlaybackPreparer.kt:81`, `PlayPlaylistShortcutActivity.kt:45` | Android Auto, launcher shortcut: outside the app's lists |

## 3. New code in `enhancements/queueguard/`

### 3.1 `GuardModel.kt` (plain Kotlin)

```kotlin
enum class PlayerKind { MAIN, BACKGROUND, POPUP }
data class PlayerState(
    val kind: PlayerKind?,        // null: no player open
    val queueSize: Int,           // 0 while the player holds no queue
    val currentServiceId: Int,
    val currentUrl: String?,      // url of the current item, null if none
    val autoQueuedCount: Int = 0, // of the queueSize items, those the player added by itself
) {
    /** Spec, Terms, "something is playing". Unchanged. */
    val somethingPlaying: Boolean get() = kind != null && queueSize > 0
    /** Spec, Terms, "a queue worth guarding": something is playing, except in the main player
     *  while its queue holds at most one video the user put there. */
    val worthGuarding: Boolean
        get() = somethingPlaying && !(kind == PlayerKind.MAIN && queueSize - autoQueuedCount <= 1)
    fun isCurrent(tap: TapRequest): Boolean =
        somethingPlaying && currentUrl == tap.url && currentServiceId == tap.serviceId
    companion object { val IDLE = PlayerState(null, 0, -1, null) }
}
data class TapRequest(
    val serviceId: Int, val url: String, val title: String,
    val startsListInBackground: Boolean = false,   // local playlist with Music Player Mode on
)
enum class GuardChoice { ADD_TO_QUEUE, PLAY_NEXT, PLAY_NOW, CANCEL }   // list dialog: add all, all next, replace
enum class TapEffect { NONE, OPEN_AS_TODAY, ASK, ENQUEUE, ENQUEUE_NEXT, START_BACKGROUND, START_POPUP }
enum class ListEffect { NONE, PLAY_AS_TODAY, ASK, ENQUEUE_ALL, ENQUEUE_ALL_NEXT }
data class QueueMove(val from: Int, val to: Int)

/** Moves, applied in order, that bring the last [added] of [sizeAfterAppend] queue items to just behind
 *  [currentIndex], in their order: i in 0 until added -> (sizeAfterAppend - added + i, currentIndex + 1 + i), minus from == to. */
fun movesForPlayNext(sizeAfterAppend: Int, added: Int, currentIndex: Int): List<QueueMove>

/** Index for "Show stream details" in a menu given as string resource ids: -1 if [detailsRes] is already
 *  there, else the index of [anchorRes], else the size (append). */
fun streamDetailsInsertIndex(entryResources: List<Int>, detailsRes: Int, anchorRes: Int): Int

const val AUTO_QUEUED_RECORD_LIMIT: Int = 1000
/** The uuids of the queue items the player added by itself. Thread-safe (every member `@Synchronized`). */
class AutoQueuedRecord(private val limit: Int = AUTO_QUEUED_RECORD_LIMIT) {
    /** Remembers [uuids]. Beyond [limit] entries the oldest are forgotten, one per entry added. */
    fun add(uuids: Collection<String>)
    /** How many entries of [queueUuids] are remembered; a uuid that occurs twice counts twice. */
    fun countIn(queueUuids: List<String>): Int
}
```

`autoQueuedCount` is the last constructor parameter and has a default, so every existing
`PlayerState(kind, queueSize, currentServiceId, currentUrl)` call, positional or named, compiles
unchanged and describes a queue the user built alone: `IDLE`, this feature's tests and those of
default-open-action. `queueSize` stays the whole queue, auto-enqueued videos included: it is the
number the dialog shows (AC-2, AC-10). `queueSize - autoQueuedCount` is the number of videos the
user put there. The two properties side by side:

| State | `somethingPlaying` | `worthGuarding` |
|---|---|---|
| no player, or `queueSize == 0` | no | no |
| `BACKGROUND` or `POPUP`, `queueSize >= 1`, any `autoQueuedCount` | yes | yes |
| `MAIN`, `queueSize - autoQueuedCount <= 1` | yes | no |
| `MAIN`, `queueSize - autoQueuedCount >= 2` | yes | yes |

`worthGuarding` implies `somethingPlaying`. Queues of the third line, main player (A is a video
the user tapped, B and C came by auto-enqueue): A alone; A and B, whichever of the two plays; A,
B, C after playback moved on by itself; B and C after the user removed A (no video of the user
left); B and D after the user removed A and enqueued D (one). Of the fourth line: A and D; A, B
and D.

**Which videos the player added by itself (the rule the glue and `AutoQueuedRecord` implement).**

1. A queue item counts as added by the player if and only if its uuid
   (`PlayerMediaItem.uuid`, one per queue slot, `player/mediaitem/PlayerMediaItem.kt:17,34`) was
   passed to `PlayQueue.appendAutoQueued` in this process. Every other item is the user's: the
   tapped video, a played list, everything enqueued by hand.
2. Playing does not change it: such a video is not the user's before, while or after it plays.
3. Moving it does not change it either, by drag in the queue screen or otherwise. Upstream drops
   its own mark on a move (`PlayQueue.java:441-443`); the fork does not follow, because the user
   still did not add the video, and following would need a second hook. Shuffling changes nothing.
4. Enqueueing the same video by hand creates a new item with a new uuid
   (`PlayerMediaItem.kt:173`, `:236`): that item is the user's, and the queue then holds the
   video twice, or once if upstream dropped the auto-enqueued tail for it (`PlayQueue.java:331-337`).
5. The record is keyed by uuid, not by queue object, so it follows the queue wherever its items
   go: a copy through `SerializedCache` (how a queue travels in an intent,
   `util/NavigationHelper.java:88,601`, `player/PlayerStartController.kt:30-31`) keeps the uuids,
   as serialization keeps the field; so does a switch between main, background and popup player
   and a restart of the player service in the same process. A removed item's uuid simply never
   matches again.
6. The record is lost when the process dies and it forgets its oldest entries beyond
   `AUTO_QUEUED_RECORD_LIMIT`. A forgotten video counts as the user's, so the guard errs towards
   asking. The player and its queue end with the process too, and 1000 is more auto-enqueues
   than a session produces.

### 3.2 `GuardPolicy.kt`, `QueueGuardController.kt` (plain Kotlin)

```kotlin
const val ASK_BEFORE_REPLACING_DEFAULT: Boolean = true
fun interface GuardSettings { fun askBeforeReplacing(): Boolean }
interface GuardPolicy {
    fun onTap(tap: TapRequest, player: PlayerState): TapEffect
    fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect   // never ASK
    fun onListPlay(player: PlayerState): ListEffect
    fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect                  // never ASK
    fun offersStreamDetails(player: PlayerState): Boolean
}
class QueueGuardPolicy(private val settings: GuardSettings) : GuardPolicy
fun interface PlayerStateSource { fun current(): PlayerState }
interface TapActions { fun openAsToday(); fun enqueue(); fun enqueueNext(); fun startInBackground(); fun startInPopup() }
interface ListActions { fun playAsToday(); fun enqueueAll(); fun enqueueAllNext() }
interface GuardUi {   // onChoice: at most once, only for a choice the user made; a dismissal calls nothing or CANCEL
    fun askForTap(title: String, queueSize: Int, onChoice: (GuardChoice) -> Unit)
    fun askForList(videoCount: Int, queueSize: Int, onChoice: (GuardChoice) -> Unit)
}
class QueueGuardController(private val policy: GuardPolicy, private val player: PlayerStateSource) {
    fun onVideoTap(tap: TapRequest, actions: TapActions, ui: GuardUi): Boolean   // true: taken over
    fun onListPlay(videoCount: Int, actions: ListActions, ui: GuardUi)
    fun offersStreamDetails(): Boolean   // policy.offersStreamDetails(player.current())
}
```

`QueueGuardPolicy`, first match wins (`playing` = `player.somethingPlaying`, `worth guarding` =
`player.worthGuarding`):

| Function | Condition | Result | AC |
|---|---|---|---|
| `onTap` | not worth guarding | `OPEN_AS_TODAY` | 7 |
| | `player.isCurrent(tap)` | `OPEN_AS_TODAY` | 8 |
| | `askBeforeReplacing()` | `ASK` | 1 |
| | otherwise | *playNow* | 16 |
| *playNow* | not playing | `OPEN_AS_TODAY` | 9 |
| | `tap.startsListInBackground` | `OPEN_AS_TODAY` | 5 |
| | kind `MAIN` / `BACKGROUND` / `POPUP` | `OPEN_AS_TODAY` / `START_BACKGROUND` / `START_POPUP` | 5 |
| `onTapChoice` | `CANCEL` | `NONE` | 6 |
| | `ADD_TO_QUEUE` | `ENQUEUE` | 3, 9 |
| | `PLAY_NEXT` | playing: `ENQUEUE_NEXT`, else `ENQUEUE` | 4, 9 |
| | `PLAY_NOW` | *playNow* | 5, 9 |
| `onListPlay` | not worth guarding, or switch off | `PLAY_AS_TODAY` | 14, 16 |
| | otherwise | `ASK` | 10 |
| `onListChoice` | `CANCEL` | `NONE` | 13 |
| | `ADD_TO_QUEUE` | `ENQUEUE_ALL` | 11 |
| | `PLAY_NEXT` | playing: `ENQUEUE_ALL_NEXT`, else `ENQUEUE_ALL` | 11 |
| | `PLAY_NOW` | `PLAY_AS_TODAY` | 12 |
| `offersStreamDetails` | playing and kind `BACKGROUND` or `POPUP` | `true`, else `false` | 17 |

Only the first row of `onTap` and of `onListPlay` differ from the plan before A11. What the table
means where the two properties differ, the main player holding at most one video the user put
there (alone, or with videos the player added by itself around it):

- **Whether to ask is decided by `worth guarding`.** A video tap gives `OPEN_AS_TODAY` and a play
  button `PLAY_AS_TODAY`, switch on or off, Music Player Mode or not: no dialog, upstream's own
  statements run (AC-7, AC-14). With the switch off this equals *playNow* for `MAIN`, so AC-16
  needs no row of its own. The moment the user has enqueued a second video the rows below the
  first apply as before (AC-1, AC-10, AC-16).
- **The current-item rule keeps its place and its meaning.** `isCurrent` is still built on
  `playing`, and a tap on the current item is `OPEN_AS_TODAY` in every state (AC-8): through the
  first row while the queue is not worth guarding, through the second otherwise.
- **What a choice does is decided by `playing`, as before.** `onTapChoice`, `onListChoice` and
  *playNow* do not look at `worth guarding`. A dialog only opens for a queue worth guarding; if
  the queue has stopped being one when the user chooses (the main player is down to one video,
  or the player switched to main), the choice is still carried out against what is playing:
  `ADD_TO_QUEUE` -> `ENQUEUE` / `ENQUEUE_ALL`, `PLAY_NEXT` -> `ENQUEUE_NEXT` / `ENQUEUE_ALL_NEXT`,
  `PLAY_NOW` -> *playNow* by kind / `PLAY_AS_TODAY` (AC-3 to AC-5, AC-11, AC-12). An explicit
  choice is never dropped, asked again or turned into another one. Only "nothing is playing" at
  that moment changes a choice (AC-9, AC-11).
- **`offersStreamDetails` is unchanged** and stays on `playing` (AC-17 says "in use"). For
  `BACKGROUND` and `POPUP` the two properties are equal; for `MAIN` it is `false`, and with one
  video there the tap itself opens the page.

`QueueGuardController`:

- `onVideoTap`: while the controller is inside `actions.openAsToday()` it returns `false` at once
  (the replay). Otherwise `s = player.current()` and `policy.onTap(tap, s)`: `OPEN_AS_TODAY`
  returns `false` and calls nothing; `ASK` calls only `ui.askForTap(tap.title, s.queueSize, cb)`
  and returns `true`; any other effect is performed and returns `true`.
- `cb(choice)` performs `policy.onTapChoice(choice, tap, player.current())`, the state being read
  at that moment. A second call of `cb` is ignored.
- Perform: `OPEN_AS_TODAY` -> `openAsToday()` with the replay flag set, cleared in `finally`;
  `ENQUEUE`, `ENQUEUE_NEXT`, `START_BACKGROUND`, `START_POPUP` -> `enqueue()`, `enqueueNext()`,
  `startInBackground()`, `startInPopup()`; `NONE`, `ASK` -> nothing.
- `onListPlay`: `videoCount <= 0` -> `playAsToday()`. Otherwise `policy.onListPlay(s)`:
  `PLAY_AS_TODAY` -> `playAsToday()`; `ASK` -> `ui.askForList(videoCount, s.queueSize, cb)`, whose
  `cb` performs `policy.onListChoice(choice, player.current())` with `PLAY_AS_TODAY`,
  `ENQUEUE_ALL`, `ENQUEUE_ALL_NEXT` -> `playAsToday()`, `enqueueAll()`, `enqueueAllNext()`. As for
  a tap, a second call of `cb` is ignored.
- Nothing here changed with A11: the controller never reads `worthGuarding` (the policy does),
  and the dialogs keep getting `s.queueSize`, the whole queue.

### 3.3 Android glue (no unit tests: the build has no Robolectric)

`QueueGuard.kt`:

```kotlin
object QueueGuard {
    @JvmStatic fun onVideoTap(fragment: Fragment, item: StreamInfoItem, startsListInBackground: Boolean, replay: Runnable): Boolean
    @JvmStatic fun playOnMainPlayer(activity: AppCompatActivity, playQueue: PlayQueue)
    @JvmStatic fun playOnPopupPlayer(context: Context, queue: PlayQueue, resumePlayback: Boolean)
    @JvmStatic fun playOnBackgroundPlayer(context: Context, queue: PlayQueue, resumePlayback: Boolean)
    @JvmStatic fun playOnBackgroundPlayerShuffled(context: Context, queue: PlayQueue, resumePlayback: Boolean)
    @JvmStatic fun addStreamDetailsEntry(fragment: Fragment, entries: MutableList<StreamDialogEntry>)
    @JvmStatic fun noteAutoQueued(items: List<PlayerMediaItem>)   // hook Q1, any thread
}
```

What its members do, and the `internal` classes of `QueueGuardGlue.kt`:

| Piece | Does |
|---|---|
| `QueueGuard` fields | one `QueueGuardController(policy, HolderPlayerStateSource)`, plus the two lines default-open-action replaces (section 6): `private val policy: GuardPolicy = QueueGuardPolicy(PrefsGuardSettings)` and `private val tapDialogExtension: TapDialogExtension? = null` |
| `onVideoTap` | `false` if `fragment.view == null`; else `controller.onVideoTap(TapRequest(item.serviceId, item.url, item.name, startsListInBackground), FragmentTapActions(fragment, item, replay), DialogGuardUi(fragment.requireActivity(), fragment.viewLifecycleOwner, tapDialogExtension))` |
| `playOn*` | `asToday` = the `NavigationHelper` method of the same name with the same arguments. `context` not an `AppCompatActivity`: run `asToday`. Else `controller.onListPlay(queue.streams.size, QueueListActions(context, queue, asToday), DialogGuardUi(activity, activity, null))` |
| `noteAutoQueued` | `autoQueuedRecord.add(items.map { it.uuid })`, nothing else: no controller, no preference, no `Context` |
| `addStreamDetailsEntry` | if `controller.offersStreamDetails()` and `i = streamDetailsInsertIndex(entries.map { it.resource }, R.string.show_stream_details, R.string.download)` is not `-1`: insert at `i` a `StreamDialogEntry(R.string.show_stream_details)` whose action is `NavigationHelper.openVideoDetailFragment(f.requireContext(), f.requireActivity().supportFragmentManager, item.serviceId, item.url, item.name, null, false)` |
| `object PrefsGuardSettings : GuardSettings` | default shared preferences of `App.getApp()`, key `R.string.enh_queue_guard_ask_key`, default `ASK_BEFORE_REPLACING_DEFAULT`, read on every call |
| `object HolderPlayerStateSource : PlayerStateSource` | from `PlayerHolder.getInstance()`: `kind` = `getType()` (`VIDEO`->`MAIN`, `AUDIO`->`BACKGROUND`, `POPUP`->`POPUP`, `null`->`null`), `queueSize` = `getQueueSize()`, `currentServiceId` and `currentUrl` from `getPlayer()?.playQueue?.item` (`-1`, `null` if none), `autoQueuedCount` = `autoQueuedRecord.countIn(uuids)` with `uuids` = `q.getItem(i)?.uuid` for `i` in `0 until q.size()`, nulls left out, `q` = `getPlayer()?.playQueue` (`0` if `q` is `null`) |
| `internal val autoQueuedRecord = AutoQueuedRecord()` (top level of `QueueGuardGlue.kt`) | the one record of the process, written by `QueueGuard.noteAutoQueued`, read by `HolderPlayerStateSource` |
| `class FragmentTapActions(fragment: Fragment, item: StreamInfoItem, replay: Runnable) : TapActions` | `openAsToday` = `replay.run()`; the others call `StreamDialogDefaultEntry.ENQUEUE`, `.ENQUEUE_NEXT`, `.START_HERE_ON_BACKGROUND`, `.START_HERE_ON_POPUP` `.action.onClick(fragment, item)` |
| `class QueueListActions(context: Context, queue: PlayQueue, asToday: Runnable) : ListActions` | `playAsToday` = `asToday.run()`; `enqueueAll` = `NavigationHelper.enqueueOnPlayer(context, queue)`; `enqueueAllNext`: `target = PlayerHolder.getInstance().player?.playQueue`; if `null`, `NavigationHelper.enqueueNextOnPlayer(context, queue)`; else toast `R.string.enqueued_next`, `target.append(queue.streams)`, then `target.move(from, to)` for each of `movesForPlayNext(target.size(), queue.streams.size, target.index)` (index read after the append) |

The queue is read by index (`PlayQueue.getItem(int)` answers `null` out of bounds, `:204-209`),
not by iterating `getStreams()`: Q1 can run on the round-play timer's thread
(`player/AutoQueueController.kt:78-82`) while a tap reads the queue on the main thread, and an
iterator would throw then. For the same reason `AutoQueuedRecord` is synchronized.

`QueueGuardDialog.kt`:

```kotlin
fun interface TapDialogExtension { fun create(context: Context): TapDialogAddOn }
interface TapDialogAddOn { val view: View; fun onChoice(choice: GuardChoice) }   // only for a row click that is then carried out, right before it; never CANCEL, never a dropped click
internal class DialogGuardUi(activity: FragmentActivity, owner: LifecycleOwner, extension: TapDialogExtension?) : GuardUi
```

An `androidx.appcompat.app.AlertDialog` built like `InfoItemDialog.java:57-97`. Custom title =
upstream `R.layout.dialog_title` (no new layout): `itemTitleView` = video title (tap) or
`enh_queue_guard_list_count` (list); `itemAdditionalDetails` = `enh_queue_guard_queue_count`, made
visible. `setItems` with three rows in the order `ADD_TO_QUEUE`, `PLAY_NEXT`, `PLAY_NOW` (tap:
`…_add_to_queue`, `…_play_next`, `…_play_now`; list: `…_add_all`, `…_play_all_next`,
`…_replace_queue`); `setNegativeButton(R.string.cancel, null)`. Tap dialog only: the extension's
view through `setView`. A row click is dropped unless `owner.lifecycle` is at least `STARTED`. An
observer on `owner.lifecycle` dismisses the dialog on `ON_DESTROY` (rotation recreates
`MainActivity`) without calling `onChoice`.

## 4. Resources

`res/values/enhancements_queue_guard.xml`, root element
`<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">` as
upstream `strings.xml:2`. No `values-xx` copies, no layout, nothing in `strings.xml` or
`settings_keys.xml`. Reused upstream strings: `cancel`, `show_stream_details`, `enqueued`,
`enqueued_next`. The settings entry is S1; being ordinary XML, its default is written at start-up
(`settings/NewPipeSettings.java:76`) and the settings search finds it.

| Name | English text |
|---|---|
| `enh_queue_guard_ask_key` (`translatable="false"`) | `enh_queue_guard_ask` (boolean preference, default `true`) |
| `enh_queue_guard_ask_title` | Ask before replacing the queue |
| `enh_queue_guard_ask_summary` | Tapping a video or a play button asks what to do with the queue that is playing. A single video in the main player is replaced without asking |
| `enh_queue_guard_add_to_queue` | Add to queue |
| `enh_queue_guard_play_next` | Play next |
| `enh_queue_guard_play_now` | Play now (replaces the queue) |
| `enh_queue_guard_add_all` | Add all to queue |
| `enh_queue_guard_play_all_next` | Play all next |
| `enh_queue_guard_replace_queue` | Replace queue |
| plurals `enh_queue_guard_queue_count` | one: `%d item in the queue`; other: `%d items in the queue` |
| plurals `enh_queue_guard_list_count` | one: `%d video in this list`; other: `%d videos in this list` |

## 5. AC coverage

JVM tests: `app/src/test/java/org/schabi/newpipe/enhancements/queueguard/`, JUnit 4, hand-written
fakes (a `GuardUi` that records the prompt and keeps `onChoice`, recording `TapActions` and
`ListActions`, a mutable `PlayerStateSource`). P = `QueueGuardPolicy`, C = `QueueGuardController`,
R = a test that parses the two resource XML files with the JDK parser (Gradle runs unit tests in
`app/`; fall back to `app/src/main/res` when `src/main/res` is absent). "Manual" = on a device.

| AC | Implemented by | Verified by |
|---|---|---|
| 1 | T1-T4, Q1, `AutoQueuedRecord`, `PlayerState.worthGuarding`, `P.onTap` | JVM: `worthGuarding` over the table of 3.1; `AutoQueuedRecord` (5.1); P gives `ASK` for every state worth guarding (`BACKGROUND` and `POPUP` with one video and with several, `MAIN` with two of the user's videos, with and without auto-enqueued ones) and for `startsListInBackground`; C returns `true`, calls only `askForTap`, no action before a choice. Manual: one tap per list (wiring); in the main player, enqueue a second video, then tap (the glue reads the live queue) |
| 2 | C -> `askForTap`, `DialogGuardUi`, strings | JVM: C passes `tap.title` and `queueSize`; R: the four English texts. Manual: look of the dialog |
| 3 | `onTapChoice` -> `enqueue()` | JVM: P; C calls only `enqueue()`. Manual: toast and queue equal long-press "Enqueue" |
| 4 | `onTapChoice` -> `enqueueNext()` | JVM: P, C. Manual: position in the queue |
| 5 | *playNow* rows | JVM: P for `MAIN`, `BACKGROUND`, `POPUP`, Music Player Mode; C: `openAsToday()` re-enters `onVideoTap`, which returns `false`. Manual: each player |
| 6 | `CANCEL` -> `NONE`; dismissals do not call back | JVM: C with `CANCEL` and with no callback: no action. Manual: Back, outside tap, rotation (needs the activity lifecycle) |
| 7 | `P.onTap`, first row | JVM: P gives `OPEN_AS_TODAY` while nothing is playing and for `MAIN` with one video of the user's and none, one or two auto-enqueued ones, switch on and off, with and without `startsListInBackground`; C returns `false`, UI and actions untouched. Manual: with "Auto-enqueue next stream" on, watch a video and tap a related one: no dialog; let playback move on by itself twice, switch to the popup player and back, tap again: no dialog (Q1 and rule 5 of 3.1) |
| 8 | `PlayerState.isCurrent` | JVM: P (same url on another service still asks) |
| 9 | `cb` reads `player.current()` at choice time | JVM: C with the fake player set to `IDLE` between prompt and choice, all three choices; and set to `MAIN` with one video instead: the choice is carried out as if nothing had changed (3.2) |
| 10 | B1-B5, `P.onListPlay`, C -> `askForList` | JVM: P gives `ASK` for every state worth guarding; C passes `videoCount` and `queueSize` (the whole queue, an auto-enqueued video included); R: texts. Manual: each of the 15 buttons |
| 11 | `enqueueAll()`, `enqueueAllNext()`, `movesForPlayNext` | JVM: P, C; the moves applied to a `MutableList` (`add(to, removeAt(from))`) give list order behind the current item, current first, middle and last. Manual: toast and queue on a device (the glue edits the live `PlayQueue`) |
| 12 | `PLAY_NOW` -> `playAsToday()` | JVM: P, C. Manual: one button per page |
| 13 | as 6 | JVM: C. Manual: as 6 |
| 14 | `P.onListPlay`, first row | JVM: P gives `PLAY_AS_TODAY` while nothing is playing and for `MAIN` with one video of the user's (with and without auto-enqueued ones), switch on and off; C calls `playAsToday()` at once, no UI |
| 15 | S1, `enh_queue_guard_ask_key`, `PrefsGuardSettings` | JVM: R (S1 is the element right after the one with `@string/preferred_open_action_key`, `defaultValue` `true`; title and the summary of section 4); `ASK_BEFORE_REPLACING_DEFAULT` is `true`; P uses one setting for taps and lists. Manual: Settings |
| 16 | switch-off rows | JVM: P for every kind, worth guarding or not; C: `MAIN` returns `false` (one video or several), `BACKGROUND` and `POPUP` call `startIn…()` without UI; list: `playAsToday()` |
| 17 | M1, `P.offersStreamDetails`, `streamDetailsInsertIndex` | JVM: P (`BACKGROUND`, `POPUP` playing: `true`; `MAIN` with one video or several, idle: `false`); index is `-1` when present. Manual: menu, page opens, playback goes on |
| 18 | section 4 | JVM: R (every name is in `values/`, only the key is untranslatable) |
| 19 | stock `AlertDialog` list and button | Manual: D-pad and TalkBack (needs a device) |

Wiring on the JVM (recommended): a test reads the 9 files as text and expects these counts of
lines containing `ENH:queue-guard`: `BaseListFragment.java` 1, `LocalPlaylistFragment.java` 5,
`StatisticsPlaylistFragment.java` 4, `FeedFragment.kt` 5, `PlaylistFragment.java` 3,
`ChannelVideosFragment.java` 3, `InfoItemDialog.java` 1, `video_audio_settings.xml` 1,
`PlayQueue.java` 1. The `PlayQueue.java` line names `QueueGuard` in full like the other code
hooks and contains `if (autoQueued)` and `.noteAutoQueued(itemList)`.

### 5.1 For the Tests layer: what A11 and A13 change

The 164 existing tests, by class. "Changes" means the test is right about everything except that
it expects a dialog for the main player with one video; the count is from reading the tests, not
from a run.

| Class | Tests | Stay | Change | New, at least |
|---|---|---|---|---|
| `GuardModelTest` | 25 | 25 | 0 | 12 |
| `QueueGuardPolicyTest` | 45 | 40 | 5 | 8 |
| `QueueGuardControllerTest` | 45 | 27 | 18 | 5 |
| `QueueGuardControllerContractTest` | 31 | 31 | 0 | 0 |
| `QueueGuardResourcesTest` | 10 | 9 | 1 | 0 |
| `QueueGuardWiringTest` | 8 | 8 | 0 | 1 |
| Total | 164 | 140 | 24 | 26 |

`Fakes.kt`: `playing(...)` gets a last parameter `autoQueued: Int = 0`, passed on as
`autoQueuedCount`. `SOMETHING_PLAYING` and `NOTHING_PLAYING` stay as they are. Two fixtures are
added, written `queueSize`/`autoQueued`. `WORTH_GUARDING`: `BACKGROUND` and `POPUP` with 1/0 and
3/0, `BACKGROUND` with 3/2, `MAIN` with 3/0, 2/0, 3/1 and 5/3. `SINGLE_IN_MAIN`: `MAIN` with 1/0,
2/1, 3/2 (A13: watched, playing by itself, next one auto-enqueued), 2/2 and 1/1 (no video of the
user left).

- `GuardModelTest`. All stay: `somethingPlaying`, `isCurrent`, the moves and the menu index did
  not change. New, `PlayerState`: `worthGuarding` is `true` for every state of `WORTH_GUARDING`
  and `false` for every state of `SINGLE_IN_MAIN`, one test per line of the table in 3.1;
  `false` for every state of `NOTHING_PLAYING`, whatever `autoQueuedCount`; `autoQueuedCount`
  is `0` for `IDLE` and for the four-argument constructor; `somethingPlaying` and `isCurrent`
  do not depend on `autoQueuedCount`. New, `AutoQueuedRecord` (rules 1 to 6 of 3.1): an empty
  record counts `0` in any queue; added uuids are counted, the others not, whatever the order of
  the queue (rules 1 to 3); a uuid that is no longer in the queue is not counted and one that
  was never added is the user's (rules 4, 5); adding a uuid twice counts it once per occurrence
  in the queue; with `limit` 3, a fourth uuid makes the first forgotten and keeps the other
  three (rule 6); `AUTO_QUEUED_RECORD_LIMIT` is `1000`; the A13 scenario end to end: record B
  and C, the queue A, B, C gives a state with `autoQueuedCount` 2 that is not worth guarding in
  `MAIN`, and A, B, C, D gives one that is.
- `QueueGuardPolicyTest`. Change, by iterating over `WORTH_GUARDING` instead of
  `SOMETHING_PLAYING`: `ac8_sameUrlOnAnotherServiceIsNotTheCurrentItemAndAsks`,
  `ac1_tapInAListWithMusicPlayerModeAsksInEveryPlayer`,
  `ac10_playButtonWhileSomethingIsPlayingAsksInEveryPlayer`,
  `ac15_oneSwitchCoversVideoTapsAndPlayButtons`; and `ac1_queueOfOneItemIsProtectedToo`, which
  now holds for `BACKGROUND` and `POPUP` only (A11 overrides A1 for the main player). The other
  40 stay, among them every `onTapChoice`, `onListChoice` and `offersStreamDetails` test (they
  run over `SOMETHING_PLAYING`, main player with one video included, and must keep doing so) and
  the switch-off tests (`MAIN` gives `OPEN_AS_TODAY` either way). New: AC-7 `onTap` over
  `SINGLE_IN_MAIN`, switch on and off, both kinds of tap, gives `OPEN_AS_TODAY`; AC-1 `MAIN`
  with two of the user's videos asks, with and without auto-enqueued ones (2/0, 3/1, 5/3); AC-14 `onListPlay` over
  `SINGLE_IN_MAIN`, switch on and off, gives `PLAY_AS_TODAY`; AC-10 `onListPlay` asks over
  `WORTH_GUARDING`; AC-16 no state of `SINGLE_IN_MAIN` asks or starts another player with the
  switch off; AC-3 to AC-5 `onTapChoice` over `SINGLE_IN_MAIN` gives `ENQUEUE`, `ENQUEUE_NEXT`,
  `OPEN_AS_TODAY`; AC-11, AC-12 `onListChoice` over `SINGLE_IN_MAIN` gives `ENQUEUE_ALL`,
  `ENQUEUE_ALL_NEXT`, `PLAY_AS_TODAY`; AC-17 `false` over `SINGLE_IN_MAIN`, `true` for
  `BACKGROUND` whatever `autoQueuedCount` (2/1, 3/2).
- `QueueGuardControllerTest`. Change, by the same swap of fixture (each taps or presses a button
  in every state of `SOMETHING_PLAYING` and expects the prompt):
  `ac1_tapWhileSomethingIsPlayingIsTakenOverAndOnlyOpensTheDialog`,
  `ac1_tapWithMusicPlayerModeIsTakenOverAndOnlyOpensTheDialog`, `ac3_addToQueueCallsOnlyEnqueue`,
  `ac4_playNextCallsOnlyEnqueueNext`, `ac5_playNowWithMusicPlayerModeReplaysTheTapInEveryPlayer`,
  `ac6_cancelCallsNoAction`, the three `ac9_…AfterThePlayerWasClosed…`,
  `ac10_playButtonWhileSomethingIsPlayingOnlyOpensTheListDialog`, the four `ac11_…` and two
  `ac12_…` that iterate over the states, `ac13_cancelInTheListDialogCallsNoAction`; and
  `ac2_dialogGetsTheQueueSizeOfEachPlayer`, where `MAIN` with `queueSize` 1 shows no dialog (use
  2 and 40 there). The other 27 stay: they use one state worth guarding (`playing(kind)` is three
  videos, `BACKGROUND` with one) or expect no dialog anyway (current item, switch off, empty
  list). New: AC-7 a tap over `SINGLE_IN_MAIN` returns `false` with an empty journal, switch on
  and off; AC-14 a play button there calls only `playAsToday()`; AC-1 the state is read at every
  tap (`MAIN` with one video: not taken over; the same scene with two: dialog); AC-2 the prompt
  gets the whole `queueSize` when some of the videos are auto-enqueued (`MAIN` 5/3 shows 5); AC-3 to AC-5 and AC-11,
  AC-12 a prompt in `MAIN` with three videos, the player set to `MAIN` with one before the
  choice: `enqueue()`, `enqueueNext()`, `openAsToday()` with the replay let through,
  `enqueueAll()`, `enqueueAllNext()`, `playAsToday()`.
- `QueueGuardControllerContractTest`. All stay: the controller did not change and the policy
  there is a fake.
- `QueueGuardResourcesTest`. `ac15_switchHasItsTitleSummaryAndPreferenceKey` expects the new
  summary of section 4. The others stay.
- `QueueGuardWiringTest`. The 8 stay. New: `PlayQueue.java` holds exactly one marked line, with
  the fragments named above (`assertCodeHooks` with `total = 1`, `taps = 0` covers the name).
- The glue (`HolderPlayerStateSource`, `QueueGuard.noteAutoQueued`) has no JVM test, as before:
  it is in the "Manual" cells of AC-1 and AC-7. Rules 3 and 5 of 3.1 hold by construction (the
  record is keyed by uuid and nothing removes from it on a move); on the JVM they are pinned by
  the `AutoQueuedRecord` tests above.

## 6. Extension points for default-open-action

It adds files and replaces the two lines named in 3.3: `policy` with its own `GuardPolicy`
wrapping `QueueGuardPolicy`, and `tapDialogExtension` with its checkbox. Its default action and
"Start playback in" are `onTap` results (`ENQUEUE`, `ENQUEUE_NEXT`, `START_BACKGROUND`,
`START_POPUP` exist already), its AC-13 is `onTapChoice`, its AC-11 is `offersStreamDetails`, its
checkbox is a `TapDialogAddOn` (tap dialog only). Its settings are S1-style lines of its own.

What A11 changes for it (its plan at `d7fe734`, rows 1-23 of its 4.2). Nothing it compiles
against changed: `PlayerState` keeps its four-argument construction, `somethingPlaying`,
`isCurrent` and the five `GuardPolicy` signatures are as they were, and its G1 and G2 lines stand.
Its "playing" stays `player.somethingPlaying` and must not become `worthGuarding`: its AC-3 has a
tap carry out the default action while something is playing, which is how *Add to queue* starts a
queue from a single video in the main player. So rows 1-5, 7-19, 20, 21, 22 and 23 are untouched.

One state is affected: something is playing, the queue is not worth guarding (main player, one
video), the tap is not on the current item, and the switch is on. Here `QueueGuardPolicy.onTap`
now answers `OPEN_AS_TODAY` and `onListPlay` answers `PLAY_AS_TODAY`, and `OpenActionPolicy` reads
both answers as "ask is off" (its 4.2: `b == ASK`, and `base.onListPlay(player) != ASK`). Left as
it is, it would carry out the default action there although the switch is on (*Add to queue*
enqueues without a dialog) and offer "Show stream details" by row 21. Its row 6 (`ASK`, its AC-4
"opens the dialog as before, the default action is not used") and row 21b no longer describe that
state. A14 decided it, in both specs. So its plan needs an amendment of its own:

- A14: in that state the tap behaves as today (this spec's AC-7) and the default
  action is not used (its AC-4). Row 6 splits into "worth guarding -> `ASK`" and "playing, not
  worth guarding, ask on -> `OPEN_AS_TODAY`"; row 21b stays as written. `OpenActionPolicy` can no
  longer take "not `ASK`" for "ask is off": it reads the switch itself, through `GuardSettings`
  (`PrefsGuardSettings` is `internal` to the module, so its G1 line can pass it). Nothing more is
  needed from this feature.
- Its tests that pair ask on with `MAIN`, `queueSize = 1` (its `SOMETHING_PLAYING` and
  `playingIn(MAIN)` contain that state) and expect a dialog change their states:
  `OpenActionPolicyTest` `ac4_row6_…`, `ac12_ac3_rows6to8and11_…` (its row 6 part),
  `ac15_playButtonsAskOrPlayAsTodayWhateverTheTwoSettingsAre`; `OpenActionControllerTest`
  `ac4_row6_…`, `ac9_row13_cancelInTheDialogCallsNoAction`,
  `ac15_askOn_playButtonOpensTheListDialog…`. New tests cover the split row. Its 144 tests
  otherwise stand, `DefaultSettingsEqualQueueGuardTest` (its AC-14) included: with its settings on
  their defaults it still equals `QueueGuardPolicy` in every state.
- Until its code is amended, its row 21b checks with ask on fail as well, for the reason above
  (`OpenActionPolicyTest` `ac11_row21b_mainPlayerInUse_askOn_…`, `OpenActionControllerTest`
  `ac11_rows20to23_…`); once it is, they stand.

## 7. Order of work

(1) `res/values/enhancements_queue_guard.xml`, then S1. (2) `GuardModel.kt`, `GuardPolicy.kt`,
`QueueGuardController.kt`, until the unit tests pass. (3) `QueueGuardGlue.kt`,
`QueueGuardDialog.kt`, `QueueGuard.kt`. (4) Hooks M1, T1-T4, B1-B5; `git grep -c "ENH:queue-guard"`
gives the counts of section 5. (5) Device pass over the "Manual" cells with the main, the
background and the popup player.

For A11 and A13, on top of the built feature: (a) `autoQueuedCount`, `worthGuarding` and
`AutoQueuedRecord` in `GuardModel.kt`, the two first rows in `GuardPolicy.kt`; (b)
`autoQueuedRecord`, `QueueGuard.noteAutoQueued` and `autoQueuedCount` in
`HolderPlayerStateSource`, then hook Q1; (c) the summary string; (d) device pass over the
"Manual" cells of AC-1 and AC-7 with "Auto-enqueue next stream" on and off.

## 8. Risks and edge cases

- Form B changes 17 lines instead of adding them, so `git diff dev` (definition of done 4) also
  shows 17 removed lines. If upstream edits one, the rebase conflicts and the swap is redone.
- Upstream "enqueue next" moves only the last video of a list behind the current one
  (`player/PlayerStartController.kt:47-55`), and one intent per video would not survive
  `util/SerializedCache.java:22` (5 entries). So *Play all next* edits the player's queue
  directly, as `player/PlayQueueActivity.java:303` does: one `MoveEvent` per video, loaded
  debounced (`player/playback/MediaSourceManager.java:277-279`). Try a list of several hundred.
- `PlayQueue.append` drops an auto-queued tail and shuffles the new items while the queue is
  shuffled (`player/playqueue/PlayQueue.java:327-337`).
- The current item (AC-8) is matched by service id and exact url, as upstream does
  (`VideoDetailFragment.kt:661`). Two url forms of one video would show the dialog.
- The replay flag is main-thread only and must be cleared in `finally`.
- A feed item without duration or uploader url is loaded first, with a "Loading stream details"
  toast (`util/SparseItemUtil.java:51-65`), exactly as from the long-press menu.
- The long-press menu hides "Enqueue next" for queues of one item (`InfoItemDialog.java:272`) and
  "Start playing in a popup" for audio-only items (`:287`); the dialog offers *Play next* and
  *Play now* regardless.
- AC-17 "in use" is read as: something is playing in the background or popup player.
- With a single video in the main player a tap replaces it without asking, as upstream does. The
  auto-enqueued video behind it goes with it, and so does a paused video.
- However long playback has moved on by itself in the main player, a tap replaces the queue
  without asking, the watched videos included (A13). The user's second video is what makes a
  queue.
- An auto-enqueued video the user dragged to another place still does not count (rule 3 of 3.1),
  although upstream then stops treating it as auto-enqueued.
- Q1 runs inside `PlayQueue`'s lock and possibly off the main thread: `noteAutoQueued` must stay
  a plain set insertion. It loads `QueueGuard`, whose initialiser must keep touching neither
  `App.getApp()` nor resources (as default-open-action's plan already requires).
- The record is per process: after a process death it is empty, and it holds at most
  `AUTO_QUEUED_RECORD_LIMIT` uuids (36 characters each). Both err towards asking.
- While the main player is starting and its queue is still `null`, `queueSize` is `0`
  (`PlayerHolder.java:95-101`): nothing is playing, as before A11.

## 9. Assumptions

Q4 to Q9 are confirmed by the spec. Q12 to Q14 were raised by the change for A11; A13 changed
what was planned. Nothing is open.

| Q | Planned with | Confirmed by | Affects |
|---|---|---|---|
| Q4 | *Play now* in the background or popup player plays the tapped video alone, in every list | A4 | AC-5, AC-16 |
| Q5 | player closed during the list dialog: *Add all* and *Play all next* start the list in the background player; *Replace queue* is the button as today | A5 | AC-11, AC-12 |
| Q6 | while the player's queue is shuffled, added videos are shuffled as today's "Enqueue" does | A6 | AC-11 |
| Q7 | the switch is on the screen titled "Player", directly below "Preferred 'open' action" | A7 | AC-15 |
| Q8 | a play button on a list with no videos loaded behaves as today, no dialog | A8 | AC-10 |
| Q9 | *Play now* in the main player is today's tap even while autoplay is not allowed: the page opens, the running player stops, the video does not start | A9 | AC-5, AC-16 |
| Q12 | an accessor line in `PlayQueue.java` as a hook form | A12 accepted the form; no longer used, Q1 is now an added call (section 2) | AC-1, AC-7, AC-10, AC-14 |
| Q13 | only the one video upstream marks is not counted | A13 decided otherwise: no video the player added by itself counts, played or not. Planned accordingly: `AutoQueuedRecord`, rules 1 to 6 of 3.1 | AC-1, AC-7, AC-10, AC-14 |
| Q14 | default-open-action, main player with one video, switch on: the tap behaves as today and the default action is not used; with the switch off its default action applies as its AC-3 says | A14 | section 6; its AC-3, AC-4, AC-11 |

Decided by this plan, within the spec's words ("videos the player added by itself ... never
count"): an auto-enqueued video the user moves stays the player's, and the same video enqueued
by hand is a new item and the user's (rules 3 and 4 of 3.1).

## Amendment 2

For spec section "Amendment 2, 2026-11-18" (AM-1 to AM-8, spec commit `a00a2a8`). Line numbers in
this section are those of this branch at `a00a2a8`, not of `dev`. `QG` is still
`org.schabi.newpipe.enhancements.queueguard.QueueGuard`, written out in full in every hook.

**What this section overrides.** The note at the top of this plan ("Changed 2026-11-15"); in
section 2 the hook Q1 and the rows `MainActivity.java:866 ...` and `VideoDetailFragment.kt:342,
:531, :550` of "Callers left alone on purpose"; in 3.1 `autoQueuedCount`, `worthGuarding`,
`AutoQueuedRecord`, `AUTO_QUEUED_RECORD_LIMIT` and the rules 1 to 6; in 3.2 the first row of
`onTap` and of `onListPlay`; in 3.3 `noteAutoQueued`, `autoQueuedRecord` and `autoQueuedCount` in
`HolderPlayerStateSource`; in section 4 the summary of the switch; section 5.1; in section 6 the
paragraphs on A11 and A14; in section 8 the five risks about a single video and auto-enqueued
videos; in section 9 the rows Q12 to Q14. Everything else stands: T1-T4, B1-B5, M1, S1, the
controller, the dialog rows, `movesForPlayNext`, `streamDetailsInsertIndex`.

Summary: 13 upstream files, 33 lines marked `ENH:queue-guard` (was 9 and 24): Q1 is removed, 10
lines are added in 5 files that had none (the tenth is P5, A2.15, for AM-9; AM-10 needs no line). No signature of `GuardPolicy`, `QueueGuardController`,
`TapActions`, `ListActions` or `GuardUi` changes.

### A2.1 Model (`GuardModel.kt`, plain Kotlin; replaces the parts of 3.1 named above)

```kotlin
data class PlayerState(
    val kind: PlayerKind?, val queueSize: Int, val currentServiceId: Int, val currentUrl: String?,
) {
    /** Spec, "a queue worth guarding" as amended: a player is open and holds at least one item. */
    val somethingPlaying: Boolean get() = kind != null && queueSize > 0
    fun isCurrent(tap: TapRequest): Boolean = isCurrent(VideoRef(tap.serviceId, tap.url))
    fun isCurrent(video: VideoRef): Boolean =
        somethingPlaying && currentUrl == video.url && currentServiceId == video.serviceId
    companion object { val IDLE = PlayerState(null, 0, -1, null) }
}
/** A video by service and exact address, as [PlayerState.isCurrent] compares it. */
data class VideoRef(val serviceId: Int, val url: String)
/** How carrying a request out "as today" would play the video. */
enum class OpenWay {
    LIST_TAP,           // a video tap in a list (T1-T4): everything this plan said before
    PAGE_CONTROL,       // a play control of the video page: play/thumbnail, Background, Popup, mini player play
    VIDEO_PAGE,         // the video's page is opened: timed link, address typed in search, "show info"/"video player" for a video from another app
    BACKGROUND_PLAYER,  // "background player" chosen for a video from another app
    POPUP_PLAYER,       // "popup player" chosen for a video from another app
}
data class TapRequest(
    val serviceId: Int, val url: String, val title: String,
    val startsListInBackground: Boolean = false,
    val way: OpenWay = OpenWay.LIST_TAP,
    val relatedTo: VideoRef? = null,   // LIST_TAP only: the video whose list of related videos was tapped
)
/** Whether carrying out [way] as today replaces the player's queue (AM-3, AM-5, AM-6). */
fun replacesQueue(way: OpenWay, player: PlayerState): Boolean
/** [choice]: the key the router handles. `null`: an action that never plays (download, add to playlist, unknown). */
fun openWayOfRouterChoice(choice: String, showInfo: String, videoPlayer: String, backgroundPlayer: String, popupPlayer: String): OpenWay?
enum class DetailsEffect { AS_TODAY, SHOW_PLAYING_PAGE, SELECT_THEN_SHOW_PAGE, NONE }
/** "Details" of the queue item at [itemIndex] (-1: no longer in the queue) while the player is on [currentIndex] (AM-4). */
fun queueItemDetails(player: PlayerState, itemIndex: Int, currentIndex: Int): DetailsEffect
```

The two new fields of `TapRequest` are last and have defaults: every existing four-argument call
means a list tap outside a related list. `worthGuarding` is removed, not kept as an alias: as
amended it equals `somethingPlaying`, and one name for it is enough.

`replacesQueue` (not playing: `false` in every row):

| `way` | `MAIN` | `BACKGROUND` | `POPUP` | Why (checked in the code, A2.3) |
|---|---|---|---|---|
| `LIST_TAP` | `true` | `true` | `true` | not used by the rules; listed for completeness |
| `PAGE_CONTROL` | `true` | `true` | `true` | the control sends the page's own queue to the player |
| `VIDEO_PAGE` | `true` | `false` | `false` | opening a page starts or stops the main player; beside another player it only shows the page |
| `BACKGROUND_PLAYER`, `POPUP_PLAYER` | `true` | `true` | `true` | the video alone is sent to that player |

`openWayOfRouterChoice`: `showInfo` and `videoPlayer` -> `VIDEO_PAGE`; `backgroundPlayer` ->
`BACKGROUND_PLAYER`; `popupPlayer` -> `POPUP_PLAYER`; anything else -> `null`.

`queueItemDetails`, first match wins:

| Condition | Result |
|---|---|
| not `player.somethingPlaying`, or `player.kind != MAIN` | `AS_TODAY` |
| `itemIndex < 0` | `NONE` |
| `itemIndex == currentIndex` | `SHOW_PLAYING_PAGE` |
| otherwise | `SELECT_THEN_SHOW_PAGE` |

### A2.2 Rules (`GuardPolicy.kt`; replaces the `onTap`, `onTapChoice` and `onListPlay` rows of 3.2)

`QueueGuardPolicy`, first match wins. playing = `player.somethingPlaying`, ask =
`settings.askBeforeReplacing()`, list = `tap.way == LIST_TAP`.

| Function | # | Condition | Result | Criterion |
|---|---|---|---|---|
| `onTap` | 1 | not playing | `OPEN_AS_TODAY` | AM-1 |
| | 2 | `player.isCurrent(tap)`, and `tap.way` is `LIST_TAP`, `PAGE_CONTROL` or `VIDEO_PAGE`, or `player.queueSize == 1` | `OPEN_AS_TODAY`; what the hook then does for the current item is in A2.15 | AC-8, AM-9, AM-10 |
| | 3 | not ask, list | *playNow* | AC-16 |
| | 4 | not ask, not list | `OPEN_AS_TODAY` | AM-7 |
| | 5 | list, `tap.relatedTo != null` and `player.isCurrent(tap.relatedTo)` | `OPEN_AS_TODAY` | AM-2 |
| | 6 | not list and not `replacesQueue(tap.way, player)` | `OPEN_AS_TODAY` | AM-5, AM-6 |
| | 7 | otherwise | `ASK` | AC-1, AM-1, AM-3, AM-5, AM-6 |
| `onTapChoice` | 8 | `CANCEL` | `NONE` | AC-6 |
| | 9 | `ADD_TO_QUEUE` | `ENQUEUE` | AC-3, AC-9 |
| | 10 | `PLAY_NEXT` | playing: `ENQUEUE_NEXT`, else `ENQUEUE` | AC-4, AC-9 |
| | 11 | `PLAY_NOW`, not list | `OPEN_AS_TODAY` | AM-3 ("what the pressed control does today") |
| | 12 | `PLAY_NOW`, list | *playNow* (unchanged) | AC-5, AC-9 |
| `onListPlay` | 13 | not playing, or not ask | `PLAY_AS_TODAY` | AM-1, AC-16 |
| | 14 | otherwise | `ASK` | AC-10 |

*playNow*, `onListChoice` and `offersStreamDetails` are unchanged. What the table means:

- **AM-1.** Rows 1 and 13 test "playing" again, as before A11: one video in the main player asks,
  and so does a queue that grew by "auto-enqueue next stream".
- **AM-2** is carried by the request, decided by the policy: the hook T1 is unchanged, the glue
  fills `relatedTo` when the tapped list is the related list (A2.4), and row 5 compares it with the
  current item. `relatedTo` is ignored for every other `way`. With the switch off row 3 comes
  first, so a related tap then does *playNow* as AC-16 says (Q18).
- **AM-3.** "Page of the current item" and "page of another video" are told apart by row 2: the
  glue builds the request from the video the page shows, and `isCurrent` compares it with the
  player's current item (service and exact address). Page of another video: row 7, the dialog;
  *Play now* is row 11, the replay of the pressed control.
- **AM-5, AM-6.** "Would replace the queue" is `replacesQueue`, row 6. A link to a time in the
  current item is row 2.
- **AM-7.** Rows 3 and 4: with the switch off only list taps are changed by this feature.
- A choice is still judged on "playing" at the moment it is made (rows 9 to 12), as 3.2 says.

### A2.3 Hooks

Removed:

| # | File | Line removed |
|---|---|---|
| Q1 | `player/playqueue/PlayQueue.java:326` | `if (autoQueued) org.schabi.newpipe.enhancements.queueguard.QueueGuard.noteAutoQueued(itemList); // ENH:queue-guard` (the whole line; the file is then byte-identical to `dev`) |

Added, all form A (an added line; the original statements stay and are skipped when the hook
returns `true`; `replay` runs them again and the controller lets that call through):

| # | File, where | Added line |
|---|---|---|
| P1 | `fragments/detail/VideoDetailFragment.kt`, `private fun openBackgroundPlayer()`, between `:523` and `:524` (`player?.changeFullscreen(false)`) | `if (QG.onPageControl(this, vm.currentInfo) { openBackgroundPlayer() }) return // ENH:queue-guard` |
| P2 | same file, `private fun openPopupPlayer()`, between `:539` (the `}` that closes the permission check) and `:540` (`// See UI changes while the remote play queue changes`) | `if (QG.onPageControl(this, vm.currentInfo) { openPopupPlayer() }) return // ENH:queue-guard` |
| P3 | same file, `hosts.onPlayPauseClicked()`, first statement of the `else` branch, between `:817` (`} else {`) and `:818` (`beginStartupTrace()`) | `if (QG.onPageControl(this@VideoDetailFragment, vm.currentInfo) { onPlayPauseClicked() }) return // ENH:queue-guard` |
| P4 | same file, `hosts.playInMainPlayer()`, between `:835` and `:836` (`beginStartupTrace()`) | `if (QG.onPageControl(this@VideoDetailFragment, vm.currentInfo) { playInMainPlayer() }) return // ENH:queue-guard` |
| D1 | `QueueItemMenuUtil.java`, the `menu_item_details` branch, between `:55` and `:56` (`NavigationHelper.openVideoDetail(context, item.getServiceId(),`) | `if (QG.openQueueItemDetails(context, playQueue, item)) return true; // ENH:queue-guard` |
| L1 | `util/external_communication/InternalUrlsHandler.java`, `handleTimestampUrl`, between `:129` (`if (linkType == StreamingService.LinkType.STREAM && seconds != -1) {`) and `:130` (`return playOnMain(...)`) | `if (QG.onTimedLink(context, service, matchedUrl, seconds, () -> playOnMain(context, matchedUrl, service, seconds, disposables))) return true; // ENH:queue-guard` |
| L2 | `fragments/list/search/SearchFragment.java`, `search(String)`, first statement of the `subscribe(intent -> {` lambda, between `:1006` and `:1007` (`getFM().popBackStackImmediate();`) | `if (QG.onSearchAddress(SearchFragment.this, intent, () -> { getFM().popBackStackImmediate(); activity.startActivity(intent); })) return; // ENH:queue-guard` |
| R1 | `RouterActivity.java`, `handleChoice`, between `:560` (the `}` that closes the popup permission check) and the blank line `:561`, so before `if (selectedChoiceKey.equals(getString(R.string.download_key))) {` | `if (QG.onRouterChoice(this, currentService, currentLinkType, currentUrl, selectedChoiceKey, () -> handleChoice(selectedChoiceKey))) return; // ENH:queue-guard` |
| R2 | `RouterActivity.java`, `showDialog`, first statement of the chooser's `setOnDismissListener((dialog) -> {`, between `:363` and `:364` (`if (!selectionIsDownload && !selectionIsAddToPlaylist) {`) | `if (QG.isAsking(RouterActivity.this)) return; // ENH:queue-guard` |

In the file each line has `org.schabi.newpipe.enhancements.queueguard.QueueGuard` where the table
writes `QG`, so that no import line is added; P1 for example is
`if (org.schabi.newpipe.enhancements.queueguard.QueueGuard.onPageControl(this, vm.currentInfo) { openBackgroundPlayer() }) return // ENH:queue-guard`.
Each line is indented like the statement below it. No blank line is added or removed.

**Why these lines** (what runs between the control and the queue being replaced):

- **P1, P2.** `fragments/detail/DetailActions.kt:94-95` -> `hosts.openBackgroundPlayer()` /
  `openPopupPlayer()` (`VideoDetailFragment.kt:829-833`) -> the private functions at `:523`,
  `:534` -> `playQueueForIntent()` (`:574`, the page's own queue, or the shown video alone) ->
  `NavigationHelper.playOnBackgroundPlayer` / `playOnPopupPlayer` -> a player intent ->
  `player/PlayerStartController.kt:23`, which replaces the queue unless the two queues are equal
  (`:77`, `:155`). The two private functions have no caller but `hosts`, so every call is a
  press. The hook is in the fragment, not in `DetailActions.kt`, because `feature/crash-fixes-more`
  edits that file and because P3 has no line there. P2 sits after the permission check so that a
  missing popup permission gives today's toast instead of a dialog.
- **P4.** Play button and thumbnail are one view (`DetailActions.kt:92`) ->
  `hosts.playInMainPlayer()` -> `openVideoPlayerAutoFullscreen()` -> `openMainPlayer()` (`:553`) ->
  the player intent with the page's queue. `openMainPlayer` itself cannot carry the hook: the
  autoplay after a page has loaded (`:422`), the service connecting (`:632`) and a player switch
  (`util/NavigationHelper.java:431`) call it too, and none of them is a press.
- **P3.** `fragments/detail/MiniPlayerSheetController.kt:234` -> `hosts.onPlayPauseClicked()`.
  Its first branch (`:813-816`, the player is not stopped) only toggles play and pause and stays
  unhooked. The `else` branch calls `openVideoPlayer(false)` -> `openMainPlayer()` and can replace
  the queue; the hook is its first statement. Its `return` leaves the function before
  `sheet?.setPlayPauseImage(...)`, which has nothing new to show.
- **D1.** `player/PlayQueueActivity.java:328` is the only caller that shows "Details"
  (`player/PlayerQueueController.kt:265` hides it). A2.5 says what the hook does.
- **L1.** A link whose address ends in a time (`TextLinkifier.java:244`,
  `CommentTextOnTouchListener.java:55`) -> `handleTimestampUrl` -> `playOnMain` (`:201`) loads the
  video, builds a one-video queue at that time and calls `NavigationHelper.playOnMainPlayer(context,
  playQueue, false)` (`:222`) -> `MainActivity.java:866` -> `openVideoDetailFragment`. The hook is
  in front of `playOnMain`, not next to `:222`: `feature/keep-position` adds a line directly above
  `:222`, and a hook there would touch the same place (A2.9); and from `:130` the replay is the
  untouched upstream function, keep-position's line included. A time written as text
  (`internal://timestamp/`, `:101-103`) returns before the hook and seeks as today. `playOnPopup`
  (`:165`, with the call at `:186`) has no caller and gets no hook.
- **Links without a time** go `InternalUrlsHandler.java:150` (and `:132`) ->
  `NavigationHelper.openRouterActivity` -> `RouterActivity`, the same way as a link from another
  app. R1 covers them; there is no hook for them in `InternalUrlsHandler.java`.
- **L2.** `SearchFragment.java:997-1009`: an address that a service knows is turned into an intent
  off the main thread and then started -> `MainActivity.java:866`. The hook is in the lambda that
  starts it, because only there the link type is known without work on the main thread. The
  replay repeats the two statements below the hook: the lambda cannot call itself, and calling
  `search()` again would come back asynchronously, after the controller has stopped letting the
  replay through. `MainActivity.java:866` cannot carry the hook for the reason section 2 gives.
- **R1.** Every action of the router passes `handleChoice` (`RouterActivity.java:545`): the
  preferred action (`:284-329`), the chooser's buttons (`:347`) and a second press on a selected
  radio button (`:387`). After R1 come download (`:562`), add to playlist (`:571`), "show info"
  (`:579`, -> `MainActivity.java:866`) and the fetcher service (`:593`, -> `:806-810`, the three
  `NavigationHelper.playOn...` calls). R1 sits after the popup permission check for the reason
  given for P2.
- **R2.** The chooser finishes the router when it is dismissed (`:363-367`), which is right after
  one of its buttons called `handleChoice`. Without R2 the router would finish under the dialog
  that R1 has just opened, and the dialog would go with it (A2.4).

**Callers still left alone**: the long-press entries and the multi-select actions (AM-8); the
player switches (`PlayQueueActivity.java:142-154`, `PlayerClickController.kt:129`,
`Player.java:1388`); `CastReceiver.kt:215`; `MediaBrowserPlaybackPreparer.kt:81`,
`PlayPlaylistShortcutActivity.kt:45`; `VideoDetailFragment.kt:342` (reCAPTCHA reload); the
existing "Show stream details" entries; a channel or playlist link from another app with a player
action (Q19).

### A2.4 Android glue (changes to 3.3; no unit tests)

`QueueGuard.kt`: `noteAutoQueued` is removed. `onVideoTap` builds its request with
`relatedTo = (fragment as? RelatedItemsFragment)?.let { f -> f.url?.let { VideoRef(f.serviceId, it) } }`
(`fragments/list/videos/RelatedItemsFragment.java:137` sets both from the page's video;
`BaseListInfoFragment.java:316-324` has the public getters). New members:

```kotlin
@JvmStatic fun onPageControl(fragment: Fragment, info: StreamInfo?, replay: Runnable): Boolean
@JvmStatic fun openQueueItemDetails(context: Context, queue: PlayQueue, item: PlayerMediaItem): Boolean
@JvmStatic fun onTimedLink(context: Context, service: StreamingService, url: String, seconds: Int, replay: Runnable): Boolean
@JvmStatic fun onSearchAddress(fragment: Fragment, intent: Intent, replay: Runnable): Boolean
@JvmStatic fun onRouterChoice(activity: AppCompatActivity, service: StreamingService?, linkType: StreamingService.LinkType?, url: String?, choiceKey: String, replay: Runnable): Boolean
@JvmStatic fun isAsking(activity: Activity): Boolean
```

All but `openQueueItemDetails` and `isAsking` end in `controller.onVideoTap(request, actions, ui)`
and return its result; each returns `false` first (as today) in the cases listed.

| Member | Returns `false` at once when | Request | Actions | Dialog |
|---|---|---|---|---|
| `onPageControl` | `info == null` or `fragment.view == null` | `TapRequest(info.serviceId, info.url, info.name, way = PAGE_CONTROL)` | `DirectTapActions(appContext, { use -> use(SinglePlayQueue(info)) }, replay)` | `DialogGuardUi(fragment.requireActivity(), fragment.viewLifecycleOwner, tapDialogExtension)` |
| `onTimedLink` | `streamUrl(service, url)` is `null`, or `context` unwraps to no `FragmentActivity` | `TapRequest(service.serviceId, streamUrl, streamUrl, way = VIDEO_PAGE)` | `DirectTapActions(appContext, loader(service.serviceId, streamUrl, seconds * 1000L), replay)` | `DialogGuardUi(activity, activity, tapDialogExtension)` |
| `onSearchAddress` | the intent's `KEY_LINK_TYPE` extra (`util/Constants.kt`) is not `STREAM`, its `KEY_URL` is `null`, or `fragment.view == null` | `TapRequest(serviceId, streamUrl ?: url, url, way = VIDEO_PAGE)` from `KEY_SERVICE_ID`, `KEY_URL` | `DirectTapActions(appContext, loader(serviceId, url, null), replay)` | `DialogGuardUi(fragment.requireActivity(), fragment.viewLifecycleOwner, tapDialogExtension)`; when the result is `true`, `(fragment as? BaseStateFragment<*>)?.hideLoading()` undoes `SearchFragment.java:1000` |
| `onRouterChoice` | `linkType != STREAM`, `service == null`, `url == null`, or `openWayOfRouterChoice(choiceKey, getString(R.string.show_info_key), ...video_player_key, ...background_player_key, ...popup_player_key)` is `null` | `TapRequest(service.serviceId, streamUrl ?: url, url, way = that way)` | `DirectTapActions(appContext, loader(service.serviceId, url, null), replay, afterEnqueue = { activity.finish() })` | `DialogGuardUi(themed, activity, tapDialogExtension, dismissOnStop = true, onDismiss = { chosen -> askingIn.remove(activity); if (!chosen) activity.finish() })`, with `askingIn.add(activity)` before `controller.onVideoTap` and `askingIn.remove(activity)` again if it returns `false` |

- `appContext` = the application context: an enqueue outlives the screen that asked.
- `streamUrl(service, url)` = `service.streamLHFactory.let { it.getUrl(it.getId(url)) }`, `null`
  on any exception: the address in the form list items and queue items carry, so that row 2 can
  recognise the current item. `InternalUrlsHandler.java:210` does the same on the main thread.
- `loader(serviceId, url, startMs)` = `{ use -> SparseItemUtil.fetchStreamInfoAndSaveToDatabase(appContext, serviceId, url) { info -> use(if (startMs == null) SinglePlayQueue(info) else SinglePlayQueue(info, startMs)) } }`:
  the "Loading stream details" toast and the error notification of the long-press "Enqueue" on a
  sparse item (`util/SparseItemUtil.java:104-132`).
- `internal class DirectTapActions(context, load: ((PlayQueue) -> Unit) -> Unit, replay: Runnable, afterEnqueue: Runnable? = null) : TapActions`:
  `openAsToday` = `replay.run()`; `enqueue` = `load { NavigationHelper.enqueueOnPlayer(context, it) }`
  then `afterEnqueue?.run()`; `enqueueNext` the same with `enqueueNextOnPlayer`;
  `startInBackground` and `startInPopup` = `openAsToday()` (rows 4 and 11 never give them for
  these requests).
- `themed` = `ContextThemeWrapper(activity, if (ThemeHelper.isLightThemeSelected(activity)) R.style.LightTheme else R.style.DarkTheme)`,
  what the router builds its own chooser with (`RouterActivity.java:520-523`).
- `askingIn` = `Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())`, private to
  `QueueGuard`, main thread only. `isAsking(activity)` = `activity in askingIn`.
- `HolderPlayerStateSource.current()` loses `autoQueuedCount` and the reading of uuids.
  `autoQueuedRecord` is removed.

`QueueGuardDialog.kt`: `DialogGuardUi(context: Context, owner: LifecycleOwner, extension: TapDialogExtension?, dismissOnStop: Boolean = false, onDismiss: ((chosen: Boolean) -> Unit)? = null)`.
`context` replaces `activity` (existing callers pass the activity, unchanged). A row click that is
carried out sets `chosen` before it calls the add-on and `onChoice`. The dismiss listener removes
the observer as before and then calls `onDismiss(chosen)`, for every dismissal. With
`dismissOnStop` the lifecycle observer also dismisses on `ON_STOP`, as the router does with its
chooser (`RouterActivity.java:130-136`).

**The router screen, case by case.** It is invisible; only its dialogs show.

| What happens | Router afterwards |
|---|---|
| R1 returns `false` (nothing playing, current item, switch off, the action does not replace the queue, not a video) | `handleChoice` goes on as today and finishes as today |
| R1 opens the dialog from the preferred action | stays, with the dialog on it |
| R1 opens the dialog from a chooser button | the chooser closes, R2 keeps its dismiss listener from finishing, the router stays with the dialog on it |
| R1 opens the dialog from a second press on a radio button | the chooser stays open under the dialog; it goes when the router finishes |
| *Add to queue*, *Play next* | the load starts with the application context, then `activity.finish()` at once; the toast "Enqueued" follows when the video is loaded |
| *Play now* | `handleChoice` runs again and finishes as today: at once for the fetcher service, after its intent has started for "show info". `onDismiss(true)` does not finish, because "show info" would be cancelled by `disposables.clear()` (`RouterActivity.java:156-160`) |
| *Cancel*, Back, outside tap | `onDismiss(false)` -> `finish()`. Nothing else happens |
| the user leaves the app while the dialog is open | `ON_STOP` dismisses the dialog -> `finish()`, as leaving the chooser does today |
| rotation | the activity is destroyed, the dialog is dismissed without a choice; the new router instance handles the link again from `onStart` and asks again |

### A2.5 AM-4: "Details" of a queue item

`NavigationHelper.openVideoDetailFragment` (`util/NavigationHelper.java:400-451`), read for each
way of calling it:

- `playQueue == null`, `switchingPlayers == false` (today): beside the background or popup player
  `autoPlay` is `false` (`:417-420`), the page loads the video and nothing touches the player
  (`VideoDetailFragment.kt:587-592` returns unless the main player is selected). With the main
  player the load ends in `hideMainPlayerOnLoadingNewStream()` (`VideoDetailFragment.kt:415`, `:587-600`): the player is
  stopped for reuse or closed, and the page then plays the video alone. That is the reported
  loss.
- `playQueue` = the player's queue, `switchingPlayers == false`: the same stop, then the copy of
  the queue that came through the intent is started again from its current item. Playback
  restarts, and with autoplay off the player closes. Rejected.
- `switchingPlayers == true`: an already visible page ignores the address it is given
  (`:427-433`), sends its own queue to the main player and moves a background or popup player to
  the main player; with "start main player in fullscreen" it also goes fullscreen. It does not
  open the item's page. Rejected.

So no way of calling it shows another video's page under the main player: there the page is the
player, and it follows the current item (`VideoDetailFragment.kt:678-700`). Planned:

`openQueueItemDetails(context, queue, item)` asks
`queueItemDetails(HolderPlayerStateSource.current(), queue.indexOf(item), queue.index)`:

| Effect | The glue does | Returns | What the user gets |
|---|---|---|---|
| `AS_TODAY` (background or popup player, or nothing playing) | nothing | `false`: the upstream statement runs with the `null` queue | the item's page opens over the queue screen and does not play; the player goes on with its queue. The page's own play controls are then covered by AM-3 |
| `SHOW_PLAYING_PAGE` (main player, the current item) | `NavigationHelper.expandMainPlayer()`, then `(context as? Activity)?.finish()` | `true` | the queue screen closes and the page of the playing video is shown expanded. Playback is not interrupted, the queue is untouched |
| `SELECT_THEN_SHOW_PAGE` (main player, another item) | `PlayerHolder.getInstance().player?.selectQueueItem(item)`, then as above | `true` | playback moves to that item inside the queue, as a tap on its row does (`PlayQueueActivity.java:321`), and the page follows it. The queue keeps its items and order (Q16) |
| `NONE` (main player, the item is no longer in the queue) | nothing | `true` | nothing happens |

AM-7: no setting is read. The item is matched by its place in the queue (`PlayQueue.indexOf`
compares uuids, `PlayQueue.java:218`), not by address: a queue may hold a video twice.

### A2.6 Resources (changes section 4)

One text changes; no string is added, no key changes.

| Name | English text |
|---|---|
| `enh_queue_guard_ask_summary` | Asks what to do before a tap, a play button or a link replaces what is playing |

A request that knows no title (L1, L2, R1) shows the video's address where the dialog shows the
title (Q20).

### A2.7 Criteria

| AM | Implemented by | Verified by |
|---|---|---|
| 1 | rows 1 and 13; Q1, `AutoQueuedRecord`, `autoQueuedCount`, `worthGuarding` removed | JVM: policy and controller over every kind with one item and with several. Manual: A2.11 step 1 |
| 2 | `relatedTo` filled by `onVideoTap`, row 5 | JVM: policy rows 3 and 5 for every kind; controller returns `false`. Manual: step 2 |
| 3 | P1-P4, `onPageControl`, rows 2, 4, 7, 11 | JVM: policy, controller (*Play now* replays; the replay is let through). Manual: step 3 |
| 4 | D1, `queueItemDetails`, `openQueueItemDetails` | JVM: the table of A2.1. Manual: step 4 |
| 5 | L1, L2 (R1 for links without a time), `replacesQueue`, rows 2, 6, 7 | JVM: policy. Manual: step 5 |
| 6 | R1, R2, `openWayOfRouterChoice`, `replacesQueue` | JVM: both functions over their tables; policy. Manual: step 6 |
| 7 | rows 3 and 4; `queueItemDetails` reads no setting | JVM: policy with the switch off for every `way`. Manual: step 7 |
| 8 | no hook on those entries (unchanged) | JVM wiring: the counts of A2.8. Manual: step 8 |

### A2.8 Marked lines after the amendment, for the wiring tests

Lines containing `ENH:queue-guard`, below `app/src/main`:

| File | Lines | |
|---|---|---|
| `java/org/schabi/newpipe/fragments/list/BaseListFragment.java` | 1 | T1 |
| `java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java` | 5 | T2, B3 |
| `java/org/schabi/newpipe/local/history/StatisticsPlaylistFragment.java` | 4 | T3, B4 |
| `java/org/schabi/newpipe/local/feed/FeedFragment.kt` | 5 | T4, B5 |
| `java/org/schabi/newpipe/fragments/list/playlist/PlaylistFragment.java` | 3 | B1 |
| `java/org/schabi/newpipe/fragments/list/channel/ChannelVideosFragment.java` | 3 | B2 |
| `java/org/schabi/newpipe/info_list/dialog/InfoItemDialog.java` | 1 | M1 |
| `res/xml/video_audio_settings.xml` | 1 | S1 |
| `java/org/schabi/newpipe/player/playqueue/PlayQueue.java` | 0 | Q1 removed |
| `java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.kt` | 5 | P1-P4, each with `QG.onPageControl(`; P5 with `QG.playersQueueFor(vm.currentInfo)?.let { return it }` |
| `java/org/schabi/newpipe/QueueItemMenuUtil.java` | 1 | D1, `QG.openQueueItemDetails(context, playQueue, item)` |
| `java/org/schabi/newpipe/util/external_communication/InternalUrlsHandler.java` | 1 | L1, `QG.onTimedLink(` and `() -> playOnMain(context, matchedUrl, service, seconds, disposables)` |
| `java/org/schabi/newpipe/fragments/list/search/SearchFragment.java` | 1 | L2, `QG.onSearchAddress(SearchFragment.this, intent,` |
| `java/org/schabi/newpipe/RouterActivity.java` | 2 | R1 `QG.onRouterChoice(`, R2 `QG.isAsking(` |
| Total | 33 | 13 files with a marked line |

`fragments/detail/DetailActions.kt` and `MainActivity.java` hold none. The marker
`ENH:default-open-action` is not affected: `QueueGuard.kt` 2, `res/xml/video_audio_settings.xml` 2.

### A2.9 Merging with the other branches

Each planned edit was merged on paper against each branch that changes the same file
(`git merge-file` on copies, the hook inserted where A2.3 puts it): no conflict in any of them.

| File | Other branch and where it edits | This plan's edit | Result |
|---|---|---|---|
| `InternalUrlsHandler.java` | `feature/keep-position`: one line added above `:222` | L1 between `:129` and `:130` | clean. Tried as well: a line added above `:222`, and `:222` itself redirected; both conflict with keep-position's line. That is why L1 is where it is |
| `QueueItemMenuUtil.java` | `feature/crash-fixes`, `feature/crash-fixes-more`: `:64-67` and `:86` | D1 between `:55` and `:56` | clean (`:59-63` lie between) |
| `RouterActivity.java` | `feature/crash-fixes-more`: lines added at `:618` and `:650` | R1 at `:560`, R2 at `:363` | clean |
| `SearchFragment.java` | `feature/crash-fixes`, `feature/crash-fixes-more`: `:375`; `feature/release-views`: `:411` | L2 at `:1006` | clean |
| `DetailActions.kt` | `feature/crash-fixes-more`: `:186` | none | not touched, on purpose |
| `VideoDetailFragment.kt`, `PlayQueue.java` | none of the six branches | P1-P4; Q1 removed | clean |

`feature/player-memory` and `feature/subscription-scale` change none of these files.
`feature/keep-position` also changes `PlayerStartController.kt:114` and
`SinglePlayQueue.java:20`, which this plan only reads.

A link with a start time, with keep-position merged: *Play now* is the upstream path, with its
line at `:222`. A link from another app or without `&t=` builds `SinglePlayQueue(info)`, which
keep-position hooks itself, so *Add to queue* and *Play next* keep the time too. A timed link
(L1) that is added to the queue is built with `SinglePlayQueue(info, startMs)`, which
keep-position does not hook: it starts at the link's time unless "Always start play from
beginning" is on, then at 0:00.

### A2.10 Tests (replaces 5.1)

Existing classes, what changes meaning:

- `Fakes.kt`: `playing(...)` loses `autoQueued`. The fixtures `WORTH_GUARDING` and
  `SINGLE_IN_MAIN` go; `SOMETHING_PLAYING` (each player with one item and with three) is the
  fixture for "asks". New helpers: a request for each `OpenWay`, and a related tap.
- `GuardModelTest`: the tests of `worthGuarding` (`ac7_ac14_noQueueIsWorthGuarding...` to
  `ac1_ac7_aQueueWorthGuardingAlwaysMeansSomethingIsPlaying`), of `autoQueuedCount`
  (`...NoVideoIsAutoQueued...`, `...DoesNotDependOnTheAutoQueuedCount`, twice) and all
  `ac1_ac7_record...` tests and `ac1_ac7_watchingOneVideoAndLettingTheNextOnesFollow...` are
  removed with the code they test. New: `isCurrent(VideoRef)`; the defaults of `TapRequest`;
  `replacesQueue`, `openWayOfRouterChoice` and `queueItemDetails` over their tables.
- `QueueGuardPolicyTest`. Now the opposite of what they pinned:
  `ac7_tapWithASingleVideoInTheMainPlayerOpensAsToday` and
  `ac7_ac8_sameUrlOnAnotherServiceOpensAsTodayWithASingleVideoInTheMainPlayer` (-> `ASK`),
  `ac14_playButtonWithASingleVideoInTheMainPlayerPlaysAsToday` (-> `ASK`),
  `ac1_ac7_theSecondVideoOfTheUsersMakesTheTapAsk` and
  `ac10_playButtonAsksInTheMainPlayerFromTheSecondVideoOfTheUsersOn` (the first video asks),
  `ac1_queueOfOneItemIsProtectedToo` (holds for the main player again). Removed:
  `ac7_videosThePlayerAddedByItselfNeverMakeTheTapAsk`. Same result on another fixture (no
  `autoQueued`, `SOMETHING_PLAYING` instead of the two removed fixtures): every other test from
  `ac8_tapOnTheCurrentItemOpensAsTodayWhetherTheQueueIsWorthGuardingOrNot` to the end of the
  class, and the five that 5.1 moved to `WORTH_GUARDING`. New: rows 3 to 7 and 11 for every
  `OpenWay` and kind, switch on and off; row 5 only for the related list of the current item,
  not for that of another video and not for another `way`.
- `QueueGuardControllerTest`. Now the opposite: `ac7_tapWithASingleVideoInTheMainPlayerIsNotTakenOver`
  (taken over, dialog with "1"), `ac14_playButtonWithASingleVideoInTheMainPlayerPlaysAsTodayAtOnce`
  (list dialog), `ac1_ac7_stateIsReadAtEveryTap_theSecondVideoOfTheUsersBringsTheDialog` (nothing
  playing: not taken over; one video: dialog). Removed:
  `ac1_ac7_ac10_ac14_stateIsReadAtEveryTapAndPress_autoQueuedVideosNeverBringTheDialog`, the two
  `...GetsTheWholeQueueSizeWhenSomeVideosWereAutoQueued`. `ac2_dialogGetsTheQueueSizeOfEachPlayer`
  uses one item for the main player again. The eight `...IsDownToOneVideoAtTheChoice` tests and
  `ac16_switchOff_tapInMainPlayerIsNotTakenOverWhetherTheQueueIsWorthGuardingOrNot` keep their
  results on plain states. New: a `PAGE_CONTROL` request: only the dialog; *Play now* calls
  `openAsToday()` in every kind, and a call of `onVideoTap` from inside it returns `false`; *Add
  to queue* and *Play next* call only `enqueue()` / `enqueueNext()`; switch off and current item
  return `false`. A `VIDEO_PAGE` request beside the background player returns `false`.
- `QueueGuardControllerContractTest`: unchanged.
- `QueueGuardResourcesTest`: `ac15_switchHasItsTitleSummaryAndPreferenceKey` expects the summary
  of A2.6.
- `QueueGuardWiringTest`: `ac1_ac7_hookQ1_playQueueReportsEveryAutoQueuedVideoOnOneMarkedLine`
  turns into "`PlayQueue.java` holds no marked line". The other eight stay. New: one test per
  new file with the count and the fragments of A2.8; for L2 also that the two lines after the
  hook are still `getFM().popBackStackImmediate();` and `activity.startActivity(intent);` (the
  replay repeats them); `DetailActions.kt` and `MainActivity.java` hold no marked line. The
  class comment says 13 files.

No JVM test for the glue (as before): the hooks, `DirectTapActions`, the dialog's `onDismiss`
and `dismissOnStop`, `openQueueItemDetails`. They are the device script.

### A2.11 Device script

Settings: "Ask before replacing the queue" on, autoplay allowed, unless a step says otherwise.
"Queue" below: play a video, then long-press two others and choose "Enqueue".

1. AM-1. Play one video on its page (main player). Go back to a list, tap another video: the
   dialog opens and says "1 item in the queue". Cancel. Press Play All above a playlist: the list
   dialog opens.
2. AM-2. On the page of the playing video, tap a video in its related list: it opens and plays
   without a dialog. Then start a video in the background player, open another video's page
   through long-press "Show stream details", tap one of its related videos: the dialog opens.
3. AM-3. Background player with a queue. Long-press another video, "Show stream details". On that
   page press, in turn, the thumbnail, Background and Popup: each opens the dialog with that
   video's title. Cancel: nothing changes. *Add to queue*: toast, the queue is one longer. *Play
   now* after Background: the video plays alone in the background. Stop playback by an error or
   from the notification so that the mini player shows play, press it on that page: dialog.
   Then, after closing the player: start a queue in the background player from a list (long-press
   "Start playing in background", then "Enqueue" twice), expand the mini player without opening
   any other page and press Popup: no dialog, the whole queue moves to the popup.
4. AM-4. Main player with a queue, minimise it, open the queue screen from the mini player.
   Long-press the playing item, "Details": the queue screen closes, the page is shown, playback
   did not restart, the queue is complete. Again with another item: playback moves to it, the
   queue is complete. Background player with a queue: "Details" opens the item's page, the
   background player keeps playing, the queue is complete. Same with the popup player.
5. AM-5. Main player with a queue. In a description, tap a link to another video with a time
   (`...&t=90`): dialog; *Play now* plays it from that time; repeat and choose *Play next*: it
   plays next, from that time. Tap a link to another video without a time: with "Preferred 'open'
   action" on "Always ask" its chooser comes first; choose the video player: the queue dialog
   opens. Type a video address into search and submit:
   dialog; Cancel leaves the search screen as it was, without a spinner. Tap a time written as
   text (`1:30`): the playing video seeks, no dialog. Play a single video and tap a link to a
   time in it: it seeks, no dialog.
6. AM-6. Main player with a queue, "Preferred 'open' action" on "Always ask". Share a video link
   from another app to PipePipe. Choose Download: download dialog, no queue dialog. Again, choose
   Background player with "Just once": the queue dialog opens and stays. Cancel: back in the
   other app, nothing changed. Again, *Add to queue*: toast, the queue is one longer. Again,
   *Play now*: it plays in the background. With the background player in use and the preferred
   action "Show info": the page opens without a dialog and playback goes on.
7. AM-7. Switch asking off. Repeat one case each of steps 3, 5 and 6: no dialog, each behaves as
   before this feature. Repeat step 4: unchanged, the queue stays.
8. AM-8. Asking on, main player with a queue: long-press a video, "Start playing in background":
   it starts at once, no dialog.

### A2.12 Risks, and what each relied-on behaviour rests on

Checked by reading the source on this branch:

- `openVideoDetailFragment` for a `null` queue, a queue and `switchingPlayers` (A2.5);
  `PlayerStartController.kt:77-158` (when a player intent replaces the queue);
  `VideoDetailFragment.kt` (who calls the four hooked functions); `RouterActivity.java` (every
  path through `handleChoice`, the chooser's dismiss listener, `onStop`, `onDestroy`);
  `Player.selectQueueItem` (`Player.java:1191-1206`: it restarts the item when it is the current
  one, so it is called for other items only).
- The merges of A2.9, by `git merge-file` on copies.

Assumed, not run (no build, no device):

- An `AlertDialog` shown from the router survives the chooser's dismissal once R2 stops the
  `finish()`; shown from an activity that is finishing it would be destroyed with it. This is the
  reason for R2 and has to be seen in step 6.
- `ContextThemeWrapper` with the app theme gives the queue dialog the look of the router's
  chooser; `R.layout.dialog_title` inflates with it (it uses only `?android:attr` text
  appearances).
- `startForegroundService` from the application context after the router has finished is
  allowed (the app was visible a moment ago, and the fetcher service does the same today). If
  Android refuses it while playback is paused, *Add to queue* from another app fails; step 6
  shows it.
- `NavigationHelper.expandMainPlayer()` expands the sheet while the main screen is behind the
  queue screen (it sets the sheet's state, `VideoDetailFragment.kt:751-752`), and finishing the
  queue screen returns to the main screen. If the queue screen was opened from the notification
  with no main screen below it, finishing it leaves the app.
- `StreamingLinkHandlerFactory.getUrl(getId(url))` gives the address form that queue items carry.
  Where it does not, a link to the current item is taken for another video and asks.

Risks:

- The hole of Q15 (the current item, a longer queue) is closed by A2.15 after A15. What is left
  of it is Q21.
- In the main player, Back on the video page of a queue's first item pops the page history and
  closes the player (`VideoDetailFragment.kt:479-521`). Not a tap and not in the amendment; noted.
- L2 repeats two upstream statements in its replay. The wiring test pins the two lines below it.
- P3's replay runs `onPlayPauseClicked()` again; if the player started in the meantime, it
  toggles play and pause instead of starting the page's video.
- While a dialog opened by R1 is up, the invisible router covers the other app, as its chooser
  does.
- A page reached through "Show stream details" under the background player asks at its controls
  even when the user wants exactly that video: one more tap than before, by AM-3.
- The address form: `info.url` of the page against the current item's address. Different forms
  of one video give a dialog on the page of the playing video (section 8 has the same for taps).

### A2.13 For default-open-action

Its plan gets its own "Amendment 2". What it must know from here: `PlayerState` loses
`autoQueuedCount` and `worthGuarding` (its tests' `playing(..., autoQueued)` and both fixtures no
longer compile); `TapRequest` gains `way` and `relatedTo`; `QueueGuardPolicy.onTap` asks for a
single video in the main player again, so its row 6b is gone; its policy must hand every request
whose `way` is not `LIST_TAP` to the wrapped policy untouched (its AM-3); `DialogGuardUi`'s first
parameter is a `Context`; the tap dialog, and with it its checkbox, now also opens from the
video page, links and the router. G1 and G2 are unchanged.

### A2.14 Order of work, questions

(1) `GuardModel.kt`, `GuardPolicy.kt` and their tests. (2) Remove Q1, `noteAutoQueued`,
`autoQueuedRecord`. (3) `QueueGuardDialog.kt`, `QueueGuardGlue.kt`, `QueueGuard.kt`. (4) The
nine hook lines; `git grep -c "ENH:queue-guard" -- app/src/main` gives A2.8 (plus the comment
line in `QueueGuard.kt`). (5) The summary string. (6) Device script.

Raised by this amendment (Q15 to Q20 are answered, A2.15 follows the answers; Q21 is open and
planned with its stated assumption): Q15 (current item, longer
queue), Q16 ("Details" in the main player), Q17 (a dialog only where the queue would be
replaced; "show info" in the main player counts), Q18 (AM-2 with the switch off), Q19 (channel
and playlist links from other apps), Q20 (the address in place of a title).

### A2.15 After A15 to A20: AM-9, AM-10, and what the answers settle

A16 (AM-4 as planned in A2.5), A17 (one rule, `replacesQueue`), A18 (AM-2 with the switch off)
and A20 (the address in place of a title) confirm the plan; nothing changes for them. A19: a
channel or playlist opened from another app with a player action is not covered and still
replaces the queue without asking (A2.3, "Callers still left alone"). A15 adds AM-9 and AM-10.

**Row 2, what it now means at each hook.** The policy still answers `OPEN_AS_TODAY` for the
current item; the hooks make "as today" keep the queue:

| Request for the current item | Policy | What then runs |
|---|---|---|
| list tap (T1-T4) | row 2, hook returns `false` | upstream's tap; where the page then starts playback, P5 hands it the player's queue |
| page control (P1-P4) | row 2, hook returns `false` | the control's own statements; P5 hands them the player's queue (AM-9) |
| timed link (L1) | not asked: the glue seeks first (AM-10) | `DetailPlayerEvents.seekTo(seconds)`; `playOnMain` does not run |
| typed address (L2), "show info" / "video player" (R1) | row 2, hook returns `false` | the page is opened as today; where it starts playback, P5 |
| "background player" / "popup player" for a link (R1) | row 2 only while the queue is that one video; with a longer queue rows 4, 6, 7 | a longer queue would be replaced by the video alone (`RouterActivity.java:806-810`), so it asks like any other video (A17's rule) |

The added condition of row 2 (A2.2) is that last line; for every other request row 2 is as it was.

**AM-9: hook P5.** How a page that never left the player keeps the queue today: the page's queue
is the player's own object (`VideoDetailFragment.kt:642-643`, set when the page registers as
listener, `player/PlayerListeners.kt:34`, and after every start, `PlayerStartController.kt:225`).
All three controls take the queue to send from `playQueueForIntent()` (`:574-582`; callers
`:530`, `:549`, `:559`); the copy that arrives equals the player's queue
(`PlayerStartController.kt:77`), so nothing is re-initialised (`:121-134`), and only a changed
player type reloads the same queue (`:161-164`). A reopened page holds the video alone instead
(`:137`, `:454`), the copy is not equal, and `:155` replaces the queue.

| # | File, where | Added line |
|---|---|---|
| P5 | `fragments/detail/VideoDetailFragment.kt`, `playQueueForIntent()`, between `:574` and `:575` (`val queue = vm.playQueue`) | `QG.playersQueueFor(vm.currentInfo)?.let { return it } // ENH:queue-guard` |

`@JvmStatic fun playersQueueFor(info: StreamInfo?): PlayQueue?` in `QueueGuard.kt`: `null` if
`info == null`; `q = PlayerHolder.getInstance().player?.playQueue`, `null` if that is `null` or
empty; `q` if `HolderPlayerStateSource.current().isCurrent(VideoRef(info.serviceId, info.url))`,
else `null`. It reads no setting (AM-9 says "never"). No new plain function: the decision is
`PlayerState.isCurrent(VideoRef)`, which A2.10 already tests.

Why at press time and not by letting the page adopt the player's queue when it loads the current
item: adopting means writing `vm.playQueue` where the page builds its own (`:454`), and that
object is also the page's history entry (`:455-456`, `fragments/detail/DetailHistory.kt`), what
Back replays (`:494-496`) and what `onMetadataUpdate` compares to decide whether the page follows
the player (`:689`). P5 changes none of that: it only chooses what is sent, at the moment it is
sent, from the player as it is then. It is one line in the one function all senders share.

What each control does on the reopened page of the current item X, the player holding a longer
queue Q (P1-P4 return `false`, row 2):

| Control | Player in use | Trace | Result |
|---|---|---|---|
| Background (`:523`) | background | `player.setRecovery()` (`:526`), Q sent with type audio; equal queue, same type: `:129-134` only `setPlayWhenReady(true)` | goes on, or resumes if paused; nothing reloads |
| Background | popup or main | as above, then `:161-164` reloads Q for the new type | Q moves to the background player at the position stored at `:526` |
| Popup (`:534`) | popup | the same with type popup | goes on or resumes |
| Popup | background or main | the same, `:161-164` | Q moves to the popup |
| play / thumbnail (`:835`), mini player play (`:817`) | background or popup | `openMainPlayer()` sends Q with type video | Q moves to the main player; these controls ask for the main player today too |
| the same | main (the player was stopped for the page's load, `:595`) | equal queue, ExoPlayer idle: `prepare()` and play (`:129-134`) | Q resumes where `stopForImmediateReusing` stored it (`player/PlayerService.java:172`) |

P5 also serves the page's own start after a load (`:422`, `:632`), since they send through the
same function. So a tap on the playing video in a list, with the main player and Autoplay
allowed, no longer replaces a longer queue by that video: the page reloads and resumes Q. That is
more than AM-9's words ("the page's controls") and changes what AC-8's "as today" does there; it
is planned because the alternative is the loss A15 forbids (Q21).

**AM-10: no new line; L1's glue seeks.** Today, for the current item in a longer queue:
`handleTimestampUrl` -> `playOnMain` (`InternalUrlsHandler.java:201-235`) loads the video, builds
it alone at the link's time (`:220-221`) and opens its page with that queue (`:222`). With the
main player the page loads, stops the player and sends the one video; the seek in place at
`PlayerStartController.kt:101-105` needs both queues to hold one video, so `:155` replaces the
queue. Beside the background or popup player the page is reopened and nothing seeks.

`QueueGuard.onTimedLink` (A2.4) gets one step, after `streamUrl` is known and before the
request is built: if `HolderPlayerStateSource.current().isCurrent(VideoRef(service.serviceId, streamUrl))`,
call `DetailPlayerEvents.seekTo(seconds)` and return `true`. That is the call a time written as
text makes (`:102`); the player that is in use handles it (`player/PlayerBroadcastReceiver.kt:36-43`:
`player.seekTo`, and play if it was playing), whichever player and however long the queue. No
setting is read. `playOnMain` and keep-position's line above `:222` are not reached for the
current item; L1's text and place (between `:129` and `:130`) are unchanged.

**Marked lines.** A2.8 as corrected above: `VideoDetailFragment.kt` 5, total 33 in 13 files.
`InternalUrlsHandler.java` stays at 1.

**Tests, in addition to A2.10.**
- `QueueGuardPolicyTest`: row 2 for `BACKGROUND_PLAYER` and `POPUP_PLAYER` requests on the
  current item: `OPEN_AS_TODAY` with one item in the queue, `ASK` with three and the switch on,
  `OPEN_AS_TODAY` with three and the switch off; for `LIST_TAP`, `PAGE_CONTROL` and `VIDEO_PAGE`
  the current item is `OPEN_AS_TODAY` whatever the queue size.
- `GuardModelTest`: `isCurrent(VideoRef)` is `false` while nothing is playing, for another
  service with the same address and for another address (it carries AM-9 and AM-10).
- `QueueGuardWiringTest`: `VideoDetailFragment.kt` holds 5 marked lines; exactly one contains
  `QueueGuard.playersQueueFor(vm.currentInfo)?.let { return it }` and it is the line right after
  `private fun playQueueForIntent(): PlayQueue? {`; `InternalUrlsHandler.java` still 1.
- No JVM test for `playersQueueFor` and the seek step (glue): device steps 9 and 10.

**Device script, in addition to A2.11.**

9. AM-9. Background player, a queue of three, the first playing at about 1:00. Tap the playing
   video in a list: its page opens. Press Popup: the popup shows the same video at about 1:00,
   the queue still has three items. Open the page again the same way and press Background: it
   moves back, same position, three items. Open the page through long-press "Show stream
   details" and press the thumbnail: the main player takes over at the same position, three
   items. Open the queue screen from the background player, "Details" on the playing item,
   press Background on that page: playback goes on, three items. Main player, Autoplay allowed,
   queue of three: go back to a list and tap the playing video: the page reloads and playback
   resumes where it was, three items.
10. AM-10. Main player, a queue of three, the first playing. In its description tap a link to a
   time in this video (a link, not a time written as text): it jumps there without reloading,
   three items. Same in the background and the popup player. With one video alone in the queue:
   it jumps there too.

**Merge check.** `VideoDetailFragment.kt` with P1, P2, P4 and P5 inserted, against a copy with
keep-position's planned line directly before `playerHolder.stopService()` (`:598`):
`git merge-file`, no conflict (P5 is 23 lines above it). `InternalUrlsHandler.java`: unchanged
from A2.9. keep-position's planned line in `PlayerStartController.kt` (between `:99` and `:100`)
is in a file this plan only reads.

**With keep-position's amendment.** Its `onStartIntent` acts when the same video is started with
a queue that is not equal; with P5 the queue is equal, so it does nothing, and the position is
kept the way it is on a page that never left the player (`setRecovery` at `:526`, `:546`, `:839`,
its hooks at the reload). Where P5 gives nothing, because the player is gone or on another
video, the page sends what it sends today and keep-position's rules apply unchanged. Its
`onPageStopsPlayer` line marks the position when the page closes the main player (`:598`,
Autoplay not allowed); the player is then gone, P5 gives `null`, and pressing play starts the
page's video at the kept position, but alone (Q21). No case was found in which both act on the
same start. For a time link in the current item neither of its hooks on that path runs any more
(`onTimestampLink`, `shouldSeekInPlace`): the seek is direct and does not depend on "Always start
play from beginning", as for a time written as text.

**Checked by reading / assumed.** Checked: the callers of `playQueueForIntent()`; the branches
of `PlayerStartController.kt:77-164`; that `PlayerBroadcastReceiver` registers its listener for
every player type (`:168`); the merge above. Assumed, not run: a copy of the player's queue made
at the press still equals it when the player handles the intent (the same assumption a page that
never left the player makes; if the player moves on in between, the copy restarts the queue at
the item it had); an idle ExoPlayer resumes the queue with `prepare()` after
`stopForImmediateReusing` (upstream's own path at `:129-134`); `info.url` of the page equals the
current item's address (where it does not, P5 gives nothing and the page behaves as before this
amendment, and P1-P4 ask).

**Risks.** A long queue is copied through the intent on every such press, as on a page that
never left the player. On the reopened page the page's own queue and history entry stay the
single video: Back and the page's follow-the-player logic behave as today. `Player.seekTo`
clamps to the duration and does nothing without an ExoPlayer (`PlayerTransportController.kt:150-166`):
a time link in a current item whose player is stopped does nothing.
