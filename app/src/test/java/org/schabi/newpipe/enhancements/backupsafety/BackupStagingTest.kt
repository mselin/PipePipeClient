package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.enhancements.backupsafety.ImportJournal.Outcome
import org.schabi.newpipe.enhancements.backupsafety.ImportJournal.Phase
import org.schabi.newpipe.enhancements.backupsafety.StageResult.Reason
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * AC-1 and AC-2: [BackupStaging.stage] (plan section 2, steps 1 to 4), what the running app
 * does with a chosen backup file, on a real temporary `databases/` directory that holds a live
 * database with an uncheckpointed WAL.
 *
 * AC-2: "Before anything is replaced, the imported database is checked [..]. Otherwise the
 * import is refused with a message that names the reason." Each [Reason] is produced here. After
 * every refusal the directory is byte for byte what it was (AC-1), and no marker exists, so the
 * next start does nothing.
 *
 * `DatabaseProbe` (SQLite's `quick_check`) is Android code; here it is the `probe` function.
 */
class BackupStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var journal: ImportJournal
    private lateinit var live: Map<String, String>

    private var probeAnswer = true
    private val probed = mutableListOf<File>()
    private var opened = 0
    private var closed = 0

    private val goodDb = SqliteBytes.file(userVersion = 902, pages = 20)

    @Before
    fun liveDatabase() {
        dir = tmp.newFolder("databases")
        File(dir, "newpipe.db").writeBytes(SqliteBytes.file(userVersion = 902, pages = 5, seed = 99))
        File(dir, "newpipe.db-wal").writeText("uncheckpointed changes of the running app")
        File(dir, "newpipe.db-shm").writeText("shm")
        live = dir.snapshot()
        journal = ImportJournal(dir)
    }

    private fun staging() = BackupStaging(journal) { file ->
        probed += file
        assertTrue("the probe must be given an existing file", file.isFile)
        probeAnswer
    }

    private fun stage(zip: ByteArray): StageResult = staging().stage {
        opened++
        object : FilterInputStream(ByteArrayInputStream(zip)) {
            override fun close() {
                closed++
                super.close()
            }
        }
    }

    private fun stagedNames(): Set<String> = dir.snapshot().keys - live.keys

    private fun assertRefused(what: String, expected: Reason, result: StageResult, version: Int? = null) {
        assertEquals("AC-2 ($what)", StageResult.Refused(expected, version), result)
        assertEquals(
            "AC-1 ($what): after a refusal the directory must be exactly as before (nothing staged, live files untouched)",
            live,
            dir.snapshot()
        )
        assertEquals("AC-1 ($what): a refusal must not leave a marker", Phase.NONE, journal.phase())
        // the next start
        val outcome = ImportJournal(dir).recover({ throw AssertionError("nothing to verify") }, { _, _ -> throw AssertionError("nothing to apply") })
        assertEquals(Outcome.NOTHING_PENDING, outcome)
        assertEquals("AC-1 ($what): the start after a refused import changes nothing", live, dir.snapshot())
    }

    // ---------------------------------------------------------------- accepted

    /**
     * AC-2, AC-3: a good backup is staged next to the live files. Nothing live is touched and
     * no marker is written: staging decides nothing.
     */
    @Test
    fun ac2_goodBackup_isStagedAndNothingLiveIsTouched() {
        val result = stage(Zips.zip(Zips.DB to goodDb, Zips.LEGACY to Zips.legacySettings(), Zips.JSON to Zips.SETTINGS_JSON))
        assertEquals(StageResult.Staged(hasSettings = true), result)
        assertArrayEquals(goodDb, journal.stagedDb.readBytes())
        assertArrayEquals(Zips.SETTINGS_JSON, journal.stagedSettingsJson.readBytes())
        assertEquals("AC-1: staging must not change a live file", live, dir.snapshot().filterKeys { it in live })
        assertEquals("AC-1: staging must not write the marker; that is commit()", Phase.NONE, journal.phase())
    }

    /** AC-3: JSON settings are preferred; the legacy entry is then not staged (as upstream's import). */
    @Test
    fun ac3_jsonSettingsArePreferredOverTheLegacyEntry() {
        stage(Zips.zip(Zips.DB to goodDb, Zips.LEGACY to Zips.legacySettings(), Zips.JSON to Zips.SETTINGS_JSON))
        assertEquals(setOf("newpipe.db.import", "preferences.json.import"), stagedNames())
    }

    /** AC-3: a backup of an old version has only the legacy settings entry; it is staged. */
    @Test
    fun ac3_legacySettingsAreStagedWhenThereIsNoJson() {
        val legacy = Zips.legacySettings(mapOf("theme" to "dark", "n" to 3, "l" to 4L, "f" to 1.5f, "b" to true, "set" to hashSetOf("a", "b")))
        val result = stage(Zips.zip(Zips.DB to goodDb, Zips.LEGACY to legacy))
        assertEquals(StageResult.Staged(hasSettings = true), result)
        assertEquals(setOf("newpipe.db.import", "newpipe.settings.import"), stagedNames())
        assertArrayEquals(legacy, journal.stagedSettingsLegacy.readBytes())
    }

    /** AC-3: a backup without settings is a database-only import. */
    @Test
    fun ac3_backupWithoutSettings_isStagedWithoutSettings() {
        assertEquals(StageResult.Staged(hasSettings = false), stage(Zips.zip(Zips.DB to goodDb)))
        assertEquals(setOf("newpipe.db.import"), stagedNames())
    }

    /** AC-2: every database version the app can open is accepted. */
    @Test
    fun ac2_everyOpenableVersionIsAccepted() {
        for (version in listOf(1, 2, 3, 4, 5, 6, 9, 900, 901, 902)) {
            val result = stage(Zips.zip(Zips.DB to SqliteBytes.file(version)))
            assertEquals("version $version", StageResult.Staged(hasSettings = false), result)
        }
    }

    /** AC-2: the database that is checked is the staged copy, never the live one. */
    @Test
    fun ac2_probeIsGivenTheStagedDatabase() {
        stage(Zips.zip(Zips.DB to goodDb))
        assertEquals(listOf(journal.stagedDb), probed)
    }

    /**
     * AC-1: staged files of an earlier, abandoned attempt do not leak into this import. Without
     * step 1 the settings of another backup would be applied with this database.
     */
    @Test
    fun ac1_leftoversOfAnEarlierAttemptAreRemovedFirst() {
        journal.stagedSettingsJson.writeText("{\"from\":\"another backup\"}")
        journal.stagedSettingsLegacy.writeText("garbage of another attempt")
        journal.stagedDb.writeText("garbage of another attempt")
        val result = stage(Zips.zip(Zips.DB to goodDb))
        assertEquals(StageResult.Staged(hasSettings = false), result)
        assertEquals(setOf("newpipe.db.import"), stagedNames())
        assertArrayEquals(goodDb, journal.stagedDb.readBytes())
    }

    /** The backup is opened once and closed again. */
    @Test
    fun ac3_backupFileIsOpenedOnceAndClosed() {
        stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON))
        assertEquals("plan step 2: read the zip once", 1, opened)
        assertTrue("the stream must be closed", closed >= 1)
        opened = 0
        closed = 0
        stage(Zips.zip(Zips.DB to goodDb).copyOf(500))
        assertEquals(1, opened)
        assertTrue("the stream must be closed after a refusal too", closed >= 1)
    }

    /** AC-3, "Cancel" on the settings question: `dropSettings` leaves a database-only import. */
    @Test
    fun ac3_dropSettings_keepsOnlyTheStagedDatabase() {
        for (settings in listOf(Zips.JSON to Zips.SETTINGS_JSON, Zips.LEGACY to Zips.legacySettings())) {
            val staging = staging()
            staging.stage { ByteArrayInputStream(Zips.zip(Zips.DB to goodDb, settings)) }
            staging.dropSettings()
            assertEquals(setOf("newpipe.db.import"), stagedNames())
            journal.discardStaging()
        }
    }

    // ---------------------------------------------------------------- refused: each reason

    /** AC-2, NOT_A_BACKUP: a zip without `newpipe.db` (for example a settings-only archive). */
    @Test
    fun ac2_refused_notABackup_whenThereIsNoDatabaseEntry() {
        assertRefused(
            "zip with settings only", Reason.NOT_A_BACKUP,
            stage(Zips.zip(Zips.JSON to Zips.SETTINGS_JSON, Zips.LEGACY to Zips.legacySettings()))
        )
        assertRefused("empty zip", Reason.NOT_A_BACKUP, stage(Zips.zip()))
        assertRefused(
            "database in a sub-directory", Reason.NOT_A_BACKUP,
            stage(Zips.zip("backup/newpipe.db" to goodDb))
        )
    }

    /** AC-1, AC-2, DAMAGED: a truncated zip (device script step 2). */
    @Test
    fun ac2_refused_damaged_whenTheZipIsTruncated() {
        val whole = Zips.zip(Zips.DB to goodDb, Zips.LEGACY to Zips.legacySettings(), Zips.JSON to Zips.SETTINGS_JSON)
        for (cut in listOf(whole.size / 2, whole.size / 4, 300, 5000)) {
            assertRefused("zip cut to $cut of ${whole.size} bytes", Reason.DAMAGED, stage(whole.copyOf(cut)))
        }
    }

    /** AC-1, AC-2, DAMAGED: an altered byte, found by the entry's checksum. */
    @Test
    fun ac2_refused_damaged_whenAByteOfTheDatabaseIsAltered() {
        // stored, in the page data: the SQLite header stays valid and the length is right, only the checksum can tell
        val stored = Zips.zip(listOf(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON), stored = true)
        val dbStart = Zips.indexOf(stored, goodDb.copyOfRange(4096, 4160)) - 4096
        stored[dbStart + 10_000] = (stored[dbStart + 10_000].toInt() xor 0x40).toByte()
        assertRefused("flipped bit in the stored database", Reason.DAMAGED, stage(stored))
        // deflated, as upstream's export writes it
        val deflated = Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON)
        deflated[deflated.size / 2] = (deflated[deflated.size / 2].toInt() xor 0x40).toByte()
        assertRefused("flipped bit in the deflated archive", Reason.DAMAGED, stage(deflated))
    }

    /** AC-1, AC-2, DAMAGED: the file cannot be opened or stops being readable (any IOException). */
    @Test
    fun ac2_refused_damaged_onAnyIoError() {
        assertRefused(
            "the file cannot be opened", Reason.DAMAGED,
            staging().stage { throw IOException("simulated: provider gone") }
        )
        val whole = Zips.zip(Zips.DB to goodDb)
        val result = staging().stage {
            object : InputStream() {
                private var served = 0
                override fun read(): Int {
                    if (served >= 9000) throw IOException("simulated read error")
                    return whole[served++].toInt() and 0xff
                }
            }
        }
        assertRefused("read error after 9000 bytes", Reason.DAMAGED, result)
    }

    /** AC-2, NOT_A_DATABASE: `newpipe.db` is a text file (device script step 3). */
    @Test
    fun ac2_refused_notADatabase_whenTheEntryIsATextFile() {
        val text = "I am not a database. ".repeat(500).toByteArray()
        assertRefused("text file as newpipe.db", Reason.NOT_A_DATABASE, stage(Zips.zip(Zips.DB to text)))
        assertRefused("empty newpipe.db", Reason.NOT_A_DATABASE, stage(Zips.zip(Zips.DB to ByteArray(0))))
    }

    /**
     * AC-1, AC-2, NOT_A_DATABASE: a database that was already incomplete when it was zipped
     * (the archive itself is intact, so no checksum complains): cut in the middle of a page,
     * and cut at a page boundary below the page count of its header.
     */
    @Test
    fun ac2_refused_notADatabase_whenTheDatabaseInsideIsIncomplete() {
        assertRefused(
            "database cut in the middle of a page", Reason.NOT_A_DATABASE,
            stage(Zips.zip(Zips.DB to goodDb.copyOf(goodDb.size - 1000)))
        )
        assertRefused(
            "database cut at a page boundary", Reason.NOT_A_DATABASE,
            stage(Zips.zip(Zips.DB to goodDb.copyOf(goodDb.size - 4096 * 3)))
        )
        assertRefused(
            "only the header", Reason.NOT_A_DATABASE,
            stage(Zips.zip(Zips.DB to goodDb.copyOf(100)))
        )
    }

    /** AC-2, NOT_A_DATABASE: the header is fine but SQLite's check of the staged file fails. */
    @Test
    fun ac2_refused_notADatabase_whenTheProbeFails() {
        probeAnswer = false
        assertRefused("probe says no", Reason.NOT_A_DATABASE, stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON)))
    }

    /**
     * AC-2, VERSION_UNSUPPORTED: versions with no migration path (finding D3: these crashed the
     * app at every start). The refusal carries the version, for the message.
     */
    @Test
    fun ac2_refused_versionUnsupported_namesTheVersion() {
        for (version in listOf(0, 7, 8, 10, 899, 903, 950, 100_000)) {
            val result = stage(Zips.zip(Zips.DB to SqliteBytes.file(version), Zips.JSON to Zips.SETTINGS_JSON))
            assertRefused("database version $version", Reason.VERSION_UNSUPPORTED, result, version)
        }
    }

    /** AC-2 (answer A5b), SETTINGS_UNREADABLE: the JSON settings do not parse as an object. */
    @Test
    fun ac2_refused_settingsUnreadable_whenTheJsonIsNotAnObject() {
        val bad = listOf(
            "truncated" to """{"theme":"dark","show_comm""",
            "an array" to """["theme","dark"]""",
            "a string" to "\"theme\"",
            "empty" to "",
            "binary" to "\u0000\u0001\u0002\u0003"
        )
        for ((what, json) in bad) {
            assertRefused(
                "preferences.json is $what", Reason.SETTINGS_UNREADABLE,
                stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to json.toByteArray()))
            )
        }
    }

    /**
     * AC-2 (answer A5b), SETTINGS_UNREADABLE: the legacy settings do not deserialize to a map
     * through `PreferencesObjectInputStream` (garbage, another type, a class it does not allow).
     */
    @Test
    fun ac2_refused_settingsUnreadable_whenTheLegacyEntryIsNotAPreferencesMap() {
        val bad = listOf(
            "garbage" to "not serialized at all".toByteArray(),
            "empty" to ByteArray(0),
            "truncated" to Zips.legacySettings().let { it.copyOf(it.size / 2) },
            "a set, not a map" to Zips.serialized(hashSetOf(1, 2)),
            "a map holding a class that is not allowed" to Zips.serialized(hashMapOf("k" to java.util.ArrayList(listOf("x")))),
            "a class that is not allowed" to Zips.serialized(java.util.TreeMap(mapOf("a" to "b")))
        )
        for ((what, bytes) in bad) {
            assertRefused(
                "newpipe.settings is $what", Reason.SETTINGS_UNREADABLE,
                stage(Zips.zip(Zips.DB to goodDb, Zips.LEGACY to bytes))
            )
        }
    }

    /** Only the settings entry that will be used is judged: an unused legacy entry does not refuse a backup with good JSON. */
    @Test
    fun ac3_unusedLegacyEntryIsNotJudged() {
        val result = stage(Zips.zip(Zips.DB to goodDb, Zips.LEGACY to "garbage".toByteArray(), Zips.JSON to Zips.SETTINGS_JSON))
        assertEquals(StageResult.Staged(hasSettings = true), result)
    }

    // ---------------------------------------------------------------- the whole import

    /**
     * AC-1, AC-3: stage, commit, restart. The live database is the backup's, byte for byte, the
     * old WAL is gone, the staged settings were handed over, nothing is left over.
     */
    @Test
    fun ac3_stageCommitRestart_installsTheBackup() {
        assertEquals(
            StageResult.Staged(hasSettings = true),
            stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON))
        )
        journal.commit()
        var settings: ByteArray? = null
        val outcome = ImportJournal(dir).recover(
            verifyOpen = { true },
            applySettings = { file, legacy ->
                assertFalse(legacy)
                settings = file.readBytes()
                true
            }
        )
        assertEquals(Outcome.COMPLETED, outcome)
        assertEquals(setOf("newpipe.db"), dir.snapshot().keys)
        assertArrayEquals(goodDb, File(dir, "newpipe.db").readBytes())
        assertArrayEquals(Zips.SETTINGS_JSON, settings)
    }

    /** AC-1 (answer A5a), Back on the settings question: staged but never committed; the next start keeps the old data. */
    @Test
    fun ac1_stagedButNotCommitted_nextStartKeepsTheOldData() {
        stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON))
        journal.discardStaging() // what Back does
        assertEquals(live, dir.snapshot())

        stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON)) // or the process dies instead
        val outcome = ImportJournal(dir).recover({ throw AssertionError() }, { _, _ -> throw AssertionError() })
        assertEquals(Outcome.NOTHING_PENDING, outcome)
        assertEquals(live, dir.snapshot())
    }

    /**
     * AC-1 (answer A5c): a backup that passes every check but cannot be opened by Room. After
     * the restart the old database and its WAL are back, byte for byte.
     */
    @Test
    fun ac1_stageCommitRestart_databaseCannotBeOpened_oldDataIsBack() {
        stage(Zips.zip(Zips.DB to goodDb, Zips.JSON to Zips.SETTINGS_JSON))
        journal.commit()
        val outcome = ImportJournal(dir).recover({ false }, { _, _ -> throw AssertionError("settings must not be applied") })
        assertEquals(Outcome.ROLLED_BACK, outcome)
        assertEquals(live, dir.snapshot())
    }
}
