package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.enhancements.downloadfixes.PendingStateFiles.ReadResult
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AC-2 (D6), plan section 2: `PendingStateFiles`, on real files in a temporary directory.
 *
 * "The saved state of a pending download survives a process kill at any moment ... A state
 * file that cannot be read is reported and kept aside, not deleted silently."
 *
 * A kill cannot be placed inside `writeAtomically` from a test. What a kill leaves behind is
 * known, though: the complete previous state under the real name, and possibly a `.new` file
 * with any prefix of the new state; or, after the rename, the complete new state. The kill
 * tests prepare each of these directories and then do what the next start does.
 */
class PendingStateFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val noPlugins: (String) -> Class<*>? = { null }

    /** The pending directory with one mission file that holds [state], as `DownloadManager.startMission` leaves it. */
    private fun pendingDir(): File = temp.newFolder("pending_downloads")

    private fun missionFile(dir: File, name: String = "1731628800000", state: Serializable? = SavedState(1)): File {
        val file = File(dir, name)
        assertTrue(file.createNewFile()) // DownloadManager.java:239
        if (state != null) PendingStateFiles.writeAtomically(file, state)
        return file
    }

    private fun names(dir: File): List<String> = dir.listFiles()!!.map { it.name }.sorted()

    private fun readOk(file: File): Any {
        val result = PendingStateFiles.read(file, noPlugins)
        if (result is ReadResult.Unreadable) throw AssertionError("expected a readable state in $file", result.cause)
        return (result as ReadResult.Ok).value
    }

    private fun readUnreadable(file: File): ReadResult.Unreadable {
        val result = PendingStateFiles.read(file, noPlugins)
        if (result is ReadResult.Ok) fail("expected $file to be unreadable, got ${result.value}")
        return result as ReadResult.Unreadable
    }

    // ---- constants ----

    /** Plan section 2: the names the loader and the migration rely on. */
    @Test
    fun ac2_theSuffixAndTheDirectoryName() {
        assertEquals(".new", PendingStateFiles.TEMP_SUFFIX)
        assertEquals("set_aside", PendingStateFiles.ASIDE_DIR)
    }

    // ---- writeAtomically ----

    /** AC-2: what is written is what is read back. */
    @Test
    fun ac2_writeThenRead_roundTrip() {
        val dir = pendingDir()
        val file = missionFile(dir, state = null)

        PendingStateFiles.writeAtomically(file, SavedState(7))

        assertEquals(SavedState(7), readOk(file))
        assertEquals("no temporary file is left, no directory is made", listOf(file.name), names(dir))
    }

    /** AC-2: a later save replaces the earlier one completely, also when it is shorter. */
    @Test
    fun ac2_write_replacesThePreviousState_alsoWithAShorterOne() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1, blocks = IntArray(50_000) { it }))
        val longLength = file.length()

        PendingStateFiles.writeAtomically(file, SavedState(2, blocks = IntArray(3)))

        assertTrue("the second state is shorter on disk", file.length() < longLength)
        assertEquals(SavedState(2, blocks = IntArray(3)), readOk(file))
        assertArrayEquals("the file is exactly the serialized state, without a tail", serialized(SavedState(2, blocks = IntArray(3))), file.readBytes())
    }

    /** AC-2: the file on disk is plain Java serialization, the format the loader of every version reads. */
    @Test
    fun ac5_write_keepsTheFileFormat() {
        val file = missionFile(pendingDir(), state = null)

        PendingStateFiles.writeAtomically(file, SavedState(3))

        assertArrayEquals(serialized(SavedState(3)), file.readBytes())
        assertEquals(SavedState(3), deserializedPlainly(file.readBytes()))
    }

    /** AC-2: the first save of a mission that has no file yet (the plan's target "always exists", but a missing one is no error). */
    @Test
    fun ac2_write_createsTheTargetWhenItDoesNotExist() {
        val dir = pendingDir()
        val file = File(dir, "1731628800001")

        PendingStateFiles.writeAtomically(file, SavedState(4))

        assertEquals(SavedState(4), readOk(file))
        assertEquals(listOf(file.name), names(dir))
    }

    /**
     * AC-2, plan section 5: "a write that fails mid-way (a `Serializable` whose `writeObject`
     * throws) leaves the old content and no `.new`". This is the save that upstream turns into
     * a truncated file.
     */
    @Test
    fun ac2_writeThatFailsMidWay_leavesTheOldContent_andNoTemporary() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1))
        val before = file.readBytes()

        try {
            PendingStateFiles.writeAtomically(file, FailsWhileWritten(IOException("disk full")))
            fail("writeAtomically must report the failure")
        } catch (e: IOException) {
            // expected
        }

        assertArrayEquals("AC-2: the previous state is untouched", before, file.readBytes())
        assertEquals("AC-2: the temporary file is deleted", listOf(file.name), names(dir))
        assertEquals(SavedState(1), readOk(file))
    }

    /**
     * AC-2, plan: a field changed while it was serialized (`ConcurrentModificationException`,
     * a `RuntimeException`) is a failed save like any other: old content, no temporary.
     */
    @Test
    fun ac2_writeThatFailsWithARuntimeException_leavesTheOldContent_andNoTemporary() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1))
        val before = file.readBytes()

        try {
            PendingStateFiles.writeAtomically(file, FailsWhileWritten(ConcurrentModificationException("headers changed")))
            fail("writeAtomically must report the failure")
        } catch (e: Exception) {
            // expected: the ConcurrentModificationException itself or an IOException around it
        }

        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(file.name), names(dir))
    }

    /** AC-2: a state that holds something not serializable fails before the target is touched. */
    @Test
    fun ac2_writeOfSomethingNotSerializable_leavesTheOldContent() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1))
        val before = file.readBytes()
        val notSerializable = arrayListOf<Any>(Thread.currentThread())

        try {
            PendingStateFiles.writeAtomically(file, notSerializable)
            fail("writeAtomically must report the failure")
        } catch (e: IOException) {
            // java.io.NotSerializableException
        }

        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(file.name), names(dir))
    }

    /** AC-2: when the temporary file cannot be created, the target is untouched and the failure is reported. */
    @Test
    fun ac2_writeWhenTheTemporaryCannotBeCreated_leavesTheOldContent() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1))
        val before = file.readBytes()
        val inTheWay = File(dir, file.name + ".new")
        assertTrue(inTheWay.mkdir())
        assertTrue(File(inTheWay, "keep").createNewFile())

        try {
            PendingStateFiles.writeAtomically(file, SavedState(2))
            fail("writeAtomically must report the failure")
        } catch (e: IOException) {
            // expected
        }

        assertArrayEquals(before, file.readBytes())
        assertEquals(SavedState(1), readOk(file))
    }

    // ---- tryWriteAtomically ----

    /** AC-2: `tryWriteAtomically` returns null on success and has written the state. */
    @Test
    fun ac2_tryWrite_success_returnsNull() {
        val file = missionFile(pendingDir(), state = SavedState(1))

        assertNull(PendingStateFiles.tryWriteAtomically(file, SavedState(2)))

        assertEquals(SavedState(2), readOk(file))
    }

    /**
     * AC-2, plan: "Same, never throws: null on success, else the failure (any Exception, e.g. a
     * `ConcurrentModificationException`...)". The save runs on download threads; an exception
     * escaping from it would end the thread, and the failure must reach the log (`Utility.java`).
     */
    @Test
    fun ac2_tryWrite_failure_returnsTheException_neverThrows_keepsTheOldContent() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(1))
        val before = file.readBytes()

        val io = PendingStateFiles.tryWriteAtomically(file, FailsWhileWritten(IOException("disk full")))
        val runtime = PendingStateFiles.tryWriteAtomically(file, FailsWhileWritten(ConcurrentModificationException("headers changed")))
        val notSerializable = PendingStateFiles.tryWriteAtomically(file, arrayListOf<Any>(Thread.currentThread()))

        assertNotNull("an IOException while writing is returned", io)
        assertNotNull("a RuntimeException while writing is returned", runtime)
        assertNotNull(notSerializable)
        assertTrue("the cause is recognisable, was $io", generateSequence<Throwable>(io) { it.cause }.any { it.message == "disk full" })
        assertTrue("the cause is recognisable, was $runtime", generateSequence<Throwable>(runtime) { it.cause }.any { it is ConcurrentModificationException })
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(file.name), names(dir))
    }

    // ---- a kill at every moment ----

    /**
     * AC-2, a kill while the new state is being written: the directory holds the previous state
     * and a `.new` file with a prefix of the new one. For every prefix length: the next start
     * (`deleteTemporaries`, then `read`) finds the previous state, complete, and no rest.
     */
    @Test
    fun ac2_killWhileWriting_atEveryByte_thePreviousStateIsRead() {
        val dir = pendingDir()
        val previous = SavedState(41)
        val next = serialized(SavedState(42))
        val file = missionFile(dir, state = previous)
        val previousBytes = file.readBytes()

        for (length in (0..next.size step 97) + next.size) {
            File(dir, file.name + ".new").writeBytes(next.copyOfRange(0, length))

            assertEquals("kill after $length bytes: temporaries removed", 1, PendingStateFiles.deleteTemporaries(dir))
            assertEquals("kill after $length bytes", previous, readOk(file))
            assertArrayEquals(previousBytes, file.readBytes())
            assertEquals("kill after $length bytes: nothing else in the directory", listOf(file.name), names(dir))
        }
    }

    /**
     * AC-2, the same kill when the start does not clean up first: `read` of the real name never
     * looks at the `.new` file, so the download is in the list with its last saved progress.
     */
    @Test
    fun ac2_killWhileWriting_readNeverTakesTheTemporary() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(41))
        val temporary = File(dir, file.name + ".new")
        temporary.writeBytes(serialized(SavedState(42)))

        assertEquals(SavedState(41), readOk(file))
        assertTrue("read does not touch the temporary", temporary.isFile)
        assertFalse("nothing was set aside", File(dir, "set_aside").exists())
    }

    /** AC-2, a kill after the rename: the new state is complete under the real name. */
    @Test
    fun ac2_killAfterTheRename_theNewStateIsRead() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(41))
        PendingStateFiles.writeAtomically(file, SavedState(42))

        assertEquals(0, PendingStateFiles.deleteTemporaries(dir))
        assertEquals(SavedState(42), readOk(file))
    }

    /**
     * AC-2, with real threads: while one thread saves a state a thousand times, another reads
     * the file as a restart would at that moment. Every single read finds a complete state, and
     * the versions never go backwards. Upstream's in-place rewrite fails this within a few
     * reads: the reader sees the truncated file.
     */
    @Test
    fun ac2_aReaderAtAnyMoment_alwaysSeesACompleteState() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(0))
        val writerDone = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val reads = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        Thread {
            try {
                start.await()
                for (version in 1..1000) PendingStateFiles.writeAtomically(file, SavedState(version))
            } catch (e: Throwable) {
                failure.compareAndSet(null, e)
            } finally {
                writerDone.set(true)
                done.countDown()
            }
        }.start()
        Thread {
            try {
                start.await()
                var last = 0
                while (true) {
                    val finished = writerDone.get() // read before the file: one more read after the last save
                    val state = try {
                        deserializedPlainly(file.readBytes()) as SavedState
                    } catch (e: Exception) {
                        throw AssertionError("read ${reads.get()} after version $last found an incomplete state", e)
                    }
                    reads.incrementAndGet()
                    if (state.version < last) throw AssertionError("version ${state.version} after $last")
                    if (state != SavedState(state.version)) throw AssertionError("version ${state.version} is damaged")
                    last = state.version
                    if (finished) break
                }
                if (last != 1000) throw AssertionError("the last read found version $last")
            } catch (e: Throwable) {
                failure.compareAndSet(null, e)
            } finally {
                done.countDown()
            }
        }.start()
        start.countDown()
        done.awaitOrFail("writer and reader")

        failure.get()?.let { throw AssertionError("writer or reader failed after ${reads.get()} reads", it) }
        assertEquals(listOf(file.name), names(dir))
    }

    /**
     * AC-2: several missions are saved at the same time by their own threads into one
     * directory (every mission saves under its own lock only). Every file ends as the last
     * state of its own mission; nothing is mixed up, no temporary is left.
     */
    @Test
    fun ac2_missionsSavedConcurrently_eachFileHoldsItsOwnLastState() {
        val dir = pendingDir()
        val missions = 6
        val files = List(missions) { missionFile(dir, name = "17316288000${10 + it}", state = SavedState(it * 1000)) }
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(missions)

        repeat(missions) { index ->
            Thread {
                try {
                    start.await()
                    for (version in 1..200) PendingStateFiles.writeAtomically(files[index], SavedState(index * 1000 + version))
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.awaitOrFail("the six saving threads")

        failure.get()?.let { throw AssertionError("a save failed", it) }
        for (index in 0 until missions) assertEquals(SavedState(index * 1000 + 200), readOk(files[index]))
        assertEquals(files.map { it.name }.sorted(), names(dir))
    }

    /**
     * AC-2: two threads save the *same* mission at the same moment (the pause and the periodic
     * save are both under the mission's lock today, `DownloadManager.java:253` is not). Whatever
     * the order, the file is one of the states that were saved, complete, and no save fails.
     * See Q5: with one temporary name per target this needs the writers of a target in turn.
     */
    @Test
    fun ac2_sameMissionSavedByTwoThreads_theFileIsAlwaysOneCompleteState() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(0))
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) { index ->
            Thread {
                try {
                    start.await()
                    for (version in 1..400) {
                        // sizes differ, so that an interleaved write cannot pass for a state
                        val state = SavedState(index * 1000 + version, blocks = IntArray(if (index == 0) 4000 else 9000) { it })
                        PendingStateFiles.writeAtomically(file, state)
                        val onDisk = deserializedPlainly(file.readBytes()) as SavedState
                        if (onDisk.blocks.size != 4000 && onDisk.blocks.size != 9000) throw AssertionError("damaged state on disk")
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.awaitOrFail("the two saving threads")

        failure.get()?.let { throw AssertionError("a save failed or left a damaged file", it) }
        val last = readOk(file) as SavedState
        assertTrue("the file holds the last save of one of the two threads, was $last", last.version == 400 || last.version == 1400)
        assertEquals(listOf(file.name), names(dir))
    }

    // ---- deleteTemporaries ----

    /** AC-2, plan: "Deletes every `*.new` in dir ... Returns the count." Everything else stays. */
    @Test
    fun ac2_deleteTemporaries_deletesOnlyTheTemporaries_andCountsThem() {
        val dir = pendingDir()
        val first = missionFile(dir, name = "1731628800001", state = SavedState(1))
        val second = missionFile(dir, name = "1731628800002", state = SavedState(2))
        File(dir, "1731628800001.new").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "1731628800003.new").writeBytes(ByteArray(0))
        File(dir, ".tmp").writeBytes(ByteArray(0)) // upstream's probe file, not ours to delete here
        File(dir, "newer").writeBytes(byteArrayOf(9)) // "new" without the dot, and not at the end
        File(dir, "1731628800004.new.1").writeBytes(byteArrayOf(9))
        val aside = File(dir, "set_aside").apply { mkdir() }
        File(aside, "1731628800000").writeBytes(byteArrayOf(5))
        File(aside, "1731628800009.new").writeBytes(byteArrayOf(6)) // a set-aside file is kept whatever its name

        assertEquals(2, PendingStateFiles.deleteTemporaries(dir))

        assertEquals(listOf(".tmp", "1731628800001", "1731628800002", "1731628800004.new.1", "newer", "set_aside"), names(dir))
        assertEquals(listOf("1731628800000", "1731628800009.new"), names(aside))
        assertEquals(SavedState(1), readOk(first))
        assertEquals(SavedState(2), readOk(second))
        assertEquals("a second run finds nothing", 0, PendingStateFiles.deleteTemporaries(dir))
    }

    /** AC-2: an empty directory and one that does not exist: nothing to delete, no exception. */
    @Test
    fun ac2_deleteTemporaries_emptyOrMissingDirectory() {
        val dir = pendingDir()
        assertEquals(0, PendingStateFiles.deleteTemporaries(dir))
        assertEquals(0, PendingStateFiles.deleteTemporaries(File(dir, "missing")))
        assertFalse(File(dir, "missing").exists())
    }

    // ---- read: files that cannot be read ----

    /**
     * AC-2, the file upstream's rewrite leaves after a kill: the state cut off at any length.
     * For every length: `read` does not throw, reports it as unreadable with a cause, and the
     * file is in `set_aside/` with its bytes; it is not deleted. (Upstream: `sub.delete()`.)
     */
    @Test
    fun ac2_truncatedFile_atEveryLength_isSetAside_notDeleted() {
        val whole = serialized(SavedState(5))
        var index = 0
        for (length in (0 until whole.size step 131) + (whole.size - 1)) {
            val dir = temp.newFolder("truncated-${index++}")
            val file = File(dir, "1731628800000")
            val content = whole.copyOfRange(0, length)
            file.writeBytes(content)

            val result = readUnreadable(file)

            val keptAt = result.keptAt
            assertNotNull("cut at $length: the cause is given", result.cause)
            assertNotNull("cut at $length: the file was moved", keptAt)
            assertEquals("cut at $length", File(File(dir, "set_aside"), "1731628800000"), keptAt)
            assertArrayEquals("cut at $length: kept with its bytes", content, keptAt!!.readBytes())
            assertFalse("cut at $length: it is no longer among the pending missions", file.exists())
            assertEquals(listOf("set_aside"), names(dir))
        }
    }

    /** AC-2, plan section 5: an empty file (the state right after `createNewFile`, or a kill right after the truncation). */
    @Test
    fun ac2_emptyFile_isSetAside() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000").apply { createNewFile() }

        val result = readUnreadable(file)

        assertEquals(File(File(dir, "set_aside"), "1731628800000"), result.keptAt)
        assertEquals(0L, result.keptAt!!.length())
        assertFalse(file.exists())
    }

    /** AC-2, plan section 5: garbage. */
    @Test
    fun ac2_garbage_isSetAside() {
        val dir = pendingDir()
        for ((n, content) in listOf("x\n".toByteArray(), bytesOf(5000, seed = 1), ByteArray(4096), "<html>".toByteArray()).withIndex()) {
            val file = File(dir, "173162880000$n")
            file.writeBytes(content)

            val result = readUnreadable(file)

            assertArrayEquals(content, result.keptAt!!.readBytes())
            assertFalse(file.exists())
        }
        assertEquals(listOf("set_aside"), names(dir))
        assertEquals(4, names(File(dir, "set_aside")).size)
    }

    /** AC-2: a complete state followed by garbage is still the state (`readObject` reads one object, as upstream does). */
    @Test
    fun ac5_stateFollowedByOtherBytes_isRead() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        file.writeBytes(serialized(SavedState(6)) + bytesOf(100, seed = 2))

        assertEquals(SavedState(6), readOk(file))
        assertTrue(file.isFile)
    }

    /**
     * AC-2/AC-4, plan: "wrong class, refused class" end in `set_aside/` too. The cause names
     * the class, so that the log says what to add to the list.
     */
    @Test
    fun ac2_refusedClass_isSetAside_andTheCauseNamesTheClass() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        val content = serialized(PriorityQueue(listOf(3, 1, 2)))
        file.writeBytes(content)

        val result = readUnreadable(file)

        assertTrue("the cause names the class, was ${result.cause}", result.cause.toString().contains("java.util.PriorityQueue"))
        assertArrayEquals(content, result.keptAt!!.readBytes())
        assertFalse(file.exists())
    }

    /**
     * AC-2, plan section 5: "a huge declared array length is survived". The stream says a byte
     * array of 2^31-1 elements follows; `read` catches the `OutOfMemoryError` (or whatever the
     * runtime answers) and the next file is read as usual.
     */
    @Test
    fun ac2_hugeDeclaredArrayLength_isSurvived() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        val stream = serialized(ByteArray(10))
        // TC_ARRAY, class description, then the length as 4 bytes, then the 10 elements
        val lengthAt = stream.size - 10 - 4
        assertArrayEquals("the length field is where the test expects it", byteArrayOf(0, 0, 0, 10), stream.copyOfRange(lengthAt, lengthAt + 4))
        byteArrayOf(0x7f, -1, -1, -1).copyInto(stream, lengthAt)
        file.writeBytes(stream)
        val healthy = missionFile(dir, name = "1731628800001", state = SavedState(8))

        val result = readUnreadable(file)

        assertNotNull(result.cause)
        assertArrayEquals(stream, result.keptAt!!.readBytes())
        assertEquals("the next file is read as usual", SavedState(8), readOk(healthy))
    }

    /** AC-2: the same for an array of objects (2^31-1 references). */
    @Test
    fun ac2_hugeDeclaredObjectArrayLength_isSurvived() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        val stream = serialized(arrayOfNulls<String>(1))
        val lengthAt = stream.size - 1 - 4 // one TC_NULL after the length
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), stream.copyOfRange(lengthAt, lengthAt + 4))
        byteArrayOf(0x7f, -1, -1, -1).copyInto(stream, lengthAt)
        file.writeBytes(stream)

        val result = readUnreadable(file)

        assertArrayEquals(stream, result.keptAt!!.readBytes())
        assertFalse(file.exists())
    }

    /**
     * AC-2, plan: `read` "catches ... StackOverflowError". A state nested deeper than the stack
     * of the reading thread (written here by a thread with a large stack) is set aside.
     */
    @Test
    fun ac2_stateNestedDeeperThanTheStack_isSurvived() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        val written = AtomicReference<ByteArray>()
        val failure = AtomicReference<Throwable>()
        val writer = Thread(null, {
            try {
                var nested: ArrayList<Any> = ArrayList()
                repeat(100_000) { nested = arrayListOf(nested) }
                written.set(serialized(nested))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "deep-writer", 2048L * 1024 * 1024)
        writer.start()
        writer.join()
        failure.get()?.let { throw AssertionError("the test could not build its file", it) }
        file.writeBytes(written.get())

        val result = readUnreadable(file)

        assertTrue("the cause is the overflow, was ${result.cause}", result.cause is StackOverflowError)
        assertArrayEquals(written.get(), result.keptAt!!.readBytes())
        assertFalse(file.exists())
    }

    /** AC-2: a file that is not there: unreadable, nothing to keep, nothing created in its place. */
    @Test
    fun ac2_missingFile_isUnreadable_withNothingKept() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")

        val result = readUnreadable(file)

        assertNull(result.keptAt)
        assertFalse(file.exists())
        assertTrue("no file appears anywhere", dir.walkTopDown().none { it.isFile })
    }

    /** AC-5: reading a good file leaves it where it is and sets nothing aside. */
    @Test
    fun ac5_readableFile_staysInPlace() {
        val dir = pendingDir()
        val file = missionFile(dir, state = SavedState(9))
        val before = file.readBytes()

        val result = PendingStateFiles.read(file, noPlugins)

        assertTrue(result is ReadResult.Ok)
        assertEquals(SavedState(9), (result as ReadResult.Ok).value)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(file.name), names(dir))
    }

    /** AC-4, plan: `read` goes "through AllowListObjectInputStream" with the resolver it is given. */
    @Test
    fun ac4_read_usesThePluginResolverItIsGiven() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        file.writeBytes(PluginPayload.streamWithPluginOnlyName("from a plugin"))
        val asked = ArrayList<String>()

        val result = PendingStateFiles.read(file) { name ->
            asked.add(name)
            if (name == PluginPayload.PLUGIN_ONLY_NAME) PluginPayload::class.java else null
        }

        assertTrue("readable with the plugin's class, was ${(result as? ReadResult.Unreadable)?.cause}", result is ReadResult.Ok)
        assertEquals(PluginPayload("from a plugin"), (result as ReadResult.Ok).value)
        assertTrue(PluginPayload.PLUGIN_ONLY_NAME in asked)
        assertTrue(file.isFile)

        // and without the plugin loaded: set aside, not lost
        val again = readUnreadable(file)
        assertEquals(File(File(dir, "set_aside"), "1731628800000"), again.keptAt)
    }

    // ---- setAside ----

    /** AC-2, plan: "Renames file to `<dir>/set_aside/<name>`" and returns the new place. */
    @Test
    fun ac2_setAside_movesTheFileWithItsBytes() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000")
        val content = bytesOf(3000, seed = 3)
        file.writeBytes(content)

        val keptAt = PendingStateFiles.setAside(file)

        assertEquals(File(File(dir, "set_aside"), "1731628800000"), keptAt)
        assertArrayEquals(content, keptAt!!.readBytes())
        assertFalse(file.exists())
    }

    /**
     * AC-2, plan: "then `<name>.1`, `.2`, ... if taken". Mission files are named by their
     * timestamp and names are free again once a file left the directory, so the same name can
     * be set aside more than once. No earlier file is overwritten: "kept aside, not deleted".
     */
    @Test
    fun ac2_setAside_sameNameAgain_getsAUniqueName_andOverwritesNothing() {
        val dir = pendingDir()
        val aside = File(dir, "set_aside")
        val contents = List(5) { bytesOf(100 + it, seed = 50L + it) }
        val kept = ArrayList<File>()

        for (content in contents) {
            val file = File(dir, "1731628800000")
            file.writeBytes(content)
            kept.add(PendingStateFiles.setAside(file)!!)
        }

        assertEquals(
            listOf("1731628800000", "1731628800000.1", "1731628800000.2", "1731628800000.3", "1731628800000.4"),
            kept.map { it.name }
        )
        assertTrue(kept.all { it.parentFile == aside })
        for ((n, content) in contents.withIndex()) assertArrayEquals("file $n kept its own bytes", content, kept[n].readBytes())
        assertEquals(listOf("set_aside"), names(dir))
    }

    /** AC-2: the same through `read`: three unreadable files of one name, three files kept. */
    @Test
    fun ac2_read_sameNameUnreadableAgain_keepsEveryFile() {
        val dir = pendingDir()
        val kept = (1..3).map { n ->
            val file = File(dir, "1731628800000")
            file.writeBytes(ByteArray(n) { n.toByte() })
            readUnreadable(file).keptAt!!
        }

        assertEquals(listOf("1731628800000", "1731628800000.1", "1731628800000.2"), kept.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), kept.map { it.length() })
    }

    /** AC-2: a gap in the numbering is filled or skipped, but an existing file is never replaced. */
    @Test
    fun ac2_setAside_neverReplacesAnExistingFile() {
        val dir = pendingDir()
        val aside = File(dir, "set_aside").apply { mkdir() }
        File(aside, "1731628800000").writeBytes(byteArrayOf(1))
        File(aside, "1731628800000.2").writeBytes(byteArrayOf(2))
        val file = File(dir, "1731628800000").apply { writeBytes(byteArrayOf(3)) }

        val keptAt = PendingStateFiles.setAside(file)!!

        assertArrayEquals(byteArrayOf(1), File(aside, "1731628800000").readBytes())
        assertArrayEquals(byteArrayOf(2), File(aside, "1731628800000.2").readBytes())
        assertArrayEquals(byteArrayOf(3), keptAt.readBytes())
        assertEquals(3, names(aside).size)
    }

    /** AC-2, plan: `setAside` returns null when the file could not be moved; here: there is none. */
    @Test
    fun ac2_setAside_ofAMissingFile_returnsNull() {
        val dir = pendingDir()

        assertNull(PendingStateFiles.setAside(File(dir, "1731628800000")))

        assertTrue(dir.walkTopDown().none { it.isFile })
    }

    /**
     * AC-2: the loader skips directories (`DownloadManager.java:147`) and `deleteTemporaries`
     * leaves `set_aside/` alone, so a file that was set aside is not read, moved or deleted by
     * any later start.
     */
    @Test
    fun ac2_whatWasSetAside_staysAcrossLaterStarts() {
        val dir = pendingDir()
        val file = File(dir, "1731628800000").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val keptAt = readUnreadable(file).keptAt!!
        val healthy = missionFile(dir, name = "1731628800001", state = SavedState(1))

        repeat(3) { // three starts
            PendingStateFiles.deleteTemporaries(dir)
            for (sub in dir.listFiles()!!.filter { it.isFile }) readOk(sub)
            PendingStateFiles.writeAtomically(healthy, SavedState(it + 2))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), keptAt.readBytes())
        assertEquals(listOf("1731628800001", "set_aside"), names(dir))
        assertEquals(listOf("1731628800000"), names(File(dir, "set_aside")))
    }
}
