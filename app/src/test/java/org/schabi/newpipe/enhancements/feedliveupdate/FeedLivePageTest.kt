package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance criteria as the owner would say them, on whole pages: the feed page's stream
 * as plan section 8 leaves it ([Page]: the real `FeedLiveUpdate`, the real on-screen gate of
 * `subscription-scale`, the page's 120 ms throttle, RxJava for real, a `TestScheduler`), a
 * stand-in loader that posts the events and reports to the `FeedRefreshLog` as the plan's three
 * marked lines do, and a stand-in for `FeedEventManager`.
 *
 * What a pass does not prove: that `FeedViewModel` and `FeedFragment` are wired like [Page]
 * (that is `FeedLiveWiringTest`), and anything about views (the device script).
 */
class FeedLivePageTest {

    private companion object {
        /** Longer than the pace and the throttle: everything pending has happened. */
        const val SETTLE = 10_000L

        /** The page's throttle, `DEFAULT_THROTTLE_TIMEOUT`. */
        const val THROTTLE = 120L
    }

    private val scheduler = TestScheduler()
    private val log = FeedRefreshLog()
    private val events = FakeEvents()
    private val loader = FakeLoader(log, events)

    private fun settle() = scheduler.advanceMillis(SETTLE)

    /** A page that was opened, is on screen (or not) and shows what is stored. */
    private fun page(groupId: Long, onScreen: Boolean = true): Page {
        val page = Page(groupId, loader, scheduler)
        if (onScreen) page.onResume()
        settle()
        return page
    }

    private fun someStoredVideos() {
        loader.stored = loader.newRows(20, GROUP_A) + loader.newRows(20, GROUP_B)
    }

    private fun Page.progressItems() = (last as Shown.Progress).items

    // ---- AC-1 ----

    /** AC-1: "While a refresh that concerns it is running, a feed page keeps showing its video list as it was before the refresh." */
    @Test
    fun ac1_whenARefreshStarts_theListStaysAsItWas() {
        someStoredVideos()
        val page = page(ALL)
        val idsBefore = page.ids
        val updatesBefore = page.listUpdates
        val buildsBefore = page.listBuilds
        assertEquals(40, idsBefore.size)

        loader.start(ALL)
        loader.progress(0, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)

        assertTrue("the page is told about the refresh", page.last is Shown.Progress)
        assertNotNull("AC-1: with its list, not instead of it", page.progressItems())
        assertFalse("AC-1: \"The list is not hidden\"", page.fullScreenProgress)
        assertEquals("AC-1: \"and not replaced\": the adapter is not given a list", updatesBefore, page.listUpdates)
        assertEquals("AC-1: the same videos", idsBefore, page.ids)
        assertEquals("the start of a refresh costs no query", buildsBefore, page.listBuilds)
    }

    /** AC-1, AC-3, plan section 9: the 1600 progress events of a refresh are counts only: no query, no item, no list update. */
    @Test
    fun ac1_progressAlone_changesOnlyTheCounts() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(0, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        val updates = page.listUpdates
        val builds = page.listBuilds
        val items = page.itemsCreated
        val statesBefore = page.states.size

        for (channel in 1..SUBSCRIPTIONS) {
            loader.progress(channel, SUBSCRIPTIONS)
            scheduler.advanceMillis(150)
        }

        assertEquals("AC-3: every count reaches the page", statesBefore + SUBSCRIPTIONS, page.states.size)
        assertEquals(Shown.Progress(SUBSCRIPTIONS, SUBSCRIPTIONS, 0, page.progressItems()), page.last)
        assertEquals("AC-1: no query for a progress event", builds, page.listBuilds)
        assertEquals("AC-1: no item is created for a progress event", items, page.itemsCreated)
        assertEquals("AC-1: the list is not touched", updates, page.listUpdates)
    }

    /** AC-1: a page opened while a refresh runs shows its list at once, with the progress. */
    @Test
    fun ac1_aPageOpenedDuringARefresh_showsItsList() {
        someStoredVideos()
        loader.start(ALL)
        loader.progress(5, SUBSCRIPTIONS)

        val page = page(ALL)

        assertTrue(page.last is Shown.Progress)
        assertFalse("AC-1: the list, not the full-screen progress", page.fullScreenProgress)
        assertEquals(40, page.ids.size)
    }

    // ---- AC-2 ----

    /** AC-2: "As the results of the refresh are stored, the list shows them". */
    @Test
    fun ac2_aStoredBatch_appearsInTheList_whileTheRefreshRuns() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(20, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        val updates = page.listUpdates
        val fresh = loader.newRows(3, GROUP_A)

        loader.storeBatch(fresh)
        scheduler.advanceMillis(THROTTLE)

        assertEquals("AC-2: the new videos are at the top of the list", fresh.map { it.id }, page.ids.take(3))
        assertEquals(43, page.ids.size)
        assertEquals("AC-2: one list update for the batch", updates + 1, page.listUpdates)
        assertTrue("the refresh is still running: progress, with the list", page.last is Shown.Progress)
        assertFalse(page.fullScreenProgress)
    }

    /** AC-2: a batch that brings no new video for the page still costs one query, and leaves the list alone if its rows are the same objects. */
    @Test
    fun ac2_twoBatchesInsideOnePeriod_areShownTogetherWhenThePeriodEnds() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(20, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        loader.storeBatch(loader.newRows(1))
        scheduler.advanceMillis(THROTTLE)
        val builds = page.listBuilds

        loader.storeBatch(loader.newRows(1))
        scheduler.advanceMillis(300)
        loader.storeBatch(loader.newRows(1))
        scheduler.advanceMillis(300)
        assertEquals("AC-2: at most one rebuild in two seconds", builds, page.listBuilds)
        assertEquals(41, page.ids.size)

        scheduler.advanceMillis(FeedLiveUpdate.PACE_MILLIS)
        assertEquals("AC-2: both waiting batches in one rebuild", builds + 1, page.listBuilds)
        assertEquals(43, page.ids.size)
    }

    /** AC-2, AC-8: "When the refresh ends the list shows the final state, as it does today", with the errors, and the result is consumed once. */
    @Test
    fun ac8_theEnd_showsTheFinalListWithTheErrors_once() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(1599, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        loader.storeBatch(loader.newRows(2))
        scheduler.advanceMillis(500)
        loader.storeBatch(loader.newRows(2)) // waits for the pace
        val gone = IllegalStateException("channel gone")

        loader.succeed(listOf(gone))
        scheduler.advanceMillis(THROTTLE)

        val end = page.last
        assertTrue("AC-8: the loaded state of today", end is Shown.Loaded)
        assertEquals("AC-8: with the messages about channels that could not be loaded", listOf(gone), (end as Shown.Loaded).errors)
        assertEquals("AC-2: the final state has every batch, also the one that was waiting", loader.stored.map { it.id }, page.ids)
        assertEquals(44, page.ids.size)
        assertEquals("AC-8: the result is consumed", 1, events.resets)

        val states = page.states.size
        val builds = page.listBuilds
        settle()
        assertEquals("AC-2: nothing follows the final state", states, page.states.size)
        assertEquals(builds, page.listBuilds)
        assertEquals(1, events.resets)
    }

    /**
     * Risk 6 of the plan, seen on the whole chain: a count that leaves the pace operator while
     * the result event waits in the page's 120 ms throttle does not double the final state. The
     * wake-up delivers the page's latest item again, and the throttle keeps only the latest, so
     * the two are one. The doubling of `FeedLiveUpdateTest`'s risk 6 test needs the result to
     * be past the throttle and not yet decided on, which is the hop between two threads that
     * this chain does not model.
     */
    @Test
    fun risk6_aCountThatArrivesWhileTheResultWaitsInThePagesThrottle_givesOneFinalState() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(1599, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        loader.storeBatch(loader.newRows(1), roomEmits = false) // shown at once; the pace period runs for 2120 ms
        scheduler.advanceMillis(2_000)
        loader.storeBatch(loader.newRows(1), roomEmits = false) // waits for the pace
        scheduler.advanceMillis(60)
        loader.progress(1600, SUBSCRIPTIONS) // passes the throttle, which now holds what follows for 120 ms
        scheduler.advanceMillis(10)
        val loadedBefore = page.states.count { it is Shown.Loaded }
        val builds = page.listBuilds

        loader.succeed() // the result waits in the throttle; 50 ms later the pace period ends
        settle()

        assertEquals("one final state", loadedBefore + 1, page.states.count { it is Shown.Loaded })
        assertTrue(page.last is Shown.Loaded)
        assertEquals("one rebuild, the final one", builds + 1, page.listBuilds)
        assertEquals(42, page.ids.size)
        assertEquals(1, events.resets)
    }

    // ---- AC-6 ----

    /** AC-6: "A feed page that has nothing to show yet ... shows the progress display of today until the first results are stored." */
    @Test
    fun ac6_anEmptyPage_showsTheFullProgress_untilTheFirstVideosAreStored() {
        val page = page(GROUP_A)
        assertEquals(Shown.Loaded(emptyList(), emptyList()), page.last)

        loader.start(GROUP_A)
        loader.progress(0, 40)
        scheduler.advanceMillis(THROTTLE)
        assertNull("AC-6: no list: the progress state of today", page.progressItems())
        assertTrue("AC-6: the progress display of today", page.fullScreenProgress)

        loader.progress(20, 40)
        loader.storeBatch(emptyList()) // twenty channels without a video
        scheduler.advanceMillis(THROTTLE)
        assertTrue("AC-6: a batch that stored no video leaves it", page.fullScreenProgress)
        assertNull(page.progressItems())

        scheduler.advanceMillis(FeedLiveUpdate.PACE_MILLIS)
        val first = loader.newRows(2, GROUP_A)
        loader.storeBatch(first)
        scheduler.advanceMillis(THROTTLE)
        assertFalse("AC-6: \"until the first results are stored\"", page.fullScreenProgress)
        assertEquals(first.map { it.id }, page.ids)
        assertTrue(page.last is Shown.Progress)
    }

    /** AC-6, device step 6: "A group with no videos keeps it until the end and then shows the empty view." */
    @Test
    fun ac6_aGroupWithoutVideos_keepsTheFullProgressUntilTheEnd() {
        val page = page(GROUP_A)
        loader.start(GROUP_A)
        loader.progress(0, 20)
        scheduler.advanceMillis(THROTTLE)

        loader.storeBatch(emptyList())
        settle()
        assertTrue(page.fullScreenProgress)

        loader.succeed()
        scheduler.advanceMillis(THROTTLE)
        assertEquals("then the loaded state, empty: the empty view of today", Shown.Loaded(emptyList(), emptyList()), page.last)
    }

    /** AC-6: a refresh of "All" brings videos of another group first: the group's empty page keeps the full progress. */
    @Test
    fun ac6_videosOfOtherGroups_doNotEndTheFullProgress() {
        val page = page(GROUP_A)
        loader.start(ALL)
        loader.progress(0, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)

        loader.storeBatch(loader.newRows(5, GROUP_B))
        settle()

        assertTrue("AC-6: nothing to show yet for this group", page.fullScreenProgress)
        assertNull(page.progressItems())
    }

    // ---- AC-7 ----

    /**
     * AC-7: "A refresh that does not concern a feed page changes nothing on that page: no
     * progress, no reload." A refresh of group 9, watched from the page of group 7, with the
     * small Room queries re-emitting after every batch as they do (plan section 1).
     */
    @Test
    fun ac7_aGroupRefresh_seenFromAnotherGroupsPage_changesNothing() {
        someStoredVideos()
        val page = page(GROUP_A)
        val states = page.states.size
        val builds = page.listBuilds
        val items = page.itemsCreated
        val updates = page.listUpdates
        val ids = page.ids

        loader.start(GROUP_B)
        assertTrue("AC-11: a pull on this page would be dropped by the service: the indicator is taken back", log.runningElsewhere(GROUP_A))
        for (channel in 1..40) {
            loader.progress(channel, 40)
            scheduler.advanceMillis(200)
            if (channel % BATCH == 0) loader.storeBatch(loader.newRows(3, GROUP_B))
        }
        settle()
        loader.succeed(listOf(IllegalStateException("of group 9")))
        settle()

        assertEquals("AC-7: no progress and nothing else is shown", states, page.states.size)
        assertEquals("AC-7: no reload", builds, page.listBuilds)
        assertEquals(items, page.itemsCreated)
        assertEquals(updates, page.listUpdates)
        assertEquals(ids, page.ids)
        assertFalse(page.fullScreenProgress)
        assertEquals("AC-7: the result is left for a page it concerns", 0, events.resets)
        assertFalse("AC-11: after the end a pull starts a refresh as today", log.runningElsewhere(GROUP_A))
    }

    /**
     * AC-7, AC-8, device step 7: "After A's end B is unchanged; A or 'All', whichever is opened
     * first, shows A's error messages." The result stays until a concerned page is on screen.
     */
    @Test
    fun ac7_aPageNotConcerned_doesNotConsumeTheResult_theNextConcernedPageGetsIt() {
        someStoredVideos()
        val all = page(ALL, onScreen = false) // a main tab that is not the one on screen
        val other = page(GROUP_A)
        val allStates = all.states.size
        val gone = IllegalStateException("channel of group 9 gone")

        loader.start(GROUP_B)
        loader.progress(1, 20)
        scheduler.advanceMillis(THROTTLE)
        val fresh = loader.newRows(2, GROUP_B)
        loader.storeBatch(fresh)
        loader.succeed(listOf(gone))
        settle()
        assertEquals("the page off screen did nothing", allStates, all.states.size)
        assertEquals(0, events.resets)

        other.onPause()
        all.onResume()
        scheduler.advanceMillis(THROTTLE)

        val end = all.last
        assertTrue(end is Shown.Loaded)
        assertEquals("AC-8: the 'All' page shows the errors of group 9's refresh", listOf(gone), (end as Shown.Loaded).errors)
        assertEquals("and group 9's new videos", fresh.map { it.id }, all.ids.take(2))
        assertEquals("AC-8: and consumes the result", 1, events.resets)
        assertTrue("AC-7: the other group's page never showed them", other.states.none { it is Shown.Loaded && it.errors.isNotEmpty() })
    }

    /** AC-7: "A refresh of a group concerns the 'All' page": progress, updates and the end. */
    @Test
    fun ac7_aGroupRefresh_seenFromTheAllPage_isShown() {
        someStoredVideos()
        val page = page(ALL)
        val builds = page.listBuilds

        loader.start(GROUP_B)
        assertFalse(log.runningElsewhere(ALL))
        loader.progress(1, 20)
        scheduler.advanceMillis(THROTTLE)
        assertEquals("progress, with the list", Shown.Progress(1, 20, 0, page.progressItems()), page.last)
        assertNotNull(page.progressItems())

        val fresh = loader.newRows(2, GROUP_B)
        loader.storeBatch(fresh)
        scheduler.advanceMillis(THROTTLE)
        assertEquals("AC-2: the group's new videos appear on the 'All' page", fresh.map { it.id }, page.ids.take(2))

        loader.succeed()
        scheduler.advanceMillis(THROTTLE)
        assertTrue(page.last is Shown.Loaded)
        assertEquals(1, events.resets)
        assertEquals("one rebuild for the batch, one for the end", builds + 2, page.listBuilds)
    }

    /** AC-7: a group refresh seen from that group's page. */
    @Test
    fun ac7_aGroupRefresh_seenFromThatGroupsPage_isShown() {
        someStoredVideos()
        val page = page(GROUP_B)
        val builds = page.listBuilds

        loader.start(GROUP_B)
        assertFalse(log.runningElsewhere(GROUP_B))
        loader.progress(1, 20)
        scheduler.advanceMillis(THROTTLE)
        assertNotNull(page.progressItems())

        val fresh = loader.newRows(2, GROUP_B)
        loader.storeBatch(fresh)
        scheduler.advanceMillis(THROTTLE)
        assertEquals(fresh.map { it.id }, page.ids.take(2))
        assertEquals(22, page.ids.size)

        loader.succeed()
        scheduler.advanceMillis(THROTTLE)
        assertTrue(page.last is Shown.Loaded)
        assertEquals(1, events.resets)
        assertEquals(builds + 2, page.listBuilds)
    }

    /** AC-7, answer A1: the background check for new videos concerns every page; here a group's page. */
    @Test
    fun ac7_theNotificationCheck_seenFromAGroupsPage_isShown() {
        someStoredVideos()
        val page = page(GROUP_A)

        loader.start(NOTIFICATIONS)
        assertFalse(log.runningElsewhere(GROUP_A))
        loader.progress(1, 12)
        scheduler.advanceMillis(THROTTLE)
        assertNotNull("progress with the list", page.progressItems())

        val fresh = loader.newRows(1, GROUP_A)
        loader.storeBatch(fresh)
        scheduler.advanceMillis(THROTTLE)
        assertEquals(fresh[0].id, page.ids.first())

        loader.succeed()
        scheduler.advanceMillis(THROTTLE)
        assertTrue(page.last is Shown.Loaded)
        assertEquals(1, events.resets)
    }

    /** AC-7: a refresh of "All" seen from a group's page. */
    @Test
    fun ac7_aRefreshOfAll_seenFromAGroupsPage_isShown() {
        someStoredVideos()
        val page = page(GROUP_A)

        loader.start(ALL)
        loader.progress(1, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        assertNotNull(page.progressItems())

        loader.storeBatch(loader.newRows(2, GROUP_B) + loader.newRows(1, GROUP_A))
        scheduler.advanceMillis(THROTTLE)
        assertEquals("only this group's videos, as the page's query says", 21, page.ids.size)
    }

    // ---- AC-10 ----

    /**
     * AC-10: "A feed page that is not on screen does no work for a running refresh; it shows
     * the current state when it is returned to". Off screen: no state, no query, no item, no
     * observer of the small queries, no pace timer. Returning during the refresh: one query,
     * the current list, the progress.
     */
    @Test
    fun ac10_offScreen_noWork_andTheCurrentStateOnReturn() {
        someStoredVideos()
        val pace = CountingScheduler(scheduler)
        val page = Page(ALL, loader, scheduler, paceScheduler = pace)
        page.onResume()
        settle()
        assertTrue("on screen the page listens for batches", pace.alive > 0)

        page.onPause()
        val states = page.states.size
        val builds = page.listBuilds
        val items = page.itemsCreated
        assertEquals("AC-10: off screen there is no subscription to the batch counts", 0, pace.alive)
        assertFalse("AC-10: and no observer of the small queries", loader.smallQueries.hasSubscribers())

        loader.start(ALL)
        for (channel in 1..100) {
            loader.progress(channel, SUBSCRIPTIONS)
            scheduler.advanceMillis(200)
            if (channel % BATCH == 0) loader.storeBatch(loader.newRows(2))
        }
        settle()
        assertEquals("AC-10: no state", states, page.states.size)
        assertEquals("AC-10: no query", builds, page.listBuilds)
        assertEquals("AC-10: no item", items, page.itemsCreated)
        assertEquals(0, pace.alive)

        page.onResume()
        scheduler.advanceMillis(THROTTLE)
        assertEquals("AC-10: returning costs one query", builds + 1, page.listBuilds)
        assertEquals("AC-10: and shows the current state", loader.stored.map { it.id }, page.ids)
        assertEquals(50, page.ids.size)
        assertTrue("the refresh still runs: its progress is shown, with the list", page.last is Shown.Progress)
        assertFalse(page.fullScreenProgress)

        loader.storeBatch(loader.newRows(2))
        settle()
        assertEquals("AC-2: and the page follows the refresh again", 52, page.ids.size)
    }

    /**
     * AC-10: returning to the page during a refresh reloads once also when no batch was stored
     * meanwhile: what changed is not the refresh's (here a video left the feed), and the page
     * "shows the current state when it is returned to, as `subscription-scale` already provides".
     */
    @Test
    fun ac10_returningDuringARefresh_reloadsOnce_alsoWithoutAStoredBatch() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(1, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        page.onPause()
        val builds = page.listBuilds

        loader.stored = loader.stored.drop(1)
        loader.progress(2, SUBSCRIPTIONS)
        settle()
        assertEquals(builds, page.listBuilds)

        page.onResume()
        settle()
        assertEquals("AC-10: one query for returning", builds + 1, page.listBuilds)
        assertEquals("AC-10: the current state", 39, page.ids.size)
        assertTrue(page.last is Shown.Progress)

        loader.progress(3, SUBSCRIPTIONS)
        settle()
        assertEquals("and none for the next progress event", builds + 1, page.listBuilds)
    }

    /** AC-10, AC-8: a refresh that ran and ended while the page was off screen: on return, one query and the end as today. */
    @Test
    fun ac10_aRefreshThatEndedOffScreen_isShownAsItsEndOnReturn() {
        someStoredVideos()
        val page = page(ALL)
        page.onPause()
        val builds = page.listBuilds
        val gone = IllegalStateException("channel gone")
        loader.start(ALL)
        loader.progress(1, 20)
        loader.storeBatch(loader.newRows(4))
        loader.succeed(listOf(gone))
        settle()
        assertEquals(builds, page.listBuilds)

        page.onResume()
        scheduler.advanceMillis(THROTTLE)

        assertEquals(Shown.Loaded((page.last as Shown.Loaded).items, listOf(gone)), page.last)
        assertEquals(44, page.ids.size)
        assertEquals("AC-10: one query", builds + 1, page.listBuilds)
        assertEquals(1, events.resets)
    }

    // ---- AC-4, AC-11 ----

    /** AC-4: the "show watched" switch works during the refresh: a reload at once, not paced. */
    @Test
    fun ac4_theShowWatchedSwitch_reloadsAtOnceDuringARefresh() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(1, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        loader.storeBatch(loader.newRows(1))
        scheduler.advanceMillis(THROTTLE)
        val builds = page.listBuilds
        scheduler.advanceMillis(300) // inside the pace period

        page.togglePlayedItems(false)
        scheduler.advanceMillis(THROTTLE)

        assertEquals("AC-4: one reload for the switch, although a rebuild was 300 ms ago", builds + 1, page.listBuilds)
        assertTrue(page.last is Shown.Progress)
    }

    /** AC-11, plan 10.4: a failed refresh shows the error state of today on a concerned page and is consumed. */
    @Test
    fun ac11_aFailedRefresh_isTheErrorOfToday() {
        someStoredVideos()
        val page = page(ALL)
        val builds = page.listBuilds
        loader.start(ALL)
        loader.progress(1, SUBSCRIPTIONS)
        scheduler.advanceMillis(THROTTLE)
        val failure = IllegalStateException("database")

        loader.fail(failure)
        scheduler.advanceMillis(THROTTLE)

        assertEquals(Shown.Error(failure), page.last)
        assertEquals("no query for the error", builds, page.listBuilds)
        assertEquals(1, events.resets)
    }

    /** AC-7, plan 10.4: "A page not concerned shows nothing" of a failed refresh either. */
    @Test
    fun ac7_aFailedRefreshOfAnotherGroup_isNotShown() {
        someStoredVideos()
        val page = page(GROUP_A)
        val states = page.states.size
        loader.start(GROUP_B)
        loader.progress(1, 20)

        loader.fail(IllegalStateException("database"))
        settle()

        assertEquals(states, page.states.size)
        assertEquals(0, events.resets)
    }

    /** AC-11: a second refresh after the first: the page follows it like the first (the batch count is not per refresh). */
    @Test
    fun ac11_aSecondRefresh_isFollowedLikeTheFirst() {
        someStoredVideos()
        val page = page(ALL)
        loader.start(ALL)
        loader.progress(1, 20)
        scheduler.advanceMillis(THROTTLE)
        loader.storeBatch(loader.newRows(1))
        loader.succeed()
        settle()
        assertTrue(page.last is Shown.Loaded)
        val updates = page.listUpdates

        loader.start(GROUP_A)
        loader.progress(1, 20)
        scheduler.advanceMillis(THROTTLE)
        assertTrue(page.last is Shown.Progress)
        assertEquals("AC-1: the start of the second refresh leaves the list alone", updates, page.listUpdates)

        val fresh = loader.newRows(1, GROUP_A)
        loader.storeBatch(fresh)
        scheduler.advanceMillis(THROTTLE)
        assertEquals(fresh[0].id, page.ids.first())
        loader.succeed()
        scheduler.advanceMillis(THROTTLE)
        assertTrue(page.last is Shown.Loaded)
        assertEquals(2, events.resets)
    }
}
