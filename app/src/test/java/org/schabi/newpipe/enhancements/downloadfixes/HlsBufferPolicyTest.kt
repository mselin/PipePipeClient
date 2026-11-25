package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * AC-3 (M7), plan section 3: `HlsBufferPolicy`, the limit and the submit window, and a model
 * of the engine's loop that uses them together with the real `SegmentMemoryBudget`.
 *
 * Plan: "Segments that may be submitted and not yet written: 2 per worker", and the gate
 * `nextToSubmit - nextToAppend < window(parallelism)`. The engine clamps the parallelism to
 * at least 1 (`config.parallelism.coerceIn(1, remainingSegments)`), so 1 is the smallest value
 * tested; the largest in the app is 6.
 */
class HlsBufferPolicyTest {

    // ---- constants ----

    /** AC-3, A3: "64 MiB per transfer". */
    @Test
    fun ac3_theLimitIs64Mebibytes() {
        assertEquals(64L * 1024 * 1024, HlsBufferPolicy.LIMIT_BYTES)
        assertEquals(67_108_864L, HlsBufferPolicy.LIMIT_BYTES)
    }

    /** AC-3, plan 5.: the key is read with a limit of 64 KiB; a valid key (16 bytes) fits. */
    @Test
    fun ac3_theKeyLimitIs64Kibibytes() {
        assertEquals(64L * 1024, HlsBufferPolicy.KEY_LIMIT_BYTES)
        assertTrue(HlsBufferPolicy.KEY_LIMIT_BYTES >= 16)
    }

    // ---- window arithmetic ----

    /** AC-3, plan: the window is two segments per worker. */
    @Test
    fun ac3_window_isTwoPerWorker() {
        assertEquals(2, HlsBufferPolicy.window(1))
        assertEquals(4, HlsBufferPolicy.window(2))
        assertEquals(6, HlsBufferPolicy.window(3))
        assertEquals(10, HlsBufferPolicy.window(5))
        assertEquals(12, HlsBufferPolicy.window(6))
    }

    /** AC-3: `maySubmit` is `nextToSubmit - nextToAppend < window(parallelism)`, for every pair in a range. */
    @Test
    fun ac3_maySubmit_isTheWindowComparison() {
        for (parallelism in 1..6) {
            val window = HlsBufferPolicy.window(parallelism)
            for (nextToAppend in 0..40) for (nextToSubmit in nextToAppend..nextToAppend + 30) {
                assertEquals(
                    "maySubmit($nextToSubmit, $nextToAppend, $parallelism)",
                    nextToSubmit - nextToAppend < window,
                    HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism)
                )
            }
        }
    }

    /** AC-5: at the start of a transfer (and of a resumed one, which starts at a later segment) the gate is open. */
    @Test
    fun ac5_maySubmit_isOpenWhenNothingIsOutstanding() {
        for (parallelism in 1..6) for (startSegment in listOf(0, 1, 7, 5000)) {
            assertTrue(HlsBufferPolicy.maySubmit(startSegment, startSegment, parallelism))
        }
    }

    /**
     * AC-3, plan section 5 "the head outstanding case": the segment at `nextToAppend` stalls.
     * Exactly `window` segments are submitted in all (the head and `window - 1` later ones),
     * then the gate is closed, however long the stall lasts.
     */
    @Test
    fun ac3_headOutstanding_exactlyWindowSegmentsAreSubmitted() {
        for (parallelism in 1..6) for (head in listOf(0, 3, 1000)) {
            val window = HlsBufferPolicy.window(parallelism)
            var nextToSubmit = head
            var guard = 0
            while (HlsBufferPolicy.maySubmit(nextToSubmit, head, parallelism)) {
                nextToSubmit++
                if (guard++ > 1000) fail("the gate never closes with parallelism $parallelism")
            }

            assertEquals("parallelism $parallelism, head $head: submitted and not written", window, nextToSubmit - head)
            assertFalse(HlsBufferPolicy.maySubmit(nextToSubmit, head, parallelism))
            assertFalse(HlsBufferPolicy.maySubmit(nextToSubmit + 1, head, parallelism))
        }
    }

    /** AC-3/AC-5: each segment the writer writes opens the gate for exactly one more. */
    @Test
    fun ac3_everyWrittenSegmentAdmitsOneMore() {
        for (parallelism in 1..6) {
            val window = HlsBufferPolicy.window(parallelism)
            var nextToAppend = 10
            var nextToSubmit = nextToAppend + window
            repeat(50) {
                assertFalse(HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism))
                nextToAppend++ // the writer wrote the head
                assertTrue(HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism))
                nextToSubmit++
            }
        }
    }

    /**
     * AC-5, plan "No deadlock": the gate never closes while a worker could not be working on
     * the head. With the gate closed, at least one segment is submitted and not written, and it
     * includes the one at `nextToAppend`; and the window is never smaller than the parallelism,
     * so the first `repeat(parallelism) { submitNextSegment() }`, which is not gated, stays inside it.
     */
    @Test
    fun ac5_theWindowIsNeverSmallerThanTheParallelism_andNeverEmpty() {
        for (parallelism in 1..64) {
            val window = HlsBufferPolicy.window(parallelism)
            assertTrue("window($parallelism) = $window", window >= parallelism)
            assertTrue(window >= 1)
            assertTrue("with nothing outstanding the gate is open", HlsBufferPolicy.maySubmit(0, 0, parallelism))
            assertTrue("a full set of workers can be busy", HlsBufferPolicy.maySubmit(parallelism - 1, 0, parallelism))
        }
    }

    // ---- the loop ----

    /** What a worker hands to the writer: plan 2., `DownloadedSegment` with `unbuffered` and `charge`. */
    private class Downloaded(val index: Int, val unbuffered: Boolean, val charge: SegmentCharge)

    private class Outcome(
        val written: List<Int>,
        val streamed: Int,
        val mostOutstanding: Int,
        val mostWaiting: Int,
        val budget: SegmentMemoryBudget,
    )

    /**
     * The engine's loop of `downloadSegmentsInParallel` (`:257-310`) as the plan changes it:
     * the gate of 4. in the submit condition, the writer of 3. that writes a buffered segment
     * and releases its charge, or streams an unbuffered one. One "event" is one completed
     * future; [pickCompleted] chooses which of the segments in flight it is, which is all a
     * network can decide. A worker charges the segment's size ([copies] times while it works,
     * once when it is done), as `SegmentBuffer` and the decrypt step do.
     *
     * Fails if the loop would wait for ever: nothing in flight and segments left.
     */
    private fun runLoop(
        segmentSizes: LongArray,
        parallelismSetting: Int,
        capacity: Long,
        startSegmentIndex: Int = 0,
        copies: Int = 1,
        pickCompleted: (inFlight: List<Int>, nextToAppend: Int) -> Int,
    ): Outcome {
        val budget = SegmentMemoryBudget(capacity)
        val total = segmentSizes.size
        val remainingSegments = total - startSegmentIndex
        val parallelism = parallelismSetting.coerceIn(1, remainingSegments) // :209
        val downloadedSegments = HashMap<Int, Downloaded>()
        val inFlight = ArrayList<Int>()
        var nextToSubmit = startSegmentIndex
        var nextToAppend = startSegmentIndex
        var submitted = 0
        var completed = 0
        val written = ArrayList<Int>()
        var streamed = 0
        var mostOutstanding = 0
        var mostWaiting = 0

        fun submitNextSegment() {
            inFlight.add(nextToSubmit++)
            submitted++
        }

        fun work(index: Int): Downloaded {
            val charge = budget.open()
            return try {
                charge.checkDeclared(segmentSizes[index], copies)
                repeat(copies) { charge.acquire(segmentSizes[index]) }
                charge.settle(segmentSizes[index])
                Downloaded(index, unbuffered = false, charge = charge)
            } catch (e: SegmentOverBudgetException) {
                Downloaded(index, unbuffered = true, charge = charge)
            }
        }

        repeat(parallelism) { submitNextSegment() } // :257

        while (completed < remainingSegments) { // :259
            if (inFlight.isEmpty()) {
                fail(
                    "deadlock: nothing in flight, $completed of $remainingSegments completed, " +
                        "nextToSubmit=$nextToSubmit nextToAppend=$nextToAppend parallelism=$parallelism"
                )
            }
            val index = pickCompleted(inFlight.toList(), nextToAppend)
            assertTrue(inFlight.remove(index))
            val downloaded = work(index)

            completed++
            downloadedSegments[downloaded.index] = downloaded

            while (true) { // :281-306
                val next = downloadedSegments.remove(nextToAppend) ?: break
                if (next.unbuffered) streamed++
                written.add(next.index)
                next.charge.release()
                nextToAppend++
            }

            while (nextToSubmit < total && submitted - completed < parallelism && // :308 with plan 4.
                HlsBufferPolicy.maySubmit(nextToSubmit, nextToAppend, parallelism)
            ) {
                submitNextSegment()
            }

            mostOutstanding = maxOf(mostOutstanding, nextToSubmit - nextToAppend)
            mostWaiting = maxOf(mostWaiting, downloadedSegments.size)
            assertTrue("held ${budget.heldBytes} of $capacity", budget.heldBytes <= capacity)
        }

        return Outcome(written, streamed, mostOutstanding, mostWaiting, budget)
    }

    private fun assertComplete(outcome: Outcome, total: Int, startSegmentIndex: Int = 0) {
        assertEquals("AC-5: every segment is written once, in playlist order", (startSegmentIndex until total).toList(), outcome.written)
        assertEquals("AC-3: nothing is held when the transfer is done", 0L, outcome.budget.heldBytes)
        assertTrue("AC-3: peak ${outcome.budget.peakBytes} within ${outcome.budget.capacityBytes}", outcome.budget.peakBytes <= outcome.budget.capacityBytes)
    }

    /** The head is the last to arrive whenever anything else is in flight: the stalled segment of the finding. */
    private val headStalls: (List<Int>, Int) -> Int = { inFlight, head -> inFlight.firstOrNull { it != head } ?: head }

    /**
     * AC-3, the finding: the segment at `nextToAppend` stalls while the others keep finishing.
     * Upstream holds every later segment that arrives in the meantime; with the gate at most
     * `window - 1` wait, for every thread count.
     */
    @Test
    fun ac3_loop_stalledHead_atMostWindowMinusOneSegmentsWait() {
        for (parallelism in 1..6) {
            val window = HlsBufferPolicy.window(parallelism)
            val sizes = LongArray(200) { 2 * MIB }
            val outcome = runLoop(sizes, parallelism, HlsBufferPolicy.LIMIT_BYTES, pickCompleted = headStalls)

            assertComplete(outcome, sizes.size)
            assertTrue("parallelism $parallelism: outstanding ${outcome.mostOutstanding} <= window $window", outcome.mostOutstanding <= window)
            assertTrue("parallelism $parallelism: waiting ${outcome.mostWaiting} <= window - 1", outcome.mostWaiting <= window - 1)
            assertEquals("2 MiB segments: 11 waiting ones are 22 MiB, nothing has to be streamed", 0, outcome.streamed)
        }
    }

    /**
     * AC-3 "whatever the ... segment size": the same stall with segments of 20 MiB and six
     * threads. Eleven of them do not fit into 64 MiB: three wait in memory, the rest is handed
     * over as "unbuffered" and streamed by the writer. Nothing fails, the bound holds.
     */
    @Test
    fun ac3_loop_stalledHead_largeSegments_boundHolds_restIsStreamed() {
        val sizes = LongArray(60) { 20 * MIB }
        val outcome = runLoop(sizes, 6, HlsBufferPolicy.LIMIT_BYTES, pickCompleted = headStalls)

        assertComplete(outcome, sizes.size)
        assertTrue("some segments were streamed", outcome.streamed > 0)
        assertTrue("and some were buffered", outcome.streamed < sizes.size)
        assertEquals("the budget was used up to the three that fit", 60 * MIB, outcome.budget.peakBytes)
    }

    /**
     * AC-3 "a single segment larger than the bound: never held": segments of 65 MiB (declared)
     * and encrypted segments of 33 MiB (two copies) are all streamed; nothing is ever charged.
     */
    @Test
    fun ac3_loop_segmentsLargerThanTheBound_areAllStreamed_neverHeld() {
        val plain = runLoop(LongArray(20) { 65 * MIB }, 6, HlsBufferPolicy.LIMIT_BYTES) { inFlight, _ -> inFlight.last() }
        assertComplete(plain, 20)
        assertEquals(20, plain.streamed)
        assertEquals(0L, plain.budget.peakBytes)

        val encrypted = runLoop(LongArray(20) { 33 * MIB }, 6, HlsBufferPolicy.LIMIT_BYTES, copies = 2) { inFlight, _ -> inFlight.last() }
        assertComplete(encrypted, 20)
        assertEquals(20, encrypted.streamed)
        assertEquals(0L, encrypted.budget.peakBytes)
    }

    /**
     * AC-3 and AC-5, "whatever the thread count, segment size or a stalled segment": random
     * playlists, random sizes from a few bytes to above the bound, random completion order,
     * plain and encrypted, fresh and resumed, every thread count. The loop always ends, every
     * segment is written once and in order, the window and the bound hold.
     */
    @Test
    fun ac3_loop_randomOrdersSizesAndThreadCounts_alwaysCompletes_withinWindowAndBound() {
        val random = Random(20261115)
        repeat(1500) { run ->
            val total = 1 + random.nextInt(80)
            val parallelism = 1 + random.nextInt(6)
            val capacity = if (random.nextInt(4) == 0) 1 + random.nextInt(4096).toLong() else HlsBufferPolicy.LIMIT_BYTES
            val largest = if (capacity == HlsBufferPolicy.LIMIT_BYTES) 70 * MIB else 2 * capacity
            val sizes = LongArray(total) {
                when (random.nextInt(4)) {
                    0 -> 0L // an empty segment
                    1 -> 1 + (random.nextDouble() * largest).toLong()
                    else -> 1 + (random.nextDouble() * largest / 16).toLong()
                }
            }
            val start = if (random.nextInt(3) == 0) random.nextInt(total) else 0
            val copies = 1 + random.nextInt(2)
            val stall = random.nextInt(3) == 0
            val order: (List<Int>, Int) -> Int = { inFlight, head ->
                if (stall) headStalls(inFlight, head) else inFlight[random.nextInt(inFlight.size)]
            }

            val outcome = runLoop(sizes, parallelism, capacity, startSegmentIndex = start, copies = copies, pickCompleted = order)

            val window = HlsBufferPolicy.window(parallelism.coerceIn(1, total - start))
            assertComplete(outcome, total, start)
            assertTrue("run $run: outstanding ${outcome.mostOutstanding} <= window $window", outcome.mostOutstanding <= window)
            assertTrue("run $run: waiting ${outcome.mostWaiting} <= window - 1", outcome.mostWaiting <= window - 1)
        }
    }

    /**
     * AC-5: in ordinary use (plan: "6 workers x 2-4 MiB segments") nothing is streamed and the
     * workers stay busy: the gate does not slow a download that arrives in any order.
     */
    @Test
    fun ac5_loop_ordinarySegments_nothingIsStreamed() {
        val random = Random(7)
        for (parallelism in 1..6) {
            val sizes = LongArray(300) { 2 * MIB + random.nextInt((2 * MIB).toInt()) }
            val outcome = runLoop(sizes, parallelism, HlsBufferPolicy.LIMIT_BYTES) { inFlight, _ -> inFlight[random.nextInt(inFlight.size)] }

            assertComplete(outcome, sizes.size)
            assertEquals("parallelism $parallelism", 0, outcome.streamed)
        }
    }

    /**
     * AC-5: when segments arrive in playlist order the gate never closes: each completion is
     * written at once, and as many segments are in flight as upstream has today.
     */
    @Test
    fun ac5_loop_inOrder_theGateNeverLimits() {
        for (parallelism in 1..6) {
            val outcome = runLoop(LongArray(100) { MIB }, parallelism, HlsBufferPolicy.LIMIT_BYTES) { inFlight, head ->
                assertTrue("the head is in flight", head in inFlight)
                head
            }

            assertComplete(outcome, 100)
            assertEquals("nothing ever waits", 0, outcome.mostWaiting)
            assertEquals("parallelism $parallelism: as many in flight as there are workers", parallelism, outcome.mostOutstanding)
        }
    }
}
