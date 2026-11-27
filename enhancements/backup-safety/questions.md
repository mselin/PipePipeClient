# Questions: backup-safety

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "backup-safety" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-8: Android's backup rules select whole files. The login cookies and the proxy token are in
the same preferences file as every other setting, and plugin logins are in further preferences
files. Planned with: Android's own backup and device transfer carry only the database; no
settings at all travel that way (the app's own export remains for settings). Is that
acceptable, or must settings without login data still be transferred (that needs a custom
backup agent, a larger change)?

## Q3 plan -> spec
AC-6 names "login cookies and the proxy token". Two more stored values are credential-like:
the SponsorBlock private user id (`sponsor_block_user_id`, lets its holder submit as the user)
and the sync server address (`debug_sync_server`). Planned with: both are ordinary settings and
stay in every export. The plan's section 4 lists all keys treated as login data, including the
on/off switches that only mean something together with a cookie or the token. Should the
SponsorBlock id be login data too?

## Q4 plan -> spec
AC-7 does not say what "a backup without login data" is, and backups made before this fix have
no flag. Planned with: a backup has login data when at least one cookie or token value in it is
not empty. Without login data, all current logins and their switches are kept. With login data,
all of them are replaced by the backup's, as today, which also logs out of a service the backup
has no cookie for. Known gap: a backup whose settings are older than PipePipe settings version
2 makes upstream's own migration clear the YouTube cookie at the next start.

## Q5 plan -> spec
AC-3 says a successful import behaves as today. Planned with three differences in the cases
around it: (a) pressing Back on "Do you want to also import settings?" cancels the whole import
(today the database is already replaced and the app does not restart); (b) a backup whose
settings entry cannot be read is refused as a whole (today the settings are skipped silently
and the database is imported); (c) the database is exchanged during the restart, so a backup
that passes the checks but cannot be opened is reported by a message after the restart, with
the previous data restored. Are these acceptable?

## A2 spec
Accepted: Android's own backup and device transfer carry the database only, no settings.
That meets AC-8; losing the other settings there is the price of whole-file rules.

## A3 spec
Accepted: the SponsorBlock private user id and the sync server address are not login data.

## A4 spec
Accepted: a backup is "without login data" when it has no non-empty cookie or token value.
A backup with login data replaces all logins, as today.

## A5 spec
Accepted, all three: Back on the settings question cancels the whole import; unreadable
settings refuse the whole import; a backup that passes the checks but cannot be opened is
reported after the restart with the old data restored. AC-3's "behaves as today" means the
successful case.

A2 to A5 were decided by the session on the owner's behalf; the owner can overrule them.

## Q6 tests -> plan
Section 2, step 2: "`ZipInputStream` checks every entry's CRC, so a truncated or altered zip
throws." That holds for a cut or change inside an entry's data. A streaming reader does not
notice a cut exactly between two entries, or inside the directory at the end of the archive:
the entries before the cut are complete and their CRCs are right. A backup cut right after
`newpipe.db` would be imported as a database-only backup, without the settings question.
Tested with: cuts and altered bytes inside entry data only (`ZipStagingTest`,
`BackupStagingTest`); no test expects a refusal for a cut at an entry boundary or in the
directory. Should the import also require the archive's end record?

## Q7 tests -> plan
Section 4, import: "keys that are absent now stay absent". `LoginData.keptOnImport` returns only
entries to write back, so it cannot say "remove". If a backup without login data still holds a
switch (for example `enable_proxy_key=true` in an edited file, or `proxy_token_key=""`, which
every backup has) and the key is absent now, the backup's value stays after
`LoginDataPrefs.keepCurrent` unless the glue also removes the login keys that are absent now.
That removal is a decision made in Android code, which no JVM test reaches.
Tested with: `keptOnImport` returns exactly the current entries of the login keys, nothing for
absent keys (`LoginDataTest`). The removal is untested; it needs a device check.

## Q8 tests -> plan
Section 2, start-up step d and the table "Why each failure point leaves the old state": after a
kill between "settings applied" and "marker renamed to `import.done`", the next start runs
steps c and d again. If `verifyOpen` or `applySettings` fails on that second run, the database
is rolled back while the settings on disk are already the backup's, and the snapshot of the old
settings died with the first process. AC-1 does not hold for that sequence.
Tested with: the second run succeeds (`ac1_killRightAfterTheSettingsWereApplied_nextStartCompletes`);
no test demands the old settings after kill-then-failure, because `ImportJournal` as planned
cannot provide them.

## Q9 tests -> plan
Section 4 says of the key lists "each resource's value equals its name". True for the 13 login
keys (pinned by `ac6_everyLoginKeyEqualsItsValueInSettingsKeys`). Not true for the reviewed
list: `clear_cookie_key` is a name whose value is `clear_cookie`, and
`login_session_refresh_time`, `logout_keep_webview_session`, `sponsor_block_user_id` and
`debug_sync_server` are values whose names end in `_key`. `REVIEWED_NOT_LOGIN` therefore mixes
names and values. Tested with: the list as the plan writes it; the scan of `settings_keys.xml`
accepts a key when its name or its value is listed. No effect on behaviour.

## Q10 tests -> plan
Section 7 proposes no instrumented tests, so none were written; `DatabaseProbe`, the editor
order in `LoginDataPrefs.keepCurrent`, Room in `verifyOpen` and the backup rules are covered by
the device script only. Two additions beyond section 7's list: `GlueSourceTest` reads the six
glue files as text (staging on `Schedulers.io()`, the probe's own error handler, no call of
upstream's in-place import, the calls into the logic classes), and `BackupSafetyStringsTest`
reads the strings file. `HookLinesTest` asserts section 5's counts exactly (1, 3, 9, 2, 1); the
9 of `ContentSettingsManager.kt` presumes `applyStagedSettings` is written on one line, as the
plan words it.

## A6 plan
No. The import reads a stream, and requiring the end record would mean copying the whole
archive to a file first. A zip cut exactly after a complete, CRC-correct database entry is
imported as a database-only backup: the database is whole and passes every check, and the
current settings are kept. Recorded as a known limit; the pull request states it.

## A7 plan
The glue removes them: `LoginDataPrefs.keepCurrent` writes back the entries `keptOnImport`
returns and calls `remove` for every login key that is absent now. The rule "which keys" stays
in `LoginData` (its key list); only the `remove` calls are in Android code. Untested on the
JVM; it becomes a device step (import a backup without login data that was edited to hold a
proxy switch, check the switch is off afterwards).

## A8 plan
Accepted as a known limit. The sequence needs a kill in the few milliseconds between two
steps and then a failure of a database that had just opened successfully. Closing it would
mean journalling the old settings to disk as well. Recorded in the plan's risks; the pull
request states it.

## A9 plan
Accepted. The wording "value equals name" applies to the 13 login keys only.

## A10 plan
Accepted, including the two extra test classes and the one-line presumption.

## Q11 code -> tests
`HookLinesTest.ac6_ac9_wiring_exportsWriteTheFilteredPreferences` expects the marked lines of
`ContentSettingsManager.kt` to contain `import org.schabi.newpipe.enhancements.backupsafety.LoginData`
exactly 1 time. Plan section 5 and the test's own table ask for two imports, `LoginData` and
`LoginDataPrefs`, and the second contains the first as a prefix, so the count is 2 and cannot
be 1 while `ac7_wiring_importsKeepTheCurrentLogins` also expects the `LoginDataPrefs` import.
Written as the plan says (both imports, 9 marked lines); this one test fails at that assertion.
Its other assertions hold (checked by hand: `var includeLoginData = false` 1, both
`LoginData.forExport(preferences.all, includeLoginData)` calls 1 each, `preferences.all` 2 in
the file, `includeLoginData = true` 0). Not done: a wildcard import or an alias that would
make the count 1. Should the expectation be `times = 2`, or the lookup be per line?

## Q12 code -> plan
Section 7, device step 4: "Database with version 902 but one table dropped: accepted, app
restarts, toast 'previous data was restored'". Room 2.8.4 does not do that. Read from the
bytecode of `androidx.room.BaseRoomConnectionManager`: `onOpen` calls only `checkIdentity`,
which compares the hash stored in `room_master_table` and validates the schema only when that
table is missing; `onValidateSchema` runs after `onCreate` and after a migration, not for a
database that already has the current version. A version-902 database with the right hash and
a missing table therefore passes step c, the import completes, and the app fails later at the
first query of that table. A database of an older version is validated after its migrations.
Implemented as planned (`openHelper.writableDatabase`); step c does not compare tables itself,
because the expected schema is reachable only through Room's restricted `RoomOpenDelegate`.
Is that limit accepted (and device step 4 changed to a database with a wrong identity hash or
an older version), or should step c validate the schema through that restricted class?

## A11 tests
The assertion was wrong: the snippet for the `LoginData` import is a prefix of the
`LoginDataPrefs` import, so the plan's two imports count as two. The test now expects two.

## A12 plan
Accepted as a known limit. A backup at the current schema version with the right identity
hash but a missing table passes every check and fails later, at the first query of that
table. Such a file does not come from the app's own export. Upstream's import has no check at
all, so this is no worse than before. Device step 4 is to be done with a backup of an older
version, which Room validates after its migrations. Recorded in the plan's risks; the pull
request states it.
