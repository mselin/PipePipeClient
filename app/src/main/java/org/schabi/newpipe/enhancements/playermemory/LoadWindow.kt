package org.schabi.newpipe.enhancements.playermemory

/**
 * Which queue items stay loaded while one of them is current: the current item, the one before
 * it and the two after it. Everything else is unloaded by [SourceUnloader].
 *
 * The window always contains what the player's own loader loads (the previous item, the current
 * one, the next one, and the first item when the current one is the last), so the source the
 * player buffers ahead for a gapless transition is never one that gets unloaded.
 */
object LoadWindow {
    const val KEEP_BEHIND = 1
    const val KEEP_AHEAD = 2

    /**
     * Whether the item at [index] stays loaded while [currentIndex] is current.
     *
     * Backwards the window ends at the start of the queue; forwards it wraps around the end, as
     * the loader does. An [index] outside `0 until queueSize` is never kept.
     */
    @JvmStatic
    fun keeps(index: Int, currentIndex: Int, queueSize: Int): Boolean {
        if (index < 0 || index >= queueSize) {
            return false
        }
        if (index == currentIndex) {
            return true
        }
        if (currentIndex - index in 1..KEEP_BEHIND) {
            return true
        }
        return Math.floorMod(index - currentIndex, queueSize) in 1..KEEP_AHEAD
    }
}
