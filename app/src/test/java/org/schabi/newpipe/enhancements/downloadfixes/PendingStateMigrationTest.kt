package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.enhancements.downloadfixes.PendingStateFiles.MigrationResult
import java.io.File
import java.util.PriorityQueue

/**
 * AC-4 (S4), plan section 4: `PendingStateFiles.migrate(oldDir, newDir)`, the move of the
 * saved state from the app's external directory to its private one, on real directories.
 *
 * "State saved by an older version of the app is moved there at the first start." The move
 * runs at every start and can be killed at any point; the plan's claim is "No state is lost
 * and none appears twice". The interruption tests prepare the directories a kill leaves behind
 * and then do what `PendingStateAndroid.prepare` does at the next start:
 * `deleteTemporaries(newDir)`, then `migrate`.
 */
class PendingStateMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var oldDir: File
    private lateinit var newDir: File

    @Before
    fun directories() {
        oldDir = File(temp.newFolder("external"), "pending_downloads").apply { mkdir() }
        newDir = File(temp.newFolder("internal"), "pending_downloads").apply { mkdir() } // made by getPendingDir before prepare
    }

    private fun names(dir: File): List<String> = dir.listFiles()!!.map { it.name }.sorted()

    private fun state(version: Int): ByteArray = serialized(SavedState(version))

    private fun nextStart(): MigrationResult {
        PendingStateFiles.deleteTemporaries(newDir)
        return PendingStateFiles.migrate(oldDir, newDir)
    }

    private val aside: File get() = File(newDir, "set_aside")

    // ---- the move ----

    /** AC-4: every state file of the old directory is in the new one with its bytes, and gone from the old one. */
    @Test
    fun ac4_move_everyFileArrivesWithItsBytes_andLeavesTheOldDirectory() {
        val files = mapOf("1731628800001" to state(1), "1731628800002" to state(2), "1731628800003" to state(3))
        files.forEach { (name, bytes) -> File(oldDir, name).writeBytes(bytes) }

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(MigrationResult(moved = 3, duplicatesRemoved = 0, setAside = 0, failed = 0), result)
        assertEquals(files.keys.sorted(), names(newDir))
        files.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(newDir, name).readBytes()) }
        assertFalse("plan: 'Then oldDir is removed if empty'", oldDir.exists())
    }

    /** AC-4: after the move the files are read from the new place like any saved state. */
    @Test
    fun ac4_move_theMovedStateIsReadable() {
        File(oldDir, "1731628800001").writeBytes(state(11))

        PendingStateFiles.migrate(oldDir, newDir)

        val read = PendingStateFiles.read(File(newDir, "1731628800001")) { null }
        assertEquals(SavedState(11), (read as PendingStateFiles.ReadResult.Ok).value)
    }

    /**
     * AC-4, plan: "The move never deserializes: it copies bytes." Files that are no valid
     * state (truncated by the old version, planted by another app, a refused class) are moved
     * byte for byte like the others; the loader decides about them afterwards, and sets them
     * aside (`PendingStateFilesTest`). Nothing is lost on the way.
     */
    @Test
    fun ac4_move_doesNotDeserialize_unreadableFilesAreMovedAsBytes() {
        val files = mapOf(
            "1731628800001" to state(1).copyOfRange(0, 40),
            "1731628800002" to ByteArray(0),
            "1731628800003" to bytesOf(3000, seed = 3),
            "1731628800004" to serialized(PriorityQueue(listOf(2, 1))),
        )
        files.forEach { (name, bytes) -> File(oldDir, name).writeBytes(bytes) }

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(MigrationResult(4, 0, 0, 0), result)
        files.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(newDir, name).readBytes()) }
        assertEquals("nothing is set aside by the move itself", files.keys.sorted(), names(newDir))
    }

    /** AC-4: a large state file (a mission with many blocks) is moved whole. */
    @Test
    fun ac4_move_aLargeFileIsMovedWhole() {
        val large = bytesOf(5 * 1024 * 1024 + 123, seed = 4)
        File(oldDir, "1731628800001").writeBytes(large)

        assertEquals(MigrationResult(1, 0, 0, 0), PendingStateFiles.migrate(oldDir, newDir))

        assertArrayEquals(large, File(newDir, "1731628800001").readBytes())
    }

    /** AC-4, plan 1.: upstream's probe file `.tmp` and unfinished `*.new` files of the old directory are deleted, not moved. */
    @Test
    fun ac4_move_probeAndTemporariesOfTheOldDirectoryAreDeleted_notMoved() {
        File(oldDir, ".tmp").writeBytes(ByteArray(0))
        File(oldDir, "1731628800001.new").writeBytes(state(1).copyOfRange(0, 20))
        File(oldDir, "1731628800002").writeBytes(state(2))

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(MigrationResult(moved = 1, duplicatesRemoved = 0, setAside = 0, failed = 0), result)
        assertEquals(listOf("1731628800002"), names(newDir))
        assertFalse(oldDir.exists())
    }

    /** AC-4: files that are already in the new directory (missions started by this version) are not touched by a move of others. */
    @Test
    fun ac4_move_leavesTheOtherFilesOfTheNewDirectoryAlone() {
        File(newDir, "1731628899999").writeBytes(state(99))
        File(aside.apply { mkdir() }, "1731620000000").writeBytes(byteArrayOf(1))
        File(oldDir, "1731628800001").writeBytes(state(1))

        assertEquals(MigrationResult(1, 0, 0, 0), PendingStateFiles.migrate(oldDir, newDir))

        assertEquals(listOf("1731628800001", "1731628899999", "set_aside"), names(newDir))
        assertArrayEquals(state(99), File(newDir, "1731628899999").readBytes())
        assertArrayEquals(byteArrayOf(1), File(aside, "1731620000000").readBytes())
    }

    // ---- nothing to do ----

    /** AC-4, plan: "does nothing when ... oldDir is not a directory": a fresh install, or a later start. */
    @Test
    fun ac4_missingOldDirectory_nothingHappens() {
        assertTrue(oldDir.delete())
        File(newDir, "1731628899999").writeBytes(state(99))

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(MigrationResult(0, 0, 0, 0), result)
        assertFalse("the old directory is not created", oldDir.exists())
        assertEquals(listOf("1731628899999"), names(newDir))
    }

    /** AC-4: a regular file where the old directory would be: nothing happens, it is not deleted. */
    @Test
    fun ac4_oldDirectoryIsAFile_nothingHappens() {
        assertTrue(oldDir.delete())
        oldDir.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(MigrationResult(0, 0, 0, 0), PendingStateFiles.migrate(oldDir, newDir))

        assertArrayEquals(byteArrayOf(1, 2, 3), oldDir.readBytes())
        assertEquals(emptyList<String>(), names(newDir))
    }

    /** AC-4, plan section 5: "old directory removed when empty": also when it was empty from the start. */
    @Test
    fun ac4_emptyOldDirectory_isRemoved() {
        assertEquals(MigrationResult(0, 0, 0, 0), PendingStateFiles.migrate(oldDir, newDir))

        assertFalse(oldDir.exists())
        assertTrue("only that directory, not its parent (the app's external files directory)", oldDir.parentFile!!.isDirectory)
    }

    /** AC-4: the second start after a complete move finds nothing to do and changes nothing. */
    @Test
    fun ac4_secondStart_nothingToDo() {
        File(oldDir, "1731628800001").writeBytes(state(1))
        PendingStateFiles.migrate(oldDir, newDir)

        val second = nextStart()

        assertEquals(MigrationResult(0, 0, 0, 0), second)
        assertEquals(listOf("1731628800001"), names(newDir))
        assertArrayEquals(state(1), File(newDir, "1731628800001").readBytes())
    }

    /**
     * AC-4: "for each regular file". A sub-directory of the old directory is not state; it is
     * neither moved nor deleted, and the old directory stays because it is not empty.
     */
    @Test
    fun ac4_subDirectoryOfTheOldDirectory_isLeftAlone() {
        val sub = File(oldDir, "something").apply { mkdir() }
        File(sub, "inside").writeBytes(byteArrayOf(7))
        File(oldDir, "1731628800001").writeBytes(state(1))

        assertEquals(MigrationResult(1, 0, 0, 0), PendingStateFiles.migrate(oldDir, newDir))

        assertArrayEquals(byteArrayOf(7), File(sub, "inside").readBytes())
        assertEquals(listOf("something"), names(oldDir))
        assertEquals(listOf("1731628800001"), names(newDir))
    }

    // ---- interruption points (plan section 5) ----

    /**
     * AC-4, killed before the rename: the old file is intact, the new directory holds
     * `<name>.new` with a prefix of it. For every prefix length, the next start completes the
     * move: the whole state in the new directory, once.
     */
    @Test
    fun ac4_killedDuringTheCopy_atEveryLength_theNextStartCompletesTheMove() {
        val bytes = state(21)
        for (length in (0..bytes.size step 173) + bytes.size) {
            File(oldDir.apply { mkdirs() }, "1731628800001").writeBytes(bytes)
            File(newDir, "1731628800001").delete()
            File(newDir, "1731628800001.new").writeBytes(bytes.copyOfRange(0, length))

            val result = nextStart()

            assertEquals("killed after $length bytes", MigrationResult(moved = 1, duplicatesRemoved = 0, setAside = 0, failed = 0), result)
            assertEquals("killed after $length bytes", listOf("1731628800001"), names(newDir))
            assertArrayEquals("killed after $length bytes", bytes, File(newDir, "1731628800001").readBytes())
            assertFalse(oldDir.exists())
        }
    }

    /**
     * AC-4, killed after the rename and before the delete: both copies exist and are equal.
     * Plan 3.: "same bytes -> the old one is a finished move, it is deleted."
     */
    @Test
    fun ac4_killedAfterTheRename_bothCopiesEqual_theOldOneIsRemoved() {
        File(oldDir, "1731628800001").writeBytes(state(22))
        File(newDir, "1731628800001").writeBytes(state(22))
        File(oldDir, "1731628800002").writeBytes(state(23)) // not yet reached by the killed move

        val result = nextStart()

        assertEquals(MigrationResult(moved = 1, duplicatesRemoved = 1, setAside = 0, failed = 0), result)
        assertEquals("no state appears twice", listOf("1731628800001", "1731628800002"), names(newDir))
        assertArrayEquals(state(22), File(newDir, "1731628800001").readBytes())
        assertArrayEquals(state(23), File(newDir, "1731628800002").readBytes())
        assertFalse(oldDir.exists())
    }

    /**
     * AC-4, killed after the rename, and the app has saved newer state since. Plan 3.:
     * "Different bytes -> the internal file wins (it is this version's newer state); the old
     * one is copied to `newDir/set_aside/` and deleted". Neither state is lost.
     */
    @Test
    fun ac4_bothCopiesDifferent_theInternalOneWins_theOldOneIsKeptAside() {
        File(oldDir, "1731628800001").writeBytes(state(30))
        File(newDir, "1731628800001").writeBytes(state(31))

        val result = nextStart()

        assertEquals(MigrationResult(moved = 0, duplicatesRemoved = 0, setAside = 1, failed = 0), result)
        assertArrayEquals("the internal, newer state is untouched", state(31), File(newDir, "1731628800001").readBytes())
        assertArrayEquals("the old state is kept aside", state(30), File(aside, "1731628800001").readBytes())
        assertEquals(listOf("1731628800001", "set_aside"), names(newDir))
        assertEquals(listOf("1731628800001"), names(aside))
        assertFalse(oldDir.exists())
    }

    /**
     * AC-4, on Android 7-10 another app can write into the old directory: a file planted there
     * under the name of a mission of this version never replaces the private state.
     */
    @Test
    fun ac4_plantedFileWithTheNameOfAPrivateMission_neverReplacesIt() {
        File(newDir, "1731628800001").writeBytes(state(40))
        val planted = serialized(PriorityQueue(listOf(1)))

        repeat(3) { // planted again before each of three starts
            File(oldDir.apply { mkdirs() }, "1731628800001").writeBytes(planted + byteArrayOf(it.toByte()))
            val result = nextStart()
            assertEquals(MigrationResult(0, 0, 1, 0), result)
            assertArrayEquals(state(40), File(newDir, "1731628800001").readBytes())
        }

        assertEquals("each planted file is kept, none overwrites an earlier one", 3, names(aside).size)
        assertTrue("the first one under its own name, was ${names(aside)}", "1731628800001" in names(aside))
        assertEquals(
            (0..2).map { (planted + byteArrayOf(it.toByte())).toList() }.toSet(),
            aside.listFiles()!!.map { it.readBytes().toList() }.toSet()
        )
    }

    /** AC-4: both copies of different length with a common prefix are different (a truncated old copy is not "the same"). */
    @Test
    fun ac4_oldCopyIsAPrefixOfTheInternalOne_isNotTakenForEqual() {
        val whole = state(50)
        File(newDir, "1731628800001").writeBytes(whole)
        File(oldDir, "1731628800001").writeBytes(whole.copyOfRange(0, whole.size - 1))

        assertEquals(MigrationResult(0, 0, 1, 0), nextStart())

        assertArrayEquals(whole, File(newDir, "1731628800001").readBytes())
        assertArrayEquals(whole.copyOfRange(0, whole.size - 1), File(aside, "1731628800001").readBytes())
    }

    // ---- failures ----

    /**
     * AC-4, plan 2.: "A copy that fails ... leaves the old file and counts as failed; tried
     * again next start." The copy of one file is made to fail (its `.new` cannot be created);
     * the other files are moved; the old directory stays; nothing is deleted that was not copied.
     */
    @Test
    fun ac4_copyThatFails_leavesTheOldFile_andTheOthersAreMoved() {
        File(oldDir, "1731628800001").writeBytes(state(1))
        File(oldDir, "1731628800002").writeBytes(state(2))
        File(oldDir, "1731628800003").writeBytes(state(3))
        val inTheWay = File(newDir, "1731628800002.new").apply { mkdir() }
        File(inTheWay, "keep").writeBytes(byteArrayOf(1))

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(MigrationResult(moved = 2, duplicatesRemoved = 0, setAside = 0, failed = 1), result)
        assertArrayEquals("AC-4: the state that could not be copied is still in the old place", state(2), File(oldDir, "1731628800002").readBytes())
        assertEquals(listOf("1731628800002"), names(oldDir))
        assertFalse("no half state under the real name", File(newDir, "1731628800002").exists())
        assertArrayEquals(state(1), File(newDir, "1731628800001").readBytes())
        assertArrayEquals(state(3), File(newDir, "1731628800003").readBytes())

        // "tried again next start": the obstacle is gone
        assertTrue(inTheWay.deleteRecursively())
        assertEquals(MigrationResult(1, 0, 0, 0), nextStart())
        assertArrayEquals(state(2), File(newDir, "1731628800002").readBytes())
        assertFalse(oldDir.exists())
    }

    /**
     * AC-4: when the new directory cannot take anything (here: it is not a directory), every
     * file fails and every old file is still there. A move that cannot copy never deletes.
     */
    @Test
    fun ac4_newDirectoryUnusable_nothingIsDeleted() {
        File(oldDir, "1731628800001").writeBytes(state(1))
        File(oldDir, "1731628800002").writeBytes(state(2))
        assertTrue(newDir.delete())
        newDir.writeBytes(byteArrayOf(0))

        val result = PendingStateFiles.migrate(oldDir, newDir)

        assertEquals(0, result.moved)
        assertEquals(2, result.failed)
        assertArrayEquals(state(1), File(oldDir, "1731628800001").readBytes())
        assertArrayEquals(state(2), File(oldDir, "1731628800002").readBytes())
    }

    // ---- everything at once ----

    /**
     * AC-4, "No state is lost and none appears twice": one start over an old directory in every
     * condition at once. Afterwards every state that existed before is found exactly where the
     * plan says, and the counts say what happened.
     */
    @Test
    fun ac4_everyCaseInOneStart_noStateIsLost_noneAppearsTwice() {
        File(oldDir, ".tmp").writeBytes(ByteArray(0))
        File(oldDir, "1731628800001").writeBytes(state(1)) // to move
        File(oldDir, "1731628800002").writeBytes(state(2)) // to move, a killed copy is in the way
        File(newDir, "1731628800002.new").writeBytes(state(2).copyOfRange(0, 9))
        File(oldDir, "1731628800003").writeBytes(state(3)) // already moved
        File(newDir, "1731628800003").writeBytes(state(3))
        File(oldDir, "1731628800004").writeBytes(state(4)) // already moved, newer state saved since
        File(newDir, "1731628800004").writeBytes(state(44))
        File(oldDir, "1731628800005.new").writeBytes(byteArrayOf(1)) // never the only copy
        File(newDir, "1731628800006").writeBytes(state(6)) // this version's own

        val result = nextStart()

        assertEquals(MigrationResult(moved = 2, duplicatesRemoved = 1, setAside = 1, failed = 0), result)
        assertEquals(
            listOf("1731628800001", "1731628800002", "1731628800003", "1731628800004", "1731628800006", "set_aside"),
            names(newDir)
        )
        assertArrayEquals(state(1), File(newDir, "1731628800001").readBytes())
        assertArrayEquals(state(2), File(newDir, "1731628800002").readBytes())
        assertArrayEquals(state(3), File(newDir, "1731628800003").readBytes())
        assertArrayEquals(state(44), File(newDir, "1731628800004").readBytes())
        assertArrayEquals(state(6), File(newDir, "1731628800006").readBytes())
        assertEquals(listOf("1731628800004"), names(aside))
        assertArrayEquals(state(4), File(aside, "1731628800004").readBytes())
        assertFalse(oldDir.exists())

        assertEquals("and the start after that has nothing to do", MigrationResult(0, 0, 0, 0), nextStart())
    }
}
