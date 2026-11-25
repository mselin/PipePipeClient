package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val U = "uuid-U"
private const val N = "uuid-N"
private const val N2 = "uuid-N2"
private const val V = "uuid-V"
private const val A = "0|https://example.org/a"
private const val B = "0|https://example.org/b"

/**
 * Spec AM-2 (extends AC-2; A12, A14): a start of the video that is playing keeps its
 * position: a switch between players with a queue that differs from the player's, a tap that
 * starts the playing video again in the same player, and pressing play after the page stopped
 * the player for the very video it shows. Every other start stays a start (AC-7).
 *
 * Plan 10.3: `onStartIntent` rows 2 to 6 (hook G1), `onPageStopsPlayer` rule 2 (hook F1) and
 * "Checks for row 4". The row numbers in the test names are those of the table "AM-2" in
 * plan 10.7.
 *
 * Before every test U (address A) plays in p at 5000 ms: `onSynchronize(p, U, A)` is done. N
 * is the item of the new queue for the same video, V another video (address B). Both
 * switches are on unless a test says otherwise.
 */
class KeepPositionTrackerSameVideoStartTest {

    private val settings = FakeKeepPositionSettings()
    private val tracker = KeepPositionTracker(settings)

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p = Any()
    private val p1 = Any()
    private val p2 = Any()

    @Before
    fun uPlaysInP() {
        tracker.onSynchronize(p, U, A)
    }

    /** `tap(...)` of plan 10.7: `onStartIntent(p, U, A, 5000, false, false, true, N, A, false)`. */
    private fun tap(
        positionMs: Long = 5000,
        isLive: Boolean = false,
        isEnded: Boolean = false,
        durationKnown: Boolean = true,
        newUuid: String? = N,
        newAddress: String? = A,
        sameQueue: Boolean = false,
        player: Any = p,
        playingUuid: String? = U,
        playingAddress: String? = A
    ): Long = tracker.onStartIntent(
        player, playingUuid, playingAddress, positionMs, isLive, isEnded, durationKnown,
        newUuid, newAddress, sameQueue
    )

    private fun assertNoMarkAndAStartAtZero(what: String) {
        assertEquals("$what: mark for N", Mark.NONE, tracker.peekMark(p, N))
        assertEquals("$what: mark for U", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("$what: load of N", NO_SEEK, tracker.load(p, N, A))
    }

    // ---- rows 1, 2: the tap continues ----

    /** AM-2, row 1: the new queue's item gets the position and a reload mark of this player. */
    @Test
    fun am2_row1_startOfThePlayingVideoKeepsItsPosition() {
        assertEquals(5000L, tap())

        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))
        assertEquals("the old item gets nothing", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("owned by the player the intent arrived at", Mark.NONE, tracker.peekMark(p2, N))
    }

    /**
     * AM-2, row 2: `initPlayback` and the reload after a type change call `onReload` with the
     * new ExoPlayer (position 0, no duration, not yet synchronized on N). They change nothing;
     * the load seeks, once.
     */
    @Test
    fun am2_row2_reloadsOfTheNewPlayerLeaveTheMark_loadSeeksOnce() {
        tap()

        assertEquals("initPlayback", NO_POSITION, tracker.onReload(p, N, 0, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))
        assertEquals("type change", NO_POSITION, tracker.onReload(p, N, 0, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))

        assertEquals("the start's load", SEEKS, tracker.load(p, N, A))
        assertEquals("a second load", NO_SEEK, tracker.load(p, N, A))
    }

    /** AM-2, AC-7, row 2: a later load of N that no tap claimed begins at 0:00. */
    @Test
    fun am2_ac7_row2_laterLoadWithoutATapBeginsAtZero() {
        tap()
        assertEquals(SEEKS, tracker.load(p, N, A))
        assertEquals("next item", NO_SEEK, tracker.load(p, V, B))

        assertEquals("back to N", NO_SEEK, tracker.load(p, N, A))
    }

    /** AM-2, row 1: the player type does not matter, so a switch with a new queue is the same call. */
    @Test
    fun am2_row1_withASingleReloadAsAfterAPlayerSwitch_continues() {
        assertEquals(5000L, tap())
        tracker.onReload(p, N, 0, false, false)

        assertEquals(SEEKS, tracker.load(p, N, A))
    }

    /** AM-2, AC-7: after the tap another video is loaded instead: N has no mark left. */
    @Test
    fun am2_ac7_anotherVideoLoadedAfterTheTap_endsTheClaim() {
        tap()

        tracker.onSynchronize(p, V, B)

        assertEquals(Mark.NONE, tracker.peekMark(p, N))
        assertEquals(NO_SEEK, tracker.load(p, N, A))
    }

    // ---- rows 3 to 6: what is not "a start of the video that is playing" ----

    /** AM-2, row 3: an ended video restarts at 0:00, as today. */
    @Test
    fun am2_row3_endedVideoRestartsAtZero() {
        assertEquals(NO_POSITION, tap(isEnded = true))

        assertNoMarkAndAStartAtZero("ended")
    }

    /**
     * AM-2, row 4, `onStartIntent` row 2: with the player's own queue nothing is decided here
     * (H1 and H5 handle it); an existing reload mark for U is untouched.
     */
    @Test
    fun am2_row4_sameQueue_nothingChanges_existingReloadMarkStays() {
        tracker.onReload(p, U, 5000, false, true)

        assertEquals(NO_POSITION, tap(sameQueue = true))

        assertEquals(Mark.NONE, tracker.peekMark(p, N))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U, A))
    }

    /** AM-2, AC-7, row 5: a start of another video gets nothing. */
    @Test
    fun am2_ac7_row5_startOfAnotherVideoBeginsAtZero() {
        assertEquals(NO_POSITION, tap(newUuid = V, newAddress = B))

        assertEquals(Mark.NONE, tracker.peekMark(p, V))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, V, B))
    }

    /** AM-2, AC-10, row 6: a live stream has no position to keep. */
    @Test
    fun am2_ac10_row6_liveStreamGetsNothing() {
        assertEquals(NO_POSITION, tap(isLive = true))

        assertNoMarkAndAStartAtZero("live")
    }

    /** AM-2, row 6: at position 0 with a known duration there is nothing to keep. */
    @Test
    fun am2_row6_positionZeroWithKnownDurationGetsNothing() {
        assertEquals(NO_POSITION, tap(positionMs = 0))

        assertNoMarkAndAStartAtZero("position 0")
    }

    /** AM-2, row 6: at position 0 without a duration and without a carried position: nothing. */
    @Test
    fun am2_row6_positionZeroWithoutDurationAndNothingCarriedGetsNothing() {
        assertEquals(NO_POSITION, tap(positionMs = 0, durationKnown = false))

        assertNoMarkAndAStartAtZero("position 0, no duration")
    }

    /**
     * AM-2, row 6: the queue already points at U, but the player was last synchronized on V:
     * the position it reports is not U's.
     */
    @Test
    fun am2_row6_playerNotSynchronizedOnThePlayingItemGetsNothing() {
        tracker.onSynchronize(p, V, B)

        assertEquals(NO_POSITION, tap())

        assertNoMarkAndAStartAtZero("not synchronized")
    }

    /** AM-2, row 6: the intent arrives at a player that plays nothing, or at another player. */
    @Test
    fun am2_ac7_row6_playerThatIsNotOnTheVideoGetsNothing() {
        assertEquals("no playing item", NO_POSITION, tap(playingUuid = null, playingAddress = null))
        assertEquals(Mark.NONE, tracker.peekMark(p, N))

        assertEquals("another player claims U", NO_POSITION, tap(player = p2))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    // ---- row 7: a timed start wins ----

    /**
     * AM-2 with AC-12 and AC-13, row 7: a link with a time to the playing video goes to the
     * link's time. The intent gives it no position; the load is a timed start, once.
     */
    @Test
    fun am2_ac12_ac13_row7_timedStartWinsOverThePlayersPosition() {
        tracker.onTimedStart(N)

        assertEquals(NO_POSITION, tap())

        assertEquals(Mark.TIMED, tracker.peekMark(p, N))
        settings.keepPosition = false
        assertEquals("timed even with keep off", SEEKS, tracker.load(p, N, A))
        settings.keepPosition = true
        assertEquals("AC-14: second load", NO_SEEK, tracker.load(p, N, A))
    }

    // ---- row 8: the two switches ----

    /** AM-2, AC-8, row 8: keep off: no position, the mark is set, the load does not seek. */
    @Test
    fun am2_ac8_row8_keepOff_startOfThePlayingVideoBeginsAtZero() {
        settings.keepPosition = false

        assertEquals(NO_POSITION, tap())

        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))
        assertEquals(NO_SEEK, tracker.load(p, N, A))
    }

    /** AM-2, AC-9, row 8: always-start off: no position from the feature, the load seeks as upstream. */
    @Test
    fun am2_ac9_row8_alwaysStartOff_noPositionFromTheFeature_loadSeeks() {
        settings.alwaysStartFromBeginning = false

        assertEquals(NO_POSITION, tap())

        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))
        assertEquals(SEEKS, tracker.load(p, N, A))
    }

    // ---- rows 9, 10, 11: together with the other marks ----

    /**
     * AM-2 with AC-2, row 9: the popup's fullscreen button (H6) set an any-player mark; the
     * intent that follows reaches a new player and is `onStartIntent` row 6, which must leave
     * that mark alone.
     */
    @Test
    fun am2_ac2_row9_intentAfterThePopupsFullscreenButtonLeavesTheSwitchMark() {
        val t = KeepPositionTracker(FakeKeepPositionSettings())
        t.onSynchronize(p1, U, A)
        t.onSwitch(p1, U, 5000, false)

        assertEquals(NO_POSITION, t.start(p2, U, A))

        assertEquals(Mark.RELOAD, t.peekMark(p2, U))
        assertEquals(SEEKS, t.load(p2, U, A))
    }

    /**
     * AM-2 over AM-1, row 10: a close mark for A at 4000 exists (the page stopped the failing
     * player), but the player is still there and on the video at 5000: row 4 wins, the close
     * mark is gone.
     */
    @Test
    fun am2_am1_row10_livingPlayersPositionWinsOverACloseMark() {
        tracker.err(p, U, A, 4000, true)
        tracker.onPageStopsPlayer(p, U, A, 4000, false, false, true, false, A)
        assertEquals("precondition", 4000L, tracker.peekClosePosition(A))

        assertEquals(5000L, tap())

        assertEquals(NO_POSITION, tracker.peekClosePosition(A))
        assertFalse(tracker.isFailing(p))
        assertEquals(SEEKS, tracker.load(p, N, A))
        assertEquals("nothing is left for a later start", NO_POSITION, tracker.start(p2, N2, A))
    }

    /**
     * AM-2 with AM-3, row 11: the tap arrives while the player is still loading a reload and
     * reports 0 without a duration: the position the reload's mark carried.
     */
    @Test
    fun am2_am3_row11_tapWhileAReloadIsLoading_usesTheCarriedPosition() {
        tracker.onReload(p, U, 5000, false, true)
        tracker.load(p, U, A)

        assertEquals(5000L, tap(positionMs = 0, durationKnown = false))

        assertEquals(Mark.RELOAD, tracker.peekMark(p, N))
        assertEquals(SEEKS, tracker.load(p, N, A))
    }

    // ---- rows 12 to 15: the page stops the player for its own video (A14) ----

    /**
     * AM-2 (A14), row 12: Autoplay is not allowed, the page has loaded the video the player is
     * on and stops the player. Pressing play continues.
     */
    @Test
    fun am2_row12_pageStopsThePlayerForItsOwnVideo_playContinues() {
        tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, A)
        assertEquals("after the page's stop", 5000L, tracker.peekClosePosition(A))

        tracker.closed(p, 5000, true, true)
        assertEquals("after the close", 5000L, tracker.peekClosePosition(A))

        assertEquals(5000L, tracker.start(p2, N, A))
        assertEquals(Mark.RELOAD, tracker.peekMark(p2, N))
        assertEquals(SEEKS, tracker.load(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /** AM-2, row 12 for a paused player: rule 2 does not ask whether it is playing. */
    @Test
    fun am2_row12_pageStopsThePausedPlayerForItsOwnVideo_playContinues() {
        tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, false, A)
        tracker.closed(p, 5000, true, false)

        assertEquals(5000L, tracker.start(p2, N, A))
    }

    /** AM-2 with AM-3, row 12 while a reload is loading: the carried position is marked. */
    @Test
    fun am2_am3_row12_pageStopsThePlayerWhileAReloadIsLoading_marksTheCarriedPosition() {
        tracker.onReload(p, U, 5000, false, true)
        tracker.load(p, U, A)

        tracker.onPageStopsPlayer(p, U, A, 0, false, false, false, false, A)

        assertEquals(5000L, tracker.peekClosePosition(A))
    }

    private fun assertPageStopSetsNoMark(what: String, stop: () -> Unit) {
        stop()
        assertEquals("$what: after the page's stop", NO_POSITION, tracker.peekClosePosition(A))
        assertEquals("$what: nothing for B", NO_POSITION, tracker.peekClosePosition(B))
        tracker.closed(p, 5000, true, true)
        assertEquals("$what: after the close", NO_POSITION, tracker.peekClosePosition(A))
        assertEquals("$what: the start", NO_POSITION, tracker.start(p2, N, A))
        assertEquals("$what: the load", NO_SEEK, tracker.load(p2, N, A))
    }

    /** AM-2, AC-7, row 13: the page shows another video: the player is closed, a start is a start. */
    @Test
    fun am2_ac7_row13_pageShowsAnotherVideo_noMark() {
        assertPageStopSetsNoMark("page on B") {
            tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, B)
        }
    }

    /** AM-2, row 13: the page has no address. */
    @Test
    fun am2_row13_pageWithoutAnAddress_noMark() {
        assertPageStopSetsNoMark("page without address") {
            tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, null)
        }
    }

    /** AM-2, row 13: the video had ended. */
    @Test
    fun am2_row13_endedVideo_noMark() {
        assertPageStopSetsNoMark("ended") {
            tracker.onPageStopsPlayer(p, U, A, 5000, false, true, true, false, A)
        }
    }

    /** AM-2, AC-10, row 13: a live stream. */
    @Test
    fun am2_ac10_row13_liveStream_noMark() {
        assertPageStopSetsNoMark("live") {
            tracker.onPageStopsPlayer(p, U, A, 5000, true, false, true, true, A)
        }
    }

    /** AM-2, row 13: position 0 with a known duration. */
    @Test
    fun am2_row13_positionZeroWithKnownDuration_noMark() {
        assertPageStopSetsNoMark("position 0") {
            tracker.onPageStopsPlayer(p, U, A, 0, false, false, true, true, A)
        }
    }

    /** AM-2, rule 2: the player has no item or no address, or is not synchronized on the item. */
    @Test
    fun am2_row13_playerWithoutItemOrNotSynchronized_noMark() {
        tracker.onPageStopsPlayer(p, null, A, 5000, false, false, true, true, A)
        assertEquals("no uuid", NO_POSITION, tracker.peekClosePosition(A))
        tracker.onPageStopsPlayer(p, U, null, 5000, false, false, true, true, null)
        assertEquals("no address", NO_POSITION, tracker.peekClosePosition(A))
        tracker.onPageStopsPlayer(p2, U, A, 5000, false, false, true, true, A)
        assertEquals("another player", NO_POSITION, tracker.peekClosePosition(A))
        tracker.onSynchronize(p, V, B)
        tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, A)
        assertEquals("p is on V", NO_POSITION, tracker.peekClosePosition(A))
    }

    /** AM-2, AC-7, row 14: another video is started before play is pressed: both at 0:00. */
    @Test
    fun am2_ac7_row14_anotherVideoStartedBeforePlayIsPressed_noResume() {
        tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, A)
        tracker.closed(p, 5000, true, true)

        assertEquals("the other video", NO_POSITION, tracker.start(p2, V, B))
        assertEquals(NO_SEEK, tracker.load(p2, V, B))
        assertEquals("the first video", NO_POSITION, tracker.start(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /** AM-2, AC-7: the mark of the page's stop is used once. */
    @Test
    fun am2_ac7_markOfThePagesStopIsUsedOnce() {
        tracker.onPageStopsPlayer(p, U, A, 5000, false, false, true, true, A)
        tracker.closed(p, 5000, true, true)
        assertEquals(5000L, tracker.start(p1, N, A))
        assertEquals(SEEKS, tracker.load(p1, N, A))
        tracker.closed(p1, 5000, true, false)

        assertEquals(NO_POSITION, tracker.start(p2, N2, A))
        assertEquals(NO_SEEK, tracker.load(p2, N2, A))
    }

    /**
     * AC-7 as it stands after AM-1 and AM-2, row 15: a player closed by the user with no error
     * and no page stop, playing or paused; pressing play begins at 0:00.
     */
    @Test
    fun am2_ac7_row15_playerClosedNormally_sameVideoStartsAtZero() {
        tracker.closed(p, 5000, true, true)

        assertEquals(NO_POSITION, tracker.peekClosePosition(A))
        assertEquals(NO_POSITION, tracker.start(p2, N, A))
        assertEquals(Mark.NONE, tracker.peekMark(p2, N))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    /** The same for a player closed while paused. */
    @Test
    fun am2_ac7_row15_pausedPlayerClosedNormally_sameVideoStartsAtZero() {
        tracker.closed(p, 5000, true, false)

        assertEquals(NO_POSITION, tracker.start(p2, N, A))
        assertEquals(NO_SEEK, tracker.load(p2, N, A))
    }

    // ---- plan 10.3, "The same address twice" ----

    /**
     * AM-2, AC-7: the player is on V; the video A sits elsewhere in its queue. A start of A is
     * a start: only the current items are compared.
     */
    @Test
    fun am2_ac7_sameAddressTwice_playerOnAnotherItemOfTheQueue_startBeginsAtZero() {
        tracker.onSynchronize(p, V, B)

        assertEquals(NO_POSITION, tap(playingUuid = V, playingAddress = B))

        assertEquals(Mark.NONE, tracker.peekMark(p, N))
        assertEquals(NO_SEEK, tracker.load(p, N, A))
    }

    /**
     * AM-2, AC-7: only the new queue's current item gets the mark; a second copy of the video
     * in the new queue begins at 0:00 when its turn comes.
     */
    @Test
    fun am2_ac7_sameAddressTwice_onlyTheCurrentItemOfTheNewQueueContinues() {
        assertEquals(5000L, tap())

        assertEquals(Mark.NONE, tracker.peekMark(p, N2))
        assertFalse(tracker.shouldSeekOnLoad(p, N2))
        assertTrue(tracker.shouldSeekOnLoad(p, N))
        assertEquals("the current item", SEEKS, tracker.load(p, N, A))
        assertEquals("the second copy", NO_SEEK, tracker.load(p, N2, A))
    }

    /**
     * AM-2: the player's own item and the new queue's item may be two copies of the video
     * with different uuids or the same uuid: the address decides.
     */
    @Test
    fun am2_startOfThePlayingVideoIsDecidedByAddress_notByUuid() {
        assertEquals(5000L, tap(newUuid = U))

        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U, A))
    }
}
