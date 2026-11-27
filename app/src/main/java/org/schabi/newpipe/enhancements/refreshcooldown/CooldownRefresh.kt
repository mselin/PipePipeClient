package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import android.os.SystemClock
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import java.time.OffsetDateTime

/**
 * Glue: the cooldown part of one `FeedLoadManager.startLoading` call.
 *
 * - [ignore] (the notification check): nothing of this feature happens. [queryThreshold] is not
 *   reached, [select] returns the list it was given, no report is cleared or published, no
 *   preference and no table is read.
 * - [forced] ("Refresh anyway"): exactly the listed channels of the feed are loaded.
 * - otherwise: upstream's query is asked for the whole feed and [CooldownRules.select] decides
 *   per channel; with no value stored that is upstream's own condition on the same numbers.
 *
 * The constructor and [queryThreshold] run on the caller's thread, [select] on the thread
 * upstream's query emits on (Room's query executor, where its own queries are allowed), and
 * [done] on the thread the loading ends on.
 */
class CooldownRefresh(
    private val context: Context,
    private val groupId: Long,
    private val ignore: Boolean,
    private val forced: LongArray?
) {
    @Volatile private var nowMillis = 0L
    @Volatile private var globalSeconds = 0
    @Volatile private var skipped: List<Long> = emptyList()

    init {
        // a new manual refresh: what an earlier one skipped is no longer offered
        if (!ignore) SkipReports.app.clear()
    }

    /**
     * Keeps upstream's `now` and its reading of the global setting for the decision, and returns
     * a threshold for which upstream's query returns every subscription of the feed.
     */
    fun queryThreshold(now: OffsetDateTime, globalSeconds: Int): OffsetDateTime {
        nowMillis = now.toInstant().toEpochMilli()
        this.globalSeconds = globalSeconds
        return now.plusYears(100)
    }

    /** The channels to load, in the order of [scope]; remembers the ones left out because of their cooldown. */
    fun select(scope: List<SubscriptionEntity>): List<SubscriptionEntity> {
        if (ignore) return scope
        val selection = if (forced != null) {
            CooldownRules.forced(scope, { it.uid }, forced)
        } else {
            CooldownRules.select(
                scope, { it.uid }, { CooldownValues.subscriptionKey(it.serviceId, it.url) },
                RoomCooldownData(context), globalSeconds, nowMillis
            )
        }
        skipped = selection.skipped
        return selection.load
    }

    /** The loading has ended: the skipped channels become the report for the feed's page (none skipped: no report). */
    fun done() {
        if (!ignore) SkipReports.app.publish(groupId, skipped, SystemClock.elapsedRealtime())
    }
}
