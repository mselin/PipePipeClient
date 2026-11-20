package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-1 and AC-2 (finding M4, plan section 3): when a preview sheet may be downloaded.
 * `PreviewLoadState` decides it for the glue: nothing before the seekbar of the item was
 * touched in a player that can show a preview (`arm`), one download at a time with the latest
 * wanted page next (`request`, `onLoaded`), and nothing of an item survives the move to the
 * next one (`onNewItem`: a result of the old generation is not accepted and starts nothing).
 *
 * "A download starts" below means `request` or `Result.next` returned a page; the scale tests
 * count those.
 */
class PreviewLoadStateTest {

    private fun armed(): PreviewLoadState = PreviewLoadState().also {
        it.onNewItem()
        it.arm(true)
    }

    // ---- AC-1: nothing before the first touch, nothing in the background player ----

    @Test
    fun ac1_newState_isNotArmed_andStartsNothing() {
        val state = PreviewLoadState()

        assertFalse("AC-1: not armed before any touch", state.armed)
        assertNull("AC-1: no download before the seekbar is touched", state.request(0, false))
        assertNull(state.request(5, false))
    }

    @Test
    fun ac1_afterANewItem_nothingStartsBeforeTheTouch() {
        val state = PreviewLoadState()
        state.onNewItem()

        assertFalse(state.armed)
        for (page in 0 until 48) {
            assertNull("AC-1: page $page must not be downloaded before the first touch", state.request(page, false))
        }
    }

    /** `arm(canShow = false)`: the background player (`audioPlayerSelected()`) cannot show a preview. */
    @Test
    fun ac1_armFalse_doesNotArm_soTheBackgroundPlayerLoadsNothing() {
        val state = PreviewLoadState()
        state.onNewItem()

        assertFalse("AC-1: arm(false) returns false", state.arm(false))
        assertFalse(state.armed)
        assertNull("AC-1: nothing is downloaded in the background player", state.request(3, false))
        assertFalse("AC-1: still false the second time", state.arm(false))
        assertNull(state.request(3, false))
    }

    @Test
    fun ac1_armTrue_arms_andTheTouchedPageStarts() {
        val state = PreviewLoadState()
        state.onNewItem()

        assertTrue("AC-1: arm(true) returns true", state.arm(true))
        assertTrue(state.armed)
        assertEquals("AC-1: the page under the thumb is downloaded after the touch", 7, state.request(7, false))
    }

    /** Plan: `armed = armed || canShow`: once touched in a player with a seekbar, the item stays armed. */
    @Test
    fun ac1_onceArmed_aLaterArmFalseDoesNotDisarm() {
        val state = armed()

        assertTrue("armed || false is true", state.arm(false))
        assertTrue(state.armed)
        assertEquals(2, state.request(2, false))
    }

    /** What was asked for before the touch is not remembered: the touch itself starts nothing. */
    @Test
    fun ac1_requestsBeforeArming_leaveNothingBehind() {
        val state = PreviewLoadState()
        state.onNewItem()
        assertNull(state.request(4, false))
        assertNull(state.request(9, false))

        state.arm(true)

        assertEquals("nothing was in flight: the first request after the touch starts", 1, state.request(1, false))
        assertEquals(
            "nothing was pending from before the touch",
            PreviewLoadState.Result(true, null),
            state.onLoaded(state.generation, 1)
        )
    }

    // ---- one download at a time, latest wins ----

    @Test
    fun ac1_cachedPage_startsNothing() {
        val state = armed()

        assertNull("a page already in the cache is not downloaded again", state.request(3, true))
        assertEquals("and it is not in flight: another page starts at once", 4, state.request(4, false))
    }

    @Test
    fun ac1_pageInFlight_isNotStartedTwice() {
        val state = armed()

        assertEquals(3, state.request(3, false))
        assertNull("the page being downloaded is not started again", state.request(3, false))
        assertNull(state.request(3, false))
        assertEquals(
            "asking again for the page in flight leaves nothing pending",
            PreviewLoadState.Result(true, null),
            state.onLoaded(state.generation, 3)
        )
    }

    @Test
    fun ac1_whileOneIsInFlight_theNextPageWaits_andStartsWhenTheFirstIsLoaded() {
        val state = armed()
        assertEquals(3, state.request(3, false))

        assertNull("one download at a time", state.request(4, false))

        assertEquals(
            "the waiting page is started by the result of the first",
            PreviewLoadState.Result(true, 4),
            state.onLoaded(state.generation, 3)
        )
        assertNull("page 4 is now the one in flight", state.request(4, false))
        assertEquals(PreviewLoadState.Result(true, null), state.onLoaded(state.generation, 4))
    }

    /** Plan: "`page` replaces the single pending page (latest wins)". */
    @Test
    fun ac1_latestWins_onlyTheLastWantedPageIsPending() {
        val state = armed()
        assertEquals(0, state.request(0, false))

        for (page in 1..20) {
            assertNull(state.request(page, false))
        }

        assertEquals(
            "of twenty pages passed while one was downloading, only the last one is loaded next",
            PreviewLoadState.Result(true, 20),
            state.onLoaded(state.generation, 0)
        )
        assertEquals(
            "the nineteen in between are not downloaded at all",
            PreviewLoadState.Result(true, null),
            state.onLoaded(state.generation, 20)
        )
    }

    @Test
    fun ac1_afterALoad_theStateIsIdle_andTheNextRequestStartsAtOnce() {
        val state = armed()
        state.request(0, false)
        state.onLoaded(state.generation, 0)

        assertEquals(6, state.request(6, false))
    }

    /** Plan, read in order: "null when not armed, when `cached`, or when `page` is the page in flight" changes nothing. */
    @Test
    fun ac1_cachedOrInFlightRequests_doNotDropThePendingPage() {
        val state = armed()
        assertEquals(0, state.request(0, false))
        assertNull(state.request(5, false))

        assertNull(state.request(2, true))
        assertNull(state.request(0, false))

        assertEquals(PreviewLoadState.Result(true, 5), state.onLoaded(state.generation, 0))
    }

    @Test
    fun ac1_accepted_isTrueForTheCurrentGeneration() {
        val state = armed()
        state.request(11, false)

        val result = state.onLoaded(state.generation, 11)

        assertTrue("the sheet of the current item is accepted into the cache", result.accept)
        assertNull(result.next)
    }

    // ---- AC-2: moving to another item ----

    @Test
    fun ac2_onNewItem_returnsTheNextGeneration() {
        val state = PreviewLoadState()
        val before = state.generation

        val returned = state.onNewItem()

        assertEquals("plan: generation + 1", before + 1, returned)
        assertEquals(returned, state.generation)
        assertEquals(returned + 1, state.onNewItem())
        assertEquals(before + 2, state.generation)
    }

    @Test
    fun ac2_onNewItem_disarms() {
        val state = armed()
        assertTrue(state.armed)

        state.onNewItem()

        assertFalse("AC-2/AC-1: the next item starts untouched", state.armed)
        assertNull("AC-1: nothing of the next item is downloaded before its seekbar is touched", state.request(0, false))
    }

    /** AC-2: "a load still running for the old item stops": its result is not accepted and starts nothing. */
    @Test
    fun ac2_resultOfTheOldItem_isNotAccepted_andStartsNothing() {
        val state = armed()
        val old = state.generation
        assertEquals(3, state.request(3, false))
        assertNull(state.request(9, false))

        state.onNewItem()

        assertEquals(
            "AC-2: the sheet of the old item is discarded, and the page that waited behind it is not downloaded",
            PreviewLoadState.Result(false, null),
            state.onLoaded(old, 3)
        )
    }

    /** `onNewItem` "forgets in-flight and pending page": the new item's first request starts, even for the same page number. */
    @Test
    fun ac2_onNewItem_forgetsInFlightAndPending() {
        val state = armed()
        assertEquals(3, state.request(3, false))
        assertNull(state.request(9, false))

        state.onNewItem()
        state.arm(true)

        assertEquals("page 3 of the new item is another sheet: it starts", 3, state.request(3, false))
        assertEquals(
            "the old item's pending page 9 is gone",
            PreviewLoadState.Result(true, null),
            state.onLoaded(state.generation, 3)
        )
    }

    /** A late result of the old item must not disturb the new item's download. */
    @Test
    fun ac2_lateResultOfTheOldItem_doesNotTouchTheNewItemsDownload() {
        val state = armed()
        val old = state.generation
        state.request(3, false)

        state.onNewItem()
        state.arm(true)
        assertEquals(5, state.request(5, false))
        assertNull(state.request(6, false))

        assertEquals(PreviewLoadState.Result(false, null), state.onLoaded(old, 3))
        assertEquals("the same late result twice changes nothing either", PreviewLoadState.Result(false, null), state.onLoaded(old, 5))

        assertNull("page 5 of the new item is still in flight", state.request(5, false))
        assertEquals(
            "and page 6 still waits behind it",
            PreviewLoadState.Result(true, 6),
            state.onLoaded(state.generation, 5)
        )
    }

    @Test
    fun ac2_generationsOfDifferentItems_differ() {
        val state = PreviewLoadState()
        val generations = (0 until LONG_QUEUE).map { state.onNewItem() }

        assertEquals("every item of a queue of 500 has its own generation", LONG_QUEUE, generations.toSet().size)
        assertNotEquals(generations.first(), generations.last())
    }

    @Test
    fun result_isAValue() {
        assertEquals(PreviewLoadState.Result(true, 4), PreviewLoadState.Result(true, 4))
        assertNotEquals(PreviewLoadState.Result(true, 4), PreviewLoadState.Result(false, 4))
        assertNotEquals(PreviewLoadState.Result(true, 4), PreviewLoadState.Result(true, null))
    }

    // ---- at scale: how many downloads start ----

    /**
     * AC-1 at the owner's scale: a queue of 500 played in the background player. Every item
     * asks for previews as the glue would if something called it (`arm(false)`, then requests);
     * today that is up to 48 downloads per item, 24 000 for the queue. Here: none.
     */
    @Test
    fun ac1_scale_queueOf500InTheBackgroundPlayer_startsNoDownload() {
        val state = PreviewLoadState()
        var started = 0

        repeat(LONG_QUEUE) {
            state.onNewItem()
            for (page in 0 until 48) {
                state.arm(false)
                if (state.request(page, false) != null) started++
            }
        }

        assertEquals("AC-1: downloads started for 500 items in the background player", 0, started)
    }

    /** AC-1: 500 items in the main player whose seekbar is never touched: none either. */
    @Test
    fun ac1_scale_queueOf500NeverTouched_startsNoDownload() {
        val state = PreviewLoadState()
        var started = 0

        repeat(LONG_QUEUE) {
            state.onNewItem()
            for (page in 0 until 48) {
                if (state.request(page, false) != null) started++
            }
        }

        assertEquals("AC-1: downloads started for 500 untouched items", 0, started)
    }

    /**
     * A fast drag across all 48 sheets of each of 500 items while every download takes longer
     * than the drag: the first page and the last wanted one are downloaded, two per item, not
     * 48; and never two at the same time.
     */
    @Test
    fun ac1_scale_fastDragAcross48Sheets_downloadsTwoPerItem_oneAtATime() {
        val state = PreviewLoadState()
        var started = 0

        repeat(LONG_QUEUE) { item ->
            val generation = state.onNewItem()
            state.arm(true)
            var inFlight: Int? = null
            for (page in 0 until 48) {
                val start = state.request(page, false)
                if (start != null) {
                    assertNull("item $item: page $start starts while page $inFlight is still downloading", inFlight)
                    inFlight = start
                    started++
                }
            }
            // The drag is over; the downloads come in.
            while (inFlight != null) {
                val result = state.onLoaded(generation, inFlight)
                assertTrue(result.accept)
                inFlight = result.next
                if (inFlight != null) started++
            }
            assertEquals("item $item: the last page loaded is the one under the thumb", null, state.request(47, true))
        }

        assertEquals("downloads for 500 items dragged across once (today: 48 each)", 2 * LONG_QUEUE, started)
    }

    /**
     * Skipping through 500 items while a download of each is still running (AC-2): every late
     * result is refused, none starts another download, and each new item still loads its own
     * touched page.
     */
    @Test
    fun ac2_scale_skipping500ItemsWithADownloadRunning_everyLateResultIsRefused() {
        val state = PreviewLoadState()
        val running = ArrayList<Pair<Int, Int>>() // generation, page
        var startedForCurrent = 0

        repeat(LONG_QUEUE) { item ->
            state.onNewItem()
            state.arm(true)
            val page = item % 48
            assertEquals("item $item: its own page starts although older downloads never reported", page, state.request(page, false))
            startedForCurrent++
            assertNull(state.request(page + 1, false))
            running.add(state.generation to page)
        }

        val current = running.removeAt(running.size - 1)
        var refused = 0
        for ((generation, page) in running) {
            assertEquals(PreviewLoadState.Result(false, null), state.onLoaded(generation, page))
            refused++
        }

        assertEquals(LONG_QUEUE - 1, refused)
        assertEquals(LONG_QUEUE, startedForCurrent)
        assertEquals(
            "the current item's download is accepted and its waiting page follows",
            PreviewLoadState.Result(true, current.second + 1),
            state.onLoaded(current.first, current.second)
        )
    }
}
