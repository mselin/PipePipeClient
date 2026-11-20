package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AC-2: "the imported database is checked: it is a readable database, and its version is one
 * the app can open". [SqliteHeader] reads both from the first 100 bytes and the file's length,
 * without SQLite (plan section 2, step 3). Headers are hand-built by [SqliteBytes].
 *
 * A database cut off in the middle (AC-1's "truncated file", when the cut happened before the
 * file was zipped) is recognised by its length.
 */
class SqliteHeaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val page = 4096L

    /** AC-2: a complete file parses; page size, version and page count are read from offsets 16, 60 and 28. */
    @Test
    fun ac2_completeFile_isParsed() {
        val header = SqliteHeader.parse(SqliteBytes.header(userVersion = 902, pageCount = 3), 3 * page)
        assertEquals(SqliteHeader(pageSize = 4096, userVersion = 902, pageCount = 3), header)
    }

    /** AC-2: the version in the refusal message is the header's `user_version`, whatever it is. */
    @Test
    fun ac2_userVersion_isReadAsFourBytesBigEndian() {
        for (version in listOf(0, 1, 6, 7, 9, 255, 256, 900, 901, 902, 950, 65_536, 16_909_060, Int.MAX_VALUE)) {
            val header = SqliteHeader.parse(SqliteBytes.header(userVersion = version, pageCount = 2), 2 * page)
            assertEquals("user_version $version", version, header?.userVersion)
        }
    }

    /** AC-2: other page sizes are read too. */
    @Test
    fun ac2_pageSize_isReadFromOffset16() {
        for (size in listOf(512, 1024, 8192, 32768)) {
            val header = SqliteHeader.parse(SqliteBytes.header(1, pageSize = size, pageCount = 4), 4L * size)
            assertEquals("page size $size", size, header?.pageSize)
        }
    }

    /** AC-2: `SIZE` is the header length the callers read. */
    @Test
    fun ac2_headerSizeIs100() {
        assertEquals(100, SqliteHeader.SIZE)
    }

    /** AC-2, NOT_A_DATABASE: the magic text is wrong (a text file, a zip, an encrypted database). */
    @Test
    fun ac2_wrongMagic_isNotADatabase() {
        assertNull(SqliteHeader.parse(SqliteBytes.header(902, magic = "SQLite format 2\u0000"), 3 * page))
        assertNull(SqliteHeader.parse(SqliteBytes.header(902, magic = "SQLite format 3 "), 3 * page))
        assertNull(SqliteHeader.parse(SqliteBytes.header(902, magic = "sqlite format 3\u0000"), 3 * page))
        assertNull(SqliteHeader.parse(ByteArray(100), 3 * page))
        assertNull(SqliteHeader.parse("PK\u0003\u0004".toByteArray() + ByteArray(96), 3 * page))
        assertNull(SqliteHeader.parse("I am a text file. ".repeat(10).toByteArray().copyOf(100), 3 * page))
    }

    /** AC-2, NOT_A_DATABASE: fewer than 100 bytes, or an empty file, is no database. */
    @Test
    fun ac2_shortFile_isNotADatabase() {
        val good = SqliteBytes.header(902, pageCount = 3)
        assertNull("no bytes", SqliteHeader.parse(ByteArray(0), 0))
        assertNull("99 bytes", SqliteHeader.parse(good.copyOf(99), 99))
        assertNull("16 bytes: only the magic", SqliteHeader.parse(good.copyOf(16), 16))
        assertNull("a complete header but an empty file", SqliteHeader.parse(good, 0))
        assertNull("only the header", SqliteHeader.parse(good, 100))
    }

    /** AC-2, NOT_A_DATABASE: the page size is not a power of two. */
    @Test
    fun ac2_pageSizeNotAPowerOfTwo_isNotADatabase() {
        for (size in listOf(0, 3, 1000, 4095, 4097, 6144)) {
            val length = if (size == 0) page else 4L * size
            assertNull("page size $size", SqliteHeader.parse(SqliteBytes.header(902, pageSize = size, pageCount = 4), length))
        }
    }

    /** AC-1, AC-2, NOT_A_DATABASE: cut in the middle of a page, the length is no multiple of the page size. */
    @Test
    fun ac2_lengthNotAMultipleOfThePageSize_isNotADatabase() {
        val bytes = SqliteBytes.header(902, pageCount = 3)
        assertNull(SqliteHeader.parse(bytes, 3 * page - 1))
        assertNull(SqliteHeader.parse(bytes, 3 * page + 1))
        assertNull(SqliteHeader.parse(bytes, 2 * page + 100))
    }

    /**
     * AC-1, AC-2, NOT_A_DATABASE: cut exactly at a page boundary. The header's page count
     * (offset 28, valid because the counters at 24 and 92 agree) is more than the file holds.
     */
    @Test
    fun ac2_lengthBelowThePageCount_isNotADatabase() {
        val bytes = SqliteBytes.header(902, pageCount = 2500) // about the owner's 10 MB
        assertNull(SqliteHeader.parse(bytes, 2499 * page))
        assertNull(SqliteHeader.parse(bytes, 1250 * page))
        assertNull(SqliteHeader.parse(bytes, 1 * page))
        assertNotNull(SqliteHeader.parse(bytes, 2500 * page))
    }

    /** AC-2: a file longer than its page count is accepted ("not less than"). */
    @Test
    fun ac2_lengthAboveThePageCount_isAccepted() {
        val header = SqliteHeader.parse(SqliteBytes.header(902, pageCount = 3), 5 * page)
        assertEquals(SqliteHeader(4096, 902, 3), header)
    }

    /**
     * AC-2: when the counters at offsets 24 and 92 differ, the page count of the header is not
     * valid (a database last written by an old SQLite). It is not used for the check and is
     * reported as unknown, so such a backup is not refused for it.
     */
    @Test
    fun ac2_pageCountIsIgnoredWhenTheCountersDisagree() {
        val bytes = SqliteBytes.header(6, pageCount = 2500, changeCounter = 12, versionValidFor = 11)
        assertEquals(SqliteHeader(pageSize = 4096, userVersion = 6, pageCount = null), SqliteHeader.parse(bytes, 3 * page))
    }

    /** AC-2: `read` takes header and length from a real file. */
    @Test
    fun ac2_read_parsesARealFile() {
        val file = tmp.newFile("newpipe.db.import")
        file.writeBytes(SqliteBytes.file(userVersion = 901, pages = 6))
        assertEquals(SqliteHeader(4096, 901, 6), SqliteHeader.read(file))
    }

    /** AC-1, AC-2, NOT_A_DATABASE: `read` on a truncated, an empty and a text file. */
    @Test
    fun ac2_read_refusesIncompleteAndForeignFiles() {
        val whole = SqliteBytes.file(userVersion = 902, pages = 6)

        val cutInPage = tmp.newFile("cut-in-page")
        cutInPage.writeBytes(whole.copyOf(whole.size - 17))
        assertNull(SqliteHeader.read(cutInPage))

        val cutAtPage = tmp.newFile("cut-at-page")
        cutAtPage.writeBytes(whole.copyOf(4096 * 4))
        assertNull(SqliteHeader.read(cutAtPage))

        val short = tmp.newFile("short")
        short.writeBytes(whole.copyOf(50))
        assertNull(SqliteHeader.read(short))

        assertNull(SqliteHeader.read(tmp.newFile("empty")))

        val text = tmp.newFile("text")
        text.writeText("I am not a database. ".repeat(1000))
        assertNull(SqliteHeader.read(text))
    }
}
