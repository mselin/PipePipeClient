package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val U = "uuid-U"
private const val N = "uuid-N"
private const val N2 = "uuid-N2"
private const val V = "uuid-V"
private const val A = "0|https://example.org/a"
private const val B = "0|https://example.org/b"

/**
 * Spec AM-1 (changes AC-7): when the player is closed because of a playback error and the
 * next start plays the same video, it continues at the position it had when the error
 * occurred; "until another video is started". A player closed by the user while it was
 * playing or paused normally, and a start of any other video, stay starts (AC-7).
 *
 * Plan 10.3: the states `failing` and `closeMark`, the methods `onErrorBegin` (hook E1),
 * `onPlayerClosed` (D1), `onPageStopsPlayer` rule 1 (F1), `onStartIntent` rows 1, 2, 3, 5, 6
 * (G1), and the two tables "Sequence for A13". The row numbers in the test names are those
 * of the table "AM-1" in plan 10.7.
 *
 * U and N are two queue items of the same video (address A); V is another video (address B).
 * p1 is the player that fails, p2 the player the next start arrives at. Both switches are on
 * unless a test says otherwise.
 */
class KeepPositionTrackerErrorCloseTest {

    private val settings = FakeKeepPositionSettings()
    private val tracker = KeepPositionTracker(settings)

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p1 = Any()
    private val p2 = Any()
    private val p3 = Any()

    /** Row 1 (E-a): U plays in p1, fails at 5000 and the error shuts the player down. */
    private fun failAndClose(t: KeepPositionTracker = tracker) {
        t.onSynchronize(p1, U, A)
        t.err(p1, U, A, 5000, true)
        t.closed(p1, 5000, true, false)
        assertEquals("precondition: the close mark", 5000L, t.peekClosePosition(A))
    }

    private fun assertNoCloseMark(what: String, t: KeepPositionTracker = tracker) {
        assertEquals("$what: close mark for A", NO_POSITION, t.peekClosePosition(A))
        assertEquals("$what: close mark for B", NO_POSITION, t.peekClosePosition(B))
    }

    // ---- rows 1, 2: the error closes the player, the next start continues ----

    /** AM-1, row 1 (E-a): the error makes the player failing; its close leaves the close mark. */
    @Test
    fun am1_row1_playerClosedByTheError_leavesACloseMarkAtTheErrorPosition() {
        tracker.onSynchronize(p1, U, A)
        assertFalse("before the error", tracker.isFailing(p1))

        tracker.err(p1, U, A, 5000, true)
        assertTrue("after the error", tracker.isFailing(p1))
        assertFalse("only that player", tracker.isFailing(p2))
        assertNoCloseMark("a failing player that is still open")

        tracker.closed(p1, 5000, true, false)
        assertFalse("after the close", tracker.isFailing(p1))
        assertEquals(5000L, tracker.peekClosePosition(A))
        assertEquals("only for that video", NO_POSITION, tracker.peekClosePosition(B))
    }

    /** AM-1, AC-7: the close itself sets no reload mark; only the next start of the video does. */
    @Test
    fun am1_ac7_closeMarkAloneExemptsNoLoad() {
        failAndClose()

        assertEquals(Mark.NONE, tracker.peekMark(p1, U))
        assertEquals(Mark.NONE, tracker.peekMark(p2, U))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertFalse(tracker.shouldSeekOnLoad(p2, N))
    }

    /** AM-1, row 2: the next start of the same video continues at the error position, once. */
    @Test
    fun am1_row2_nextStartOfTheSameVideoContinues_andASecondLoadDoesNot() {
        failAndClose()

        assertEquals("the position for the new queue", 5000L, tracker.start(p2, N, A))

        assertEquals(Mark.RELOAD, tracker.peekMark(p2, N))
        assertEquals("the old queue item gets nothing", Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("the close mark is spent", NO_POSITION, tracker.peekClosePosition(A))
        assertEquals("the start's load", SEEKS, tracker.load(p2, N, A))
        assertEquals("a second load", NO_SEEK, tracker.load(p2, N, A))
    }

    /**
     * AM-1, `onStartIntent` row 5: `reload = (newUuid, ANY, false)`. The mark is for whichever
     * player loads the new item (the intent may be answered by a new service).
     */
    @Test
    fun am1_row2_markOfTheStartFitsWhicheverPlayerLoadsTheItem() {
        failAndClose()
        tracker.start(p2, N, A)

        assertEquals(Mark.RELOAD, tracker.peekMark(p3, N))
        assertEquals(SEEKS, tracker.load(p3, N, A))
    }

    /** AM-1, row 2 with the same queue item again (a start of the very queue that failed). */
    @Test
    fun am1_row2_startWithTheSameQueueItemContinuesToo() {
        failAndClose()

        assertEquals(5000L, tracker.start(p2, U, A))
        assertEquals(SEEKS, tracker.load(p2, U, A))
    }

    /**
     * AM-1, AC-7: the mark is used once. After the continued start, a normal close and another
     * start of the same video begin at 0:00.
     */
    @Test
    fun am1_ac7_closeMarkIsUsedOnce() {
        failAndClose()
        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(SEEKS, tracker.load(p2, N, A))

        tracker.closed(p2, 65000, true, true)
        assertEquals("the second start", NO_POSITION, tracker.start(p3, N2, A))

        assertEquals(Mark.NONE, tracker.peekMark(p3, N2))
        assertEquals(NO_SEEK, tracker.load(p3, N2, A))
    }

    // ---- rows 3, 4: the two tables "Sequence for A13" ----

    /**
     * AM-1, A13, row 3, plan 10.3 first sequence table: an error that was recovered must not
     * resume. The player plays on for a minute, the user closes it, the same video is started:
     * no mark, 0:00.
     */
    @Test
    fun am1_row3_recoveredErrorPlayedOnThenClosedByTheUser_sameVideoStartsAtZero() {
        tracker.onSynchronize(p1, U, A)

        tracker.err(p1, U, A, 120000, true)
        assertTrue("network error", tracker.isFailing(p1))
        assertNoCloseMark("network error")

        assertEquals("recovery reload", 120000L, tracker.onReload(p1, U, 120000, false, true))
        assertEquals("the recovery continues (AC-4)", SEEKS, tracker.load(p1, U, A))
        assertTrue("same player, same item: still failing", tracker.isFailing(p1))
        assertNoCloseMark("recovery")

        // plays on for a minute: no call

        tracker.closed(p1, 180000, true, true)
        assertFalse("user closes it", tracker.isFailing(p1))
        assertNoCloseMark("closed after playing on")

        assertEquals("same video started", NO_POSITION, tracker.start(p2, N, A))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertEquals("0:00", NO_SEEK, tracker.load(p2, N, A))
    }

    /** AM-1, A13, row 3 variant: the recovered player was paused a minute later, then closed. */
    @Test
    fun am1_row3_recoveredErrorPausedAMinuteLaterThenClosed_sameVideoStartsAtZero() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 120000, true)
        tracker.onReload(p1, U, 120000, false, true)
        tracker.load(p1, U, A)

        tracker.closed(p1, 180000, true, false)

        assertNoCloseMark("closed paused, a minute further on")
        assertEquals(NO_POSITION, tracker.start(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /**
     * AM-1 (E-c), A13, row 4, plan 10.3 second sequence table: the player keeps retrying
     * without success, the user closes it, the same video continues where the error hit.
     */
    @Test
    fun am1_row4_stuckPlayerClosedByTheUser_sameVideoContinuesAtTheErrorPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 120000, true)

        repeat(3) { round ->
            assertEquals("retry $round: reload", 120000L, tracker.onReload(p1, U, 120000, false, false))
            assertEquals("retry $round: load", SEEKS, tracker.load(p1, U, A))
            tracker.onErrorBegin(p1, U, A, 120000, false, false)
            assertTrue("retry $round: still failing", tracker.isFailing(p1))
            assertNoCloseMark("retry $round")
        }

        tracker.closed(p1, 120000, false, false)
        assertFalse(tracker.isFailing(p1))
        assertEquals(120000L, tracker.peekClosePosition(A))

        assertEquals(120000L, tracker.start(p2, N, A))
        assertFalse(tracker.isFailing(p1))
        assertEquals(NO_POSITION, tracker.peekClosePosition(A))
        assertEquals(SEEKS, tracker.load(p2, N, A))
    }

    /**
     * AM-1, A13: the retry loop cannot fake "played on". Retries synchronize the item again
     * and report 0 or any other position without a duration; none of that moves the error
     * position or counts as playing on.
     */
    @Test
    fun am1_retryLoopCannotFakePlayedOn() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 120000, true)

        // retry 1: the player reports 0 while the source loads
        assertEquals(120000L, tracker.onReload(p1, U, 120000, false, true))
        tracker.load(p1, U, A)
        tracker.onErrorBegin(p1, U, A, 0, false, false)
        // retry 2: it reports a far position, still without a duration
        assertEquals(120000L, tracker.onReload(p1, U, 0, false, false))
        tracker.load(p1, U, A)
        tracker.onErrorBegin(p1, U, A, 300000, false, false)
        // retry 3: synchronized, no further error yet
        tracker.onReload(p1, U, 300000, false, false)
        tracker.load(p1, U, A)
        assertTrue(tracker.isFailing(p1))

        tracker.closed(p1, 300000, false, false)

        assertEquals("the first error's position", 120000L, tracker.peekClosePosition(A))
        assertEquals(120000L, tracker.start(p2, N, A))
    }

    // ---- row 5: what "played on" means at the close ----

    private fun closeAfterAnErrorAt5000(positionMs: Long, durationKnown: Boolean, isPlaying: Boolean): Long {
        val t = KeepPositionTracker(FakeKeepPositionSettings())
        t.onSynchronize(p1, U, A)
        t.err(p1, U, A, 5000, true)
        t.closed(p1, positionMs, durationKnown, isPlaying)
        assertFalse("a close always ends the failing state", t.isFailing(p1))
        return t.peekClosePosition(A)
    }

    /** AM-1, row 5: closed while playing: played on, no mark. */
    @Test
    fun am1_row5_closedWhilePlaying_noMark() {
        assertEquals(NO_POSITION, closeAfterAnErrorAt5000(5000, true, true))
    }

    /** AM-1, row 5: closed paused 4 seconds further on, duration known: played on, no mark. */
    @Test
    fun am1_row5_closedFourSecondsFurtherOn_noMark() {
        assertEquals(NO_POSITION, closeAfterAnErrorAt5000(9000, true, false))
    }

    /** AM-1, row 5: closed within 2 seconds of the error position: the error position. */
    @Test
    fun am1_row5_closedWithinTwoSeconds_marksTheErrorPosition() {
        assertEquals(5000L, closeAfterAnErrorAt5000(6500, true, false))
        assertEquals("exactly 2 s", 5000L, closeAfterAnErrorAt5000(7000, true, false))
    }

    /** AM-1, row 5: just over 2 seconds away: played on, no mark. */
    @Test
    fun am1_row5_closedJustOverTwoSecondsAway_noMark() {
        assertEquals(NO_POSITION, closeAfterAnErrorAt5000(7001, true, false))
        assertEquals(NO_POSITION, closeAfterAnErrorAt5000(2999, true, false))
    }

    /** AM-1, row 5: closed at another position but without a duration: the error position. */
    @Test
    fun am1_row5_closedWithoutADuration_marksTheErrorPosition() {
        assertEquals(5000L, closeAfterAnErrorAt5000(9000, false, false))
        assertEquals(5000L, closeAfterAnErrorAt5000(0, false, false))
    }

    // ---- rows 6, 7: closes that are not the failing player's ----

    /** AM-1, row 6: another player's close changes nothing; p1 is still failing. */
    @Test
    fun am1_row6_closeOfAnotherPlayerChangesNothing() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.closed(p2, 5000, false, false)

        assertTrue(tracker.isFailing(p1))
        assertNoCloseMark("another player's close")

        tracker.closed(p1, 5000, false, false)
        assertEquals("p1's own close still marks", 5000L, tracker.peekClosePosition(A))
    }

    /** AM-1, AC-7, row 7: a close with no error before sets no mark; the next start is 0:00. */
    @Test
    fun am1_ac7_row7_closeWithoutAnErrorSetsNoMark() {
        tracker.onSynchronize(p1, U, A)

        tracker.closed(p1, 5000, true, false)

        assertNoCloseMark("normal close")
        assertEquals(NO_POSITION, tracker.start(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    // ---- rows 8, 9, 10: the page stops the player (E-b) ----

    /**
     * AM-1, row 8 (E-b): the page stops the failing player. The close mark is there at once
     * and still after the destroy that follows; the next start continues.
     */
    @Test
    fun am1_row8_pageStopsTheFailingPlayer_markAtOnceAndAfterTheClose() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onPageStopsPlayer(p1, U, A, 5000, false, false, true, false, A)
        assertEquals("after the page's stop", 5000L, tracker.peekClosePosition(A))
        assertTrue("failing stays: the destroy follows", tracker.isFailing(p1))

        tracker.closed(p1, 5000, false, false)
        assertEquals("after the close", 5000L, tracker.peekClosePosition(A))
        assertFalse(tracker.isFailing(p1))

        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(Mark.RELOAD, tracker.peekMark(p2, N))
        assertEquals(NO_POSITION, tracker.peekClosePosition(A))
        assertEquals(SEEKS, tracker.load(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /**
     * AM-1, `onPageStopsPlayer` rule 1 has no condition on the page: the page that reacts to
     * the error may show another video (it "followed the player") or none.
     */
    @Test
    fun am1_row8_pageStopsTheFailingPlayer_whateverThePageShows() {
        for (pageAddress in listOf(B, null)) {
            val t = KeepPositionTracker(FakeKeepPositionSettings())
            t.onSynchronize(p1, U, A)
            t.err(p1, U, A, 5000, true)

            t.onPageStopsPlayer(p1, U, A, 5000, false, false, true, false, pageAddress)

            assertEquals("page=$pageAddress", 5000L, t.peekClosePosition(A))
        }
    }

    /** AM-1, rule 1 marks the position of the error, not the one the player reports later. */
    @Test
    fun am1_row8_pageStopMarksTheErrorPosition_notTheReportedOne() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onPageStopsPlayer(p1, U, A, 0, false, false, false, false, A)

        assertEquals(5000L, tracker.peekClosePosition(A))
    }

    /**
     * AM-1, row 9 (E-b): between the page's stop and the destroy the dying player recovers
     * and plays. Its reload continues, the mark of the page's stop stays, and the next start
     * continues at the error position.
     */
    @Test
    fun am1_row9_dyingPlayerRecoversBetweenThePageStopAndTheClose_markStays() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)
        tracker.onPageStopsPlayer(p1, U, A, 5000, false, false, true, false, A)

        assertEquals(5000L, tracker.onReload(p1, U, 5000, false, true))
        assertEquals("the dying player's reload", SEEKS, tracker.load(p1, U, A))
        assertEquals("after its synchronization", 5000L, tracker.peekClosePosition(A))

        tracker.closed(p1, 30000, true, true)
        assertEquals("after the close of a player that played on", 5000L, tracker.peekClosePosition(A))

        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(SEEKS, tracker.load(p2, N, A))
    }

    /**
     * AM-1, AC-7, row 10: healthy again after an error, and the page loads another video and
     * stops the player. Rule 1 is refused (played on), rule 2 too (other address): no mark.
     */
    @Test
    fun am1_ac7_row10_recoveredPlayerStoppedByThePageForAnotherVideo_noMark() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onPageStopsPlayer(p1, U, A, 90000, false, false, true, true, B)
        assertNoCloseMark("after the page's stop")

        tracker.closed(p1, 90000, true, true)
        assertNoCloseMark("after the close")
        assertEquals(NO_POSITION, tracker.start(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /**
     * AM-1 and AM-2, row 10 with the page on the same video: rule 1 is refused (played on),
     * rule 2 applies. The mark is the position the video has now, not the old error position.
     */
    @Test
    fun am1_am2_row10_recoveredPlayerStoppedByThePageForItsOwnVideo_marksTheCurrentPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onPageStopsPlayer(p1, U, A, 90000, false, false, true, true, A)

        assertEquals(90000L, tracker.peekClosePosition(A))
    }

    // ---- rows 11, 12, 13: another video in between ----

    /** AM-1, AC-7, row 11: another video is started first; the mark is gone for good. */
    @Test
    fun am1_ac7_row11_anotherVideoStartedInBetween_noResume() {
        failAndClose()

        assertEquals("the other video", NO_POSITION, tracker.start(p2, V, B))
        assertNoCloseMark("after the start of another video")
        assertEquals(Mark.NONE, tracker.peekMark(p2, V))
        assertEquals(NO_SEEK, tracker.load(p2, V, B))

        assertEquals("the first video, later", NO_POSITION, tracker.start(p2, N, A))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /** AM-1, AC-7, row 12: another video is synchronized without a start intent: mark gone. */
    @Test
    fun am1_ac7_row12_anotherVideoSynchronized_markGone() {
        failAndClose()

        tracker.onSynchronize(p2, V, B)

        assertNoCloseMark("after another video")
        assertEquals(NO_POSITION, tracker.start(p3, N, A))
    }

    /**
     * AM-1, AC-7, row 12: the same video synchronized in another player without an intent
     * (it was loaded as a start, at 0:00): the mark must not wait for a later start.
     */
    @Test
    fun am1_ac7_row12_sameVideoSynchronizedInAnotherPlayerWithoutAnIntent_markGone() {
        failAndClose()

        assertEquals("that load is a start", NO_SEEK, tracker.load(p2, N, A))

        assertNoCloseMark("after the load")
        assertEquals(NO_POSITION, tracker.start(p3, N2, A))
    }

    /** AM-1, row 12: a synchronization that carries no address cannot keep the mark either. */
    @Test
    fun am1_row12_synchronizationWithoutAnAddress_markGone() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)
        tracker.onPageStopsPlayer(p1, U, A, 5000, false, false, true, false, A)

        tracker.onSynchronize(p1, U)

        assertNoCloseMark("after a synchronization without address")
    }

    /** AM-1, row 12: the dying player itself moves on to another video: mark gone. */
    @Test
    fun am1_ac7_row12_dyingPlayerSynchronizesAnotherVideo_markGone() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)
        tracker.onPageStopsPlayer(p1, U, A, 5000, false, false, true, false, A)
        assertEquals(5000L, tracker.peekClosePosition(A))

        tracker.onSynchronize(p1, V, B)

        assertNoCloseMark("after the next item")
        assertFalse(tracker.isFailing(p1))
    }

    /** AM-1, AC-7, row 13: the failing player goes to its next item: no longer failing, no mark. */
    @Test
    fun am1_ac7_row13_nextItemInTheFailingPlayer_endsTheFailingState() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onSynchronize(p1, V, B)
        assertFalse(tracker.isFailing(p1))

        tracker.closed(p1, 0, false, false)
        assertNoCloseMark("close on the next item")
        assertEquals(NO_POSITION, tracker.start(p2, N, A))
    }

    /** AM-1, row 13: the same when another player synchronizes anything. */
    @Test
    fun am1_ac7_row13_synchronizationInAnotherPlayer_endsTheFailingState() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onSynchronize(p2, U, A)
        assertFalse(tracker.isFailing(p1))

        tracker.closed(p1, 5000, false, false)
        assertNoCloseMark("close after another player took over")
    }

    /**
     * AM-1, AC-7, `onStartIntent` row 6: another video is started in the failing player
     * itself. The failing state ends with the intent, before anything is synchronized.
     */
    @Test
    fun am1_ac7_anotherVideoStartedInTheFailingPlayer_endsTheFailingState() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        assertEquals(NO_POSITION, tracker.onStartIntent(p1, U, A, 5000, false, false, false, V, B, false))

        assertFalse(tracker.isFailing(p1))
        tracker.closed(p1, 5000, false, false)
        assertNoCloseMark("close after another video was started")
    }

    // ---- row 14: errors that make no player failing ----

    private fun assertErrorIsIgnored(what: String, prepare: (KeepPositionTracker) -> Unit) {
        val t = KeepPositionTracker(FakeKeepPositionSettings())
        prepare(t)
        assertFalse("$what: failing", t.isFailing(p1))
        t.closed(p1, 5000, false, false)
        assertNoCloseMark(what, t)
        assertEquals("$what: the next start", NO_POSITION, t.start(p2, N, A))
    }

    /** AM-1, AC-10, row 14: a live stream has no position to keep. */
    @Test
    fun am1_ac10_row14_errorOfALiveStreamIsIgnored() {
        assertErrorIsIgnored("live") {
            it.onSynchronize(p1, U, A)
            it.onErrorBegin(p1, U, A, 5000, true, true)
        }
    }

    /** AM-1, row 14: without a queue item or without an address nothing can be named. */
    @Test
    fun am1_row14_errorWithoutItemOrAddressIsIgnored() {
        assertErrorIsIgnored("uuid null") {
            it.onSynchronize(p1, U, A)
            it.err(p1, null, A, 5000, true)
        }
        assertErrorIsIgnored("address null") {
            it.onSynchronize(p1, U, A)
            it.err(p1, U, null, 5000, true)
        }
    }

    /** AM-1, row 14: the position belongs to the item the player is synchronized on. */
    @Test
    fun am1_row14_errorInAPlayerNotSynchronizedOnTheItemIsIgnored() {
        assertErrorIsIgnored("nothing synchronized") {
            it.err(p1, U, A, 5000, true)
        }
        assertErrorIsIgnored("p1 is on another item") {
            it.onSynchronize(p1, V, B)
            it.err(p1, U, A, 5000, true)
        }
        assertErrorIsIgnored("another player is on the item") {
            it.onSynchronize(p2, U, A)
            it.err(p1, U, A, 5000, true)
        }
    }

    /** AM-1, row 14: at position 0 with a known duration there is nothing to continue at. */
    @Test
    fun am1_row14_errorAtZeroWithKnownDurationIsIgnored() {
        assertErrorIsIgnored("position 0, duration known") {
            it.onSynchronize(p1, U, A)
            it.err(p1, U, A, 0, true)
        }
        assertErrorIsIgnored("position 0, no duration, nothing carried") {
            it.onSynchronize(p1, U, A)
            it.err(p1, U, A, 0, false)
        }
    }

    /** AM-1, row 14: an ignored error leaves an existing failing state as it is. */
    @Test
    fun am1_row14_ignoredErrorLeavesTheFailingStateAsItIs() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onErrorBegin(p1, U, A, 60000, true, true)
        tracker.err(p1, null, A, 60000, true)
        tracker.err(p1, U, null, 60000, true)
        tracker.err(p1, V, B, 60000, true)
        tracker.err(p2, U, A, 60000, true)

        assertTrue(tracker.isFailing(p1))
        assertFalse(tracker.isFailing(p2))
        tracker.closed(p1, 5000, false, false)
        assertEquals(5000L, tracker.peekClosePosition(A))
    }

    // ---- rows 15, 16, 17: which position the failing state holds ----

    /** AM-1, row 15: errors of the retries keep the first error's position. */
    @Test
    fun am1_row15_errorsOfTheRetriesKeepTheFirstPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)
        tracker.onReload(p1, U, 5000, false, true)
        tracker.load(p1, U, A)

        tracker.err(p1, U, A, 5000, false)
        tracker.err(p1, U, A, 0, false)

        assertTrue(tracker.isFailing(p1))
        tracker.closed(p1, 0, false, false)
        assertEquals(5000L, tracker.peekClosePosition(A))
    }

    /** AM-1, row 16: played on after the first error, then a new error: the new position. */
    @Test
    fun am1_row16_newErrorAfterPlayingOn_marksTheNewPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.err(p1, U, A, 60000, true)

        tracker.closed(p1, 60000, true, false)
        assertEquals(60000L, tracker.peekClosePosition(A))
        assertEquals(60000L, tracker.start(p2, N, A))
    }

    /** AM-1, row 16 at the boundary: a second error within 2 seconds keeps the first position. */
    @Test
    fun am1_row16_secondErrorWithinTwoSeconds_keepsTheFirstPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.err(p1, U, A, 7000, true)

        tracker.closed(p1, 5000, true, false)
        assertEquals(5000L, tracker.peekClosePosition(A))
    }

    /** AM-1, row 16 at the boundary: a second error just over 2 seconds away is a new error. */
    @Test
    fun am1_row16_secondErrorJustOverTwoSecondsAway_marksTheNewPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.err(p1, U, A, 7001, true)

        tracker.closed(p1, 7001, true, false)
        assertEquals(7001L, tracker.peekClosePosition(A))
    }

    /**
     * AM-1 with AM-3, row 17: the error hits while the player is still loading a reload and
     * reports 0 without a duration. The position the reload's mark carried is the one the
     * video had.
     */
    @Test
    fun am1_am3_row17_errorWhileLoadingAReload_usesTheCarriedPosition() {
        tracker.onSynchronize(p1, U, A)
        tracker.onReload(p1, U, 5000, false, true)
        tracker.load(p1, U, A)

        tracker.err(p1, U, A, 0, false)
        assertTrue(tracker.isFailing(p1))
        tracker.closed(p1, 0, false, false)

        assertEquals(5000L, tracker.peekClosePosition(A))
        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(SEEKS, tracker.load(p2, N, A))
    }

    // ---- row 18: the two switches ----

    /** AM-1, AC-8, row 18: with the keep switch off the start gets no position and no seek. */
    @Test
    fun am1_ac8_row18_keepOff_startAfterAnErrorCloseBeginsAtZero() {
        failAndClose()
        settings.keepPosition = false

        assertEquals(NO_POSITION, tracker.start(p2, N, A))

        assertEquals("the mark is spent all the same", NO_POSITION, tracker.peekClosePosition(A))
        assertEquals("rule 3 of shouldSeek decides", Mark.RELOAD, tracker.peekMark(p2, N))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /**
     * AM-1, AC-9, row 18: with "Always start play from beginning" off the feature stores
     * nothing (upstream's own position is used) and every decision is upstream's open gate.
     */
    @Test
    fun am1_ac9_row18_alwaysStartOff_startGetsNoPosition_everyDecisionSeeks() {
        failAndClose()
        settings.alwaysStartFromBeginning = false

        assertEquals(NO_POSITION, tracker.start(p2, N, A))

        assertTrue(tracker.shouldSeekInPlace(p2, N, N, false))
        assertEquals(SEEKS, tracker.load(p2, N, A))
        assertEquals("also without any mark", SEEKS, tracker.load(p2, N, A))
    }

    /** AM-1, row 18: the switches are read at the start, not when the player failed. */
    @Test
    fun am1_row18_switchesAreReadWhenTheStartArrives() {
        settings.keepPosition = false
        failAndClose()
        settings.keepPosition = true

        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(SEEKS, tracker.load(p2, N, A))
    }

    // ---- rows 19, 20: timed start, same queue ----

    /**
     * AM-1 with AC-12 and AC-13, row 19: a timed start wins over a kept position. It gets no
     * position from the mark, the close mark is gone, and the load is a timed start (it seeks
     * even with the keep switch off).
     */
    @Test
    fun am1_ac12_ac13_row19_timedStartWinsOverTheCloseMark() {
        failAndClose()
        tracker.onTimedStart(N)

        assertEquals(NO_POSITION, tracker.start(p2, N, A))

        assertNoCloseMark("after a timed start")
        assertEquals(Mark.TIMED, tracker.peekMark(p2, N))
        settings.keepPosition = false
        assertEquals("timed load", SEEKS, tracker.load(p2, N, A))
        settings.keepPosition = true
        assertEquals("AC-14: second load", NO_SEEK, tracker.load(p2, N, A))
        assertEquals("AC-14: the next start of the video", NO_POSITION, tracker.start(p3, N2, A))
    }

    /**
     * AM-1, row 20, `onStartIntent` row 2: an intent with the player's own queue is not a
     * start of something else; the mark stays for the start that follows.
     */
    @Test
    fun am1_row20_intentWithTheSameQueueLeavesTheCloseMark() {
        failAndClose()

        assertEquals(
            NO_POSITION,
            tracker.onStartIntent(p2, null, null, 0, false, false, false, N, A, true)
        )

        assertEquals(5000L, tracker.peekClosePosition(A))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertEquals(5000L, tracker.start(p2, N, A))
    }

    /** AM-1, `onStartIntent` row 1: an intent whose queue names no item changes nothing. */
    @Test
    fun am1_intentWithoutANewItemOrAddressChangesNothing() {
        failAndClose()

        assertEquals("no uuid", NO_POSITION, tracker.start(p2, null, A))
        assertEquals("no address", NO_POSITION, tracker.start(p2, N, null))

        assertEquals(5000L, tracker.peekClosePosition(A))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
    }

    /** AM-1, `onStartIntent` rows 1 and 2 leave a failing player failing. */
    @Test
    fun am1_intentThatIsNoStartLeavesTheFailingState() {
        tracker.onSynchronize(p1, U, A)
        tracker.err(p1, U, A, 5000, true)

        tracker.onStartIntent(p1, U, A, 5000, false, false, false, null, null, false)
        tracker.onStartIntent(p1, U, A, 5000, false, false, false, U, A, true)

        assertTrue(tracker.isFailing(p1))
    }

    // ---- row 21 and the same address twice ----

    /**
     * AM-1, row 21: two closes, A at 5000 then B at 7000: only B's mark. (The slot is single;
     * A's mark is already dropped when the second player synchronizes B, row 12.)
     */
    @Test
    fun am1_ac7_row21_twoCloses_onlyTheSecondVideoIsKept() {
        failAndClose()

        tracker.onSynchronize(p2, V, B)
        tracker.err(p2, V, B, 7000, true)
        tracker.closed(p2, 7000, true, false)

        assertEquals(NO_POSITION, tracker.peekClosePosition(A))
        assertEquals(7000L, tracker.peekClosePosition(B))
        assertEquals("A, started next", NO_POSITION, tracker.start(p3, N, A))
        assertEquals("that start was another video: B's mark is gone too", NO_POSITION, tracker.peekClosePosition(B))
    }

    /**
     * AM-1, plan 10.3 "The same address twice": the close mark is keyed by address, so it
     * serves whichever copy of the video is started next, and only that one.
     */
    @Test
    fun am1_sameAddressTwice_closeMarkServesTheCopyThatIsStartedNext_once() {
        failAndClose()

        assertEquals(5000L, tracker.start(p2, N2, A))

        assertEquals(Mark.RELOAD, tracker.peekMark(p2, N2))
        assertEquals("the other copy in the new queue", Mark.NONE, tracker.peekMark(p2, N))
        assertEquals(SEEKS, tracker.load(p2, N2, A))
        assertEquals("the other copy, when its turn comes", NO_SEEK, tracker.load(p2, N, A))
    }
}
