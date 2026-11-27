package org.schabi.newpipe.enhancements.feedliveupdate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AC-1, AC-2, AC-6, AC-7, AC-8 (plan 5.2): the two decision tables, every row, with every
 * value of the columns the plan marks "any". The tables are written out here as the plan
 * prints them; nothing is computed from a rule.
 */
class FeedLivePlanTest {

    private val both = listOf(true, false)

    private fun assertPlan(row: String, expected: Plan, kind: EventKind, concerned: Boolean, pageChanged: Boolean, storedSinceBuilt: Boolean) {
        assertEquals(
            "$row: plan($kind, concerned=$concerned, pageChanged=$pageChanged, storedSinceBuilt=$storedSinceBuilt)",
            expected,
            plan(kind, concerned, pageChanged, storedSinceBuilt)
        )
    }

    /** Row 1, `IDLE | any | any | any -> LOADED, rebuild`: no refresh, the list is built on every item, as today. */
    @Test
    fun plan_row1_idle_isLoadedAndRebuilds_whateverElse() {
        for (concerned in both) for (pageChanged in both) for (stored in both) {
            assertPlan("row 1", Plan(Show.LOADED, true), EventKind.IDLE, concerned, pageChanged, stored)
        }
    }

    /** AC-2, AC-8, row 2, `SUCCESS | yes | any | any -> END, rebuild`: the end always shows the final state. */
    @Test
    fun ac8_plan_row2_aConcernedSuccess_isTheEndAndAlwaysRebuilds() {
        for (pageChanged in both) for (stored in both) {
            assertPlan("row 2", Plan(Show.END, true), EventKind.SUCCESS, true, pageChanged, stored)
        }
    }

    /** AC-11, row 3, `ERROR | yes | any | any -> ERROR, no rebuild`: a failed refresh shows the error, without a query, as today. */
    @Test
    fun ac11_plan_row3_aConcernedError_isTheErrorWithoutRebuild() {
        for (pageChanged in both) for (stored in both) {
            assertPlan("row 3", Plan(Show.ERROR, false), EventKind.ERROR, true, pageChanged, stored)
        }
    }

    /** Row 4, `PROGRESS | yes | yes | any -> PROGRESS, rebuild`: a page opened, returned to or switched during the refresh loads its list. */
    @Test
    fun ac1_plan_row4_concernedProgress_onAChangedPage_rebuilds() {
        for (stored in both) {
            assertPlan("row 4", Plan(Show.PROGRESS, true), EventKind.PROGRESS, true, true, stored)
        }
    }

    /** AC-2, row 5, `PROGRESS | yes | no | yes -> PROGRESS, rebuild`: results were stored since the list was built. */
    @Test
    fun ac2_plan_row5_concernedProgress_afterAStoredBatch_rebuilds() {
        assertPlan("row 5", Plan(Show.PROGRESS, true), EventKind.PROGRESS, true, false, true)
    }

    /** AC-1, row 6, `PROGRESS | yes | no | no -> PROGRESS, no rebuild`: a progress tick alone costs no query. */
    @Test
    fun ac1_plan_row6_concernedProgress_withNothingNew_doesNotRebuild() {
        assertPlan("row 6", Plan(Show.PROGRESS, false), EventKind.PROGRESS, true, false, false)
    }

    /** AC-7, row 7, `PROGRESS, SUCCESS, ERROR | no | yes | any -> LOADED, rebuild`: a page not concerned loads as if nothing ran. */
    @Test
    fun ac7_plan_row7_notConcerned_onAChangedPage_isLoadedAndRebuilds() {
        for (kind in listOf(EventKind.PROGRESS, EventKind.SUCCESS, EventKind.ERROR)) for (stored in both) {
            assertPlan("row 7", Plan(Show.LOADED, true), kind, false, true, stored)
        }
    }

    /** AC-7, row 8, `PROGRESS, SUCCESS, ERROR | no | no | any -> NOTHING, no rebuild`: "no progress, no reload". */
    @Test
    fun ac7_plan_row8_notConcerned_otherwise_isNothing() {
        for (kind in listOf(EventKind.PROGRESS, EventKind.SUCCESS, EventKind.ERROR)) for (stored in both) {
            assertPlan("row 8", Plan(Show.NOTHING, false), kind, false, false, stored)
        }
    }

    /** The eight rows cover all 32 combinations; counted here so that a new enum value is noticed. */
    @Test
    fun plan_theTableIsComplete() {
        assertEquals(listOf("IDLE", "PROGRESS", "SUCCESS", "ERROR"), EventKind.values().map { it.name })
        assertEquals(listOf("LOADED", "END", "ERROR", "PROGRESS", "NOTHING"), Show.values().map { it.name })
        var rebuilds = 0
        var nothing = 0
        for (kind in EventKind.values()) for (concerned in both) for (pageChanged in both) for (stored in both) {
            val p = plan(kind, concerned, pageChanged, stored)
            if (p.rebuild) rebuilds++
            if (p.show == Show.NOTHING) nothing++
            if (p.show == Show.NOTHING) assertEquals("nothing is shown, so nothing is queried", false, p.rebuild)
        }
        // rows 1: 8, 2: 4, 4: 2, 5: 1, 7: 6 rebuild; row 8: 6 show nothing
        assertEquals(21, rebuilds)
        assertEquals(6, nothing)
    }

    // ---- display(show, hasItems) ----

    /** AC-1: a refresh on a page with a list keeps the list: the slim progress. */
    @Test
    fun ac1_display_progressWithItems_isTheListWithProgress() {
        assertEquals(Display.PROGRESS_LIST, display(Show.PROGRESS, true))
    }

    /** AC-6: "A feed page that has nothing to show yet ... shows the progress display of today". */
    @Test
    fun ac6_display_progressWithoutItems_isTheFullScreenProgress() {
        assertEquals(Display.PROGRESS_FULL, display(Show.PROGRESS, false))
    }

    /** AC-8: loaded and the end are the loaded page, with a list or empty (the empty view is the page's business). */
    @Test
    fun ac8_display_loadedAndEnd_areLoaded() {
        for (hasItems in both) {
            assertEquals(Display.LOADED, display(Show.LOADED, hasItems))
            assertEquals(Display.LOADED, display(Show.END, hasItems))
        }
    }

    @Test
    fun ac11_display_error_isTheError() {
        for (hasItems in both) assertEquals(Display.ERROR, display(Show.ERROR, hasItems))
    }

    /** AC-7: nothing stays nothing. */
    @Test
    fun ac7_display_nothing_isNothing() {
        for (hasItems in both) assertEquals(Display.NOTHING, display(Show.NOTHING, hasItems))
    }

    @Test
    fun display_theEnumIsThePlans() {
        assertEquals(listOf("LOADED", "PROGRESS_FULL", "PROGRESS_LIST", "ERROR", "NOTHING"), Display.values().map { it.name })
    }

    /** `Plan` is a data class of the two columns. */
    @Test
    fun plan_isAValue() {
        assertEquals(Plan(Show.END, true), Plan(Show.END, true).copy())
        assertEquals(Show.PROGRESS, Plan(Show.PROGRESS, false).show)
        assertEquals(false, Plan(Show.PROGRESS, false).rebuild)
    }
}
