package org.schabi.newpipe.enhancements.backupsafety

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * The first 100 bytes of an SQLite file, as far as the import needs them: enough to refuse a
 * file that is no database or was cut off, and to read the schema version without opening it.
 * Layout: https://www.sqlite.org/fileformat.html#the_database_header (all numbers big-endian).
 */
data class SqliteHeader(val pageSize: Int, val userVersion: Int, val pageCount: Long?) {
    companion object {
        const val SIZE = 100

        private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)

        /** null when [bytes] (the first [SIZE] bytes) and [fileLength] are not a complete SQLite file */
        fun parse(bytes: ByteArray, fileLength: Long): SqliteHeader? {
            if (bytes.size < SIZE || fileLength < SIZE) {
                return null
            }
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) {
                    return null
                }
            }
            val rawPageSize = ((bytes[16].toInt() and 0xff) shl 8) or (bytes[17].toInt() and 0xff)
            // the two bytes cannot hold 65536; SQLite writes it as 1
            val pageSize = if (rawPageSize == 1) 65536 else rawPageSize
            if (pageSize < 512 || pageSize and (pageSize - 1) != 0) {
                return null
            }
            if (fileLength % pageSize != 0L) {
                return null
            }
            // the page count is valid only when the change counter equals "version valid for"
            val pageCount = if (u32(bytes, 24) == u32(bytes, 92)) u32(bytes, 28) else null
            if (pageCount != null && fileLength < pageCount * pageSize) {
                return null
            }
            return SqliteHeader(pageSize, u32(bytes, 60).toInt(), pageCount)
        }

        /** null when [file] cannot be read or is not a complete SQLite file */
        fun read(file: File): SqliteHeader? {
            val bytes = ByteArray(SIZE)
            return try {
                var filled = 0
                FileInputStream(file).use { input ->
                    while (filled < SIZE) {
                        val n = input.read(bytes, filled, SIZE - filled)
                        if (n < 0) {
                            break
                        }
                        filled += n
                    }
                }
                if (filled < SIZE) null else parse(bytes, file.length())
            } catch (e: IOException) {
                null
            }
        }
    }
}
