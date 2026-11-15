package org.schabi.newpipe.enhancements.downloadfixes

import java.io.IOException

/** A segment does not fit the budget. Not a failed transfer: the segment is written without buffering. */
class SegmentOverBudgetException(needed: Long, capacity: Long) :
    IOException("segment data of $needed bytes does not fit the buffer budget of $capacity bytes")

/**
 * Counts the heap one transfer holds for segment data. Nothing ever waits on it: a request
 * that does not fit is refused at once, so no thread can block another through the budget.
 * One lock (this object) guards the budget and all of its charges.
 */
class SegmentMemoryBudget(val capacityBytes: Long) {
    private var held = 0L
    private var peak = 0L

    init {
        require(capacityBytes >= 0) { "capacity must not be negative" }
    }

    val heldBytes: Long
        get() = synchronized(this) { held }

    /** Highest [heldBytes] so far. */
    val peakBytes: Long
        get() = synchronized(this) { peak }

    fun open(): SegmentCharge = SegmentCharge(this)

    /** Caller holds the lock. `held <= capacityBytes` always, so the subtraction cannot overflow. */
    internal fun take(bytes: Long): Boolean {
        if (bytes > capacityBytes - held) return false
        held += bytes
        if (held > peak) peak = held
        return true
    }

    /** Caller holds the lock. */
    internal fun give(bytes: Long) {
        held -= bytes
    }
}

/** What one segment holds of a [SegmentMemoryBudget]. */
class SegmentCharge internal constructor(private val budget: SegmentMemoryBudget) {
    private var own = 0L

    val bytes: Long
        get() = synchronized(budget) { own }

    /** Never blocks. If it does not fit: releases all this charge holds, then throws. */
    @Throws(SegmentOverBudgetException::class)
    fun acquire(bytes: Long) {
        require(bytes >= 0) { "cannot acquire a negative amount" }
        synchronized(budget) {
            if (budget.take(bytes)) {
                own += bytes
                return
            }
            budget.give(own)
            own = 0
        }
        throw SegmentOverBudgetException(bytes, budget.capacityBytes)
    }

    /**
     * Throws at once if a declared size (> 0) can never fit the capacity, [copies] times.
     * Compares with the capacity, not with what is free now, and reserves nothing.
     */
    @Throws(SegmentOverBudgetException::class)
    fun checkDeclared(declaredBytes: Long, copies: Int) {
        if (declaredBytes <= 0) return
        if (declaredBytes > budget.capacityBytes / copies.coerceAtLeast(1)) {
            throw SegmentOverBudgetException(declaredBytes, budget.capacityBytes)
        }
    }

    /** Lowers the charge to [bytes]; never raises it. */
    fun settle(bytes: Long) {
        val target = bytes.coerceAtLeast(0)
        synchronized(budget) {
            if (target >= own) return
            budget.give(own - target)
            own = target
        }
    }

    /** Gives everything back. Idempotent. */
    fun release() {
        synchronized(budget) {
            budget.give(own)
            own = 0
        }
    }
}
