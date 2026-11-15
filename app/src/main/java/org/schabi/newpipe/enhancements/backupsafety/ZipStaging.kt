package org.schabi.newpipe.enhancements.backupsafety

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads a backup archive once and copies the wanted entries to files next to the live ones. */
object ZipStaging {
    /**
     * Copies the named entries to their files and syncs them. Returns the names found. Deletes
     * all targets and rethrows on any error.
     *
     * The archive is read to its end, so the checksum of every entry is verified by
     * [ZipInputStream], also of entries that are not wanted. [zip] is not closed.
     */
    @Throws(IOException::class)
    fun extract(zip: InputStream, targets: Map<String, File>): Set<String> {
        val found = mutableSetOf<String>()
        val buffer = ByteArray(64 * 1024)
        try {
            val input = ZipInputStream(zip)
            while (true) {
                val entry = input.nextEntry ?: break
                val target = if (entry.isDirectory || entry.name in found) null else targets[entry.name]
                if (target == null) {
                    // read it anyway: reaching the end of an entry is what checks its CRC
                    while (input.read(buffer) >= 0) Unit
                } else {
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) {
                                break
                            }
                            out.write(buffer, 0, n)
                        }
                        out.flush()
                        out.fd.sync()
                    }
                    found += entry.name
                }
                input.closeEntry()
            }
        } catch (e: Throwable) {
            targets.values.forEach { it.delete() }
            throw if (e is IOException || e is Error) e else IOException("Unable to read the archive", e)
        }
        return found
    }
}
