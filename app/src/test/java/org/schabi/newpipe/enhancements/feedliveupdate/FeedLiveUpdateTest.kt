package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.SuccessResultEvent
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import java.util.concurrent.TimeUnit

/**
 * AC-1, AC-2, AC-6, AC-7, AC-8, AC-10 (plan 3.2 and 5.2): `FeedLiveUpdate`, one per feed page,
 * driven by hand the way `FeedViewModel` drives it after plan section 8: an item that
 * `wake(source)` delivers is decided on with `next(event, showPlayed)`; if that is true,
 * `rebuild` says whether the 500-row query runs, `shownAs` which event the state is made from
 * and `rows` which rows it shows.
 *
 * RxJava runs for real; the pace runs on a `TestScheduler`. No sleeps, no wall clock. The page's
 * own 120 ms throttle and the on-screen gate are not in these tests (`FeedLivePageTest` and
 * `FeedLiveScaleTest` have the whole chain), so times here are exact multiples of the pace.
 */
class FeedLiveUpdateTest {

    private companion object {
        const val PACE = FeedLiveUpdate.PACE_MILLIS

        /** Longer than a pace period: after it, the pace operator's timer has stopped. */
        const val LONG = 10_000L
    }

    private val scheduler = TestScheduler()
    private val log = FeedRefreshLog()

    /** The page's combined stream, reduced to its event (`FeedViewModel.kt:44-55`). */
    private val source = PublishProcessor.create<FeedEventManager.Event>()

    /** The feed table. */
    private var table: List<Row> = emptyList()
    private var nextId = 1L

    /** A batch is stored: its rows are committed, then the marked line raises the count (plan 3.1). */
    private fun storeBatch(rows: Int = 1) {
        table = (1..rows).map { Row(nextId++) } + table
        log.batchStored()
    }

    private class Decision(
        val event: FeedEventManager.Event,
        val passed: Boolean,
        val rebuild: Boolean,
        val shownAs: FeedEventManager.Event?,
        val rows: List<Row>?,
        val at: Long
    )

    /** One page. With [auto] every delivered item is decided on at once; else when [decide] is called. */
    private inner class Hand(pageGroupId: Long = ALL, private val auto: Boolean = true) {
        val live = FeedLiveUpdate<Row, RowItem>(pageGroupId, log, PACE, scheduler)
        var showPlayed = true
        val delivered = mutableListOf<FeedEventManager.Event>()
        val decisions = mutableListOf<Decision>()
        var queries = 0
        private var subscription: Disposable? = null

        init {
            subscribe()
        }

        fun subscribe() {
            subscription = live.wake(source).subscribe {
                delivered += it
                if (auto) decide(it)
            }
        }

        fun unsubscribe() = subscription?.dispose()

        /** `FeedViewModel.kt:60-69` after the plan's edits. */
        fun decide(event: FeedEventManager.Event): Decision {
            val passed = live.next(event, showPlayed)
            val decision = if (passed) {
                val rebuild = live.rebuild
                val streamItems = if (rebuild) {
                    queries++
                    table.map { it.copy() }
                } else {
                    arrayListOf()
                }
                Decision(event, true, rebuild, live.shownAs(event), live.rows(streamItems), now())
            } else {
                Decision(event, false, live.rebuild, null, null, now())
            }
            decisions += decision
            return decision
        }

        val last: Decision get() = decisions.last()
        val rebuilds: List<Decision> get() = decisions.filter { it.passed && it.rebuild }
    }

    private fun now() = scheduler.now(TimeUnit.MILLISECONDS)

    /** A page that has loaded its list, with no refresh running and the pace timer at rest. */
    private fun loadedPage(pageGroupId: Long = ALL, auto: Boolean = true): Hand {
        val hand = Hand(pageGroupId, auto)
        source.onNext(IdleEvent)
        if (!auto) hand.decide(IdleEvent)
        scheduler.advanceMillis(LONG)
        return hand
    }

    // ---- constants ----

    /** AC-2: "the list is rebuilt at most once in two seconds". */
    @Test
    fun ac2_theRebuildPeriodIsTwoSeconds() {
        assertEquals(2_000L, FeedLiveUpdate.REBUILD_PERIOD_MILLIS)
    }

    /**
     * AC-2, plan 3.2: the page's own throttle can delay a wake-up by up to 120 ms, so two
     * rebuilds a pace apart could start 120 ms less than a pace apart. The pace is therefore
     * the period plus that throttle: 2120.
     */
    @Test
    fun ac2_thePaceIsThePeriodPlusThePagesThrottle() {
        assertEquals(FeedLiveUpdate.REBUILD_PERIOD_MILLIS + DEFAULT_THROTTLE_TIMEOUT, FeedLiveUpdate.PACE_MILLIS)
        assertEquals(2_120L, FeedLiveUpdate.PACE_MILLIS)
    }

    // ---- wake ----

    /** Plan 3.2: "Every item of `source` passes unchanged", whatever its type. */
    @Test
    fun wake_passesEveryItemOfTheSourceUnchangedAndInOrder() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        val strings = PublishProcessor.create<String>()

        val out = live.wake(strings).test()
        strings.onNext("a")
        strings.onNext("b")
        strings.onNext("b")
        scheduler.advanceMillis(1)
        strings.onNext("c")

        out.assertValues("a", "b", "b", "c").assertNotComplete().assertNoErrors()
    }

    /** Plan 3.2: before the source has an item there is nothing to deliver again; a stored batch alone emits nothing. */
    @Test
    fun wake_aBatchBeforeTheFirstItem_emitsNothing() {
        val hand = Hand()

        storeBatch()
        scheduler.advanceMillis(LONG)

        assertEquals(0, hand.delivered.size)
    }

    /**
     * AC-10, plan section 9: `wake` is inside the on-screen gate, and a page off screen has "no
     * subscription to the batch counts". Calling `wake` subscribes to nothing; its result
     * subscribes to the source and to the paced counts (one scheduler worker for
     * `throttleLatest`); cancelling it releases both.
     */
    @Test
    fun ac10_wake_holdsNothingUnlessSubscribed() {
        val pace = CountingScheduler(scheduler)
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, pace)

        val woken = live.wake(source)

        assertFalse("AC-10: wake() itself does not subscribe to the page's stream", source.hasSubscribers())
        assertEquals("AC-10: wake() itself starts no pace timer", 0, pace.created)

        val subscription = woken.test()
        assertTrue(source.hasSubscribers())
        assertTrue("the pace runs on the scheduler given to the constructor", pace.alive > 0)

        subscription.cancel()
        assertFalse("AC-10: leaving the screen unsubscribes from the page's stream", source.hasSubscribers())
        assertEquals("AC-10: and from the batch counts, pace timer included", 0, pace.alive)

        storeBatch()
        scheduler.advanceMillis(LONG)
        subscription.assertNoValues()
    }

    // ---- the pacing (AC-2, plan 3.2) ----

    /**
     * AC-2, the plan's first sequence, **progress, batch, batch, end**: the list is rebuilt on
     * the first paced count, the second batch waits for the period, the end always rebuilds
     * and is shown as the success event itself.
     */
    @Test
    fun ac2_progressBatchBatchEnd() {
        val hand = loadedPage()
        assertEquals("opening the page builds the list once", 1, hand.rebuilds.size)
        log.started(ALL)
        val tick = ProgressEvent(1, 40)

        source.onNext(tick)
        assertTrue("a concerned progress event is shown", hand.last.passed)
        assertFalse("AC-1: but costs no query", hand.last.rebuild)
        assertSame(tick, hand.last.shownAs)

        storeBatch()
        assertEquals("AC-2: the first stored batch rebuilds at once", 2, hand.rebuilds.size)
        assertSame("the page is woken with its latest event; no event changed", tick, hand.last.event)
        assertSame(tick, hand.last.shownAs)
        assertEquals("AC-2: the list shows the stored batch", table, hand.last.rows)

        scheduler.advanceMillis(100)
        storeBatch()
        scheduler.advanceMillis(PACE - 101)
        assertEquals("AC-2: the second batch waits until one pace after the first rebuild", 2, hand.rebuilds.size)

        scheduler.advanceMillis(1)
        assertEquals("AC-2: and is then shown", 3, hand.rebuilds.size)
        assertEquals(PACE, hand.rebuilds[2].at - hand.rebuilds[1].at)
        assertEquals(table, hand.last.rows)

        val end = SuccessResultEvent(listOf(IllegalStateException("channel gone")))
        scheduler.advanceMillis(50)
        source.onNext(end)
        assertEquals("AC-2, AC-8: the end rebuilds at once, whatever the pace", 4, hand.rebuilds.size)
        assertSame("AC-8: the state is made from the success event, with its errors", end, hand.last.shownAs)
        assertEquals(4, hand.queries)
    }

    /** AC-2: "at most one rebuild per period however fast batches arrive": 80 batches in 800 ms. */
    @Test
    fun ac2_howeverFastBatchesArrive_oneRebuildPerPace() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(0, SUBSCRIPTIONS))
        val before = hand.rebuilds.size

        repeat(BATCHES) {
            storeBatch()
            scheduler.advanceMillis(10)
        }
        assertEquals("AC-2: 80 batches inside one period: one rebuild so far", before + 1, hand.rebuilds.size)

        scheduler.advanceMillis(LONG)
        val during = hand.rebuilds.drop(before)
        assertEquals("AC-2: and one more when the period has ended, for the 79 that waited", 2, during.size)
        assertEquals(PACE, during[1].at - during[0].at)
        assertEquals("AC-2: the last rebuild shows every stored batch", BATCHES, during[1].rows!!.size)
    }

    /** AC-2: batches for a long time, faster than the pace: rebuilds exactly one pace apart, never closer. */
    @Test
    fun ac2_aSteadyStreamOfBatches_rebuildsExactlyOnePaceApart() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(0, SUBSCRIPTIONS))
        val before = hand.rebuilds.size

        repeat(200) {
            storeBatch()
            scheduler.advanceMillis(100) // 20 s of batches, 10 per second
        }
        scheduler.advanceMillis(LONG)

        val times = hand.rebuilds.drop(before).map { it.at }
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue("AC-2: no two rebuilds closer than the pace: $gaps", gaps.all { it >= PACE })
        // at once, then one per 2120 ms while batches keep coming (nine more up to 19 080 ms),
        // then one for the batches that were still waiting when the last one was stored
        assertEquals("AC-2: 200 batches in 20 s", 11, times.size)
        assertTrue("AC-2: exactly one pace apart while batches wait: $gaps", gaps.all { it == PACE })
        assertEquals("AC-2: the last rebuild shows every stored batch", 200, hand.last.rows!!.size)
    }

    /** AC-2: batches slower than the pace are each shown at once. */
    @Test
    fun ac2_batchesSlowerThanThePace_areEachShownAtOnce() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(0, SUBSCRIPTIONS))
        val before = hand.rebuilds.size

        repeat(5) {
            storeBatch()
            assertEquals("AC-2: \"an update follows each stored batch\"", before + it + 1, hand.rebuilds.size)
            assertEquals(now(), hand.last.at)
            scheduler.advanceMillis(PACE)
        }
    }

    /**
     * AC-2: "the last batch before the end is never lost". A batch that arrives while the pace
     * timer runs, with no event after it, is shown when the period ends, not earlier and not
     * never.
     */
    @Test
    fun ac2_theLastBatch_isShownWhenThePeriodEnds_withoutAnyFurtherEvent() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(39, 40))
        storeBatch()
        val before = hand.rebuilds.size
        scheduler.advanceMillis(1)

        storeBatch() // the last one; the loader now takes its time and posts nothing
        val last = table.first()

        scheduler.advanceMillis(PACE - 2)
        assertEquals(before, hand.rebuilds.size)
        scheduler.advanceMillis(1)
        assertEquals("AC-2: the last batch is shown one pace after the rebuild before it", before + 1, hand.rebuilds.size)
        assertTrue("AC-2: with its rows", hand.last.rows!!.contains(last))

        scheduler.advanceMillis(LONG)
        assertEquals("and once only", before + 1, hand.rebuilds.size)
    }

    /** AC-2: the same when the end comes first: the final list has the last batch, and nothing follows it. */
    @Test
    fun ac2_theLastBatch_isInTheFinalList_whenTheEndComesFirst() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(39, 40))
        storeBatch()
        scheduler.advanceMillis(1)
        storeBatch()
        val last = table.first()
        val before = hand.rebuilds.size

        scheduler.advanceMillis(500)
        source.onNext(SuccessResultEvent())

        assertEquals(before + 1, hand.rebuilds.size)
        assertTrue("AC-2: the final list has the last batch", hand.last.rows!!.contains(last))
        val decisions = hand.decisions.size
        val delivered = hand.delivered.size

        scheduler.advanceMillis(LONG)
        assertEquals("AC-2: \"nothing follows the final list\": the count that waited is dropped", delivered, hand.delivered.size)
        assertEquals(decisions, hand.decisions.size)
    }

    /** AC-2: a concerned success event always rebuilds: with nothing stored, and directly after a rebuild. */
    @Test
    fun ac2_aConcernedSuccess_alwaysRebuilds() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(1, 1))
        val before = hand.rebuilds.size

        source.onNext(SuccessResultEvent())
        assertEquals("with no batch stored at all (every channel was up to date)", before + 1, hand.rebuilds.size)

        // The stale result event of plan section 1: the page keeps it as its latest event, and every later item is another end.
        source.onNext(SuccessResultEvent())
        assertEquals("END rebuilds on every item, exactly as today (plan 5.2)", before + 2, hand.rebuilds.size)
    }

    @Test
    fun ac2_aConcernedSuccess_rebuildsDirectlyAfterABatchRebuild() {
        val hand = loadedPage()
        log.started(ALL)
        source.onNext(ProgressEvent(1, 1))
        storeBatch()
        val before = hand.rebuilds.size
        scheduler.advanceMillis(5)

        source.onNext(SuccessResultEvent())

        assertEquals("AC-2: the end is not paced", before + 1, hand.rebuilds.size)
        assertEquals(5L, hand.rebuilds[before].at - hand.rebuilds[before - 1].at)
    }

    /** AC-1, AC-2: "progress events alone cause no rebuild": 1600 ticks, one per channel. */
    @Test
    fun ac1_progressEventsAlone_causeNoRebuild() {
        val hand = loadedPage()
        log.started(ALL)
        val shown = hand.last.rows
        val before = hand.queries

        for (channel in 1..SUBSCRIPTIONS) {
            source.onNext(ProgressEvent(channel, SUBSCRIPTIONS))
            scheduler.advanceMillis(7)
            assertTrue("every tick is shown (the counts)", hand.last.passed)
            assertFalse(hand.last.rebuild)
            assertSame("AC-1: with the list as it was: the same rows object", shown, hand.last.rows)
        }

        assertEquals("AC-1: 1600 progress events, no query", before, hand.queries)
    }

    /**
     * Plan 3.2, "drop a wake-up for batches already in the list": a batch waits for the pace,
     * the user causes a rebuild meanwhile (the "show watched" switch), so the list already has
     * the batch when the period ends. Nothing is delivered then.
     */
    @Test
    fun ac2_aBatchAlreadyInTheList_doesNotWakeThePage() {
        val hand = loadedPage()
        log.started(ALL)
        val tick = ProgressEvent(1, 40)
        source.onNext(tick)
        storeBatch()
        scheduler.advanceMillis(100)
        storeBatch() // waits for the pace
        val before = hand.rebuilds.size

        hand.showPlayed = false
        source.onNext(tick) // the switch: the same event, combined with the new value
        assertEquals("a rebuild the user causes is done at once", before + 1, hand.rebuilds.size)
        assertEquals("and reads everything stored", table, hand.last.rows)
        val delivered = hand.delivered.size

        scheduler.advanceMillis(LONG)
        assertEquals("the waiting count is for a batch the list already shows: no wake-up", delivered, hand.delivered.size)
        assertEquals(before + 1, hand.rebuilds.size)
    }

    /** AC-2: after the end, a count still waiting in the pace operator emits nothing. */
    @Test
    fun ac2_afterTheEnd_aWaitingCountEmitsNothing() {
        val hand = loadedPage(auto = false)
        log.started(ALL)
        val tick = ProgressEvent(40, 40)
        source.onNext(tick)
        hand.decide(tick)
        storeBatch()
        hand.decide(hand.delivered.last())
        scheduler.advanceMillis(2_000)
        storeBatch() // waits; 120 ms of the period are left
        scheduler.advanceMillis(100)
        val end = SuccessResultEvent()
        source.onNext(end)

        val decision = hand.decide(end) // decided before the period ends
        assertTrue(decision.passed && decision.rebuild)
        val delivered = hand.delivered.size

        scheduler.advanceMillis(LONG)
        assertEquals("AC-2: nothing is rebuilt after the end for batches already in the list", delivered, hand.delivered.size)
    }

    /**
     * Risk 6 of the plan, accepted, **not a bug**: "if that count leaves the pace operator in
     * the up to 120 ms between the result event and its decision, the page gets the final state
     * twice, which is what a late Room emission causes today".
     *
     * The same sequence as [ac2_afterTheEnd_aWaitingCountEmitsNothing], but the period ends
     * while the result event is still on its way to its decision (in the page's 120 ms throttle,
     * or between two threads). The count is then for a batch the list does not have yet, so the
     * page is woken with its latest event, the result, a second time; each is an end, each
     * rebuilds. Nothing is lost and nothing follows the second.
     */
    @Test
    fun risk6_theFinalStateCanBeDeliveredTwice_whenThePeriodEndsBeforeTheResultIsDecidedOn() {
        val hand = loadedPage(auto = false)
        log.started(ALL)
        val tick = ProgressEvent(40, 40)
        source.onNext(tick)
        hand.decide(tick)
        storeBatch()
        hand.decide(hand.delivered.last())
        scheduler.advanceMillis(2_000)
        storeBatch() // waits; 120 ms of the period are left
        scheduler.advanceMillis(100)
        val end = SuccessResultEvent(listOf(IllegalStateException("channel gone")))
        source.onNext(end)
        val before = hand.delivered.size

        scheduler.advanceMillis(20) // the period ends; the result has not been decided on yet

        assertEquals("risk 6: the result event is delivered a second time", before + 1, hand.delivered.size)
        assertSame(end, hand.delivered[before - 1])
        assertSame(end, hand.delivered[before])

        val first = hand.decide(end)
        val second = hand.decide(end)
        assertTrue("risk 6: the first is the end, with a rebuild", first.passed && first.rebuild)
        assertTrue("risk 6: the second too: the final state twice", second.passed && second.rebuild)
        assertSame(end, first.shownAs)
        assertSame(end, second.shownAs)
        assertEquals("both show the same, final rows", first.rows, second.rows)
        assertEquals(table, second.rows)

        scheduler.advanceMillis(LONG)
        assertEquals("risk 6: and nothing follows", before + 1, hand.delivered.size)
    }

    // ---- what changes the page (plan 5.2: pageChanged) ----

    /** The first decision of a page builds its list, whatever the event: here a progress event of a running refresh. */
    @Test
    fun ac1_aPageOpenedDuringARefresh_buildsItsListWithTheFirstEvent() {
        log.started(ALL)
        table = listOf(Row(100), Row(101))
        val hand = Hand()
        val tick = ProgressEvent(5, 40)

        source.onNext(tick)

        assertTrue(hand.last.passed)
        assertTrue("the first item of a new page rebuilds", hand.last.rebuild)
        assertSame("and is shown as progress, with the list", tick, hand.last.shownAs)
        assertEquals(table, hand.last.rows)

        source.onNext(ProgressEvent(6, 40))
        assertFalse("the second does not", hand.last.rebuild)
        assertEquals(1, hand.queries)
    }

    /** Plan 3.2 and 5.2: "a new subscription: first load or back on screen" rebuilds once. */
    @Test
    fun ac10_aNewSubscriptionToWake_rebuildsOnce() {
        val hand = loadedPage()
        log.started(ALL)
        val tick = ProgressEvent(1, 40)
        source.onNext(tick)
        assertFalse(hand.last.rebuild)
        val before = hand.queries

        hand.unsubscribe() // off screen
        source.onNext(ProgressEvent(2, 40))
        scheduler.advanceMillis(LONG)
        assertEquals("AC-10: off screen: no decision, no query", before, hand.queries)

        hand.subscribe() // back on screen; no batch was stored meanwhile
        source.onNext(tick)
        assertTrue("AC-10: \"it shows the current state when it is returned to\"", hand.last.rebuild)

        source.onNext(tick)
        assertFalse("once", hand.last.rebuild)
        assertEquals(before + 1, hand.queries)
    }

    /** Plan 3.2: "A rebuild the user causes (... the 'show watched' switch) is done at once", during a refresh and inside a pace period. */
    @Test
    fun ac4_aChangeOfShowPlayed_rebuildsAtOnce() {
        val hand = loadedPage()
        log.started(ALL)
        val tick = ProgressEvent(1, 40)
        source.onNext(tick)
        storeBatch()
        val before = hand.queries
        scheduler.advanceMillis(10)

        hand.showPlayed = false
        source.onNext(tick)
        assertTrue(hand.last.rebuild)
        assertSame(tick, hand.last.shownAs)

        source.onNext(tick)
        assertFalse("the new value is the page's from then on", hand.last.rebuild)

        hand.showPlayed = true
        source.onNext(tick)
        assertTrue("and switching back rebuilds again", hand.last.rebuild)
        assertEquals(before + 2, hand.queries)
    }

    // ---- which refresh concerns the page (AC-7, plan section 4) ----

    /**
     * AC-7, the plan's sequence "refresh of group 9 on the page of group 7": the first item
     * loads the page as if nothing ran (shown as `IdleEvent`: no progress, no errors, no
     * `reset()`), then `next` is false for progress, batches, success and error.
     */
    @Test
    fun ac7_aGroupRefresh_seenFromAnotherGroupsPage_isNothing() {
        log.started(GROUP_B)
        val hand = Hand(GROUP_A)

        source.onNext(ProgressEvent(1, 40))
        assertTrue("the page opens: its list is loaded", hand.last.passed && hand.last.rebuild)
        assertSame("AC-7: as a page with no refresh", IdleEvent, hand.last.shownAs)

        source.onNext(ProgressEvent(2, 40))
        assertFalse("AC-7: no progress", hand.last.passed)
        assertFalse(hand.live.rebuild)

        scheduler.advanceMillis(LONG)
        storeBatch()
        storeBatch()
        scheduler.advanceMillis(LONG)
        assertTrue("AC-7: no reload for a stored batch", hand.decisions.drop(1).none { it.passed })
        assertFalse(hand.live.rebuild)

        source.onNext(ProgressEvent(PROCESSING))
        assertFalse(hand.last.passed)

        source.onNext(SuccessResultEvent(listOf(IllegalStateException("of group 9"))))
        assertFalse("AC-7: the result is not this page's: it posts nothing, so it does not consume it", hand.last.passed)
        assertFalse(hand.live.rebuild)

        source.onNext(ErrorResultEvent(IllegalStateException("of group 9")))
        assertFalse("AC-7: nor is the failure", hand.last.passed)

        assertEquals("AC-7: one query, for opening the page", 1, hand.queries)
    }

    /** AC-7: a page not concerned still obeys its user: the "show watched" switch reloads, shown as idle. */
    @Test
    fun ac7_aPageNotConcerned_stillReloadsForItsUser() {
        log.started(GROUP_B)
        val hand = Hand(GROUP_A)
        val foreign = SuccessResultEvent(listOf(IllegalStateException("of group 9")))
        source.onNext(foreign)
        assertSame(IdleEvent, hand.last.shownAs)
        source.onNext(foreign)
        assertFalse(hand.last.passed)

        hand.showPlayed = false
        source.onNext(foreign)

        assertTrue(hand.last.passed && hand.last.rebuild)
        assertSame("AC-7: without the foreign errors and without consuming the result", IdleEvent, hand.last.shownAs)
    }

    /** AC-7: "A refresh of a group concerns the 'All' page": the same refresh, seen from "All". */
    @Test
    fun ac7_aGroupRefresh_seenFromTheAllPage_isItsRefresh() {
        val hand = loadedPage(ALL)
        log.started(GROUP_B)
        val tick = ProgressEvent(1, 40)

        source.onNext(tick)
        assertTrue(hand.last.passed)
        assertSame("progress is shown", tick, hand.last.shownAs)

        storeBatch()
        assertTrue("batches rebuild", hand.last.rebuild)

        val end = SuccessResultEvent(listOf(IllegalStateException("of group 9")))
        source.onNext(end)
        assertTrue(hand.last.passed && hand.last.rebuild)
        assertSame("and the result is its result, errors included", end, hand.last.shownAs)
    }

    /** AC-7: a group refresh seen from that group's page. */
    @Test
    fun ac7_aGroupRefresh_seenFromThatGroupsPage_isItsRefresh() {
        val hand = loadedPage(GROUP_B)
        log.started(GROUP_B)
        val tick = ProgressEvent(1, 40)

        source.onNext(tick)
        assertSame(tick, hand.last.shownAs)
        storeBatch()
        assertTrue(hand.last.rebuild)
        val end = SuccessResultEvent()
        source.onNext(end)
        assertSame(end, hand.last.shownAs)
    }

    /** AC-7, answer A1: the notification check concerns the page of any group. */
    @Test
    fun ac7_theNotificationCheck_seenFromAGroupsPage_isItsRefresh() {
        val hand = loadedPage(GROUP_A)
        log.started(NOTIFICATIONS)
        val tick = ProgressEvent(1, 12)

        source.onNext(tick)
        assertSame(tick, hand.last.shownAs)
        storeBatch()
        assertTrue(hand.last.rebuild)
        val end = SuccessResultEvent()
        source.onNext(end)
        assertSame(end, hand.last.shownAs)
    }

    /** AC-8: the result is decided on after the loader has reported its end; it is still that refresh's result. */
    @Test
    fun ac8_theResult_decidedAfterEnded_stillBelongsToItsRefresh() {
        val mine = loadedPage(GROUP_B)
        val other = loadedPage(GROUP_A)
        log.started(GROUP_B)
        source.onNext(ProgressEvent(1, 1))
        log.ended()

        val end = SuccessResultEvent()
        source.onNext(end)

        assertSame(end, mine.last.shownAs)
        assertFalse(other.last.passed)
    }

    /**
     * Risk 7 of the plan, accepted: two refreshes at once, "events are attributed to the one
     * started last". The notification check starts while group 9 is refreshed: from then on the
     * page of group 7 takes the events as its own, also those of group 9's refresh.
     */
    @Test
    fun risk7_twoRefreshesAtOnce_eventsAreAttributedToTheOneStartedLast() {
        val hand = loadedPage(GROUP_A)
        log.started(GROUP_B)
        source.onNext(ProgressEvent(1, 40))
        assertFalse(hand.last.passed)

        log.started(NOTIFICATIONS)
        val tick = ProgressEvent(2, 40) // posted by group 9's refresh

        source.onNext(tick)
        assertTrue("risk 7: shown, because the refresh started last concerns this page", hand.last.passed)
        assertSame(tick, hand.last.shownAs)
    }

    // ---- error (AC-11) ----

    /** AC-11, plan 10.4: a failed refresh on a concerned page: the error event unchanged, no query. */
    @Test
    fun ac11_anError_onAConcernedPage_isPassedUnchangedWithoutRebuild() {
        val hand = loadedPage()
        val shown = hand.last.rows
        log.started(ALL)
        source.onNext(ProgressEvent(1, 40))
        val before = hand.queries
        val failure = ErrorResultEvent(IllegalStateException("database"))

        source.onNext(failure)

        assertTrue(hand.last.passed)
        assertFalse("no query for an error, as today", hand.last.rebuild)
        assertSame(failure, hand.last.shownAs)
        assertSame("the kept rows stay", shown, hand.last.rows)
        assertEquals(before, hand.queries)
    }

    // ---- rows and items (plan 5.2, "the other members") ----

    /** `rows`: "returns the kept rows (empty before the first rebuild)". */
    @Test
    fun rows_beforeTheFirstRebuild_areEmpty() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)

        assertEquals(emptyList<Row>(), live.rows(arrayListOf()))
    }

    /** `rows`: with a rebuild the queried list is kept; without one the view model passes an empty list and gets the kept one. */
    @Test
    fun ac1_rows_keepsTheListOfTheLastRebuild() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        val queried = listOf(Row(1), Row(2))

        assertTrue(live.next(IdleEvent, true))
        assertTrue(live.rebuild)
        assertSame(queried, live.rows(queried))

        log.started(ALL)
        assertTrue(live.next(ProgressEvent(1, 2), true))
        assertFalse(live.rebuild)
        assertSame("AC-1: the list as it was, not the empty list of a tick", queried, live.rows(arrayListOf()))
        assertSame(queried, live.rows(arrayListOf()))

        val requeried = listOf(Row(3), Row(1), Row(2))
        assertTrue(live.next(SuccessResultEvent(), true))
        assertSame("a rebuild replaces it", requeried, live.rows(requeried))
    }

    /** `shownAs`: the event itself, except for a page that is loaded as if nothing ran. */
    @Test
    fun shownAs_idleStaysIdle() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)

        assertTrue(live.next(IdleEvent, true))

        assertSame(IdleEvent, live.shownAs(IdleEvent))
    }

    /** AC-6, `items`: no rows, no list: `null`, which is the full-screen progress of today. */
    @Test
    fun ac6_items_ofNoRows_isNull() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        var created = 0

        assertNull(live.items(emptyList()) { created++; RowItem(it) })
        assertNull(live.items(arrayListOf()) { created++; RowItem(it) })
        assertEquals(0, created)
    }

    /** AC-1, `items`: one item per row, in order. */
    @Test
    fun ac1_items_ofRows_areTheirItemsInOrder() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        val rows = listOf(Row(3), Row(1), Row(2))

        val items = live.items(rows) { RowItem(it) }

        assertNotNull(items)
        assertEquals(3, items!!.size)
        for (i in rows.indices) assertSame("item $i is made from row $i", rows[i], items[i].row)
    }

    /** AC-10, plan 5.2: "remembered for as long as it is called with the same list instance, so 1600 progress ticks create no items". */
    @Test
    fun ac10_items_areRememberedForTheSameListInstance() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        val rows = (1L..500L).map { Row(it) }
        var created = 0

        val first = live.items(rows) { created++; RowItem(it) }
        repeat(SUBSCRIPTIONS) {
            assertSame("the same items for the same rows object", first, live.items(rows) { created++; RowItem(it) })
        }

        assertEquals("AC-10: 500 items once, not once per tick", 500, created)
    }

    /** AC-2: another list (a rebuild) gets its own items; an empty one drops to `null` again. */
    @Test
    fun ac2_items_ofAnotherList_areMadeFromThatList() {
        val live = FeedLiveUpdate<Row, RowItem>(ALL, log, PACE, scheduler)
        val before = listOf(Row(1), Row(2))
        val after = listOf(Row(3), Row(1), Row(2))

        val first = live.items(before) { RowItem(it) }!!
        val second = live.items(after) { RowItem(it) }!!

        assertNotSame(first, second)
        assertEquals(listOf(3L, 1L, 2L), second.map { it.row.id })
        assertSame(after[0], second[0].row)
        assertNull(live.items(emptyList()) { RowItem(it) })
        assertEquals(listOf(1L, 2L), live.items(before) { RowItem(it) }!!.map { it.row.id })
    }

    /** The defaults of plan section 2: the app's log, the default pace, RxJava's computation scheduler. Compiles = exists. */
    @Test
    fun theConstructorHasThePlansDefaults() {
        val live = FeedLiveUpdate<Row, RowItem>(GROUP_A)

        assertEquals(emptyList<Row>(), live.rows(arrayListOf()))
    }
}
