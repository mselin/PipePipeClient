package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AC-3 (M7), plan section 3: `SegmentBuffer`, which takes the place of the
 * `ByteArrayOutputStream` a segment is received into.
 *
 * Plan: "Allocates initialCapacity, then 256 KiB chunks, charging each allocation before making
 * it. toByteArray(): returns the first array itself when it is exactly full, else charges and
 * builds one array and frees the chunks."
 *
 * Two things are tested throughout: the content that comes out is the content that went in
 * (AC-5: a segment in the file is what the server sent), and the charge is never smaller than
 * the heap the buffer holds (AC-3: the bound counts "heap actually allocated").
 */
class SegmentBufferTest {

    private fun budget(capacity: Long = HlsBufferPolicy.LIMIT_BYTES) = SegmentMemoryBudget(capacity)

    /** Writes [content] the way `copyPartToMemory` does: through a copy buffer of [step] bytes. */
    private fun copy(content: ByteArray, into: OutputStream, step: Int = CHUNK) {
        val input = ByteArrayInputStream(content)
        val buffer = ByteArray(step)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return
            into.write(buffer, 0, read)
        }
    }

    private fun expectOverBudget(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SegmentOverBudgetException) {
            return
        }
        fail("$what: expected a SegmentOverBudgetException")
    }

    // ---- content ----

    /** AC-5: the bytes that come out are the bytes that went in, for sizes around every boundary. */
    @Test
    fun ac5_contentEqualsInput_aroundEveryBoundary() {
        val initial = 1000
        val sizes = listOf(
            0, 1, initial - 1, initial, initial + 1,
            initial + CHUNK - 1, initial + CHUNK, initial + CHUNK + 1,
            initial + 2 * CHUNK, initial + 3 * CHUNK + 12345
        )
        for (size in sizes) {
            val content = bytesOf(size, seed = size.toLong())
            val budget = budget()
            val charge = budget.open()
            val buffer = SegmentBuffer(charge, initial)

            copy(content, buffer, step = 777)

            assertEquals("size() after $size bytes", size.toLong(), buffer.size())
            val result = buffer.toByteArray()
            assertEquals("length of the result for $size bytes", size, result.size)
            assertArrayEquals("content of $size bytes", content, result)
            assertTrue("peak ${budget.peakBytes} within the capacity", budget.peakBytes <= budget.capacityBytes)
        }
    }

    /** AC-5: the same with the writes the engine makes: its copy buffer is 256 KiB, the initial capacity is at least that. */
    @Test
    fun ac5_contentEqualsInput_withTheEnginesBufferSizes() {
        for (size in listOf(CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK, 5 * CHUNK + 4321, 4 * 1024 * 1024)) {
            for (initial in listOf(CHUNK, size.coerceAtLeast(CHUNK))) {
                val content = bytesOf(size, seed = 7L * size + initial)
                val buffer = SegmentBuffer(budget().open(), initial)

                copy(content, buffer, step = CHUNK)

                assertArrayEquals("content of $size bytes, initial capacity $initial", content, buffer.toByteArray())
            }
        }
    }

    /** AC-5: `write(int)`, `write(b, off, len)` with an offset, empty writes and `write(b)` mixed in random sizes. */
    @Test
    fun ac5_contentEqualsInput_mixedWrites() {
        val random = Random(42)
        val content = bytesOf(3 * CHUNK + 999, seed = 43)
        val buffer = SegmentBuffer(budget().open(), 4096)
        var position = 0
        while (position < content.size) {
            when (random.nextInt(4)) {
                0 -> {
                    buffer.write(content[position].toInt()) // also negative bytes: only the low 8 bits count
                    position++
                }
                1 -> buffer.write(content, position, 0)
                2 -> {
                    val length = minOf(1 + random.nextInt(70000), content.size - position)
                    buffer.write(content.copyOfRange(position, position + length))
                    position += length
                }
                else -> {
                    val length = minOf(1 + random.nextInt(600000), content.size - position)
                    // from the middle of a larger array, as a copy buffer that is not full
                    val padded = ByteArray(length + 20) { 0x55 }
                    System.arraycopy(content, position, padded, 10, length)
                    buffer.write(padded, 10, length)
                    position += length
                }
            }
        }

        assertEquals(content.size.toLong(), buffer.size())
        assertArrayEquals(content, buffer.toByteArray())
    }

    /** AC-5: `write(int)` takes the low eight bits, as every `OutputStream` does. */
    @Test
    fun ac5_writeInt_takesTheLowEightBits() {
        val buffer = SegmentBuffer(budget().open(), 4)
        buffer.write(0x141)
        buffer.write(-1)
        buffer.write(0)
        buffer.write(0x7f)
        buffer.write(0x80)

        assertArrayEquals(byteArrayOf(0x41, -1, 0, 0x7f, -128), buffer.toByteArray())
    }

    /** AC-5: the buffer keeps its own copy: the caller reuses its copy buffer for the next read. */
    @Test
    fun ac5_theCallersArrayIsCopied() {
        val buffer = SegmentBuffer(budget().open(), 8)
        val copyBuffer = byteArrayOf(1, 2, 3, 4)
        buffer.write(copyBuffer, 0, 4)
        copyBuffer.fill(9)
        buffer.write(copyBuffer, 0, 4)
        copyBuffer.fill(7)
        buffer.write(copyBuffer, 0, 2)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 9, 9, 9, 9, 7, 7), buffer.toByteArray())
    }

    /** AC-5: an empty segment is an empty array. */
    @Test
    fun ac5_nothingWritten_isAnEmptyArray() {
        val charge = budget().open()
        val buffer = SegmentBuffer(charge, CHUNK)

        assertEquals(0L, buffer.size())
        assertEquals(0, buffer.toByteArray().size)
    }

    // ---- the charge ----

    /** AC-3, plan: "charging each allocation before making it": the initial capacity is charged when the buffer is made. */
    @Test
    fun ac3_initialCapacityIsChargedAtConstruction() {
        val budget = budget()
        val charge = budget.open()

        SegmentBuffer(charge, 3 * CHUNK)

        assertEquals(3L * CHUNK, charge.bytes)
        assertEquals(3L * CHUNK, budget.heldBytes)
    }

    /** AC-3: an initial capacity that does not fit is refused before the array is allocated, and nothing stays charged. */
    @Test
    fun ac3_initialCapacityThatDoesNotFit_isRefused() {
        val budget = budget(capacity = 1000)
        val other = budget.open().apply { acquire(600) }
        val charge = budget.open()

        expectOverBudget("600 + 401 of 1000") { SegmentBuffer(charge, 401) }

        assertEquals(0L, charge.bytes)
        assertEquals(600L, budget.heldBytes)
        assertEquals(600L, other.bytes)
        assertEquals(600L, budget.peakBytes)
    }

    /** AC-3: within the initial capacity nothing more is charged. */
    @Test
    fun ac3_withinTheInitialCapacity_nothingMoreIsCharged() {
        val budget = budget()
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, 1000)

        copy(bytesOf(1000, seed = 1), buffer, step = 100)

        assertEquals(1000L, charge.bytes)
        assertEquals(1000L, budget.peakBytes)
    }

    /** AC-3, plan: "then 256 KiB chunks": one byte more than the initial capacity charges one chunk, and so on. */
    @Test
    fun ac3_growthIsChargedInChunksOf256KiB() {
        val budget = budget()
        val charge = budget.open()
        val initial = 1000
        val buffer = SegmentBuffer(charge, initial)
        buffer.write(ByteArray(initial), 0, initial)
        assertEquals(initial.toLong(), charge.bytes)

        buffer.write(1)
        assertEquals("the first chunk", initial + 1L * CHUNK, charge.bytes)

        buffer.write(ByteArray(CHUNK - 1), 0, CHUNK - 1)
        assertEquals("the first chunk is exactly full", initial + 1L * CHUNK, charge.bytes)

        buffer.write(2)
        assertEquals("the second chunk", initial + 2L * CHUNK, charge.bytes)

        buffer.write(ByteArray(3 * CHUNK), 0, 3 * CHUNK) // one write that spans several chunks
        assertEquals("one more than three further chunks hold", initial + 5L * CHUNK, charge.bytes)
        assertEquals(initial + 4L * CHUNK + 1, buffer.size())
        assertEquals(budget.heldBytes, charge.bytes)
    }

    /**
     * AC-3: at every moment while a segment is received, the charge is at least the bytes
     * received and at most one chunk more: it counts the allocation, and it does not overcount.
     */
    @Test
    fun ac3_whileReceiving_theChargeCoversWhatIsHeld() {
        val budget = budget()
        val charge = budget.open()
        val initial = CHUNK
        val buffer = SegmentBuffer(charge, initial)
        val random = Random(5)
        var written = 0L

        repeat(400) {
            val length = 1 + random.nextInt(40000)
            buffer.write(ByteArray(length), 0, length)
            written += length

            assertEquals(written, buffer.size())
            assertTrue("charge ${charge.bytes} covers $written bytes received", charge.bytes >= written)
            assertTrue("charge ${charge.bytes} is at most one chunk above ${maxOf(written, initial.toLong())}", charge.bytes < maxOf(written, initial.toLong()) + CHUNK)
        }
    }

    /**
     * AC-3, plan section 5: "exact-size buffer returns its array without a copy". A segment
     * with a declared length is received into an array of that length and handed on as it is:
     * no second array, no second charge.
     */
    @Test
    fun ac3_exactlyFullFirstArray_isReturnedWithoutACopy() {
        val content = bytesOf(3 * CHUNK + 17, seed = 8)
        val budget = budget()
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, content.size)
        copy(content, buffer)

        val first = buffer.toByteArray()
        val second = buffer.toByteArray()

        assertArrayEquals(content, first)
        assertSame("the array itself, not a copy", first, second)
        assertEquals("nothing was charged for a copy", content.size.toLong(), budget.peakBytes)
        assertEquals(content.size.toLong(), charge.bytes)
    }

    /**
     * AC-3: the exact size fits a budget that has room for it once and not twice. With a copy
     * this segment would be refused although it fits.
     */
    @Test
    fun ac3_exactlyFullFirstArray_fitsABudgetOfItsOwnSize() {
        val content = bytesOf(CHUNK, seed = 9)
        val budget = budget(capacity = CHUNK.toLong())
        val buffer = SegmentBuffer(budget.open(), CHUNK)
        copy(content, buffer)

        assertArrayEquals(content, buffer.toByteArray())
        assertEquals(CHUNK.toLong(), budget.peakBytes)
    }

    /**
     * AC-3, plan: "else charges and builds one array and frees the chunks". While the array is
     * built, chunks and array are both held and both counted; afterwards only the array is.
     */
    @Test
    fun ac3_toByteArray_ofChunks_chargesTheCopy_thenOnlyTheArrayStaysCounted() {
        val initial = 1000
        val content = bytesOf(initial + 2 * CHUNK + 5, seed = 10)
        val budget = budget()
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, initial)
        copy(content, buffer)
        val whileReceiving = charge.bytes
        assertEquals(initial + 3L * CHUNK, whileReceiving)

        val result = buffer.toByteArray()

        assertArrayEquals(content, result)
        assertEquals("chunks and the new array were counted together", whileReceiving + content.size, budget.peakBytes)
        assertEquals("afterwards the charge is the array that is handed on", content.size.toLong(), charge.bytes)
        assertEquals(content.size.toLong(), budget.heldBytes)
    }

    /** AC-3: a first array that is not full (a segment shorter than declared, or than 256 KiB) is copied to its size and charged as that. */
    @Test
    fun ac3_toByteArray_ofAPartlyFilledFirstArray_isChargedAsItsSize() {
        val content = bytesOf(1234, seed = 11)
        val budget = budget()
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, CHUNK)
        copy(content, buffer)

        val result = buffer.toByteArray()

        assertArrayEquals(content, result)
        assertEquals(1234, result.size)
        assertEquals(1234L, charge.bytes)
        assertEquals(CHUNK + 1234L, budget.peakBytes)
    }

    /**
     * AC-3: a segment that grows beyond the budget is refused in `write`, with the type the
     * engine catches; the whole charge is given back, so the other segments keep their room.
     */
    @Test
    fun ac3_growthBeyondTheBudget_isRefusedInWrite_andTheChargeIsGivenBack() {
        val budget = budget(capacity = 4L * CHUNK)
        val waiting = budget.open().apply { acquire(CHUNK.toLong()) }
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, CHUNK)
        copy(ByteArray(3 * CHUNK), buffer) // 1 initial + 2 chunks: the budget is full

        try {
            buffer.write(ByteArray(10), 0, 10)
            fail("expected a SegmentOverBudgetException")
        } catch (e: IOException) {
            assertTrue("the refusal is a SegmentOverBudgetException, was $e", e is SegmentOverBudgetException)
        }

        assertEquals("the refused segment holds nothing", 0L, charge.bytes)
        assertEquals("the waiting segment keeps what it has", CHUNK.toLong(), budget.heldBytes)
        assertEquals(CHUNK.toLong(), waiting.bytes)
        assertEquals(4L * CHUNK, budget.peakBytes)
    }

    /** AC-3: the copy in `toByteArray` is charged before it is made; when it does not fit, the segment is refused there. */
    @Test
    fun ac3_copyThatDoesNotFit_isRefusedInToByteArray() {
        val budget = budget(capacity = 4L * CHUNK)
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, CHUNK)
        copy(ByteArray(2 * CHUNK + 1), buffer) // holds 3 chunks, the copy would need 2 more

        expectOverBudget("3 chunks held + a copy of 2 chunks and a byte, of 4") { buffer.toByteArray() }

        assertEquals(0L, charge.bytes)
        assertEquals(0L, budget.heldBytes)
        assertTrue(budget.peakBytes <= budget.capacityBytes)
    }

    /**
     * AC-3, "a single segment larger than the bound: never held ... without [a declared length],
     * after at most 64 MiB": a body without end is refused when the real limit is reached, and
     * nothing of it stays held. (The stream below is endless; the test ends because of the bound.)
     */
    @Test
    fun ac3_endlessSegment_isRefusedAtTheRealLimit() {
        val budget = budget(HlsBufferPolicy.LIMIT_BYTES)
        val charge = budget.open()
        val buffer = SegmentBuffer(charge, CHUNK)
        val block = ByteArray(CHUNK)
        var refusedAfter = -1L

        for (i in 0 until 1024) { // 256 MiB at most
            try {
                buffer.write(block, 0, block.size)
            } catch (e: SegmentOverBudgetException) {
                refusedAfter = i.toLong() * CHUNK
                break
            }
        }

        assertEquals("refused when 64 MiB were held", HlsBufferPolicy.LIMIT_BYTES, refusedAfter)
        assertEquals(0L, charge.bytes)
        assertEquals(0L, budget.heldBytes)
        assertEquals(HlsBufferPolicy.LIMIT_BYTES, budget.peakBytes)
    }

    /** Plan 5.: the key is read into a budget of 64 KiB: a valid key of 16 bytes fits exactly and is not copied. */
    @Test
    fun ac3_key_sixteenBytesFitTheKeyBudget() {
        val key = bytesOf(16, seed = 12)
        val budget = SegmentMemoryBudget(HlsBufferPolicy.KEY_LIMIT_BYTES)
        val buffer = SegmentBuffer(budget.open(), 16) // AES_128_KEY_BYTES, as at :613
        copy(key, buffer)

        assertArrayEquals(key, buffer.toByteArray())
        assertEquals(16L, budget.peakBytes)
    }

    /** Plan 5.: a "key" without end is refused instead of read whole. */
    @Test
    fun ac3_key_anOversizedKeyIsRefused() {
        val budget = SegmentMemoryBudget(HlsBufferPolicy.KEY_LIMIT_BYTES)
        val buffer = SegmentBuffer(budget.open(), 16)

        expectOverBudget("a key of 1 MiB") { copy(ByteArray(1024 * 1024), buffer) }

        assertEquals(0L, budget.heldBytes)
        assertTrue(budget.peakBytes <= HlsBufferPolicy.KEY_LIMIT_BYTES)
    }

    // ---- several workers ----

    /**
     * AC-3: six workers receive segments of random sizes into buffers that share one budget,
     * as the engine's workers do. Whatever fits comes out as it went in; whatever does not fit is
     * refused and gives its memory back; the peak stays within the capacity; nothing is left.
     */
    @Test
    fun ac3_sixWorkers_sharedBudget_contentIsRight_peakWithinCapacity() {
        val workers = 6
        val capacity = 8L * CHUNK
        val budget = budget(capacity)
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val buffered = AtomicInteger()
        val refused = AtomicInteger()

        repeat(workers) { index ->
            Thread {
                try {
                    val random = Random(500L + index)
                    start.await()
                    repeat(60) { round ->
                        val size = random.nextInt(3 * CHUNK)
                        val content = bytesOf(size, seed = 31L * index + round)
                        val charge = budget.open()
                        try {
                            val declared = random.nextBoolean()
                            val buffer = SegmentBuffer(charge, if (declared) size.coerceAtLeast(CHUNK) else CHUNK)
                            copy(content, buffer, step = 1 + random.nextInt(CHUNK))
                            val bytes = buffer.toByteArray()
                            if (!content.contentEquals(bytes)) throw AssertionError("worker $index round $round: content differs")
                            charge.settle(bytes.size.toLong())
                            buffered.incrementAndGet()
                        } catch (e: SegmentOverBudgetException) {
                            refused.incrementAndGet()
                            if (charge.bytes != 0L) throw AssertionError("a refused segment holds ${charge.bytes}")
                        } finally {
                            charge.release() // the writer wrote it
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.awaitOrFail("the six workers")

        failure.get()?.let { throw AssertionError("a worker failed", it) }
        assertTrue("AC-3: peak ${budget.peakBytes} within the capacity $capacity", budget.peakBytes <= capacity)
        assertEquals("every segment was either buffered or refused", workers * 60, buffered.get() + refused.get())
        assertTrue("segments were buffered", buffered.get() > 0)
        assertEquals("nothing is held when all are written", 0L, budget.heldBytes)
    }

    /** `SegmentBuffer` is an `OutputStream` (plan: "Replaces ByteArrayOutputStream"; the two lines using `output` stay). */
    @Test
    fun segmentBufferIsAnOutputStream() {
        val buffer: Any = SegmentBuffer(budget().open(), 16)
        assertTrue(buffer is OutputStream)
        assertNotSame(buffer, SegmentBuffer(budget().open(), 16))
    }
}
