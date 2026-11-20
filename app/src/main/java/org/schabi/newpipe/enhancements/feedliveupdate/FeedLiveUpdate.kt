package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.local.feed.service.FeedEventManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class EventKind { IDLE, PROGRESS, SUCCESS, ERROR }

/** What a feed page makes of one item of its stream. [NOTHING]: no state is posted. */
enum class Show { LOADED, END, ERROR, PROGRESS, NOTHING }

/** How the page looks. [PROGRESS_FULL] is the full-screen progress, [PROGRESS_LIST] the list with the progress above it. */
enum class Display { LOADED, PROGRESS_FULL, PROGRESS_LIST, ERROR, NOTHING }

/** [rebuild]: the page's list is queried again for this item. */
data class Plan(val show: Show, val rebuild: Boolean)

/**
 * The decision for one item of a feed page's stream.
 *
 * @param concerned the refresh the event belongs to concerns this page
 * @param pageChanged the page is new or back on screen, or its "show watched" switch changed
 * @param storedSinceBuilt a batch was stored, and let through by the pace, since the list was built
 */
fun plan(kind: EventKind, concerned: Boolean, pageChanged: Boolean, storedSinceBuilt: Boolean): Plan = when {
    // no refresh: every item reloads, as upstream does
    kind == EventKind.IDLE -> Plan(Show.LOADED, true)
    // somebody else's refresh: the page looks idle; it loads when it has a reason of its own
    !concerned -> if (pageChanged) Plan(Show.LOADED, true) else Plan(Show.NOTHING, false)
    // the end always shows the final state
    kind == EventKind.SUCCESS -> Plan(Show.END, true)
    kind == EventKind.ERROR -> Plan(Show.ERROR, false)
    else -> Plan(Show.PROGRESS, pageChanged || storedSinceBuilt)
}

fun display(show: Show, hasItems: Boolean): Display = when (show) {
    Show.PROGRESS -> if (hasItems) Display.PROGRESS_LIST else Display.PROGRESS_FULL
    Show.LOADED, Show.END -> Display.LOADED
    Show.ERROR -> Display.ERROR
    Show.NOTHING -> Display.NOTHING
}

/**
 * Keeps a feed page's list alive during a refresh. One per `FeedViewModel`, which calls it
 * through its marked lines, for each item of its stream in this order:
 *
 * 1. [wake] wraps the page's combined stream (inside the on-screen gate): a stored batch,
 *    paced, delivers the latest item again;
 * 2. [next] decides (background thread); `false` means nothing is posted for this item;
 * 3. [rebuild] says whether the list query runs;
 * 4. [shownAs] and [rows] give the event and the rows the state is made from;
 * 5. [items] (main thread) gives a progress state its list, or `null` for the full-screen progress.
 *
 * [next], [rebuild], [shownAs] and [rows] must be called one item after the other on one
 * thread at a time, as `filter` and `map` behind one `observeOn` do.
 *
 * @param R a row of the list query
 * @param I a list item made from a row
 */
class FeedLiveUpdate<R : Any, I : Any>(
    private val pageGroupId: Long,
    private val log: FeedRefreshLog = FeedRefreshLog.app,
    private val paceMillis: Long = PACE_MILLIS,
    private val paceScheduler: Scheduler = Schedulers.computation()
) {
    /** Set by a new subscription to [wake]: first load, or back on screen. Taken by the next decision. */
    private val fresh = AtomicBoolean(true)

    /** The last batch count the pace let through. */
    private val paced = AtomicLong()

    /** [FeedRefreshLog.storedCount] read before the query of the last rebuild: the list holds at least these batches. */
    @Volatile
    private var builtAt = 0L
    private var builtShowPlayed: Boolean? = null

    @Volatile
    private var show = Show.NOTHING

    @Volatile
    private var kept: List<R> = emptyList()

    // main thread only
    private var itemsOf: List<R>? = null
    private var itemsMade: List<I>? = null

    /** Whether the last [next] asked for the list to be queried again. */
    @Volatile
    var rebuild: Boolean = false
        private set

    /**
     * Passes every item of [source] unchanged and, when a batch was stored that the page's
     * list does not hold yet, delivers the latest item again: at once for the first batch,
     * then at most once per pace period, the latest count never lost.
     */
    fun <T : Any> wake(source: Flowable<T>): Flowable<T> = Flowable.defer {
        fresh.set(true)
        var seen = -1L
        val pacedCounts = log.stored()
            .throttleLatest(paceMillis, TimeUnit.MILLISECONDS, paceScheduler)
            .doOnNext { paced.set(it) }
            .onBackpressureLatest()
        Flowable.combineLatest(source, pacedCounts) { item, count -> item to count }
            .filter { (_, count) ->
                val batchWake = seen != -1L && count != seen
                seen = count
                // a wake-up for batches the list already holds is dropped
                !batchWake || count > builtAt
            }
            .map { it.first }
    }

    /** Decides on one item. `false`: post nothing. */
    fun next(event: FeedEventManager.Event, showPlayed: Boolean): Boolean {
        val kind = when (event) {
            is FeedEventManager.Event.IdleEvent -> EventKind.IDLE
            is FeedEventManager.Event.ProgressEvent -> EventKind.PROGRESS
            is FeedEventManager.Event.SuccessResultEvent -> EventKind.SUCCESS
            is FeedEventManager.Event.ErrorResultEvent -> EventKind.ERROR
        }
        val concerned = FeedRefreshLog.concerns(log.groupId, pageGroupId)
        val pageChanged = fresh.getAndSet(false) or (showPlayed != builtShowPlayed)
        val decided = plan(kind, concerned, pageChanged, paced.get() > builtAt)
        rebuild = decided.rebuild
        show = decided.show
        if (decided.rebuild) {
            // read before the query: a batch stored meanwhile costs one more rebuild, never a missed one
            builtAt = log.storedCount
            builtShowPlayed = showPlayed
        }
        return decided.show != Show.NOTHING
    }

    /** A page that is not concerned handles a foreign event as "idle", so a result event stays for a concerned page. */
    fun shownAs(event: FeedEventManager.Event): FeedEventManager.Event =
        if (show == Show.LOADED) FeedEventManager.Event.IdleEvent else event

    /** After a rebuild [queried] becomes the page's rows; else the rows of the last rebuild are returned. */
    fun rows(queried: List<R>): List<R> {
        if (rebuild) kept = queried
        return kept
    }

    /**
     * The list of a progress state: `null` when there is nothing to show (full-screen
     * progress), else one item per row. Main thread. The items are made once per list
     * instance, so progress events between two rebuilds create none.
     */
    fun items(rows: List<R>, create: (R) -> I): List<I>? {
        if (display(Show.PROGRESS, rows.isNotEmpty()) == Display.PROGRESS_FULL) return null
        if (itemsOf !== rows) {
            itemsMade = rows.map(create)
            itemsOf = rows
        }
        return itemsMade
    }

    companion object {
        /** AC-2: the list is rebuilt at most once in two seconds. */
        const val REBUILD_PERIOD_MILLIS = 2_000L

        /**
         * [REBUILD_PERIOD_MILLIS] plus the page's own throttle behind the gate
         * (`DEFAULT_THROTTLE_TIMEOUT`, 120 ms), which can delay one wake-up by that much.
         */
        const val PACE_MILLIS = 2_120L
    }
}
