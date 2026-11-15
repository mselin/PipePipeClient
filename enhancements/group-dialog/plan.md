# Plan: group-dialog

Implements `spec.md` AC-1..AC-8. Replaces the code of group-create-inline. Code paths are
relative to `app/src/main/java/org/schabi/newpipe/`, resource paths to `app/src/main/`. Line
numbers are those of commit `c14d59f`. `FGSD` =
`local/subscription/dialog/FeedGroupSelectionDialog.java`, `FDM` =
`local/feed/FeedDatabaseManager.kt`, `DAO` = `database/feed/dao/FeedGroupDAO.kt`.

Precondition: this branch forks from `enhancements/base` at `ec77fd5`. `FINDINGS.md` and the
PROCESS paragraph on marked defect fixes are in base commits `4fb9f0d` and `849b72c`. Rebase onto
base before the code commit. `check-layers.sh` is identical in both.

## 1. D2: confirmed

Read in the code and reproduced against the project's RxJava jar (3.1.12, `app/build.gradle:273`).

- `FGSD:188` and `:202` end every step with `.toSingle(() -> (Void) null)`. RxJava 3 does not
  allow null values: when the Completable completes, `toSingle` calls `onError(
  NullPointerException("The value supplied is null"))`. The write of that step
  (`FDM:147-151`) has already run by then. A three-step `Single.zip` of such steps, run
  synchronously, printed `[write0, error:java.lang.NullPointerException: ...]`: one write, then
  the error, the other two never ran.
- `FGSD:213` zips the steps. `Single.zip` disposes all other sources on the first error. So with
  N >= 2 toggled groups the step that finishes first keeps its write, and each other step keeps
  its write only if that write had already started. A step still waiting for its member list
  (read on Room's executor, delivered on the main thread, `FDM:141-145`) or for an io thread is
  cancelled: **that group's add or remove is lost**. Which ones is a race; it was not measured.
- `FGSD:216-221`: the error path only dismisses, so the loss, and any real database error, is
  invisible. With exactly one toggled group the write survives and the dialog closes through the
  error path. With none it closes at `:207-210`.
- AC-7, same code: each step reads the member list, then `DAO:48-52` deletes all members of the
  group and inserts the list read earlier, in a later transaction. A member change in between is
  overwritten.
- group-create-inline hands off to this save (`groupcreateinline/GroupCreateInline.kt:122`), so
  its "other checkbox changes" are lost the same way.

## 2. Assumptions

| # | Assumption | ACs |
|---|---|---|
| S1 | OK saves the differences to the state loaded when the dialog opened: ticked and not a member then (add), member then and unticked (remove), checked pending groups (create). No difference: the dialog closes without a write, as today (`FGSD:207-210`). Adds and removes are idempotent, so a stale loaded state cannot produce a wrong row. | AC-2, AC-3 |
| S2 | After a failed load OK closes the dialog without saving (nothing is known, nothing can be ticked). Q2. | AC-6 |
| S3 | Rotation during the save: the transaction is not cancelled, so everything is saved or nothing; only the answer to the old dialog is dropped. Q3. | AC-2, F12 |
| S4 | A group deleted elsewhere while the dialog is open and then ticked here makes the whole save fail (foreign key at commit): AC-4 applies. | AC-4 |
| S5 | S2 (button disabled until loaded), S3 (pending rows after existing ones) and S5 (rotation discards) of group-create-inline's plan still hold. | AC-1 |

## 3. Shape

- The dialog is edited directly. `FGSD` owns the views, builds the session, tells it when the
  load ended, renders pending rows in its own adapter and implements the ui interface.
- All decisions stay plain Kotlin in one package, **`org.schabi.newpipe.enhancements.groupdialog`**
  (feature name without hyphens, as PROCESS requires for the test path).
- The package `enhancements.groupcreateinline` is removed, main and test. Its spec, plan and
  questions stay as history; `spec.md` AC-1 refers to them.

| Existing file (`enhancements/groupcreateinline/`) | Becomes |
|---|---|
| `GroupNameRules.kt` | moved to `groupdialog/`, only the package line changes |
| `GroupCreateSession.kt` | replaced by `groupdialog/GroupDialogSession.kt` (section 4) |
| `GroupWriter.kt` | replaced by `groupdialog/GroupChangeWriter.kt` (section 4) |
| `RoomGroupTable.kt` | moved, `setMembers` replaced by `addMember` / `removeMember` (section 6) |
| `RxGroupSaver.kt` | replaced by `groupdialog/RxChangeSaver.kt` (section 5) |
| `NamePrompt.kt` | moved; package and resource names change, nothing else |
| `GroupCreateInline.kt`, `PendingGroupsAdapter.kt` | deleted: the take-over and the second adapter (AC-8) |
| `res/values/enhancements_group_create_inline.xml` | replaced by `res/values/enhancements_group_dialog.xml` |
| `res/layout/enhancements_group_create_inline_name_prompt.xml` | renamed `enhancements_group_dialog_name_prompt.xml`; ids `enh_group_dialog_name_container`, `enh_group_dialog_name_input` |
| `res/layout/enhancements_group_create_inline_new_group.xml` | deleted; the button is in `enhancements_group_dialog_extras.xml` |
| hook line `FGSD:76` (`ENH:group-create-inline`) | changed into `setupSession(view); // ENH:group-dialog` |

Strings in `res/values/enhancements_group_dialog.xml` (translatable, English only):
`enh_group_dialog_new_group` "New group", `enh_group_dialog_add` "Add",
`enh_group_dialog_name_exists` "A group with this name already exists",
`enh_group_dialog_save_failed` "Could not save. Nothing was changed.",
`enh_group_dialog_load_failed` "Could not load the groups."

New layout `res/layout/enhancements_group_dialog_extras.xml`: a `<merge>` with a `TextView`
`enh_group_dialog_load_error` (`android:visibility="gone"`, text
`@string/enh_group_dialog_load_failed`) and a borderless `Button`
`enh_group_dialog_new_group_button` (`android:enabled="false"`, text
`@string/enh_group_dialog_new_group`).

### Upstream files

| File | Edit | Lines |
|---|---|---|
| `res/layout/dialog_feed_group_selection.xml` | after the RecyclerView (`:21`): `<include layout="@layout/enhancements_group_dialog_extras" /> <!-- ENH:group-dialog -->` | +1 |
| `DAO` | after `:43`: `@Query("DELETE FROM feed_group_subscription_join WHERE group_id = :groupId AND subscription_id = :subscriptionId") // ENH:group-dialog` and `abstract fun deleteSubscriptionFromGroup(groupId: Long, subscriptionId: Long): Int // ENH:group-dialog`. No schema change. | +2 |
| `FGSD` | E1..E9 below | about +88, -62 |

`FGSD` edits; every added or changed line ends with `// ENH:group-dialog`, closing braces
included, and no blank line is added (the check counts every `+` line):

- **E1** imports of the `groupdialog` classes, `NewPipeDatabase`, `Toast`; the class line
  becomes `... extends DialogFragment implements SelectionUi`.
- **E2** fields: `session`, `saver`, `pending` (`List<PendingGroup>`, empty), `busy`,
  `recyclerView`, `saveButton`, `cancelButton`, `newGroupButton`, `loadError`, `namePrompt`.
- **E3** `onCreateDialog`: `:76` becomes `setupSession(view);`. New `setupSession(View)`:
  `saver = new RxChangeSaver(new RoomGroupTable(NewPipeDatabase.getInstance(requireContext())))`,
  `session = new GroupDialogSession(subscriptionId, saver, this)`, finds `loadError` and
  `newGroupButton`, whose click opens `new NamePrompt(requireContext(), session::submitName)`.
- **E4** `setupButtons` (`:92-93`) and `setupRecyclerView` (`:85`) assign the fields instead of
  locals. The listeners at `:95-96` stay.
- **E5** load: success (`:150-151`) additionally collects the names of `allGroups` and calls
  `session.onLoaded(names)`; failure (`:155-156`) additionally calls `session.onLoadFailed()`.
- **E6** `saveChanges`: the guard `:164-166` stays; `:168-224` (57 lines) is replaced by
  `session.confirm(selectedGroupIds, originalGroupIds);`. No `toSingle`, no zip of writes.
- **E7** `SelectionUi` methods: `showPending` (store the list, `adapter.notifyDataSetChanged()`,
  scroll to the last row if not empty); `showLoaded` (enable `newGroupButton`); `showLoadError`
  (`recyclerView` gone, `loadError` visible); `setBusy` (`busy` field, `setEnabled(!busy)` on the
  three buttons, `setCancelable(!busy)`, `adapter.notifyDataSetChanged()`); `showSaveError`
  (long `Toast`, `enh_group_dialog_save_failed`); `close` (`dismissAllowingStateLoss()`).
- **E8** adapter: `getItemCount` (`:263`) returns `allGroups.size() + pending.size()`;
  `onBindViewHolder` binds `pending.get(position - allGroups.size())` for the rows after the
  groups. `GroupViewHolder` gets `bindPending(PendingGroup)`: name, checkbox state with the
  listener detached, then a listener calling `session.setChecked(id, isChecked)`, row click
  toggles. Both `bind` and `bindPending` set `itemView` and `checkBox` enabled to `!busy`.
- **E9** `onDestroy`: dismiss an open `namePrompt`, `saver.detach()`.

Not touched: `loadDataSynchronously` apart from E5, the selection sets, `newInstance`,
`fragments/list/channel/ChannelVideosFragment.java:758`.

### Layer check

No rule change is needed. `check-layers.sh` tests each code commit per upstream file: every `+`
line carries `ENH:group-dialog`, and removed <= added. `FGSD` loses about 62 lines (57 of the
old save, 5 changed lines) and gains about 88, because the wiring that was in
`GroupCreateInline.kt` and `PendingGroupsAdapter.kt` (191 lines) now lives in the dialog. So:
**all `FGSD` edits go into one code commit**; a commit that only removes the old save would fail.
If the real count ends below the removed lines, Code does not pad: it asks in `questions.md`.
The deleted `groupcreateinline` files are not upstream files, so the check does not look at them.

## 4. Decision logic (plain Kotlin, tested on the JVM)

No import from `android.*`, `androidx.*`, `io.reactivex.*` or `org.schabi.newpipe.*` outside the
package. Synchronous, main-thread-confined.

`GroupNameRules.kt`: unchanged (`NameCheck`, `clean`, `sameName`, `check`).

`GroupDialogSession.kt`:

```kotlin
data class PendingGroup(val id: Long, val name: String, val checked: Boolean)

data class GroupChanges(
    val subscriptionId: Long,
    val newGroupNames: List<String>,      // checked pending groups, in the order added
    val addToGroupIds: List<Long>,        // ascending
    val removeFromGroupIds: List<Long>    // ascending
) { val isEmpty: Boolean }                // all three lists empty

fun interface SaveCallback { fun onResult(error: Throwable?) }   // null = success

interface ChangeSaver {
    /** Saves all of [changes] or nothing. Calls [callback] at most once, now or later. */
    fun save(changes: GroupChanges, callback: SaveCallback)
}

interface SelectionUi {
    fun showPending(pending: List<PendingGroup>)
    fun showLoaded()                 // groups are loaded: New group becomes usable
    fun showLoadError()              // AC-6
    fun setBusy(busy: Boolean)       // AC-5
    fun showSaveError()              // AC-4
    fun close()
}

class GroupDialogSession(
    private val subscriptionId: Long,
    private val saver: ChangeSaver,
    private val ui: SelectionUi
) {
    val pending: List<PendingGroup>   // read-only snapshot, in the order added
    val isBusy: Boolean
    fun onLoaded(existingNames: List<String>)
    fun onLoadFailed()
    fun submitName(raw: String): NameCheck
    fun setChecked(id: Long, checked: Boolean)
    fun confirm(tickedGroupIds: Set<Long>, loadedGroupIds: Set<Long>)
}
```

Contract (tests assert exactly this, including the order of `ui` calls):

- Load state starts as loading. `onLoaded(names)`: state loaded, keeps a copy of `names`,
  `ui.showLoaded()` once. `onLoadFailed()`: state failed, `ui.showLoadError()` once; `showLoaded`
  is never called. Each is called at most once, by the dialog.
- `submitName(raw)`: as in group-create-inline's plan section 4, with the names of `onLoaded`
  in place of `existingNames()`. `setChecked`: unchanged. Both are unreachable while loading,
  failed or busy (button and rows disabled, prompt modal): unspecified, not tested.
- `confirm(ticked, loaded)`:
  1. busy or still loading: return, nothing happens.
  2. load failed: `ui.close()`, return (S2).
  3. `changes = GroupChanges(subscriptionId, names of the checked pending groups in the order
     added, (ticked - loaded) sorted, (loaded - ticked) sorted)`.
  4. `changes.isEmpty`: `ui.close()`, return. No `saver`, no `setBusy`.
  5. `isBusy = true`, `ui.setBusy(true)`, `saver.save(changes, cb)`. The callback may arrive
     inside that call.
  6. `cb(null)`: `isBusy = false`, then `ui.close()` only.
  7. `cb(error)`: `isBusy = false`, pending unchanged; then `ui.setBusy(false)`,
     `ui.showSaveError()`. No `close`. A later `confirm` builds new changes from its arguments
     and the pending groups of that moment.

`GroupChangeWriter.kt`:

```kotlin
interface GroupTable {
    fun inTransaction(block: () -> Unit)                 // commits; rolls back if block throws
    fun subscriptionExists(subscriptionId: Long): Boolean
    fun insertGroup(name: String): Long                  // default icon, last in order; new id
    fun addMember(groupId: Long, subscriptionId: Long)      // no effect if already a member
    fun removeMember(groupId: Long, subscriptionId: Long)   // no effect if not a member
}

object GroupChangeWriter {
    fun apply(table: GroupTable, changes: GroupChanges)
}
```

Contract: `changes.isEmpty`: return without touching `table`. Otherwise exactly one
`table.inTransaction { ... }` with every other call inside it, in this order:
`subscriptionExists(subscriptionId)` once, if false throw `IllegalStateException` before any
write; for each new name in order `id = insertGroup(name)` immediately followed by
`addMember(id, subscriptionId)`; for each id of `addToGroupIds` `addMember(id, subscriptionId)`;
for each id of `removeFromGroupIds` `removeMember(id, subscriptionId)`. Nothing is caught.
`GroupTable` has no call that sets a member list, so the writer cannot rewrite one (AC-7).

## 5. Android glue outside the dialog

| File | Signature | Does |
|---|---|---|
| `RoomGroupTable.kt` | `class RoomGroupTable(database: AppDatabase) : GroupTable` | section 6 |
| `RxChangeSaver.kt` | `class RxChangeSaver(table: GroupTable) : ChangeSaver { fun detach() }` | `Completable.fromAction { GroupChangeWriter.apply(table, changes) }` on `Schedulers.io()`, answer on the main thread: `onResult(null)` or `onResult(e)`. The subscription is **not** disposed (disposing interrupts the io thread mid-transaction); `detach()` only makes later answers be dropped (S3). |
| `NamePrompt.kt` | `class NamePrompt(context: Context, onSubmit: (String) -> NameCheck) { fun show(); fun dismiss() }` | as today |

## 6. The save (AC-2, AC-3, AC-4, AC-7)

One `AppDatabase.runInTransaction` on an io thread, started by OK:

| `GroupTable` | Room call |
|---|---|
| `inTransaction` | `database.runInTransaction(Runnable)` |
| `subscriptionExists` | `subscriptionDAO().getSubscription(id) != null` (`database/subscription/SubscriptionDAO.kt:115`, blocking) |
| `insertGroup` | `feedGroupDAO().insert(FeedGroupEntity(0, name, FeedGroupIcon.ALL))` (`DAO:23-28`) |
| `addMember` | `feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(groupId, subscriptionId)))` (`DAO:45-46`, `OnConflictStrategy.IGNORE`) |
| `removeMember` | `feedGroupDAO().deleteSubscriptionFromGroup(groupId, subscriptionId)` (new, section 3) |

- AC-2: new groups, adds and removes are in the same transaction; the DAO's own `@Transaction`
  methods join it. An exception anywhere rolls all of it back.
- AC-3: there are no parallel steps left. Every toggled group gets exactly one insert or one
  delete of the row `(group, channel)`; untouched groups get no write.
- AC-7: only rows `(group_id, this subscription)` are inserted or deleted.
  `updateSubscriptionsForGroup` and `deleteSubscriptionsFromGroup(groupId)` are not called by
  this dialog any more, and no member list is read for writing.
- AC-4: an exception reaches `RxChangeSaver`, then `session` step 7 on the main thread: controls
  enabled again, toast, no `close()`. `FGSD` keeps `selectedGroupIds` and `pending` untouched, so
  the checkboxes and pending rows are as they were. The join table's foreign keys are deferred
  (`database/feed/model/FeedGroupSubscriptionEntity.kt:21`, `:28`), so a missing group or
  subscription fails at commit at the latest; the subscription check fails earlier.
- Invalidation: one transaction, one notification to the Subscriptions tab and open feeds
  instead of one per group (J2, J3).

## 7. Tests

The Tests layer owns the files. Because `test(group-dialog)` may write only
`.../enhancements/groupdialog/`, the old directory is removed in a separate
`test(group-create-inline)` commit. No Robolectric, no Mockito, no build file change.

| Existing (70) | Fate |
|---|---|
| `GroupNameRulesTest` (12) | apply unchanged; moved, package line only |
| `GroupCreateSessionTest`, 20 of 46: the `submitName` / `setChecked` tests (`ac3_*`, `ac4_*` except `existingNamesAreReadAtEverySubmit`, `ac5_*`, `ac7_threeNames...`, `ac9_setChecked*`, `ac9_ac10_unchecking...`, `ac10_*`, `ac15_sessionCreatedAfterAnother...`) | same assertions; only the set-up changes (new constructor, names given by `onLoaded`, `ui.forgetCalls()` after it). Now in `GroupDialogSessionTest`. |
| `GroupCreateSessionTest`, 26 of 46: every `confirm` test, `ac15_ac10_newSession...`, `ac15_confirmOnASession...`, `ac4_existingNamesAreReadAtEverySubmit`, `ac7_idsAreNotReusedAfterTheSavedGroupsWereCleared` | must change: there is no `runUpstreamSave`, the saver gets `GroupChanges`, success is `close()` only, names are not asked through a lambda, and after a success the dialog is closed. Rewritten against section 4. |
| `GroupWriterTest` (10) | must change: `setMembers` is gone. Rewritten as `GroupChangeWriterTest`. |
| `GroupCreateInlineResourcesTest` (2) | must change: file name, string names, five texts. Now `GroupDialogResourcesTest`. |
| `FakeUi`, `FakeSaver`, `FakeTable` | rewritten for the new interfaces. `FakeTable` stores groups with member lists, including other members; `addMember` / `removeMember` are idempotent; it can fail on the n-th write call and report the subscription missing. |

New JVM tests (names start with `ac<n>_`, the AC numbers of this spec):

- `GroupDialogSessionTest`: `onLoaded` gives `showLoaded` once; `onLoadFailed` gives only
  `showLoadError` and a later `confirm` only `close` (AC-6); `confirm` while loading does
  nothing; ticked/loaded sets give sorted add and remove lists, groups in both or neither are
  absent, 50 toggles are all present (AC-3); pending names, adds and removes arrive in one
  `save` call (AC-2); toggles without pending groups also go to the saver; no difference gives
  `close` only; unchecked pending groups are not in the changes (old AC-9); `setBusy(true)`
  precedes `save`, `isBusy` is true inside it, a second `confirm` while busy does nothing
  (AC-5); error gives `setBusy(false)`, `showSaveError`, no `close`, pending intact, and a retry
  sends the sets of the retry (AC-4); success gives `close` only.
- `GroupChangeWriterTest`: empty changes do not touch the table; one transaction, every call
  inside; the call order of section 4; missing subscription throws before any write; a failure
  at an insert, an add or a remove propagates and leaves the fake's stored state as before,
  earlier new groups included (AC-2, AC-4); after a mixed save the channel is a member of
  exactly the ticked groups (AC-3); other members of every touched group are unchanged (AC-7).
- `GroupDialogResourcesTest`: the five strings and their texts, none untranslatable (old AC-16);
  in `enhancements_group_dialog_extras.xml` the button has `android:enabled="false"` and the
  error view `android:visibility="gone"` (AC-6).
- `GroupDialogSourceTest`, reading source files as text (AC-8, D2): `FGSD` contains none of
  `groupcreateinline`, `ConcatAdapter`, `registerAdapterDataObserver`, `toSingle(`,
  `updateSubscriptionsForGroup`; `src/main/java/.../enhancements/groupcreateinline` does not
  exist; `dialog_feed_group_selection.xml` includes the extras layout once.

Instrumented, `app/src/androidTest/java/org/schabi/newpipe/enhancements/groupdialog/
RoomGroupTableTest.kt` (in-memory `AppDatabase`; existing `androidTest` dependencies suffice;
not run by CI, run in device check 7): a mixed save commits; a save that throws after writes
leaves the tables as before; removing leaves the other members of the group; adding to a group
id that does not exist fails at commit and saves nothing.

## 8. AC coverage

| AC | Implemented by | Verified by |
|---|---|---|
| AC-1 | sections 3-5; name rules, prompt and pending rows carried over | carried-over JVM tests; device 1-4 |
| AC-2 | `GroupChanges`, `GroupChangeWriter.apply`, `runInTransaction` | session and writer tests; `RoomGroupTableTest`; device 3 |
| AC-3 | `confirm` step 3, one write per toggled group | session and writer tests; device 5 (ten toggles) |
| AC-4 | `confirm` step 7, E7 `showSaveError`, rollback | session and writer tests; `RoomGroupTableTest`; device 6 |
| AC-5 | `confirm` steps 1 and 5, E7 `setBusy`, E8 | session tests; review of E7/E8 (the save takes milliseconds on a device) |
| AC-6 | `onLoadFailed`, E5, E7, extras layout | session and resources tests; review of E5 |
| AC-7 | `GroupTable` without a set-members call, section 6 | writer test; `RoomGroupTableTest`; `GroupDialogSourceTest` |
| AC-8 | section 3: edits E1..E9, deleted files | `GroupDialogSourceTest`; `git grep "ENH:group-create-inline" app/` is empty |

## 9. Order of work

1. `test(group-dialog)`: the tests of section 7 (they fail to compile: not implemented).
2. `test(group-create-inline)`: delete `app/src/test/.../groupcreateinline/`.
3. `code(group-dialog)`, **one commit**: resources; the `groupdialog` package; the two `DAO`
   lines; the layout include; `FGSD` E1..E9; delete main `groupcreateinline/` and its three
   resource files.
4. `check-layers.sh origin/dev`; `git grep "ENH:group-dialog"` lists only `FGSD`, two `DAO`
   lines and one layout line; unit tests green.
5. Device checks.

## 10. Device checks

1. Channel page, add-to-group: *New group* enabled, with zero and with several groups.
2. Name prompt: checks 2 and 6 of group-create-inline's plan.
3. Add two names, uncheck the first, tick one existing group, untick another, OK: one new group
   with the channel, asterisk icon, last in Subscriptions; both toggles saved; dialog closes.
4. Add a name, then Cancel / Back / touch outside / rotate: no group is created.
5. Ten groups: tick all, OK, reopen: all ten ticked. Untick all, OK, reopen: none. Repeat three
   times (this is the D2 case).
6. A group with 20 other channels: add and remove this channel; the other 20 stay.
7. Run `RoomGroupTableTest` on the device (covers the failure path, which cannot be provoked by
   hand).
8. Subscriptions tab, *New*: a name already in use is still accepted.

## 11. Risks

- **None of the `FGSD` wiring is covered by JVM tests** (F1): E5, E7, E8 are verified by review
  and device checks only.
- **Marker noise**: about 88 lines of `FGSD` end with `// ENH:group-dialog`, and a rebase conflict
  in this file is now resolved by hand. Upstream changed it once in 410 commits (F10).
- **New DAO method** changes Room's generated code for `FeedGroupDAO`, not the schema. A wrong
  SQL string is a compile error (Room verifies queries).
- **Load failure cannot be provoked on a device**; AC-6 rests on the session test and review.
- **Rotation during the save** (S3): the new dialog instance may load before the transaction
  commits and show the old ticks for that moment; the data is saved whole.
- **`close()` uses `dismissAllowingStateLoss()`**: upstream's `dismiss()` from an async callback
  throws if the app went to the background meanwhile.
- **group-sort** hooks the same dialog's group list (`FDM.groups()`); the pending rows still
  follow whatever order that source delivers. Expect a textual conflict in `FGSD` on integration
  if group-sort adds a line near E5.
