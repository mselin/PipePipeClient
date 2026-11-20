package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/**
 * AC-2 and AC-10 at the owner's size (plan section 9): one refresh of 1600 subscriptions,
 * stored in 80 batches, 1600 progress events, watched from one feed page. The list builds
 * (500-row query, items, diff) are counted and compared with the plan's table:
 *
 * | Batches arrive | Rebuilds during the refresh | With the final one |
 * |---|---|---|
 * | every 5 s (400 s) | 80 | 81 |
 * | every 3 s (240 s) | 80 | 81 |
 * | every 333 ms (27 s) | 13 | 14 |
 * | all at once | 1 | 2 |
 *
 * plus one build for opening the page, and 0 for a page that is off screen or not concerned.
 * Before this feature the number is 1 (the final one) whatever the rate; a page that rebuilt
 * for every batch would do 80 at every rate.
 *
 * The page is [Page]: the whole chain with the gate and the 120 ms throttle, on a
 * `TestScheduler`. As in the plan's own run the page is on screen from time 0, when the pace
 * timer starts; every batch brings one new video.
 */
class FeedLiveScaleTest {

    private class Outcome(
        val page: Page,
        val events: FakeEvents,
        val loader: FakeLoader,
        val pace: CountingScheduler,
        val successAt: Long
    ) {
        /** Opening the page. */
        val opening: Int get() = page.buildTimes.count { it == 0L }

        /** Times of the list builds the refresh caused before its end. */
        val during: List<Long> get() = page.buildTimes.filter { it > 0L && it < successAt }

        /** The final one(s). */
        val final: Int get() = page.buildTimes.count { it >= successAt }
    }

    /**
     * One refresh of [refreshGroup], watched from the page of [pageGroup]. Batch k is stored at
     * [batchAt] (k); the 1600 progress events are spread evenly up to the last batch, each
     * before the batch that contains its channel is stored (`FeedLoadManager.kt:263-267`).
     */
    private fun refresh(
        pageGroup: Long,
        refreshGroup: Long,
        successAt: Long,
        onScreen: Boolean = true,
        leaveAt: Long? = null,
        batchAt: (Int) -> Long
    ): Outcome {
        val scheduler = TestScheduler()
        val pace = CountingScheduler(scheduler)
        val log = FeedRefreshLog()
        val events = FakeEvents()
        val loader = FakeLoader(log, events)
        val page = Page(pageGroup, loader, scheduler, paceScheduler = pace, openOnScreen = onScreen)
        val videoGroup = if (refreshGroup == ALL || refreshGroup == NOTIFICATIONS) GROUP_A else refreshGroup

        val timeline = TreeMap<Long, MutableList<() -> Unit>>()
        fun at(time: Long, action: () -> Unit) = timeline.getOrPut(time) { mutableListOf() }.add(action)

        val lastBatch = batchAt(BATCHES)
        for (channel in 1..SUBSCRIPTIONS) {
            val batch = (channel + BATCH - 1) / BATCH
            val from = if (batch == 1) 0L else batchAt(batch - 1)
            val time = maxOf(1L, from + (batchAt(batch) - from) * (channel - (batch - 1) * BATCH) / BATCH - 1)
            at(time) { loader.progress(channel, SUBSCRIPTIONS) }
        }
        for (batch in 1..BATCHES) at(batchAt(batch)) { loader.storeBatch(loader.newRows(1, videoGroup)) }
        if (leaveAt != null) at(leaveAt) { page.onPause() }
        at(successAt) { loader.succeed() }
        assertTrue("the end comes after the last batch", successAt > lastBatch)

        loader.start(refreshGroup)
        for ((time, actions) in timeline) {
            scheduler.advanceToMillis(time)
            actions.forEach { it() }
        }
        scheduler.advanceMillis(60_000)
        return Outcome(page, events, loader, pace, successAt)
    }

    private fun assertPaced(outcome: Outcome) {
        val gaps = outcome.during.zipWithNext { a, b -> b - a }
        assertTrue(
            "AC-2: \"the list is rebuilt at most once in two seconds\"; gaps between the rebuilds of the refresh: $gaps",
            gaps.all { it >= FeedLiveUpdate.REBUILD_PERIOD_MILLIS }
        )
    }

    private fun assertFinalStateShown(outcome: Outcome) {
        assertTrue("AC-2: the page ends in the loaded state", outcome.page.last is Shown.Loaded)
        assertEquals("AC-2: the final list has the video of every batch", BATCHES, outcome.page.ids.size)
        assertEquals(outcome.loader.rowsOf(outcome.page.groupId).map { it.id }, outcome.page.ids)
        assertEquals("AC-8: the result is consumed once", 1, outcome.events.resets)
    }

    /** AC-2, table row 1: a refresh that takes minutes, a batch every 5 s: each batch is shown. 80 + 1, and 1 for opening. */
    @Test
    fun ac2_all1600_aBatchEvery5Seconds_80RebuildsAndTheFinalOne() {
        val outcome = refresh(ALL, ALL, successAt = 405_000) { it * 5_000L }

        assertEquals("opening the page", 1, outcome.opening)
        assertEquals("AC-2: \"an update follows each stored batch\"", 80, outcome.during.size)
        assertEquals("AC-2: and the final state", 1, outcome.final)
        assertEquals("plan section 13: 82 list builds", 82, outcome.page.listBuilds)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }

    /** AC-2, table row 2: a batch every 3 s (four minutes): still each batch, 80 + 1. */
    @Test
    fun ac2_all1600_aBatchEvery3Seconds_80RebuildsAndTheFinalOne() {
        val outcome = refresh(ALL, ALL, successAt = 243_000) { it * 3_000L }

        assertEquals(1, outcome.opening)
        assertEquals(80, outcome.during.size)
        assertEquals(1, outcome.final)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }

    /** AC-2, table row 3: three batches a second (27 s): one rebuild per pace, 13 + 1. */
    @Test
    fun ac2_all1600_aBatchEvery333Millis_13RebuildsAndTheFinalOne() {
        val outcome = refresh(ALL, ALL, successAt = 30_000) { it * 333L }

        assertEquals(1, outcome.opening)
        assertEquals("AC-2: 26.6 s of batches at one rebuild per 2.12 s", 13, outcome.during.size)
        assertEquals(1, outcome.final)
        assertEquals("plan section 13: 15 list builds", 15, outcome.page.listBuilds)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }

    /** AC-2, table row 4: all 80 batches at once: 1 + 1. */
    @Test
    fun ac2_all1600_allBatchesAtOnce_1RebuildAndTheFinalOne() {
        val outcome = refresh(ALL, ALL, successAt = 30_000) { 1_000L }

        assertEquals(1, outcome.opening)
        assertEquals("AC-2: 80 batches in one moment are one rebuild", 1, outcome.during.size)
        assertEquals(1, outcome.final)
        assertEquals("plan section 13: 3 list builds", 3, outcome.page.listBuilds)
        assertFinalStateShown(outcome)
    }

    /** AC-2: the end comes while the last batches wait for the pace: the final list has them, and nothing follows it. */
    @Test
    fun ac2_all1600_theEndBeforeTheLastPacedRebuild_nothingFollowsTheFinalState() {
        val outcome = refresh(ALL, ALL, successAt = 26_700) { it * 333L }

        assertEquals(1, outcome.final)
        assertTrue(outcome.during.size in 12..13)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
        assertTrue("the final state is the last thing shown", outcome.page.states.last() is Shown.Loaded)
        assertEquals(1, outcome.page.states.count { it is Shown.Loaded && it !== outcome.page.states.first() })
    }

    /** AC-1, AC-10, plan section 9: what the 1600 progress events cost on screen: states, but no items beyond the rebuilds'. */
    @Test
    fun ac10_all1600_itemsAreCreatedPerRebuild_notPerProgressEvent() {
        val outcome = refresh(ALL, ALL, successAt = 30_000) { it * 333L }

        assertTrue("the progress events reach the page", outcome.page.states.count { it is Shown.Progress } > 100)
        assertTrue(
            "AC-10: at most one set of items per list build (15 builds of up to 80 rows), not one per progress event; created: ${outcome.page.itemsCreated}",
            outcome.page.itemsCreated <= outcome.page.listBuilds * BATCHES
        )
        assertEquals("one list update per rebuild that changed the rows", outcome.page.listBuilds, outcome.page.listUpdates)
    }

    /** AC-10: "the same with the page off screen": the page loaded once and was never on screen. 0. */
    @Test
    fun ac10_all1600_thePageOffScreen_doesNothing() {
        for (rate in listOf(5_000L, 333L)) {
            val outcome = refresh(ALL, ALL, successAt = rate * BATCHES + 5_000, onScreen = false) { it * rate }

            assertEquals("the first load of a page that was never shown, as subscription-scale provides", 1, outcome.opening)
            assertEquals("AC-10: no rebuild during the refresh (a batch every $rate ms)", 0, outcome.during.size)
            assertEquals("AC-10: and none for its end", 0, outcome.final)
            assertEquals("AC-10: no state", 1, outcome.page.states.size)
            assertEquals("AC-10: no pace timer", 0, outcome.pace.alive)
            assertFalse("AC-10: no observer of the small queries", outcome.loader.smallQueries.hasSubscribers())
            assertEquals("the result waits for the page", 0, outcome.events.resets)
        }
    }

    /** AC-10: the page is left five seconds into the refresh: nothing after that. */
    @Test
    fun ac10_all1600_thePageLeftDuringTheRefresh_stopsWorking() {
        val outcome = refresh(ALL, ALL, successAt = 30_000, leaveAt = 5_000) { it * 333L }

        assertTrue("it followed the refresh while on screen", outcome.during.isNotEmpty())
        assertTrue("AC-10: no rebuild after leaving (plus at most the item already in the 120 ms throttle)", outcome.during.all { it <= 5_120 })
        assertEquals(0, outcome.final)
        assertEquals(0, outcome.pace.alive)
        assertEquals(0, outcome.events.resets)
    }

    /** AC-7: "not concerned: 0". The same refresh for group 9, watched from the page of group 7. */
    @Test
    fun ac7_group1600_seenFromAnotherGroupsPage_doesNothing() {
        for (rate in listOf(5_000L, 333L)) {
            val outcome = refresh(GROUP_A, GROUP_B, successAt = rate * BATCHES + 5_000) { it * rate }

            assertEquals(1, outcome.opening)
            assertEquals("AC-7: no reload during the refresh (a batch every $rate ms)", 0, outcome.during.size)
            assertEquals("AC-7: and none for its end", 0, outcome.final)
            assertEquals("AC-7: no progress: the page shows its one loaded state", 1, outcome.page.states.size)
            assertEquals("AC-7: the result is not consumed", 0, outcome.events.resets)
        }
    }

    /** AC-7: a group refresh seen from that group's page: paced like "All". */
    @Test
    fun ac7_group1600_seenFromThatGroupsPage_isPacedLikeAll() {
        val outcome = refresh(GROUP_B, GROUP_B, successAt = 30_000) { it * 333L }

        assertEquals(13, outcome.during.size)
        assertEquals(1, outcome.final)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }

    /** AC-7: a group refresh seen from the "All" page: paced like "All". */
    @Test
    fun ac7_group1600_seenFromTheAllPage_isPacedLikeAll() {
        val outcome = refresh(ALL, GROUP_B, successAt = 30_000) { it * 333L }

        assertEquals(13, outcome.during.size)
        assertEquals(1, outcome.final)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }

    /** AC-7, answer A1: the notification check seen from a group's page: paced like "All". */
    @Test
    fun ac7_notificationCheck1600_seenFromAGroupsPage_isPacedLikeAll() {
        val outcome = refresh(GROUP_A, NOTIFICATIONS, successAt = 30_000) { it * 333L }

        assertEquals(13, outcome.during.size)
        assertEquals(1, outcome.final)
        assertPaced(outcome)
        assertFinalStateShown(outcome)
    }
}
