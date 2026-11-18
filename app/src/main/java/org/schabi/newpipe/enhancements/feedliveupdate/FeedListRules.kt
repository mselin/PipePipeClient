package org.schabi.newpipe.enhancements.feedliveupdate

enum class AfterUpdate { NOTHING, SCROLL_TO_TOP, SHOW_NEW_ITEMS }

/** The feed page's rules that need no view: what follows a list update, which items a search text keeps, the progress line. */
object FeedListRules {

    /**
     * What to do once the list shows [newIds] instead of [oldIds].
     *
     * @param atTop the list could not be scrolled up before the update
     * @param firstVisible index in [oldIds] of the first item that was on screen; negative counts as 0
     * @param duringRefresh the update belongs to a running refresh (the final state does not)
     */
    fun afterUpdate(atTop: Boolean, firstVisible: Int, oldIds: List<Long>, newIds: List<Long>, duringRefresh: Boolean): AfterUpdate {
        // first fill, or nothing left: a saved scroll position may be waiting, leave the list alone
        if (oldIds.isEmpty() || newIds.isEmpty()) return AfterUpdate.NOTHING
        if (atTop) return if (newIds[0] != oldIds[0]) AfterUpdate.SCROLL_TO_TOP else AfterUpdate.NOTHING
        val from = maxOf(firstVisible, 0)
        if (from >= oldIds.size) return AfterUpdate.NOTHING
        val inNew = newIds.toHashSet()
        // the first item the reader saw that is still there
        val anchor = oldIds.subList(from, oldIds.size).firstOrNull { it in inNew } ?: return AfterUpdate.NOTHING
        val inOld = oldIds.toHashSet()
        val newAbove = newIds.subList(0, newIds.indexOf(anchor)).any { it !in inOld }
        return if (newAbove && duringRefresh) AfterUpdate.SHOW_NEW_ITEMS else AfterUpdate.NOTHING
    }

    /** Whether [next] shows the rows [shown] does: same size and the same row instance at each index. */
    inline fun <T> sameRows(shown: List<T>, next: List<T>, row: (T) -> Any?): Boolean {
        if (shown.size != next.size) return false
        for (i in shown.indices) if (row(shown[i]) !== row(next[i])) return false
        return true
    }

    /** The test of the feed's search field: [text] is part of the title or of the uploader's name, case ignored. */
    fun matches(title: String?, uploader: String?, text: String): Boolean {
        val wanted = text.lowercase()
        return title?.lowercase()?.contains(wanted) == true || uploader?.lowercase()?.contains(wanted) == true
    }

    /** The progress of a refresh as one line: the counts behind [loading], else [message], else "∞/∞". */
    fun progressText(current: Int, max: Int, loading: String, message: String?): String = when {
        current != -1 || max != -1 -> "$loading $current/$max"
        message != null -> message
        else -> "∞/∞"
    }
}
