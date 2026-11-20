package org.schabi.newpipe.enhancements.downloadfixes

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InvalidClassException
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.Serializable

/**
 * The files that hold the saved state of pending downloads: one file per download, plain Java
 * serialization, in one directory.
 *
 * - A save never touches the only copy: the new state is written to `<name>.new`, synced, and
 *   renamed over the old one. At any instant the real name holds one complete state.
 * - A file that cannot be read is moved to `set_aside/` in the same directory. Nothing here
 *   deletes a state file that is the only copy of its content.
 */
object PendingStateFiles {
    const val TEMP_SUFFIX = ".new"
    const val ASIDE_DIR = "set_aside"

    /** Upstream's probe file (`DownloadManager.testDir`). */
    private const val PROBE_NAME = ".tmp"

    /**
     * The writers of one target take turns: they share one temporary name. Stripes instead of
     * one lock, so that the sync of one download does not hold up the saves of the others;
     * instead of a lock per path, so that nothing accumulates.
     */
    private val writeLocks = Array(64) { Any() }

    private fun lockFor(target: File): Any {
        val locks = writeLocks
        return locks[(target.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
    }

    /**
     * Serializes to `<target>.new`, flushes, `FileDescriptor.sync()`, closes, then
     * `File.renameTo(target)` (rename(2): replaces atomically). On any failure the temporary
     * file is deleted and the target is untouched. A state that the reader would refuse
     * (a class outside [PendingStateAllowList]) is such a failure.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeAtomically(target: File, value: Serializable) {
        synchronized(lockFor(target)) {
            val temp = File(target.path + TEMP_SUFFIX)
            var replaced = false
            try {
                FileOutputStream(temp).use { file ->
                    val out = ReadableStateOutputStream(BufferedOutputStream(file))
                    out.writeObject(value)
                    out.flush()
                    file.fd.sync()
                }
                if (!temp.renameTo(target)) throw IOException("cannot rename $temp to $target")
                replaced = true
            } finally {
                // Result not checked: a temporary that stays is removed by deleteTemporaries
                // at the next start, and is never read as state.
                if (!replaced) temp.delete()
            }
        }
    }

    /**
     * Plain Java serialization, byte for byte (nothing is added to the stream), that fails
     * when the state holds a class the reader would refuse: such a file would be set aside
     * at the next start and the download would leave the list. Failing the save instead keeps
     * the previous, readable state; the exception names the class.
     */
    private class ReadableStateOutputStream(out: OutputStream) : ObjectOutputStream(out) {
        @Throws(IOException::class)
        override fun annotateClass(cl: Class<*>) {
            if (!PendingStateAllowList.isAcceptedOnRead(cl)) {
                throw InvalidClassException(cl.name, "not saved: the reader of pending download state would refuse this class")
            }
        }

        @Throws(IOException::class)
        override fun annotateProxyClass(cl: Class<*>) {
            throw InvalidClassException(cl.name, "not saved: the reader of pending download state refuses dynamic proxies")
        }
    }

    /**
     * Same, never throws: null on success, else the failure (any Exception, e.g. a
     * ConcurrentModificationException from a field changed while it was serialized).
     */
    @JvmStatic
    fun tryWriteAtomically(target: File, value: Serializable): Exception? = try {
        writeAtomically(target, value)
        null
    } catch (e: Exception) {
        e
    } catch (e: StackOverflowError) {
        IOException("state too deeply nested to be saved: $target", e)
    } catch (e: OutOfMemoryError) {
        IOException("not enough memory to save the state: $target", e)
    }

    /** Deletes every `*.new` in dir: by construction an unfinished write. Returns the count. */
    @JvmStatic
    fun deleteTemporaries(dir: File): Int {
        var deleted = 0
        for (file in dir.listFiles() ?: return 0) {
            val name = file.name
            if (!name.endsWith(TEMP_SUFFIX) || !file.isFile) continue
            // in turn with a writer of that target, whose temporary this may be
            val target = File(dir, name.substring(0, name.length - TEMP_SUFFIX.length))
            synchronized(lockFor(target)) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    sealed class ReadResult {
        class Ok(val value: Any) : ReadResult()

        /** keptAt: where the file is now; null if it could not be moved and stays in place. */
        class Unreadable(val cause: Throwable, val keptAt: File?) : ReadResult()
    }

    /**
     * Reads one object through [AllowListObjectInputStream]. A file that cannot be read
     * (missing, short, damaged, a refused class, too large or too deep for the memory or
     * the stack) is moved aside with [setAside] and reported as [ReadResult.Unreadable].
     */
    @JvmStatic
    fun read(file: File, pluginClasses: (String) -> Class<*>?): ReadResult {
        val failure: Throwable = try {
            // The file is closed by its own `use`: the constructor of the object stream reads
            // the header and throws on a damaged file, before any `use` of its own could start.
            val value = FileInputStream(file).use { raw ->
                AllowListObjectInputStream(BufferedInputStream(raw), pluginClasses).readObject()
            }
            if (value != null) return ReadResult.Ok(value)
            IOException("the file holds no object: $file")
        } catch (e: Exception) {
            e
        } catch (e: OutOfMemoryError) {
            e
        } catch (e: StackOverflowError) {
            e
        } catch (e: LinkageError) {
            e
        }
        return ReadResult.Unreadable(failure, setAside(file))
    }

    /**
     * Renames file to `<dir>/set_aside/<name>` (then `<name>.1`, `.2`, ... if taken). An
     * existing file is never replaced. Returns the new place, or null when the file is not
     * there or could not be moved; it then stays where it is.
     */
    @JvmStatic
    fun setAside(file: File): File? {
        if (!file.isFile) return null
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: return null
        val place = reserve(File(parent, ASIDE_DIR), file.name) ?: return null
        if (file.renameTo(place)) return place
        // Our own empty reservation. Result not checked: an empty file left in set_aside is harmless.
        place.delete()
        return null
    }

    /**
     * Creates an empty file under the first free name of [name], `name.1`, `name.2`, ... in
     * [dir] and returns it. `createNewFile` is atomic, so the name is ours: a rename onto it
     * replaces nothing but this reservation.
     */
    private fun reserve(dir: File, name: String): File? {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        for (n in 0 until MAX_ASIDE_NAMES) {
            val candidate = File(dir, if (n == 0) name else "$name.$n")
            try {
                if (candidate.createNewFile()) return candidate
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
        }
        return null
    }

    data class MigrationResult(val moved: Int, val duplicatesRemoved: Int, val setAside: Int, val failed: Int)

    /**
     * Moves the state files of [oldDir] into [newDir] (which exists) by copying bytes; nothing
     * is deserialized. Does nothing when [oldDir] is not a directory. For each regular file:
     *
     * 1. the probe file and `*.new`: deleted;
     * 2. no file of that name in [newDir]: copied to `<name>.new` there, synced, renamed, then
     *    the old file is deleted (`moved`);
     * 3. a file of that name exists in [newDir]: with the same bytes the old one is deleted
     *    (`duplicatesRemoved`); with different bytes the file in [newDir] stays, the old one is
     *    copied to `newDir/set_aside/` under a free name and deleted (`setAside`).
     *
     * An old file is deleted only after its bytes are complete in [newDir]. `failed` counts the
     * old files that are still there afterwards and are tried again at the next call: a copy
     * that failed, or an old file that could not be deleted after its copy (that one is also
     * counted under what was done with it). Then [oldDir] is removed if it is empty.
     */
    @JvmStatic
    fun migrate(oldDir: File, newDir: File): MigrationResult {
        val none = MigrationResult(0, 0, 0, 0)
        if (!oldDir.isDirectory) return none
        val files = oldDir.listFiles() ?: return none
        var moved = 0
        var duplicates = 0
        var aside = 0
        var failed = 0
        for (old in files.sortedBy { it.name }) {
            if (!old.isFile) continue
            if (old.name == PROBE_NAME || old.name.endsWith(TEMP_SUFFIX)) {
                // Never the only copy of a state. Result not checked: a file that stays is met
                // again at the next start, and keeps the old directory from being removed.
                old.delete()
                continue
            }
            val target = File(newDir, old.name)
            try {
                // in turn with a save of the same name: both use "<name>.new" in newDir
                val done = synchronized(lockFor(target)) {
                    val temp = File(target.path + TEMP_SUFFIX)
                    when {
                        !target.exists() -> {
                            copyThenRename(old, temp, target)
                            Done.MOVED
                        }
                        target.isFile && sameBytes(old, target) -> Done.DUPLICATE
                        isKeptAside(old, File(newDir, ASIDE_DIR)) -> Done.DUPLICATE
                        else -> {
                            val place = reserve(File(newDir, ASIDE_DIR), old.name)
                                ?: throw IOException("no place in $ASIDE_DIR for $old")
                            try {
                                copyThenRename(old, temp, place)
                            } catch (e: Exception) {
                                place.delete() // our own empty reservation
                                throw e
                            }
                            Done.SET_ASIDE
                        }
                    }
                }
                when (done) {
                    Done.MOVED -> moved++
                    Done.DUPLICATE -> duplicates++
                    Done.SET_ASIDE -> aside++
                }
                // Its bytes are in newDir. If it cannot be deleted it is met again at the next
                // start, as a duplicate.
                if (!old.delete()) failed++
            } catch (e: Exception) {
                failed++
            }
        }
        if (oldDir.listFiles()?.isEmpty() == true) {
            // Result not checked: an empty directory that stays is tried again at the next start.
            oldDir.delete()
        }
        return MigrationResult(moved, duplicates, aside, failed)
    }

    private enum class Done { MOVED, DUPLICATE, SET_ASIDE }

    /**
     * Copies [from] to [temp], syncs, checks the length, and renames [temp] to [place]. On any
     * failure [temp] is deleted and [place] is as it was.
     */
    @Throws(IOException::class)
    private fun copyThenRename(from: File, temp: File, place: File) {
        var renamed = false
        try {
            val copied = FileInputStream(from).use { input ->
                FileOutputStream(temp).use { output ->
                    val count = input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                    count
                }
            }
            if (temp.length() != copied) throw IOException("copy of $from is ${temp.length()} bytes, read $copied")
            if (!temp.renameTo(place)) throw IOException("cannot rename $temp to $place")
            renamed = true
        } finally {
            // Result not checked: see writeAtomically.
            if (!renamed) temp.delete()
        }
    }

    /** Whether [dir] already holds a copy of [file] under its name or one of its numbered names. */
    @Throws(IOException::class)
    private fun isKeptAside(file: File, dir: File): Boolean {
        val candidates = dir.listFiles() ?: return false
        val numbered = file.name + "."
        return candidates.any { kept ->
            kept.isFile && (kept.name == file.name || (kept.name.startsWith(numbered) && kept.name.substring(numbered.length).toIntOrNull() != null)) &&
                sameBytes(file, kept)
        }
    }

    @Throws(IOException::class)
    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        BufferedInputStream(FileInputStream(a)).use { first ->
            BufferedInputStream(FileInputStream(b)).use { second ->
                while (true) {
                    val x = first.read()
                    if (x != second.read()) return false
                    if (x == -1) return true
                }
            }
        }
    }

    private const val MAX_ASIDE_NAMES = 100_000
}
