package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.Random

/**
 * AC-1, AC-2, AC-3 and AC-7 together, at the owner's scale: the three plain classes wired as
 * plan section 3 describes `LazySeekbarPreviews` (which itself needs bitmaps, Picasso and a
 * main thread and cannot run here), with a hand-written download queue in place of Picasso.
 *
 * The wiring below is the plan's text, sentence by sentence; the tests then play long queues
 * and pin how many sheets are downloaded and how many stay decoded. If the three classes each
 * pass their own tests but do not fit together (keys of the cache that are not page indexes of
 * the table, a generation that does not match), it shows here.
 */
class SeekbarPreviewRetentionTest {

    /** Plan section 3, `LazySeekbarPreviews`, without Android. */
    private class Previews(private val audioOnly: Boolean = false) {
        val sheets = SheetCache(LIMIT_BYTES)
        val state = PreviewLoadState()
        var table: SeekbarFrameTable? = null

        /** Downloads handed to the executor and not yet reported: generation and page. */
        val running = ArrayDeque<Pair<Int, Int>>()
        var downloadsStarted = 0
        var downloadsRefused = 0
        var peakRunningForCurrentItem = 0

        /** "`resetFrom`: `state.onNewItem()`; cancels the running download; `cache.clear()`; builds the table. No network." */
        fun resetFrom(shape: FramesetShape?) {
            state.onNewItem()
            sheets.cache.clear()
            table = shape?.let { SeekbarFrameTable(it) }
        }

        private fun start(page: Int?) {
            if (page == null) return
            downloadsStarted++
            running.add(state.generation to page)
            peakRunningForCurrentItem = Math.max(peakRunningForCurrentItem, running.count { it.first == state.generation })
        }

        private fun want(positionMs: Int) {
            val ref = table?.nearest(positionMs) ?: return
            start(state.request(ref.pageIndex, ref.pageIndex in sheets.cache.keys))
        }

        /** "`onSeekbarTouched`: `state.arm(!player.audioPlayerSelected())`, then requests the page of `table.nearest(positionMs)`." */
        fun onSeekbarTouched(positionMs: Int) {
            state.arm(!audioOnly)
            want(positionMs)
        }

        /** "`getBitmapAt` arms the same way, requests that page, and returns the cut-out of `table.nearestAmong(positionInMs, cache.keys)`." */
        fun getBitmapAt(positionMs: Int): Pair<FrameRef, Sheet>? {
            if (!state.arm(!audioOnly)) return null
            want(positionMs)
            val ref = table?.nearestAmong(positionMs, sheets.cache.keys) ?: return null
            val sheet = sheets.cache[ref.pageIndex] ?: return null
            return ref to sheet
        }

        /** The oldest running download reports: "`accept == false`: the bitmap is recycled. Otherwise `cache.put(page, bitmap)`, `next` is started". */
        fun deliverOne(): Boolean {
            val (generation, page) = running.poll() ?: return false
            val result = state.onLoaded(generation, page)
            if (result.accept) {
                sheets.put(page)
                start(result.next)
            } else {
                downloadsRefused++
                assertNull("a refused result starts nothing", result.next)
            }
            return true
        }

        fun deliverAll() {
            while (deliverOne()) {
                // until nothing is running
            }
        }
    }

    /** A two-hour video: 160 sheets of 3x3 frames, one frame every 5 s; upstream samples every 4th sheet (40). */
    private val twoHours = FramesetShape(160, 1440, 5000, 3, 3)
    private val twoHoursMs = 7_200_000

    // ---- AC-1 ----

    /** AC-1: a queue of 500 two-hour videos in the background player: no sheet is downloaded or held. */
    @Test
    fun ac1_scale_backgroundPlayer_queueOf500_downloadsAndHoldsNothing() {
        val previews = Previews(audioOnly = true)

        repeat(LONG_QUEUE) {
            previews.resetFrom(twoHours)
            // Whatever calls arrive in the background player, nothing may load.
            previews.onSeekbarTouched(60_000)
            assertNull(previews.getBitmapAt(120_000))
            previews.deliverAll()
        }

        assertEquals("AC-1: sheets downloaded in the background player (today: 40 per item)", 0, previews.downloadsStarted)
        assertEquals("AC-1: sheets held", 0, previews.sheets.created.size)
    }

    /** AC-1: 500 items in the main player, seekbar never touched: the item change alone loads nothing. */
    @Test
    fun ac1_scale_mainPlayer_untouchedItems_downloadNothing() {
        val previews = Previews()

        repeat(LONG_QUEUE) {
            previews.resetFrom(twoHours)
            previews.deliverAll()
        }

        assertEquals("AC-1: sheets downloaded without a touch", 0, previews.downloadsStarted)
        assertEquals(0L, previews.sheets.cache.bytes)
    }

    /** AC-1: the first touch downloads the one sheet under the thumb, not the item's 40. */
    @Test
    fun ac1_firstTouch_downloadsOneSheet() {
        val previews = Previews()
        previews.resetFrom(twoHours)

        previews.onSeekbarTouched(3_600_000)
        previews.deliverAll()

        assertEquals("AC-1: one touch, one sheet", 1, previews.downloadsStarted)
        assertEquals(1, previews.sheets.live.size)
        val shown = previews.getBitmapAt(3_600_000)
        assertNotNull("AC-7: the preview is there once its sheet is loaded", shown)
        assertEquals("AC-7: and it is the frame upstream shows at one hour", SeekbarFrameTable(twoHours).nearest(3_600_000), shown!!.first)
        assertEquals("looking at a loaded preview downloads nothing more", 1, previews.downloadsStarted)
    }

    /** No frameset (or the setting on "none"): no table, nothing loads, nothing is shown. */
    @Test
    fun ac1_noFrameset_nothingLoads() {
        val previews = Previews()
        previews.resetFrom(null)

        previews.onSeekbarTouched(1000)
        assertNull(previews.getBitmapAt(1000))

        assertEquals(0, previews.downloadsStarted)
    }

    // ---- AC-3, AC-7 ----

    /**
     * AC-3: the seekbar of a two-hour video dragged from start to end and back, slowly enough
     * that every sheet arrives (the worst case for memory). All 40 sampled sheets are
     * downloaded at some point; never more than 8 (8 MiB) are decoded at once, plus the one
     * being decoded ("One more sheet may be in decode"). AC-7: whenever the sheet under the
     * thumb is loaded, the frame is upstream's.
     */
    @Test
    fun ac3_scale_slowDragAcrossATwoHourVideo_holdsAtMostEightSheets_andShowsUpstreamsFrames() {
        val previews = Previews()
        previews.resetFrom(twoHours)
        val reference = SeekbarFrameTable(twoHours)
        var peakLive = 0
        var peakBytes = 0L
        var exact = 0

        previews.onSeekbarTouched(0)
        val path = (0..twoHoursMs step 20_000) + (twoHoursMs downTo 0 step 20_000)
        for (position in path) {
            previews.getBitmapAt(position)
            previews.deliverAll()
            val shown = previews.getBitmapAt(position)

            assertNotNull("AC-7: at $position ms the sheet has arrived and a preview is shown", shown)
            assertEquals("AC-7: at $position ms the frame is the one upstream shows", reference.nearest(position), shown!!.first)
            assertEquals("the sheet shown is the frame's sheet", shown.first.pageIndex, shown.second.page)
            assertEquals("and it has not been recycled", 0, shown.second.recycled)
            exact++
            peakLive = Math.max(peakLive, previews.sheets.live.size)
            peakBytes = Math.max(peakBytes, previews.sheets.liveBytes)
            assertTrue("one download at a time", previews.peakRunningForCurrentItem <= 1)
        }

        assertEquals(path.size, exact)
        assertTrue("the drag reached every sampled sheet (${previews.downloadsStarted} downloads)", previews.downloadsStarted >= reference.sampledPages.size)
        assertTrue("AC-3: peak of decoded sheets, was $peakLive", peakLive <= SHEETS_WITHIN_LIMIT)
        assertTrue("AC-3: peak of decoded bytes, was $peakBytes", peakBytes <= LIMIT_BYTES)
        assertEquals(emptyList<Sheet>(), previews.sheets.recycledMoreThanOnce)
    }

    /**
     * A fast drag: downloads never report during the drag. While nothing has arrived nothing is
     * shown; the number of downloads stays at two (the first and the last wanted sheet).
     */
    @Test
    fun ac3_fastDrag_downloadsOnlyTheFirstAndTheLastWantedSheet() {
        val previews = Previews()
        previews.resetFrom(twoHours)

        previews.onSeekbarTouched(0)
        for (position in 0..twoHoursMs step 10_000) {
            assertNull("nothing has arrived yet", previews.getBitmapAt(position))
        }
        previews.deliverAll()

        assertEquals("downloads for one fast drag across two hours (today: 40 at item start)", 2, previews.downloadsStarted)
        assertEquals(2, previews.sheets.live.size)
        val shown = previews.getBitmapAt(twoHoursMs)
        assertEquals("AC-7: the frame under the thumb at the end", SeekbarFrameTable(twoHours).nearest(twoHoursMs), shown!!.first)
    }

    /** Plan, "Could regress": until the sheet of a new part arrives, the nearest loaded frame is shown. */
    @Test
    fun ac7_untilTheNewSheetArrives_theNearestLoadedFrameIsShown() {
        val previews = Previews()
        previews.resetFrom(twoHours)
        previews.onSeekbarTouched(0)
        previews.deliverAll()

        val shown = previews.getBitmapAt(3_600_000)

        assertNotNull(shown)
        assertEquals("the only loaded sheet is the first", 0, shown!!.first.pageIndex)
        assertEquals("its last frame is the nearest to one hour", SeekbarFrameTable(twoHours).nearestAmong(3_600_000, setOf(0)), shown.first)
        previews.deliverAll()
        assertEquals("one download later the frame is upstream's", SeekbarFrameTable(twoHours).nearest(3_600_000), previews.getBitmapAt(3_600_000)!!.first)
    }

    // ---- AC-2 ----

    /** AC-2: the item changes during a drag: its sheets are released at once and the sheet still downloading is refused. */
    @Test
    fun ac2_itemChangeDuringADrag_releasesEverySheet_andRefusesTheLateOne() {
        val previews = Previews()
        previews.resetFrom(twoHours)
        previews.onSeekbarTouched(0)
        for (position in 0..600_000 step 20_000) {
            previews.getBitmapAt(position)
            previews.deliverAll()
        }
        previews.getBitmapAt(3_000_000) // one more download is running
        assertTrue(previews.sheets.live.isNotEmpty())
        assertEquals(1, previews.running.size)

        previews.resetFrom(twoHours)

        assertEquals("AC-2: no sheet of the old item is held after the item change", emptyList<Sheet>(), previews.sheets.live)
        assertEquals(0L, previews.sheets.cache.bytes)
        assertNull("AC-2/AC-1: the new item shows nothing before its own touch", previews.sheets.cache[0])

        previews.deliverAll()

        assertEquals("AC-2: the late sheet of the old item is refused", 1, previews.downloadsRefused)
        assertEquals("AC-2: and not put into the new item's cache", emptyList<Sheet>(), previews.sheets.live)
        assertEquals(0L, previews.sheets.cache.bytes)
    }

    /**
     * Hours of use: a queue of 500 two-hour videos; on each the seekbar is dragged to a few
     * dozen pseudo-random places (downloads arriving now and then), and every third item is
     * skipped while a download is still running. Pinned: at most 8 sheets (8 MiB) decoded at
     * any time, nothing held across an item change, no late sheet accepted, every sheet
     * released exactly once by the end, and far fewer downloads than today's 40 per item.
     */
    @Test
    fun ac3_scale_queueOf500WithSeeking_holdsEightMebibytesAtMost_andNothingAcrossItems() {
        val random = Random(1600)
        val previews = Previews()
        var peakLive = 0
        var peakBytes = 0L

        repeat(LONG_QUEUE) { item ->
            previews.resetFrom(twoHours)
            assertEquals("AC-2: item $item starts with nothing held", emptyList<Sheet>(), previews.sheets.live)

            previews.onSeekbarTouched(random.nextInt(twoHoursMs))
            repeat(30) {
                previews.getBitmapAt(random.nextInt(twoHoursMs))
                if (random.nextInt(3) != 0) previews.deliverOne()
                peakLive = Math.max(peakLive, previews.sheets.live.size)
                peakBytes = Math.max(peakBytes, previews.sheets.liveBytes)
                assertTrue("AC-3: item $item: ${previews.sheets.cache.bytes} bytes held", previews.sheets.cache.bytes <= LIMIT_BYTES)
            }
            if (item % 3 != 0) previews.deliverAll() // else: skipped with a download still running
        }
        previews.resetFrom(null)
        previews.deliverAll()

        assertTrue("AC-3: peak of decoded sheets over the whole queue, was $peakLive", peakLive <= SHEETS_WITHIN_LIMIT)
        assertTrue("AC-3: peak of decoded bytes over the whole queue, was $peakBytes", peakBytes <= LIMIT_BYTES)
        assertTrue("one download at a time per item", previews.peakRunningForCurrentItem <= 1)
        assertTrue("AC-2: late sheets were refused (${previews.downloadsRefused})", previews.downloadsRefused > 0)
        assertEquals("AC-2: at the end nothing is held", emptyList<Sheet>(), previews.sheets.live)
        assertEquals("every accepted sheet was released exactly once", emptyList<Sheet>(), previews.sheets.created.filter { it.recycled != 1 })
        assertEquals(
            "every download was either accepted into the cache or refused",
            previews.downloadsStarted,
            previews.sheets.created.size + previews.downloadsRefused
        )
        assertTrue(
            "downloads for 500 items with 31 seeks each: ${previews.downloadsStarted} (today: ${LONG_QUEUE * 40} at item start)",
            previews.downloadsStarted <= LONG_QUEUE * 31
        )
    }
}
