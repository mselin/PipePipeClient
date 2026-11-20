package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.fail
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Files of `app/src/main`, read as text by the source and resource tests. */
internal object MainFiles {
    /** `src/main/<path>` when run by Gradle (working directory `app/`), else `app/src/main/<path>`. */
    fun file(path: String): File {
        val inModule = File("src/main/$path")
        return if (inModule.exists()) inModule else File("app/src/main/$path")
    }

    fun existing(ac: String, path: String): File {
        val file = file(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist")
        }
        return file
    }

    fun lines(ac: String, path: String): List<String> = existing(ac, path).readLines()

    fun text(ac: String, path: String): String = existing(ac, path).readText()
}

/** Hand-built SQLite files: a correct 100-byte header followed by filler pages. No SQLite involved. */
internal object SqliteBytes {
    const val MAGIC = "SQLite format 3\u0000"

    private fun put(bytes: ByteArray, offset: Int, value: Long, length: Int) {
        for (i in 0 until length) {
            bytes[offset + i] = (value shr (8 * (length - 1 - i)) and 0xff).toByte()
        }
    }

    /** The first 100 bytes of a database file. */
    fun header(
        userVersion: Int,
        pageSize: Int = 4096,
        pageCount: Long = 3,
        changeCounter: Long = 7,
        versionValidFor: Long = changeCounter,
        magic: String = MAGIC
    ): ByteArray {
        val bytes = ByteArray(100)
        magic.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0, 0, 16)
        put(bytes, 16, pageSize.toLong(), 2)
        bytes[18] = 1
        bytes[19] = 1
        bytes[21] = 64
        bytes[22] = 32
        bytes[23] = 32
        put(bytes, 24, changeCounter, 4)
        put(bytes, 28, pageCount, 4)
        put(bytes, 44, 4, 4)
        put(bytes, 56, 1, 4)
        put(bytes, 60, userVersion.toLong(), 4)
        put(bytes, 92, versionValidFor, 4)
        put(bytes, 96, 3046001, 4)
        return bytes
    }

    /** A whole file of [pages] pages whose header says so; the filler does not compress. */
    fun file(userVersion: Int, pages: Int = 3, pageSize: Int = 4096, seed: Long = 1): ByteArray {
        val bytes = ByteArray(pages * pageSize)
        Random(seed).nextBytes(bytes)
        header(userVersion, pageSize, pages.toLong()).copyInto(bytes)
        return bytes
    }
}

/** Backup archives built in memory. */
internal object Zips {
    const val DB = "newpipe.db"
    const val JSON = "preferences.json"
    const val LEGACY = "newpipe.settings"

    val SETTINGS_JSON = """{"theme":"dark","show_comments":true}""".toByteArray()

    /** Entries in the given order, deflated like upstream's export, or stored when [stored]. */
    fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.compressedSize = content.size.toLong()
                    entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = zip(entries.toList())

    /** What upstream writes as `newpipe.settings`: a Java-serialized `HashMap`. */
    fun legacySettings(map: Map<String, Any?> = mapOf("theme" to "dark", "show_comments" to true)): ByteArray =
        serialized(HashMap(map))

    fun serialized(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        ObjectOutputStream(out).use { it.writeObject(value) }
        return out.toByteArray()
    }

    /** Index of the first occurrence of [part] in [whole]; fails when absent. */
    fun indexOf(whole: ByteArray, part: ByteArray): Int {
        outer@ for (i in 0..whole.size - part.size) {
            for (j in part.indices) {
                if (whole[i + j] != part[j]) continue@outer
            }
            return i
        }
        throw AssertionError("fixture: content not found in the archive")
    }
}

/** A directory as name to content, to compare "before" with "after" byte for byte. */
internal fun File.snapshot(): Map<String, String> =
    (listFiles() ?: emptyArray()).sortedBy { it.name }.associate {
        it.name to if (it.isDirectory) "<directory>" else it.readBytes().toString(Charsets.ISO_8859_1)
    }
