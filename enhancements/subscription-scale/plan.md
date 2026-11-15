# Plan: many subscriptions

Spec: `spec.md` (AC-1..AC-7). Paths are relative to `app/src/main/java/org/schabi/newpipe/`
unless they start with `app/` or `enhancements/`. `P` = `org.schabi.newpipe.enhancements.subscriptionscale`.
Depends on Q2, Q3, Q4 in `questions.md`; each states the assumption used here.
"Counted" = read from the code. "Estimate" = not measured.

## 1. Findings checked against the code

| Finding | Holds? | What the code shows |
|---|---|---|
| M6 | yes | `local/feed/service/FeedLoadManager.kt:265-269`: `.buffer(20)` -> `DatabaseConsumer` -> `.toList()` -> `x.flatten()`. Every `FeedUpdateInfo` stays referenced until the end, with `streams` (`FeedUpdateInfo.kt:27`) and `newStreams` (`:59`). `FeedLoadService.kt:98-99` ignores the result. |
| J1 | yes: 4 live sites, 2 dead, 1 not named | `info_list/holder/StreamMiniInfoItemHolder.java:63-64,79-80` and `StreamInfoItemHolder.java:92-93,108-109` call `loadStreamState(infoItem).blockingGet()` in `updateFromItem`, which `InfoListAdapter.java:487` runs on the main thread. `:129` and `:160` (`updateState`) have no caller. Not named: `info_list/ComposeItemUiHelper.kt:226` does the same for rows of the experimental UI. |
| J2 | yes, with a correction | `local/feed/FeedViewModel.kt:43-67`. The 500-row query (`database/feed/dao/FeedDAO.kt:44-67`, five tables) runs on every emission while the feed event is `IdleEvent` or `SuccessResultEvent`. During a refresh the event is `ProgressEvent`: then only the two small queries (`:47-48`) re-run per batch, and the big one runs twice at the end. Nothing stops the stream before `onCleared` (`:86`). |
| J3 | yes, and worse | `local/subscription/SubscriptionViewModel.kt:81-88` maps every emission of `SELECT *` (`database/subscription/SubscriptionDAO.kt:21-22`) to new `ChannelItem`s. `ChannelItem` has no `equals`, so Groupie sees every row as changed and `subscriptionsSection.update` (`local/subscription/SubscriptionFragment.kt:285`) diffs on the main thread and rebinds every visible row. Writers: `SubscriptionManager.kt:128`, per channel inside the 20-channel transaction (`FeedLoadManager.kt:317-327`), and `:84`, per channel from `services/SubscriptionsFetchService.java:120-124`. |

Cost with 1600 subscriptions:

- **M6.** Counted: 1600 `FeedUpdateInfo` in 80 batches, all kept. Estimate: 30-90 items per
  channel at about 1 KB = 50-150 MB at the end of a full refresh.
- **J1.** Counted per bound row: one main-thread wait for an io thread that runs two Room
  `Flowable.blockingFirst()` (`local/history/HistoryRecordManager.java:331-345`). Estimate:
  2-10 ms per row, far longer while the reloads of J2/J3 occupy Room's query threads; that
  contention is its only link to the subscription count.
- **J2.** Counted: one 500-row query per live group feed (back stack or main tab) per write
  burst to `subscriptions`, `feed_group_subscription_join` or `feed_last_updated`
  (`FeedDAO.kt:197-240`); "All" only for the last. Idle triggers: subscribe, unsubscribe, group
  edit, opening a subscribed channel (`fragments/list/channel/ChannelVideosFragment.java:321`),
  each channel of `SubscriptionsFetchService` (up to 1600 x live feeds).
- **J3.** Counted: 80 reloads of 1600 rows x 8 columns per "All" refresh, up to 1600 during
  `SubscriptionsFetchService`; each makes 3200 objects and a 1600-item main-thread diff.
  Estimate: 1-2 MB per reload, mostly `description`.

## 2. Shape of the change

Marked direct edits (`// ENH:subscription-scale`, below `// ENH`) in 11 upstream files: 23
added lines, 13 changed one for one, none removed, no import lines (fully qualified names, as
queue-guard does). Six plain Kotlin files (RxJava 3, extractor and entity classes) and one
Android glue file in `P`. No resources, settings, schema or build file change (AC-7).

```kotlin
// FeedResultRetention.kt
object FeedResultRetention {
    fun retain(batch: List<Notification<FeedUpdateInfo>>, retainNewStreams: Boolean): List<Notification<FeedUpdateInfo>>
}
// LatestRequest.kt: one per row; a newer start() or cancel() makes an older result undeliverable
class LatestRequest<K : Any> {
    fun <T : Any> start(key: K, request: Single<T>, deliverOn: Scheduler, onResult: Consumer<T>)
    fun cancel()
}
// BoundedCache.kt: least recently used, synchronized
class BoundedCache<K : Any, V : Any>(maxEntries: Int) { fun get(key: K): V?; fun put(key: K, value: V); val size: Int }
// OnScreenGate.kt
class OnScreenGate {
    fun setOnScreen(onScreen: Boolean)
    fun <T : Any> gate(source: Flowable<T>): Flowable<T>
}
// SubscriptionRows.kt
const val SUBSCRIPTION_ROWS_QUERY = "SELECT uid, service_id AS serviceId, url, name, avatar_url AS avatarUrl, subscriber_count AS subscriberCount FROM subscriptions ORDER BY name COLLATE NOCASE ASC"
data class SubscriptionRow(val uid: Long, val serviceId: Int, val url: String?, val name: String?, val avatarUrl: String?, val subscriberCount: Long?) {
    fun toChannelInfoItem(): ChannelInfoItem
}
object SubscriptionRows {
    fun sameNamesAndPictures(old: List<SubscriptionRow>, new: List<SubscriptionRow>): Boolean
    fun listing(rows: Flowable<List<SubscriptionRow>>, gate: OnScreenGate): Flowable<List<SubscriptionRow>>
}
// ReusingMapper.kt: used by one stream, not thread-safe
class ReusingMapper<R : Any, I : Any>(idOf: (R) -> Long, create: (R) -> I) { fun map(rows: List<R>): List<I> }
// RowWatchState.kt: Android glue (main-thread scheduler, progress bar, HistoryRecordManager)
class RowWatchState {
    fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, progressView: AnimatedProgressBar)
    fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, onChanged: Runnable)
    fun cancel()
    companion object { @JvmStatic fun cached(item: InfoItem): StreamStateEntity? }
}
```

## 3. M6: keep only what the notification needs (AC-1)

What `NotificationWorker` uses (counted, `local/feed/notifications/NotificationWorker.kt:47-60`,
`NotificationHelper.kt:34-78`): only channels whose `newStreams` is not empty; of those `name`,
`url` (also as `pseudoId`), `serviceId`, `avatarUrl`, and of each new stream its `name` and
the count. Never `streams`, `errors`, or channels without new videos.

`FeedResultRetention.retain`: with `retainNewStreams == false` it returns `emptyList()`.
Otherwise, for each `onNext` item whose `newStreams` is set and not empty, it returns
`Notification.createOnNext(info.copy(streams = emptyList(), errors = emptyList()))` with
`newStreams` copied over; all else is dropped. "Set" is tested with
`runCatching { info.newStreams }`: the `lateinit` property stays unset for a channel
unsubscribed during the refresh (`FeedLoadManager.kt:327`), which today fails the worker.

Edits:
- `FeedLoadManager.kt`, new line after `:74`: `retainNewStreams: Boolean = false, // ENH`.
- `FeedLoadManager.kt`, new line after `:266` (`.doOnNext(DatabaseConsumer())`):
  `.map { batch -> P.FeedResultRetention.retain(batch, retainNewStreams) } // ENH`.
  The return type and `:268-269` stay.
- `NotificationWorker.kt`, new line after `:43`: `retainNewStreams = true, // ENH`.
  `FeedLoadService.kt` is not edited and now retains nothing.

Each batch is released when its transaction ends. Held to the end: nothing per channel for
a manual refresh; for the worker one slim object per notification-enabled channel with new
videos, holding only its new items (Q4). Items in flight were already bounded (6 parallel,
buffer 20). `FeedResultsHolder`'s error list stays; it feeds the "some feeds failed" report.
Could regress: a notification missing a channel or a title.

## 4. J1: watched state without blocking (AC-2)

Chosen: per-row asynchronous load, cancelled on rebind, plus a small in-memory cache.
Rejected: one batched query per page; items reach `InfoListAdapter` by five paths (add, set,
filter, sort, reverse) and the states would be a second list to keep in step with them.

`RowWatchState`, one per row holder:
- `cached(item)`: looks up `"<serviceId>|<url>"` in a process-wide `BoundedCache` of 500
  entries holding "state X" or "has none". Returns the state, or null for none or unknown.
- `refresh(...)`: `LatestRequest.start(item, manager.loadStreamState(item),
  AndroidSchedulers.mainThread()) { ... }`. On the result: put it in the cache; if it differs
  from what the cache held before (other progress, or state vs none), apply it. The progress
  bar variant applies the rule both upstream branches use, without animation, as today:
  `state != null && item.duration > 0` -> visible, max = duration, progress = seconds; else
  gone. The `Runnable` variant calls back. An error is logged (today it crashes the bind).
- **Before the answer:** the cached state if this video was drawn before in this process,
  otherwise no bar (right for most rows). The correction follows one or two frames later.
- **Recycled rows:** `cancel()` is the first statement of every bind, so a holder rebound to
  any item, also a live stream that starts no request, cannot receive the old answer.
  `LatestRequest` disposes the old request and delivers only if it is still the current one;
  cancel and delivery both run on the main thread. A holder recycled and not yet rebound may
  get its answer: it lands in an unused view and the next bind overwrites it.

Edits (field `watched`; `P.` written out in the source):
- `StreamMiniInfoItemHolder.java`: field after `:30`
  `private final P.RowWatchState watched = new P.RowWatchState(); // ENH`; after `:53`
  `watched.cancel(); // ENH`; `:63-64` become
  `final StreamStateEntity paidState = watched.cached(item); // ENH` and
  `watched.refresh(item, historyRecordManager, itemProgressView); // ENH`; `:79-80` the same
  with `state`; `:129` becomes `final StreamStateEntity state = watched.cached(infoItem); // ENH`
  (dead code; changed so the holders contain no `blockingGet`). The upstream `if/else` blocks
  `:65-72` and `:81-88` stay and now draw the cached state. 2 added, 5 changed.
- `StreamInfoItemHolder.java`: the same: field after `:58`, cancel after `:82`, `:92-93`,
  `:108-109`, `:160`. Covers the grid and card holders. 2 added, 5 changed.
- `ComposeItemUiHelper.kt:226` becomes `val state = P.RowWatchState.cached(item) // ENH`.
- `ComposeInfoItemHolder.kt`: field `private val watched = P.RowWatchState() // ENH`; first
  line of `updateFromItem` `watched.cancel() // ENH`; last line
  `if (infoItem is StreamInfoItem) watched.refresh(infoItem, historyRecordManager) { updateFromItem(infoItem, historyRecordManager) } // ENH`.
  The callback fires only on a difference and the second pass finds none, so it ends.
- `HistoryRecordManager` and `InfoListAdapter` are not edited.

Could regress: a bar that appears late or shows an older value for a frame or two; the video
page's related list uses the same holders. Not covered (they block, but not while drawing a
row): `info_list/dialog/StreamDialogDefaultEntry.java:197`, `local/playlist/LocalPlaylistFragment.java:889`.

## 5. J2 and J3: no work off screen, no rebuild without a visible change (AC-3, AC-4, AC-5)

### 5.1 "On screen" for a ViewModel-owned stream

On screen = the fragment is resumed, reported from `onResume`/`onPause`. `LiveData` activity
cannot serve: hidden main tabs are kept started (`fragments/MainFragment.java:199,308`) and
started observers count as active. Resumed is false for a hidden tab, a page on the back
stack and a stopped app; a page under the expanded player stays resumed (Q3).

`OnScreenGate.gate(source)` = `onScreen.distinctUntilChanged().switchMap { ... }` over a
`BehaviorProcessor` that starts `false`:
- on screen: subscribe to `source`, pass everything;
- off screen and nothing delivered yet: `source.take(1)`. This is the first load, so a tab
  not shown yet and `settings/SelectChannelGroupFragment.java:79-81`, which never reports,
  get their data as today;
- off screen otherwise: `Flowable.never()`. The old subscription is disposed, so Room removes
  its table observer and runs nothing.

Nothing is lost because every return subscribes again, and a Room `Flowable` and
`FeedEventManager.events()` emit their current value on subscription. `source` may be
subscribed from the main thread; Room's `Flowable` moves itself to its executor.

### 5.2 Group feed (J2)

- `FeedViewModel.kt`: field after `:41` `private val onScreenGate = P.OnScreenGate() // ENH`;
  new line after `:54` (end of `combineLatest`) `.let { onScreenGate.gate(it) } // ENH`; new
  line after `:105` `fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen) // ENH`.
- `local/feed/FeedFragment.kt`: after `:245` `viewModel.setOnScreen(false) // ENH`; after
  `:252` `viewModel.setOnScreen(true) // ENH`.

The gate sits before `throttleLatest` and the query (`:55-67`), so off screen neither the
small queries nor the 500-row query run. On return `combineLatest` emits once (events and the
played-items toggle replay at once, then both database values arrive): one query, one
`LoadedState`. No `distinctUntilChanged` is added: a group edit can leave both database
values equal and must still reload the list (AC-5).

Could regress: see "return cost" and "shared feed event" in section 10.

### 5.3 Subscriptions tab (J3)

- `SubscriptionDAO.kt`, two lines after `:22`: `@Query(P.SUBSCRIPTION_ROWS_QUERY) // ENH` and
  `abstract fun getAllRows(): Flowable<List<P.SubscriptionRow>> // ENH`. Six columns, no
  `description`; Room maps by the aliases; the schema is unchanged.
- `SubscriptionViewModel.kt`: fields after `:18` `private val onScreenGate = P.OnScreenGate() // ENH`
  and `private val channelItems = P.ReusingMapper<P.SubscriptionRow, ChannelItem>({ it.uid }) { ChannelItem(it.toChannelInfoItem(), it.uid, ChannelItem.ItemVersion.MINI) } // ENH`;
  after `:50` `fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen) // ENH`;
  after `:72` (groups stream) `.let { onScreenGate.gate(it) } // ENH`; `:81` becomes
  `private var stateItemsDisposable = P.SubscriptionRows.listing(subscriptionManager.subscriptionTable().getAllRows(), onScreenGate) // ENH`;
  `:83` becomes `.map { rows -> channelItems.map(rows) } // ENH`. 4 added, 2 changed.
- `SubscriptionFragment.kt`: after `:92` `viewModel.setOnScreen(true) // ENH`; after `:104`
  `viewModel.setOnScreen(false) // ENH`.

`listing(rows, gate)` = `gate.gate(rows.distinctUntilChanged(::sameNamesAndPictures)).distinctUntilChanged()`:
- `sameNamesAndPictures`: same size and, position by position, same `uid`, `serviceId`, `url`,
  `name`, `avatarUrl`. While the tab stays on screen, a write that changes only subscriber
  counts or descriptions emits nothing: no mapping, no diff, no rebind (AC-4, Q2).
- The inner filter lives for one subscription, so a return always lets the first list through;
  the outer one (all six fields) drops it if nothing changed at all. A return therefore shows
  current names, pictures and counts and costs one background query and a comparison.
- `ReusingMapper.map` returns the same `ChannelItem` for a row equal to the one last mapped
  under that id, a new one otherwise, and forgets ids that are gone. Groupie compares
  instances, so subscribing to one channel inserts one row and rebinds no other.

AC-5: subscribe and unsubscribe change size or ids and pass both filters; on the tab they
show at once, done elsewhere they show on return. Group edits reach the tab through the
groups stream and a feed through 5.2.

Could regress: counts lag (Q2). The settings group picker shows the groups as of its opening.
Channel rows lose `description`, which the `MINI`/`GRID` rows and the long-press menu
(`SubscriptionFragment.kt:289-314`) do not use.

## 6. Overlap with other planned changes

- **release-views** adds `onDestroyView` to `SubscriptionFragment.kt` and edits the list base
  classes; this plan adds one line each after `:92` and `:104`: different hunks. Its
  `itemsList.setAdapter(null)` recycles rows; a pending row answer then lands in an unused
  view (section 4). It keeps `groupAdapter` and its items across view rebuilds, which
  `ReusingMapper` relies on. It does not touch `FeedFragment.kt` or the holders.
- **group-sort** replaces `FeedDatabaseManager.kt:39` and leaves `SubscriptionViewModel.kt`
  alone; this plan adds a line after `SubscriptionViewModel.kt:72` and leaves
  `FeedDatabaseManager` alone. Its sorted stream is a cold `combineLatest`, so the gate
  resubscribes it on return and the tab shows the current sort order. Its sentence "consumer 1
  stays subscribed for the life of its ViewModel" becomes "while on screen", same visible
  result; its picker (consumer 2) still gets the first emission (5.1).
- **queue-guard, default-open-action** edit `FeedFragment.kt:330-347,781`; this plan `:245,252`.
- To avoid conflicts: no feature edits another's marked lines; integration merges them.

## 7. Verification

**JVM unit tests**, `app/src/test/java/org/schabi/newpipe/enhancements/subscriptionscale/`,
JUnit 4 and RxJava 3 test classes (`TestSubscriber`, `TestScheduler`, processors, subjects).
No Android class is loaded: `FeedUpdateInfo` is built with its primary constructor;
`StreamInfoItem` and `ChannelInfoItem` are extractor classes.

| Test class | AC | Cases |
|---|---|---|
| `FeedResultRetentionTest` | 1 | `false` -> empty; `true` -> only channels with new streams, `streams` and `errors` empty, `name`, `url`, `avatarUrl`, `serviceId`, `pseudoId`, `newStreams` kept; unset or empty `newStreams` and `onError` items dropped |
| `LatestRequestTest` | 2 | delivered once, only when the scheduler runs; a second `start` or `cancel()` -> the first result is never delivered, also if it completes later; error -> no callback, no throw |
| `BoundedCacheTest` | 2 | hit, miss, overwrite, least recently used entry evicted at `maxEntries` |
| `OnScreenGateTest` | 3, 4 | off: first item delivered, then the source has no subscriber and later items are not delivered; on: one new subscription, current item delivered; off again: unsubscribed; on before the first item: one delivery |
| `SubscriptionRowsTest` | 4, 5 | on screen: count-only change -> nothing; name or picture change, insert, delete, reorder -> emitted; off: source not subscribed; return after a count-only change -> one emission; return unchanged -> nothing; `toChannelInfoItem` fields, null count -> -1 |
| `ReusingMapperTest` | 4, 5 | equal row -> same instance; changed row -> new one; removed id forgotten; order follows the input |

**Not unit-testable:** holder and fragment wiring and Groupie (Android views), the Room query
(generated code needs SQLite on a device; optionally an instrumented test with an in-memory
`AppDatabase` for `getAllRows()`), `RowWatchState` (main looper), AC-6. Wiring is reviewed with
`git grep -n "ENH:subscription-scale"` against this plan (23 added, 13 changed),
`git grep -n blockingGet -- '*/info_list/holder/*' '*/ComposeItemUiHelper.kt'` (empty) and
`git diff --stat origin/dev -- app/src/main/res app/build.gradle` (empty, AC-7).

**Device script.** Debug build; `$PKG` from `adb shell pm list packages | grep -i NewPipeEnhanced`.
Every step is run on the build before and after the change.

1. Profile. Real: in the owner's installed app, Subscriptions > Import/Export > export to
   file; copy it over; in the debug build Import from > Previous export (inserts without
   network). Synthetic, for list checks only (cannot be refreshed): a file
   `{"app_version":"x","app_version_int":0,"subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/UC<22 chars>","name":"Channel 0001"}, ...]}`
   with 1600 entries. Create six groups of 50-300 channels; make two feeds main tabs.
2. AC-6: `adb shell dumpsys gfxinfo $PKG reset`; ten times
   `adb shell input swipe 540 1600 540 400 150` on (a) the Subscriptions tab, (b) a channel's
   video list containing watched videos; then
   `adb shell dumpsys gfxinfo $PKG | grep -E "Janky|percentile"`. Note janky % and the
   90th/95th/99th percentile. Repeat (a) during an "All" refresh. Expected: (b) and (a) during
   a refresh clearly better, (a) idle not worse.
3. AC-1: during an "All" refresh, `adb shell dumpsys meminfo $PKG | grep "Java Heap"` every
   30 s: before it grows to the end, after it stays flat within noise. With notifications on
   for three channels, let the worker run when one has a new upload: one notification per
   channel with title, count and lines as before.
4. AC-2: channel video list and search results show the same bars as before on first draw,
   after flinging away and back, and after watching part of a video and reopening the list; a
   live row never shows one. Once more with the experimental UI on.
5. AC-3, AC-4 by method trace: `adb shell am profile start $PKG /data/local/tmp/s.trace`, act,
   `adb shell am profile stop $PKG`; in Android Studio count calls of the `FeedDAO_Impl`
   stream queries, the `SubscriptionDAO_Impl` list queries and `ChannelItem.<init>`.
   (a) two group feeds on the back stack, then subscribe and unsubscribe three times on a
   channel page: 0 feed queries (before: at least 12). (b) Subscriptions tab on screen during
   a refresh of a 100-channel group: 0 `ChannelItem.<init>` unless a name or picture changed
   (before: 5 x 1600). (c) the same refresh watched from a channel page: 0 list queries.
6. AC-5: subscribe on a channel page, Back: the channel is in the tab. Unsubscribe by
   long-press: the row leaves at once, position kept. Add a channel to a group, then open the
   group feed from the back stack and from a main tab: its videos are there. Rename a group:
   the card shows it. Rotate on each screen.

## 8. AC coverage

| AC | Design | Unit tests | Device |
|---|---|---|---|
| AC-1 | section 3 | `FeedResultRetentionTest` | 3 |
| AC-2 | section 4 | `LatestRequestTest`, `BoundedCacheTest` | 2b, 4 |
| AC-3 | 5.1, 5.2 | `OnScreenGateTest` | 5a |
| AC-4 | 5.1, 5.3 | `OnScreenGateTest`, `SubscriptionRowsTest`, `ReusingMapperTest` | 5b, 5c |
| AC-5 | 5.2 (no filter), 5.3 (size and ids pass) | `SubscriptionRowsTest`, `ReusingMapperTest` | 6 |
| AC-6 | sections 3-5 together | none | 2 |
| AC-7 | section 2 | none | diff check in section 7 |

## 9. Order of work

Four `code(subscription-scale):` commits. Each builds alone and brings its own new files.
Steps 1, 2 and 4 revert cleanly at any time; step 3 owns `OnScreenGate.kt`, so reverting it
while step 4 is in place keeps that one file.

1. **M6**: `FeedResultRetention.kt`; `FeedLoadManager.kt`, `NotificationWorker.kt`.
2. **J1**: `LatestRequest.kt`, `BoundedCache.kt`, `RowWatchState.kt`; the two holders, the two
   Compose files.
3. **J2**: `OnScreenGate.kt`; `FeedViewModel.kt`, `FeedFragment.kt`.
4. **J3**: `SubscriptionRows.kt`, `ReusingMapper.kt`; `SubscriptionDAO.kt`,
   `SubscriptionViewModel.kt`, `SubscriptionFragment.kt`. Then the device script, with the
   before and after numbers in the pull request.

## 10. Risks

- **Stale by design** (Q2): subscriber counts on a tab that stays open during a refresh.
- **Return cost**: a feed re-queries and rebinds its visible rows on every resume, also after
  screen off and on (today only after a write). One query per return replaces one per write
  per live feed; device step 5 measures it.
- **Shared feed event**: a refresh ending while no feed is on screen leaves its
  `SuccessResultEvent` in `FeedEventManager`; the next feed opened consumes it and shows that
  refresh's error report, where today the feed that started it would.
- **First show of a hidden tab** relies on the `take(1)` load. If `setOnScreen(true)` arrives
  before it finishes, the load restarts once (a second small query, no second list).
- **Row state for a frame or two**: unknown videos draw without a bar first; device step 4
  decides whether that is visible.
- **Item reuse** needs the fragment to keep setting `gesturesListener` and `itemVersion` on
  every delivery (`SubscriptionFragment.kt:275-283`); it does.
- **Rebase**: 11 upstream files; the 13 changed lines conflict if upstream edits the same
  statements, most likely the holders' state blocks and `SubscriptionViewModel.kt:81-83`.
- **Left as is**: `SubscriptionManager.updateFromInfo` still writes unchanged rows, so the slim
  list query still runs once per batch while the tab is on screen (background, six short
  columns); `SubscriptionFragment.onResume` (`:100`) still re-applies the filter.
