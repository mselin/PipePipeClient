package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/**
 * AC-7 (finding M4, plan section 3): seekbar previews, once shown, look as today. That holds
 * when a seekbar position maps to the same frame as in upstream's
 * `SeekbarPreviewThumbnailHolder`. `SeekbarFrameTable` is compared here against a reference
 * written from upstream: `generateDataFrom` (`:113-150`, which frames exist and on which sheet)
 * and `getBitmapAt` (`:240-255`, a linear scan for the nearest one, the lower on a tie).
 *
 * Also the page bound upstream has (at most 48 sampled sheets per item), which AC-3 builds on.
 */
class SeekbarFrameTableTest {

    private fun shape(pages: Int, total: Int, durationMs: Int, x: Int, y: Int) = FramesetShape(pages, total, durationMs, x, y)

    // ---- the reference: upstream, restated ----

    /** Upstream `:115-116`. */
    private fun referencePageStep(pageCount: Int, maxPages: Int): Int =
        Math.max(1, Math.ceil(pageCount.toDouble() / maxPages).toInt())

    /**
     * Upstream `:119-150`: position in ms of every frame it would have generated, mapped to the
     * page (sheet) the frame is on. Ascending, as `SparseArrayCompat` iterates.
     */
    private fun referenceFrames(shape: FramesetShape, maxPages: Int = 48): TreeMap<Int, Int> {
        val frames = TreeMap<Int, Int>()
        val urlFrameCount = shape.framesPerPageX * shape.framesPerPageY
        val pageStep = referencePageStep(shape.pageCount, maxPages)
        var pageIndex = 0
        while (pageIndex < shape.pageCount) {
            var currentPosMs = pageIndex.toLong() * urlFrameCount * shape.durationPerFrameMs
            var pos = pageIndex * urlFrameCount + 1
            for (i in 0 until urlFrameCount) {
                if (pos > shape.totalCount || currentPosMs > Int.MAX_VALUE) {
                    break
                }
                frames[currentPosMs.toInt()] = pageIndex
                currentPosMs += shape.durationPerFrameMs
                pos++
            }
            pageIndex += pageStep
        }
        return frames
    }

    /** Upstream `:240-255`: linear scan in ascending key order, strictly smaller distance wins. */
    private fun referenceNearest(frames: TreeMap<Int, Int>, positionMs: Int, pages: Set<Int>? = null): FrameRef? {
        var closest: FrameRef? = null
        var min = Long.MAX_VALUE
        for ((key, page) in frames) {
            if (pages != null && page !in pages) continue
            val distance = Math.abs(key.toLong() - positionMs.toLong())
            if (distance < min) {
                closest = FrameRef(page, key)
                min = distance
            }
        }
        return closest
    }

    /** Positions worth asking: around frames (on, next to, halfway between), below 0, beyond the end. */
    private fun positions(frames: TreeMap<Int, Int>, durationMs: Int, sampleEvery: Int = 1): List<Int> {
        val positions = ArrayList<Long>()
        positions.addAll(listOf(-60_000L, -1L, 0L, 1L))
        val keys = frames.keys.map { it.toLong() }
        for (i in keys.indices step sampleEvery) {
            val key = keys[i]
            positions.addAll(listOf(key - 1, key, key + 1, key + durationMs / 2, key + durationMs / 2 + 1, key + durationMs - 1))
            if (i + 1 < keys.size) {
                val next = keys[i + 1]
                val middle = key + (next - key) / 2 // a tie when the gap is even, also across skipped sheets
                positions.addAll(listOf(middle - 1, middle, middle + 1))
            }
        }
        if (keys.isNotEmpty()) {
            val last = keys.last()
            positions.addAll(listOf(last + durationMs, last + 3_600_000L, Int.MAX_VALUE.toLong()))
        }
        return positions.filter { it >= -60_000L && it <= Int.MAX_VALUE }.map { it.toInt() }
    }

    private fun assertAgrees(what: String, shape: FramesetShape, sampleEvery: Int = 1) {
        val frames = referenceFrames(shape)
        val table = SeekbarFrameTable(shape)
        for (position in positions(frames, shape.durationPerFrameMs, sampleEvery)) {
            assertEquals("AC-7: $what, $shape: nearest($position)", referenceNearest(frames, position), table.nearest(position))
        }
    }

    // ---- which sheets ----

    @Test
    fun ac7_pageStep_isUpstreamsSampling() {
        assertEquals(1, SeekbarFrameTable(shape(1, 25, 2000, 5, 5)).pageStep)
        assertEquals(1, SeekbarFrameTable(shape(48, 1200, 2000, 5, 5)).pageStep)
        assertEquals("49 sheets: every second one", 2, SeekbarFrameTable(shape(49, 1225, 2000, 5, 5)).pageStep)
        assertEquals(3, SeekbarFrameTable(shape(120, 3000, 2000, 5, 5)).pageStep)
        assertEquals(11, SeekbarFrameTable(shape(500, 12500, 2000, 5, 5)).pageStep)
        assertEquals("no sheet: the step is still at least 1", 1, SeekbarFrameTable(shape(0, 0, 2000, 5, 5)).pageStep)
        assertEquals("upstream's limit", 48, SeekbarFrameTable.MAX_PAGES)
    }

    @Test
    fun ac7_sampledPages_areEveryPageStepThSheetBelowPageCount() {
        assertEquals(listOf(0), SeekbarFrameTable(shape(1, 25, 2000, 5, 5)).sampledPages)
        assertEquals((0 until 48).toList(), SeekbarFrameTable(shape(48, 1200, 2000, 5, 5)).sampledPages)
        assertEquals((0..48 step 2).toList(), SeekbarFrameTable(shape(49, 1225, 2000, 5, 5)).sampledPages)
        assertEquals((0 until 120 step 3).toList(), SeekbarFrameTable(shape(120, 3000, 2000, 5, 5)).sampledPages)
        assertEquals((0 until 500 step 11).toList(), SeekbarFrameTable(shape(500, 12500, 2000, 5, 5)).sampledPages)
        assertEquals(emptyList<Int>(), SeekbarFrameTable(shape(0, 0, 2000, 5, 5)).sampledPages)
    }

    @Test
    fun ac7_maxPages_canBeGiven() {
        val table = SeekbarFrameTable(shape(10, 250, 1000, 5, 5), 4)

        assertEquals(3, table.pageStep)
        assertEquals(listOf(0, 3, 6, 9), table.sampledPages)
        assertEquals("a frame of a sheet that is not sampled maps to the nearest sampled frame", FrameRef(0, 24_000), table.nearest(30_000))
        assertEquals(FrameRef(3, 75_000), table.nearest(60_000))
    }

    /**
     * "Whatever the video's length" (AC-3 rests on it): for any number of sheets, never more
     * than 48 are sampled, and the step is upstream's.
     */
    @Test
    fun ac3_anyVideoLength_atMost48SheetsAreSampled() {
        for (pageCount in 0..3000) {
            val table = SeekbarFrameTable(shape(pageCount, pageCount * 25, 2000, 5, 5))
            val step = referencePageStep(pageCount, 48)

            assertEquals("pageStep for $pageCount sheets", step, table.pageStep)
            assertEquals("sampledPages for $pageCount sheets", (0 until pageCount step step).toList(), table.sampledPages)
            assertTrue("AC-3: $pageCount sheets: ${table.sampledPages.size} sampled", table.sampledPages.size <= 48)
        }
    }

    // ---- nearest ----

    @Test
    fun ac7_nearest_byHand() {
        // Two sheets of 2x2 frames, one frame every 10 s, 7 frames (the last sheet is not full).
        val table = SeekbarFrameTable(shape(2, 7, 10_000, 2, 2))

        assertEquals("on a frame", FrameRef(0, 10_000), table.nearest(10_000))
        assertEquals("first frame of the second sheet", FrameRef(1, 40_000), table.nearest(40_000))
        assertEquals("closer to the lower frame", FrameRef(0, 30_000), table.nearest(34_999))
        assertEquals("closer to the upper frame", FrameRef(1, 40_000), table.nearest(35_001))
        assertEquals("AC-7: a tie goes to the lower frame, as upstream's scan does", FrameRef(0, 30_000), table.nearest(35_000))
        assertEquals("below 0: the first frame", FrameRef(0, 0), table.nearest(-5_000))
        assertEquals("the 8th frame does not exist (totalCount = 7): the last one is 60 s", FrameRef(1, 60_000), table.nearest(70_000))
        assertEquals("far beyond the end", FrameRef(1, 60_000), table.nearest(Int.MAX_VALUE))
    }

    /** Plan section 4: page counts 1, 48, 49, 120, 500 against the reference. */
    @Test
    fun ac7_nearest_agreesWithUpstream_forPageCounts1_48_49_120_500() {
        assertAgrees("1 sheet", shape(1, 25, 2000, 5, 5))
        assertAgrees("48 sheets", shape(48, 1200, 2000, 5, 5))
        assertAgrees("49 sheets, every second sampled", shape(49, 1225, 5000, 5, 5))
        assertAgrees("120 sheets, every third sampled", shape(120, 3000, 5000, 5, 5))
        assertAgrees("500 sheets, every 11th sampled", shape(500, 12_500, 10_000, 5, 5), sampleEvery = 3)
    }

    @Test
    fun ac7_nearest_agreesWithUpstream_forSeveralGrids() {
        assertAgrees("10x10", shape(3, 300, 1000, 10, 10))
        assertAgrees("3x3", shape(60, 540, 4000, 3, 3))
        assertAgrees("1x1, one frame per sheet", shape(100, 100, 2000, 1, 1))
        assertAgrees("5x3, not square", shape(20, 300, 3000, 5, 3))
        assertAgrees("3x5, not square", shape(20, 300, 3001, 3, 5))
        assertAgrees("odd frame duration", shape(49, 1225, 1999, 5, 5))
    }

    /** `n + 1 <= totalCount`: frames beyond the video's length do not exist. */
    @Test
    fun ac7_nearest_agreesWithUpstream_whenTheLastSheetIsNotFull() {
        assertAgrees("7 frames short", shape(48, 1200 - 7, 2000, 5, 5))
        assertAgrees("one frame on the last sheet", shape(4, 76, 2000, 5, 5))
        assertAgrees("three frames in all", shape(1, 3, 2000, 5, 5))
        assertAgrees("one frame in all", shape(1, 1, 2000, 5, 5))
        assertAgrees("sampled, the last sampled sheet partly beyond the end", shape(120, 2990, 5000, 5, 5))
        assertAgrees("fewer frames than the sheets could hold: whole sheets are empty", shape(10, 60, 2000, 5, 5))
        assertAgrees("more frames announced than the sheets hold", shape(4, 1000, 2000, 5, 5))
    }

    /** `n * durationPerFrameMs` must fit an `Int` (upstream `:139`): later frames do not exist. */
    @Test
    fun ac7_nearest_agreesWithUpstream_whenPositionsWouldOverflowAnInt() {
        val overflowing = shape(48, 1200, 2_000_000, 5, 5)
        val frames = referenceFrames(overflowing)
        assertEquals("the reference itself: 1074 frames fit below 2^31 ms", 1074, frames.size)

        assertAgrees("2 000 s per frame", overflowing)
        assertEquals(FrameRef(42, 2_146_000_000), SeekbarFrameTable(overflowing).nearest(Int.MAX_VALUE))
        assertAgrees("sampled and overflowing", shape(500, 12_500, 400_000, 5, 5), sampleEvery = 3)
    }

    /** Every frame the reference has is found on its own position, on the sheet upstream took it from. */
    @Test
    fun ac7_everyUpstreamFrame_isFoundOnItsOwnPosition_onTheSameSheet() {
        for (shape in listOf(shape(48, 1200, 2000, 5, 5), shape(120, 2990, 5000, 5, 5), shape(500, 12_500, 10_000, 5, 5))) {
            val table = SeekbarFrameTable(shape)
            val frames = referenceFrames(shape)
            for ((key, page) in frames) {
                assertEquals("AC-7: $shape: frame at $key ms", FrameRef(page, key), table.nearest(key))
            }
            assertEquals("the frames are only on sampled sheets", table.sampledPages.toSet(), table.sampledPages.toSet() + frames.values)
        }
    }

    @Test
    fun ac7_noFrames_givesNull() {
        assertNull("totalCount = 0", SeekbarFrameTable(shape(3, 0, 2000, 5, 5)).nearest(0))
        assertNull("totalCount = 0", SeekbarFrameTable(shape(3, 0, 2000, 5, 5)).nearest(10_000))
        assertNull("no sheet", SeekbarFrameTable(shape(0, 100, 2000, 5, 5)).nearest(0))
        assertNull(SeekbarFrameTable(shape(0, 0, 2000, 5, 5)).nearestAmong(0, setOf(0)))
        assertNull(SeekbarFrameTable(shape(3, 0, 2000, 5, 5)).nearestAmong(0, setOf(0, 1, 2)))
    }

    // ---- nearestAmong: what is shown while only some sheets are loaded ----

    @Test
    fun ac7_nearestAmong_byHand() {
        val table = SeekbarFrameTable(shape(4, 16, 10_000, 2, 2))

        assertEquals("the sheet of the position is loaded", FrameRef(2, 90_000), table.nearestAmong(91_000, setOf(0, 2)))
        assertEquals("not loaded: the nearest frame of a loaded sheet below", FrameRef(0, 30_000), table.nearestAmong(50_000, setOf(0, 3)))
        assertEquals("or above", FrameRef(3, 120_000), table.nearestAmong(100_000, setOf(0, 3)))
        assertEquals("a tie between two loaded sheets goes to the lower frame", FrameRef(0, 30_000), table.nearestAmong(75_000, setOf(0, 3)))
        assertEquals("all sheets given: the same as nearest", table.nearest(75_000), table.nearestAmong(75_000, setOf(0, 1, 2, 3)))
    }

    @Test
    fun ac7_nearestAmong_emptySet_givesNull() {
        val table = SeekbarFrameTable(shape(48, 1200, 2000, 5, 5))

        assertNull("AC-7/AC-1: nothing loaded, nothing shown", table.nearestAmong(0, emptySet()))
        assertNull(table.nearestAmong(60_000, emptySet()))
    }

    /** Sheets that are not sampled, or do not exist, have no frames: asking among them alone gives null. */
    @Test
    fun ac7_nearestAmong_pagesWithoutFrames_giveNull_orAreIgnored() {
        val table = SeekbarFrameTable(shape(120, 3000, 5000, 5, 5)) // sampled: 0, 3, 6, ...

        assertNull("sheets 1 and 2 are not sampled", table.nearestAmong(200_000, setOf(1, 2)))
        assertNull("sheets that do not exist", table.nearestAmong(200_000, setOf(-1, 120, 4000)))
        assertEquals(
            "next to a sampled sheet they are ignored",
            table.nearestAmong(200_000, setOf(3)),
            table.nearestAmong(200_000, setOf(1, 2, 3, -1, 4000))
        )
        val beyondTheEnd = SeekbarFrameTable(shape(10, 60, 2000, 5, 5)) // frames only on sheets 0, 1, 2
        assertNull("a sampled sheet beyond the video's length has no frame", beyondTheEnd.nearestAmong(0, setOf(5, 9)))
    }

    /** Plan section 4: "`nearestAmong` agrees with the scan restricted to those pages". */
    @Test
    fun ac7_nearestAmong_agreesWithUpstreamsScanOverThosePages() {
        val shapes = listOf(
            shape(48, 1200 - 7, 2000, 5, 5) to 1,
            shape(49, 1225, 5000, 5, 5) to 1,
            shape(120, 3000, 5000, 3, 3) to 2,
            shape(500, 12_500, 10_000, 5, 5) to 7
        )
        for ((shape, sampleEvery) in shapes) {
            val frames = referenceFrames(shape)
            val table = SeekbarFrameTable(shape)
            val sampled = table.sampledPages
            val pageSets = listOf(
                setOf(sampled.first()),
                setOf(sampled.last()),
                setOf(sampled[sampled.size / 2]),
                sampled.filterIndexed { i, _ -> i % 2 == 0 }.toSet(),
                sampled.drop(sampled.size / 3).take(SHEETS_WITHIN_LIMIT).toSet(), // eight neighbouring sheets, as the cache holds
                setOf(sampled.first(), sampled.last()),
                sampled.toSet()
            )
            for (pages in pageSets) {
                for (position in positions(frames, shape.durationPerFrameMs, sampleEvery)) {
                    assertEquals(
                        "AC-7: $shape: nearestAmong($position, $pages)",
                        referenceNearest(frames, position, pages),
                        table.nearestAmong(position, pages)
                    )
                }
            }
        }
    }

    /**
     * AC-7, "once shown, look as today": when the sheet a position belongs to is among the
     * loaded ones, the frame shown is exactly the one upstream shows with everything loaded.
     */
    @Test
    fun ac7_whenTheSheetOfThePositionIsLoaded_theFrameIsTheSameAsWithEverythingLoaded() {
        val shape = shape(160, 1440, 5000, 3, 3) // two hours, a frame every 5 s
        val table = SeekbarFrameTable(shape)
        val frames = referenceFrames(shape)

        for (position in 0..7_200_000 step 1_777) {
            val all = referenceNearest(frames, position)!!
            assertEquals(
                "AC-7: position $position with only sheet ${all.pageIndex} loaded",
                all,
                table.nearestAmong(position, setOf(all.pageIndex))
            )
            assertEquals(all, table.nearest(position))
        }
    }
}
