# Plan: feed stays visible while it updates

Spec: `spec.md` (AC-1..AC-12). Paths are relative to `app/src/main/java/org/schabi/newpipe/`
unless they start with `app/` or `enhancements/`. Line numbers are those of this branch at
`54e9a9f`. `P` = `org.schabi.newpipe.enhancements.feedliveupdate`; in the source `P.` is
written out (no import lines). `// ENH` below stands for `// ENH:feed-live-update`.
Depends on Q1..Q6 in `questions.md`; each states the assumption used here.
"Checked" = read in the code or in the compiled library classes. "Assumed" = not checked.

## 1. What the code does today

| Spec, "Today" | Holds? | What the code shows |
|---|---|---|
| The list is replaced by a full-screen progress display until the end; taps are off | yes, with details | Every `ProgressEvent` becomes a `ProgressState` without items (`local/feed/FeedViewModel.kt:75`; the 500-row query runs only for `SuccessResultEvent`/`IdleEvent`, `:61`). `local/feed/FeedFragment.kt:747-748` calls `showLoading()` for each: list alpha 0 but still scrollable (`:703`), header with the play buttons hidden (`:704`), centre text and bar shown (`:705`, `fragments/BaseStateFragment.java:123-125`), pull indicator on, `isRefreshing = true` (`:706-707`). Tap and long-press test `!isRefreshing` (`:782`, `:804`); a tap in selection mode is handled before that test (`:778`). |
| Results are stored in batches long before the end | yes | `local/feed/service/FeedLoadManager.kt:266-267`: `buffer(20)`, then `DatabaseConsumer` (`:316-370`) writes the 20 results in one transaction. 1600 channels = 80 transactions. The end is `postProcessFeed()` (`:294-305`): "Processing feed…" twice, a cleanup, then `SuccessResultEvent`. |
| Progress is app-wide; every open feed page shows it | yes, with details | `local/feed/service/FeedEventManager.kt` is one object with one event stream; events carry no group. Since `subscription-scale` only a page that is on screen receives them (`FeedViewModel.kt:56`); a page off screen keeps its last state. Not in the spec: the notification check uses the same loader (`local/feed/notifications/NotificationWorker.kt:42-46`, group id `-2`) and is announced the same way (Q1). |

Also checked, used below:

- **Ways a refresh starts** (`git grep FeedLoadService`, `startLoading(`): `FeedFragment.reloadContent()`
  (`:1134-1143`: header, refresh icon, pull, error panel "retry") with the page's group id
  (`-1` for "All"); `NotificationWorker` with `-2`. There is no automatic refresh: the
  "outdated" threshold only selects which channels a started refresh loads
  (`FeedLoadManager.kt:88-111`). `FeedLoadService` runs one refresh at a time and drops a
  start request while one runs (`FeedLoadService.kt:85`).
- **Which tables the two small queries observe** (generated `FeedDAO_Impl`, built in the
  `group-sort` worktree from an identical `FeedDAO.kt`): `notLoadedCount()` and
  `oldestSubscriptionUpdateFromAll()`: `feed_last_updated`; `oldestSubscriptionUpdate(group)`:
  `feed_last_updated`, `feed_group_subscription_join`; `notLoadedCountForGroup`: those two
  and `subscriptions`. A batch writes `feed_last_updated` for every channel it stores or
  marks failed (`FeedDatabaseManager.kt:116-118`, `:78-79`), so both re-emit per batch, for
  every page and every refresh.
- **A stale result event.** `FeedEventManager.reset()` posts `IdleEvent` while `events()`
  filters it out (`FeedEventManager.kt:18-26`), so a page that has consumed
  `SuccessResultEvent` keeps it as "latest event" until it subscribes again, and treats every
  later emission as another end of refresh (query, `LoadedState` with the same errors,
  `reset()`). The fragment shows the errors once (`FeedFragment.kt:856-861`). Unchanged.
- **A list update ignores the search filter.** `handleLoadedState` shows all items
  (`FeedFragment.kt:829-837`) while `isFilterEnabled` stays true. AC-9 needs more than "as
  today" (Q4).

## 2. Shape of the change

Marked direct edits in 4 upstream files: 13 added lines, 9 changed one for one, none removed,
no import lines, no blank lines added (the layer check counts an added blank line as
unmarked). Three plain Kotlin files (RxJava 3 and `FeedEventManager.Event`, no Android
import) and one Android glue file in `P`. No layout, string, setting, schema or build file
change (AC-12).

```kotlin
// FeedRefreshLog.kt: plain. What the loader reports; one instance for the app.
class FeedRefreshLog {
    val groupId: Long                    // group of the refresh started last; ALL before any
    val storedCount: Long                // batches stored since the process started
    fun started(groupId: Long)           // running + 1
    fun ended()                          // running - 1, not below 0
    fun batchStored()                    // count + 1, then announced; synchronized
    fun stored(): Flowable<Long>         // the current count at once, then every new count
    fun runningElsewhere(pageGroupId: Long): Boolean   // running > 0 && !concerns(groupId, pageGroupId)
    companion object {
        const val ALL = -1L              // = FeedGroupEntity.GROUP_ALL_ID
        const val NOTIFICATIONS = -2L    // = FeedLoadManager.GROUP_NOTIFICATION_ENABLED
        @JvmField val app = FeedRefreshLog()
        fun concerns(refreshGroupId: Long, pageGroupId: Long): Boolean
    }
}

// FeedLiveUpdate.kt: plain. One per FeedViewModel.
enum class EventKind { IDLE, PROGRESS, SUCCESS, ERROR }
enum class Show { LOADED, END, ERROR, PROGRESS, NOTHING }
enum class Display { LOADED, PROGRESS_FULL, PROGRESS_LIST, ERROR, NOTHING }
data class Plan(val show: Show, val rebuild: Boolean)
fun plan(kind: EventKind, concerned: Boolean, pageChanged: Boolean, storedSinceBuilt: Boolean): Plan
fun display(show: Show, hasItems: Boolean): Display

class FeedLiveUpdate<R : Any, I : Any>(
    pageGroupId: Long,
    log: FeedRefreshLog = FeedRefreshLog.app,
    paceMillis: Long = PACE_MILLIS,
    paceScheduler: Scheduler = Schedulers.computation()
) {
    fun <T : Any> wake(source: Flowable<T>): Flowable<T>
    fun next(event: FeedEventManager.Event, showPlayed: Boolean): Boolean   // false: post nothing
    val rebuild: Boolean                                                    // of the last next()
    fun shownAs(event: FeedEventManager.Event): FeedEventManager.Event
    fun rows(queried: List<R>): List<R>
    fun items(rows: List<R>, create: (R) -> I): List<I>?                    // main thread
    companion object {
        const val REBUILD_PERIOD_MILLIS = 2_000L
        const val PACE_MILLIS = 2_120L   // + DEFAULT_THROTTLE_TIMEOUT (util/Constants.kt:8), see 3.2
    }
}

// FeedListRules.kt: plain.
enum class AfterUpdate { NOTHING, SCROLL_TO_TOP, SHOW_NEW_ITEMS }
object FeedListRules {
    fun afterUpdate(atTop: Boolean, firstVisible: Int, oldIds: List<Long>, newIds: List<Long>, duringRefresh: Boolean): AfterUpdate
    inline fun <T> sameRows(shown: List<T>, next: List<T>, row: (T) -> Any?): Boolean
    fun matches(title: String?, uploader: String?, text: String): Boolean
    fun progressText(current: Int, max: Int, loading: String, message: String?): String
}

// FeedListUpdate.kt: Android glue (RecyclerView, Groupie).
object FeedListUpdate {
    fun show(list: RecyclerView, adapter: GroupAdapter<*>, shown: List<Item<*>>, duringRefresh: Boolean,
             showNewItems: () -> Unit, onDone: () -> Unit)
}
```

## 3. The signal "a batch was stored" (AC-2)

### 3.1 Choice

Chosen: a counter the loader raises after each batch transaction, `FeedRefreshLog.batchStored()`,
one added upstream line directly after `.doOnNext(DatabaseConsumer())`. The line is reached
only when the transaction has committed (an exception skips it and fails the refresh as
today). `batchStored()` raises `storedCount` and then emits it on `stored()`.

Rejected:
- The two Room `Flowable`s. They do re-emit per batch (section 1), but for every refresh
  and every page, also for writes that are no batch; they arrive inside `combineLatest`
  where they cannot be told from a progress event; and `throttleLatest` may drop the one
  emission that followed a batch. Their re-emission is not relied on for AC-2.
- Progress events: one per channel, 1600 per refresh, and they are posted before the batch
  that contains the channel is stored (`FeedLoadManager.kt:263-267`).

### 3.2 "At most once in two seconds, and the final state for certain"

`FeedLiveUpdate.wake(source)`, placed directly before the on-screen gate:

```kotlin
Flowable.defer {
    fresh.set(true)                                   // a new subscription: first load or back on screen
    var seen = -1L
    val paced = log.stored()
        .throttleLatest(paceMillis, TimeUnit.MILLISECONDS, paceScheduler)
        .doOnNext { this.paced.set(it) }
        .onBackpressureLatest()
    Flowable.combineLatest(source, paced) { item, count -> item to count }
        .filter { (_, count) ->
            val batchWake = seen != -1L && count != seen
            seen = count
            !batchWake || count > builtAt             // drop a wake-up for batches already in the list
        }
        .map { it.first }
}
```

- Every item of `source` passes unchanged. A paced batch count re-emits the latest item, so
  the page's stream is woken although no event changed.
- `throttleLatest` (checked, section 12): the first count passes at once and starts a
  timer; counts arriving while it runs replace each other; when it ends the latest one is
  emitted and the timer starts again; with nothing waiting it stops. So paced counts are at
  least `paceMillis` apart, and the last batch before the end is never lost: it is emitted
  at the latest `paceMillis` after it was stored, unless the end comes first.
- The page's own `throttleLatest(120 ms)` sits behind the gate and can delay a wake-up by
  up to 120 ms, so two refresh-driven rebuilds could start 1880 ms apart with a pace of
  2000. `PACE_MILLIS` is therefore 2120.
- The limit applies to rebuilds caused by the refresh. A rebuild the user causes (returning
  to the page, the "show watched" switch) is done at once.
- The end: a concerned `SuccessResultEvent` always rebuilds (section 5), whatever the pace.
  It stays in `FeedEventManager` until a concerned page on screen has consumed it, and
  `throttleLatest` always delivers the latest item. A paced count still waiting at the end
  is dropped by the filter (`builtAt` is then the final count), so nothing follows the final
  list. Exception: if that count leaves the pace operator in the up to 120 ms between the
  result event and its decision, the page gets the final state twice, which is what a late
  Room emission causes today (section 1, "a stale result event").

`builtAt` is `log.storedCount` read in `next()` before the query of a rebuild; `paced` is
the last count the pace operator let through. "Results were stored since the list was
built" = `paced > builtAt`. The count is raised after the commit and read before the query,
so a list marked as built at `n` contains at least batches 1..n; a batch can cause one
unneeded rebuild, never a missed one.

## 4. Which refresh concerns which page (AC-7)

The loader reports its group: `started(groupId)` on subscription of `startLoading`'s result
and `ended()` when it terminates or is disposed (two added lines at the end of the chain).
Events stay anonymous; a page asks `FeedRefreshLog.app.groupId` when it decides. Rejected:
a group id in every event (four event classes, four post sites and `FeedLoadService.kt:229`).

`concerns(refreshGroupId, pageGroupId)` =
`refreshGroupId == ALL || refreshGroupId == NOTIFICATIONS || pageGroupId == ALL || refreshGroupId == pageGroupId`:

| Refresh of | Page "All" (`-1`) | Page of group 7 | Page of group 9 |
|---|---|---|---|
| "All" (`-1`; also the value before any refresh) | yes | yes | yes |
| group 7 | yes | yes | no |
| notification check (`-2`, Q1) | yes | yes | yes |

Every start goes through `startLoading` (section 1), so the group is always set before the
first event of a refresh. Two refreshes at once (the notification check during a manual
refresh) already mix their events today; the group of the one started last is used (risk 7).

## 5. State and decision

### 5.1 State shape

`FeedState.ProgressState` gains `val items: List<StreamItem>? = null`. `null`: full-screen
progress as today. A list: show this list with the slim progress. `LoadedState` and
`ErrorState` are unchanged. Consumers of `FeedState`: `FeedFragment.handleResult`
(`FeedFragment.kt:728-736`) and `:264-265`; `SubscriptionListFragment` only names the type.
Every progress state carries the whole current list, so a page whose view was rebuilt
(rotation, back stack) can draw it from the replayed state; the page skips the list work
when it already shows those rows (6.2).

### 5.2 The decision (plain)

`next(event, showPlayed)` runs for every item, on the stream's background thread:

```kotlin
val kind = kindOf(event)                                   // by class
val concerned = FeedRefreshLog.concerns(log.groupId, pageGroupId)
val pageChanged = fresh.getAndSet(false) or (showPlayed != builtShowPlayed)   // builtShowPlayed starts null
val p = plan(kind, concerned, pageChanged, paced.get() > builtAt)
rebuild = p.rebuild; show = p.show
if (p.rebuild) { builtAt = log.storedCount; builtShowPlayed = showPlayed }
return p.show != Show.NOTHING
```

`plan(kind, concerned, pageChanged, storedSinceBuilt)`:

| kind | concerned | pageChanged | storedSinceBuilt | show | rebuild |
|---|---|---|---|---|---|
| IDLE | any | any | any | LOADED | yes |
| SUCCESS | yes | any | any | END | yes |
| ERROR | yes | any | any | ERROR | no |
| PROGRESS | yes | yes | any | PROGRESS | yes |
| PROGRESS | yes | no | yes | PROGRESS | yes |
| PROGRESS | yes | no | no | PROGRESS | no |
| PROGRESS, SUCCESS, ERROR | no | yes | any | LOADED | yes |
| PROGRESS, SUCCESS, ERROR | no | no | any | NOTHING | no |

`display(show, hasItems)`, where `hasItems` = the list after the rebuild, or the kept one,
is not empty: `PROGRESS` -> `PROGRESS_LIST` if `hasItems` else `PROGRESS_FULL` (AC-6);
`LOADED`, `END` -> `LOADED`; `ERROR` -> `ERROR`; `NOTHING` -> `NOTHING`.

The other members, in the order the view model calls them for one item:
- `rebuild`: whether the 500-row query runs.
- `shownAs(event)`: `IdleEvent` when `show == LOADED`, else `event`. A page not concerned
  thus handles a foreign event as "idle": `LoadedState` without errors, no `reset()`, so the
  result event stays for a concerned page.
- `rows(queried)`: if `rebuild`, keeps `queried` as the page's rows; returns the kept rows
  (empty before the first rebuild).
- `items(rows, create)`, main thread: `null` if `display(PROGRESS, rows.isNotEmpty())` is
  `PROGRESS_FULL`; else `rows.map(create)`, remembered for as long as it is called with the
  same list instance, so 1600 progress ticks create no items.

IDLE and END rebuild on every item, exactly as today.

## 6. The page

### 6.1 What is shown (AC-1, AC-3, AC-4, AC-6)

- **`PROGRESS_FULL`** (`items == null`): `handleProgressState` as today, untouched.
- **`PROGRESS_LIST`**: the page is in its loaded look (`hideLoading()`: list and header
  visible, centre text and bar hidden, error panel hidden, `isRefreshing = false`) with two
  differences: the pull indicator stays on, and the header's first line shows the progress
  instead of "Feed last updated: …". `isRefreshing == false` is what switches taps and the
  long-press menu back on; the two listeners (`:782`, `:804`) are not edited. The header
  holds the play buttons, so they work too.
- **Progress text** (`FeedListRules.progressText`): `"<Loading feed…> <current>/<max>"`
  with the existing `feed_notification_loading`; without counts the event's message
  ("Processing feed…"), else `"∞/∞"`: the texts of `:753-759`. It is written at the end of
  `updateRefreshViewState()` while the current state is a progress state with items, so
  every state change and `onResume` keep it right and the final state restores the line.
  The header is above the list (`res/layout/fragment_feed.xml:8-91`) and its first line
  keeps its height. The centre bar is not shown in this mode (Q3).
- **Pull indicator**: on in both progress modes, as today, so a pull during a refresh does
  nothing (10.5).

### 6.2 List updates during the refresh (AC-2, AC-9)

A progress state with items goes to a new three-line `handleLiveProgress(items)`:
- the rows are the ones shown (`sameRows(originalItems, items) { it.streamWithState }`: same
  size and the same row instance at each index): `hideLoading()` only. This is every tick
  between two rebuilds, and the first tick of a refresh, so the list is not touched (AC-1);
- else `handleLoadedState(FeedState.LoadedState(items, oldestSubscriptionUpdate, 0))`:
  upstream's own list code runs, so an update during the refresh treats item layout,
  selection and play buttons exactly as the final one does.

What `handleLoadedState` does, for an update during the refresh and for the final state:

| Step (`FeedFragment.kt`) | During | Final | How |
|---|---|---|---|
| item layout (`:814-819`) | yes | yes | unchanged |
| selection: drop vanished, mark (`:823-824`) | yes | yes | unchanged (AC-9) |
| `originalItems` (`:827-828`) | yes | yes | unchanged |
| `filteredItems` (`:829-830`) | filter applied | filter applied | `:830` changed: with `isFilterEnabled`, only items for which `FeedListRules.matches(title, uploader, text of the search field)`, the test of `:631-632` (AC-9, Q4) |
| play buttons visible (`:832`) | yes | yes | unchanged |
| adapter update (`:837`) | synchronous, shows `filteredItems` | asynchronous as today, shows `filteredItems` | `:837` changed to `FeedListUpdate.show(...)`, section 7 |
| `highlightNewItemsAfter` (`:838-840`) | yes, against the time of before the refresh, without the button | yes, as today | the field `oldestSubscriptionUpdate` does not move during the refresh; `:1072` changed so that the button is not shown while the state is a progress state |
| `listState` restore (`:842-845`) | yes | yes | unchanged; needed for the first list after the view was rebuilt |
| "Not loaded: n" (`:847-854`) | left as it is | yes | `:848` changed; the count passed is 0, so the text is not written |
| error messages (`:856-861`) | none | yes | the list passed is empty |
| `oldestSubscriptionUpdate` (`:862`) | unchanged | yes | its own value is passed. It must not move: the final state compares it to show the errors and highlights against it (AC-8) |
| `showEmptyState()` / `hideLoading()` (`:864-868`) | `hideLoading()` | as today | items are never empty here; `:715` changed so that `hideLoading()` leaves the pull indicator on while the state is a progress state |

"While the state is a progress state" is one added property,
`liveRefresh = viewModel.stateLiveData.value is FeedState.ProgressState`. In the four places
that read it, it is true only on the path through `handleLiveProgress`: `hideLoading()` and
`handleLoadedState` are otherwise reached from a `LoadedState` or an `ErrorState`.

The update during a refresh is synchronous because Groupie applies an asynchronous result
on top of whatever a synchronous `update` did meanwhile (checked, section 12), and the
search field updates synchronously on every key (`:640`). With a rebuild every two seconds
that would be hit; a 500-item diff with few changes is a few milliseconds.

### 6.3 A refresh request on a page that is not concerned (AC-7, AC-11)

Such a page looks idle, so its refresh controls are usable while the service is busy with
another group and drops the request. A pull would leave the indicator on for good. One line
in `reloadContent()` takes it back when `FeedRefreshLog.app.runningElsewhere(groupId)` (Q6).

## 7. Nothing moves under the reader (AC-5)

Checked in the compiled classes (section 12):
- Groupie turns the diff into `notifyItemRangeInserted/Removed/Changed`. Unchanged rows
  (`StreamItem` is a data class over the row) get no notification.
- `LinearLayoutManager`, which `GridLayoutManager` extends, lays out from a child it already
  shows, at that child's new position and old offset. Items inserted above the first
  visible one therefore do not move what is on screen, in list and card mode (one item per
  row; `StreamItem.getSpanSize`).
- This also holds at the top: the old first item stays where it is and the new ones are
  above it, out of sight. "At the top, new videos appear at the top" needs
  `scrollToPosition(0)`.
- Grid mode: `GridLayoutManager` moves the anchor back to the first item of its row. The
  rows on screen keep their vertical place, but when the number of items inserted above is
  not a multiple of the column count, the videos shift by that many columns. Accepted (Q2).

`FeedListUpdate.show(list, adapter, shown, duringRefresh, showNewItems, onDone)`:

```kotlin
val oldIds = (0 until adapter.itemCount).map { adapter.getItem(it).id }
val atTop = !list.canScrollVertically(-1)
val first = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
val done = OnAsyncUpdateListener {
    onDone()
    when (FeedListRules.afterUpdate(atTop, first, oldIds, shown.map { it.id }, duringRefresh)) {
        AfterUpdate.SCROLL_TO_TOP -> list.scrollToPosition(0)
        AfterUpdate.SHOW_NEW_ITEMS -> showNewItems()
        AfterUpdate.NOTHING -> Unit
    }
}
if (duringRefresh) { adapter.update(shown, false); done.onUpdateComplete() } else adapter.updateAsync(shown, false, done)
```

The item id is the stream's uid (`local/feed/item/StreamItem.kt:48`). `list` is captured
because the binding may be gone when an asynchronous result arrives.

`FeedListRules.afterUpdate`, first matching row:

| Condition | Result |
|---|---|
| `oldIds` or `newIds` is empty | NOTHING (first fill: a saved scroll position may be restored; `scrollToPosition` would discard it) |
| `atTop`, `newIds[0] != oldIds[0]` | SCROLL_TO_TOP |
| `atTop` | NOTHING |
| no old item at index `firstVisible` or later is in `newIds`, or `firstVisible` is outside `oldIds` (negative counts as 0) | NOTHING |
| in `newIds`, before the first such item, there is an id not in `oldIds`, and `duringRefresh` | SHOW_NEW_ITEMS |
| otherwise | NOTHING |

- `showNewItems` is `{ if (tryGetNewItemsLoadedButton()?.isVisible == false) showNewItemsLoaded() }`:
  the existing button, its ten-second timeout, its click (to the top) and its hiding at the
  top (`FeedFragment.kt:221-233`, `:292-295`, `:1079-1099`), not restarted while it shows.
- The rule runs for every list update. Outside a refresh only its "at the top stays at the
  top" row can apply, also for the final state, where the button is upstream's as today (Q5).
- Not kept in place, as the spec says under "Not covered": a refreshed channel's older
  videos leave the list (`FeedDAO.kt:160-174`), also when they are on screen.

## 8. The upstream edits

`+` = added line after the line named, `~` = changed line (old, then new).

**`local/feed/FeedState.kt`**: 1 changed.
- `~ :11` `@StringRes val progressMessage: Int = 0` ->
  `@StringRes val progressMessage: Int = 0, val items: List<StreamItem>? = null // ENH`

**`local/feed/service/FeedLoadManager.kt`**: 3 added.
- `+ :267` (`.doOnNext(DatabaseConsumer())`): `.doOnNext { P.FeedRefreshLog.app.batchStored() } // ENH`
- `+ :271` (`.flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }`), two lines:
  `.doOnSubscribe { P.FeedRefreshLog.app.started(groupId) } // ENH` and
  `.doFinally { P.FeedRefreshLog.app.ended() } // ENH`

**`local/feed/FeedViewModel.kt`**: 3 added, 3 changed.
- `+ :42` (the `onScreenGate` field): `private val live = P.FeedLiveUpdate<StreamWithState, StreamItem>(groupId) // ENH`
- `+ :55` (the `)` that closes `combineLatest`), so directly before the gate line `:56`:
  `.let { live.wake(it) } // ENH`
- `+ :59` (`.observeOn(Schedulers.io())`): `.filter { live.next(it.t1, it.t2) } // ENH`
- `~ :61` `val streamItems = if (event is SuccessResultEvent || event is IdleEvent)` ->
  `val streamItems = if (live.rebuild) // ENH`
- `~ :68` `CombineResultDataHolder(event, streamItems, notLoadedCount, oldestUpdate)` ->
  `CombineResultDataHolder(live.shownAs(event), live.rows(streamItems), notLoadedCount, oldestUpdate) // ENH`
- `~ :75` `is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage)` ->
  `is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage, live.items(listFromDB) { e -> StreamItem(e) }) // ENH`

`:62-66` (the query), `:74`, `:76-77` and `:81-83` (`reset()`) stay: they now see the event
`shownAs` returned and the kept rows. `filter` and `map` run one after the other on the same
thread for each item, so `rebuild`, `shownAs` and `rows` belong to the `next` just made.
`wake` is inside the gate: off screen it is not subscribed.

**`local/feed/FeedFragment.kt`**: 7 added, 5 changed.
- `+ :118` (`private var isRefreshing = false`):
  `private val liveRefresh get() = viewModel.stateLiveData.value is FeedState.ProgressState // ENH`
- `~ :715` in `hideLoading()`: `feedBinding.swipeRefreshLayout.isRefreshing = false` ->
  `feedBinding.swipeRefreshLayout.isRefreshing = liveRefresh // ENH`
- `+ :747` (`private fun handleProgressState(...) {`):
  `if (progressState.items != null) return handleLiveProgress(progressState.items) // ENH`
- `+ :766` (the `}` that closes `handleProgressState`), three lines, no blank line:
  ```kotlin
      private fun handleLiveProgress(items: List<StreamItem>): Unit = // ENH
          if (P.FeedListRules.sameRows(originalItems, items) { it.streamWithState }) hideLoading() // ENH
          else handleLoadedState(FeedState.LoadedState(items, oldestSubscriptionUpdate, 0)) // ENH
  ```
- `~ :830` `filteredItems.addAll(originalItems)` ->
  `filteredItems.addAll(if (isFilterEnabled) originalItems.filter { P.FeedListRules.matches(it.streamWithState.stream.title, it.streamWithState.stream.uploader, editText?.text?.toString().orEmpty()) } else originalItems) // ENH`
- `~ :837` `groupAdapter.updateAsync(loadedState.items, false) {` ->
  `P.FeedListUpdate.show(feedBinding.itemsList, groupAdapter, filteredItems.toList(), liveRefresh, { if (tryGetNewItemsLoadedButton()?.isVisible == false) showNewItemsLoaded() }) { // ENH`
  (the lambda body `:838-841` stays and is `onDone`)
- `~ :848` `feedBinding.refreshSubtitleText.isVisible = feedsNotLoaded` ->
  `feedBinding.refreshSubtitleText.isVisible = if (liveRefresh) feedBinding.refreshSubtitleText.isVisible && feedBinding.refreshSubtitleText.text.isNotEmpty() else feedsNotLoaded // ENH`
  (during a refresh the line stays as it is; in a new view it is empty and is hidden)
- `+ :1014` (the `)` that ends the statement in `updateRefreshViewState()`):
  `(viewModel.stateLiveData.value as? FeedState.ProgressState)?.takeIf { it.items != null }?.let { feedBinding.refreshText.text = P.FeedListRules.progressText(it.currentProgress, it.maxProgress, getString(R.string.feed_notification_loading), if (it.progressMessage > 0) getString(it.progressMessage) else null) } // ENH`
- `~ :1072` `if (highlightCount > 0) {` -> `if (highlightCount > 0 && !liveRefresh) { // ENH`
- `+ :1141` (the `)` that closes `startService(` in `reloadContent()`):
  `if (P.FeedRefreshLog.app.runningElsewhere(groupId)) feedBinding.swipeRefreshLayout.isRefreshing = false // ENH`

Marked lines per file, for the wiring test: `FeedState.kt` 1, `FeedLoadManager.kt` 3,
`FeedViewModel.kt` 6, `FeedFragment.kt` 12; total 22 = 13 added + 9 changed. The lines
marked `ENH:subscription-scale` in three of these files are not touched, and its wiring test
still holds: one `.throttleLatest(` line, one `.getStreams(groupId, showPlayedItems)` line
and one `.distinctUntilChanged()` in `FeedViewModel.kt`, the gate between `combineLatest`
and the throttle.

## 9. Cost (AC-10)

- **Off screen.** The gate (`subscriptionscale/OnScreenGate.kt:30-43`) leaves its source
  without a subscriber. `wake` is that source, so off screen there is no subscription to
  the batch counts, no Room observer, no decision, no query, no item. At most one item
  already waiting in the 120 ms throttle is still handled after leaving, as today. On
  return `fresh` makes the first decision rebuild once.
- **On screen, per progress event** (1600): the two small queries per batch as today, one
  decision, one state, a 500-element identity comparison, `hideLoading()` on views already
  in that state. No query, no new item, no adapter call.
- **List rebuilds** (500-row query over five tables, 500 `StreamItem`s, one diff, changed
  rows redrawn), "All" refresh of 1600 subscriptions = 80 batches, page on screen: one per
  batch, at most one per 2.12 s, plus the final one. Run against RxJava 3.1.12 with a test
  scheduler and a model of `wake`/`next`:

  | Batches arrive | Rebuilds during the refresh | With the final one |
  |---|---|---|
  | every 5 s (400 s) | 80 | 81 |
  | every 3 s (240 s) | 80 | 81 |
  | every 333 ms (27 s) | 13 | 14 |
  | all at once | 1 | 2 |

  So about 80 for a refresh that takes "minutes" (longer than 170 s), about T/2.12 for a
  faster one; today 1. A page not concerned: 0.

## 10. Unchanged behaviour (AC-8, AC-11)

1. **Start.** Pull or tap -> `reloadContent()` -> service -> `started` -> first
   `ProgressEvent`. Page with a list: `plan` = PROGRESS without rebuild, the kept rows are
   the ones on screen, so `handleLiveProgress` only calls `hideLoading()`: the pull
   indicator stays or comes on, the header line shows `0/n`. Empty page: `items == null`,
   `showLoading()` as today.
2. **Running.** Sections 3 and 6.
3. **Cancel** (notification action). `cancelSignal` ends the extraction; what was fetched
   is stored, `postProcessFeed()` runs, `SuccessResultEvent` follows: point 5.
4. **Failure.** `FeedLoadService.kt:229` posts `ErrorResultEvent`. Concerned page: `plan` =
   ERROR, the event passes as it is, `:77` posts `ErrorState`, `:81-83` reset; the fragment
   shows the error panel and hides the list (`FeedFragment.kt:871-878`, `:738-745`), as
   today. A page not concerned shows nothing (AC-7).
5. **The end.** Concerned `SuccessResultEvent`: END, always a query, `:76` posts
   `LoadedState` with the errors, `:81-83` reset. `handleLoadedState` runs whole:
   `oldestSubscriptionUpdate` still holds the value of before the refresh, so the error
   messages show (`:856-861`), the highlight is computed against that time and shows the
   button (`liveRefresh` is false), "Not loaded" and the oldest-update line are set,
   `hideLoading()` turns the pull indicator off. Different from today only by sections 6.2
   (filter) and 7 (at the top the new videos are shown).
6. **Pull while a refresh runs.** Concerned page: the indicator is on, so
   `SwipeRefreshLayout` does not take the gesture (checked) and the list scrolls; as today.
   A tap on the header or the refresh icon, now visible, calls `reloadContent()`; the
   service ignores it (`FeedLoadService.kt:85`). Page not concerned: 6.3.
7. **Search during a refresh.** Typing calls `filterItems` as today (synchronous update,
   to the top). The next rebuild keeps the filter.

## 11. Merge check

Done with `git merge-file` on copies carrying exactly the edits of section 8, against each
branch's version and the common ancestor, for all four edited files:

| Branch | Its edits in these files | Result |
|---|---|---|
| `feature/queue-guard`, `feature/default-open-action` | `FeedFragment.kt:330-349` (play buttons), one line after `:783` (tap) | clean; nearest edit of this plan `:766` |
| `feature/crash-fixes-more` | one line each at the start of `handleItemsErrors` and `handleFeedNotAvailable` | clean; nearest `:848` |
| `feature/subscription-scale` | in this branch | lines not touched |
| `build/device-test`, `origin/enhancements/integration`, `feature/release-views`, `feature/group-sort`, `feature/keep-position`, `feature/player-memory`, `feature/crash-fixes` | none near | clean |

No conflict is expected. With `queue-guard` merged, a tap during a refresh reaches its
`onVideoTap` line, because `isRefreshing` is false in list mode.

## 12. Library behaviour relied on

| Behaviour | Status |
|---|---|
| `Flowable.throttleLatest`: first item at once, latest item at the end of each period, timer restarts after an emission (`FlowableThrottleLatest$ThrottleLatestSubscriber.drain`, rxjava 3.1.12) | checked in the classes; also run (section 9) |
| Groupie 2.10.1 `GroupAdapter.updateAsync`: synchronous when the adapter is empty; else a background diff applied only if no newer `updateAsync` was scheduled; a synchronous `update` in between does not cancel it; the listener is held weakly; `update` diffs and notifies at once | checked (`GroupAdapter`, `AsyncDiffUtil`, `DiffTask`) |
| Groupie `DiffCallback`: `isSameAs` / `hasSameContentAs`; results go to `notifyItemRange*` | checked |
| RecyclerView 1.4.0 `LinearLayoutManager.updateAnchorFromChildren`: anchor = a child on screen (the focused one first), at its current adapter position and old offset | checked |
| `GridLayoutManager.ensureAnchorIsInCorrectSpan`: the anchor moves to the first position of its row | checked |
| `LinearLayoutManager.scrollToPosition` discards the anchor of a pending restored state | checked |
| `scrollToPosition(0)` called right after a synchronous `update` is applied to the list as updated, in the next layout | assumed; instrumented test and device step 5 |
| `SwipeRefreshLayout` 1.2.0 does not take the touch while it is refreshing (`onInterceptTouchEvent`); `setRefreshing(true)` while refreshing does nothing | first checked; second assumed |
| Room 2.8.4 `Flowable` queries: observed tables as listed in section 1 (generated code) | checked |
| Room re-emits after every write transaction to an observed table, without comparing results | assumed; not relied on for AC-2 |
| A `StreamItem` may be created off the main thread | not needed: items are still created on the main thread |

## 13. Verification

**JVM unit tests**, `app/src/test/java/org/schabi/newpipe/enhancements/feedliveupdate/`,
JUnit 4 and RxJava's `TestScheduler`, processors and `TestSubscriber`. Events are built
with `FeedEventManager.Event`'s constructors (plain data classes). Rows and items are
strings or ints. Each test uses its own `FeedRefreshLog()`.

| Test class | AC | Cases |
|---|---|---|
| `FeedRefreshLogTest` | 2, 7, 11 | the `concerns` table of section 4, every cell, and `-1` before any refresh; `ALL` and `NOTIFICATIONS` equal `FeedGroupEntity.GROUP_ALL_ID` and `FeedLoadManager.GROUP_NOTIFICATION_ENABLED`; `stored()` gives the current count on subscription and every later one; `storedCount` is already raised when the count is emitted; `runningElsewhere`: false with nothing running, false for a concerned page, true for another group's page, false again after `ended()`, never below 0 |
| `FeedLivePlanTest` | 1, 2, 6, 7, 8 | every row of the `plan` table with every value of the "any" columns; `display` for each `Show` with and without items |
| `FeedLiveUpdateTest` | 1, 2, 6, 7, 8, 10 | sequences through `wake` + `next` + `rows` + `items` with a `PublishProcessor` source: **progress, batch, batch, end** (rebuild on the first paced count, the second waits for the period, the end always rebuilds, `shownAs` is the success event); first item of a new page during a refresh rebuilds; empty rows -> `items` null until a batch brings rows (AC-6); progress ticks without a batch: no rebuild, `rows` the same instance, `items` the same instance; a change of `showPlayed` rebuilds at once; a new subscription to `wake` rebuilds once; refresh of group 9 on the page of group 7: first item LOADED as `IdleEvent`, then `next` false for progress, batches, success and error; the same refresh on the "All" page: as concerned; error for a concerned page: `ErrorResultEvent` unchanged, no rebuild; after END a waiting paced count emits nothing; `PACE_MILLIS == REBUILD_PERIOD_MILLIS + DEFAULT_THROTTLE_TIMEOUT` |
| `FeedLiveScaleTest` | 2, 10 | 1600 progress events and 80 batches, rebuilds counted, page subscribed at 0 with the default pace: batches every 5000 ms and success at 405 000 -> 82 list builds (1 on opening, 80, 1 final); batches at 333 ms x k and success at 30 000 -> 15 (1 + 13 + 1); 80 batches at 1000 and success at 30 000 -> 3; in each, no two refresh-driven rebuilds closer than `PACE_MILLIS`; page of another group -> 1; `wake` never subscribed -> `stored()` has no subscriber and nothing is built |
| `FeedListRulesTest` | 3, 5, 9 | every row of the `afterUpdate` table, with: insert above, insert below, anchor item removed, all old items gone, `firstVisible` -1 and beyond the end, `duringRefresh` false; `sameRows`: same instances, equal but different instances, different size, both empty; `matches`: title, uploader, case, empty text, nulls; `progressText`: counts, message, neither, `0/n` |
| `FeedLiveWiringTest` | all | per file the number of lines containing `ENH:feed-live-update` (1, 3, 6, 12) and, whitespace ignored, each snippet of section 8. Order: `live` field before `combineDisposable`; `live.wake` after the `oldestSubscriptionUpdate(groupId)` line and before `onScreenGate.gate`; `live.next` after `.throttleLatest(` and before `.map {`; `live.rebuild` before `.getStreams(`; `batchStored` after `.doOnNext(DatabaseConsumer())` and before `.toList()`; `started` and `ended` after `.flatMap { x -> postProcessFeed()`. Enclosing functions: `hideLoading`, `handleProgressState`, `handleLoadedState` (three lines), `updateRefreshViewState`, `highlightNewItemsAfter`, `reloadContent`. Unchanged: `!isRefreshing` twice in `FeedFragment.kt`; no `ENH:feed-live-update` in `res/`; `enhancements_feed_live_update.xml` does not exist; the three plain files import nothing from `android.`, `androidx.` or `.rxjava3.android.` |

**Instrumented**, `app/src/androidTest/.../feedliveupdate/FeedListUpdateTest.kt`, compiled
by CI, run with the device script: a `RecyclerView` measured and laid out by hand on the
main thread, `GridLayoutManager` with the adapter's span lookup, a `GroupieAdapter`, test
items of fixed height. (1) scrolled to item 20, `show` with five new items in front,
`duringRefresh`: the first visible item has the same id and the same top, `showNewItems`
called once; (2) at the top: position 0 is visible and holds the new first id, `showNewItems`
not called; (3) into an empty adapter: no scroll, `onDone` called; (4) span count 3, six
new items in front, scrolled down: same first visible id and top.

**Not testable without a device:** the fragment and view model wiring, the views, Room.

**Device script.** Debug build, the owner's subscriptions imported as in
`subscription-scale/plan.md` section 7 step 1, two groups A and B without a common channel,
"All", A and B as main tabs. One step per criterion; steps 1 to 7 are done during one "All"
refresh unless they say otherwise.

1. AC-1: scroll "All" to the middle, start a refresh from the header. The list stays, same
   position, same items.
2. AC-2: stay at the top; new videos appear in steps at least two seconds apart
   (`adb logcat` with a debug build shows `handleResult`), and after "Processing feed…" the
   list equals the one a second device or the previous build shows after its refresh.
3. AC-3: the header's first line shows "Loading feed… n/1600", then "Processing feed…",
   then "Feed last updated: …". Nothing lies over the list except the pull indicator.
4. AC-4: tap a video (it opens), go back, long-press (menu), the three play buttons,
   multi-select two videos and add them to a playlist.
5. AC-5: in list, card and grid mode: scrolled down, the items on screen do not move for
   three updates and "New feed items" appears, a tap on it leads to the top; at the top,
   new videos come in at the top and the button stays away. Note for the grid whether the
   columns shift. Watch the highlighted rows at the top for flicker (risk 3).
6. AC-6: a new group with channels never loaded: its page shows the centre progress until
   the first batch with one of its channels is stored, then the list. A group with no
   videos keeps it until the end and then shows the empty view.
7. AC-7: refresh A; on B: no progress, no reload, taps work; pull on B: the indicator goes
   back. On "All": progress and updates. After A's end B is unchanged; A or "All",
   whichever is opened first, shows A's error messages.
8. AC-8: compare the end with the build before this change on the same data: error
   dialogs and snackbar, "Not loaded: n", highlighted items and "New feed items", "Feed last
   updated".
9. AC-9: select three videos, refresh: they stay selected through the updates. Search for
   a channel name during the refresh: only matching videos through every update and after
   the end.
10. AC-10: method trace as in `subscription-scale` step 5 during an "All" refresh watched
    from a channel page with two feeds on the back stack: 0 calls of the `FeedDAO_Impl`
    stream queries and of `StreamItem.<init>`. Returning to a feed: one query, current list.
11. AC-11: start by pull, by header, by refresh icon; cancel from the notification (final
    list and messages appear); airplane mode before starting (each channel fails, messages
    as before); pull during the refresh (nothing).
12. AC-12: `git diff --stat origin/dev -- app/src/main/res app/build.gradle` shows nothing
    of this feature.

## 14. AC coverage

| AC | Design | Unit tests | Device |
|---|---|---|---|
| AC-1 | 5.1, 6.1, 6.2 (same rows: list untouched) | `FeedLiveUpdateTest`, `FeedListRulesTest` | 1 |
| AC-2 | 3, 5.2 | `FeedLivePlanTest`, `FeedLiveUpdateTest`, `FeedLiveScaleTest` | 2 |
| AC-3 | 6.1 | `FeedListRulesTest` (`progressText`) | 3 |
| AC-4 | 6.1 (`isRefreshing` false in list mode) | wiring | 4 |
| AC-5 | 7 | `FeedListRulesTest`, instrumented | 5 |
| AC-6 | 5.2 (`display`) | `FeedLivePlanTest`, `FeedLiveUpdateTest` | 6 |
| AC-7 | 4, 5.2, 6.3 | `FeedRefreshLogTest`, `FeedLiveUpdateTest` | 7 |
| AC-8 | 6.2 table, 10.5 | `FeedLiveUpdateTest` (END) | 8 |
| AC-9 | 6.2 | `FeedListRulesTest` (`matches`) | 9 |
| AC-10 | 9 | `FeedLiveScaleTest` | 10 |
| AC-11 | 10 | `FeedRefreshLogTest` | 11 |
| AC-12 | 2 | wiring | 12 |

## 15. Order of work

Three `code(feed-live-update):` commits; each builds alone.

1. `FeedRefreshLog.kt`; `FeedLoadManager.kt`. No visible change.
2. `FeedLiveUpdate.kt`; `FeedState.kt`, `FeedViewModel.kt`. Pacing and AC-7 work; a
   concerned page still shows the full-screen progress, because the fragment ignores `items`.
3. `FeedListRules.kt`, `FeedListUpdate.kt`; `FeedFragment.kt`. Then the device script.

## 16. Risks

1. **A page not concerned stays as it is** until it is returned to, also when it shares
   channels with the refreshed group (AC-7 as written). While a foreign result event waits
   for a concerned page, such a page on screen also does not reload on database writes; it
   reloads on return and on the "show watched" switch.
2. **Grid mode**: columns shift when the number of items inserted above is not a multiple
   of the column count (Q2).
3. **Highlight pass per rebuild.** `highlightNewItemsAfter` redraws the highlighted rows
   (`FeedFragment.kt:1067-1070`), today once, now at each rebuild; they may cross-fade.
   Device step 5. Avoiding it means another edit in that function.
4. **The header's first line** changes its text; if "Feed last updated: …" wraps to two
   lines on a narrow screen, the list moves by one line at the start and the end. After a
   view rebuild during a refresh the empty "Not loaded" line is hidden until the end.
5. **Asynchronous final update.** As today its callback (highlight, and now the scroll to
   the top) is skipped if a newer update follows or Groupie's weak reference is cleared.
6. **Final state twice** in the 120 ms window of 3.2; same effect as the existing case.
7. **Two refreshes at once**: events are attributed to the one started last; a page may
   show or ignore progress of the wrong one until both have ended.
8. **Main-thread diff** during a refresh: up to 500 items every two seconds; largest for
   the first rebuild after a long pause.
9. **Returning to the page during a refresh** restores the saved scroll position by index
   (`FeedFragment.kt:842-845`) on a list that may have grown above it, as a reload does
   today.

## Amendment, 2026-11-25: on upstream 5.6.0

Upstream 5.6.0 trims every stored batch itself (a `.map` with `forNotification()` right after
`.doOnNext(DatabaseConsumer())`), and `subscription-scale` withdrew its own retention line and
parameter from `FeedLoadManager.kt` (its plan, amendment of 2026-11-25). `startLoading` now
returns `Single<List<FeedUpdateInfo>>`. None of this changes this feature's three lines there:

- `batchStored()` stays directly after `.doOnNext(DatabaseConsumer())`, so it still counts a
  batch only once its transaction has committed, and before upstream's trimming.
- `started(groupId)` and `ended()` stay the last two operators of the chain.

For the wiring test: `subscription-scale` has no marked line left in `FeedLoadManager.kt`, and
the order to pin there is `DatabaseConsumer`, `batchStored()`, upstream's `forNotification()`,
`.toList()`.
