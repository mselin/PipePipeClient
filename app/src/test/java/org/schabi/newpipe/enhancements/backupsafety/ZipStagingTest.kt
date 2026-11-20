package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * AC-1 and AC-2: [ZipStaging.extract] (plan section 2, step 2) copies the backup's entries to
 * staging files and notices a truncated or altered archive, on real files in a temporary
 * directory.
 *
 * The archives are cut and altered inside an entry's data. A cut exactly between two entries or
 * inside the directory at the archive's end leaves every remaining entry complete and is not
 * noticed by a streaming reader (question Q6).
 */
class ZipStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = SqliteBytes.file(userVersion = 902, pages = 80) // 320 kB: many buffers
    private val legacy = Zips.legacySettings()

    private lateinit var dir: File

    private fun targets(): Map<String, File> {
        dir = tmp.newFolder()
        return mapOf(
            Zips.DB to File(dir, "newpipe.db.import"),
            Zips.JSON to File(dir, "preferences.json.import"),
            Zips.LEGACY to File(dir, "newpipe.settings.import")
        )
    }

    private fun backup(stored: Boolean = false) = Zips.zip(
        listOf(Zips.DB to db, Zips.LEGACY to legacy, Zips.JSON to Zips.SETTINGS_JSON),
        stored
    )

    /** Where the database's bytes start in an archive whose entries are stored. */
    private fun dbStart(storedArchive: ByteArray): Int =
        Zips.indexOf(storedArchive, db.copyOfRange(4096, 4096 + 64)) - 4096

    private fun assertRefusedAndClean(what: String, zip: InputStream, targets: Map<String, File>) {
        try {
            ZipStaging.extract(zip, targets)
            fail("AC-2 ($what): extract must throw an IOException")
        } catch (expected: IOException) {
            // the reason "damaged or incomplete"
        }
        assertEquals(
            "AC-1 ($what): after a failed extract no target file may remain",
            emptyMap<String, String>(),
            dir.snapshot()
        )
    }

    /** AC-3: the entries of a complete backup arrive byte for byte, and their names are returned. */
    @Test
    fun ac3_completeBackup_isCopiedByteForByte() {
        for (stored in listOf(false, true)) {
            val targets = targets()
            val found = ZipStaging.extract(ByteArrayInputStream(backup(stored)), targets)
            assertEquals(setOf(Zips.DB, Zips.JSON, Zips.LEGACY), found)
            assertArrayEquals(db, targets.getValue(Zips.DB).readBytes())
            assertArrayEquals(Zips.SETTINGS_JSON, targets.getValue(Zips.JSON).readBytes())
            assertArrayEquals(legacy, targets.getValue(Zips.LEGACY).readBytes())
        }
    }

    /** AC-2: only entries that exist are reported and written; other entries are ignored. */
    @Test
    fun ac2_onlyEntriesFoundAreReportedAndWritten() {
        val targets = targets()
        val zip = Zips.zip("readme.txt" to "hello".toByteArray(), Zips.DB to db)
        assertEquals(setOf(Zips.DB), ZipStaging.extract(ByteArrayInputStream(zip), targets))
        assertEquals(setOf("newpipe.db.import"), dir.snapshot().keys)
    }

    /** AC-2: an archive without a database entry reports no `newpipe.db` (the reason NOT_A_BACKUP). */
    @Test
    fun ac2_archiveWithoutDatabase_reportsNoDatabase() {
        val targets = targets()
        val zip = Zips.zip("something.txt" to "hello".toByteArray(), Zips.JSON to Zips.SETTINGS_JSON)
        val found = ZipStaging.extract(ByteArrayInputStream(zip), targets)
        assertFalse(Zips.DB in found)
        assertFalse(targets.getValue(Zips.DB).exists())
    }

    /** AC-2: an entry in a sub-directory or with a longer name is not the database. */
    @Test
    fun ac2_onlyTheExactEntryNameCounts() {
        val targets = targets()
        val zip = Zips.zip(
            "backup/newpipe.db" to db,
            "../newpipe.db" to db,
            "newpipe.db.bak" to db
        )
        assertEquals(emptySet<String>(), ZipStaging.extract(ByteArrayInputStream(zip), targets))
        assertEquals(emptyMap<String, String>(), dir.snapshot())
        assertFalse("nothing may be written outside the targets", File(dir.parentFile, "newpipe.db").exists())
    }

    /** An existing target (a leftover) is replaced, not appended to. */
    @Test
    fun ac1_existingTarget_isReplaced() {
        val targets = targets()
        targets.getValue(Zips.DB).writeBytes(ByteArray(900_000) { 1 })
        ZipStaging.extract(ByteArrayInputStream(backup()), targets)
        assertArrayEquals(db, targets.getValue(Zips.DB).readBytes())
    }

    /**
     * AC-1, AC-2: a backup cut off inside the database entry (the device script's "half its
     * size", and other lengths) throws, and nothing staged remains.
     */
    @Test
    fun ac2_truncatedArchive_throwsAndLeavesNoTargets() {
        for (stored in listOf(false, true)) {
            val whole = backup(stored)
            // the database is the first entry and nearly all of the archive
            val cuts = mutableListOf(whole.size / 2, whole.size / 4, 300, whole.size * 9 / 10)
            if (stored) {
                val dbStart = dbStart(whole)
                cuts += dbStart + 1 // one byte of the database
                cuts += dbStart + 100 // exactly the SQLite header
                cuts += dbStart + 4096 // exactly one page
                cuts += dbStart + db.size - 1 // all but the last byte
            }
            for (cut in cuts) {
                val targets = targets()
                assertRefusedAndClean(
                    "archive of ${whole.size} bytes cut to $cut, stored=$stored",
                    ByteArrayInputStream(whole.copyOf(cut)),
                    targets
                )
            }
        }
    }

    /**
     * AC-1, AC-2: one altered byte inside the database entry. The length is right, only the
     * checksum can tell; the archive must be read with its CRC checked.
     */
    @Test
    fun ac2_alteredByteInTheDatabase_throwsAndLeavesNoTargets() {
        // stored: the byte is a byte of the database itself, so only the CRC can notice
        val stored = backup(stored = true)
        val dbStart = dbStart(stored)
        for (offset in listOf(200, 5000, db.size / 2, db.size - 1)) {
            val altered = stored.copyOf()
            altered[dbStart + offset] = (altered[dbStart + offset].toInt() xor 0x01).toByte()
            val targets = targets()
            assertRefusedAndClean("bit flipped at byte $offset of the stored database", ByteArrayInputStream(altered), targets)
        }
        // deflated, as upstream's export writes it
        val deflated = backup(stored = false)
        for (position in listOf(deflated.size / 4, deflated.size / 2, deflated.size * 3 / 4)) {
            val altered = deflated.copyOf()
            altered[position] = (altered[position].toInt() xor 0x01).toByte()
            val targets = targets()
            assertRefusedAndClean("bit flipped at byte $position of the deflated archive", ByteArrayInputStream(altered), targets)
        }
    }

    /**
     * AC-1: an altered byte in a later entry (the settings) also removes the database that was
     * already copied completely: "deletes all targets [..] on any error".
     */
    @Test
    fun ac1_alteredSettingsEntry_alsoRemovesTheAlreadyCopiedDatabase() {
        val whole = Zips.zip(listOf(Zips.DB to db, Zips.JSON to Zips.SETTINGS_JSON), stored = true)
        val jsonStart = Zips.indexOf(whole, Zips.SETTINGS_JSON)
        val altered = whole.copyOf()
        altered[jsonStart + 3] = (altered[jsonStart + 3].toInt() xor 0x20).toByte()
        val targets = targets()
        assertRefusedAndClean("bit flipped in preferences.json", ByteArrayInputStream(altered), targets)
    }

    /** AC-1: the source failing in the middle (storage removed, provider gone) is the same refusal. */
    @Test
    fun ac1_sourceFailsWhileReading_throwsAndLeavesNoTargets() {
        val whole = backup()
        val failing = object : InputStream() {
            private val inner = ByteArrayInputStream(whole)
            private var served = 0
            override fun read(): Int {
                if (served >= whole.size / 2) throw IOException("simulated read error")
                served++
                return inner.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= whole.size / 2) throw IOException("simulated read error")
                val n = inner.read(b, off, minOf(len, whole.size / 2 - served))
                if (n > 0) served += n
                return n
            }
        }
        val targets = targets()
        assertRefusedAndClean("read error after half the archive", failing, targets)
    }

    /**
     * AC-1, the nearest a JVM test gets to "full disk": a target that cannot be written (its
     * directory does not exist). The error is an IOException and the other targets are removed.
     */
    @Test
    fun ac1_targetCannotBeWritten_throwsAndLeavesNoTargets() {
        val targets = targets()
        val unwritable = targets + (Zips.JSON to File(File(dir, "missing-directory"), "preferences.json.import"))
        // the database comes first in the archive and is copied before the failure
        val zip = Zips.zip(listOf(Zips.DB to db, Zips.JSON to Zips.SETTINGS_JSON))
        assertRefusedAndClean("target in a missing directory", ByteArrayInputStream(zip), unwritable)
    }

    /** AC-2: a file that is no archive at all has no entries: no database is reported, nothing is written. */
    @Test
    fun ac2_notAnArchive_reportsNoEntriesOrThrows() {
        val targets = targets()
        val found = try {
            ZipStaging.extract(ByteArrayInputStream("this is a text file, not a zip".toByteArray()), targets)
        } catch (e: IOException) {
            emptySet()
        }
        assertTrue(found.isEmpty())
        assertEquals(emptyMap<String, String>(), dir.snapshot())
    }
}
