package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * AC-3 (M7), plan section 3: `SegmentMemoryBudget` and `SegmentCharge`, the count of heap held
 * for segment data by one HLS transfer.
 *
 * The budget is shared by the download threads of a transfer (up to 6) and the writer. What
 * the AC stands on:
 * - what is held never exceeds the capacity, whatever the threads do ([SegmentMemoryBudget.peakBytes]);
 * - a charge that does not fit gives back everything it holds, and never waits;
 * - nothing is counted twice or lost: when every charge is released, nothing is held.
 */
class SegmentMemoryBudgetTest {

    private fun expectOverBudget(what: String, block: () -> Unit): SegmentOverBudgetException {
        try {
            block()
        } catch (e: SegmentOverBudgetException) {
            return e
        }
        fail("$what: expected a SegmentOverBudgetException")
        throw AssertionError()
    }

    // ---- one thread ----

    /** AC-3: a new budget holds nothing; a new charge holds nothing. */
    @Test
    fun ac3_newBudgetAndNewChargeHoldNothing() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open()

        assertEquals(1000L, budget.capacityBytes)
        assertEquals(0L, budget.heldBytes)
        assertEquals(0L, budget.peakBytes)
        assertEquals(0L, charge.bytes)
    }

    /** AC-3: what is acquired is counted in the charge and in the budget. */
    @Test
    fun ac3_acquire_isCountedInChargeAndBudget() {
        val budget = SegmentMemoryBudget(1000)
        val first = budget.open()
        val second = budget.open()

        first.acquire(300)
        first.acquire(200)
        second.acquire(100)

        assertEquals(500L, first.bytes)
        assertEquals(100L, second.bytes)
        assertEquals(600L, budget.heldBytes)
        assertEquals(600L, budget.peakBytes)
    }

    /** AC-3: the capacity itself fits ("at most"); one byte more does not. */
    @Test
    fun ac3_acquire_exactlyTheCapacityFits_oneMoreDoesNot() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open()
        charge.acquire(1000)
        assertEquals(1000L, budget.heldBytes)

        val other = budget.open()
        expectOverBudget("1 byte into a full budget") { other.acquire(1) }
        assertEquals(1000L, budget.heldBytes)
        assertEquals(1000L, budget.peakBytes)
    }

    /**
     * AC-3, plan: "If it does not fit: releases all this charge holds, then throws." The memory
     * of the refused segment is free for the others at once.
     */
    @Test
    fun ac3_acquire_refusal_releasesTheWholeCharge() {
        val budget = SegmentMemoryBudget(1000)
        val waiting = budget.open().apply { acquire(400) }
        val growing = budget.open().apply { acquire(500) }

        expectOverBudget("500 + 400 + 200 > 1000") { growing.acquire(200) }

        assertEquals("the refused charge holds nothing", 0L, growing.bytes)
        assertEquals("only the other charge is left", 400L, budget.heldBytes)
        assertEquals("the other charge is untouched", 400L, waiting.bytes)
        assertEquals("the peak is what was really held, never the refused sum", 900L, budget.peakBytes)
    }

    /** AC-3: a single request larger than the whole capacity is refused, also into an empty budget. */
    @Test
    fun ac3_acquire_moreThanTheCapacity_isRefused() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open()

        expectOverBudget("1001 of 1000") { charge.acquire(1001) }
        expectOverBudget("Long.MAX_VALUE of 1000") { charge.acquire(Long.MAX_VALUE) }

        assertEquals(0L, budget.heldBytes)
        assertEquals(0L, budget.peakBytes)
    }

    /**
     * AC-3: the sum does not overflow. A charge that holds something and asks for close to
     * `Long.MAX_VALUE` more is refused, it does not wrap around into "fits".
     */
    @Test
    fun ac3_acquire_doesNotOverflow() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open().apply { acquire(600) }

        expectOverBudget("600 + Long.MAX_VALUE") { charge.acquire(Long.MAX_VALUE) }
        expectOverBudget("Long.MAX_VALUE - 100") { budget.open().apply { acquire(10) }.acquire(Long.MAX_VALUE - 100) }

        assertEquals(0L, budget.heldBytes)
        assertTrue(budget.peakBytes <= 1000)
    }

    /**
     * AC-3, plan 2.: the refusal is an `IOException` of its own type, so that the engine can
     * tell it from a failed transfer and does not retry it.
     */
    @Test
    fun ac3_refusal_isAnIOExceptionOfItsOwnType() {
        val refusal: Any = expectOverBudget("2 of 1") { SegmentMemoryBudget(1).open().acquire(2) }
        assertTrue(refusal is IOException)
        val constructed: Any = SegmentOverBudgetException(2, 1) // the plan's constructor: (needed, capacity)
        assertTrue(constructed is IOException)
    }

    /** AC-3, plan 2.: "each attempt starts with `charge.release()`": a charge is used again after a refusal and after a release. */
    @Test
    fun ac3_charge_isUsableAgainAfterRefusalAndRelease() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open()

        charge.acquire(800)
        expectOverBudget("800 + 300") { charge.acquire(300) }
        charge.release() // the next attempt begins
        charge.acquire(700)
        assertEquals(700L, charge.bytes)
        assertEquals(700L, budget.heldBytes)

        charge.release()
        charge.acquire(1000)
        assertEquals(1000L, budget.heldBytes)
    }

    /** AC-3: `release()` gives everything back. */
    @Test
    fun ac3_release_givesEverythingBack() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open().apply { acquire(300); acquire(300) }
        val other = budget.open().apply { acquire(50) }

        charge.release()

        assertEquals(0L, charge.bytes)
        assertEquals(50L, budget.heldBytes)
        assertEquals(50L, other.bytes)
        assertEquals("the peak stays", 650L, budget.peakBytes)
    }

    /**
     * AC-3, plan: "`release()` idempotent". The retry lambda, the writer and a failure path can
     * all release the same charge; a second release must not give back bytes a second time, or
     * the budget would admit more than its capacity from then on.
     */
    @Test
    fun ac3_release_isIdempotent() {
        val budget = SegmentMemoryBudget(1000)
        val other = budget.open().apply { acquire(600) }
        val charge = budget.open().apply { acquire(300) }

        charge.release()
        charge.release()
        charge.release()

        assertEquals(600L, budget.heldBytes)
        assertEquals(600L, other.bytes)
        // If the second release had been counted, 600 would now look like 0 and this would fit:
        expectOverBudget("600 + 401") { budget.open().acquire(401) }
        budget.open().acquire(400)
        assertEquals(1000L, budget.heldBytes)
    }

    /** AC-3: a release of a charge that never held anything, and one after a refusal, change nothing. */
    @Test
    fun ac3_release_ofAnEmptyOrRefusedCharge_changesNothing() {
        val budget = SegmentMemoryBudget(1000)
        val other = budget.open().apply { acquire(900) }
        budget.open().release()
        val refused = budget.open().apply { acquire(50) }
        expectOverBudget("900 + 50 + 100") { refused.acquire(100) }

        refused.release()
        refused.release()

        assertEquals(900L, budget.heldBytes)
        assertEquals(900L, other.bytes)
    }

    /** AC-3, plan: "`settle(bytes)`: lower the charge to bytes". After decrypting, only the plain text stays counted. */
    @Test
    fun ac3_settle_lowersTheCharge() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open().apply { acquire(400); acquire(400) } // cipher text and its decrypted copy

        charge.settle(390)

        assertEquals(390L, charge.bytes)
        assertEquals(390L, budget.heldBytes)
        assertEquals(800L, budget.peakBytes)
        budget.open().acquire(610) // what was given back is free for others
        assertEquals(1000L, budget.heldBytes)
    }

    /** AC-3, plan: settle "never raises it". Memory enters the count only through `acquire`, which checks the capacity. */
    @Test
    fun ac3_settle_neverRaisesTheCharge() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open().apply { acquire(300) }

        charge.settle(300)
        assertEquals(300L, charge.bytes)
        charge.settle(900)
        assertEquals(300L, charge.bytes)
        charge.settle(Long.MAX_VALUE)

        assertEquals(300L, charge.bytes)
        assertEquals(300L, budget.heldBytes)
        assertEquals(300L, budget.peakBytes)
    }

    /** AC-3: settling to 0 is a release; settling an empty charge changes nothing. */
    @Test
    fun ac3_settle_toZero_andOnAnEmptyCharge() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open().apply { acquire(300) }
        charge.settle(0)
        assertEquals(0L, charge.bytes)
        assertEquals(0L, budget.heldBytes)

        charge.settle(100)
        assertEquals(0L, charge.bytes)
        assertEquals(0L, budget.heldBytes)
        charge.release()
        assertEquals(0L, budget.heldBytes)
    }

    /**
     * AC-3, plan: `checkDeclared` "throws at once if a declared size (> 0) can never fit the
     * capacity": 1 copy for a plain segment, 2 for an encrypted one (cipher text + plain text).
     */
    @Test
    fun ac3_checkDeclared_refusesWhatCanNeverFit() {
        val budget = SegmentMemoryBudget(1000)
        val charge = budget.open()

        charge.checkDeclared(1000, 1)
        charge.checkDeclared(500, 2)
        charge.checkDeclared(1, 2)
        expectOverBudget("1001 x 1") { charge.checkDeclared(1001, 1) }
        expectOverBudget("501 x 2") { charge.checkDeclared(501, 2) }
        expectOverBudget("1000 x 2") { charge.checkDeclared(1000, 2) }
    }

    /** AC-3: the limit of the plan: an encrypted segment declared above 32 MiB, a plain one above 64 MiB. */
    @Test
    fun ac3_checkDeclared_withTheRealLimit() {
        val charge = SegmentMemoryBudget(HlsBufferPolicy.LIMIT_BYTES).open()

        charge.checkDeclared(64 * MIB, 1)
        charge.checkDeclared(32 * MIB, 2)
        expectOverBudget("64 MiB + 1") { charge.checkDeclared(64 * MIB + 1, 1) }
        expectOverBudget("32 MiB + 1, encrypted") { charge.checkDeclared(32 * MIB + 1, 2) }
    }

    /** AC-3: a declared size of several exabytes times 2 must not overflow into "fits". */
    @Test
    fun ac3_checkDeclared_doesNotOverflow() {
        val charge = SegmentMemoryBudget(HlsBufferPolicy.LIMIT_BYTES).open()

        expectOverBudget("Long.MAX_VALUE x 2") { charge.checkDeclared(Long.MAX_VALUE, 2) }
        expectOverBudget("Long.MAX_VALUE x 1") { charge.checkDeclared(Long.MAX_VALUE, 1) }
        expectOverBudget("2^62 + 1, x 2") { charge.checkDeclared((1L shl 62) + 1, 2) }
        expectOverBudget("2^63 / 2 + 32 MiB, x 2") { charge.checkDeclared((1L shl 62) + 16 * MIB, 2) }
    }

    /**
     * AC-3/AC-5, plan: "a declared size (> 0)". Unknown (`expectedBytes` gives -1) and 0 are not
     * declared sizes: the segment is downloaded and counted as it arrives.
     */
    @Test
    fun ac5_checkDeclared_unknownSizeIsNotRefused() {
        val charge = SegmentMemoryBudget(1000).open()

        charge.checkDeclared(-1, 1)
        charge.checkDeclared(-1, 2)
        charge.checkDeclared(0, 2)
    }

    /**
     * AC-3: `checkDeclared` asks whether the size can *ever* fit the capacity, not whether it
     * fits now, and it reserves nothing. A segment that only has to wait for others is not
     * turned into an unbuffered one before a byte is read.
     */
    @Test
    fun ac3_checkDeclared_comparesWithTheCapacity_andReservesNothing() {
        val budget = SegmentMemoryBudget(1000)
        val busy = budget.open().apply { acquire(900) }
        val charge = budget.open().apply { acquire(50) }

        charge.checkDeclared(800, 1)

        assertEquals(50L, charge.bytes)
        assertEquals(950L, budget.heldBytes)
        assertEquals(900L, busy.bytes)
    }

    /** AC-3: a budget of capacity 0 buffers nothing and still works: everything is refused, nothing is held. */
    @Test
    fun ac3_capacityZero_refusesEverything() {
        val budget = SegmentMemoryBudget(0)
        val charge = budget.open()

        expectOverBudget("1 of 0") { charge.acquire(1) }
        expectOverBudget("declared 1 of 0") { charge.checkDeclared(1, 1) }

        assertEquals(0L, budget.heldBytes)
        assertEquals(0L, budget.peakBytes)
    }

    // ---- several threads ----

    /**
     * AC-3: six threads acquire fixed amounts at the same moment, again and again; at every
     * meeting point the budget holds exactly the sum. A lost update (an unsynchronized counter)
     * shows as a wrong sum, or as bytes left over at the end.
     */
    @Test
    fun ac3_threads_theSumIsExactAtEveryMeetingPoint() {
        val threads = 6
        val rounds = 300
        val budget = SegmentMemoryBudget(HlsBufferPolicy.LIMIT_BYTES)
        val amounts = LongArray(threads) { (it + 1) * 1000L }
        val failure = AtomicReference<Throwable>()
        val holding = AtomicInteger(0) // 1 while the charges are held, 0 while they are released
        val barrier = CyclicBarrier(threads) {
            val expected = if (holding.get() == 1) amounts.sum() else 0L
            if (budget.heldBytes != expected) failure.compareAndSet(null, AssertionError("held ${budget.heldBytes}, expected $expected"))
            holding.set(1 - holding.get())
        }
        val done = CountDownLatch(threads)

        repeat(threads) { index ->
            Thread {
                try {
                    val charge = budget.open()
                    repeat(rounds) {
                        barrier.await(PATIENCE_SECONDS, TimeUnit.SECONDS) // everybody holds nothing
                        // in small steps, so that the threads really interleave
                        repeat(100) { charge.acquire(amounts[index] / 100) }
                        barrier.await(PATIENCE_SECONDS, TimeUnit.SECONDS) // everybody holds its amount
                        if (it % 2 == 0) charge.release() else repeat(100) { step -> charge.settle(amounts[index] - (step + 1) * amounts[index] / 100) }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                    barrier.reset()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        done.awaitOrFail("the six threads")

        failure.get()?.let { throw AssertionError("a thread failed", it) }
        assertEquals("everything was released", 0L, budget.heldBytes)
        assertEquals("the peak is the sum of all six", amounts.sum(), budget.peakBytes)
    }

    /**
     * AC-3: the capacity is not exceeded when several threads ask at the same moment for more
     * than half of it. Exactly one of them gets it in every round; two winners would hold more
     * than the capacity.
     */
    @Test
    fun ac3_threads_onlyOneOfSixGetsMoreThanHalfTheCapacity() {
        val threads = 6
        val rounds = 500
        val capacity = 1000L
        val budget = SegmentMemoryBudget(capacity)
        val failure = AtomicReference<Throwable>()
        val winners = AtomicInteger()
        val wrongRounds = AtomicInteger()
        val charges = List(threads) { budget.open() }
        // phase 0: all acquire, then count the winners; phase 1: all release.
        val phase = AtomicInteger(0)
        val barrier = CyclicBarrier(threads) {
            if (phase.get() == 1) {
                if (winners.getAndSet(0) != 1 || budget.heldBytes != 501L) wrongRounds.incrementAndGet()
            } else if (budget.heldBytes != 0L) {
                wrongRounds.incrementAndGet()
            }
            phase.set(1 - phase.get())
        }
        val done = CountDownLatch(threads)

        repeat(threads) { index ->
            Thread {
                try {
                    repeat(rounds) {
                        barrier.await(PATIENCE_SECONDS, TimeUnit.SECONDS)
                        try {
                            charges[index].acquire(501)
                            winners.incrementAndGet()
                        } catch (e: SegmentOverBudgetException) {
                            if (charges[index].bytes != 0L) throw AssertionError("a refused charge holds ${charges[index].bytes}")
                        }
                        barrier.await(PATIENCE_SECONDS, TimeUnit.SECONDS)
                        charges[index].release()
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                    barrier.reset()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        done.awaitOrFail("the six threads")

        failure.get()?.let { throw AssertionError("a thread failed", it) }
        assertEquals("rounds in which not exactly one thread held 501 bytes", 0, wrongRounds.get())
        assertEquals(501L, budget.peakBytes)
        assertEquals(0L, budget.heldBytes)
    }

    /**
     * AC-3, plan section 5: "`peakBytes` never above capacity with 6 threads writing random
     * sizes". Each thread plays a worker: acquire while a segment arrives, sometimes a second
     * copy (decrypt), settle, hold for a while, release; refusals are part of the game. Each
     * thread keeps its own books, and the books must agree with the budget at the end.
     */
    @Test
    fun ac3_threads_randomSizes_peakNeverAboveCapacity_andTheBooksAgree() {
        val threads = 6
        val capacity = 64 * 1024L
        val budget = SegmentMemoryBudget(capacity)
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val stillHeld = AtomicLong()
        val refusals = AtomicInteger()
        val acquired = AtomicInteger()

        repeat(threads) { index ->
            Thread {
                try {
                    val random = Random(1000L + index)
                    start.await()
                    val held = ArrayList<SegmentCharge>() // finished segments waiting for the writer
                    repeat(4000) {
                        val charge = budget.open()
                        var mine = 0L
                        try {
                            repeat(1 + random.nextInt(8)) {
                                val step = 1L + random.nextInt(4096)
                                charge.acquire(step)
                                mine += step
                                acquired.incrementAndGet()
                                if (charge.bytes != mine) throw AssertionError("charge says ${charge.bytes}, the thread acquired $mine")
                            }
                            if (random.nextBoolean()) { // decrypt: a second copy, then only the plain text stays
                                charge.acquire(mine)
                                charge.settle(mine - random.nextInt(16).coerceAtMost(mine.toInt()))
                            }
                            held.add(charge)
                        } catch (e: SegmentOverBudgetException) {
                            refusals.incrementAndGet()
                            if (charge.bytes != 0L) throw AssertionError("a refused charge holds ${charge.bytes}")
                        }
                        val now = budget.heldBytes
                        if (now < 0 || now > capacity) throw AssertionError("held $now of $capacity")
                        // the writer takes the oldest ones
                        while (held.size > random.nextInt(4)) {
                            val oldest = held.removeAt(0)
                            oldest.release()
                            if (random.nextInt(8) == 0) oldest.release() // a second release from another path
                        }
                    }
                    stillHeld.addAndGet(held.sumOf { it.bytes })
                    if (index % 2 == 0) {
                        stillHeld.addAndGet(-held.sumOf { it.bytes })
                        held.forEach { it.release() }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.awaitOrFail("the six threads")

        failure.get()?.let { throw AssertionError("a thread failed", it) }
        assertTrue("AC-3: peak ${budget.peakBytes} must not be above the capacity $capacity", budget.peakBytes <= capacity)
        assertTrue("the test did reach the limit (refusals: ${refusals.get()})", refusals.get() > 0)
        assertTrue("and most of the time it did not (acquired: ${acquired.get()})", acquired.get() > refusals.get())
        assertEquals("what the budget holds is what the threads still hold", stillHeld.get(), budget.heldBytes)
    }
}
