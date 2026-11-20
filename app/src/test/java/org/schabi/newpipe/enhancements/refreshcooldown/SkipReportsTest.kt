package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AC-8, AC-9, AC-10 (plan 4.4 and 6.1): what a refresh skipped, held for the page that shows
 * the end of that refresh. The loader publishes when the loading has ended; the page of the
 * same feed takes the report once, within 60 seconds; the next refresh replaces it.
 *
 * Times are the caller's (`SystemClock.elapsedRealtime()` in the glue); the tests pass numbers.
 *
 * Not shown here: the snackbar, its 8 seconds, its place after an error message, and that the
 * action starts the service with the report's ids (`SkippedMessageUi`, glue: device steps 8 to 10).
 */
class SkipReportsTest {

    private val t = 5_000_000L

    private fun holding(groupId: Long, vararg ids: Long): SkipReports = SkipReports().apply { publish(groupId, ids.toList(), t) }

    // ---- 4.4, row by row ----

    /** AC-10, plan 4.4 row 1: a refresh that skipped nothing publishes nothing, and takes away what an earlier refresh left. */
    @Test
    fun ac10_publishAnEmptyList_holdsNothing() {
        val fresh = SkipReports()
        fresh.publish(7, emptyList(), t)
        assertNull("AC-10: skipped nothing: no report, so no message", fresh.take(7, t))

        val replaced = holding(7, 3, 5)
        replaced.publish(7, emptyList(), t + 10)
        assertNull("an earlier refresh's report is gone", replaced.take(7, t + 11))

        val otherFeed = holding(7, 3, 5)
        otherFeed.publish(9, emptyList(), t + 10)
        assertNull("also when the refresh that skipped nothing was of another feed", otherFeed.take(7, t + 11))
        assertNull(otherFeed.take(9, t + 11))
    }

    /** AC-8, plan 4.4 row 2: a refresh that skipped channels publishes their ids and its feed. */
    @Test
    fun ac8_publish_holdsTheReport() {
        val report = holding(7, 3, 5).take(7, t + 1)

        assertEquals(SkipReport(7, listOf(3L, 5L)), report)
        assertEquals(7L, report!!.groupId)
        assertEquals("AC-9: exactly the skipped ids, in the refresh's order", listOf(3L, 5L), report.subscriptionIds)
        assertEquals("AC-8: \"says how many were skipped\"", 2, report.count)
    }

    /** Plan 4.4 row 3 and 6.1: the start of a manual refresh clears the report of an earlier one. */
    @Test
    fun ac8_clear_holdsNothing() {
        val reports = holding(7, 3, 5)
        reports.clear()

        assertNull(reports.take(7, t + 1))
        assertNull(reports.take(ALL, t + 1))
        SkipReports().clear()
    }

    /** Plan 4.4 row 4: nothing held, nothing given, for any feed at any time. */
    @Test
    fun ac10_take_nothingHeld_isNull() {
        val reports = SkipReports()

        for (group in listOf(ALL, 0L, 7L, -2L)) for (now in listOf(0L, t, Long.MAX_VALUE / 2)) assertNull(reports.take(group, now))
    }

    /** AC-8, plan 4.4 rows 5 and 6, Q5: the report is offered for 60 seconds after the end of the refresh: at 60 000 ms yes, at 60 001 ms no. */
    @Test
    fun ac8_take_within60Seconds_notAfter() {
        assertNotNull("at once", holding(7, 3, 5).take(7, t))
        assertNotNull("after 59.999 s", holding(7, 3, 5).take(7, t + 59_999))
        assertNotNull("after exactly 60 s", holding(7, 3, 5).take(7, t + 60_000))
        assertNull("after 60.001 s", holding(7, 3, 5).take(7, t + 60_001))
        assertNull("after an hour", holding(7, 3, 5).take(7, t + 3_600_000))
    }

    /** Plan 4.4 row 6, "Held afterwards: nothing": a report that was asked for too late is gone, also for an earlier time stamp. */
    @Test
    fun ac8_take_tooLate_dropsTheReport() {
        val reports = holding(7, 3, 5)

        assertNull(reports.take(7, t + 60_001))
        assertNull("it does not come back", reports.take(7, t + 1))
    }

    /** AC-8 ("shown once"), plan 4.4 row 5 "Held afterwards: nothing": the report is given once. */
    @Test
    fun ac8_take_givesTheReportOnce() {
        val reports = holding(7, 3, 5)

        assertNotNull(reports.take(7, t + 1))
        assertNull("AC-8: the second take gets nothing", reports.take(7, t + 1))
        assertNull(reports.take(7, t + 2))
        assertNull(reports.take(ALL, t + 2))
    }

    /**
     * AC-8 ("on the page of the feed that was refreshed"), plan 4.4 rows 7 to 9: a page of
     * another feed gets nothing and leaves the report for the right page. "All" is a feed like
     * any other here: it does not get a group's report, and a group's page does not get "All"'s.
     */
    @Test
    fun ac8_take_onlyThePageOfTheSameFeed() {
        val group = holding(7, 3, 5)
        assertNull("the page of group 9", group.take(9, t + 1))
        assertNull("the page of \"All\"", group.take(ALL, t + 1))
        assertNull(group.take(0, t + 1))
        assertEquals("the report is still there for the page of group 7", SkipReport(7, listOf(3L, 5L)), group.take(7, t + 2))

        val all = holding(ALL, 3, 5)
        assertNull("a group's page does not get the report of \"All\"", all.take(7, t + 1))
        assertEquals("the page of \"All\" does", SkipReport(ALL, listOf(3L, 5L)), all.take(ALL, t + 1))
        assertNull(all.take(ALL, t + 1))
    }

    /** AC-8, Q5 ("A later refresh replaces it"): the next refresh's report replaces the held one, with its own feed, ids and 60 seconds. */
    @Test
    fun ac8_publish_replacesTheEarlierReport() {
        val reports = holding(7, 3, 5)
        reports.publish(9, listOf(8L), t + 50_000)

        assertNull("the page of the earlier refresh gets nothing any more", reports.take(7, t + 50_001))
        assertEquals("the 60 seconds count from the later refresh", SkipReport(9, listOf(8L)), reports.take(9, t + 110_000))

        val sameFeed = holding(7, 3, 5)
        sameFeed.publish(7, listOf(4L), t + 100)
        assertEquals("the same feed again: only the later refresh's channels", SkipReport(7, listOf(4L)), sameFeed.take(7, t + 101))
        assertNull(sameFeed.take(7, t + 102))

        val late = holding(7, 3, 5)
        late.publish(7, listOf(4L), t + 50_000)
        assertNull("too late for the later refresh too", late.take(7, t + 110_001))
    }

    /** AC-8: a taken or expired report does not stand in the way of the next refresh's. */
    @Test
    fun ac8_publishAfterTakeOrClear_holdsTheNewReport() {
        val reports = holding(7, 3, 5)
        assertNotNull(reports.take(7, t))
        reports.publish(7, listOf(6L), t + 5)
        assertEquals(SkipReport(7, listOf(6L)), reports.take(7, t + 6))

        reports.clear()
        reports.publish(ALL, listOf(1L, 2L, 3L), t + 10)
        assertEquals(3, reports.take(ALL, t + 11)!!.count)
    }

    /** Plan section 4: the time a report is offered for is the constructor's, 60 seconds by default. */
    @Test
    fun ac8_customOfferMillis() {
        fun custom(millis: Long) = SkipReports(millis).apply { publish(7, listOf(3L), t) }

        assertNotNull(custom(1000).take(7, t + 1000))
        assertNull(custom(1000).take(7, t + 1001))
        assertNotNull(custom(0).take(7, t))
        assertNull(custom(0).take(7, t + 1))
        assertNotNull("longer than the default", custom(600_000).take(7, t + 60_001))
    }

    // ---- the message's count ----

    /**
     * AC-8, AC-10, plan 4.4: "The message for 0, 1 and many skipped channels: 0 never becomes a
     * report, so no message; otherwise the plural with `report.count`". The texts for 1 and
     * many are in `RefreshCooldownResourcesTest`.
     */
    @Test
    fun ac10_count_zeroIsNoReport_oneAndManyAreTheNumberOfChannels() {
        val none = SkipReports().apply { publish(ALL, emptyList(), t) }
        assertNull("0 skipped: no report", none.take(ALL, t))

        assertEquals("1 skipped", 1, holding(ALL, 42).take(ALL, t)!!.count)
        assertEquals("2 skipped", 2, holding(ALL, 42, 43).take(ALL, t)!!.count)

        val many = (1L..SUBSCRIPTIONS).toList()
        val report = SkipReports().apply { publish(ALL, many, t) }.take(ALL, t)!!
        assertEquals("1600 skipped", SUBSCRIPTIONS, report.count)
        assertEquals("AC-9: all 1600 ids, for \"Refresh anyway\"", many, report.subscriptionIds)
        assertEquals(0, SkipReport(7, emptyList()).count)
    }

    /** AC-9: the ids of a report are what `forced` takes: the round trip from a refresh's skipped list to the next refresh's forced set. */
    @Test
    fun ac9_theReportsIdsAreTheForcedSet() {
        val scope = (1..20L).map { Channel(it) }
        val data = FakeData(last = scope.filter { it.id % 3 == 0L }.associate { it.id to NOW - 1000 })
        val skipped = select(scope, data, 300).skipped
        val reports = SkipReports().apply { publish(7, skipped, t) }

        val report = reports.take(7, t + 1)!!
        val anyway = CooldownRules.forced(scope, { it.id }, report.subscriptionIds.toLongArray())

        assertEquals(listOf(3L, 6L, 9L, 12L, 15L, 18L), report.subscriptionIds)
        assertEquals(report.subscriptionIds, anyway.load.map { it.id })
        // AC-10, plan 6.3: the forced refresh "publishes an empty report: no message".
        reports.publish(7, anyway.skipped, t + 2)
        assertNull(reports.take(7, t + 3))
    }

    // ---- constants ----

    /** Plan section 4 and 6.2: the constants, and that the wait after an error message is longer than that message stays (`LENGTH_LONG`, 2750 ms). */
    @Test
    fun ac8_constants() {
        assertEquals(60_000L, SkipReports.OFFER_MILLIS)
        assertEquals("AC-8: \"goes away by itself\": 8 seconds", 8_000, SkipReports.MESSAGE_MILLIS)
        assertEquals(3_500L, SkipReports.ERROR_MESSAGE_WAIT_MILLIS)
        assertTrue("AC-8: \"those come first\": the wait outlasts the error message", SkipReports.ERROR_MESSAGE_WAIT_MILLIS > 2750)
        assertTrue("and the report is still offered when the wait is over", SkipReports.OFFER_MILLIS > SkipReports.ERROR_MESSAGE_WAIT_MILLIS)
        assertEquals("org.schabi.newpipe.enhancements.refreshcooldown.FORCED", SkipReports.EXTRA_FORCED)
    }

    /** Plan section 4: one holder for the app, shared by the loader and the pages; it offers for the default 60 seconds. */
    @Test
    fun ac8_app_isOneHolder() {
        assertSame(SkipReports.app, SkipReports.app)
        assertSame("`@JvmField`: a static field, as the marked lines' glue reads it", SkipReports.app, SkipReports::class.java.getField("app").get(null))
    }

    // ---- threads ----

    /**
     * AC-8 ("shown once"), plan 4.4 ("all three functions synchronized"): the loader publishes
     * on a background thread, pages take on the main thread. With real threads: 2000 reports,
     * each asked for by four threads at the same moment: every report is given to exactly one.
     *
     * A pass shows this for the runs made; a race that never happened in them is not excluded.
     */
    @Test
    fun ac8_threads_fourTakersAtOnce_exactlyOneGetsEachReport() {
        val takers = 4
        val rounds = 2000
        val reports = SkipReports()
        val pool = Executors.newFixedThreadPool(takers)
        val failure = AtomicReference<Throwable>()
        try {
            for (round in 1..rounds) {
                reports.publish(7, listOf(round.toLong(), -round.toLong()), t)
                val barrier = CyclicBarrier(takers)
                val done = CountDownLatch(takers)
                val given = AtomicInteger()
                repeat(takers) {
                    pool.execute {
                        try {
                            barrier.await(10, TimeUnit.SECONDS)
                            val report = reports.take(7, t + 1)
                            if (report != null) {
                                given.incrementAndGet()
                                if (report != SkipReport(7, listOf(round.toLong(), -round.toLong()))) throw AssertionError("round $round: got $report")
                            }
                        } catch (e: Throwable) {
                            failure.compareAndSet(null, e)
                        } finally {
                            done.countDown()
                        }
                    }
                }
                assertTrue("round $round: the takers finish", done.await(20, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("round $round: $it", it) }
                assertEquals("round $round: the report is given exactly once", 1, given.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Plan 4.4: publishing, clearing and taking from several threads at once: nothing throws,
     * every report a page gets is one that was published whole (its feed and its ids belong
     * together) for that page's feed, and none is given twice.
     */
    @Test
    fun ac8_threads_publishClearAndTakeAtOnce_reportsStayWholeAndAreGivenOnce() {
        val reports = SkipReports()
        val perPublisher = 20_000
        val publishers = 2
        val takers = 3
        val start = CountDownLatch(1)
        val publishing = CountDownLatch(publishers)
        val failure = AtomicReference<Throwable>()
        val taken = java.util.concurrent.ConcurrentLinkedQueue<SkipReport>()
        val threads = ArrayList<Thread>()

        fun thread(body: () -> Unit) = threads.add(
            Thread {
                try {
                    start.await()
                    body()
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }.apply { start() }
        )

        // Publisher p publishes for feed p + 1 the reports (n, n + 1, n + 2) with n = 10 * k + p, each once.
        repeat(publishers) { p ->
            thread {
                try {
                    for (k in 1..perPublisher) {
                        val n = 10L * k + p
                        reports.publish(p + 1L, listOf(n, n + 1, n + 2), t)
                        if (k % 97 == 0) reports.clear()
                        if (k % 89 == 0) reports.publish(p + 1L, emptyList(), t)
                    }
                } finally {
                    publishing.countDown()
                }
            }
        }
        repeat(takers) { index ->
            thread {
                var spins = 0L
                while (publishing.count > 0 || spins++ < 100) {
                    reports.take(1L + index % publishers, t + 1)?.let { taken.add(it) }
                }
            }
        }
        start.countDown()
        for (thread in threads) {
            thread.join(60_000)
            assertTrue("the threads finish", !thread.isAlive)
        }

        failure.get()?.let { throw AssertionError("a thread failed: $it", it) }
        for (report in taken) {
            val n = report.subscriptionIds.first()
            assertEquals("a report is whole: $report", listOf(n, n + 1, n + 2), report.subscriptionIds)
            assertEquals("its feed is its publisher's: $report", n % 10 + 1, report.groupId)
            assertEquals(3, report.count)
        }
        assertEquals("no report is given twice", taken.size, taken.map { it.subscriptionIds.first() }.toSet().size)
    }
}
