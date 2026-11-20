package org.schabi.newpipe.enhancements.crashfixes

/**
 * C2: what the search page does in `onResume`. `onPause` disposes the running request but
 * leaves the loading flag set; "flag set, nothing running" is the stuck state.
 */
object SearchResume {
    enum class Action { NONE, RESTART, LOAD_MORE }

    @JvmStatic
    fun decide(
        isLoading: Boolean,
        searchRunning: Boolean,
        hasItems: Boolean,
        hasNextPage: Boolean,
        queryEmpty: Boolean
    ): Action = when {
        !isLoading || searchRunning -> Action.NONE
        hasItems && hasNextPage -> Action.LOAD_MORE
        !queryEmpty -> Action.RESTART
        else -> Action.NONE
    }
}
