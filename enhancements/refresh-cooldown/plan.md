# Plan: refresh cooldown per group and per subscription

Spec: `spec.md` (AC-1..AC-12). Paths are relative to `app/src/main/java/org/schabi/newpipe/`
unless they start with `app/`, `res/` (= `app/src/main/res/`) or `enhancements/`. Line numbers
are those of this branch at `bc63c9c`. `P` = `org.schabi.newpipe.enhancements.refreshcooldown`;
in the source `P.` is written out (no import lines). `// ENH` below stands for
`// ENH:refresh-cooldown`. Depends on Q1..Q7 in `questions.md`; each states the assumption
used here. "Checked" = read in the code or in the compiled library classes. "Assumed" = not
checked.

## 1. What the code does today

| Spec, "Today" / AC | Holds? | What the code shows |
|---|---|---|
| A refresh skips every channel refreshed recently; one app-wide setting, default 5 minutes, seven choices | yes; the screen is named wrongly (Q1) | `local/feed/service/FeedLoadManager.kt:88-98`: the preference `feed_update_threshold_key` (default `300`, `res/values/settings_keys.xml:342-343`) becomes `outdatedThreshold` = now minus that many seconds. `:103-111` picks one of three queries by group id; each returns the subscriptions with `last_updated IS NULL OR last_updated < :outdatedThreshold` (`database/feed/dao/FeedDAO.kt:259-301`). Choices and values: `settings_keys.xml:349-367` (0, 300, 900, 3600, 21600, 43200, 86400 s). The setting is the first entry of `res/xml/notifications_settings.xml:7-15`, a screen that the settings list shows as **"Feed"** (`res/xml/main_settings.xml:49-53`, `settings_category_feed_title`), not "Notifications". |
| The setting is the same for every channel and group | yes | one threshold for the whole query (`:103-111`). |
| A refresh does not say that it skipped channels | yes | only the returned rows exist for the loader; `maxProgress` is their count (`:117-120`). When none is returned, `:121` (`filter { it.isNotEmpty() }`) empties the stream, `toList()` gives an empty list and `postProcessFeed()` (`:297-308`) runs at once: "Processing feed…" twice, then `SuccessResultEvent`. The page shows the pull indicator for a moment and then the same list. |
| No way to refresh a skipped channel short of changing the setting | yes for a feed refresh | Outside a feed refresh, `feed_last_updated` and the channel's feed rows are also written when a channel is subscribed to (`local/subscription/SubscriptionManager.kt:144-149`) and by the fetch service after a subscription import (`local/subscription/services/SubscriptionsFetchService.java:123` -> `SubscriptionManager.kt:74-86`). Both go through `FeedDatabaseManager.upsertAll` (`local/feed/FeedDatabaseManager.kt:85-119`), which sets `last_updated` to now, so such a channel is in cooldown afterwards (Q6). |
| AC-6: never refreshed, and last refresh failed, are not in cooldown | yes | **Never refreshed** = no row in `feed_last_updated` (the queries `LEFT JOIN` it) or a row with `NULL`; both satisfy `last_updated IS NULL`. Imported subscriptions have no row (`SubscriptionManager.kt:69-72`). **Success**: `DatabaseConsumer` (`FeedLoadManager.kt:319-373`) calls `upsertAll` (`:336`), which ends with `setLastUpdatedForSubscription(uid, now)` (`FeedDatabaseManager.kt:116-118`, `FeedDAO.kt:188-195`). **Failure**: `markAsOutdated(uid)` writes `NULL` (`FeedLoadManager.kt:363`, `FeedDatabaseManager.kt:78-79`). A success whose result carries reportable errors is stored and then also set to `NULL` in the same transaction (`:338-355`): it counts as failed. A failed refresh never sets a time. Two cases leave the row as it was: a channel unsubscribed during the refresh (`:332`) and an error that is not a `RequestException` (`:364-366`; every per-channel error is wrapped into one at `:232-240`). |
| AC-7: the notification check | - | `local/feed/notifications/NotificationWorker.kt:42-46`: `startLoading(ignoreOutdatedThreshold = true, groupId = -2)`; the threshold is "now" (`FeedLoadManager.kt:88-89`), so every channel with notifications on is loaded whatever its `last_updated`. |

Also checked, used below:

- **Ways a refresh starts**: `FeedFragment.reloadContent()` (`local/feed/FeedFragment.kt:1140-1150`)
  starts `FeedLoadService` with `EXTRA_GROUP_ID`; the service calls `startLoading(groupId)`
  (`local/feed/service/FeedLoadService.kt:92-93`) and drops a start while a refresh runs
  (`:85`). The only other caller is `NotificationWorker`.
- **Stored times** are epoch milliseconds (`database/Converters.kt:17-31`); the SQL compares
  these integers. `now.minusSeconds(s)` converted = `now` converted minus `s * 1000` (run on a
  JDK for a `now` with nanoseconds).
- **Ids.** `subscriptions.uid` and `feed_group.uid` are `INTEGER PRIMARY KEY AUTOINCREMENT`
  (`app/schemas/org.schabi.newpipe.database.AppDatabase/902.json:9` and `:572`; the migration
  that creates `feed_group` says the same, `database/Migrations.java:160-162`; no migration
  recreates `subscriptions`). SQLite therefore never gives the id of a deleted row to a later
  row of the same database file, also after `DELETE FROM` of the whole table; the counter
  (`sqlite_sequence`) is part of the exported file.
- **A channel's long-press menu on the Subscriptions tab** (`local/subscription/SubscriptionFragment.kt:291-319`)
  gets a `ChannelInfoItem`: service id, address and name, **no subscription id**
  (`enhancements/subscriptionscale/SubscriptionRows.kt:16-21`). Its own "Unsubscribe"
  identifies the subscription by service id and address (`:321-327`).
- **The group dialog** (`local/subscription/dialog/FeedGroupDialog.kt`): the group arrives in
  `handleGroup` (`:311-323`), only for an existing group (for a new one the `Maybe` of
  `FeedGroupDialogViewModel.kt:51-53` completes empty). OK runs
  `handlePositiveButtonInitialScreen` (`:287-309`): after its two checks it calls
  `createGroup` or `updateGroup`. A new group's id exists only inside
  `FeedGroupDialogViewModel.kt:71-74` (the `it` of `flatMapCompletable`). The group's name is
  changed nowhere else (`git grep "updateGroup("`). Cancel and back dismiss without a write
  (`:217-222`).
- **The choices' labels**: `settings/custom/DurationListPreference.kt:29-42`: a value of 0 or
  less keeps its entry text ("Always update"), any other is
  `Localization.localizeDuration(context, seconds)` (`util/Localization.java:281-301`).
- **Backup**: an export writes `preferences.all` (`settings/ContentSettingsManager.kt:39`,
  `:121`); an import clears the preferences and writes back every `Boolean`, number and
  `String` of the file (`:125-145`, legacy `:149-170`). A JSON number that fits an `Int`
  comes back as `Int`, so a `Long` preference would not survive; a `String` does.
  `feature/backup-safety` filters by a fixed list of 14 key names
  (`backupsafety/LoginData.kt`, `ALL_KEYS`); every other key passes export and import
  unchanged. Its test for unclassified keys reads `settings_keys.xml` only and looks for
  `cookie`, `token`, `password`, `secret`, `credential`.
- **Messages on the feed page**: item errors use `ErrorUtil.showSnackbar(fragment, …)`
  (`FeedFragment.kt:915`, `error/ErrorUtil.kt:151-161`): a `Snackbar` on the fragment's view,
  `LENGTH_LONG`, yellow action. A second snackbar cancels the one on screen (checked, 12).

## 2. Shape of the change

Marked edits in 6 upstream files: 14 added lines, 3 changed one for one, none removed, no
import lines, no blank lines added. Four plain Kotlin files and six Android glue files in `P`.
One new values file, one new layout. No Room entity, schema, migration or build file change;
three read-only queries are added to `FeedDAO` (a query is not part of the schema JSON).

- **Values** live in the default shared preferences as two strings (section 3).
- **The loader** asks upstream's own query for the whole scope and filters it in memory with
  each channel's effective cooldown (section 5). At most four queries per refresh.
- **Skipped channels** are kept by the loader and published, when the loading has ended, to
  this feature's own holder `SkipReports`; the feed page of the same feed takes the report
  when it shows the end of the refresh and shows a snackbar (section 6).
- **Refresh anyway** starts `FeedLoadService` with the skipped ids as an intent extra; the
  loader then loads exactly those (section 6.3).

`feedliveupdate/FeedRefreshLog.kt` is not edited and not used: it has no place for a list of
ids, its tests pin its members, and the report needs "taken once" and an age, which the log
does not have.

## 3. Storage (AC-2, AC-3, AC-4)

### 3.1 Format

Two preferences in `PreferenceManager.getDefaultSharedPreferences`, each one `String`:

| Key (literal) | Holds |
|---|---|
| `enh_refresh_cooldown_groups` | the groups' values |
| `enh_refresh_cooldown_subscriptions` | the subscriptions' values |

Value: entries `<key>=<seconds>` joined by `;`, sorted by key (`String.compareTo`). No entry
= *Use default*. With no entry at all the preference is removed, so an app without values
has no key of this feature.

- group key: `<uid>:<hash>`; `hash` = `Integer.toHexString(name.hashCode())` left-padded
  with `0` to 8 characters; `name` is the group's name as stored in the database.
  `groupKey(7, "Music")` = `7:0473fe05`; `"music"` -> `0636ee25`; `"News"` -> `0024fef3`;
  `""` -> `00000000` (computed on a JDK).
- subscription key: `<serviceId>:<URLEncoder.encode(url ?: "", "UTF-8")>`.
  `subscriptionKey(0, "https://www.youtube.com/channel/UC-lHJZR3Gqxm24_Vd_AJ5Yw")` =
  `0:https%3A%2F%2Fwww.youtube.com%2Fchannel%2FUC-lHJZR3Gqxm24_Vd_AJ5Yw`;
  `"https://example.org/c/a b;c=d:e?x=1&y=é|z"` encodes to
  `https%3A%2F%2Fexample.org%2Fc%2Fa+b%3Bc%3Dd%3Ae%3Fx%3D1%26y%3D%C3%A9%7Cz`.
- A valid key matches `-?[0-9]+:[A-Za-z0-9.*_+%-]*`; both builders produce only such keys.
- `seconds`: 1 to 9 digits, and one of the global setting's values
  (`R.array.feed_update_threshold_values`).

Example: `7:0473fe05=3600;9:0024fef3=0`.

Why this and not the alternatives:
- **A Room column or table**: a schema version and migration that collide with upstream's
  next version and with backup-safety's list of known versions. Rejected.
- **One preference per id**: up to 1600 keys in the settings export; the loader would scan
  all preferences by prefix. One string is read once and parsed by plain code.
- **A file**: not in the backup.
- **Subscriptions keyed by `uid`**: the long-press menu has no `uid` (section 1) and builds
  its entries synchronously; the address is what the menu and the loader both have.

### 3.2 Parsing (plain, never throws)

`CooldownValues.parse(text, allowedSeconds)`:

| Input | Result |
|---|---|
| `null`, `""`, `";;"`, `"abc"`, `"=300"`, `"7:0473fe05="` | empty |
| entry without `=`, or with `=` at position 0 | entry dropped |
| key not matching the pattern (`"7=300"`, `"x:1=300"`, `"7:a b=300"`) | entry dropped |
| seconds not 1-9 digits (`"3e2"`, `"-300"`, `"+300"`, `" 300"`, `"3000000000"`) | entry dropped |
| seconds not in `allowedSeconds` (`"301"`) | entry dropped |
| spaces around an entry (`" 7:0473fe05=300 ;"`) | trimmed, kept |
| the same key twice | the last one wins |
| a valid entry next to garbage | the valid entry is kept |

The entry is split at its **last** `=`. `format()` writes what `parse` kept, so garbage and
values that are no longer a choice disappear at the next write of that preference;
`parse(format(v), allowed)` equals `v`.

### 3.3 Stale values

| Case | What happens |
|---|---|
| Group deleted | its entry stays; no group will ever have that id again (AUTOINCREMENT), so it matches nothing. No effect. |
| Group renamed in the dialog | the save removes the entry of the old name and writes the one of the new name (section 7.1). |
| Channel unsubscribed | its entry stays and matches no subscription. No effect. |
| Channel subscribed to again | the entry matches again: the channel has its old cooldown (Q2). |
| Database imported **with** its settings | ids, names and addresses are those the values were stored for. |
| Database imported **without** the matching settings, or settings imported alone | a subscription's value follows the channel's address, so it applies to that channel where it is subscribed and to nothing else. A group's value applies only to a group with the same id **and** the same name; any other group is on *Use default*. |
| Id used by another row (only possible across two database files) | covered by the line above: the name hash differs (a false match needs equal id and equal `String.hashCode`). |

Values are never pruned. Size: about 20 bytes per group value, about 75 per subscription
value. The preferences are written only from the main thread (the two UI places), so
read-modify-write cannot lose an update.

## 4. New code: plain Kotlin

No `android.`, `androidx.` or `.rxjava3.android.` import; no Room entity.

```kotlin
// CooldownValues.kt: the stored values of one preference. Immutable.
class CooldownValues private constructor(private val entries: Map<String, Int>) {
    val size: Int
    operator fun get(key: String): Int?
    fun with(key: String, seconds: Int?): CooldownValues   // null removes; an invalid key: returns this
    fun format(): String                                   // "" when empty
    override fun equals(other: Any?): Boolean              // by entries; hashCode with it
    companion object {
        val EMPTY: CooldownValues
        fun parse(text: String?, allowedSeconds: Set<Int>): CooldownValues
        fun groupKey(groupId: Long, name: String): String
        fun subscriptionKey(serviceId: Int, url: String?): String
        fun isValidKey(key: String): Boolean
        /** What the group dialog's save stores. */
        fun groupSaved(values: CooldownValues, groupId: Long, oldName: String, newName: String, seconds: Int?): CooldownValues
    }
}

// CooldownChoices.kt: the global setting's choices, in its order.
class CooldownChoices(val seconds: List<Int>, val labels: List<String>) {
    val allowed: Set<Int>
    fun label(value: Int?, useDefault: String): String     // null or not a choice -> useDefault
    fun items(useDefault: String): List<String>            // useDefault, then labels
    fun indexOf(value: Int?): Int                          // null or not a choice -> 0; else position + 1
    fun valueAt(index: Int): Int?                          // 0 or out of range -> null
    companion object {
        fun of(values: Array<String>, options: Array<String>, localize: (Int) -> String): CooldownChoices
    }
}

// CooldownRules.kt: the decisions.
data class GroupName(val id: Long, val name: String)
data class Membership(val groupId: Long, val subscriptionId: Long)
/** What a refresh reads. Each function is one query or one preference read. */
interface CooldownData {
    fun lastUpdatedMillis(): Map<Long, Long>   // subscription id -> time; no entry: never or failed
    fun memberships(): List<Membership>
    fun groups(): List<GroupName>
    fun ownValues(): CooldownValues
    fun groupValues(): CooldownValues
}
class Selection<T>(val load: List<T>, val skipped: List<Long>)
object CooldownRules {
    fun effective(own: Int?, groupValues: Collection<Int>, global: Int): Int
    fun inCooldown(lastUpdatedMillis: Long?, cooldownSeconds: Int, nowMillis: Long): Boolean
    fun <T> select(scope: List<T>, idOf: (T) -> Long, keyOf: (T) -> String,
                   data: CooldownData, globalSeconds: Int, nowMillis: Long): Selection<T>
    fun <T> forced(scope: List<T>, idOf: (T) -> Long, forcedIds: LongArray): Selection<T>
}

// SkipReports.kt: what a refresh skipped, for the page that shows its end.
data class SkipReport(val groupId: Long, val subscriptionIds: List<Long>) { val count: Int }
class SkipReports(private val offerMillis: Long = OFFER_MILLIS) {
    fun clear()
    fun publish(groupId: Long, skipped: List<Long>, nowMillis: Long)   // an empty list clears
    fun take(pageGroupId: Long, nowMillis: Long): SkipReport?
    companion object {
        const val OFFER_MILLIS = 60_000L
        const val MESSAGE_MILLIS = 8_000
        const val ERROR_MESSAGE_WAIT_MILLIS = 3_500L
        const val EXTRA_FORCED = "org.schabi.newpipe.enhancements.refreshcooldown.FORCED"
        @JvmField val app = SkipReports()
    }
}
```

### 4.1 `effective(own, groupValues, global)` = `own ?: groupValues.minOrNull() ?: global`

| own | groupValues | global | result |
|---|---|---|---|
| 3600 | any | any | 3600 |
| 0 | [86400] | 300 | 0 |
| 86400 | [0] | 300 | 86400 |
| null | [] | 300 | 300 |
| null | [3600, 900] | 300 | 900 |
| null | [3600] | 300 | 3600 (a group's value replaces the global one, also when longer) |
| null | [0, 3600] | 86400 | 0 |

`groupValues` holds one value per group the channel is in that has a value; groups on *Use
default* are not in it.

### 4.2 `inCooldown(last, s, now)` = `last != null && last >= now - s * 1000L`

The negation of the SQL condition of section 1, on the same integers.

| last | s | now | in cooldown |
|---|---|---|---|
| null | any | any | no (AC-6) |
| now - 299_999 | 300 | now | yes |
| now - 300_000 | 300 | now | yes (`<` in the SQL is strict) |
| now - 300_001 | 300 | now | no |
| now - 1 | 0 | now | no ("Always update") |
| now | 0 | now | yes (as today) |
| now + 5000 (clock set back) | any | now | yes (as today) |

### 4.3 `select` and `forced`

`select(scope, idOf, keyOf, data, globalSeconds, nowMillis)`:

1. `last = data.lastUpdatedMillis()`; `own = data.ownValues()`; `groupValues = data.groupValues()`.
2. If `groupValues.size == 0`: no group has a value; `memberships()` and `groups()` are
   **not** called. Otherwise `secondsOfGroup` = for each `GroupName g` of `data.groups()` the
   value `groupValues[groupKey(g.id, g.name)]`, if any; and `bySubscription` = for each
   `Membership` of `data.memberships()` whose group is in `secondsOfGroup`, that value, in a
   list per subscription id.
3. For each `t` of `scope`, in order: `e = effective(own[keyOf(t)], bySubscription[idOf(t)]
   ?: emptyList(), globalSeconds)`; if `inCooldown(last[idOf(t)], e, nowMillis)` the id goes
   to `skipped`, else `t` goes to `load`.

Each function of `data` is called at most once per `select`, whatever the sizes: 1 query
without group values, 3 with them. With upstream's scope query that is 2 or 4 per refresh.

Which channels a refresh loads, `now` = 1 000 000 000, global 300 s; A has no group, B and C
are in group 7 ("Music", value 3600), C is also in group 9 ("News", value 0), D is in group 9
and has its own value 86400, E is in group 8 (no value):

| Channel | last updated before now | effective | result |
|---|---|---|---|
| A | 200 s | 300 (global) | skipped |
| A | 301 s | 300 | loaded |
| A | never / failed | 300 | loaded |
| B | 301 s | 3600 (group 7) | skipped |
| B | 3601 s | 3600 | loaded |
| C | 1 s | 0 (shortest of 3600 and 0) | loaded |
| D | 3601 s | 86400 (own) | skipped |
| D | 86401 s | 86400 | loaded |
| E | 200 s | 300 (global) | skipped |
| B, group 7 renamed to "Rock" in the database, value still stored for "Music" | 301 s | 300 (the value does not match) | loaded |
| a channel not in `scope` | any | - | neither loaded nor skipped |

The result for a channel does not depend on the group of the refresh: memberships and
values of **all** groups are read (AC-5).

`forced(scope, idOf, forcedIds)`: `load` = the elements of `scope` whose id is in
`forcedIds`, in scope order; `skipped` empty. Ids not in `scope` are ignored.

### 4.4 `SkipReports`

Holds at most one report and the time it was published; all three functions synchronized.

| Held | Call | Returns | Held afterwards |
|---|---|---|---|
| any | `publish(7, [], t)` | - | nothing |
| any | `publish(7, [3, 5], t)` | - | report (7, [3, 5]) at t |
| any | `clear()` | - | nothing |
| nothing | `take(any, any)` | null | nothing |
| (7, …) at t | `take(7, t + 60_000)` | the report | nothing |
| (7, …) at t | `take(7, t + 60_001)` | null | nothing |
| (7, …) at t | `take(9, t + 1)`, `take(-1, t + 1)` | null | the report |
| (-1, …) at t | `take(-1, t + 1)` | the report | nothing |
| (-1, …) at t | `take(7, t + 1)` | null | the report |

The message for 0, 1 and many skipped channels: 0 never becomes a report, so no message
(AC-10); otherwise the plural `enh_refresh_cooldown_skipped` with `report.count` (section 8).

### 4.5 `CooldownChoices`

`of(values, options, localize)`: for each index `i` of `values`: `s = values[i]` as an
integer (an entry that is none is left out); label = `options[i]` if `s <= 0` (or `s` as
text when `options` is shorter), else `localize(s)`. That is the rule of
`DurationListPreference.kt:29-42`, without its exception. With the app's arrays: seconds
`[0, 300, 900, 3600, 21600, 43200, 86400]`, English labels `["Always update", "5 minutes",
"15 minutes", "1 hour", "6 hours", "12 hours", "1 day"]`.

`indexOf(null)` = 0, `indexOf(0)` = 1, `indexOf(86400)` = 7, `indexOf(301)` = 0;
`valueAt(0)` = null, `valueAt(1)` = 0, `valueAt(7)` = 86400, `valueAt(8)` = null,
`valueAt(-1)` = null; `label(301, "Use default")` = `"Use default"`.

### 4.6 `groupSaved(values, groupId, oldName, newName, seconds)`

`values.with(groupKey(groupId, oldName), null).with(groupKey(groupId, newName), seconds)`.

| Stored before | old name | new name | chosen | Stored after |
|---|---|---|---|---|
| nothing | Music | Music | null | nothing |
| nothing | Music | Music | 3600 | `7:0473fe05=3600` |
| `7:0473fe05=3600` | Music | Music | null | nothing |
| `7:0473fe05=3600` | Music | News | 3600 | `7:0024fef3=3600` |
| `7:0473fe05=3600;9:0024fef3=0` | Music | Music | 900 | `7:0473fe05=900;9:0024fef3=0` |

## 5. The loader (AC-5, AC-6, AC-7, AC-11)

### 5.1 Glue: `CooldownRefresh.kt`, one object per `startLoading` call

```kotlin
class CooldownRefresh(private val context: Context, private val groupId: Long,
                      private val ignore: Boolean, private val forced: LongArray?) {
    init { if (!ignore) SkipReports.app.clear() }
    fun queryThreshold(now: OffsetDateTime, globalSeconds: Int): OffsetDateTime
    fun select(scope: List<SubscriptionEntity>): List<SubscriptionEntity>
    fun done()
}
```

- `queryThreshold` stores `now.toInstant().toEpochMilli()` and `globalSeconds` (two
  `@Volatile` fields) and returns `now.plusYears(100)`. With that threshold upstream's query
  returns every subscription of its scope ("All", or the group), as its condition is then
  true for every stored time.
- `select(scope)`: if `ignore`, returns `scope` itself. Else if `forced != null`:
  `CooldownRules.forced(scope, { it.uid }, forced)`. Else `CooldownRules.select(scope,
  { it.uid }, { CooldownValues.subscriptionKey(it.serviceId, it.url) }, RoomCooldownData(context),
  globalSeconds, nowMillis)`. Keeps `skipped` in a `@Volatile` field and returns `load`.
- `done()`: if not `ignore`: `SkipReports.app.publish(groupId, skipped,
  SystemClock.elapsedRealtime())`.

`RoomCooldownData.kt` implements `CooldownData`, each function with exactly one call:
`NewPipeDatabase.getInstance(context).feedDAO().cooldownLastUpdated()` (rows with a non-null
time, as `toInstant().toEpochMilli()`), `.cooldownMemberships()`, `.cooldownGroups()`,
`CooldownStore.subscriptions(context)`, `CooldownStore.groups(context)`.

`CooldownStore.kt`: `choices(context): CooldownChoices` (from
`R.array.feed_update_threshold_values`, `R.array.feed_update_threshold_options` and
`Localization.localizeDuration`); `subscriptions(context)` and `groups(context)`:
`CooldownValues.parse(string, choices.allowed)`, the string read inside `runCatching` (a
value of another type gives empty); `setSubscription(context, key, seconds)` and
`saveGroup(context, groupId, oldName, newName, seconds)`: read, `with` / `groupSaved`, then
`putString(format())`, or `remove(key)` when the result is empty, with `apply()`.

### 5.2 Why the whole scope, and why AC-11 holds

The count of skipped channels (AC-8) needs every channel of the scope, also with no value
set, and the in-memory decision needs each channel's stored time, which upstream's query
does not return. So upstream's query is asked for the whole scope and `cooldownLastUpdated()`
supplies the times. Asking with the shortest cooldown in play, or with "now", was rejected:
both lose the channels a longer or equal cooldown skips, which are the ones to count.

With no value stored, `effective` is the global value for every channel and `select` loads a
channel exactly when `last == null || last < now - global * 1000`: today's SQL condition on
the same integers, with the `now` and the seconds that upstream's own lines produce
(`FeedLoadManager.kt:91-97`). The order of the list is the query's. `maxProgress` is still
the number of channels to load, because `select` sits before `:117`.

Differences that remain, none of them in which channels are loaded: the scope query returns
all rows of the scope (as a refresh after a day does today); the times are read by a second
query, not in the same statement; a channel whose stored time is more than 100 years ahead
is not counted as skipped (it is not loaded, as today).

### 5.3 The notification check (AC-7)

`NotificationWorker.kt` is not edited. With `ignoreOutdatedThreshold = true`:
`queryThreshold` is not reached (`FeedLoadManager.kt:88-89` is the other branch), the
constructor does nothing, `select` returns the list it was given, `done()` does nothing, and
`cooldownForced` is `null`. No preference and no table of this feature is read; a report of a
manual refresh is neither cleared nor published.

## 6. Skipped channels and "Refresh anyway" (AC-8, AC-9, AC-10)

### 6.1 From the loader to the page

- Start of a manual refresh: the report of an earlier refresh is cleared (constructor).
- `select` keeps the skipped ids. `done()` publishes them from a marked line between
  `.toList()` and the `postProcessFeed()` line, so the report exists before
  `SuccessResultEvent` is posted, and only when the loading has ended (also after a cancel
  from the notification; not after a failure of the chain).
- The page: one marked line at the end of `FeedFragment.handleResult`, for a `LoadedState`
  only. A `LoadedState` reaches `handleResult` for the end of a refresh and for an idle page;
  list updates during a refresh do not pass `handleResult` with one (they are
  `ProgressState`s, `FeedFragment.kt:769-771`). `take(groupId, now)` gives the report once,
  to a page of the same feed as the refresh ("All" for "All", the group for the group), for
  60 seconds after the end. A page that was left before the end gets it when it is returned
  to within that time.

If every channel was skipped, the refresh ends at once as today (section 1) and the message
appears with that end.

### 6.2 The message: `SkippedMessageUi.kt` (glue)

```kotlin
object SkippedMessageUi {
    fun offer(fragment: Fragment, groupId: Long, afterErrors: Boolean)
}
```

- `offer`: `view = fragment.view ?: return`. If `afterErrors`:
  `view.postDelayed({ show(fragment, groupId) }, SkipReports.ERROR_MESSAGE_WAIT_MILLIS)`,
  else `show(fragment, groupId)`.
- `show`: returns if `fragment.view == null` or `!fragment.isResumed`. `report =
  SkipReports.app.take(groupId, SystemClock.elapsedRealtime()) ?: return`. Then, inside
  `runCatching` (no suitable parent view: no message):
  `Snackbar.make(view, resources.getQuantityString(R.plurals.enh_refresh_cooldown_skipped,
  report.count, report.count), SkipReports.MESSAGE_MILLIS).setActionTextColor(Color.YELLOW)
  .setAction(R.string.enh_refresh_cooldown_refresh_anyway) { refreshAnyway(context, report) }.show()`.
  A snackbar does not block the page and goes away after 8 seconds (AC-8).
- `afterErrors` is `result.itemsErrors.isNotEmpty()`: the end of that refresh also shows
  upstream's error snackbar, and a second snackbar would cancel it at once. The skipped
  message waits 3.5 s (`LENGTH_LONG` is 2.75 s) (Q4). The report is taken when the message is
  shown, not when it is scheduled, so a page left meanwhile keeps it.

### 6.3 Refresh anyway

`refreshAnyway(context, report)`: `context.startService(Intent(context,
FeedLoadService::class.java).putExtra(FeedLoadService.EXTRA_GROUP_ID, report.groupId)
.putExtra(SkipReports.EXTRA_FORCED, report.subscriptionIds.toLongArray()))`: what
`reloadContent()` does (`FeedFragment.kt:1143-1147`), plus the ids (1600 ids are 12.8 kB).

The service passes the extra to `startLoading` (one changed line); a start that the service
drops takes its extra with it, so no forced set can be left over for a later refresh. In the
loader the scope query runs for the same group and `forced` keeps exactly the listed ids:
their cooldown is not looked at, nothing else is loaded. The refresh is otherwise a normal
one of that group: the page shows it (`FeedRefreshLog.started(groupId)`), results are stored
by `DatabaseConsumer`, a success sets `last_updated` to now, so the channels are in cooldown
afterwards and a failed one is not (AC-9, AC-6). It publishes an empty report: no message
(AC-10).

Not refreshed by it: a channel that was unsubscribed or left the group since the report
(Q5). If another refresh is running when the action is tapped, the service drops the start
and nothing happens (risk 2).

## 7. The two places to set a value (AC-2, AC-3)

Both use `CooldownChooser.kt` (glue):

```kotlin
object CooldownChooser {
    fun show(context: Context, current: Int?, onChosen: (Int?) -> Unit): AlertDialog
}
```

An `androidx.appcompat.app.AlertDialog`: title `enh_refresh_cooldown_title`, single-choice
items `choices.items(getString(R.string.enh_refresh_cooldown_use_default))`, checked item
`choices.indexOf(current)`; a tap on an item calls `onChosen(choices.valueAt(which))` and
dismisses; negative button `R.string.cancel` (no call).

### 7.1 The group dialog: `GroupCooldownUi.kt` (glue)

```kotlin
object GroupCooldownUi {
    fun attach(optionsRoot: ViewGroup, group: FeedGroupEntity?)
    fun save(optionsRoot: ViewGroup, groupId: Long, newName: String)
}
```

- **The row** is a borderless button below the "n selected" line and above "Select
  subscriptions", text `enh_refresh_cooldown_entry` with the value's label: "Refresh
  cooldown: Use default", "Refresh cooldown: 1 hour".
- `attach` is called at the end of `handleGroup`. `group == null`, or the row already in
  `optionsRoot` (`findViewById(R.id.enh_refresh_cooldown_group_button) != null`): nothing.
  Else: inflate `R.layout.enhancements_refresh_cooldown_group_row` with `optionsRoot` as
  parent (`attachToRoot = false`), `addView`; set the "Select subscriptions" button's
  `(layoutParams as ConstraintLayout.LayoutParams).topToBottom` to the row's id and assign
  the params back; if `optionsRoot.layoutParams.height > 0`, add 48 dp
  (`(48 * density).roundToInt()`) to it and assign the params back. The row's tag is a
  `RowState(var seconds: Int?, val storedName: String)` with `seconds` =
  `CooldownStore.groups(context)[groupKey(group.uid, group.name)]` and `storedName` =
  `group.name`. A click opens the chooser with `state.seconds`; `onChosen` sets
  `state.seconds` and the text. Nothing is stored yet. The chooser is dismissed when the row
  is detached from its window (`addOnAttachStateChangeListener`).
- `save` is called in `handlePositiveButtonInitialScreen` after its two checks, before
  `createGroup` / `updateGroup`: no row or no `RowState`: nothing. Else
  `CooldownStore.saveGroup(context, groupId, state.storedName, newName, state.seconds)`.
  `newName` is the trimmed name the dialog writes to the database (`FeedGroupDialog.kt:288`),
  so the stored hash is that of the stored name.
- **Cancel**, back, or a tap outside: no `save`, nothing stored. **Delete**: goes through
  `handlePositiveButton` (`:282`), not through the save; the value stays and matches nothing.
- **A new group** has no row: `handleGroup` is never called for it. It is on *Use default*;
  the value is set by editing the group afterwards (Q3).
- **Rotation**: the dialog's view is rebuilt, `attach` runs again and shows the stored value;
  a choice not yet saved is lost (the group-dialog feature accepts the same for its pending
  rows).

### 7.2 The channel's long-press menu: `SubscriptionCooldownUi.kt` (glue)

```kotlin
object SubscriptionCooldownUi {
    fun entry(context: Context, item: ChannelInfoItem): String
    fun choose(context: Context, item: ChannelInfoItem)
}
```

- A fourth entry after "Unsubscribe": `entry` = `enh_refresh_cooldown_entry` with the label
  of `CooldownStore.subscriptions(context)[subscriptionKey(item.serviceId, item.url)]`.
- `choose` opens the chooser with that value; `onChosen` calls
  `CooldownStore.setSubscription(context, key, seconds)` at once. The menu is rebuilt each
  time it is opened, so it shows the new value.
- Not changed: the similar menu of the channel list page of a group
  (`local/subscription/SubscriptionListFragment.kt:238-290`); the spec names the
  Subscriptions tab (Q7).

## 8. New resources (AC-12)

`res/values/enhancements_refresh_cooldown.xml`:

| Name | Text |
|---|---|
| `enh_refresh_cooldown_groups_key` (`translatable="false"`) | `enh_refresh_cooldown_groups` |
| `enh_refresh_cooldown_subscriptions_key` (`translatable="false"`) | `enh_refresh_cooldown_subscriptions` |
| `enh_refresh_cooldown_title` | Refresh cooldown |
| `enh_refresh_cooldown_entry` | Refresh cooldown: %s |
| `enh_refresh_cooldown_use_default` | Use default |
| `enh_refresh_cooldown_refresh_anyway` | Refresh anyway |
| plurals `enh_refresh_cooldown_skipped`, `one` | %d channel was skipped because of its cooldown |
| plurals `enh_refresh_cooldown_skipped`, `other` | %d channels were skipped because of their cooldown |

The choices' labels are the existing ones (section 4.5); "Cancel" is `R.string.cancel`.

`res/layout/enhancements_refresh_cooldown_group_row.xml`: one `Button`,
`android:id="@+id/enh_refresh_cooldown_group_button"`,
`style="@style/Widget.AppCompat.Button.Borderless"`, `android:layout_width="0dp"`,
`android:layout_height="48dp"`, `android:layout_marginStart="8dp"`,
`android:layout_marginEnd="8dp"`, `android:textAllCaps="false"`,
`app:layout_constraintStart_toStartOf="parent"`, `app:layout_constraintEnd_toEndOf="parent"`,
`app:layout_constraintTop_toBottomOf="@id/selected_subscription_count_view"`.
`res/layout/dialog_feed_group_create.xml` is not edited: a changed attribute inside a tag
cannot carry a marker.

## 9. The upstream edits

`+` = added line after the line named, `~` = changed line (old, then new). Every added line
has the indentation of its neighbours.

**`local/feed/service/FeedLoadManager.kt`**: 4 added, 1 changed.
- `+ :75` (`retainNewStreams: Boolean = false, // ENH:subscription-scale`):
  `cooldownForced: LongArray? = null, // ENH`
- `+ :86` (the `)` that closes `filterFutureItems`):
  `val cooldown = P.CooldownRefresh(context, groupId, ignoreOutdatedThreshold, cooldownForced) // ENH`
- `~ :97` `OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(thresholdOutdatedSeconds.toLong())` ->
  `cooldown.queryThreshold(OffsetDateTime.now(ZoneOffset.UTC), thresholdOutdatedSeconds) // ENH`
- `+ :116` (`.map { list -> list.filter { ServiceList.getById(it.serviceId) != null } }`):
  `.map { list -> cooldown.select(list) } // ENH`
- `+ :271` (`.toList()`): `.doOnSuccess { cooldown.done() } // ENH`

`:88-96` stay: upstream's own reading of the setting supplies the seconds. `:103-111` stay:
they get the whole-scope threshold through `outdatedThreshold`. The lines marked
`ENH:subscription-scale` and `ENH:feed-live-update` are not touched, and their wiring tests
still hold: `batchStored` directly after `.doOnNext(DatabaseConsumer())`; `.toList()` before
the `postProcessFeed()` line (order, not adjacency); `started` and `ended` directly after it
and last.

**`local/feed/service/FeedLoadService.kt`**: 1 changed.
- `~ :93` `loadingDisposable = feedLoadManager.startLoading(groupId)` ->
  `loadingDisposable = feedLoadManager.startLoading(groupId, cooldownForced = intent.getLongArrayExtra(P.SkipReports.EXTRA_FORCED)) // ENH`

**`database/feed/dao/FeedDAO.kt`**: 6 added.
- `+ :301` (`): Flowable<List<SubscriptionEntity>>`, the last line before the class's `}`):
  ```kotlin
      @Query("SELECT * FROM feed_last_updated") // ENH
      abstract fun cooldownLastUpdated(): List<FeedLastUpdatedEntity> // ENH
      @Query("SELECT * FROM feed_group_subscription_join") // ENH
      abstract fun cooldownMemberships(): List<org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity> // ENH
      @Query("SELECT * FROM feed_group") // ENH
      abstract fun cooldownGroups(): List<org.schabi.newpipe.database.feed.model.FeedGroupEntity> // ENH
  ```
  Plain `List` results run on the calling thread. They are in `FeedDAO` and not in
  `FeedGroupDAO` because `feature/group-dialog` adds lines to the latter. Declared queries
  and not raw SQL in the glue, so that Room checks them when the code is compiled.

**`local/feed/FeedFragment.kt`**: 1 added.
- `+ :736` (`updateRefreshViewState()`, the last statement of `handleResult`):
  `if (result is FeedState.LoadedState) P.SkippedMessageUi.offer(this, groupId, result.itemsErrors.isNotEmpty()) // ENH`

**`local/subscription/dialog/FeedGroupDialog.kt`**: 2 added.
- `+ :303` (the `}` that closes `if (selectedSubscriptions.isEmpty()) {`):
  `P.GroupCooldownUi.save(feedGroupCreateBinding.optionsRoot, groupId, name) // ENH`
- `+ :322` (the `}` that closes `if (feedGroupCreateBinding.groupNameInput.text.isNullOrBlank()) {`):
  `P.GroupCooldownUi.attach(feedGroupCreateBinding.optionsRoot, feedGroupEntity) // ENH`

**`local/subscription/SubscriptionFragment.kt`**: 1 changed, 1 added.
- `~ :295` `getString(R.string.unsubscribe)` ->
  `getString(R.string.unsubscribe), P.SubscriptionCooldownUi.entry(requireContext(), selectedItem) // ENH`
- `+ :305` (`2 -> deleteChannel(selectedItem)`):
  `3 -> P.SubscriptionCooldownUi.choose(requireContext(), selectedItem) // ENH`

Marked lines per file, for the wiring test: `FeedLoadManager.kt` 5, `FeedLoadService.kt` 1,
`FeedDAO.kt` 6, `FeedFragment.kt` 1, `FeedGroupDialog.kt` 2, `SubscriptionFragment.kt` 2;
total 17 = 14 added + 3 changed. Not edited: `FeedViewModel.kt`, `FeedState.kt`,
`NotificationWorker.kt`, `FeedDatabaseManager.kt`, `FeedGroupDialogViewModel.kt`,
`FeedGroupDAO.kt`, `SubscriptionDAO.kt`, `ContentSettingsManager.kt`, every upstream file
under `res/`.

## 10. Cost

Per manual refresh: upstream's scope query (1), `cooldownLastUpdated` (1), and only when a
group has a value `cooldownMemberships` and `cooldownGroups` (2); two preference strings
parsed once. Then one pass over the scope with map lookups. 1600 subscriptions and 40 groups:
4 queries, about 1600 + rows of the join table in memory. Nothing per channel. The
notification check: no extra work.

## 11. Merge check

Done with `git merge-file` on copies carrying exactly the edits of section 9, against each
branch's version and the common ancestor, for `FeedFragment.kt` and `SubscriptionFragment.kt`,
with `feature/queue-guard`, `feature/default-open-action`, `feature/crash-fixes-more`,
`feature/release-views`, `feature/group-sort`, `feature/group-dialog`,
`feature/backup-safety` and `build/device-test`: 16 merges, no conflict, the marked lines
present in each result.

| Branch | Its edits in the six files | Result |
|---|---|---|
| `feature/queue-guard`, `feature/default-open-action` | `FeedFragment.kt`: play buttons `:330-349`, one line in the tap listener | clean; nearest edit of this plan `:736` |
| `feature/crash-fixes-more` | `FeedFragment.kt`: first line of `handleItemsErrors` and of `handleFeedNotAvailable` | clean |
| `feature/release-views` | `SubscriptionFragment.kt`: five lines after `onDestroy` (`:131`) | clean; this plan edits `:295`, `:305` |
| `feature/group-sort` | none (`FeedDatabaseManager.kt:39` only) | clean |
| `feature/group-dialog` | none (`FeedGroupDAO.kt`, `dialog_feed_group_selection.xml`) | clean |
| `feature/backup-safety` | none (`ContentSettingsManager.kt`, `BackupSettingsFragment.java`) | clean |
| `build/device-test` | the sum of the above | clean |

`FeedLoadManager.kt`, `FeedLoadService.kt`, `FeedDAO.kt` and `FeedGroupDialog.kt` are edited
by no other branch (`git diff --stat` of each branch against its merge base). No conflict is
expected.

## 12. Library and framework behaviour relied on

| Behaviour | Status |
|---|---|
| `uid` of `subscriptions` and `feed_group` is AUTOINCREMENT in every way the tables are created | checked (schema JSON, migration) |
| SQLite never reuses an AUTOINCREMENT id within one database file | assumed (SQLite's documented behaviour) |
| Stored times are epoch milliseconds; `minusSeconds(s)` then conversion equals conversion minus `s * 1000` | checked (`Converters.kt`; run on a JDK) |
| Import and export carry every `String` preference except backup-safety's 14 keys | checked (`ContentSettingsManager.kt`, `LoginData.kt`) |
| `String.hashCode`, `Integer.toHexString`, `URLEncoder.encode(…, "UTF-8")` give the same result on the device as on the test JVM | first two specified by the language; `URLEncoder` assumed (run on a JDK only) |
| A Room `@Query` with a plain `List` result runs on the calling thread; `.map` after upstream's query runs off the main thread (Room's query executor) | assumed; a main-thread call would throw on the first refresh (device step 5) |
| Adding `@Query` methods does not change the exported schema | assumed (the schema JSON lists entities and views only; `subscription-scale` added one the same way) |
| Material 1.14.0 `SnackbarManager.show`: a new snackbar cancels the one on screen and is shown next | checked (compiled class) |
| `Snackbar.setDuration`/`make` accept a duration in milliseconds; `LENGTH_LONG` is 2750 ms | `make(View, CharSequence, int)` and `setDuration(int)` checked to exist; the values assumed |
| `ConstraintLayout.LayoutParams.topToBottom` is a public field (2.2.1); assigning `layoutParams` requests a layout | first checked; second assumed |
| A layout inflated with a `ConstraintLayout` parent and `attachToRoot = false` gets `ConstraintLayout.LayoutParams` from its `app:layout_constraint…` attributes; `@id/…` of another layout file resolves | assumed; instrumented test and device step 2 |
| `getStringArray` resolves the `@string/…` item of `feed_update_threshold_options` in the current language | assumed (it is how the setting's own list gets it) |
| `Intent` carries a `long[]` of 1600 elements; `getLongArrayExtra` returns `null` without the extra | assumed |

## 13. Verification

**JVM unit tests**, `app/src/test/java/org/schabi/newpipe/enhancements/refreshcooldown/`,
JUnit 4 only. Channels are test data classes `(id, serviceId, url)`; `CooldownData` is a
fake that counts the calls of each function.

| Test class | AC | Cases |
|---|---|---|
| `CooldownValuesTest` | 2, 3, 4 | every row of 3.2; the key examples of 3.1 (hashes and encodings as printed there); `isValidKey`; `with` adds, replaces, removes, ignores an invalid key, does not change the receiver; `format` sorted by key, `""` when empty; `parse(format(v))` equals `v` for 1600 subscription values; a key whose address contains `;`, `=`, `:`, a space and a non-ASCII letter survives the round trip; every row of 4.6 |
| `CooldownChoicesTest` | 1, 2, 3 | `of` with the app's two arrays (read from `res/values/settings_keys.xml`) and a fake `localize`: seconds, labels, the 0 entry keeps its option text; a non-numeric value is left out; the examples of 4.5 |
| `CooldownRulesTest` | 5, 6, 11 | every row of 4.1 and 4.2; every row of the table in 4.3; order of `load` = order of `scope`; `forced`: exactly the listed ids, in scope order, unknown ids ignored, `skipped` empty, an empty array loads nothing |
| `CooldownEqualsTodayTest` | 11 | with empty values, for each of the seven global values and for times at -1, 0 and +1 ms around `now - s * 1000`, never, and in the future: `load` equals the channels for which `last == null || last < now - s * 1000`; `memberships()` and `groups()` not called |
| `CooldownScaleTest` | 5 | 1600 channels, 40 groups, 4000 memberships, values on 10 groups and 100 channels: each `CooldownData` function called exactly once; with no group value: `lastUpdatedMillis`, `ownValues`, `groupValues` once, the other two never; the same counts for 16 channels; `load.size + skipped.size == 1600`; a channel in two groups gets the shorter value whichever group is listed first |
| `SkipReportsTest` | 8, 9, 10 | every row of 4.4; `take` twice gives the report once; a custom `offerMillis`; the constants (`OFFER_MILLIS` 60 000, `MESSAGE_MILLIS` 8000, `ERROR_MESSAGE_WAIT_MILLIS` 3500 and greater than 2750) |
| `RefreshCooldownResourcesTest` | 12 | `res/values/enhancements_refresh_cooldown.xml` read as XML: the names and English texts of section 8; the two keys `translatable="false"` with the literal values of 3.1; the plural has `one` and `other`, each with one `%d`; `enh_refresh_cooldown_entry` has one `%s`; no key contains `cookie`, `token`, `password`, `secret` or `credential`; the layout file has the id, the height and the three constraints of section 8; no `values-*/enhancements_refresh_cooldown.xml` exists |
| `NewFilesSourceTest` | all | the package holds the ten files of sections 4-7 and no other; the four plain files import nothing from `android.`, `androidx.` or `.rxjava3.android.`; `RoomCooldownData.kt` mentions each of the three DAO functions once; `CooldownRefresh.kt` mentions `plusYears(100)`, `SkipReports.app.clear()` and `SkipReports.app.publish(`; `SkippedMessageUi.kt` mentions `SkipReports.app.take(`, `EXTRA_FORCED`, `FeedLoadService.EXTRA_GROUP_ID` and `postDelayed` |
| `RefreshCooldownWiringTest` | all | per file the number of lines containing `ENH:refresh-cooldown` (5, 1, 6, 1, 2, 2; no other upstream file under `app/src/main` has one) and, whitespace ignored, each line of section 9. Order and place: the parameter directly after the `retainNewStreams` parameter; `val cooldown` after the `filterFutureItems` statement and before `val outdatedThreshold`; `queryThreshold` inside the `else` branch, the line `OffsetDateTime.now(ZoneOffset.UTC)` of the `if` branch (`:89`) unchanged and unmarked; `cooldown.select` directly after the `ServiceList.getById` line and before `maxProgress.set(it.size)`; `cooldown.done()` directly after `.toList()` and directly before the `postProcessFeed()` line; the three `outdatedThreshold` arguments of `:104-110` unchanged. `FeedDAO.kt`: three annotation/function pairs, each annotation directly above its function, after `getOutdatedWithNotificationMode`. `FeedFragment.kt`: the line in `handleResult`, directly after `updateRefreshViewState()`. `FeedGroupDialog.kt`: `save` in `handlePositiveButtonInitialScreen` before `when (groupId) {`, `attach` the last statement of `handleGroup`. `SubscriptionFragment.kt`: both in `showLongTapDialog`; the old line `getString(R.string.unsubscribe)` gone. Unchanged: `NotificationWorker.kt` has no marker of this feature and still passes `ignoreOutdatedThreshold = true`; `settings_keys.xml` and `notifications_settings.xml` have none (AC-1); the files of `enhancements/feedliveupdate` and `enhancements/subscriptionscale` have none; no file edited here imports from the feature's package |

**Instrumented**, `app/src/androidTest/.../refreshcooldown/GroupCooldownRowTest.kt`, compiled
by CI, run with the device script: `dialog_feed_group_create` inflated with
`ContextThemeWrapper(targetContext, R.style.DarkTheme)`, `options_root` made visible,
measured and laid out by hand at 360 dp width. (1) `attach` with a `FeedGroupEntity`: the
row exists, its text is "Refresh cooldown: Use default", its top is at or below the bottom
of the count line, its bottom at or above the top of "Select subscriptions", and
`options_root` is 48 dp higher than before; (2) `attach` twice: one row, height raised once;
(3) `attach(null)`: no row; (4) with `7:<hash of the name>=3600` stored: "Refresh cooldown:
1 hour"; `save` with a new name stores the entry under the new name's key and removes the
old one. The test restores the preference it wrote.

**Not testable without a device:** the loader chain, Room, the snackbar, the dialogs.

**Device script.** Debug build, the owner's subscriptions, groups "Music" (A) and "News" (B)
with one common channel X; A holds X and a channel Y and nothing else; a channel Z in no
group; "All", A and B as main tabs. Global setting 5 minutes unless a step says otherwise.

1. AC-1: Settings > Feed: "Feed update threshold" is where it was, seven choices, default
   5 minutes.
2. AC-2: long-press group A: the row "Refresh cooldown: Use default" sits between "n
   selected" and "Select subscriptions", nothing overlaps, also in landscape. Choose
   "1 hour", cancel the dialog, reopen: "Use default". Choose "1 hour", OK, reopen:
   "1 hour". Rename A and keep the value, OK, reopen: "1 hour". A new group's dialog has no
   row.
3. AC-3: Subscriptions tab, long-press Z: fourth entry "Refresh cooldown: Use default";
   choose "1 day"; long-press again: "Refresh cooldown: 1 day".
4. AC-4: restart the app: steps 2 and 3 still show their values. Export a backup, clear the
   app's data, import database and settings: the same values. Delete group A and
   unsubscribe from Z: a refresh of "All" behaves as with no values for them. Import the
   database only, into a fresh install: no group has a value.
5. AC-5: A on 1 hour, B on "Always update", Z on 1 day. Refresh "All" twice, a minute apart:
   the second loads exactly the channels of B, X among them (shortest of its two groups);
   the count in the header is B's size. Refresh A's page: 1 channel (X), Y is skipped. Wait
   six minutes: "All" loads B's channels and the channels without a value, not Y, not Z.
6. AC-6: airplane mode, refresh A with Y's cooldown expired: Y fails ("Not loaded"); network
   on, refresh A at once: Y is loaded. A channel imported and never loaded is loaded by the
   first refresh whatever its group's value.
7. AC-7: notifications on for Y, Y in cooldown: Settings > Debug > "Check for new streams"
   (`settings/DebugSettingsFragment.java:131`): its notification counts Y; no snackbar on
   the feed afterwards, and a report of a refresh made just before is still offered.
8. AC-8: refresh "All" a minute after a full refresh: "n channels were skipped because of
   their cooldown" with "REFRESH ANYWAY" at the bottom, the list scrolls and taps work while
   it shows, it goes after about 8 seconds. With exactly one skipped channel (a group of
   one): "1 channel was skipped because of its cooldown". With a failing channel in the same
   refresh: the error message first, the skipped message about 3.5 s later.
9. AC-9: tap "Refresh anyway" after a refresh of A that skipped Y: the progress counts to 1,
   Y's new videos appear, channels of other groups keep their "last updated". Refresh A
   again: Y is skipped again.
10. AC-10: set everything to "Always update" (or wait out every cooldown), refresh: no
    message. After "Refresh anyway": no message.
11. AC-11: remove all values. Refresh "All" one minute and six minutes after a full refresh
    and compare the header's count with the build before this change on the same data: the
    same numbers; the only difference is the message.
12. AC-12: switch the app to another language: the new texts are English, the choices are in
    that language.

## 14. AC coverage

| AC | Design | Unit tests | Device |
|---|---|---|---|
| AC-1 | nothing of the setting is edited (9) | wiring (unchanged files), `CooldownChoicesTest` | 1 |
| AC-2 | 3, 7.1 | `CooldownValuesTest` (4.6), instrumented | 2 |
| AC-3 | 3, 7.2 | `CooldownValuesTest`, `CooldownChoicesTest` | 3 |
| AC-4 | 3.1, 3.3 | `CooldownValuesTest`, `CooldownRulesTest` (renamed group) | 4 |
| AC-5 | 4.1-4.3, 5 | `CooldownRulesTest`, `CooldownScaleTest` | 5 |
| AC-6 | 4.2, section 1 | `CooldownRulesTest` | 6 |
| AC-7 | 5.3 | wiring (`NotificationWorker.kt`, the `if` branch) | 7 |
| AC-8 | 6.1, 6.2 | `SkipReportsTest`, resources | 8 |
| AC-9 | 6.3 | `CooldownRulesTest` (`forced`), wiring | 9 |
| AC-10 | 4.4 | `SkipReportsTest` | 10 |
| AC-11 | 5.2 | `CooldownEqualsTodayTest` | 11 |
| AC-12 | 8 | `RefreshCooldownResourcesTest` | 12 |

## 15. Order of work

Three `code(refresh-cooldown):` commits; each builds alone.

1. The four plain files, `CooldownStore.kt`, `RoomCooldownData.kt`, `CooldownRefresh.kt`,
   the values file; `FeedDAO.kt`, `FeedLoadManager.kt`, `FeedLoadService.kt`. Values cannot
   be set yet: behaviour as today.
2. `SkippedMessageUi.kt`; `FeedFragment.kt`. The message and "Refresh anyway" work.
3. `CooldownChooser.kt`, `GroupCooldownUi.kt`, `SubscriptionCooldownUi.kt`, the layout;
   `FeedGroupDialog.kt`, `SubscriptionFragment.kt`. Then the device script.

## 16. Risks

1. **The message a moment early.** The report is published just before "Processing feed…".
   A page that handles an idle state in that moment shows the message before the refresh has
   ended; "Refresh anyway" tapped within that moment is dropped by the service. Publishing
   after the result event could lose the message instead.
2. **"Refresh anyway" while another manual refresh runs** (one started in the eight
   seconds the message shows) does nothing, and the report is already taken. The
   notification check does not use the service and does not block it.
3. **The row in the group dialog** is placed by code in a layout of fixed height; not seen
   on a device before the code is written. Instrumented test and device step 2; landscape on
   a small screen may need scrolling that the dialog does not offer.
4. **Rotation** in the group dialog discards a choice not yet saved.
5. **A value is lost** (falls back to *Use default*) when a group's name or a subscription's
   stored address changes outside the group dialog, for example by upstream's debug database
   sync (`sync/DebugSync.kt`).
6. **The error snackbar** can be cut short when accessibility settings lengthen it beyond
   3.5 s; then the skipped message replaces it.
7. **Upstream changes the choices**: a stored value that is no longer a choice counts as
   *Use default* everywhere and is dropped at the next write.
8. **Whole scope in memory** at every manual refresh (1600 rows with descriptions), as a
   refresh after a day loads today.
9. **Stale entries** are never removed; they cost bytes only.
10. **The fourth menu entry comes after "Unsubscribe"**; putting it before would change
    upstream's index lines.

## Amendment, 2026-11-25: on upstream 5.6.0

Upstream 5.6.0 trims every stored batch itself (a `.map` with `forNotification()` right after
the stored batch is counted), and `startLoading` now returns `Single<List<FeedUpdateInfo>>`.
`subscription-scale` withdrew its part there (its plan, amendment of 2026-11-25): the
`retainNewStreams` parameter, its retention line and the `retainNewStreams = true` argument in
`NotificationWorker.kt` are gone. This feature's five lines in `FeedLoadManager.kt` keep their
text and their meaning; only the neighbours named in section 9 change:

- `cooldownForced` directly follows `ignoreOutdatedThreshold: Boolean = false,` and is still
  the last parameter, directly before `): Single<List<FeedUpdateInfo>> {`.
- `.doOnSuccess { cooldown.done() }` is still directly after `.toList()` and directly before
  the `postProcessFeed()` line. Before `.toList()` the chain is now: `DatabaseConsumer`,
  `batchStored()`, upstream's trimming, `.subscribeOn(Schedulers.io())`.
- `NotificationWorker.kt` is still not edited; its call is upstream's
  `startLoading(ignoreOutdatedThreshold = true, groupId = GROUP_NOTIFICATION_ENABLED)`.
- The list handed to `done()` and to the notification check holds only the channels with new
  streams, trimmed. `done()` does not read it (the report comes from `select`), so nothing in
  sections 5 and 6 changes.

For the wiring test: `subscription-scale` has no marked line in `FeedLoadManager.kt` or
`NotificationWorker.kt`; the places above replace those of section 9 and of the table in
section 13 that name `retainNewStreams` or the retention line.
