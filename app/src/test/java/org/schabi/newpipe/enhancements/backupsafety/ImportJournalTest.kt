package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.enhancements.backupsafety.ImportJournal.Outcome
import org.schabi.newpipe.enhancements.backupsafety.ImportJournal.Phase
import org.schabi.newpipe.enhancements.backupsafety.ImportJournal.Step
import java.io.File

/**
 * AC-1: "An import either completes or leaves the database, its companion files and the settings
 * exactly as they were. This holds for [..] a process kill at any moment."
 *
 * [ImportJournal] (plan section 2) on a real temporary `databases/` directory. Every "process
 * start" is a new [ImportJournal] on the same directory, as after a kill. The settings are a
 * string that the fake `applySettings` replaces by the content of the staged file; the fake
 * `verifyOpen` leaves a `-wal` file behind, as Room does when it opens a database.
 *
 * What "exactly" means here, for every test:
 *
 * - **old state**: the directory holds the files it held before the import, byte for byte, and
 *   nothing else (no marker, no `.import`, no `.old`, no `-wal` of the new database); the
 *   settings are the old ones;
 * - **new state**: the directory holds the new `newpipe.db` and the `-wal` its opening created,
 *   and nothing else. In particular no companion file of the old database lies next to the new
 *   one: SQLite would replay an old WAL into the new database. The settings are the new ones
 *   if settings were staged, else the old ones.
 *
 * A kill is imitated as the plan says, by throwing from [ImportJournal.beforeStep]. What is
 * thrown is an [Error], so that an implementation which turns an `Exception` of a step into a
 * rollback does not swallow the "kill".
 */
class ImportJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Kill : Error("simulated process kill")

    private enum class Settings { NONE, JSON, LEGACY }

    private companion object {
        const val DB = "newpipe.db"
        const val WAL = "newpipe.db-wal"
        const val SHM = "newpipe.db-shm"
        const val JOURNAL = "newpipe.db-journal"

        const val NEW_DB = "NEW database from the backup"
        const val NEW_WAL = "wal written while the NEW database was opened"
        const val OLD_SETTINGS = "old settings"
        const val NEW_SETTINGS = "{\"settings\":\"from the backup\"}"

        /** Killed with Room open: an uncheckpointed WAL belongs to the database. */
        val OLD_WITH_WAL = mapOf(DB to "old database", WAL to "old wal, not checkpointed", SHM to "old shm")
        val OLD_PLAIN = mapOf(DB to "old database")
        val OLD_WITH_JOURNAL = mapOf(DB to "old database", JOURNAL to "old rollback journal")
        val OLD_ALL_FOUR = OLD_WITH_WAL + (JOURNAL to "old rollback journal")
        val NO_DATABASE = emptyMap<String, String>()

        val ALL_OLD_SETS = listOf(OLD_WITH_WAL, OLD_PLAIN, OLD_WITH_JOURNAL, OLD_ALL_FOUR, NO_DATABASE)
    }

    /** One `databases/` directory plus the settings, across process starts. */
    private inner class Device(val old: Map<String, String>, val settings: Settings = Settings.JSON) {
        val dir: File = tmp.newFolder()
        var prefs = OLD_SETTINGS
        var verifyCalls = 0
        var applyCalls = 0
        val applied = mutableListOf<Pair<String, Boolean>>()
        val order = mutableListOf<String>()

        init {
            old.forEach { (name, content) -> File(dir, name).writeText(content) }
        }

        fun files(): Map<String, String> = dir.snapshot()

        /** What the running app does before the restart: staged files, then the marker. */
        fun stageAndCommit(): Device {
            stage()
            ImportJournal(dir).commit()
            return this
        }

        fun stage(): Device {
            val journal = ImportJournal(dir)
            journal.stagedDb.writeText(NEW_DB)
            when (settings) {
                Settings.JSON -> journal.stagedSettingsJson.writeText(NEW_SETTINGS)
                Settings.LEGACY -> journal.stagedSettingsLegacy.writeText(NEW_SETTINGS)
                Settings.NONE -> Unit
            }
            return this
        }

        /**
         * One process start: `recover` on a new instance. [duringVerify] and [duringApply] run
         * inside the two callbacks, after their effect, to imitate a kill in the middle of them.
         */
        fun start(
            verify: Boolean = true,
            apply: Boolean = true,
            beforeStep: ((Step) -> Unit)? = null,
            duringVerify: () -> Unit = {},
            duringApply: () -> Unit = {}
        ): Outcome {
            val journal = ImportJournal(dir)
            if (beforeStep != null) {
                journal.beforeStep = beforeStep
            }
            return journal.recover(
                verifyOpen = {
                    verifyCalls++
                    order += "verify"
                    val seen = files()
                    assertEquals(
                        "AC-1: when the new database is opened for verification, the live database must be the new one",
                        NEW_DB,
                        seen[DB]
                    )
                    assertTrue(
                        "AC-1: when the new database is opened, no companion file of the old database may lie " +
                            "next to it (SQLite would replay an old WAL into the new database); directory: ${seen.keys}",
                        seen[WAL].let { it == null || it == NEW_WAL } && SHM !in seen && JOURNAL !in seen
                    )
                    File(dir, WAL).writeText(NEW_WAL)
                    duringVerify()
                    verify
                },
                applySettings = { file, legacy ->
                    applyCalls++
                    order += "apply"
                    applied += file.name to legacy
                    assertTrue("AC-1: applySettings must be given an existing staged file, got ${file.name}", file.isFile)
                    if (apply) {
                        prefs = file.readText()
                    }
                    duringApply()
                    apply
                }
            )
        }

        fun phase(): Phase = ImportJournal(dir).phase()

        fun assertOldState(where: String) {
            assertEquals("AC-1 ($where): the directory must hold exactly the old files", old, files())
            assertEquals("AC-1 ($where): the settings must be the old ones", OLD_SETTINGS, prefs)
        }

        fun assertNewState(where: String) {
            assertEquals(
                "AC-1 ($where): the directory must hold exactly the new database (and the WAL its opening created)",
                mapOf(DB to NEW_DB, WAL to NEW_WAL),
                files()
            )
            assertEquals(
                "AC-1 ($where): the settings must be the backup's if settings were staged, else unchanged",
                if (settings == Settings.NONE) OLD_SETTINGS else NEW_SETTINGS,
                prefs
            )
        }

        /**
         * While an import is neither finished nor absent, every byte of the old database must
         * still be on disk, as the live file or as its `.old` copy.
         */
        fun assertOldDataStillOnDisk(where: String) {
            if (phase() == Phase.NONE || phase() == Phase.DONE) {
                return
            }
            val now = files()
            for ((name, content) in old) {
                assertTrue(
                    "AC-1 ($where, phase ${phase()}): the old $name must still exist as $name or $name.old; " +
                        "directory: ${now.keys}",
                    now[name] == content || now["$name.old"] == content
                )
            }
        }
    }

    private fun describe(old: Map<String, String>, settings: Settings) =
        "old files ${old.keys}, staged settings $settings"

    /**
     * Kills the first start at its n-th step for every n, then starts again without a kill.
     * Returns the number of steps of an unkilled start.
     */
    private fun killSweep(
        old: Map<String, String>,
        settings: Settings,
        verify: Boolean,
        apply: Boolean,
        expectNew: Boolean
    ): Int {
        var n = 1
        while (true) {
            assertTrue("recover has more than 300 steps", n <= 300)
            val where = "${describe(old, settings)}, killed at step $n"
            val device = Device(old, settings).stageAndCommit()
            var calls = 0
            val killed = try {
                device.start(verify, apply, beforeStep = { if (++calls == n) throw Kill() })
                false
            } catch (e: Kill) {
                true
            }
            if (killed) {
                device.assertOldDataStillOnDisk(where)
                val outcome = device.start(verify, apply)
                assertEquals("AC-1 ($where): after the next start nothing may be pending", Phase.NONE, device.phase())
                val allowed = if (expectNew) Outcome.COMPLETED else Outcome.ROLLED_BACK
                assertTrue(
                    "AC-1 ($where): the next start must report $allowed (or NOTHING_PENDING if only the marker's " +
                        "deletion was left), reported $outcome",
                    outcome == allowed || outcome == Outcome.NOTHING_PENDING
                )
            }
            if (expectNew) device.assertNewState(where) else device.assertOldState(where)
            if (!killed) {
                return n - 1
            }
            n++
        }
    }

    // ---------------------------------------------------------------- no import pending

    /**
     * AC-1: with no marker, a start changes no live file ("no marker means live files are
     * byte-identical"), opens nothing and applies nothing.
     */
    @Test
    fun ac1_noMarker_startLeavesLiveFilesByteIdentical() {
        for (old in ALL_OLD_SETS) {
            val device = Device(old)
            val outcome = device.start()
            assertEquals(Outcome.NOTHING_PENDING, outcome)
            device.assertOldState("no marker, old files ${old.keys}")
            assertEquals("AC-1: nothing pending, so the database must not be opened by recover", 0, device.verifyCalls)
            assertEquals("AC-1: nothing pending, so no settings may be applied", 0, device.applyCalls)
        }
    }

    /**
     * AC-1, "kill before `import.commit` exists": staged files without a marker are an import
     * that was never decided. The next start deletes them and keeps everything else; the
     * staged database is not promoted and the staged settings are not applied.
     */
    @Test
    fun ac1_killBeforeCommit_stagedFilesAreDiscardedAtTheNextStart() {
        for (settings in Settings.values()) {
            for (old in ALL_OLD_SETS) {
                val device = Device(old, settings).stage()
                val outcome = device.start()
                assertEquals(Outcome.NOTHING_PENDING, outcome)
                device.assertOldState("staged but not committed, ${describe(old, settings)}")
                assertEquals(0, device.verifyCalls)
                assertEquals(0, device.applyCalls)
            }
        }
    }

    /** AC-1: `.old` leftovers without a marker are deleted, never renamed over the live files. */
    @Test
    fun ac1_noMarker_oldLeftoversAreDeletedNotRestored() {
        val device = Device(OLD_WITH_WAL)
        File(device.dir, "$DB.old").writeText("stale copy of an earlier import")
        File(device.dir, "$WAL.old").writeText("stale wal")
        File(device.dir, "$SHM.old").writeText("stale shm")
        File(device.dir, "$JOURNAL.old").writeText("stale journal")
        assertEquals(Outcome.NOTHING_PENDING, device.start())
        device.assertOldState("no marker, .old leftovers")
    }

    // ---------------------------------------------------------------- the marker

    /** AC-1: without a marker file the phase is NONE. */
    @Test
    fun ac1_phase_isNoneWithoutMarker() {
        assertEquals(Phase.NONE, Device(OLD_PLAIN).phase())
        assertEquals("staged files alone are not a marker", Phase.NONE, Device(OLD_PLAIN).stage().phase())
    }

    /** AC-1: the marker's file name is the phase (`import.commit`, `.rollback`, `.restore`, `.done`). */
    @Test
    fun ac1_phase_isTheMarkersName() {
        val names = mapOf(
            "import.commit" to Phase.COMMIT,
            "import.rollback" to Phase.ROLLBACK,
            "import.restore" to Phase.RESTORE,
            "import.done" to Phase.DONE
        )
        for ((name, phase) in names) {
            val device = Device(OLD_PLAIN)
            File(device.dir, name).writeText("")
            assertEquals("marker $name", phase, device.phase())
        }
    }

    /**
     * AC-1, "nothing is replaced while the app runs": `commit()` creates `import.commit` and
     * touches nothing else. The live files and the staged files are as before.
     */
    @Test
    fun ac1_commit_createsTheMarkerAndTouchesNothingElse() {
        for (old in ALL_OLD_SETS) {
            val device = Device(old).stage()
            val before = device.files()
            ImportJournal(device.dir).commit()
            assertEquals(Phase.COMMIT, device.phase())
            assertTrue("commit() must create databases/import.commit", File(device.dir, "import.commit").isFile)
            assertEquals(
                "AC-1: commit() must not change any file but its marker",
                before,
                device.files() - "import.commit"
            )
        }
    }

    /** AC-1: before a commit, `discardStaging()` deletes the three staged files and only them. */
    @Test
    fun ac1_discardStaging_deletesStagedFilesWhileNothingIsPending() {
        val device = Device(OLD_WITH_WAL)
        val journal = ImportJournal(device.dir)
        journal.stagedDb.writeText("leftover")
        journal.stagedSettingsJson.writeText("leftover")
        journal.stagedSettingsLegacy.writeText("leftover")
        journal.discardStaging()
        device.assertOldState("discardStaging without marker")
    }

    /**
     * AC-1: once `import.commit` exists the staged files are the import; `discardStaging()`
     * (step 1 of a second import started before the restart) must not delete them.
     */
    @Test
    fun ac1_discardStaging_keepsStagedFilesOfACommittedImport() {
        val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        val before = device.files()
        ImportJournal(device.dir).discardStaging()
        assertEquals("AC-1: a committed import must keep its staged files", before, device.files())
        assertEquals(Outcome.COMPLETED, device.start())
        device.assertNewState("discardStaging after commit, then start")
    }

    /** The staged files are the ones the plan names, in the database directory. */
    @Test
    fun ac1_stagedFiles_haveThePlansNames() {
        val dir = tmp.newFolder()
        val journal = ImportJournal(dir)
        assertEquals(File(dir, "newpipe.db.import"), journal.stagedDb)
        assertEquals(File(dir, "preferences.json.import"), journal.stagedSettingsJson)
        assertEquals(File(dir, "newpipe.settings.import"), journal.stagedSettingsLegacy)
    }

    // ---------------------------------------------------------------- roll forward

    /**
     * AC-1 and AC-3: a committed import completes at the next start. The database and all its
     * companions are exchanged as a whole, the settings are applied, nothing is left over.
     */
    @Test
    fun ac1_ac3_committedImport_completesAtTheNextStart() {
        for (settings in Settings.values()) {
            for (old in ALL_OLD_SETS) {
                val device = Device(old, settings).stageAndCommit()
                val outcome = device.start()
                assertEquals(describe(old, settings), Outcome.COMPLETED, outcome)
                device.assertNewState(describe(old, settings))
                assertEquals(Phase.NONE, device.phase())
                assertEquals("the new database is opened once for verification", 1, device.verifyCalls)
            }
        }
    }

    /** AC-3: staged JSON settings are applied as JSON, legacy settings as legacy; none means no call. */
    @Test
    fun ac3_settings_areAppliedFromTheStagedFileInItsFormat() {
        val json = Device(OLD_PLAIN, Settings.JSON).stageAndCommit()
        json.start()
        assertEquals(listOf("preferences.json.import" to false), json.applied)

        val legacy = Device(OLD_PLAIN, Settings.LEGACY).stageAndCommit()
        legacy.start()
        assertEquals(listOf("newpipe.settings.import" to true), legacy.applied)

        val none = Device(OLD_PLAIN, Settings.NONE).stageAndCommit()
        none.start()
        assertEquals("database-only import: no settings may be applied", 0, none.applyCalls)
    }

    /**
     * AC-1: the settings are touched only after the new database was opened successfully
     * (plan step c before d), so a database that cannot be opened never costs the settings.
     */
    @Test
    fun ac1_settings_areAppliedOnlyAfterTheDatabaseWasVerified() {
        val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        device.start()
        assertEquals(listOf("verify", "apply"), device.order)

        val failing = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        failing.start(verify = false)
        assertEquals("AC-1: no settings may be applied when the database cannot be opened", 0, failing.applyCalls)
    }

    /** AC-1: a second start after a completed import finds nothing to do and changes nothing. */
    @Test
    fun ac1_recover_isRepeatableAfterCompletion() {
        val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        assertEquals(Outcome.COMPLETED, device.start())
        assertEquals(Outcome.NOTHING_PENDING, device.start())
        device.assertNewState("second start after completion")
        assertEquals(1, device.verifyCalls)
        assertEquals(1, device.applyCalls)
    }

    // ---------------------------------------------------------------- rollback

    /**
     * AC-1 (plan step c, answer A5c): Room cannot open the new database. The new files,
     * including the WAL its opening created, are deleted, the whole old set is back, the
     * settings were never touched, and the outcome says so (it triggers the message).
     */
    @Test
    fun ac1_databaseCannotBeOpened_oldSetIsRestoredExactly() {
        for (settings in Settings.values()) {
            for (old in ALL_OLD_SETS) {
                val device = Device(old, settings).stageAndCommit()
                val outcome = device.start(verify = false)
                assertEquals(describe(old, settings), Outcome.ROLLED_BACK, outcome)
                device.assertOldState("verifyOpen false, ${describe(old, settings)}")
                assertEquals(Phase.NONE, device.phase())
                assertEquals(0, device.applyCalls)
            }
        }
    }

    /**
     * AC-1 (plan step d): the settings cannot be written. The database is rolled back as well:
     * new database with old settings would be half an import.
     */
    @Test
    fun ac1_settingsCannotBeApplied_databaseIsRolledBackToo() {
        for (settings in listOf(Settings.JSON, Settings.LEGACY)) {
            for (old in ALL_OLD_SETS) {
                val device = Device(old, settings).stageAndCommit()
                val outcome = device.start(apply = false)
                assertEquals(describe(old, settings), Outcome.ROLLED_BACK, outcome)
                device.assertOldState("applySettings false, ${describe(old, settings)}")
                assertEquals(Phase.NONE, device.phase())
            }
        }
    }

    /** AC-1: after a rollback the next start has nothing pending and does not try the import again. */
    @Test
    fun ac1_rolledBackImport_isNotRetriedAtLaterStarts() {
        val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        assertEquals(Outcome.ROLLED_BACK, device.start(verify = false))
        assertEquals(Outcome.NOTHING_PENDING, device.start(verify = true))
        device.assertOldState("start after a rollback")
        assertEquals(1, device.verifyCalls)
    }

    // ---------------------------------------------------------------- kill at any moment

    /**
     * AC-1, "kill after `import.commit`, at any later step: the next start continues from the
     * marker; the import completes". For every step n of the roll forward: kill there, start
     * again, and the directory holds exactly the new state. At the kill, the old data is still
     * on disk unless the import is already marked done.
     */
    @Test
    fun ac1_killAtEveryStepOfTheRollForward_nextStartCompletesTheImport() {
        for (settings in Settings.values()) {
            for (old in ALL_OLD_SETS) {
                val steps = killSweep(old, settings, verify = true, apply = true, expectNew = true)
                assertTrue(
                    "${describe(old, settings)}: beforeStep must be called before every file operation, " +
                        "counted only $steps calls",
                    steps >= old.size + 3
                )
            }
        }
    }

    /**
     * AC-1: the same sweep when Room cannot open the new database. Wherever the kill falls,
     * in the roll forward or in the rollback, the result is exactly the old state.
     */
    @Test
    fun ac1_killAtEveryStepWhenTheDatabaseCannotBeOpened_oldStateIsRestored() {
        for (settings in Settings.values()) {
            for (old in ALL_OLD_SETS) {
                killSweep(old, settings, verify = false, apply = true, expectNew = false)
            }
        }
    }

    /** AC-1: the same sweep when the settings cannot be applied. */
    @Test
    fun ac1_killAtEveryStepWhenTheSettingsCannotBeApplied_oldStateIsRestored() {
        for (settings in listOf(Settings.JSON, Settings.LEGACY)) {
            for (old in ALL_OLD_SETS) {
                killSweep(old, settings, verify = true, apply = false, expectNew = false)
            }
        }
    }

    /**
     * AC-1: two kills in a row, at every pair of steps (first start killed at step n, second
     * start killed at its step m), then an unkilled third start. `recover` can be re-run from
     * any point.
     */
    @Test
    fun ac1_twoKillsInARow_atEveryPairOfSteps() {
        for ((verify, expectNew) in listOf(true to true, false to false)) {
            var n = 1
            sweep@ while (true) {
                assertTrue(n <= 300)
                var m = 1
                while (true) {
                    assertTrue(m <= 300)
                    val where = "verifyOpen $verify, killed at step $n, then at step $m"
                    val device = Device(OLD_ALL_FOUR, Settings.JSON).stageAndCommit()
                    var calls = 0
                    try {
                        device.start(verify, beforeStep = { if (++calls == n) throw Kill() })
                        break@sweep // n is past the last step: the single-kill sweeps cover the rest
                    } catch (e: Kill) {
                        device.assertOldDataStillOnDisk(where)
                    }
                    calls = 0
                    val secondKilled = try {
                        device.start(verify, beforeStep = { if (++calls == m) throw Kill() })
                        false
                    } catch (e: Kill) {
                        true
                    }
                    if (secondKilled) {
                        device.assertOldDataStillOnDisk(where)
                        device.start(verify)
                    }
                    assertEquals(where, Phase.NONE, device.phase())
                    if (expectNew) device.assertNewState(where) else device.assertOldState(where)
                    if (!secondKilled) {
                        break
                    }
                    m++
                }
                n++
            }
            assertTrue("the sweep must have had kill points", n > 5)
        }
    }

    /**
     * AC-1: a kill while Room has the new database open (it has written a WAL, a migration may
     * be half done). The next start verifies again; if that works the import completes, if not
     * the old state is back, without the new database's WAL.
     */
    @Test
    fun ac1_killWhileTheNewDatabaseIsBeingOpened() {
        for (secondVerify in listOf(true, false)) {
            val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
            try {
                device.start(duringVerify = { throw Kill() })
                fail("the kill must propagate out of recover")
            } catch (e: Kill) {
                device.assertOldDataStillOnDisk("killed inside verifyOpen")
                assertEquals("killed inside verifyOpen: the import is still pending", Phase.COMMIT, device.phase())
            }
            val outcome = device.start(verify = secondVerify)
            if (secondVerify) {
                assertEquals(Outcome.COMPLETED, outcome)
                device.assertNewState("killed inside verifyOpen, second start opens it")
            } else {
                assertEquals(Outcome.ROLLED_BACK, outcome)
                device.assertOldState("killed inside verifyOpen, second start cannot open it")
            }
        }
    }

    /**
     * AC-1: a kill right after the settings were written and before the marker moved on. The
     * next start applies the staged settings again and completes; the old database is kept
     * until then.
     */
    @Test
    fun ac1_killRightAfterTheSettingsWereApplied_nextStartCompletes() {
        val device = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        try {
            device.start(duringApply = { throw Kill() })
            fail("the kill must propagate out of recover")
        } catch (e: Kill) {
            assertEquals("killed inside applySettings: the import is still pending", Phase.COMMIT, device.phase())
            device.assertOldDataStillOnDisk("killed inside applySettings")
        }
        assertEquals(Outcome.COMPLETED, device.start())
        device.assertNewState("killed inside applySettings, then started again")
    }

    // ---------------------------------------------------------------- every marker phase, hand-made

    /**
     * AC-1, marker `import.rollback`: the live files belong to the new database and are
     * deleted, the `.old` set comes back, nothing is opened and no settings are applied.
     */
    @Test
    fun ac1_startWithRollbackMarker_deletesTheNewFilesAndRestoresTheOldSet() {
        val device = Device(emptyMap())
        val dir = device.dir
        File(dir, "import.rollback").writeText("")
        File(dir, DB).writeText(NEW_DB)
        File(dir, WAL).writeText(NEW_WAL)
        File(dir, SHM).writeText("new shm")
        File(dir, "$DB.old").writeText("old database")
        File(dir, "$WAL.old").writeText("old wal")
        File(dir, "$JOURNAL.old").writeText("old journal")
        File(dir, "preferences.json.import").writeText(NEW_SETTINGS)

        assertEquals(Outcome.ROLLED_BACK, device.start())
        assertEquals(
            mapOf(DB to "old database", WAL to "old wal", JOURNAL to "old journal"),
            device.files()
        )
        assertEquals("AC-1: a rollback never applies settings", OLD_SETTINGS, device.prefs)
        assertEquals("AC-1: a rollback does not open the database through the journal", 0, device.verifyCalls)
        assertEquals(0, device.applyCalls)
    }

    /**
     * AC-1, marker `import.restore`: the new files are already gone and part of the old set is
     * already back. The live database is the old one now and must survive; the rest is renamed
     * back.
     */
    @Test
    fun ac1_startWithRestoreMarker_finishesRenamingBackAndKeepsWhatIsAlreadyBack() {
        val device = Device(emptyMap())
        val dir = device.dir
        File(dir, "import.restore").writeText("")
        File(dir, DB).writeText("old database") // already renamed back before the kill
        File(dir, "$WAL.old").writeText("old wal")
        File(dir, "$SHM.old").writeText("old shm")
        File(dir, "newpipe.settings.import").writeText(NEW_SETTINGS)

        assertEquals(Outcome.ROLLED_BACK, device.start())
        assertEquals(
            mapOf(DB to "old database", WAL to "old wal", SHM to "old shm"),
            device.files()
        )
        assertEquals(OLD_SETTINGS, device.prefs)
        assertEquals(0, device.verifyCalls)
        assertEquals(0, device.applyCalls)
    }

    /**
     * AC-1, marker `import.done`: the import is complete; only deletions are left. The live
     * (new) files stay, the `.old` set and staged files go, the settings are not applied again.
     */
    @Test
    fun ac1_startWithDoneMarker_onlyDeletesLeftovers() {
        val device = Device(emptyMap())
        val dir = device.dir
        device.prefs = NEW_SETTINGS // applied before the marker became import.done
        File(dir, "import.done").writeText("")
        File(dir, DB).writeText(NEW_DB)
        File(dir, WAL).writeText(NEW_WAL)
        File(dir, "$DB.old").writeText("old database")
        File(dir, "$SHM.old").writeText("old shm")
        File(dir, "preferences.json.import").writeText("{\"other\":true}")

        assertEquals(Outcome.COMPLETED, device.start())
        assertEquals(mapOf(DB to NEW_DB, WAL to NEW_WAL), device.files())
        assertEquals("AC-1: a done import does not apply its settings a second time", NEW_SETTINGS, device.prefs)
        assertEquals(0, device.verifyCalls)
        assertEquals(0, device.applyCalls)
    }

    // ---------------------------------------------------------------- beforeStep

    /** `beforeStep` has a default, so production code need not set it. */
    @Test
    fun ac1_beforeStep_hasADefaultThatDoesNothing() {
        val device = Device(OLD_PLAIN).stageAndCommit()
        assertEquals(Outcome.COMPLETED, ImportJournal(device.dir).recover({ true }, { _, _ -> true }))
    }

    /**
     * AC-1: the kill tests are only as fine as the calls of `beforeStep`. A roll forward
     * announces one MOVE_OLD per live file, then PROMOTE, VERIFY, APPLY_SETTINGS, MARK, and one
     * DELETE per `.old` file and staged settings file; a rollback announces RESTORE_OLD per
     * `.old` file.
     */
    @Test
    fun ac1_beforeStep_isCalledForEveryFileOperation() {
        val forward = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        val steps = mutableListOf<Step>()
        forward.start(beforeStep = { steps += it })
        assertTrue("one MOVE_OLD per live file (3), got $steps", steps.count { it == Step.MOVE_OLD } >= 3)
        assertTrue("PROMOTE, got $steps", steps.count { it == Step.PROMOTE } >= 1)
        assertTrue("VERIFY, got $steps", steps.count { it == Step.VERIFY } >= 1)
        assertTrue("APPLY_SETTINGS, got $steps", steps.count { it == Step.APPLY_SETTINGS } >= 1)
        assertTrue("MARK, got $steps", steps.count { it == Step.MARK } >= 1)
        assertTrue(
            "one DELETE per .old file (3) and for the staged settings (1), got $steps",
            steps.count { it == Step.DELETE } >= 4
        )
        val firstOf = { step: Step -> steps.indexOf(step) }
        assertTrue(
            "order MOVE_OLD, PROMOTE, VERIFY, APPLY_SETTINGS, MARK, got $steps",
            steps.lastIndexOf(Step.MOVE_OLD) < firstOf(Step.PROMOTE) &&
                firstOf(Step.PROMOTE) < firstOf(Step.VERIFY) &&
                firstOf(Step.VERIFY) < firstOf(Step.APPLY_SETTINGS) &&
                firstOf(Step.APPLY_SETTINGS) < firstOf(Step.MARK)
        )
        assertTrue(
            "AC-1: no .old file may be deleted before the marker says the import is done, got $steps",
            firstOf(Step.MARK) < firstOf(Step.DELETE)
        )

        val back = Device(OLD_WITH_WAL, Settings.JSON).stageAndCommit()
        val backSteps = mutableListOf<Step>()
        back.start(verify = false, beforeStep = { backSteps += it })
        assertTrue(
            "one RESTORE_OLD per .old file (3), got $backSteps",
            backSteps.count { it == Step.RESTORE_OLD } >= 3
        )
        assertFalse("a rollback never applies settings, got $backSteps", Step.APPLY_SETTINGS in backSteps)
    }
}
