package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * AC-4 and AC-5 (finding M5, plan section 2): which queue items stay loaded.
 * `LoadWindow.keeps(index, currentIndex, queueSize)` is the whole decision; `SourceUnloader`
 * (glue, not unit tested) applies it to every position of the playlist after each
 * synchronization.
 *
 * The scale tests replay that loop on a model of the playlist (one flag per position: loaded
 * or placeholder) for queues of hundreds of items, and pin how many items are loaded at any
 * time. They count; nothing is timed.
 */
class LoadWindowTest {

    /** Spec AC-4: the current item, the previous one, the next two (plan: forward wrapping, as the loader wraps). */
    private fun expectedWindow(current: Int, size: Int): Set<Int> {
        val window = HashSet<Int>()
        window.add(current)
        if (current - 1 >= 0) window.add(current - 1)
        window.add((current + 1) % size)
        window.add((current + 2) % size)
        return window
    }

    /**
     * What upstream's loader loads, restated from `MediaSourceManager.getItemsToLoad`
     * (`MediaSourceManager.java:591-602`, `WINDOW_SIZE = 1`) as positions: the previous item if
     * any, the current one, the next one, and position 0 when the current item is the last.
     */
    private fun upstreamLoadSet(current: Int, size: Int): Set<Int> {
        val windowSize = 1
        val leftBound = Math.max(0, current - windowSize)
        val rightLimit = current + windowSize + 1
        val rightBound = Math.min(size, rightLimit)
        val positions = HashSet<Int>((leftBound until rightBound).toList())
        val excess = rightLimit - size
        if (excess >= 0) {
            positions.addAll((0 until Math.min(size, excess)).toList())
        }
        positions.add(current)
        return positions
    }

    /**
     * The playlist as far as memory is concerned: `loaded[i]` is true where upstream holds a
     * `LoadedMediaSource`, false where it holds the placeholder.
     */
    private class Playlist(size: Int) {
        val loaded = BooleanArray(size)
        val size: Int get() = loaded.size
        val loadedCount: Int get() = loaded.count { it }
        val loadedPositions: Set<Int> get() = loaded.indices.filter { loaded[it] }.toSet()
    }

    /** The loader finished: its positions hold loaded sources (upstream `onMediaSourceReceived`). */
    private fun load(playlist: Playlist, current: Int) {
        upstreamLoadSet(current, playlist.size).forEach { playlist.loaded[it] = true }
    }

    /**
     * `SourceUnloader.unloadOutsideWindow` as plan section 2 states it: every loaded position
     * that `LoadWindow.keeps` does not keep becomes the placeholder. Returns how many.
     */
    private fun unloadOutsideWindow(playlist: Playlist, current: Int): Int {
        var unloaded = 0
        for (i in 0 until playlist.size) {
            if (playlist.loaded[i] && !LoadWindow.keeps(i, current, playlist.size)) {
                playlist.loaded[i] = false
                unloaded++
            }
        }
        return unloaded
    }

    // ---- AC-4: the window ----

    @Test
    fun ac4_window_isOneBehindAndTwoAhead() {
        assertEquals("AC-4: the previous one", 1, LoadWindow.KEEP_BEHIND)
        assertEquals("AC-4: the next two", 2, LoadWindow.KEEP_AHEAD)
    }

    @Test
    fun ac4_middleOfAQueue_keepsPreviousCurrentAndNextTwo() {
        val kept = (0 until 10).filter { LoadWindow.keeps(it, 5, 10) }

        assertEquals("AC-4: of ten items with item 5 current, items 4, 5, 6 and 7 stay loaded", listOf(4, 5, 6, 7), kept)
    }

    /** Plan section 4, `LoadWindowTest` row: sizes 1..40, every current index, exactly the window. */
    @Test
    fun ac4_everySizeTo40_everyCurrentIndex_exactlyTheWindowIsKept() {
        for (size in 1..40) {
            for (current in 0 until size) {
                val expected = expectedWindow(current, size)
                for (index in -3 until size + 3) {
                    assertEquals(
                        "AC-4: keeps(index=$index, currentIndex=$current, queueSize=$size)",
                        index in expected,
                        LoadWindow.keeps(index, current, size)
                    )
                }
            }
        }
    }

    /** Plan section 2: "False for an index outside `0 until queueSize`", also where the modulo would say yes. */
    @Test
    fun ac4_positionsOutsideTheQueue_areNeverKept() {
        assertFalse("AC-4: -1 is no item ((-1 - 0) mod 3 is 2)", LoadWindow.keeps(-1, 0, 3))
        assertFalse("AC-4: -2 is no item ((-2 - 0) mod 3 is 1)", LoadWindow.keeps(-2, 0, 3))
        assertFalse("AC-4: position 5 of five items does not exist ((5 - 4) mod 5 is 1)", LoadWindow.keeps(5, 4, 5))
        assertFalse("AC-4: position 6 of five items does not exist", LoadWindow.keeps(6, 4, 5))
        assertFalse("AC-4: the queue size itself is no position", LoadWindow.keeps(LONG_QUEUE, 0, LONG_QUEUE))
        assertFalse(LoadWindow.keeps(Int.MIN_VALUE, 0, 10))
        assertFalse(LoadWindow.keeps(Int.MAX_VALUE, 0, 10))
    }

    /** An empty queue has no position; the modulo must not be asked. */
    @Test
    fun ac4_emptyQueue_keepsNothingAndDoesNotThrow() {
        for (index in -2..2) {
            assertFalse("AC-4: keeps($index, 0, 0)", LoadWindow.keeps(index, 0, 0))
        }
    }

    /** Only "the next two" wrap (A3); the previous item of the first one is not the last. */
    @Test
    fun ac4_firstItem_theLastItemIsNotItsPrevious() {
        assertFalse("AC-4: with item 0 current, the last of ten is not kept", LoadWindow.keeps(9, 0, 10))
        assertFalse(LoadWindow.keeps(8, 0, 10))
        assertEquals(listOf(0, 1, 2), (0 until 10).filter { LoadWindow.keeps(it, 0, 10) })
    }

    /** A3 (1): "the next two" wrap at the end of the queue as the player's own order does. */
    @Test
    fun ac4_endOfTheQueue_theNextTwoWrapToTheStart() {
        assertEquals(
            "AC-4: last item current: previous, current, and the first two",
            listOf(0, 1, 8, 9),
            (0 until 10).filter { LoadWindow.keeps(it, 9, 10) }
        )
        assertEquals(
            "AC-4: second to last current: previous, current, last, first",
            listOf(0, 7, 8, 9),
            (0 until 10).filter { LoadWindow.keeps(it, 8, 10) }
        )
    }

    @Test
    fun ac4_shortQueues_keepEveryItemOnce() {
        assertTrue("one item: it is the current one", LoadWindow.keeps(0, 0, 1))
        assertEquals(listOf(0, 1), (0 until 2).filter { LoadWindow.keeps(it, 0, 2) })
        assertEquals(listOf(0, 1), (0 until 2).filter { LoadWindow.keeps(it, 1, 2) })
        for (current in 0 until 3) {
            assertEquals("three items, item $current current", listOf(0, 1, 2), (0 until 3).filter { LoadWindow.keeps(it, current, 3) })
        }
    }

    /** AC-4 at the owner's scale: whatever the queue's length, never more than four positions are kept. */
    @Test
    fun ac4_queuesOfHundreds_atMostFourPositionsAreKept_whereverThePlayerIs() {
        for (size in intArrayOf(4, 5, 30, LONG_QUEUE, VERY_LONG_QUEUE)) {
            for (current in 0 until size) {
                val kept = (0 until size).count { LoadWindow.keeps(it, current, size) }
                assertEquals(
                    "AC-4: positions kept of $size with item $current current",
                    if (current == 0) 3 else 4,
                    kept
                )
            }
        }
    }

    // ---- AC-5: what the loader loads is never unloaded ----

    /** Plan section 4: every index the upstream loader loads is kept, so next and previous find loaded sources as today. */
    @Test
    fun ac5_everyPositionTheLoaderLoads_isKept() {
        val sizes = (1..40).toList() + listOf(LONG_QUEUE, VERY_LONG_QUEUE)
        for (size in sizes) {
            for (current in 0 until size) {
                for (index in upstreamLoadSet(current, size)) {
                    assertTrue(
                        "AC-5: the loader loads position $index while $current of $size is current; it must stay loaded",
                        LoadWindow.keeps(index, current, size)
                    )
                }
            }
        }
    }

    /** AC-5: the next and the previous item, the ones a transition goes to, by name. */
    @Test
    fun ac5_nextAndPrevious_stayLoaded() {
        for (current in 1 until LONG_QUEUE - 1) {
            assertTrue("AC-5: next of $current", LoadWindow.keeps(current + 1, current, LONG_QUEUE))
            assertTrue("AC-5: previous of $current", LoadWindow.keeps(current - 1, current, LONG_QUEUE))
        }
        assertTrue("AC-5: repeat all, the first item follows the last", LoadWindow.keeps(0, LONG_QUEUE - 1, LONG_QUEUE))
    }

    // ---- AC-4 at scale: how much stays loaded while a long queue plays ----

    /**
     * A queue of 500 played from the first item to the last and, with repeat all, twice more.
     * Today every item played stays loaded (500 after the first pass). With the window: never
     * more than four, and about one item is unloaded per transition (plan D2: "one unload per skip").
     */
    @Test
    fun ac4_scale_queueOf500PlayedThreeTimesAround_neverHoldsMoreThanFour() {
        val playlist = Playlist(LONG_QUEUE)
        var peak = 0
        var unloadedInAll = 0
        val steps = 3 * LONG_QUEUE

        for (step in 0 until steps) {
            val current = step % LONG_QUEUE
            load(playlist, current)
            // Between the load and the synchronization the window plus nothing else is loaded.
            assertTrue("AC-4: step $step: loaded before the unload: ${playlist.loadedPositions}", playlist.loadedCount <= 5)
            val unloaded = unloadOutsideWindow(playlist, current)
            unloadedInAll += unloaded

            peak = Math.max(peak, playlist.loadedCount)
            assertTrue("AC-4: step $step: at most four items loaded, found ${playlist.loadedPositions}", playlist.loadedCount <= 4)
            // One per transition; two at the wrap to the first item, which has no previous one.
            assertTrue("AC-4: step $step: at most two items are unloaded per transition, was $unloaded", unloaded <= 2)
            assertTrue("AC-5: step $step: the loader's items are loaded", playlist.loadedPositions.containsAll(upstreamLoadSet(current, LONG_QUEUE)))
        }

        assertTrue("AC-4: peak of loaded items over three passes of 500, was $peak", peak <= 4)
        // Every transition but the first few unloads exactly the item that fell out behind.
        assertTrue("AC-4: about one unload per transition, was $unloadedInAll of $steps", unloadedInAll >= steps - 4)
        assertTrue(unloadedInAll <= steps)
    }

    /**
     * Hours of use: 20 000 moves in a queue of 1600 (next, previous, far picks from the queue
     * screen, in a fixed pseudo-random order). After every synchronization at most four items
     * are loaded, the loader's items among them, and no position ever holds a loaded source
     * outside the window.
     */
    @Test
    fun ac4_scale_hoursOfSkippingAndFarPicks_neverHoldMoreThanFour() {
        val random = Random(20261115)
        val playlist = Playlist(VERY_LONG_QUEUE)
        var current = 0
        var peak = 0
        var loads = 0L

        repeat(20_000) { step ->
            current = when (random.nextInt(10)) {
                0, 1 -> random.nextInt(VERY_LONG_QUEUE) // a pick further away
                2, 3 -> Math.max(0, current - 1) // previous
                else -> (current + 1) % VERY_LONG_QUEUE // next, wrapping with repeat all
            }
            val before = playlist.loadedCount
            load(playlist, current)
            loads += playlist.loadedCount - before
            unloadOutsideWindow(playlist, current)

            peak = Math.max(peak, playlist.loadedCount)
            assertTrue("AC-4: move $step to $current: loaded ${playlist.loadedPositions}", playlist.loadedCount <= 4)
            assertEquals(
                "AC-4: move $step to $current: nothing loaded outside the window",
                emptySet<Int>(),
                playlist.loadedPositions - expectedWindow(current, VERY_LONG_QUEUE)
            )
            assertTrue("AC-5: move $step: the current item and its neighbours are loaded", playlist.loadedPositions.containsAll(upstreamLoadSet(current, VERY_LONG_QUEUE)))
        }

        assertTrue("AC-4: peak over 20 000 moves in a queue of 1600, was $peak", peak <= 4)
        assertTrue("the model did load items again after unloading them (AC-4: 'loads again')", loads > 4)
    }

    /**
     * AC-4, "loads again when the player reaches it": an item played, left far behind and picked
     * again was unloaded in between, and is back in the window (and in the loader's set) when
     * it is current again.
     */
    @Test
    fun ac4_anItemLeftFarBehind_isUnloaded_andKeptAgainWhenReached() {
        val playlist = Playlist(LONG_QUEUE)
        load(playlist, 3)
        unloadOutsideWindow(playlist, 3)
        assertTrue(playlist.loaded[3])

        load(playlist, 25)
        val unloaded = unloadOutsideWindow(playlist, 25)

        assertFalse("AC-4: item 3 is unloaded while 25 plays", playlist.loaded[3])
        assertEquals("AC-4: items 2, 3 and 4 were unloaded by the far pick", 3, unloaded)
        assertEquals(setOf(24, 25, 26), playlist.loadedPositions)

        load(playlist, 3)
        unloadOutsideWindow(playlist, 3)
        assertTrue("AC-4: item 3 is loaded again when it is picked again", playlist.loaded[3])
        assertEquals(setOf(2, 3, 4), playlist.loadedPositions)
    }

    /**
     * Plan section 2, "Shuffle": after a reorder the current item is at position 0 and other
     * positions hold sources of other items. The first synchronization unloads all of them.
     * Today (and with a window that looked only at neighbours of the old position) they stay.
     */
    @Test
    fun ac4_scale_afterAShuffle_oneSynchronizationUnloadsEveryOtherPosition() {
        val random = Random(7)
        val playlist = Playlist(LONG_QUEUE)
        // 300 positions hold loaded sources of items that are now elsewhere.
        while (playlist.loadedCount < 300) {
            playlist.loaded[random.nextInt(LONG_QUEUE)] = true
        }
        val outside = playlist.loadedPositions.count { it !in setOf(0, 1, 2) }

        load(playlist, 0)
        val unloaded = unloadOutsideWindow(playlist, 0)

        assertEquals("AC-4: every loaded position outside the window is unloaded at once", outside, unloaded)
        assertEquals("AC-4: after the shuffle only the current item and the next two can be loaded", setOf(0, 1), playlist.loadedPositions - setOf(2))
        assertTrue(playlist.loadedCount <= 3)
    }

    /** Plan section 2, "Repeat": at the last item position 0 is kept; after the wrap the last item goes. */
    @Test
    fun ac4_repeatAll_threeItemQueue_andLongQueue_wrapWithoutUnloadingTheNextItem() {
        val small = Playlist(3)
        for (step in 0 until 12) {
            val current = step % 3
            load(small, current)
            assertEquals("AC-4: three items are all inside the window (step $step)", 0, unloadOutsideWindow(small, current))
        }

        val long = Playlist(LONG_QUEUE)
        val last = LONG_QUEUE - 1
        load(long, last - 1)
        unloadOutsideWindow(long, last - 1)
        load(long, last)
        unloadOutsideWindow(long, last)
        assertTrue("AC-5: at the last item the first one, which follows it, is loaded and kept", long.loaded[0])
        load(long, 0)
        unloadOutsideWindow(long, 0)
        assertFalse("AC-4: after the wrap the last item is unloaded", long.loaded[last])
        assertEquals(setOf(0, 1), long.loadedPositions)
    }

    /** Plan section 2, "Repeat one" and a paused player: synchronizing again on the same item unloads nothing. */
    @Test
    fun ac4_sameItemSynchronizedAgain_unloadsNothing() {
        val playlist = Playlist(LONG_QUEUE)
        load(playlist, 100)
        unloadOutsideWindow(playlist, 100)

        repeat(1000) {
            load(playlist, 100)
            assertEquals(0, unloadOutsideWindow(playlist, 100))
        }
        assertEquals(setOf(99, 100, 101), playlist.loadedPositions)
    }
}
