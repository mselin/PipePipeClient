package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.fail
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** The owner's use: queues of hundreds of items, played for hours. */
internal const val LONG_QUEUE = 500
internal const val VERY_LONG_QUEUE = 1600

/** Plan section 3: one decoded sheet of 960x540 in RGB_565. */
internal const val SHEET_BYTES = 960L * 540 * 2

/** Plan section 3, `LazySeekbarPreviews.MAX_SHEET_BYTES`: 8 MiB of decoded sheets per item. */
internal const val LIMIT_BYTES = 8L * 1024 * 1024

/** How many sheets of [SHEET_BYTES] fit into [LIMIT_BYTES] (plan section 3: "8 sheets of 960x540 RGB_565"). */
internal const val SHEETS_WITHIN_LIMIT = 8

/**
 * Stands in for a decoded `Bitmap` (which does not exist on the JVM): it has a size and can be
 * recycled. Compared by identity, as bitmaps are.
 */
internal class Sheet(val page: Int, val bytes: Long = SHEET_BYTES) {
    /** How often this sheet was handed to `onEvicted` (the glue calls `Bitmap.recycle()` there). */
    var recycled = 0
    override fun toString() = "Sheet(page=$page, bytes=$bytes, recycled=$recycled)"
}

/**
 * A `ByteBoundedCache` of [Sheet]s wired as plan section 3 wires the cache of bitmaps
 * (`sizeOf` = the allocation size, `onEvicted` = recycle), with a record of everything that
 * was ever put into it, so that a test can say how much is still held.
 */
internal class SheetCache(maxBytes: Long = LIMIT_BYTES) {
    val created = ArrayList<Sheet>()
    val evicted = ArrayList<Sheet>()
    private val held: MutableSet<Sheet> = Collections.newSetFromMap(IdentityHashMap())
    private val twice = ArrayList<Sheet>()

    val cache = ByteBoundedCache<Int, Sheet>(
        maxBytes,
        { it.bytes },
        {
            it.recycled++
            evicted.add(it)
            held.remove(it)
            if (it.recycled > 1) twice.add(it)
        }
    )

    fun put(page: Int, bytes: Long = SHEET_BYTES): Sheet {
        val sheet = Sheet(page, bytes)
        created.add(sheet)
        held.add(sheet)
        cache.put(page, sheet)
        return sheet
    }

    /** The sheets put and not yet handed to `onEvicted`: what is still decoded in memory. */
    val live: List<Sheet> get() = held.toList()
    val liveBytes: Long get() = held.sumOf { it.bytes }
    val recycledMoreThanOnce: List<Sheet> get() = twice
}

/** Files of `app/src/main`, read as text by the wiring tests. */
internal object MainFiles {
    /** `src/main/<path>` when run by Gradle (working directory `app/`), else `app/src/main/<path>`. */
    fun file(path: String): File {
        val inModule = File("src/main/$path")
        return if (inModule.exists()) inModule else File("app/src/main/$path")
    }

    fun lines(ac: String, path: String): List<String> {
        val file = file(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist")
        }
        return file.readLines()
    }

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** How often [snippet] occurs in [text], whitespace ignored in both. */
    fun count(text: String, snippet: String): Int {
        val wanted = squeeze(snippet)
        return squeeze(text).windowed(wanted.length).count { it == wanted }
    }
}
