package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor

/**
 * What the feed loader reports about its refreshes: which group the refresh started last is
 * for, whether one is running, and how many batches of channels it has stored. The loader's
 * events carry no group, so a feed page asks here when it decides what an event means to it.
 *
 * One instance for the app ([app]); the loader writes it through three marked lines in
 * `FeedLoadManager.startLoading`. Written from the loader's threads, read from the pages'
 * threads: writers hold one lock, readers read volatile fields.
 */
class FeedRefreshLog {
    private val lock = Any()
    private val counts = BehaviorProcessor.createDefault(0L)
    private var running = 0

    /** Group of the refresh started last; [ALL] before any refresh. It stays after the refresh has ended. */
    @Volatile
    var groupId: Long = ALL
        private set

    /** Batches stored since the process started. */
    @Volatile
    var storedCount: Long = 0L
        private set

    @Volatile
    private var anyRunning = false

    /** A refresh of [groupId] was subscribed to. */
    fun started(groupId: Long) = synchronized(lock) {
        this.groupId = groupId
        running++
        anyRunning = true
    }

    /** A refresh has terminated or was disposed. Never counts below zero. */
    fun ended() = synchronized(lock) {
        if (running > 0) running--
        anyRunning = running > 0
    }

    /**
     * A batch transaction has committed. The count is raised first and announced second, so
     * whoever receives a count from [stored] already reads at least that count in [storedCount].
     */
    fun batchStored() = synchronized(lock) {
        val count = storedCount + 1
        storedCount = count
        counts.onNext(count)
    }

    /** The current count at once, then every new count; a slow reader gets the latest one. */
    fun stored(): Flowable<Long> = counts.onBackpressureLatest()

    /** A refresh is running that does not concern the page of [pageGroupId]. */
    fun runningElsewhere(pageGroupId: Long): Boolean = anyRunning && !concerns(groupId, pageGroupId)

    companion object {
        /** `FeedGroupEntity.GROUP_ALL_ID`. */
        const val ALL = -1L

        /** `FeedLoadManager.GROUP_NOTIFICATION_ENABLED`: the background check for new videos. */
        const val NOTIFICATIONS = -2L

        @JvmField
        val app = FeedRefreshLog()

        /**
         * Whether a refresh of [refreshGroupId] concerns the feed page of [pageGroupId]: a
         * refresh of "All" and the notification check concern every page, a group's refresh
         * concerns that group's page and the "All" page.
         */
        fun concerns(refreshGroupId: Long, pageGroupId: Long): Boolean =
            refreshGroupId == ALL || refreshGroupId == NOTIFICATIONS ||
                pageGroupId == ALL || refreshGroupId == pageGroupId
    }
}
