# Plan: group-create-inline

Implements `spec.md` AC-1..AC-16. New package `org.schabi.newpipe.enhancements.groupcreateinline`.
Code paths below are relative to `app/src/main/java/org/schabi/newpipe/`, resource paths to
`app/src/main/`. Line numbers are those of commit `1f62f1a`. `FGSD` =
`local/subscription/dialog/FeedGroupSelectionDialog.java`.

## 1. Approach

- The upstream "Select groups" dialog stays the dialog. **One inserted line** in
  `FGSD.onCreateDialog` hands the fork the root view, the subscription id, the loaded group list
  and the dialog's own save method. Upstream files touched: **1**. No upstream statement, layout,
  string or build file is edited.
- At runtime the fork adds a *New group* button below the list, pending rows (own adapter
  concatenated after upstream's rows), a name prompt, and an OK handler that first creates the
  checked pending groups in one database transaction, then runs upstream's unchanged save.
- Decisions live in three plain-Kotlin files (section 4); Android glue is five small files
  (section 5) behind the interfaces `SelectionUi`, `GroupSaver`, `GroupTable`.
- Rejected: replacing the dialog at `fragments/list/channel/ChannelVideosFragment.java:758` with
  an own `DialogFragment`. Also one file, one line, but it copies the dialog, and upstream
  changes or another feature's hook inside `FGSD` (group-sort) would not reach the copy.

## 2. Assumptions (what the spec leaves open)

| # | Assumption | ACs |
|---|---|---|
| S1 | **Open question Q3.** AC-12 ("stays open, error shown") applies to creating the pending groups. Today's save of the other checkbox changes keeps today's failure behaviour: it closes the dialog silently (`FGSD:217-220`). Pending groups are committed first; if that fails, nothing at all is saved. If Q3 is answered "all changes": the hook line gains two arguments (`selectedGroupIds`, `originalGroupIds`), `GroupWriter` also applies the membership adds/removes in the same transaction, and `SelectionUi.runUpstreamSave()` becomes a plain dismiss. Still one line in one file. | AC-8, AC-12 |
| S2 | *New group* is visible at once but disabled until upstream has loaded the groups (milliseconds). The duplicate check needs the loaded names, and upstream ignores OK until then (`FGSD:163-165`). | AC-1, AC-4 |
| S3 | Pending groups are listed after all existing groups, in the order added, as ordinary rows. | AC-5, AC-7 |
| S4 | While the creation transaction runs, OK, Cancel, *New group* and Back are disabled, as the existing group dialog does while saving (`local/subscription/dialog/FeedGroupDialog.kt:517-524`). | AC-8, AC-10 |
| S5 | Rotation discards pending groups and an open name prompt. Upstream already discards unsaved checkbox changes (`FGSD:56-66`). | AC-15 |

## 3. Hook point

`FGSD`, method `onCreateDialog` (`:70-81`): insert one line after `setupButtons(view);` (`:75`),
before `loadDataSynchronously();` (`:76`):

```java
        org.schabi.newpipe.enhancements.groupcreateinline.GroupCreateInline.attach(this, view, subscriptionId, allGroups, this::saveChanges); // ENH:group-create-inline
```

Fully qualified on purpose: no import line, so the upstream diff is exactly one added line
(`app/build.gradle` applies no checkstyle or line-length check). It must stay after `:74`
(adapter is set) and `:75` (so the fork's listener replaces the one set at `:95`).

| Argument | Upstream source | Used for |
|---|---|---|
| `this` | the `DialogFragment` | context, lifecycle (clean up on destroy), `isCancelable` |
| `view` | local, `:71` | find `groups_recycler_view`, `save_button`, `cancel_button`; insert the button |
| `subscriptionId` | field `:38`, set `:58` | member of every created group |
| `allGroups` | field `:39`, filled by the load `:104-105` | **read only**: names of existing groups (AC-4) |
| `this::saveChanges` | private method `:162-224`, as `Runnable` | today's save and dismiss |

Original statements: none changes textually. At runtime `attach` (a) replaces the click listener
of `save_button` set at `:95`; `saveChanges()` still performs today's save, called through the
`Runnable`; (b) replaces the RecyclerView's adapter by `ConcatAdapter(upstreamAdapter,
pendingAdapter)`; upstream keeps notifying through its own `adapter` field (`:150`, `:155`),
which `ConcatAdapter` forwards; (c) adds one child view to the root layout. The fork never
writes upstream's `allGroups`, `selectedGroupIds`, `originalGroupIds` or `adapter`.

## 4. New files: decision logic

No import from `android.*`, `androidx.*`, `io.reactivex.*`, or `org.schabi.newpipe.*` outside
this package: JVM tests run against the stub `android.jar` (`app/build.gradle` sets no
`returnDefaultValues`), where any Android call throws. Synchronous, main-thread-confined.

### `GroupNameRules.kt`

```kotlin
enum class NameCheck { OK, EMPTY, DUPLICATE }

object GroupNameRules {
    /** The name as stored and shown: Kotlin `raw.trim()`. */
    fun clean(raw: String): String
    /** Spec "same name": `clean(a).equals(clean(b), ignoreCase = true)`. */
    fun sameName(a: String, b: String): Boolean
    /** EMPTY if `clean(raw)` is empty; else DUPLICATE if `sameName(raw, t)` for a t in
     *  [takenNames]; else OK. */
    fun check(raw: String, takenNames: Collection<String>): NameCheck
}
```

### `GroupCreateSession.kt`

```kotlin
data class PendingGroup(val id: Long, val name: String, val checked: Boolean)

fun interface SaveCallback { fun onResult(error: Throwable?) }   // null = success

interface GroupSaver {
    /** Creates one group per name, in list order, each with [subscriptionId] as its only
     *  member; all or nothing. Calls [callback] exactly once, now or later. */
    fun createGroups(names: List<String>, subscriptionId: Long, callback: SaveCallback)
}

interface SelectionUi {
    fun showPending(pending: List<PendingGroup>)   // render exactly these pending rows
    fun setBusy(busy: Boolean)                     // S4
    fun showSaveError()                            // AC-12
    fun runUpstreamSave()                          // today's save of the checkboxes + dismiss
}

class GroupCreateSession(
    private val subscriptionId: Long,
    private val existingNames: () -> List<String>,  // asked on every submitName
    private val saver: GroupSaver,
    private val ui: SelectionUi
) {
    val pending: List<PendingGroup>   // read-only snapshot, in the order added
    val isBusy: Boolean               // true only while a createGroups call is unanswered
    fun submitName(raw: String): NameCheck
    fun setChecked(id: Long, checked: Boolean)
    fun confirm()
}
```

Contract (tests assert exactly this, including the order of `ui` calls):

- `submitName(raw)`: `r = GroupNameRules.check(raw, existingNames() + names of all pending
  groups, checked or not)`. `r != OK`: return `r`; no state change, no `ui` call. `OK`: append
  `PendingGroup(id, GroupNameRules.clean(raw), checked = true)`, call `ui.showPending(pending)`
  once, return `OK`. Ids are 1, 2, 3, ... in the order added, never reused (row ids, not
  database ids). Never calls `saver`.
- `setChecked(id, checked)`: sets that item's flag, position unchanged; unknown id ignored. No
  `ui` or `saver` call.
- `confirm()`:
  1. `isBusy`: return, nothing happens (double tap).
  2. `names` = names of the checked pending groups, in the order added.
  3. `names` empty: `ui.runUpstreamSave()`, return. No `saver`, no `setBusy`.
  4. Else `isBusy = true`, `ui.setBusy(true)`, then `saver.createGroups(names, subscriptionId,
     cb)`. The callback may arrive inside that call.
  5. `cb(null)`: pending becomes empty, `isBusy = false`; then in this order
     `ui.setBusy(false)`, `ui.showPending(emptyList())`, `ui.runUpstreamSave()`.
  6. `cb(error)`: `isBusy = false`, pending unchanged (names, flags, order); then
     `ui.setBusy(false)`, `ui.showSaveError()`. No `runUpstreamSave`. A later `confirm()` retries.
- `submitName`/`setChecked` while `isBusy` cannot be reached from the UI: unspecified, not tested.

### `GroupWriter.kt`

```kotlin
interface GroupTable {
    fun inTransaction(block: () -> Unit)                 // commits; rolls back if block throws
    fun subscriptionExists(subscriptionId: Long): Boolean
    fun insertGroup(name: String): Long                  // default icon, last in order; new id
    fun setMembers(groupId: Long, subscriptionIds: List<Long>)
}

object GroupWriter {
    fun createGroupsWithMember(table: GroupTable, names: List<String>, subscriptionId: Long)
}
```

Contract: `names` empty: return without touching `table`. Otherwise exactly one
`table.inTransaction { ... }`, with every other table call inside it, in this order:
`subscriptionExists(subscriptionId)` once, and if false throw `IllegalStateException` before any
insert; then for each name in list order `id = insertGroup(name)` immediately followed by
`setMembers(id, listOf(subscriptionId))`. Nothing is caught: a table exception propagates.

## 5. New files: Android glue (no decisions)

| File | Signature | Does |
|---|---|---|
| `GroupCreateInline.kt` | `object GroupCreateInline { @JvmStatic fun attach(dialog: DialogFragment, root: View, subscriptionId: Long, existingGroups: List<FeedGroupEntity>, upstreamSave: Runnable) }`, plus a private `SelectionUi` implementation | wiring, steps below |
| `PendingGroupsAdapter.kt` | `class PendingGroupsAdapter(onCheckedChange: (id: Long, checked: Boolean) -> Unit) : RecyclerView.Adapter<PendingGroupsAdapter.Holder>() { fun submit(items: List<PendingGroup>) }` | rows inflated from upstream `R.layout.item_feed_group_selection` (`group_checkbox`, `group_name`); row click toggles the checkbox; bind sets the state with the listener detached |
| `NamePrompt.kt` | `class NamePrompt(context: Context, onSubmit: (String) -> NameCheck) { fun show(); fun dismiss() }` | AC-2, 3, 4, 6; below |
| `RoomGroupTable.kt` | `class RoomGroupTable(database: AppDatabase) : GroupTable` | section 7 |
| `RxGroupSaver.kt` | `class RxGroupSaver(table: GroupTable) : GroupSaver { fun dispose() }` | `Completable.fromAction { GroupWriter.createGroupsWithMember(table, names, subscriptionId) }`, `subscribeOn(Schedulers.io())`, `observeOn(AndroidSchedulers.mainThread())`; success `onResult(null)`, error `onResult(e)`; `dispose()` drops undelivered callbacks |

`GroupCreateInline.attach`:

1. Find `R.id.groups_recycler_view`, its adapter, `R.id.save_button`, `R.id.cancel_button`. If
   one is missing, return and change nothing (upstream behaviour intact).
2. `recycler.adapter = ConcatAdapter(upstreamAdapter, pendingAdapter)`.
3. Inflate `enhancements_group_create_inline_new_group` into `recycler.parent as ViewGroup` at
   `indexOfChild(recycler) + 1` (between the list and the Cancel/OK row), disabled. An
   `AdapterDataObserver` on `upstreamAdapter` enables it on the first change callback (`onChanged`
   and the range callbacks). Upstream notifies when its load ends, on success and on failure,
   also with zero groups (`FGSD:149-150`, `:154-155`).
4. `session = GroupCreateSession(subscriptionId, { existingGroups.map { it.name } },
   RxGroupSaver(RoomGroupTable(NewPipeDatabase.getInstance(context))), ui)`.
5. Button click: `NamePrompt(context, session::submitName).show()`. `save_button` click:
   `session.confirm()`.
6. `dialog.lifecycle.addObserver(DefaultLifecycleObserver)`: `onDestroy` dismisses an open prompt
   (a plain `AlertDialog`, it would leak its window otherwise) and calls `saver.dispose()`.

`SelectionUi` implementation: `showPending` = `pendingAdapter.submit(items)`, then scroll to the
last row if not empty. `setBusy(b)` = `isEnabled = !b` on `save_button`, `cancel_button` and
*New group*, and `dialog.isCancelable = !b`. `showSaveError` = long `Toast` with
`enh_group_create_inline_save_failed`. `runUpstreamSave` = `upstreamSave.run()`.

`NamePrompt`: `androidx.appcompat.app.AlertDialog.Builder(context)` (as the parent, `FGSD:78`), title
`enh_group_create_inline_new_group`, the prompt layout inflated with the builder's context,
positive `enh_group_create_inline_add` and negative `R.string.cancel`, both with a `null`
listener. Before `show()`: soft input mode `SOFT_INPUT_STATE_ALWAYS_VISIBLE`, `requestFocus()` on
the field. After `show()`: an `OnClickListener` on the positive button (so the dialog does not
close by itself) and an editor-action listener for `IME_ACTION_DONE` both call one `submit()`:
`OK` dismisses; `EMPTY` sets the container error `R.string.feed_group_dialog_empty_name`;
`DUPLICATE` sets `enh_group_create_inline_name_exists`. A text change clears the error. Cancel,
Back and touch outside only dismiss.

## 6. New resources

`res/values/enhancements_group_create_inline.xml` (no `translatable="false"`, no other locale):

| Name | English text | Used for |
|---|---|---|
| `enh_group_create_inline_new_group` | New group | button (AC-1), prompt title (AC-2) |
| `enh_group_create_inline_add` | Add | prompt's positive button (AC-2) |
| `enh_group_create_inline_name_exists` | A group with this name already exists | AC-4 |
| `enh_group_create_inline_save_failed` | Could not save. No group was created. | AC-12 |

Upstream strings reused read-only (`res/values/strings.xml`): `feed_group_dialog_empty_name`
(`:642`, AC-3), `cancel` (`:8`), `name` (`:403`, field hint).

| New layout | Content |
|---|---|
| `res/layout/enhancements_group_create_inline_new_group.xml` | one `Button`, id `enh_group_create_inline_new_group_button`, `style="@style/Widget.AppCompat.Button.Borderless"` (as upstream's Cancel, `res/layout/dialog_feed_group_selection.xml:32`), `wrap_content` both ways, text `@string/enh_group_create_inline_new_group` |
| `res/layout/enhancements_group_create_inline_name_prompt.xml` | `FrameLayout` with `?dialogPreferredPadding` left and right, containing a `TextInputLayout` (`enh_group_create_inline_name_container`, `android:hint="@string/name"`, `app:errorEnabled="true"`) around `org.schabi.newpipe.views.NewPipeEditText` (`enh_group_create_inline_name_input`, `inputType="textCapSentences"`, `maxLines="1"`, `imeOptions="actionDone"`, `importantForAutofill="no"`); the same pair as `res/layout/dialog_feed_group_create.xml:34-57` |

No upstream layout is edited; `item_feed_group_selection.xml` is only inflated.

## 7. Persistence

`RoomGroupTable` maps to existing calls only:

| `GroupTable` | Existing call |
|---|---|
| `inTransaction(block)` | `AppDatabase.runInTransaction(Runnable)`; database from `NewPipeDatabase.getInstance(context)` (`NewPipeDatabase.java:30`) |
| `subscriptionExists(id)` | `subscriptionDAO().getSubscription(id) != null` (`database/subscription/SubscriptionDAO.kt:115`) |
| `insertGroup(name)` | `feedGroupDAO().insert(FeedGroupEntity(0, name, FeedGroupIcon.ALL))` (`database/feed/dao/FeedGroupDAO.kt:23-28`) |
| `setMembers(groupId, ids)` | `feedGroupDAO().updateSubscriptionsForGroup(groupId, ids)` (`FeedGroupDAO.kt:48-52`) |

Sequence on OK:

1. Main thread: `session.confirm()` picks the checked pending names. None: go to step 4 (AC-9).
2. `Schedulers.io()`, one `runInTransaction`: subscription check, then per name in the order
   added `insert` and `updateSubscriptionsForGroup(newId, [subscriptionId])`. The DAO's own
   `@Transaction` methods join the outer transaction. Group and member row are written together,
   so this dialog cannot leave a group empty (AC-8, AC-9).
3. On an exception Room rolls everything back; the main thread gets `onResult(e)`; error toast;
   dialog, checkboxes and pending rows stay; upstream's save is not started. Stop (AC-12).
4. Main thread: upstream `saveChanges()` (`FGSD:162-224`) saves the other checkbox changes
   through `FeedDatabaseManager.subscriptionIdsForGroup` / `updateSubscriptionsForGroup` and
   dismisses, exactly as today (AC-8).

- AC-11: `FeedGroupIcon.ALL` is what the existing dialog starts with and saves by default
  (`FeedGroupDialog.kt:289`, `:415`). `FeedGroupDAO.insert` sets `sort_order` to
  `MAX(sort_order) + 1` (`:25-26`, `:62-63`); the existing create flow ends in the same call
  (`local/feed/FeedDatabaseManager.kt:154-158`). Sequential inserts keep the order added.
- AC-12: `FeedDatabaseManager.createGroup` followed by `updateSubscriptionsForGroup` is
  deliberately **not** used: those are two transactions (`FeedDatabaseManager.kt:147-158`,
  chained in `FeedGroupDialogViewModel.kt:69-76`), and a failure between them leaves an empty
  group. The subscription check is there because the join table's foreign keys are deferred
  (`database/feed/model/FeedGroupSubscriptionEntity.kt:16-30`): a missing subscription would
  otherwise fail only at COMMIT.
- Group list: the fork runs no query for it. It reads the list upstream loaded with
  `FeedDatabaseManager.groups()` (`FGSD:100`), so whatever group-sort does to that source or to
  this dialog applies unchanged; pending rows follow it.

## 8. AC coverage

JVM tests in `app/src/test/java/org/schabi/newpipe/enhancements/groupcreateinline/`:
`GroupNameRulesTest`, `GroupCreateSessionTest`, `GroupWriterTest`,
`GroupCreateInlineResourcesTest`; method names start with `ac<n>_`. "Device n" = manual check n
of section 11, used where the behaviour is Android framework or Room.

| AC | Implemented by | Verified by |
|---|---|---|
| AC-1 | `attach` step 3, button layout | Device 1 (view insertion). |
| AC-2 | `NamePrompt` | Device 2 (focus, keyboard, IME action). |
| AC-3 | `GroupNameRules.check`, `submitName`, `NamePrompt.submit` | JVM: `check` is `EMPTY` for `""`, `"   "`, tab/newline; `submitName` returns `EMPTY`, pending unchanged, no `ui`/`saver` call. Device 2: error text on the field, prompt stays. |
| AC-4 | same | JVM: `sameName("Music", " music ")` and `"MUSIC"`; `submitName` returns `DUPLICATE` against `existingNames`, against a checked and against an unchecked pending group; pending unchanged. Device 2: error text. |
| AC-5 | `submitName`, `PendingGroupsAdapter` | JVM: `submitName(" Music ")` returns `OK`; last pending is name `"Music"`, `checked = true`; one `showPending`; `saver` not called; flags of earlier pending groups unchanged. Device 3: existing rows keep their checkboxes. |
| AC-6 | `NamePrompt` calls `onSubmit` only from Add and IME done | Device 2 (dismissal paths). |
| AC-7 | `submitName` | JVM: three names give three pending groups in order, ids 1, 2, 3. |
| AC-8 | `confirm`, `GroupWriter`, section 7 | JVM: `saver` gets the checked names and the subscription id once; on success the `ui` order of `confirm` step 5; without pending groups only `runUpstreamSave`. `GroupWriter`: each `insertGroup` is followed by `setMembers(id, [sub])`. Device 3. |
| AC-9 | `setChecked`, `confirm` steps 2-3 | JVM: unchecked names are not passed; all unchecked: `saver` never called, `runUpstreamSave` once; a re-checked name is passed again. Device 3. |
| AC-10 | `saver` is reachable only from `confirm` | JVM: after `submitName`/`setChecked` without `confirm`, `saver` has no call. Device 4. |
| AC-11 | `RoomGroupTable.insertGroup`, list order | JVM: `saver` receives and `GroupWriter` inserts the names in the order added. Device 3: icon and position (Room). |
| AC-12 | `confirm` step 6, `GroupWriter`, `runInTransaction` | JVM: `cb(error)` gives `setBusy(false)`, `showSaveError`, no `runUpstreamSave`, pending intact, retry works; a second `confirm` while busy does nothing. `GroupWriter`: missing subscription throws `IllegalStateException` with no insert; a throwing `setMembers` propagates; every call is inside the one `inTransaction`. The rollback itself is Room's: review of `RoomGroupTable`. |
| AC-13 | ordinary `feed_group` row; the tab observes `groups()` (`local/subscription/SubscriptionViewModel.kt:72`) | Device 3. Emptying is done with "Remove from group" in the group's channel list; the edit dialog refuses an empty selection (`FeedGroupDialog.kt:300-303`). |
| AC-14 | `FeedGroupDialog*.kt` untouched; `GroupNameRules` used only in this package | Review: the DoD diff shows no change in `FeedGroupDialog.kt`. Device 5. |
| AC-15 | session lives in `attach`, nothing goes into instance state, lifecycle observer | JVM: a new session has no pending groups (with AC-10). Device 4. |
| AC-16 | strings file, section 6 | JVM: parse `src/main/res/values/enhancements_group_create_inline.xml` (Gradle runs unit tests with `app/` as working directory); assert the four names, their English text, no `translatable="false"`. |

Test doubles, hand-written: `FakeUi` records calls in order. `FakeSaver` records arguments and
either answers at once (success or error) or keeps the callback for the test to answer.
`FakeTable` records calls and whether each was inside `inTransaction`, hands out ids, can throw
on the n-th `setMembers`, can report the subscription missing.

## 9. Order of work

(1) Resources of section 6. (2) `GroupNameRules.kt`, `GroupCreateSession.kt`, `GroupWriter.kt`,
until the JVM tests are green. (3) `RoomGroupTable.kt`, `RxGroupSaver.kt`.
(4) `PendingGroupsAdapter.kt`, `NamePrompt.kt`, `GroupCreateInline.kt`. (5) The hook line of
section 3. (6) `git grep "ENH:group-create-inline"` shows one line and the DoD diff one upstream
line. (7) Device checks of section 11.

## 10. Risks and edge cases found in the code

- **Partial failure (Q3).** If creation succeeds and upstream's save of the other changes then
  fails, the dialog closes without a message, as today; the new groups exist with the channel.
- **No "loaded" signal upstream.** `isDataLoaded` is a private primitive, so the fork infers it
  from the adapter notification. If upstream stops notifying, *New group* stays disabled: caught
  by device check 1, upstream behaviour unaffected.
- **Runtime replacement of listener and adapter** depends on the hook's position and on the ids
  `groups_recycler_view`, `save_button`, `cancel_button`. A renamed id is a compile error; a
  moved hook line is caught by device check 3.
- **`allGroups` is mutated off the main thread** (`FGSD:104-105` under
  `subscribeOn(Schedulers.io())`, `:134`) and holds the names as loaded when the dialog opened.
  The fork reads it only after the load notification. If the load failed (`FGSD:152-156`) it can
  be empty and a duplicate can pass.
- **Rotation while the transaction runs.** The groups are still created (OK was pressed); the
  callback is dropped, so the other checkbox changes are not saved, as upstream drops its own
  in-flight saves (`FGSD:226-230`).
- **`TextInputLayout` under the default alert-dialog theme** has an upstream precedent only under
  the AppCompat dialog themes (`res/values/styles.xml:98-122`): device check 6.

## 11. Device checks

1. Subscribed channel, add-to-group: *New group* shown and enabled, with zero and with several
   groups.
2. *New group*: field focused, keyboard up. Empty, spaces, an existing name in other case, a
   pending group's name: error, prompt stays. Keyboard done adds. Cancel, Back, touch outside
   add nothing.
3. Add two names, uncheck the first, toggle an existing group, OK: only the second exists, holds
   the channel, has the asterisk icon, is last in Subscriptions and can be edited and deleted
   there; the existing toggle is saved.
4. Add a name, then Cancel / Back / touch outside / rotate (also with the prompt open): no group
   is created.
5. Subscriptions tab, *New*: a name already in use is still accepted.
6. Light, dark and black theme: prompt and its error text are readable.
