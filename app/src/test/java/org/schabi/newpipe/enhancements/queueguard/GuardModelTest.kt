package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The model of plan 3.1 (`GuardModel.kt`): what "something is playing", "a queue worth guarding" and
 * "current item" mean, the record of the videos the player added by itself ([AutoQueuedRecord]), and the
 * two pure functions behind *Play all next* (AC-11) and the "Show stream details" entry (AC-17).
 */
class GuardModelTest {

    // ---- "Something is playing" (spec, Terms): a player is open and holds at least one item ----

    @Test
    fun ac1_ac7_somethingIsPlayingWhenAnOpenPlayerHoldsAtLeastOneItem() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind with one item", playing(kind, queueSize = 1).somethingPlaying)
            assertTrue("$kind with several items", playing(kind, queueSize = 25).somethingPlaying)
        }
    }

    @Test
    fun ac7_nothingIsPlayingWhileNoPlayerIsOpen() {
        assertFalse("IDLE", PlayerState.IDLE.somethingPlaying)
        val noPlayerButStaleQueueData =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("kind == null decides, whatever the other fields say", noPlayerButStaleQueueData.somethingPlaying)
    }

    @Test
    fun ac7_nothingIsPlayingWhileTheOpenPlayerHoldsNoItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind with an empty queue", openButEmpty(kind).somethingPlaying)
        }
    }

    @Test
    fun ac7_idleIsNoPlayerNoQueueNoCurrentItem() {
        assertEquals(
            PlayerState(kind = null, queueSize = 0, currentServiceId = -1, currentUrl = null),
            PlayerState.IDLE,
        )
    }

    // ---- "A queue worth guarding" (spec, Terms; answers A11 and A13) ----
    //
    // Plan 3.1, the table of `somethingPlaying` and `worthGuarding`, one test per line. A queue is
    // written queueSize/autoQueued; queueSize - autoQueued is the number of videos the user put there.

    private fun main(queueSize: Int, autoQueued: Int = 0) =
        playing(PlayerKind.MAIN, queueSize = queueSize, autoQueued = autoQueued)

    /** Line 1: "no player, or `queueSize == 0`" is not worth guarding, whatever `autoQueuedCount` says. */
    @Test
    fun ac7_ac14_noQueueIsWorthGuardingWhileNothingIsPlaying() {
        for (state in NOTHING_PLAYING) {
            for (autoQueued in listOf(0, 1, 5)) {
                val withCount = state.copy(autoQueuedCount = autoQueued)
                assertFalse("$withCount", withCount.worthGuarding)
            }
        }
        for (autoQueued in listOf(0, 1, 3)) {
            val noPlayerButStaleQueueData = PlayerState(
                kind = null,
                queueSize = 3,
                currentServiceId = SERVICE,
                currentUrl = CURRENT_URL,
                autoQueuedCount = autoQueued,
            )
            assertFalse("$noPlayerButStaleQueueData", noPlayerButStaleQueueData.worthGuarding)
        }
    }

    /** Line 2: "`BACKGROUND` or `POPUP`, `queueSize >= 1`, any `autoQueuedCount`" is worth guarding. */
    @Test
    fun ac1_ac10_everyQueueOfTheBackgroundAndThePopupPlayerIsWorthGuarding() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 2, 3, 25)) {
                for (autoQueued in 0..queueSize) {
                    val state = playing(kind, queueSize = queueSize, autoQueued = autoQueued)
                    assertTrue("$state", state.worthGuarding)
                }
            }
        }
    }

    /** Line 3: "`MAIN`, `queueSize - autoQueuedCount <= 1`" is not worth guarding. */
    @Test
    fun ac7_ac14_mainPlayerWithAtMostOneVideoOfTheUsersIsNotWorthGuarding() {
        assertFalse("A alone", main(1).worthGuarding)
        assertFalse("A and the auto-enqueued B", main(2, autoQueued = 1).worthGuarding)
        assertFalse("A, B, C after playback moved on by itself (A13)", main(3, autoQueued = 2).worthGuarding)
        assertFalse("B and C after the user removed A", main(2, autoQueued = 2).worthGuarding)
        assertFalse("B and D after the user removed A and enqueued D", main(2, autoQueued = 1).worthGuarding)
        assertFalse("one auto-enqueued video alone", main(1, autoQueued = 1).worthGuarding)
        for (autoQueued in 0..30) {
            assertFalse("${autoQueued + 1}/$autoQueued", main(autoQueued + 1, autoQueued).worthGuarding)
            if (autoQueued > 0) {
                assertFalse("$autoQueued/$autoQueued", main(autoQueued, autoQueued).worthGuarding)
            }
        }
    }

    /** Line 4: "`MAIN`, `queueSize - autoQueuedCount >= 2`" is worth guarding. */
    @Test
    fun ac1_ac10_mainPlayerWithTwoVideosOfTheUsersIsWorthGuarding() {
        assertTrue("A and D", main(2).worthGuarding)
        assertTrue("A, B and D", main(3, autoQueued = 1).worthGuarding)
        assertTrue("three videos of the user's", main(3).worthGuarding)
        assertTrue("a long queue", main(500).worthGuarding)
        for (autoQueued in 0..30) {
            assertTrue("${autoQueued + 2}/$autoQueued", main(autoQueued + 2, autoQueued).worthGuarding)
            assertTrue("${autoQueued + 7}/$autoQueued", main(autoQueued + 7, autoQueued).worthGuarding)
        }
    }

    /** The user's second video is what makes a queue (plan section 8), wherever the first one stands. */
    @Test
    fun ac1_ac7_theSecondVideoOfTheUsersTurnsTheMainPlayersQueueIntoOneWorthGuarding() {
        for (state in SINGLE_IN_MAIN.filter { it.queueSize - it.autoQueuedCount == 1 }) {
            assertFalse("$state", state.worthGuarding)
            val oneMoreEnqueuedByTheUser = state.copy(queueSize = state.queueSize + 1)
            assertTrue("$oneMoreEnqueuedByTheUser", oneMoreEnqueuedByTheUser.worthGuarding)
            val oneMoreAutoEnqueued =
                state.copy(queueSize = state.queueSize + 1, autoQueuedCount = state.autoQueuedCount + 1)
            assertFalse("$oneMoreAutoEnqueued", oneMoreAutoEnqueued.worthGuarding)
        }
    }

    /** The fixture every "asks" test runs over (plan 5.1). */
    @Test
    fun ac1_ac10_everyStateOfTheFixtureWorthGuardingIsWorthGuarding() {
        assertEquals("states in WORTH_GUARDING", 9, WORTH_GUARDING.size)
        for (state in WORTH_GUARDING) {
            assertTrue("$state", state.worthGuarding)
            assertTrue("$state: worth guarding implies something is playing", state.somethingPlaying)
        }
    }

    /** The fixture every "single video in the main player" test runs over (plan 5.1). */
    @Test
    fun ac7_ac14_noStateOfTheFixtureSingleInMainIsWorthGuarding() {
        assertEquals("states in SINGLE_IN_MAIN", 5, SINGLE_IN_MAIN.size)
        for (state in SINGLE_IN_MAIN) {
            assertEquals("$state", PlayerKind.MAIN, state.kind)
            assertFalse("$state", state.worthGuarding)
            assertTrue("$state: something is playing all the same", state.somethingPlaying)
        }
    }

    /** Plan 3.1: "`worthGuarding` implies `somethingPlaying`", for every kind, size and count. */
    @Test
    fun ac1_ac7_aQueueWorthGuardingAlwaysMeansSomethingIsPlaying() {
        for (kind in ALL_KINDS + null) {
            for (queueSize in 0..6) {
                for (autoQueued in 0..queueSize) {
                    val state = PlayerState(kind, queueSize, SERVICE, CURRENT_URL, autoQueued)
                    if (state.worthGuarding) {
                        assertTrue("$state", state.somethingPlaying)
                    }
                }
            }
        }
    }

    /**
     * Plan 3.1: `autoQueuedCount` is the last constructor parameter with default `0`, so a state built
     * with four arguments (this feature's older tests, default-open-action) is a queue the user built
     * alone.
     */
    @Test
    fun ac1_ac7_noVideoIsAutoQueuedInIdleAndInAStateBuiltWithFourArguments() {
        assertEquals("IDLE", 0, PlayerState.IDLE.autoQueuedCount)
        assertEquals("positional", 0, PlayerState(PlayerKind.MAIN, 3, SERVICE, CURRENT_URL).autoQueuedCount)
        assertEquals(
            "named",
            0,
            PlayerState(kind = PlayerKind.POPUP, queueSize = 1, currentServiceId = SERVICE, currentUrl = null)
                .autoQueuedCount,
        )
        assertEquals(
            "the fifth argument is the count",
            PlayerState(PlayerKind.MAIN, 3, SERVICE, CURRENT_URL, autoQueuedCount = 2),
            PlayerState(PlayerKind.MAIN, 3, SERVICE, CURRENT_URL, 2),
        )
        assertEquals(2, PlayerState(PlayerKind.MAIN, 3, SERVICE, CURRENT_URL, 2).autoQueuedCount)
    }

    /** Plan 3.1: "`somethingPlaying` ... keep[s its] meaning": `queueSize` stays the whole queue. */
    @Test
    fun ac1_ac7_somethingPlayingDoesNotDependOnTheAutoQueuedCount() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 2, 3)) {
                for (autoQueued in 0..queueSize) {
                    val state = playing(kind, queueSize = queueSize, autoQueued = autoQueued)
                    assertTrue("$state", state.somethingPlaying)
                }
            }
            for (autoQueued in listOf(0, 1, 4)) {
                val empty = openButEmpty(kind).copy(autoQueuedCount = autoQueued)
                assertFalse("$empty", empty.somethingPlaying)
            }
        }
        assertFalse(PlayerState.IDLE.copy(autoQueuedCount = 2).somethingPlaying)
    }

    /** Plan 3.1 and 3.2: `isCurrent` is still built on "playing", not on "worth guarding" (AC-8). */
    @Test
    fun ac8_currentItemDoesNotDependOnTheAutoQueuedCount() {
        for (state in SINGLE_IN_MAIN + WORTH_GUARDING) {
            assertTrue("$state", state.isCurrent(tapOnCurrent()))
            assertFalse("$state, another video", state.isCurrent(tapOn(url = TAPPED_URL)))
            assertFalse(
                "$state, same url on another service",
                state.isCurrent(tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)),
            )
        }
    }

    // ---- AutoQueuedRecord: which videos the player added by itself (plan 3.1, rules 1 to 6) ----
    //
    // Queue items are given by their uuid, one per queue slot. Capital letters here: A and D are videos
    // the user tapped or enqueued, B and C came through "Auto-enqueue next stream".

    @Test
    fun ac1_ac7_emptyRecordCountsNoVideoInAnyQueue() {
        val record = AutoQueuedRecord()
        assertEquals("empty queue", 0, record.countIn(emptyList()))
        assertEquals("one video", 0, record.countIn(listOf("A")))
        assertEquals("several videos", 0, record.countIn(listOf("A", "B", "C")))
        record.add(emptyList())
        assertEquals("after adding nothing", 0, record.countIn(listOf("A", "B", "C")))
    }

    /** Rule 1: an item counts if and only if its uuid was reported. Rule 2: playing does not change it. */
    @Test
    fun ac1_ac7_recordCountsTheReportedVideosAndNoOthers() {
        val record = AutoQueuedRecord()
        record.add(listOf("B"))
        assertEquals("A, B", 1, record.countIn(listOf("A", "B")))
        record.add(listOf("C"))
        assertEquals("A, B, C", 2, record.countIn(listOf("A", "B", "C")))
        assertEquals("asking again gives the same answer", 2, record.countIn(listOf("A", "B", "C")))
        assertEquals("the user's videos only", 0, record.countIn(listOf("A", "D")))
        assertEquals("empty queue", 0, record.countIn(emptyList()))
    }

    /** One call can report several videos (hook Q1 passes the whole appended list). */
    @Test
    fun ac1_ac7_recordTakesSeveralVideosInOneCall() {
        val record = AutoQueuedRecord()
        record.add(listOf("B", "C"))
        record.add(setOf("E"))
        assertEquals(3, record.countIn(listOf("A", "B", "C", "D", "E")))
    }

    /** Rule 3: moving an auto-enqueued video, by drag or by shuffling, does not make it the user's. */
    @Test
    fun ac1_ac7_recordCountsTheSameWhateverTheOrderOfTheQueue() {
        val record = AutoQueuedRecord()
        record.add(listOf("B", "C"))
        val orders = listOf(
            listOf("A", "B", "C", "D"),
            listOf("B", "A", "D", "C"),
            listOf("C", "B", "A", "D"),
            listOf("D", "C", "B", "A"),
            listOf("B", "C", "D", "A"),
        )
        for (queue in orders) {
            assertEquals("$queue", 2, record.countIn(queue))
        }
    }

    /**
     * Rule 5: "A removed item's uuid simply never matches again", and the record follows the uuids
     * wherever the queue goes. Rule 4: the same video enqueued by hand is a new item with a new uuid, so
     * a uuid that was never reported is the user's.
     */
    @Test
    fun ac1_ac7_recordDoesNotCountARemovedVideoNorOneThatWasNeverReported() {
        val record = AutoQueuedRecord()
        record.add(listOf("B", "C"))
        assertEquals("B was removed from the queue", 1, record.countIn(listOf("A", "C")))
        assertEquals("B and C were removed", 0, record.countIn(listOf("A")))
        assertEquals("the queue was replaced", 0, record.countIn(listOf("X", "Y", "Z")))
        assertEquals(
            "the video of B enqueued by hand is another item, B2",
            2,
            record.countIn(listOf("A", "B", "C", "B2")),
        )
        assertEquals("C is still known when it is in a queue again", 1, record.countIn(listOf("D", "C")))
    }

    /** Plan 3.1, `add` and `countIn`: a uuid is remembered once and counted once per slot that holds it. */
    @Test
    fun ac1_ac7_recordCountsAVideoReportedTwiceOncePerOccurrenceInTheQueue() {
        val record = AutoQueuedRecord()
        record.add(listOf("B"))
        record.add(listOf("B"))
        record.add(listOf("B", "B"))
        assertEquals("reported four times, in the queue once", 1, record.countIn(listOf("A", "B")))
        assertEquals("a uuid that occurs twice counts twice", 2, record.countIn(listOf("B", "A", "B")))
        assertEquals("not in the queue", 0, record.countIn(listOf("A")))
    }

    /**
     * Rule 6: beyond the limit the record forgets its oldest entries, "one per entry added". A forgotten
     * video counts as the user's, so the guard errs towards asking.
     */
    @Test
    fun ac1_ac7_recordWithLimitThreeForgetsItsOldestVideoWhenAFourthIsReported() {
        val record = AutoQueuedRecord(limit = 3)
        record.add(listOf("u1"))
        record.add(listOf("u2"))
        record.add(listOf("u3"))
        val queue = listOf("u1", "u2", "u3", "u4", "u5")
        assertEquals("at the limit nothing is forgotten", 3, record.countIn(queue))

        record.add(listOf("u4"))
        assertEquals("u1 is forgotten", 0, record.countIn(listOf("u1")))
        assertEquals("u2, u3 and u4 are kept", 3, record.countIn(listOf("u2", "u3", "u4")))
        assertEquals(3, record.countIn(queue))

        record.add(listOf("u5"))
        assertEquals("u1 and u2 are forgotten", 0, record.countIn(listOf("u1", "u2")))
        assertEquals("u3, u4 and u5 are kept", 3, record.countIn(listOf("u3", "u4", "u5")))
    }

    /** Rule 6 with the videos reported by one call up to the limit and one call beyond it. */
    @Test
    fun ac1_ac7_recordWithLimitThreeForgetsItsOldestVideoAlsoWhenVideosComeInLists() {
        val record = AutoQueuedRecord(limit = 3)
        record.add(listOf("u1", "u2", "u3"))
        assertEquals(3, record.countIn(listOf("u1", "u2", "u3")))
        record.add(listOf("u4"))
        assertEquals("u1 is forgotten", 0, record.countIn(listOf("u1")))
        assertEquals("u2, u3 and u4 are kept", 3, record.countIn(listOf("u1", "u2", "u3", "u4")))
    }

    /** Plan 3.1: `AUTO_QUEUED_RECORD_LIMIT` is 1000 and the limit of a record built without arguments. */
    @Test
    fun ac1_ac7_recordRemembersAThousandVideosByDefault() {
        assertEquals(1000, AUTO_QUEUED_RECORD_LIMIT)

        val record = AutoQueuedRecord()
        val uuids = List(1001) { "uuid-$it" }
        for (uuid in uuids.take(1000)) record.add(listOf(uuid))
        assertEquals("1000 reported", 1000, record.countIn(uuids))

        record.add(listOf(uuids[1000]))
        assertEquals("the 1001st makes the first forgotten", 0, record.countIn(listOf(uuids[0])))
        assertEquals("the other 1000 are kept", 1000, record.countIn(uuids))
    }

    /**
     * Plan 3.1 and 3.3: the record is thread-safe, because hook Q1 can run on the round-play timer's
     * thread while a tap reads the queue on the main thread. Four threads report 250 videos each while
     * the test thread keeps counting; afterwards all 1000 are remembered.
     */
    @Test
    fun ac1_ac7_recordCanBeWrittenAndReadFromSeveralThreadsAtOnce() {
        val record = AutoQueuedRecord()
        val all = List(4) { t -> List(250) { i -> "thread$t-video$i" } }
        val queue = all.flatten()
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val writers = all.map { uuids ->
            thread {
                try {
                    start.await()
                    for (uuid in uuids) record.add(listOf(uuid))
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        start.countDown()
        var last = 0
        while (writers.any { it.isAlive }) {
            val count = record.countIn(queue)
            assertTrue("the count never shrinks while videos are only added: $last, then $count", count >= last)
            last = count
        }
        for (writer in writers) writer.join(TimeUnit.SECONDS.toMillis(10))
        assertEquals("no writer failed", emptyList<Throwable>(), failures.toList())
        assertEquals(1000, record.countIn(queue))
    }

    /**
     * Answer A13 end to end, as the glue puts record and state together (plan 3.3,
     * `HolderPlayerStateSource`): the user watches A in the main player; B is auto-enqueued and plays, C
     * is auto-enqueued. The queue A, B, C is not worth guarding. Once the user enqueues D it is.
     */
    @Test
    fun ac1_ac7_watchingOneVideoAndLettingTheNextOnesFollowIsNotAQueueUntilTheUserAddsAVideo() {
        val record = AutoQueuedRecord()
        fun mainPlayerWith(queue: List<String>) = PlayerState(
            kind = PlayerKind.MAIN,
            queueSize = queue.size,
            currentServiceId = SERVICE,
            currentUrl = CURRENT_URL,
            autoQueuedCount = record.countIn(queue),
        )

        assertFalse("A alone", mainPlayerWith(listOf("A")).worthGuarding)

        record.add(listOf("B"))
        assertFalse("A, B", mainPlayerWith(listOf("A", "B")).worthGuarding)

        record.add(listOf("C"))
        val watching = mainPlayerWith(listOf("A", "B", "C"))
        assertEquals(2, watching.autoQueuedCount)
        assertEquals("the dialog would still show the whole queue", 3, watching.queueSize)
        assertFalse("A, B, C", watching.worthGuarding)

        val withASecondVideoOfTheUsers = mainPlayerWith(listOf("A", "B", "C", "D"))
        assertEquals(2, withASecondVideoOfTheUsers.autoQueuedCount)
        assertTrue("A, B, C, D", withASecondVideoOfTheUsers.worthGuarding)

        assertFalse("B, C after the user removed A", mainPlayerWith(listOf("B", "C")).worthGuarding)
        assertFalse("B, D: one video of the user's", mainPlayerWith(listOf("B", "D")).worthGuarding)
        assertTrue("A, D", mainPlayerWith(listOf("A", "D")).worthGuarding)
    }

    // ---- "Current item" (AC-8): matched by service id and exact url ----

    @Test
    fun ac8_currentItemIsTheOneWithTheSameServiceAndTheSameUrl() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind", playing(kind).isCurrent(tapOnCurrent()))
            assertTrue("$kind, queue of one", playing(kind, queueSize = 1).isCurrent(tapOnCurrent()))
            assertTrue(
                "$kind, Music Player Mode does not change which item is current",
                playing(kind).isCurrent(tapOnCurrent(startsListInBackground = true)),
            )
        }
    }

    @Test
    fun ac8_currentItemIsRecognisedWhateverTitleTheListShows() {
        val tap = tapOn(url = CURRENT_URL, title = "Another title for the same video")
        assertTrue(playing(PlayerKind.MAIN).isCurrent(tap))
    }

    @Test
    fun ac8_anotherUrlIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind).isCurrent(tapOn(url = TAPPED_URL)))
        }
    }

    /** Plan section 8: the url is compared exactly, as upstream does; another form of the url is another item. */
    @Test
    fun ac8_urlIsComparedExactly() {
        val state = playing(PlayerKind.BACKGROUND, url = CURRENT_URL)
        assertFalse("extra parameter", state.isCurrent(tapOn(url = "$CURRENT_URL&t=10")))
        assertFalse("other case", state.isCurrent(tapOn(url = CURRENT_URL.uppercase())))
    }

    @Test
    fun ac8_sameUrlOnAnotherServiceIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
            assertFalse("$kind", playing(kind, serviceId = SERVICE).isCurrent(tap))
        }
    }

    @Test
    fun ac8_noItemIsCurrentWhileNothingIsPlaying() {
        val urlOfIdle = tapOn(url = CURRENT_URL, serviceId = -1)
        assertFalse("IDLE", PlayerState.IDLE.isCurrent(urlOfIdle))
        for (kind in ALL_KINDS) {
            val emptyQueueWithStaleItem =
                PlayerState(kind = kind, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            assertFalse("$kind with an empty queue", emptyQueueWithStaleItem.isCurrent(tapOnCurrent()))
        }
        val noPlayerWithStaleItem =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("no player", noPlayerWithStaleItem.isCurrent(tapOnCurrent()))
    }

    @Test
    fun ac8_noItemIsCurrentWhileThePlayerReportsNoCurrentUrl() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind, url = null).isCurrent(tapOnCurrent()))
        }
    }

    // ---- TapRequest ----

    /** Plan 3.1: `startsListInBackground` defaults to `false`; only Music Player Mode in a list sets it. */
    @Test
    fun ac5_tapStartsNoListInTheBackgroundUnlessToldSo() {
        val tap = TapRequest(SERVICE, TAPPED_URL, "Tapped video")
        assertFalse(tap.startsListInBackground)
        assertEquals(TapRequest(SERVICE, TAPPED_URL, "Tapped video", false), tap)
    }

    // ---- movesForPlayNext (AC-11, "Play all next") ----
    //
    // The glue appends the list to the player's queue and then moves the appended videos, one move per
    // video, to just behind the current item. A move is applied as PlayQueue.move does on its list:
    // add(to, removeAt(from)).

    private fun <T> MutableList<T>.applyMoves(moves: List<QueueMove>) {
        for (move in moves) add(move.to, removeAt(move.from))
    }

    private fun queueAfterPlayAllNext(queue: List<String>, currentIndex: Int, list: List<String>): List<String> {
        val appended = (queue + list).toMutableList()
        appended.applyMoves(movesForPlayNext(appended.size, list.size, currentIndex))
        return appended
    }

    @Test
    fun ac11_playAllNext_currentItemFirst_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "x", "y", "b", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 0, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemInTheMiddle_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "x", "y", "z", "c", "d"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c", "d"), currentIndex = 1, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemLast_videosStayRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "c", "x", "y"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 2, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_queueOfOneItem_videosFollowIt() {
        assertEquals(
            listOf("a", "x", "y", "z"),
            queueAfterPlayAllNext(queue = listOf("a"), currentIndex = 0, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_listOfOneVideo_landsRightBehindTheCurrentItem() {
        assertEquals(
            listOf("a", "b", "x", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 1, list = listOf("x")),
        )
    }

    /** Plan 3.1: i in 0 until added -> (sizeAfterAppend - added + i, currentIndex + 1 + i). */
    @Test
    fun ac11_movesForPlayNext_isOneMovePerVideoFromTheTailToBehindTheCurrentItem() {
        assertEquals(
            "queue of 3 on its first item, 2 videos appended",
            listOf(QueueMove(3, 1), QueueMove(4, 2)),
            movesForPlayNext(5, 2, 0),
        )
        assertEquals(
            "queue of 4 on its second item, 3 videos appended",
            listOf(QueueMove(4, 2), QueueMove(5, 3), QueueMove(6, 4)),
            movesForPlayNext(7, 3, 1),
        )
        assertEquals(
            "queue of 3 on its second item, 1 video appended",
            listOf(QueueMove(3, 2)),
            movesForPlayNext(4, 1, 1),
        )
    }

    /** Plan 3.1: "minus from == to". With the current item last, the appended videos are in place already. */
    @Test
    fun ac11_movesForPlayNext_leavesOutMovesThatMoveNothing() {
        assertEquals("current item last", emptyList<QueueMove>(), movesForPlayNext(5, 2, 2))
        assertEquals("queue of one item", emptyList<QueueMove>(), movesForPlayNext(4, 3, 0))
    }

    @Test
    fun ac11_movesForPlayNext_nothingAddedNeedsNoMove() {
        assertEquals(emptyList<QueueMove>(), movesForPlayNext(3, 0, 1))
    }

    /** Every queue of 1..6 items, every current index, every list of 1..5 videos. */
    @Test
    fun ac11_movesForPlayNext_putsTheListBehindTheCurrentItemForEveryQueueAndPosition() {
        for (queueSize in 1..6) {
            for (currentIndex in 0 until queueSize) {
                for (added in 1..5) {
                    val queue = List(queueSize) { "q$it" }
                    val list = List(added) { "new$it" }
                    val case = "queue of $queueSize on index $currentIndex, $added added"

                    val moves = movesForPlayNext(queueSize + added, added, currentIndex)
                    val result = (queue + list).toMutableList().apply { applyMoves(moves) }

                    val expected =
                        queue.subList(0, currentIndex + 1) + list + queue.subList(currentIndex + 1, queueSize)
                    assertEquals("$case: order", expected, result)
                    assertEquals("$case: the current item keeps its index", "q$currentIndex", result[currentIndex])
                    assertTrue("$case: no move with from == to in $moves", moves.none { it.from == it.to })
                    val expectedMoves = if (currentIndex == queueSize - 1) 0 else added
                    assertEquals("$case: one move per video that has to move", expectedMoves, moves.size)
                }
            }
        }
    }

    // ---- streamDetailsInsertIndex (AC-17) ----
    //
    // Menus are given as string resource ids. Any ints do.

    private val details = 7001
    private val anchor = 7002
    private val other1 = 7003
    private val other2 = 7004
    private val other3 = 7005

    /** AC-17: "Where the menu has that entry already, nothing changes." */
    @Test
    fun ac17_streamDetailsIsNotInsertedWhereTheMenuHasItAlready() {
        assertEquals("only entry", -1, streamDetailsInsertIndex(listOf(details), details, anchor))
        assertEquals("no anchor", -1, streamDetailsInsertIndex(listOf(other1, details, other2), details, anchor))
        assertEquals("before anchor", -1, streamDetailsInsertIndex(listOf(other1, details, anchor), details, anchor))
        assertEquals("behind anchor", -1, streamDetailsInsertIndex(listOf(anchor, other1, details), details, anchor))
    }

    @Test
    fun ac17_streamDetailsGoesToTheIndexOfTheAnchorEntry() {
        assertEquals("anchor first", 0, streamDetailsInsertIndex(listOf(anchor, other1, other2), details, anchor))
        assertEquals(
            "anchor in the middle",
            2,
            streamDetailsInsertIndex(listOf(other1, other2, anchor, other3), details, anchor),
        )
        assertEquals("anchor last", 2, streamDetailsInsertIndex(listOf(other1, other2, anchor), details, anchor))
        assertEquals("anchor alone", 0, streamDetailsInsertIndex(listOf(anchor), details, anchor))
    }

    @Test
    fun ac17_streamDetailsIsAppendedWhereTheMenuHasNoAnchorEntry() {
        assertEquals("three others", 3, streamDetailsInsertIndex(listOf(other1, other2, other3), details, anchor))
        assertEquals("one other entry", 1, streamDetailsInsertIndex(listOf(other1), details, anchor))
        assertEquals("empty menu", 0, streamDetailsInsertIndex(emptyList(), details, anchor))
    }

    /** Inserting at the returned index puts the entry directly in front of the anchor and keeps the rest. */
    @Test
    fun ac17_streamDetailsInsertedAtTheReturnedIndexSitsInFrontOfTheAnchor() {
        val menu = mutableListOf(other1, anchor, other2)
        menu.add(streamDetailsInsertIndex(menu, details, anchor), details)
        assertEquals(listOf(other1, details, anchor, other2), menu)
    }
}
