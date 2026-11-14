# Plan: group-sort

Implements `spec.md` AC-1..AC-12. Java/Kotlin paths are relative to
`app/src/main/java/org/schabi/newpipe/`, `res/...` to `app/src/main/res/`. Line numbers are
those of this branch (upstream files are unmodified on it).

## 1. Design in short

Every group list reads one stream, `FeedDatabaseManager.groups()`. It is wrapped once (H1):
combined with the setting and sorted in memory. The feature never writes to the database, so
`sort_order` (the manual order) cannot change through sorting. The reorder dialog reads the raw
DAO stream instead (H2) and gets a notice (H3). The setting is one line in the Appearance XML
(H4). **4 upstream files touched, one line each** (2 replaced, 2 added); all else is new files.

## 2. Where groups are listed

`FeedGroupDAO.getAll()` (`database/feed/dao/FeedGroupDAO.kt:17-18`, `ORDER BY sort_order ASC`)
is called only by `FeedDatabaseManager.groups()` (`local/feed/FeedDatabaseManager.kt:39`), which
has exactly three callers (`git grep -n "\.groups()"`, `git grep -n "feedGroupDAO()"`):

| # | Consumer of `groups()` | Shown as | Must be | Achieved by |
|---|---|---|---|---|
| 1 | `local/subscription/SubscriptionViewModel.kt:72-79` -> `feedGroupsLiveData` -> `SubscriptionFragment.kt:259`, `handleFeedGroups` `:398-408` | Channel groups section | sorted | H1; file untouched |
| 2 | the same LiveData -> `settings/SelectChannelGroupFragment.java:79-81`, `handleFeedGroups` `:146-160` (opened by `settings/tabs/ChooseTabsFragment.java:207-211`) | group picker for a main-page tab | sorted | H1; file untouched |
| 3 | `local/subscription/dialog/FeedGroupSelectionDialog.java:100-101` (opened by `fragments/list/channel/ChannelVideosFragment.java:758`) | "Select groups" dialog | sorted | H1; file untouched |
| 4 | `local/subscription/dialog/FeedGroupReorderDialogViewModel.kt:23-26` -> `FeedGroupReorderDialog.kt:58`, `handleGroups` `:85-96` | reorder dialog | manual | H2 (+ H3 notice) |

Not group lists, no change: `FeedGroupDialog` loads one group by id
(`FeedDatabaseManager.kt:160-164`); `SubscriptionListFragment.kt:292-305` reads a group's
subscription ids; `settings/tabs/Tab.java:657` (`ChannelGroupTab`) stores one id and name.
A dialog that replaces #3 and still calls `groups()` (branch `group-create-inline`) is sorted
by H1 with no further hook.

## 3. Hook points

Hooks use the fully qualified name, so no `import` line is added. `P` stands for
`org.schabi.newpipe.enhancements.groupsort`; the real line spells it out.

| | File:line | Original statement | Line after the change |
|---|---|---|---|
| H1 | `local/feed/FeedDatabaseManager.kt:39` | `fun groups() = feedGroupTable.getAll()` (replaced) | `fun groups() = P.GroupSortHooks.sortedGroups(feedGroupTable.getAll()) // ENH:group-sort` |
| H2 | `local/subscription/dialog/FeedGroupReorderDialogViewModel.kt:23` | `private var groupsDisposable = feedDatabaseManager.groups()` (replaced; `.take(1)...` on `:24-26` stay) | `private var groupsDisposable = P.GroupSortHooks.manualGroups(feedDatabaseManager) // ENH:group-sort` |
| H3 | `local/subscription/dialog/FeedGroupReorderDialog.kt`, new line after `:55` (`_binding = DialogFeedGroupReorderBinding.bind(view)` in `onViewCreated`) | none changed | `P.GroupSortHooks.attachReorderNotice(view) // ENH:group-sort` |
| H4 | `res/xml/appearance_settings.xml`, new line after `:70` (end of the `grid_columns_landscape_key` entry, before the `main_page_content_key` entry that starts on `:72`) | none changed | the `<ListPreference ... /> <!-- ENH:group-sort -->` line of section 6 |

- H1 passes the DAO `Flowable` (manual order) and returns the same type,
  `Flowable<List<FeedGroupEntity>>`, so Java caller #3 compiles unchanged. No `Context` is
  passed because `FeedDatabaseManager` does not keep its constructor parameter (`:25-26`); the
  glue uses `App.getApp()` (`App.java:80`). Nothing runs at call time, only at subscription.
- H2 passes the manager; the glue returns `manager.database().feedGroupDAO().getAll()`
  (`FeedDatabaseManager.kt:41`), exactly the stream the dialog read before H1.
- **H1 and H2 go in the same commit.** With H1 alone the dialog would receive the name order,
  seed `groupOrderedIdList` with it (`FeedGroupReorderDialog.kt:88-90`), and "OK" would store it
  as the manual order (`:70-72` -> `FeedDatabaseManager.kt:178-185`), breaking AC-9.
- H3 passes the dialog root, the vertical `LinearLayout` of
  `res/layout/dialog_feed_group_reorder.xml:2-6`. The upstream layout is not edited.
- H4 is a declarative XML line, not a call, but still one marked line that `git grep "ENH:"`
  finds. A call-style hook in `AppearanceSettingsFragment` adding a second preference XML was
  rejected: the entry could only go last, the settings search would miss it (it parses the
  registered XML: `settings/SettingsResourceRegistry.java:34`,
  `settings/preferencesearch/PreferenceParser.java:115-134`) and `settings/NewPipeSettings.java:78`
  would not persist its default.
- Also rejected: hooks in #1 and #3 instead of H1+H2 (4 files too, but a replacement of #3
  loses sorting); no H2 and re-deriving the manual order from `sortOrder` inside the reorder
  dialog (3 files, but the order is reconstructed instead of read, and one line does two jobs).

## 4. New code: `app/src/main/java/org/schabi/newpipe/enhancements/groupsort/`

One file per type, named after it. The first three are what the JVM tests call.

```kotlin
enum class GroupSortMode(val preferenceValue: String) {       // pure Kotlin
    MANUAL("manual"), NAME_ASC("name_asc"), NAME_DESC("name_desc");
    val sortsByName: Boolean                      // MANUAL -> false; NAME_ASC, NAME_DESC -> true
    companion object {
        val DEFAULT: GroupSortMode                // MANUAL
        fun fromPreferenceValue(value: String?): GroupSortMode
    }
}
object GroupSorter {                                          // pure Kotlin
    fun compareNames(a: String, b: String): Int   // section 5; negative, 0 or positive
    fun <T> sort(groups: List<T>, mode: GroupSortMode, nameOf: (T) -> String): List<T>
}
object GroupSortStreams {                                     // Kotlin + RxJava 3 Flowable, no Android
    fun <T : Any> sorted(groups: Flowable<List<T>>, modes: Flowable<GroupSortMode>, nameOf: (T) -> String): Flowable<List<T>>
}
object GroupSortPreference {                                  // Android glue
    fun current(context: Context): GroupSortMode
    fun modes(context: Context): Flowable<GroupSortMode>
}
object GroupSortHooks {                                       // Android glue; all that upstream references
    fun sortedGroups(manualOrder: Flowable<List<FeedGroupEntity>>): Flowable<List<FeedGroupEntity>>
    fun manualGroups(manager: FeedDatabaseManager): Flowable<List<FeedGroupEntity>>
    fun attachReorderNotice(dialogRoot: View)
}
```

- `fromPreferenceValue`: exact, case-sensitive match on `preferenceValue`; `null`, `""` and any
  other string (`"MANUAL"`, `" manual"`, `"az"`) give `MANUAL`. Never throws.
- `GroupSorter.sort`: `MANUAL` returns `groups` itself (same instance). `NAME_ASC` returns a new
  list, stable-sorted with `compareNames(nameOf(x), nameOf(y))`. `NAME_DESC` returns a new list,
  stable-sorted with the reversed comparison `compareNames(nameOf(y), nameOf(x))`; it is **not**
  the reversed A-Z list, so ties keep their input order in both directions (AC-6). The input is
  never mutated; the result holds exactly the input elements (`sortedWith` is stable).
- `GroupSortStreams.sorted` = `Flowable.combineLatest(groups, modes.distinctUntilChanged())
  { list, mode -> GroupSorter.sort(list, mode, nameOf) }`, no scheduler. So: nothing is emitted
  until both sources have emitted; then one emission per `groups` item and one per *changed*
  mode, each the latest list sorted by the latest mode; errors pass through.
- `GroupSortPreference.current`: `fromPreferenceValue` of the string stored under
  `R.string.enh_group_sort_key` in `PreferenceManager.getDefaultSharedPreferences`; a missing
  value or one of another type (read inside `runCatching`) gives `MANUAL`.
- `GroupSortPreference.modes`: `Flowable.create(..., BackpressureStrategy.LATEST)` on a
  serialized emitter. Emits `current()` on subscription; an `OnSharedPreferenceChangeListener`
  emits `current()` when the changed key is ours or `null` (API 30+ reports `clear()` so) and
  is unregistered in `setCancellable`.
- `sortedGroups(src)` = `GroupSortStreams.sorted(src, GroupSortPreference.modes(App.getApp()))
  { it.name }`.
- `attachReorderNotice(root)`: if `GroupSortPreference.current(root.context).sortsByName` and
  `root` is a `ViewGroup`, inflate `R.layout.enhancements_group_sort_notice` and
  `addView(notice, 0)` (above the list); otherwise nothing. Rotation creates a new root and
  calls the hook again, so there is always exactly one notice.

## 5. Comparison algorithm (AC-5, AC-6)

```
compareNames(a, b):
    x = a.trim(); y = b.trim()        // Kotlin trim(): strips leading/trailing Char.isWhitespace()
    for i in 0 until min(x.length, y.length):
        cx = fold(x[i]); cy = fold(y[i])
        if cx != cy: return cx.code - cy.code
    return x.length - y.length
fold(c) = Character.toLowerCase(Character.toUpperCase(c))    // per UTF-16 unit, no Locale
```

It is this loop, not `compareTo(ignoreCase = true)`: the JDK compares characters outside the
BMP by code point there, and the test JVM and the device must agree. Consequences:

- The first differing character decides, by code after folding: space <
  `! " # $ % & ' ( ) * + , - . /` < `0-9` < `: ; < = > ? @ [ \ ] ^ _` and backtick < `a-z`
  (= `A-Z`) < `{ | } ~` < every non-ASCII character by code (`Ä` = `ä` = U+00E4, after "z").
- Digits are characters: "Mix 10" < "Mix 2". A prefix comes first: "Mix" < "Mix 2".
- "Music", "music" and " MUSIC " compare as 0: a tie, resolved by AC-6.

Fixtures (computed by running this algorithm on a JDK):

| | Input in manual order | A-Z | Z-A |
|---|---|---|---|
| F1 | "ZZZ - Last", "music", "Mix 2", "001 - First", "ZZZZ - Last of last", "Mix 10" | "001 - First", "Mix 10", "Mix 2", "music", "ZZZ - Last", "ZZZZ - Last of last" | exact reverse of A-Z |
| F2 | "b", "Music", "a", "music", " MUSIC " | "a", "b", "Music", "music", " MUSIC " | "Music", "music", " MUSIC ", "b", "a" |
| F3 | any shuffle of the A-Z column | "Mix", "music", "Zebra", "zebra 2", "Äpfel", "ärger", "日本" | exact reverse |
| F4 (assumes Q4) | any shuffle of the A-Z column | "- Top", "001", ":x", "[old]", "_misc", "A Team", "A-Team", "music", "{x}", "~last", "Äpfel" | exact reverse |

## 6. New resources

`res/values/enhancements_group_sort.xml` (nothing goes into `strings.xml` or
`settings_keys.xml`; no `values-xx/` copy is created):

| Name | Kind | Value |
|---|---|---|
| `enh_group_sort_key` | string, `translatable="false"` | `enh_group_sort` (the preference key) |
| `enh_group_sort_title` | string | Channel group order |
| `enh_group_sort_manual` | string | Manual |
| `enh_group_sort_name_asc` | string | Name (A-Z) |
| `enh_group_sort_name_desc` | string | Name (Z-A) |
| `enh_group_sort_reorder_notice` | string | Groups are currently sorted by name, so this manual order is not in effect. It applies again when Channel group order in the Appearance settings is set to Manual. |
| `enh_group_sort_entries` | string-array | `@string/enh_group_sort_manual`, `@string/enh_group_sort_name_asc`, `@string/enh_group_sort_name_desc` |
| `enh_group_sort_values` | string-array, `translatable="false"` | `manual`, `name_asc`, `name_desc` (same order as the entries and as `GroupSortMode`) |

`res/layout/enhancements_group_sort_notice.xml`: one `TextView` root with
`android:id="@+id/enh_group_sort_notice"`, `match_parent` x `wrap_content`, padding 16dp
left/right, 12dp top, 6dp bottom, `android:text="@string/enh_group_sort_reorder_notice"`,
`android:textAppearance="?android:attr/textAppearanceSmall"`.

H4, one line (the `app:` namespace is declared on `appearance_settings.xml:3`):

```xml
    <ListPreference android:key="@string/enh_group_sort_key" android:title="@string/enh_group_sort_title" android:summary="%s" android:entries="@array/enh_group_sort_entries" android:entryValues="@array/enh_group_sort_values" android:defaultValue="manual" app:singleLineTitle="false" app:iconSpaceReserved="false" /> <!-- ENH:group-sort -->
```

Stored value: a `String` under key `enh_group_sort`, default `manual`. `android:summary="%s"`
shows the selected entry, as other entries of the file do (`:11`, `:56`, `:67`). No Java change.

## 7. Reactivity (AC-8)

- **Group created, renamed, deleted, reordered.** `getAll()` is a Room `Flowable` that emits
  again after any write to `feed_group`. Consumer 1 stays subscribed for the life of its
  ViewModel (`SubscriptionViewModel.kt:72-79`, disposed `:93`), so each new list passes through
  `GroupSortStreams.sorted` and reaches `feedGroupsSection.update` (`SubscriptionFragment.kt:399`)
  sorted. A rename keeps `sort_order` (`FeedGroupDialog.kt:307,315`) and a new group gets
  `MAX(sort_order)+1` (`FeedGroupDAO.kt:24-28,62-63`), so the manual order stays intact.
- **Setting changed.** `modes` emits the new mode and `combineLatest` re-sorts the latest list
  without a database write. Consumer 1's ViewModel outlives the visit to the settings activity;
  its LiveData delivers the re-sorted list when the Subscriptions tab is started again.
  Consumers 2-4 subscribe afresh each time they open (#2 creates its own `SubscriptionViewModel`,
  `SelectChannelGroupFragment.java:79-80`; #3 `firstOrError()`; #4 `take(1)`). No restart.

## 8. Settings export/import (AC-11)

Confirmed in the code: no change needed. Export writes `preferences.all` of the default shared
preferences; import clears them and writes back every entry, strings included.

- `settings/ContentSettingsManager.kt`: `exportDatabase` `:31-52` (legacy file `:38-41`,
  `preferences.json` `:49-50`), `exportSettingsOnly` `:56-59`, `importSettingsOnly` `:63-65`,
  `loadSharedPreferences` `:107-117`, `applyJson` `:125-145` (`is String` `:136`), `applyLegacy`
  `:149-171` (`is String` `:161`).
- All callers pass the default shared preferences: `settings/BackupSettingsFragment.java:295`
  and `:318` (`defaultPreferences`, `BasePreferenceFragment.java:27`), `:363-367`, `:407-409`;
  `settings/ScheduledBackupWorker.kt:34,53`. The app restarts after an import (`:430`).
- Since the entry is in `appearance_settings.xml`, `NewPipeSettings.java:78`
  (`setDefaultValues(..., true)`) stores `manual` at the next start, so the key is in every
  export. A backup without the key, or with an unknown value, reads as `MANUAL`.

## 9. AC coverage

JVM tests go in `app/src/test/java/org/schabi/newpipe/enhancements/groupsort/` and use JUnit 4,
the JDK and RxJava (`PublishProcessor`, `Flowable.test()`) only. They must not reference
`GroupSortPreference` or `GroupSortHooks` (Android classes throw on the JVM here) and use
`String` or a local `data class` as `T`, not `FeedGroupEntity`. "Resources" and "wiring" tests
read files as XML/text; Gradle runs unit tests with `app/` as working directory (resolve
`src/main/...`, fall back to `app/src/main/...`).

| AC | Implemented by | JVM unit test | Device check (why not a unit test) |
|---|---|---|---|
| AC-1 | H4 line, values file, `GroupSortMode` | `GroupSortMode`: three members, their `preferenceValue`s, `DEFAULT`, `fromPreferenceValue` incl. `null`/unknown. Resources: `appearance_settings.xml` has one entry with the key, `summary="%s"`, `defaultValue="manual"` and both arrays; `enh_group_sort_values` equals `GroupSortMode.values().map { it.preferenceValue }`; title and the three labels have the texts of section 6 | D1 (a preference screen needs the framework) |
| AC-2 | `sort(.., MANUAL, ..)` returns its input | `GroupSorter.sort` returns the same instance; `GroupSortStreams.sorted` with `MANUAL` emits the source lists unchanged | D2 |
| AC-3 | H1 + `sort(.., NAME_ASC, ..)` | `GroupSorter.sort` on F1-F3. Wiring: `FeedDatabaseManager.kt` has exactly one `ENH:group-sort` line and it contains `GroupSortHooks.sortedGroups(feedGroupTable.getAll())` | D2 (the lists are Fragments) |
| AC-4 | H1 + `sort(.., NAME_DESC, ..)` | `GroupSorter.sort` on F1-F3 | D2 |
| AC-5 | `GroupSorter.compareNames` | the AC-5 chain pairwise and as a sort; case, trim, prefix, digits as characters, "Ä" after "Z"; `compareNames(a,b)` and `(b,a)` have opposite signs; F4 in separately named tests (Q4) | none needed |
| AC-6 | stable sort; reversed comparator for Z-A | F2 in both directions, elements told apart by an id | none needed |
| AC-7 | no new code: only database groups reach the sorter. "All" and "New" are separate adapter items around the section (`SubscriptionFragment.kt:183-185`); the picker adds "All" before the groups (`SelectChannelGroupFragment.java:150-152`) | `GroupSorter.sort` returns exactly the input elements (same size, same multiset) in every mode | D2 (placement is adapter wiring in a Fragment) |
| AC-8 | Room re-emission, `GroupSortPreference.modes`, `GroupSortStreams.sorted` | `sorted`: a new `groups` item is emitted sorted; a mode change re-emits the last list re-sorted without a new `groups` item; a repeated mode emits nothing; nothing before both sources emitted | D3 (preference listener and LiveData need the framework) |
| AC-9 | no writes anywhere; H2 | `sort` leaves the input equal to a copy taken before and accepts an unmodifiable list; `sorted`: `NAME_ASC` then `MANUAL` emits the original order. Wiring: `FeedGroupReorderDialogViewModel.kt` contains `GroupSortHooks.manualGroups(feedDatabaseManager)` and no `feedDatabaseManager.groups()` | D4 |
| AC-10 | H2, H3, `sortsByName`, notice string and layout | `sortsByName` for the three modes. Resources: notice string and layout file exist. Wiring: `FeedGroupReorderDialog.kt` has one `ENH:group-sort` line containing `GroupSortHooks.attachReorderNotice(view)` | D4 (a dialog view needs the framework) |
| AC-11 | no new code (section 8); the value is a `String` in the default shared preferences | `fromPreferenceValue(mode.preferenceValue) == mode` for every mode, unknown gives `MANUAL`; the AC-1 resources test shows the key is declared in `appearance_settings.xml` | D5 (export needs `StoredFileHelper` (Uri, Context) and a real `SharedPreferences`; there is no Robolectric) |
| AC-12 | all texts in `res/values/enhancements_group_sort.xml` | Resources: the five visible strings exist without `translatable="false"`; key string and values array have it; entries are `@string/` references; no `enh_group_sort` name in `values/strings.xml` or `values/settings_keys.xml` | D6 |

Device checks (debug APK of `enhancements/integration`):

- D1 Settings > Appearance shows "Channel group order" after the grid-column entries; its
  summary is the selected value and follows it; the settings search finds the entry.
- D2 Create groups in the manual order of F1. For Manual, A-Z and Z-A compare the three lists
  (Subscriptions tab, "Select groups" on a subscribed channel, the channel-group picker when
  adding a main-page tab); "All" stays first and "New" last in the tab, "All" first in the picker.
- D3 With A-Z: a created or renamed group appears at its sorted place at once; after changing
  the setting and going back, the tab is reordered without a restart.
- D4 With A-Z: the reorder dialog shows the notice and the manual order; drag, OK: the tab is
  still A-Z; switch to Manual: the order just set. With Manual: no notice.
- D5 Select Z-A, export (settings-only JSON and full zip), select Manual, import: Z-A is back.
- D6 With the device language set to German the new texts appear in English.

## 10. Order of work (Code layer)

1. `GroupSortMode.kt`, `GroupSorter.kt` (mode and sorter tests green).
2. `GroupSortStreams.kt` (stream tests green).
3. The values and layout files, then H4 (resources tests green).
4. `GroupSortPreference.kt`, `GroupSortHooks.kt`.
5. H1 and H2 together, then H3 (wiring tests green).
6. `./gradlew :app:testDebugUnitTest -DskipFormatKtlint`; `git diff dev -- . ':!enhancements'
   ':!app/src/test'` must show new files plus exactly the four hook lines; then D1-D6.

## 11. Assumptions and open question

- **Q4 (open; AC-5, and through it AC-3/AC-4):** the spec does not place spaces and symbols.
  Planned with plain character-code order after lower-case folding (section 5). A different
  answer changes only `fold`/`compareNames` and fixture F4; no hook, signature or resource.
- AC-6 "equal names" is read as "equal under AC-5": names that differ only in case or
  surrounding whitespace are ties. This follows from AC-5 and AC-6 taken together.
- AC-8 "the next time they are shown": the Subscriptions tab is re-sorted as soon as the
  setting changes, while it is not visible, which meets the wording.

## 12. Risks and edge cases

- H1 changes `groups()` for every caller, also ones upstream adds later. After each rebase
  re-run `git grep -n "\.groups()"` and compare with section 2. The wiring tests fail if a
  rebase drops a hook, in particular H2 (without it AC-9 breaks silently, section 3).
- `SharedPreferences` holds listeners weakly: the listener must be referenced from the emitter's
  `setCancellable` closure, or it is collected and setting changes are missed.
- A setting change is emitted on the main thread, so that one re-sort runs there (an in-memory
  sort of a few dozen names). Database-driven emissions stay on Room's thread.
- Rows with equal `sort_order` have no defined SQL order today; unchanged by this feature.
- Names are trimmed when saved (`FeedGroupDialog.kt:288`); only an imported database can hold
  untrimmed ones, and `compareNames` trims them.
- A stored value outside the three (hand-edited backup) sorts as Manual; the preference then
  shows an empty summary until a value is picked.
- Lint flags the new strings as untranslated; the build stays green (`abortOnError false`,
  `app/build.gradle:92-95`) and Android falls back to `values/` at run time (AC-12).
