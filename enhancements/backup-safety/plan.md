# Backup safety: plan

Covers AC-1 to AC-9 of `spec.md`. Paths are under `app/src/main/java/org/schabi/newpipe/`
unless they start with `app/` or `res/`. Line numbers are those of `origin/dev` `cd87fc7`.
Nothing here was run; every statement about today's behaviour is from reading the code.

## 1. How it works today, and the findings

**Export** (`settings/BackupSettingsFragment.java:362-375`): checkpoints Room, then
`ContentSettingsManager.exportDatabase` (`settings/ContentSettingsManager.kt:33-53`) zips
`newpipe.db`, a Java-serialized `preferences.all` (`newpipe.settings`, line 40) and the same map
as JSON (`preferences.json`, lines 51 and 119-123). "Export settings" writes only that JSON
(`:57-61`). The map is the whole default preferences file.

**Scheduled backup** (`settings/ScheduledBackupWorker.kt:49-54`): the same `exportDatabase`
call from a worker, so the same zip.

**Import** (`BackupSettingsFragment.java:377-419`, on the main thread): `isValidZipFile`, then
`extractDb` (`ContentSettingsManager.kt:87-96`) -> `ZipHelper.extractFileFromZip`
(`util/ZipHelper.java:82-98`), which deletes `databases/newpipe.db` and streams the entry into
the same path. Then it asks "also import settings?"; OK runs `loadSharedPreferences`
(`:109-119`, `clear()` plus puts, errors swallowed). `finishImport` (`:426-431`) restarts
through `NavigationHelper.restartApp` (`util/NavigationHelper.java:712-716`: closes Room, then
ProcessPhoenix kills the process).

| Finding | Verdict |
|---|---|
| D1 | **Confirmed.** Delete-then-write in place (`ZipHelper.java:86-98`); nothing is validated; Room is never closed before the write (only `restartApp` closes it, afterwards). The stale `-wal`/`-shm` part is **half true**: they are deleted after a successful extract (`ContentSettingsManager.kt:89-93`), but an exception mid-extract skips that and leaves a truncated database next to the old WAL. |
| D3 | **Confirmed by reading.** `NewPipeDatabase.java:22-26` registers 12 migrations and no destructive fallback. From 7 and 8 the only edges go down to 6 (`database/Migrations.java:251`, `:292`); Room looks for an edge upwards, finds none and throws at the first open. Any version above 902, 0, or 10 to 899 has no path either. 9 is fine (`MIGRATION_9_900`, `:385`). Room's path search is quoted from memory of Room 2.8, it is not in this repository; the device script confirms it. |
| S2 | **Confirmed**, with a wrong path in the finding: the https check is in `settings/PluginSettingsFragment.kt:283-288`. `plugin/MissingPluginHelper.kt:68` takes the plugin record from the `plugin_services` table of the (imported) database, `:123-138` shows only its name, and `:196-214` fetches `repositoryUrl` unchecked. The package is verified against a key from that same repository, so a first install is not protected. Legacy ids 1-4 always use the built-in address. In release builds plain http already fails at the platform (`res/xml/network_security_config.xml`, cleartext off), so the real exposure is an https address of the attacker's that is never shown. |
| S3 | **Confirmed.** `preferences.all` is exported unfiltered at `ContentSettingsManager.kt:40` and `:121`. `app/src/main/AndroidManifest.xml:53` has `allowBackup="true"`, and no `fullBackupContent` or `dataExtractionRules` exist. |

## 2. Import (AC-1, AC-2, AC-3)

### Design: stage, validate, commit a marker, swap during the restart

The live database, its companion files and the settings are **not touched while the app
runs**. The import writes new files next to them, validates them, and records the decision in
a marker file. The swap happens at the next process start, before anything opens the database.
Reasons: Room cannot be closed reliably in a running app (player, feed and workers reopen it
at any moment), and a swap of several files plus the settings cannot be atomic, so it needs a
journal that a restart can finish or undo. The import already restarts the app (AC-3).

Files, all in `databases/`:

| File | Meaning |
|---|---|
| `newpipe.db.import`, `preferences.json.import`, `newpipe.settings.import` | staged database and staged settings (JSON preferred, else the legacy entry) |
| `import.commit` / `import.rollback` / `import.restore` / `import.done` | the marker; its name is the phase, changed by rename |
| `newpipe.db.old`, `newpipe.db-wal.old`, `newpipe.db-shm.old`, `newpipe.db-journal.old` | the previous database, kept until the import is complete |

**While the app runs** (`BackupImportUi.start`, work on `Schedulers.io`):

1. If no marker exists, delete leftover staged files.
2. Read the zip once, to its end (`ZipStaging.extract`): `newpipe.db` -> `newpipe.db.import`,
   `preferences.json` or else `newpipe.settings` -> its `.import` file; `fd.sync()` each.
   `ZipInputStream` checks every entry's CRC, so a truncated or altered zip throws. Refusals:
   no `newpipe.db` entry -> `NOT_A_BACKUP`; any `IOException` (also a full disk) -> `DAMAGED`.
3. Validate the staged database without Room:
   - `SqliteHeader.parse`: magic `SQLite format 3\0`, page size (offset 16) a power of two,
     file length a multiple of it and not less than the page count at offset 28 (when the
     counters at 24 and 92 agree), `user_version` at offset 60. Failure -> `NOT_A_DATABASE`.
   - `SchemaVersions.canOpen(userVersion)`. Failure -> `VERSION_UNSUPPORTED(version)`.
   - `DatabaseProbe.quickCheck`: `SQLiteDatabase.openDatabase(staged, OPEN_READONLY)` with an
     error handler that does nothing (the default one deletes the file), `PRAGMA quick_check`
     returns `ok`, table `room_master_table` exists; close; delete the probe's side files.
     Failure -> `NOT_A_DATABASE`.
4. Validate staged settings: the JSON parses as an object, or the legacy entry deserializes to
   a map through `PreferencesObjectInputStream`. Failure -> `SETTINGS_UNREADABLE`.
5. Any refusal: delete the staged files, show a dialog naming the reason and "Nothing was
   changed" (AC-2). Otherwise, if settings were staged, show upstream's question
   (`R.string.import_settings`): OK keeps the staged settings, Cancel deletes them (database
   only, as today), Back aborts the import and deletes all staged files.
6. `ImportJournal.commit()` creates `import.commit` (synced). Then the fragment's own
   `finishImport` runs: saves the path, restarts (AC-3).

**At process start** (`BackupImportStartup.run`, first line of the main process's
`App.onCreate` after the phoenix check at `App.java:103-107`, before `initSettings`):
`ImportJournal.recover` acts on the marker.

- No marker: delete staged and `.old` leftovers. Nothing else.
- `import.commit` (roll forward):
  a. while `newpipe.db.import` exists: rename each existing live file (`newpipe.db`, `-wal`,
     `-shm`, `-journal`) to its `.old` name;
  b. rename `newpipe.db.import` -> `newpipe.db`; if no live database exists now -> roll back;
  c. `verifyOpen`: `NewPipeDatabase.close()`, then open through Room
     (`getInstance(ctx).openHelper.writableDatabase`), which runs the migrations and the
     identity check. An exception -> close, roll back;
  d. if staged settings exist: snapshot `prefs.all`, apply them with
     `ContentSettingsManager.applyStagedSettings`. An exception or a failed `commit()` -> put
     the snapshot back, roll back;
  e. rename the marker to `import.done`, delete the `.old` and staged files, delete the marker.
- `import.rollback`: delete the live `newpipe.db`, `-wal`, `-shm`, `-journal` (they belong to
  the new database); rename the marker to `import.restore`.
- `import.restore`: rename every existing `.old` file back; delete staged files and the marker.
  A toast says the import failed and the previous data is back.
- `import.done`: finish the deletions of step e.

Every step tests for the file it acts on, so `recover` can be re-run from any point.

### "A version the app can open"

`SchemaVersions` holds `CURRENT = 902` and the registered edges `1-2, 2-3, 3-4, 4-5, 5-6, 7-6,
8-6, 9-6, 6-900, 9-900, 900-901, 901-902`. `path(from, to)` repeats Room's search: from the
current version take the registered edge with the highest target that is above the current
version and not above `to`; stop with "no path" when there is none (no backtracking).
`canOpen(v)` is `v == CURRENT`, or `1 <= v < CURRENT` and a path exists. Result: 1-6, 9, 900,
901, 902 open; 0, 7, 8, 10-899 and everything above 902 are refused. The version comes from
the file header, so neither Room nor SQLite is involved. A unit test reads
`NewPipeDatabase.java` and `database/AppDatabase.java` as text and fails when the constants
drift from `addMigrations(...)` or `version = DB_VER_n`. Step c is the second line of defence
for what a version number cannot show (a foreign schema with the same version, a migration
that fails on unexpected content).

### Why each failure point leaves the old state

| Moment | State afterwards |
|---|---|
| Truncated or damaged zip, full disk while staging, failed validation | Only `.import` files were written; they are deleted. Live files never opened for writing. |
| Kill before `import.commit` exists | Same; leftovers are deleted at the next start. |
| Kill after `import.commit`, at any later step | The next start continues from the marker; the import completes. |
| Room cannot open the new database (c) | New files deleted, `.old` set renamed back, settings not yet touched. |
| Settings cannot be written (d) | Snapshot put back in memory, the preferences file on disk still holds the old content, database rolled back. |
| Kill during a rollback | The marker phase (`rollback`, `restore`) says which half is left. |

Renames need no free space. The `.old` set moves as a whole including an uncheckpointed WAL,
so a process that was killed with Room open loses nothing on rollback.

## 3. Plugins after an import (AC-4, AC-5)

`MissingPluginHelper.kt:158` is redirected: Install opens `PluginInstallConfirm.show` before
anything is fetched. The dialog lists every plugin as name plus its **full** repository
address on its own line, with Install / Cancel; only then upstream's `ensureEnabled` and
`install` run, with the allowed plugins only. `RepositoryAddress.isAllowed(url, allowPlainHttp)`
is the settings rule (`https://` prefix; `http://` only when `BuildConfig.DEBUG`) and also
rejects addresses with whitespace or control characters, so the shown text cannot hide a part
of itself. Refused plugins are listed in the same dialog under "Not installed, because the
address is not https", with their addresses; if none is allowed the dialog has only OK. The
dialog is shown for every address, the built-in one included: the code cannot tell which
records came from an import.

## 4. Secrets (AC-6 to AC-9)

### Keys that count as login data (`LoginData`)

Found by reading every key in `res/values/settings_keys.xml` and every preferences file the
code opens. Values are the literal key strings (each resource's value equals its name).

| Group | Keys |
|---|---|
| Secrets | `youtube_cookies_key`, `bilibili_cookies_key`, `niconico_cookies_key`, `override_cookies_youtube_value_key`, `override_cookies_bilibili_value_key`, `override_cookies_niconico_value_key`, `proxy_token_key`, and two leftovers of older versions that can still be present: `recaptcha_cookies_key` (`error/ChallengeCookies.kt:43-54`), `youtube_po_token_key` (`settings/PipePipeMigrations.java:34`) |
| Switches that only mean something with them | `override_cookies_youtube_key`, `override_cookies_bilibili_key`, `override_cookies_niconico_key`, `enable_proxy_key` |

Reviewed and **not** login data: `clear_cookie_key`, `login_key`, `logout_key`,
`check_token_validity_key` (buttons); `cookie_functions_bilibili_key`,
`cookie_functions_niconico_key`, `login_session_refresh_time`, `logout_keep_webview_session`
(ordinary settings); `youtube_visitor_data` (no account); `sponsor_block_user_id` and
`debug_sync_server` (see Q3). Outside the default preferences, never written by the app's
export: `plugin_<id>` (plugin settings and plugin login cookies), `challenge_cookies`,
`login_sessions`, `plugin_state`, `plugin_guide`, `debug_sync`, `youtube-player-cache`, and the
WebView cookie store. Only Android's own backup reaches those.

### Export (AC-6, AC-9)

`ContentSettingsManager` gets a property `includeLoginData`, default `false`. Both places that
write `preferences.all` (`:40`, `:121`) write `LoginData.forExport(preferences.all,
includeLoginData)` instead, which drops both groups unless the flag is set. The result is a
`java.util.HashMap`, because readers of `newpipe.settings` accept only that class
(`settings/PreferencesObjectInputStream.kt:22-35`).

The tick box: "Export database" and "Export settings" (`BackupSettingsFragment.java:112`,
`:182`) each get one line at the top of their click listener,
`ExportOptionsUi.intercept(p, manager)`. It shows a dialog with the unticked box "Include
login data", the warning text and Export / Cancel. Export sets `manager.includeLoginData` to
the box and calls the same listener again, which now falls through to the file picker. Cancel
does nothing; no file is created.

`ScheduledBackupWorker` builds its own manager and is not edited, so its flag is always
`false` (AC-9). A unit test pins that the worker's source does not mention the property.

### Import (AC-7)

`LoginData.hasLoginData(imported)`: some key of the Secrets group has a non-blank string
value. (Presence of a key is no signal: `setDefaultValues` writes `proxy_token_key=""`.)

- Backup **without** login data: every key of both groups keeps its current value and type,
  and keys that are absent now stay absent. Everything else is replaced as today.
- Backup **with** login data: all keys are replaced by the backup's, as today.

One hook each in `applyJson` and `applyLegacy`, after their loop and before the commit:
`LoginDataPrefs.keepCurrent(preferences, editor, imported)`. An editor applies `clear()` first
and puts afterwards, so re-putting the current values works. This covers the full import
(through `applyStagedSettings`) and "Import settings".

### Android's own backup and device transfer (AC-8)

Backup rules select files, not keys, and the cookies and the token live in the same file as
every other setting. The rules are therefore an allow-list: only the database. Plugins
(`files/plugins`, `plugin_state`), all preferences files, the WebView data and the staging
files are left out. See Q2. `allowBackup` stays `true`.

`res/xml/enhancements_backup_rules.xml` (Android 6 to 11, `android:fullBackupContent`):

```xml
<full-backup-content> <!-- ENH:backup-safety -->
    <include domain="database" path="newpipe.db" />
    <include domain="database" path="newpipe.db-wal" />
    <include domain="database" path="newpipe.db-shm" />
    <include domain="database" path="newpipe.db-journal" />
</full-backup-content>
```

`res/xml/enhancements_data_extraction_rules.xml` (Android 12+, `android:dataExtractionRules`):
`<data-extraction-rules>` with `<cloud-backup>` and `<device-transfer>`, each holding the same
four `<include>` lines.

## 5. Edits to upstream files (16 marked lines in 5 files)

Every line ends with `// ENH:backup-safety`. No line is removed without a replacement.

| File | Lines | Edit |
|---|---|---|
| `App.java` | +1 after `:107` | `org.schabi.newpipe.enhancements.backupsafety.BackupImportStartup.run(this);` |
| `settings/BackupSettingsFragment.java` | +2, 1 changed | after `:112` and after `:182`: `if (ExportOptionsUi.intercept(p, manager)) { return true; }` (fully qualified). `:354` `importDatabase(file, lastImportDataUri))` becomes `BackupImportUi.start(this, file, () -> finishImport(lastImportDataUri)))`. Upstream's `importDatabase` stays as unused code. |
| `settings/ContentSettingsManager.kt` | +6, 3 changed | two imports (`LoginData`, `LoginDataPrefs`); property `var includeLoginData = false`; `:40` and `:121` write `LoginData.forExport(preferences.all, includeLoginData)`; `LoginDataPrefs.keepCurrent(preferences, editor, json)` after `:141` and `...keepCurrent(preferences, editor, entries)` after `:168`; `:169` `editor.commit()` becomes `if (!editor.commit()) throw IOException("Unable to commit preferences")`; new one-line method `@Throws(Exception::class) fun applyStagedSettings(preferences: SharedPreferences, file: java.io.File, legacy: Boolean) = FileInputStream(file).use { if (legacy) applyLegacy(preferences, it) else applyJson(preferences, it) }` |
| `plugin/MissingPluginHelper.kt` | +1, 1 changed | import; `:158` becomes `PluginInstallConfirm.show(activity, installable.map { it.plugin!! }) { ok -> ensureEnabled(activity) { install(activity, ok) } }` |
| `app/src/main/AndroidManifest.xml` | 1 changed | `:63` becomes `android:fullBackupContent="@xml/enhancements_backup_rules" android:dataExtractionRules="@xml/enhancements_data_extraction_rules" tools:ignore="AllowBackup,ENH:backup-safety">`. A start tag cannot hold a comment, so the marker rides in `tools:ignore`, which the manifest merger passes over and lint (not aborting, `app/build.gradle:92-95`) reports at most as an unknown id. |

Not edited: `util/ZipHelper.java`, `NewPipeDatabase.java`, `database/Migrations.java`,
`settings/ScheduledBackupWorker.kt`, `settings/PluginSettingsFragment.kt`, `strings.xml`.

## 6. New files

Package `org.schabi.newpipe.enhancements.backupsafety`, directory
`enhancements/backupsafety/`.

**Plain Kotlin, no Android imports**

```kotlin
// SqliteHeader.kt
data class SqliteHeader(val pageSize: Int, val userVersion: Int, val pageCount: Long?) {
    companion object {
        const val SIZE = 100
        /** null when [bytes] (the first [SIZE] bytes) and [fileLength] are not a complete SQLite file */
        fun parse(bytes: ByteArray, fileLength: Long): SqliteHeader?
        fun read(file: java.io.File): SqliteHeader?
    }
}

// SchemaVersions.kt
object SchemaVersions {
    const val CURRENT: Int = 902
    val MIGRATIONS: List<Pair<Int, Int>>
    fun path(from: Int, to: Int = CURRENT, migrations: List<Pair<Int, Int>> = MIGRATIONS): List<Pair<Int, Int>>?
    fun canOpen(version: Int): Boolean
}

// ZipStaging.kt
object ZipStaging {
    /** Copies the named entries to their files and syncs them. Returns the names found. Deletes all targets and rethrows on any error. */
    @Throws(java.io.IOException::class)
    fun extract(zip: java.io.InputStream, targets: Map<String, java.io.File>): Set<String>
}

// ImportJournal.kt
class ImportJournal(private val dbDir: java.io.File, private val dbName: String = "newpipe.db") {
    enum class Phase { NONE, COMMIT, ROLLBACK, RESTORE, DONE }
    enum class Outcome { NOTHING_PENDING, COMPLETED, ROLLED_BACK }
    enum class Step { MOVE_OLD, PROMOTE, VERIFY, APPLY_SETTINGS, MARK, DELETE, RESTORE_OLD }
    val stagedDb: java.io.File
    val stagedSettingsJson: java.io.File
    val stagedSettingsLegacy: java.io.File
    /** called before every single file operation; tests throw from it to imitate a kill */
    var beforeStep: (Step) -> Unit
    fun phase(): Phase
    fun discardStaging()                       // no-op unless phase() == NONE
    @Throws(java.io.IOException::class) fun commit()
    fun recover(verifyOpen: () -> Boolean, applySettings: (file: java.io.File, legacy: Boolean) -> Boolean): Outcome
}

// BackupStaging.kt
sealed class StageResult {
    data class Staged(val hasSettings: Boolean) : StageResult()
    data class Refused(val reason: Reason, val version: Int? = null) : StageResult()
    enum class Reason { NOT_A_BACKUP, DAMAGED, NOT_A_DATABASE, VERSION_UNSUPPORTED, SETTINGS_UNREADABLE }
}
class BackupStaging(private val journal: ImportJournal, private val probe: (java.io.File) -> Boolean) {
    /** steps 1 to 4 of section 2; on refusal nothing staged remains */
    fun stage(openZip: () -> java.io.InputStream): StageResult
    fun dropSettings()
}

// LoginData.kt
object LoginData {
    val SECRET_KEYS: Set<String>
    val SWITCH_KEYS: Set<String>
    val ALL_KEYS: Set<String>
    val REVIEWED_NOT_LOGIN: Set<String>
    fun forExport(all: Map<String, *>, includeLoginData: Boolean): java.util.HashMap<String, Any?>
    fun hasLoginData(imported: Map<*, *>): Boolean
    /** entries of [current] to write back after an import; empty when [imported] has login data */
    fun keptOnImport(imported: Map<*, *>, current: Map<String, *>): Map<String, Any?>
}

// RepositoryAddress.kt
object RepositoryAddress {
    data class Offer(val pluginName: String, val repositoryUrl: String)
    data class Split(val allowed: List<Offer>, val refused: List<Offer>)
    fun isAllowed(url: String, allowPlainHttp: Boolean): Boolean
    fun split(offers: List<Offer>, allowPlainHttp: Boolean): Split
    /** one "name\nfull address" block per offer, in order */
    fun lines(offers: List<Offer>): List<String>
}
```

**Android glue**

```kotlin
object BackupImportUi {      // BackupImportUi.kt
    @JvmStatic fun start(fragment: androidx.fragment.app.Fragment, file: StoredFileHelper, onStaged: Runnable)
}
object BackupImportStartup { // BackupImportStartup.kt
    @JvmStatic fun run(context: android.content.Context)
}
object DatabaseProbe {       // DatabaseProbe.kt
    fun quickCheck(file: java.io.File): Boolean
}
object LoginDataPrefs {      // LoginDataPrefs.kt
    @JvmStatic fun keepCurrent(preferences: SharedPreferences, editor: SharedPreferences.Editor, imported: Map<*, *>)
}
object ExportOptionsUi {     // ExportOptionsUi.kt
    @JvmStatic fun intercept(preference: androidx.preference.Preference, manager: ContentSettingsManager): Boolean
}
object PluginInstallConfirm { // PluginInstallConfirm.kt
    @JvmStatic fun show(activity: android.app.Activity, plugins: List<PluginServiceEntity>, proceed: (List<PluginServiceEntity>) -> Unit)
}
```

**Resources**: the two rule files of section 4 and `res/values/enhancements_backup_safety.xml`:

| Name | English text |
|---|---|
| `enh_backup_safety_checking` | Checking the backup… |
| `enh_backup_safety_refused_title` | Backup not imported |
| `enh_backup_safety_refused_not_a_backup` | This file is not a PipePipe backup: it contains no database. |
| `enh_backup_safety_refused_damaged` | The backup file is damaged or incomplete, or there is not enough free storage to unpack it. |
| `enh_backup_safety_refused_not_a_database` | The database in this backup is damaged or incomplete. |
| `enh_backup_safety_refused_version` | The database in this backup has version %1$d. This version of the app cannot open it. |
| `enh_backup_safety_refused_settings` | The settings in this backup cannot be read. |
| `enh_backup_safety_nothing_changed` | Nothing was changed. |
| `enh_backup_safety_rolled_back` | Import failed: the backup could not be opened. Your previous data was restored. |
| `enh_backup_safety_export_title` | Export |
| `enh_backup_safety_include_login` | Include login data |
| `enh_backup_safety_include_login_warning` | Login cookies and the proxy token are written to the file in clear text. Anyone with the file can use your accounts. |
| `enh_backup_safety_export_button` | Export |
| `enh_backup_safety_plugin_confirm_title` | Install plugins from these addresses? |
| `enh_backup_safety_plugin_confirm_intro` | A plugin runs inside the app and can read your logins. Install only from addresses you trust. |
| `enh_backup_safety_plugin_refused` | Not installed, because the address is not https: |

## 7. Verification

**JVM unit tests** (JUnit 4, temporary directories, `app/src/test/.../enhancements/backupsafety/`;
the working directory of the test task is `app/`, so sources are read as `src/main/...`):

- `ImportJournalTest` (AC-1): for every n, throw from `beforeStep` at call n, run `recover`
  again on a new instance, and assert the directory holds either exactly the old or exactly
  the new database with matching companions and no leftovers; the same with `verifyOpen` and
  `applySettings` returning false; no marker means live files are byte-identical.
- `ZipStagingTest`, `BackupStagingTest` (AC-1, AC-2): truncated zip, flipped byte (CRC), no
  database entry, each `Reason`, nothing staged after a refusal.
- `SqliteHeaderTest` (AC-2): hand-built headers; wrong magic, short file, length below the
  page count, version read.
- `SchemaVersionsTest` (AC-2): the openable and refused versions listed in section 2; drift
  test against `NewPipeDatabase.java` and `AppDatabase.java`.
- `LoginDataTest` (AC-6, AC-7, AC-9): default export drops both groups and returns a
  `java.util.HashMap`; ticked export keeps them; the import rules; every key equals its value
  in `settings_keys.xml`; every key there whose name contains `cookie`, `token`, `password`,
  `secret` or `credential` is in `ALL_KEYS` or `REVIEWED_NOT_LOGIN`; `ScheduledBackupWorker.kt`
  does not contain `includeLoginData`.
- `RepositoryAddressTest` (AC-4, AC-5): https only, http only with the flag, whitespace
  refused, `lines` contain the complete address.
- `BackupRulesTest` (AC-8): the two rule files parse and contain only `database` includes of
  `newpipe.db*`; the manifest names both files.
- `HookLinesTest`: the marked-line count per upstream file equals section 5.

Not unit-testable: `DatabaseProbe`, Room's real behaviour in step c, the dialogs, preferences
writes, the manifest's effect.

**Device script** (debug APK):

1. Export, import the same file: data and settings as before, app restarts (AC-3).
2. Cut the zip to half its size; import: "damaged or incomplete", data unchanged (AC-1, AC-2).
3. Zip whose `newpipe.db` is a text file, and one with `PRAGMA user_version` set to 7 and to
   950 with `sqlite3`: refused, the version message names 7 / 950 (AC-2).
4. Database with version 902 but one table dropped: accepted, app restarts, toast "previous
   data was restored", old data present (AC-1).
5. During a large import run `adb shell am force-stop` while "Checking the backup…" shows, and
   again right after tapping OK on the settings question: first case old data, second case the
   import is complete after the next start (AC-1).
6. Fill the storage (`fallocate`) and import: refused, data unchanged (AC-1).
7. Export without ticking: `unzip -p file.zip preferences.json | grep -c cookies_key` is 0 and
   no token; same for "Export settings"; with the tick the cookies are present (AC-6).
8. Logged in, import the file of step 7 with settings: still logged in (AC-7).
9. Scheduled backup with a short interval: its zip has no login data (AC-9).
10. Import a backup whose `plugin_services` row has an `https://example.org/...` address and
    one with `http://`: the dialog shows the full address; the http one is listed as not
    installed (AC-4, AC-5).
11. `adb shell bmgr backupnow <package>` then `bmgr restore` on a wiped install (Android 12+
    and one older device or emulator): subscriptions are back, not logged in, no plugins (AC-8).

## 8. AC coverage

| AC | Covered by |
|---|---|
| AC-1 | `ImportJournal`, `ZipStaging`, `BackupImportStartup`; section 2 |
| AC-2 | `SqliteHeader`, `SchemaVersions`, `DatabaseProbe`, `BackupStaging`, refusal dialog |
| AC-3 | `BackupImportUi` calls the fragment's `finishImport`; settings question kept |
| AC-4 | `PluginInstallConfirm`, `RepositoryAddress.lines` |
| AC-5 | `RepositoryAddress.isAllowed` / `split` |
| AC-6 | `LoginData.forExport`, `includeLoginData`, `ExportOptionsUi` |
| AC-7 | `LoginData.keptOnImport`, `LoginDataPrefs.keepCurrent` hooks |
| AC-8 | the two rule files, manifest line |
| AC-9 | default `false`, worker unedited, pinned by a test |

## 9. Order of work

1. Plain Kotlin: `LoginData`, `RepositoryAddress`, `SqliteHeader`, `SchemaVersions`,
   `ZipStaging`, `ImportJournal`, `BackupStaging`; unit tests green.
2. Strings and rule files; manifest line (AC-8 done).
3. `ContentSettingsManager` edits, `LoginDataPrefs`, `ExportOptionsUi` and its two hooks
   (AC-6, AC-7, AC-9 done).
4. `PluginInstallConfirm` and the `MissingPluginHelper` line (AC-4, AC-5 done).
5. `DatabaseProbe`, `BackupImportUi`, `BackupImportStartup`, the `App.java` line and the
   import redirect last, because they only work together (AC-1 to AC-3 done).
6. Device script.

## 10. Risks

- **Room's path search is reproduced, not called.** If it differs, a version is wrongly
  accepted or refused. Step c catches wrong acceptance; device step 3 checks 7 and 950.
- **Work started before `App.onCreate`.** WorkManager is initialized by a provider and may
  start a worker that opens Room while the swap runs (only at the one start after an import).
  `verifyOpen` closes that instance first; such a worker can then fail once with "database
  closed".
- **Power loss** (not a process kill) can lose renames that were not yet on disk; directories
  are not synced.
- **A failed preferences write** leaves the in-memory preferences changed; the snapshot is put
  back, which the code layer must test on a device with a full disk.
- **Old settings in a backup** (PipePipe settings version below 2) make upstream's migration
  clear the YouTube cookie at start (`PipePipeMigrations.java:29-36`), also when AC-7 kept it.
- **A recreated settings screen** between ticking the box and choosing the file loses the
  tick; the export is then without login data (the safe side).
- **Manifest marker in `tools:ignore`.** If a build tool rejects it, the fallback is a
  namespace declaration `xmlns:enh="ENH:backup-safety"` plus an `enh:` attribute; ask before
  using it.
- **`importDatabase`, `extractDb` and `ZipHelper.extractFileFromZip` for the database stay in
  upstream as unused, unsafe code**; `HookLinesTest` pins that the redirect is in place.
- **Settings no longer travel with Android's backup** (Q2), and three smaller changes of
  behaviour (Q5).
