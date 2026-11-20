package org.schabi.newpipe.enhancements.downloadfixes

import java.io.OutputStream

/**
 * Receives one segment into memory and charges every array to [charge] before allocating it:
 * first one array of `initialCapacity`, then chunks of 256 KiB. A request that does not fit
 * the budget throws [SegmentOverBudgetException] (the charge is then empty).
 */
class SegmentBuffer(private val charge: SegmentCharge, initialCapacity: Int) : OutputStream() {
    private val first: ByteArray
    private val chunks = ArrayList<ByteArray>()
    private var current: ByteArray
    private var room: Int
    private var count = 0L

    init {
        require(initialCapacity >= 0) { "initial capacity must not be negative" }
        charge.acquire(initialCapacity.toLong())
        first = ByteArray(initialCapacity)
        current = first
        room = initialCapacity
    }

    override fun write(b: Int) {
        if (room == 0) grow()
        current[current.size - room] = b.toByte()
        room--
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        var from = off
        var left = len
        while (left > 0) {
            if (room == 0) grow()
            val n = minOf(left, room)
            System.arraycopy(b, from, current, current.size - room, n)
            room -= n
            from += n
            left -= n
            count += n
        }
    }

    private fun grow() {
        charge.acquire(CHUNK_BYTES.toLong())
        current = ByteArray(CHUNK_BYTES)
        chunks.add(current)
        room = CHUNK_BYTES
    }

    fun size(): Long = count

    /**
     * The received bytes as one array. The first array itself when it is exactly full (no
     * copy, no further charge). Otherwise the copy is charged before it is made, and
     * afterwards only the copy stays charged.
     */
    fun toByteArray(): ByteArray {
        if (count == first.size.toLong()) return first
        if (count > MAX_ARRAY_BYTES) {
            charge.release()
            throw SegmentOverBudgetException(count, MAX_ARRAY_BYTES.toLong())
        }
        charge.acquire(count)
        val result = ByteArray(count.toInt())
        var position = 0
        var index = -1
        while (position < result.size) {
            val part = if (index < 0) first else chunks[index]
            val n = minOf(part.size, result.size - position)
            System.arraycopy(part, 0, result, position, n)
            position += n
            index++
        }
        chunks.clear()
        current = result
        room = 0
        charge.settle(count)
        return result
    }

    private companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8
    }
}
