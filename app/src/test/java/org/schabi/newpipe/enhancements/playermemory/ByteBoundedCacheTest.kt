package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * AC-3 (finding M4, plan section 3): the memory held for the preview sheets of one item has a
 * fixed upper limit. `ByteBoundedCache(maxBytes, sizeOf, onEvicted)` enforces it on every
 * insert: least recently used out first, never the entry just put, so a single sheet larger
 * than the limit is kept alone. Also AC-2: `clear()` hands every sheet to `onEvicted` (the glue
 * recycles the bitmap there) when the player moves to another item.
 *
 * The sheets here are [Sheet]s with a byte size; [SheetCache] records what was put and what was
 * evicted, so "held" is counted, not assumed.
 */
class ByteBoundedCacheTest {

    // ---- basics ----

    @Test
    fun ac3_newCache_holdsNothing() {
        val sheets = SheetCache()

        assertEquals(0L, sheets.cache.bytes)
        assertEquals(emptySet<Int>(), sheets.cache.keys)
        assertNull(sheets.cache[0])
    }

    @Test
    fun ac3_put_isCountedInBytes_andFoundByGet() {
        val sheets = SheetCache()
        val a = sheets.put(0, 100)
        val b = sheets.put(4, 250)

        assertSame(a, sheets.cache[0])
        assertSame(b, sheets.cache[4])
        assertNull("a page never put is not there", sheets.cache[1])
        assertEquals("AC-3: bytes is the sum of the sizes", 350L, sheets.cache.bytes)
        assertEquals(setOf(0, 4), sheets.cache.keys)
        assertEquals("nothing is evicted below the limit", emptyList<Sheet>(), sheets.evicted)
    }

    /** Plan: "`put` replaces an existing key (old value evicted)". */
    @Test
    fun ac3_putOnAnExistingKey_evictsTheOldValue_andCountsOnlyTheNew() {
        val sheets = SheetCache(1000)
        val old = sheets.put(3, 400)
        val new = sheets.put(3, 300)

        assertSame(new, sheets.cache[3])
        assertEquals("AC-3: the replaced sheet is reported, once", listOf(old), sheets.evicted)
        assertEquals(1, old.recycled)
        assertEquals("the sheet just put is not reported", 0, new.recycled)
        assertEquals("AC-3: only the new sheet is counted", 300L, sheets.cache.bytes)
        assertEquals(setOf(3), sheets.cache.keys)
    }

    // ---- the limit ----

    /** Plan: "evicts until `bytes <= maxBytes`": exactly at the limit nothing goes. */
    @Test
    fun ac3_exactlyAtTheLimit_nothingIsEvicted() {
        val sheets = SheetCache(1000)
        sheets.put(0, 400)
        sheets.put(1, 600)

        assertEquals(1000L, sheets.cache.bytes)
        assertEquals(setOf(0, 1), sheets.cache.keys)
        assertEquals(emptyList<Sheet>(), sheets.evicted)
    }

    @Test
    fun ac3_oneByteOverTheLimit_theLeastRecentlyUsedGoes() {
        val sheets = SheetCache(1000)
        val first = sheets.put(0, 400)
        sheets.put(1, 600)
        sheets.put(2, 1)

        assertEquals("AC-3: the oldest entry is evicted", listOf(first), sheets.evicted)
        assertEquals(601L, sheets.cache.bytes)
        assertEquals(setOf(1, 2), sheets.cache.keys)
        assertNull(sheets.cache[0])
    }

    /** Plan section 3: 8 sheets of 960x540 RGB_565 fit into 8 MiB, the ninth pushes the first out. */
    @Test
    fun ac3_eightSheetsFitIntoEightMebibytes_theNinthEvictsTheFirst() {
        val sheets = SheetCache(LIMIT_BYTES)
        val put = (0 until SHEETS_WITHIN_LIMIT).map { sheets.put(it) }

        assertEquals("AC-3: eight sheets are within the limit", emptyList<Sheet>(), sheets.evicted)
        assertEquals(SHEETS_WITHIN_LIMIT * SHEET_BYTES, sheets.cache.bytes)

        sheets.put(8)

        assertEquals("AC-3: the ninth sheet evicts the least recently used one", listOf(put[0]), sheets.evicted)
        assertEquals((1..8).toSet(), sheets.cache.keys)
        assertTrue("AC-3: bytes ${sheets.cache.bytes} within $LIMIT_BYTES", sheets.cache.bytes <= LIMIT_BYTES)
    }

    @Test
    fun ac3_evictionOrder_isOldestFirst_asManyAsNeeded() {
        val sheets = SheetCache(1000)
        val a = sheets.put(0, 300)
        val b = sheets.put(1, 300)
        val c = sheets.put(2, 300)
        sheets.put(3, 700)

        assertEquals("AC-3: two entries have to go, the two least recently used, in that order", listOf(a, b), sheets.evicted)
        assertEquals(0, c.recycled)
        assertEquals(1000L, sheets.cache.bytes)
        assertEquals(setOf(2, 3), sheets.cache.keys)
    }

    /** Plan: "`get` marks the entry most recently used": the sheet under the thumb survives. */
    @Test
    fun ac3_get_refreshesAnEntry() {
        val sheets = SheetCache(900)
        val a = sheets.put(0, 300)
        val b = sheets.put(1, 300)
        val c = sheets.put(2, 300)

        assertSame(a, sheets.cache[0])
        sheets.put(3, 300)

        assertEquals("AC-3: the entry read last stays, the next oldest goes", listOf(b), sheets.evicted)
        assertEquals(setOf(0, 2, 3), sheets.cache.keys)

        sheets.put(4, 300)
        assertEquals("AC-3: then the next oldest, still not the one that was read", listOf(b, c), sheets.evicted)
        assertEquals(0, a.recycled)
    }

    /**
     * The same at sheet size: the sheet under a resting thumb is read on every progress change
     * and must survive while all 47 other sheets of the item pass through the cache.
     */
    @Test
    fun ac3_theSheetInUse_survivesWhileAllOthersPassThrough() {
        val sheets = SheetCache(LIMIT_BYTES)
        val inUse = sheets.put(0)

        for (page in 1 until 48) {
            assertSame("the sheet in use is still there before page $page", inUse, sheets.cache[0])
            sheets.put(page)
            assertTrue("AC-3: ${sheets.cache.bytes} bytes", sheets.cache.bytes <= LIMIT_BYTES)
        }

        assertEquals("AC-3: the sheet read before every insert was never evicted", 0, inUse.recycled)
        assertEquals("the seven most recent other sheets are next to it", setOf(0) + (41..47), sheets.cache.keys)
        assertEquals(40, sheets.evicted.size)
        assertEquals("evicted oldest first", (1..40).toList(), sheets.evicted.map { it.page })
    }

    /** A miss, and looking at `keys` or `bytes`, is no use of an entry. */
    @Test
    fun ac3_missesAndKeys_doNotChangeTheOrder() {
        val sheets = SheetCache(900)
        val a = sheets.put(0, 300)
        sheets.put(1, 300)
        sheets.put(2, 300)

        assertNull(sheets.cache[7])
        assertTrue(sheets.cache.keys.contains(0))
        assertEquals(900L, sheets.cache.bytes)
        sheets.put(3, 300)

        assertEquals(listOf(a), sheets.evicted)
    }

    // ---- a single oversized entry ----

    /** Plan: "a single larger sheet is kept alone"; "evicts until `bytes <= maxBytes` or only the new entry is left". */
    @Test
    fun ac3_oversizedEntry_isKeptAlone() {
        val sheets = SheetCache(1000)
        val a = sheets.put(0, 300)
        val b = sheets.put(1, 300)
        val big = sheets.put(2, 5000)

        assertEquals("AC-3: everything else is evicted", listOf(a, b), sheets.evicted)
        assertSame("AC-3: the oversized entry itself is kept (never the entry just put)", big, sheets.cache[2])
        assertEquals(0, big.recycled)
        assertEquals(setOf(2), sheets.cache.keys)
        assertEquals("AC-3: the limit is then that sheet's size", 5000L, sheets.cache.bytes)
    }

    @Test
    fun ac3_oversizedEntry_goesWithTheNextPut() {
        val sheets = SheetCache(1000)
        val big = sheets.put(0, 5000)
        val small = sheets.put(1, 10)

        assertEquals("AC-3: the oversized entry does not stay next to another one", listOf(big), sheets.evicted)
        assertSame(small, sheets.cache[1])
        assertEquals(10L, sheets.cache.bytes)
        assertEquals(setOf(1), sheets.cache.keys)
    }

    /** Plan section 8: "if sheets are larger than 8 MiB each the cache holds one sheet". */
    @Test
    fun ac3_onlyOversizedSheets_exactlyOneIsHeldAtAnyTime() {
        val sheets = SheetCache(LIMIT_BYTES)
        for (page in 0 until 48) {
            sheets.put(page, LIMIT_BYTES + 1)

            assertEquals("AC-3: one sheet held after page $page", 1, sheets.live.size)
            assertEquals(setOf(page), sheets.cache.keys)
            assertEquals(LIMIT_BYTES + 1, sheets.cache.bytes)
        }
        assertEquals(47, sheets.evicted.size)
        assertEquals(emptyList<Sheet>(), sheets.recycledMoreThanOnce)
    }

    // ---- AC-2: clear ----

    /** AC-2: the previews of an item are released when the player moves on: `clear()` reports every entry. */
    @Test
    fun ac2_clear_reportsEveryEntryOnce_andLeavesNothing() {
        val sheets = SheetCache(LIMIT_BYTES)
        val put = (0 until 5).map { sheets.put(it) }

        sheets.cache.clear()

        assertEquals("AC-2: every sheet is handed to onEvicted", put.toSet(), sheets.evicted.toSet())
        assertEquals("AC-2: each exactly once", 5, sheets.evicted.size)
        assertEquals(0L, sheets.cache.bytes)
        assertEquals(emptySet<Int>(), sheets.cache.keys)
        assertNull(sheets.cache[0])
        assertEquals("AC-2: nothing is still held", emptyList<Sheet>(), sheets.live)
    }

    @Test
    fun ac2_clearTwice_reportsNothingTwice_andTheCacheWorksAgain() {
        val sheets = SheetCache(1000)
        sheets.put(0, 500)
        sheets.cache.clear()
        sheets.cache.clear()

        assertEquals(1, sheets.evicted.size)
        assertEquals(emptyList<Sheet>(), sheets.recycledMoreThanOnce)

        val next = sheets.put(0, 600)
        sheets.put(1, 400)
        assertSame(next, sheets.cache[0])
        assertEquals("the limit counts from zero after a clear", 1000L, sheets.cache.bytes)
        assertEquals(1, sheets.evicted.size)
    }

    @Test
    fun ac2_clearOnAnEmptyCache_reportsNothing() {
        val sheets = SheetCache()
        sheets.cache.clear()

        assertEquals(emptyList<Sheet>(), sheets.evicted)
        assertEquals(0L, sheets.cache.bytes)
    }

    // ---- any sequence ----

    /**
     * Plan section 4: "`bytes` never exceeds the limit after any `put` sequence, except a single
     * oversized entry kept alone; every evicted or cleared value is reported once."
     * 30 000 pseudo-random operations with sheets of mixed sizes, checked after each one against
     * what was put and what was reported.
     */
    @Test
    fun ac3_anySequenceOfPutGetClear_staysWithinTheLimit_andReportsEveryValueOnce() {
        val random = Random(4)
        val limit = 10_000L
        val sheets = SheetCache(limit)
        var last: Sheet? = null

        repeat(30_000) { step ->
            when (random.nextInt(100)) {
                in 0..59 -> {
                    val size = if (random.nextInt(25) == 0) limit + 1 + random.nextInt(5000) else 1L + random.nextInt(4000)
                    last = sheets.put(random.nextInt(40), size)
                }
                in 60..97 -> sheets.cache[random.nextInt(40)]
                else -> { sheets.cache.clear(); last = null }
            }

            val live = sheets.live
            assertEquals("step $step: bytes is the size of what was put and not yet reported", live.sumOf { it.bytes }, sheets.cache.bytes)
            assertEquals("step $step: keys are the pages of what is held", live.map { it.page }.toSet(), sheets.cache.keys)
            assertEquals("step $step: one sheet per page", live.size, sheets.cache.keys.size)
            assertTrue(
                "AC-3: step $step: ${sheets.cache.bytes} bytes held in ${live.size} entries, limit $limit",
                sheets.cache.bytes <= limit || live.size == 1
            )
            assertEquals("step $step: no value is reported twice", emptyList<Sheet>(), sheets.recycledMoreThanOnce)
            last?.let { assertEquals("step $step: the entry just put is never evicted by its own put", 0, it.recycled) }
            last = null
        }

        for (sheet in sheets.live) {
            assertSame("what is held is what get returns", sheet, sheets.cache[sheet.page])
        }
        sheets.cache.clear()
        assertEquals("after the last clear every sheet ever put was reported exactly once", sheets.created.size, sheets.evicted.size)
        assertEquals(sheets.created.toSet(), sheets.evicted.toSet())
    }

    // ---- AC-3 at scale: what one long video and a long queue retain ----

    /**
     * "Whatever the video's length": a video with 48 sampled sheets (today: all 48 decoded,
     * about 47 MiB) whose seekbar is dragged back and forth for an hour, one sheet per second,
     * used as the glue uses the cache (a cached page is read, a missing one is put). Never more
     * than 8 sheets and 8 MiB are held.
     */
    @Test
    fun ac3_scale_anHourOfDraggingAcross48Sheets_neverHoldsMoreThanEightMebibytes() {
        val sheets = SheetCache(LIMIT_BYTES)
        var peakBytes = 0L
        var peakSheets = 0
        var page = 0
        var direction = 1

        repeat(3600) { second ->
            if (page in sheets.cache.keys) {
                assertTrue(sheets.cache[page] != null)
            } else {
                sheets.put(page)
            }
            if (second % 7 == 0) direction = -direction // the thumb goes back a little now and then
            page = Math.floorMod(page + direction * (1 + second % 3), 48)

            peakBytes = Math.max(peakBytes, sheets.liveBytes)
            peakSheets = Math.max(peakSheets, sheets.live.size)
            assertEquals(sheets.liveBytes, sheets.cache.bytes)
        }

        assertTrue("AC-3: peak of decoded sheets held, was $peakBytes bytes", peakBytes <= LIMIT_BYTES)
        assertTrue("AC-3: peak number of sheets held, was $peakSheets", peakSheets <= SHEETS_WITHIN_LIMIT)
        assertTrue("the drag did reach more sheets than fit (${sheets.created.size} were loaded)", sheets.created.size > 48)
        assertEquals(emptyList<Sheet>(), sheets.recycledMoreThanOnce)
    }

    /**
     * A queue of 500 items, each one's seekbar dragged across all 48 sheets, the cache cleared
     * at every item change (AC-2). Today's holder keeps 48 sheets per item until the next reset;
     * here at most 8 are held at any time, none after an item change, and all 24 000 sheets were
     * handed back exactly once.
     */
    @Test
    fun ac3_scale_queueOf500ItemsEachDraggedAcross_holdsEightSheetsAtMost_andNothingAfterAnItemChange() {
        val sheets = SheetCache(LIMIT_BYTES)
        var peakSheets = 0

        repeat(LONG_QUEUE) { item ->
            for (page in 0 until 48) {
                sheets.put(page)
                peakSheets = Math.max(peakSheets, sheets.created.size - sheets.evicted.size)
                assertTrue("AC-3: item $item, page $page: ${sheets.cache.bytes} bytes", sheets.cache.bytes <= LIMIT_BYTES)
            }
            sheets.cache.clear()
            assertEquals("AC-2: item $item: nothing is held after the item change", sheets.created.size, sheets.evicted.size)
            assertEquals(0L, sheets.cache.bytes)
        }

        assertEquals(LONG_QUEUE * 48, sheets.created.size)
        assertTrue("AC-3: peak number of sheets held over the whole queue, was $peakSheets", peakSheets <= SHEETS_WITHIN_LIMIT)
        assertEquals("every sheet was released exactly once", emptyList<Sheet>(), sheets.created.filter { it.recycled != 1 })
    }
}
