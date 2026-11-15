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
