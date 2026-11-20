package org.schabi.newpipe.enhancements.feedliveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.service.FeedLoadManager
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * AC-2, AC-7, AC-11 (plan sections 2, 3.1 and 4): what the loader reports. Which refresh
 * concerns which page, how many batches were stored, and whether a refresh is running that a
 * page is not concerned by. Each test uses its own `FeedRefreshLog()`.
 */
class FeedRefreshLogTest {

    private val log = FeedRefreshLog()

    // ---- the terms of the spec: "a refresh concerns a feed page" (plan section 4, the table) ----

    /** AC-7, table row 1: "when it is a refresh of 'All'" it concerns every page. */
    @Test
    fun ac7_concerns_aRefreshOfAll_concernsEveryPage() {
        assertTrue("refresh of All, page All", FeedRefreshLog.concerns(ALL, ALL))
        assertTrue("refresh of All, page of group 7", FeedRefreshLog.concerns(ALL, GROUP_A))
        assertTrue("refresh of All, page of group 9", FeedRefreshLog.concerns(ALL, GROUP_B))
    }

    /** AC-7, table row 2: a refresh of group 7 concerns its own page and "All", not group 9's page. */
    @Test
    fun ac7_concerns_aRefreshOfAGroup_concernsThatGroupsPage() {
        assertTrue("refresh of group 7, page of group 7", FeedRefreshLog.concerns(GROUP_A, GROUP_A))
        assertTrue("refresh of group 9, page of group 9", FeedRefreshLog.concerns(GROUP_B, GROUP_B))
    }

    /** AC-7: "A refresh of group A does not concern the page of group B", in both directions. */
    @Test
    fun ac7_concerns_aRefreshOfAGroup_doesNotConcernAnotherGroupsPage() {
        assertFalse("refresh of group 7, page of group 9", FeedRefreshLog.concerns(GROUP_A, GROUP_B))
        assertFalse("refresh of group 9, page of group 7", FeedRefreshLog.concerns(GROUP_B, GROUP_A))
        assertFalse("group ids are compared as numbers, not by sign or size", FeedRefreshLog.concerns(1L, 2L))
        assertFalse(FeedRefreshLog.concerns(Long.MAX_VALUE, 1L))
    }

    /** AC-7: "A refresh of a group concerns the 'All' page, because that page shows the group's channels too." */
    @Test
    fun ac7_concerns_aRefreshOfAGroup_concernsTheAllPage() {
        assertTrue("refresh of group 7, page All", FeedRefreshLog.concerns(GROUP_A, ALL))
        assertTrue("refresh of group 9, page All", FeedRefreshLog.concerns(GROUP_B, ALL))
    }

    /** AC-7, answer A1, table row 3: the background check for new videos "concerns every page". */
    @Test
    fun ac7_concerns_theNotificationCheck_concernsEveryPage() {
        assertTrue("notification check, page All", FeedRefreshLog.concerns(NOTIFICATIONS, ALL))
        assertTrue("notification check, page of group 7", FeedRefreshLog.concerns(NOTIFICATIONS, GROUP_A))
        assertTrue("notification check, page of group 9", FeedRefreshLog.concerns(NOTIFICATIONS, GROUP_B))
    }

    /**
     * The asymmetry of the table: `-2` is special as a refresh only. No page has that id, and a
     * page is not concerned by a group's refresh for having an unusual id.
     */
    @Test
    fun ac7_concerns_isNotSymmetric() {
        assertFalse("a refresh of group 7 seen from a page with the id of the notification check", FeedRefreshLog.concerns(GROUP_A, NOTIFICATIONS))
    }

    /** Plan section 2: the two constants are upstream's ids, so that `started(groupId)` can be compared. */
    @Test
    fun ac7_theConstantsAreUpstreamsGroupIds() {
        assertEquals(-1L, FeedRefreshLog.ALL)
        assertEquals(-2L, FeedRefreshLog.NOTIFICATIONS)
        assertEquals(FeedGroupEntity.GROUP_ALL_ID, FeedRefreshLog.ALL)
        assertEquals(FeedLoadManager.GROUP_NOTIFICATION_ENABLED, FeedRefreshLog.NOTIFICATIONS)
    }

    /** Plan section 2: "one instance for the app", `@JvmField val app`: a public static field, always the same object. */
    @Test
    fun theAppHasOneLog() {
        val field = FeedRefreshLog::class.java.getField("app")
        assertTrue("FeedRefreshLog.app is a static field (@JvmField in the companion)", Modifier.isStatic(field.modifiers))
        assertSame(FeedRefreshLog.app, field.get(null))
        assertSame(FeedRefreshLog.app, FeedRefreshLog.app)
    }

    // ---- started / ended ----

    /** Plan section 4, table row 1: before any refresh the group is "All", which concerns every page. */
    @Test
    fun ac7_beforeAnyRefresh_theGroupIsAll() {
        assertEquals(FeedRefreshLog.ALL, log.groupId)
        assertEquals(0L, log.storedCount)
        assertFalse("nothing is running", log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(ALL))
    }

    @Test
    fun ac7_started_setsTheGroup() {
        log.started(GROUP_A)
        assertEquals(GROUP_A, log.groupId)

        log.ended()
        log.started(NOTIFICATIONS)
        assertEquals(NOTIFICATIONS, log.groupId)
    }

    /**
     * AC-8: the result event is decided on by the page after the loader's chain has terminated
     * (`ended()` runs in `doFinally`, the page's stream is throttled and on another thread). The
     * group must still be the one of that refresh then: "group of the refresh started last".
     */
    @Test
    fun ac8_ended_keepsTheGroupOfTheRefreshStartedLast() {
        log.started(GROUP_B)
        log.ended()

        assertEquals("the result of group 9's refresh is still attributed to group 9", GROUP_B, log.groupId)
        assertTrue(FeedRefreshLog.concerns(log.groupId, GROUP_B))
        assertFalse(FeedRefreshLog.concerns(log.groupId, GROUP_A))
    }

    /** AC-11, plan 6.3: `runningElsewhere` = a refresh runs, and it does not concern the page. */
    @Test
    fun ac11_runningElsewhere_falseWithNothingRunning() {
        assertFalse(log.runningElsewhere(ALL))
        assertFalse(log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(GROUP_B))
    }

    @Test
    fun ac11_runningElsewhere_falseForAConcernedPage() {
        log.started(GROUP_A)

        assertFalse("group 7's own page: its pull is handled as today", log.runningElsewhere(GROUP_A))
        assertFalse("the All page is concerned by a group's refresh", log.runningElsewhere(ALL))
    }

    /** AC-11: "A refresh asked for on a page that the running refresh does not concern is not started ... the pull indicator goes back". */
    @Test
    fun ac11_runningElsewhere_trueForAnotherGroupsPage() {
        log.started(GROUP_A)

        assertTrue("group 9's page while group 7 is refreshed", log.runningElsewhere(GROUP_B))
    }

    @Test
    fun ac11_runningElsewhere_falseWhileAllOrTheNotificationCheckRuns() {
        log.started(ALL)
        assertFalse(log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(ALL))
        log.ended()

        log.started(NOTIFICATIONS)
        assertFalse(log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(GROUP_B))
        assertFalse(log.runningElsewhere(ALL))
    }

    @Test
    fun ac11_runningElsewhere_falseAgainAfterTheEnd() {
        log.started(GROUP_A)
        log.ended()

        assertFalse("nothing runs: a pull on group 9's page starts its refresh as today", log.runningElsewhere(GROUP_B))
    }

    /** Plan section 2: `ended()` is "running - 1, not below 0". An `ended()` too many must not hide the next refresh. */
    @Test
    fun ac11_ended_neverCountsBelowZero() {
        log.ended()
        log.ended()
        assertFalse(log.runningElsewhere(GROUP_B))

        log.started(GROUP_A)

        assertTrue("one started() after two stray ended(): a refresh is running", log.runningElsewhere(GROUP_B))
        log.ended()
        assertFalse(log.runningElsewhere(GROUP_B))
    }

    /**
     * Plan section 4 and risk 7, two refreshes at once (the notification check during a manual
     * refresh): "the group of the one started last is used", and something is running until
     * both have ended.
     */
    @Test
    fun risk7_twoRefreshesAtOnce_theOneStartedLastCounts() {
        log.started(GROUP_A)
        assertTrue(log.runningElsewhere(GROUP_B))

        log.started(NOTIFICATIONS)

        assertEquals(NOTIFICATIONS, log.groupId)
        assertFalse("the notification check concerns group 9's page", log.runningElsewhere(GROUP_B))

        log.ended() // one of the two; the log cannot tell which
        assertEquals("still attributed to the one started last", NOTIFICATIONS, log.groupId)
        assertFalse(log.runningElsewhere(GROUP_B))

        log.ended()
        assertFalse(log.runningElsewhere(GROUP_B))
        assertEquals(NOTIFICATIONS, log.groupId)
    }

    /** Risk 7, the other order: a group refresh started during another group's refresh. */
    @Test
    fun risk7_twoRefreshesAtOnce_stillRunningAfterTheFirstEnd() {
        log.started(GROUP_A)
        log.started(GROUP_B)
        assertEquals(GROUP_B, log.groupId)
        assertTrue("group 7's page is now told that group 9 is being refreshed", log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(GROUP_B))

        log.ended()

        assertTrue("one of the two still runs, attributed to group 9", log.runningElsewhere(GROUP_A))
        assertFalse(log.runningElsewhere(GROUP_B))

        log.ended()
        assertFalse(log.runningElsewhere(GROUP_A))
    }

    // ---- batchStored / stored ----

    /** AC-2, plan 3.1: `stored()` gives "the current count at once, then every new count". */
    @Test
    fun ac2_stored_givesTheCurrentCountAtOnce() {
        log.stored().test().assertValues(0L).assertNotComplete().assertNoErrors()

        log.batchStored()
        log.batchStored()

        log.stored().test().assertValues(2L)
        assertEquals(2L, log.storedCount)
    }

    @Test
    fun ac2_stored_givesEveryLaterCount() {
        val counts = log.stored().test()

        repeat(5) { log.batchStored() }

        counts.assertValues(0L, 1L, 2L, 3L, 4L, 5L).assertNotComplete().assertNoErrors()
    }

    /** Each page has its own subscription; one leaving does not end the other's. */
    @Test
    fun ac2_stored_severalSubscribers() {
        val first = log.stored().test()
        log.batchStored()
        val second = log.stored().test()
        log.batchStored()
        first.cancel()
        log.batchStored()

        first.assertValues(0L, 1L, 2L)
        second.assertValues(1L, 2L, 3L)
    }

    /**
     * Plan 3.2: "The count is raised after the commit and read before the query", and
     * `batchStored()` is "count + 1, then announced". A page that reads `storedCount` when it is
     * woken by count n must read at least n, or it would mark its list as older than it is.
     */
    @Test
    fun ac2_storedCount_isAlreadyRaisedWhenTheCountIsAnnounced() {
        val readWhenAnnounced = mutableListOf<Pair<Long, Long>>()
        log.stored().subscribe { readWhenAnnounced += it to log.storedCount }

        repeat(3) { log.batchStored() }

        assertEquals(listOf(0L to 0L, 1L to 1L, 2L to 2L, 3L to 3L), readWhenAnnounced)
    }

    /** The count is "batches stored since the process started": a refresh starting or ending does not reset it. */
    @Test
    fun ac2_storedCount_isNotResetByARefresh() {
        log.started(ALL)
        log.batchStored()
        log.batchStored()
        log.ended()
        log.started(GROUP_A)
        log.batchStored()

        assertEquals(3L, log.storedCount)
        log.stored().test().assertValues(3L)
    }

    /**
     * Plan section 2: `batchStored()` is "synchronized". The loader stores from one thread at a
     * time, but the notification check can run beside a manual refresh (risk 7). Real threads:
     * no count is lost, every subscriber sees the counts in order without a gap, and the count
     * read while a number is announced is never behind it.
     */
    @Test
    fun ac2_batchStored_fromSeveralThreads_losesNothingAndAnnouncesInOrder() {
        val threads = 8
        val perThread = 2_000
        val seen = CopyOnWriteArrayList<Long>()
        val behind = CopyOnWriteArrayList<String>()
        log.stored().subscribe { count ->
            seen += count
            val read = log.storedCount
            if (read < count) behind += "announced $count, storedCount $read"
        }
        val go = CountDownLatch(1)

        val workers = (1..threads).map {
            thread {
                go.await()
                repeat(perThread) { log.batchStored() }
            }
        }
        go.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val total = (threads * perThread).toLong()
        assertEquals("no batch is lost", total, log.storedCount)
        assertEquals("every count is announced once, in order", (0L..total).toList(), seen.toList())
        assertEquals(emptyList<String>(), behind.toList())
        log.stored().test().assertValues(total)
    }

    /**
     * `started()` runs on the thread that subscribes to the loader's chain and `ended()` on the
     * one that terminates or disposes it (plan section 4), so the two come from different
     * threads. Assumed here (question Q7): the running count survives that. Real threads; after
     * as many `ended()` as `started()` nothing is running, and the next refresh is seen.
     */
    @Test
    fun ac11_startedAndEnded_fromSeveralThreads_balance() {
        val threads = 8
        val perThread = 2_000
        val go = CountDownLatch(1)
        val allStarted = CountDownLatch(threads)
        val endNow = CountDownLatch(1)

        val workers = (1..threads).map {
            thread {
                go.await()
                repeat(perThread) { log.started(GROUP_A) }
                allStarted.countDown()
                endNow.await()
                repeat(perThread) { log.ended() }
            }
        }
        go.countDown()
        assertTrue(allStarted.await(30, TimeUnit.SECONDS))
        assertTrue(log.runningElsewhere(GROUP_B))
        endNow.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertFalse("${threads * perThread} started(), as many ended(): nothing is running", log.runningElsewhere(GROUP_B))
        log.started(GROUP_A)
        assertTrue(log.runningElsewhere(GROUP_B))
        log.ended()
        assertFalse(log.runningElsewhere(GROUP_B))
    }
}
