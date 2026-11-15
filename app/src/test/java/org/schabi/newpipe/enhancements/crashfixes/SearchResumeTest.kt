package org.schabi.newpipe.enhancements.crashfixes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.crashfixes.SearchResume.Action

/**
 * AC-2 (C2): what the search page does when it is returned to. `SearchResume.decide` is the whole
 * decision (plan section "C2", "Fix"):
 *
 * `NONE` unless `isLoading && !searchRunning`; then `LOAD_MORE` if `hasItems && hasNextPage`;
 * else `RESTART` if the query is not empty; else `NONE`.
 *
 * These tests do not prove that `SearchFragment.onResume` calls `decide` or acts on its answer;
 * `CrashFixesWiringTest` checks that the call is there, device step D2 that it works.
 */
class SearchResumeTest {

    private fun decide(
        isLoading: Boolean,
        searchRunning: Boolean,
        hasItems: Boolean,
        hasNextPage: Boolean,
        queryEmpty: Boolean
    ): Action = SearchResume.decide(isLoading, searchRunning, hasItems, hasNextPage, queryEmpty)

    private val booleans = listOf(false, true)

    /** The stuck state of the finding: flag set, request disposed in `onPause`, nothing shown. */
    @Test
    fun ac2_interruptedSearch_restarts() {
        assertEquals(
            Action.RESTART,
            decide(isLoading = true, searchRunning = false, hasItems = false, hasNextPage = false, queryEmpty = false)
        )
        // a next page left over from an earlier search does not turn a first search into "load more"
        assertEquals(
            Action.RESTART,
            decide(isLoading = true, searchRunning = false, hasItems = false, hasNextPage = true, queryEmpty = false)
        )
    }

    /** Results are shown and the request for the next page was interrupted. */
    @Test
    fun ac2_interruptedLoadMore_loadsMore() {
        assertEquals(
            Action.LOAD_MORE,
            decide(isLoading = true, searchRunning = false, hasItems = true, hasNextPage = true, queryEmpty = false)
        )
    }

    /**
     * Items without a valid next page cannot load more; with a query the search is restarted
     * (the plan's rule: `LOAD_MORE` needs both, "else `RESTART` if the query is not empty").
     */
    @Test
    fun ac2_itemsWithoutNextPage_restarts() {
        assertEquals(
            Action.RESTART,
            decide(isLoading = true, searchRunning = false, hasItems = true, hasNextPage = false, queryEmpty = false)
        )
    }

    /**
     * First `onResume` after `onViewCreated` started the search: the flag is set and the request
     * is running. It must not be started a second time, whatever else is true.
     */
    @Test
    fun ac2_runningSearch_isLeftAlone() {
        for (hasItems in booleans) for (hasNextPage in booleans) for (queryEmpty in booleans) {
            assertEquals(
                "hasItems=$hasItems hasNextPage=$hasNextPage queryEmpty=$queryEmpty",
                Action.NONE,
                decide(isLoading = true, searchRunning = true, hasItems = hasItems, hasNextPage = hasNextPage, queryEmpty = queryEmpty)
            )
        }
    }

    /**
     * Not loading: a finished search, or a link lookup started instead of a search (it does not
     * set the flag and a restart would cancel it). Nothing is started, whatever else is true.
     * Also AC-7: returning to a search page that was not interrupted changes nothing.
     */
    @Test
    fun ac2_notLoading_nothing() {
        for (searchRunning in booleans) for (hasItems in booleans) for (hasNextPage in booleans) for (queryEmpty in booleans) {
            assertEquals(
                "searchRunning=$searchRunning hasItems=$hasItems hasNextPage=$hasNextPage queryEmpty=$queryEmpty",
                Action.NONE,
                decide(isLoading = false, searchRunning = searchRunning, hasItems = hasItems, hasNextPage = hasNextPage, queryEmpty = queryEmpty)
            )
        }
    }

    /** Stuck flag but no query and nothing to continue: there is nothing to search for. */
    @Test
    fun ac2_emptyQuery_nothing() {
        assertEquals(
            Action.NONE,
            decide(isLoading = true, searchRunning = false, hasItems = false, hasNextPage = false, queryEmpty = true)
        )
        assertEquals(
            Action.NONE,
            decide(isLoading = true, searchRunning = false, hasItems = true, hasNextPage = false, queryEmpty = true)
        )
        assertEquals(
            Action.NONE,
            decide(isLoading = true, searchRunning = false, hasItems = false, hasNextPage = true, queryEmpty = true)
        )
    }

    /** The enum has exactly the three answers `SearchFragment` handles (plan section 0). */
    @Test
    fun ac2_actions_areNoneRestartLoadMore() {
        assertEquals(listOf("NONE", "RESTART", "LOAD_MORE"), Action.values().map { it.name })
    }
}
