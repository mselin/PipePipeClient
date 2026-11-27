package org.schabi.newpipe.enhancements.playermemory

/**
 * Decides when a seekbar preview sheet may be downloaded.
 *
 * Nothing is downloaded for an item before its seekbar was touched in a player that can show a
 * preview ([arm]). After that one sheet is downloaded at a time; while one is in flight only the
 * page wanted last is remembered and started next. A new item ([onNewItem]) starts a new
 * generation: results of an older generation are refused and start nothing.
 *
 * Not thread safe: use it from one thread.
 */
class PreviewLoadState {
    /** Identifies the current item; a download carries the generation it was started in. */
    var generation: Int = 0
        private set

    /** Whether the seekbar of the current item was touched where a preview can be shown. */
    var armed: Boolean = false
        private set

    private var inFlight: Int? = null
    private var pending: Int? = null

    /** The player moved to another item: nothing is armed, in flight or pending any more. */
    fun onNewItem(): Int {
        generation += 1
        armed = false
        inFlight = null
        pending = null
        return generation
    }

    /** Arms the current item if [canShow]; once armed it stays armed. Returns [armed]. */
    fun arm(canShow: Boolean): Boolean {
        armed = armed || canShow
        return armed
    }

    /**
     * The sheet [page] is wanted. Returns the page to download now, or null: when not armed,
     * when the page is [cached] or already in flight (both leave a pending page as it is), or
     * when another page is in flight, in which case [page] replaces the pending page.
     */
    fun request(page: Int, cached: Boolean): Int? {
        if (!armed || cached || page == inFlight) {
            return null
        }
        if (inFlight == null) {
            inFlight = page
            return page
        }
        pending = page
        return null
    }

    /**
     * A download started in [generation] ended, with or without a sheet. For the current
     * generation the pending page, if any, is in flight from now on and returned as
     * [Result.next]; for an older one nothing changes.
     */
    @Suppress("UNUSED_PARAMETER") // the page is part of the planned signature
    fun onLoaded(generation: Int, page: Int): Result {
        if (generation != this.generation) {
            return Result(false, null)
        }
        inFlight = pending
        pending = null
        return Result(true, inFlight)
    }

    /** [accept]: the sheet belongs to the current item. [next]: the page to download now. */
    data class Result(val accept: Boolean, val next: Int?)
}
