package org.schabi.newpipe.enhancements.refreshcooldown

data class SkipReport(val groupId: Long, val subscriptionIds: List<Long>) {
    val count: Int get() = subscriptionIds.size
}

/** What a refresh skipped, for the page that shows its end. */
class SkipReports(private val offerMillis: Long = OFFER_MILLIS) {
    private var held: SkipReport? = null
    private var publishedAt = 0L

    @Synchronized
    fun clear() {
        held = null
    }

    /** An empty list clears. */
    @Synchronized
    fun publish(groupId: Long, skipped: List<Long>, nowMillis: Long) {
        held = if (skipped.isEmpty()) null else SkipReport(groupId, skipped.toList())
        publishedAt = nowMillis
    }

    @Synchronized
    fun take(pageGroupId: Long, nowMillis: Long): SkipReport? {
        val report = held ?: return null
        if (report.groupId != pageGroupId) return null
        held = null
        return if (nowMillis - publishedAt <= offerMillis) report else null
    }

    companion object {
        const val OFFER_MILLIS = 60_000L
        const val MESSAGE_MILLIS = 8_000
        const val ERROR_MESSAGE_WAIT_MILLIS = 3_500L
        const val EXTRA_FORCED = "org.schabi.newpipe.enhancements.refreshcooldown.FORCED"

        @JvmField
        val app = SkipReports()
    }
}
