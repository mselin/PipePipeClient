package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val U = "uuid-U"
private const val V = "uuid-V"
private const val LIVE = "uuid-live"

/**
 * Reloads of the playing video: the reload mark of plan section 2, set by `onReload` (H1) and
 * `onSwitch` (H6) and spent by the reload's own load. Rows `onReload`, `onSwitch`, `peekMark`
 * and `shouldSeekOnLoad` of the method table in plan section 3.1.
 *
 * Both switches are on, the case of AC-2 to AC-5. That a start is never taken for a reload
 * is in [KeepPositionTrackerStartTest]; the keep switch off is in
 * [KeepPositionTrackerSwitchesTest].
 */
class KeepPositionTrackerReloadTest {

    private val tracker = KeepPositionTracker(FakeKeepPositionSettings())

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p = Any()
    private val p1 = Any()
    private val p2 = Any()

    // ---- AC-2: player switch ----

    /** Plan section 6, AC-2, first sequence. */
    @Test
    fun ac2_reloadInTheSamePlayerContinues_andASecondLoadDoesNot() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals("the reload's own load", SEEKS, tracker.load(p, U))
        assertEquals("a second load, without a reload", NO_SEEK, tracker.load(p, U))
    }

    /**
     * Plan section 6, AC-2, second sequence: the popup's fullscreen button stops the service,
     * a new player continues. Its `initPlayback` reloads at position 0, which sets nothing.
     */
    @Test
    fun ac2_switchThroughANewPlayerContinues_andASecondLoadDoesNot() {
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 5000, false)
        tracker.onReload(p2, U, 0, false)

        assertEquals("the new player's load", SEEKS, tracker.load(p2, U))
        assertEquals("a second load, without a reload", NO_SEEK, tracker.load(p2, U))
    }

    /**
     * After the switch the new player is the one that is on the item (row `onSynchronize`:
     * `synced = (p, u)`), so its own reloads (quality, audio track, error recovery) continue.
     */
    @Test
    fun ac2_ac3_ac4_reloadInTheNewPlayerAfterASwitchContinues() {
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 5000, false)
        tracker.onReload(p2, U, 0, false)
        assertEquals("the switch", SEEKS, tracker.load(p2, U))

        tracker.onReload(p2, U, 7000, false)

        assertEquals("the mark of the new player", Mark.RELOAD, tracker.peekMark(p2, U))
        assertEquals("not of the old one", Mark.NONE, tracker.peekMark(p1, U))
        assertEquals("the reload in the new player", SEEKS, tracker.load(p2, U))
    }

    /** Row `onSwitch`: owner ANY. The mark fits every player, but only the switched item. */
    @Test
    fun ac2_switchMarkFitsEveryPlayer_butOnlyTheSwitchedItem() {
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 5000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p1, U))
        assertEquals(Mark.RELOAD, tracker.peekMark(p2, U))
        assertEquals(Mark.NONE, tracker.peekMark(p1, V))
        assertEquals(Mark.NONE, tracker.peekMark(p2, V))
    }

    /**
     * Spec AC-2 (A6): where the main player does not start by itself after the popup's
     * fullscreen button, pressing play still continues, "unless another video was started in
     * between". The other video's synchronization discards the mark.
     */
    @Test
    fun ac2_ac7_afterASwitch_anotherVideoStartedFirstEndsTheSwitch() {
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 5000, false)

        assertEquals("the other video", NO_SEEK, tracker.load(p2, V))
        assertEquals("the switched video, started after it", NO_SEEK, tracker.load(p2, U))
    }

    /**
     * A switch between main, background and popup inside one service passes the seek in place
     * first (H5): the intent's queue is a copy of the playing one, so the link item is the
     * playing item and carries no timed mark. No seek in place, as today; then the reload.
     */
    @Test
    fun ac2_switchInsideTheRunningPlayer_noSeekInPlace_thenTheReloadContinues() {
        tracker.onSynchronize(p, U)

        assertFalse(tracker.shouldSeekInPlace(p, U, U, true))
        tracker.onReload(p, U, 5000, false)

        assertEquals(SEEKS, tracker.load(p, U))
    }

    /** Rows `peekMark` and `shouldSeekOnLoad`: "no change", however often they are asked. */
    @Test
    fun ac2_askingForTheMarkOrTheLoadDecisionDoesNotSpendTheMark() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        repeat(3) {
            assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
            assertTrue(tracker.shouldSeekOnLoad(p, U))
        }
        tracker.onSynchronize(p, U)
        assertTrue(tracker.shouldSeekOnSynchronize(p, U))
    }

    /** Plan section 2: "A failed attempt changes nothing." Every guard of `onReload` / `onSwitch`. */
    @Test
    fun ac2_failedAttemptLeavesAnExistingMarkAsItIs() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        val failing = listOf<Pair<String, () -> Unit>>(
            "onReload at position 0" to { tracker.onReload(p, U, 0, false) },
            "onReload without queue item" to { tracker.onReload(p, null, 5000, false) },
            "onReload of a live stream" to { tracker.onReload(p, U, 5000, true) },
            "onReload of an item p is not on" to { tracker.onReload(p, V, 5000, false) },
            "onReload by a player that is not on U" to { tracker.onReload(p2, U, 5000, false) },
            "onSwitch at position 0" to { tracker.onSwitch(p, U, 0, false) },
            "onSwitch without queue item" to { tracker.onSwitch(p, null, 5000, false) },
            "onSwitch of a live stream" to { tracker.onSwitch(p, U, 5000, true) },
            "onSwitch of an item p is not on" to { tracker.onSwitch(p, V, 5000, false) },
            "onSwitch by a player that is not on U" to { tracker.onSwitch(p2, U, 5000, false) }
        )
        for ((what, attempt) in failing) {
            attempt()
            assertEquals("after $what: the mark of p", Mark.RELOAD, tracker.peekMark(p, U))
            assertEquals("after $what: still owned by p alone", Mark.NONE, tracker.peekMark(p2, U))
            assertEquals("after $what: nothing for V", Mark.NONE, tracker.peekMark(p, V))
        }
        assertEquals(SEEKS, tracker.load(p, U))
    }

    /** Row `onSwitch`: a successful attempt sets the mark anew, here with owner ANY. */
    @Test
    fun ac2_switchAfterAReloadOfTheSameItem_fitsEveryPlayer() {
        tracker.onSynchronize(p1, U)
        tracker.onReload(p1, U, 5000, false)
        assertEquals(Mark.NONE, tracker.peekMark(p2, U))

        tracker.onSwitch(p1, U, 6000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p2, U))
        assertEquals(SEEKS, tracker.load(p2, U))
    }

    // ---- AC-3, AC-4: quality or audio track change, error recovery ----

    /** Plan section 6, AC-3 and AC-4: reload and load twice in a row (an error loop). */
    @Test
    fun ac3_ac4_reloadAndLoadTwiceInARow_continuesBothTimes() {
        tracker.onSynchronize(p, U)

        tracker.onReload(p, U, 5000, false)
        assertEquals("first reload", SEEKS, tracker.load(p, U))
        tracker.onReload(p, U, 5000, false)
        assertEquals("second reload", SEEKS, tracker.load(p, U))
    }

    /**
     * A second reload before the first one's load synchronized (two quality picks, an error
     * during the reload). Whether the second attempt passes the guards (position above 0) or
     * fails them (position 0), the item keeps its mark.
     */
    @Test
    fun ac3_ac4_reloadRepeatedBeforeItsLoad_stillContinues() {
        tracker.onSynchronize(p, U)

        tracker.onReload(p, U, 5000, false)
        tracker.onReload(p, U, 0, false)
        assertEquals("after a failed second attempt", Mark.RELOAD, tracker.peekMark(p, U))
        tracker.onReload(p, U, 5000, false)
        assertEquals("after a third attempt", Mark.RELOAD, tracker.peekMark(p, U))

        assertEquals(SEEKS, tracker.load(p, U))
    }

    // ---- AC-5: the guards of onReload / onSwitch ----

    /** Plan section 6, AC-5: `pos <= 0`. There is no position to continue at. */
    @Test
    fun ac5_reloadWithoutAPositionSetsNoMark() {
        tracker.onSynchronize(p, U)

        tracker.onReload(p, U, 0, false)
        assertEquals("onReload at 0", Mark.NONE, tracker.peekMark(p, U))
        tracker.onReload(p, U, -1, false)
        assertEquals("onReload at -1", Mark.NONE, tracker.peekMark(p, U))
        tracker.onSwitch(p, U, 0, false)
        assertEquals("onSwitch at 0", Mark.NONE, tracker.peekMark(p, U))
        tracker.onSwitch(p, U, -1, false)
        assertEquals("onSwitch at -1", Mark.NONE, tracker.peekMark(p, U))

        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    /** The guard is `pos > 0`: the smallest position above 0 is a position. */
    @Test
    fun ac5_reloadAtOneMillisecondSetsTheMark() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 1, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
    }

    /** The same boundary for `onSwitch`. */
    @Test
    fun ac5_switchAtOneMillisecondSetsTheMark() {
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 1, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p2, U))
    }

    /**
     * Plan section 6, AC-5: `u == null`, the queue has no current item. `peekMark` takes a
     * uuid, so the test asks for the item the player is synchronized on.
     */
    @Test
    fun ac5_reloadWithoutAQueueItemSetsNoMark() {
        tracker.onSynchronize(p, U)

        tracker.onReload(p, null, 5000, false)
        assertEquals("onReload", Mark.NONE, tracker.peekMark(p, U))
        tracker.onSwitch(p, null, 5000, false)
        assertEquals("onSwitch", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("onSwitch, another player", Mark.NONE, tracker.peekMark(p2, U))
    }

    /** Plan section 6, AC-5: not synchronized. Nothing was synchronized at all. */
    @Test
    fun ac5_reloadBeforeAnythingWasSynchronizedSetsNoMark() {
        tracker.onReload(p, U, 5000, false)
        assertEquals("onReload", Mark.NONE, tracker.peekMark(p, U))
        tracker.onSwitch(p, U, 5000, false)
        assertEquals("onSwitch", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("onSwitch, another player", Mark.NONE, tracker.peekMark(p2, U))
    }

    /**
     * Plan section 6, AC-5: not synchronized. The player is on V while the queue already
     * points at U: the position is V's, not U's.
     */
    @Test
    fun ac5_reloadOfAnItemThePlayerIsNotOnSetsNoMark() {
        tracker.onSynchronize(p, V)

        tracker.onReload(p, U, 5000, false)
        assertEquals("onReload, U", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("onReload, V", Mark.NONE, tracker.peekMark(p, V))
        tracker.onSwitch(p, U, 5000, false)
        assertEquals("onSwitch, U", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("onSwitch, U, another player", Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("onSwitch, V", Mark.NONE, tracker.peekMark(p, V))
    }

    /**
     * Plan section 6, AC-5: not synchronized. Another player is on U; a player that never
     * synchronized it (a new player, AC-7) cannot mark it.
     */
    @Test
    fun ac5_ac7_reloadByAPlayerThatNeverSynchronizedTheItemSetsNoMark() {
        tracker.onSynchronize(p1, U)

        tracker.onReload(p2, U, 5000, false)
        assertEquals("onReload, for p2", Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("onReload, for p1", Mark.NONE, tracker.peekMark(p1, U))
        tracker.onSwitch(p2, U, 5000, false)
        assertEquals("onSwitch, for p2", Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("onSwitch, for p1", Mark.NONE, tracker.peekMark(p1, U))

        assertEquals(NO_SEEK, tracker.load(p2, U))
    }

    // ---- AC-10: live streams ----

    /** Plan section 6, AC-10: `onReload` with `live = true`. */
    @Test
    fun ac10_reloadOfALiveStreamSetsNoMark() {
        tracker.onSynchronize(p, LIVE)
        tracker.onReload(p, LIVE, 5000, true)

        assertEquals(Mark.NONE, tracker.peekMark(p, LIVE))
        assertEquals(NO_SEEK, tracker.load(p, LIVE))
    }

    /** Plan section 6, AC-10: `onSwitch` with `live = true`. */
    @Test
    fun ac10_switchOfALiveStreamSetsNoMark() {
        tracker.onSynchronize(p1, LIVE)
        tracker.onSwitch(p1, LIVE, 5000, true)

        assertEquals(Mark.NONE, tracker.peekMark(p1, LIVE))
        assertEquals(Mark.NONE, tracker.peekMark(p2, LIVE))
        assertEquals(NO_SEEK, tracker.load(p2, LIVE))
    }
}
