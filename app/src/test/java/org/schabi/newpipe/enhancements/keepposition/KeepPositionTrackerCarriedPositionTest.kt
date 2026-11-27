package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val U = "uuid-U"
private const val V = "uuid-V"
private const val T = "uuid-T"
private const val A = "0|https://example.org/a"
private const val B = "0|https://example.org/b"

/**
 * Spec AM-3 (extends AC-3 and AC-4): a reload that begins before the previous reload has
 * finished loading keeps the position the video had.
 *
 * Plan 10.3: the state `carried`, the table "Position of a mark", the return value of
 * `onReload` / `onSwitch` (the position the glue of H1 and H6 stores when the queue has none)
 * and the table "State machine for AM-3", whose step letters are in the test names.
 *
 * Before every test U (address A) plays in p: `onSynchronize(p, U, A)` is done. Both switches
 * are on unless a test says otherwise.
 */
class KeepPositionTrackerCarriedPositionTest {

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

    /** Steps a. and c.: a reload at 5000 and its load. Afterwards the mark is spent. */
    private fun reloadAt5000AndLoad() {
        assertEquals("a. reload", 5000L, tracker.onReload(p, U, 5000, false, true))
        assertEquals("c. load", SEEKS, tracker.load(p, U, A))
        assertEquals("c. the mark is spent", Mark.NONE, tracker.peekMark(p, U))
    }

    // ---- the state machine ----

    /** AM-3, step a.: a reload returns the position its mark carries. */
    @Test
    fun am3_stepA_reloadReturnsItsPosition() {
        assertEquals(5000L, tracker.onReload(p, U, 5000, false, true))

        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
    }

    /**
     * AM-3, steps a. to c.: a second reload before the first was synchronized, reporting 0
     * without a duration, keeps the mark and returns the first reload's position.
     */
    @Test
    fun am3_stepsAtoC_secondReloadAtZeroBeforeTheFirstWasSynchronized_keepsThePosition() {
        assertEquals("a.", 5000L, tracker.onReload(p, U, 5000, false, true))

        assertEquals("b.", 5000L, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))

        assertEquals("c.", SEEKS, tracker.load(p, U, A))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
    }

    /** AM-3, steps a. to c., the other variant of b.: the player still reports 5000. */
    @Test
    fun am3_stepsAtoC_secondReloadAtTheSamePositionBeforeTheFirstWasSynchronized() {
        assertEquals("a.", 5000L, tracker.onReload(p, U, 5000, false, true))

        assertEquals("b.", 5000L, tracker.onReload(p, U, 5000, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))

        assertEquals("c.", SEEKS, tracker.load(p, U, A))
    }

    /**
     * AM-3, steps a. to e., "this is the fix": a reload after the synchronization of c. and
     * before its source is prepared reports 0 without a duration. It sets the mark and
     * returns 5000 for the glue to store; its load seeks.
     */
    @Test
    fun am3_stepsAtoE_reloadAtZeroBeforeTheSourceIsPrepared_continuesAtTheCarriedPosition() {
        reloadAt5000AndLoad()

        assertEquals("d.", 5000L, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))

        assertEquals("e.", SEEKS, tracker.load(p, U, A))
        assertEquals("a load without a reload", NO_SEEK, tracker.load(p, U, A))
    }

    /** AM-3, steps a. to e., the other variant of d.: the player reports 5000 without a duration. */
    @Test
    fun am3_stepsAtoE_reloadAtTheSamePositionBeforeTheSourceIsPrepared_continues() {
        reloadAt5000AndLoad()

        assertEquals("d.", 5000L, tracker.onReload(p, U, 5000, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))

        assertEquals("e.", SEEKS, tracker.load(p, U, A))
    }

    /**
     * AM-3 (M11), steps d. and e. any number of times: several failed recoveries in a row
     * all go back to where the video was.
     */
    @Test
    fun am3_stepsDandE_repeated_everyReloadContinuesAtTheSamePosition() {
        reloadAt5000AndLoad()

        repeat(5) { round ->
            assertEquals("round $round: reload", 5000L, tracker.onReload(p, U, 0, false, false))
            assertEquals("round $round: load", SEEKS, tracker.load(p, U, A))
        }
    }

    /** AM-3, step f.: playing again, a later reload carries the new position from then on. */
    @Test
    fun am3_stepF_laterReloadCarriesTheNewPosition() {
        reloadAt5000AndLoad()
        tracker.onReload(p, U, 0, false, false)
        tracker.load(p, U, A)

        assertEquals("f.", 9000L, tracker.onReload(p, U, 9000, false, true))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U, A))

        assertEquals("the next early reload", 9000L, tracker.onReload(p, U, 0, false, false))
    }

    /**
     * AM-3 with AC-7, steps g. and h.: another item was played in between. Back at U at 0
     * without a duration nothing is carried: no mark, no position, 0:00.
     */
    @Test
    fun am3_ac7_stepsGandH_anotherItemInBetween_nothingIsCarried() {
        reloadAt5000AndLoad()

        tracker.onSynchronize(p, V, B)
        tracker.onSynchronize(p, U, A)

        assertEquals("h.", NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, U, A))
    }

    /** AM-3 with AC-7, step g.: the other item gets nothing from U's carried position either. */
    @Test
    fun am3_ac7_stepG_carriedPositionGivesNothingToAnotherItem() {
        reloadAt5000AndLoad()

        tracker.onSynchronize(p, V, B)

        assertEquals(NO_POSITION, tracker.onReload(p, V, 0, false, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, V))
    }

    // ---- "Position of a mark" ----

    /** AM-3, AC-5, position row 1: a position above 0 is the position, whatever is carried. */
    @Test
    fun am3_ac5_positionRow1_reportedPositionWinsOverTheCarriedOne() {
        reloadAt5000AndLoad()

        assertEquals("without duration", 7000L, tracker.onReload(p, U, 7000, false, false))
        tracker.load(p, U, A)
        assertEquals("with duration", 8000L, tracker.onReload(p, U, 8000, false, true))
    }

    /** AM-3, position row 2: 0 without a duration, and a carried position that fits. */
    @Test
    fun am3_positionRow2_zeroWithoutDurationTakesTheCarriedPosition() {
        reloadAt5000AndLoad()

        assertEquals(5000L, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
    }

    /** AM-3, position row 2 also covers a negative position reported without a duration. */
    @Test
    fun am3_positionRow2_negativePositionWithoutDurationTakesTheCarriedPosition() {
        reloadAt5000AndLoad()

        assertEquals(5000L, tracker.onReload(p, U, -1, false, false))
    }

    /**
     * AM-3 with AC-5, position row 3: with a known duration a 0 is a real 0. No mark, no
     * position, even though a position is carried; and the carried position is left alone.
     */
    @Test
    fun am3_ac5_positionRow3_zeroWithKnownDurationIsARealZero() {
        reloadAt5000AndLoad()

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, true))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))

        assertEquals("a failed attempt changes nothing", 5000L, tracker.onReload(p, U, 0, false, false))
    }

    /** AM-3 with AC-5, position row 3: 0 without a duration and nothing carried. */
    @Test
    fun am3_ac5_positionRow3_zeroWithoutDurationAndNothingCarried() {
        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, U, A))
    }

    /** AM-3: the other guards of `onReload` still refuse, whatever is carried. */
    @Test
    fun am3_ac10_guardsOfOnReloadStillRefuse_whateverIsCarried() {
        reloadAt5000AndLoad()

        assertEquals("live", NO_POSITION, tracker.onReload(p, U, 0, true, false))
        assertEquals("no item", NO_POSITION, tracker.onReload(p, null, 0, false, false))
        assertEquals("another player", NO_POSITION, tracker.onReload(p2, U, 0, false, false))
        assertEquals("another item", NO_POSITION, tracker.onReload(p, V, 0, false, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(Mark.NONE, tracker.peekMark(p2, U))
    }

    // ---- onSwitch: the same rows, owner ANY ----

    /** AM-3, `onSwitch` row 1 of the position table, and its return value. */
    @Test
    fun am3_switch_returnsItsPosition() {
        assertEquals(5000L, tracker.onSwitch(p, U, 5000, false, true))
        assertEquals("any player", Mark.RELOAD, tracker.peekMark(p2, U))
    }

    /** AM-3, `onSwitch` row 2: the popup's fullscreen button pressed while a reload is loading. */
    @Test
    fun am3_switch_zeroWithoutDurationTakesTheCarriedPosition() {
        reloadAt5000AndLoad()

        assertEquals(5000L, tracker.onSwitch(p, U, 0, false, false))

        assertEquals(Mark.RELOAD, tracker.peekMark(p2, U))
        assertEquals(SEEKS, tracker.load(p2, U, A))
    }

    /** AM-3, `onSwitch` row 3: a real 0, or nothing carried. */
    @Test
    fun am3_switch_zeroWithKnownDurationOrNothingCarried_noMark() {
        assertEquals("nothing carried", NO_POSITION, tracker.onSwitch(p, U, 0, false, false))
        reloadAt5000AndLoad()
        assertEquals("known duration", NO_POSITION, tracker.onSwitch(p, U, 0, false, true))

        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(Mark.NONE, tracker.peekMark(p2, U))
    }

    /**
     * AM-3 with AC-2: the position a switch carried belongs to any player, so the new player
     * that took the video over can go back to it when it reloads before its source is ready.
     */
    @Test
    fun am3_ac2_switch_carriedPositionFollowsTheVideoIntoTheNewPlayer() {
        val t = KeepPositionTracker(FakeKeepPositionSettings())
        t.onSynchronize(p1, U, A)
        assertEquals(5000L, t.onSwitch(p1, U, 5000, false, true))
        assertEquals("the new player's first reload: not on U yet", NO_POSITION, t.onReload(p2, U, 0, false, false))
        assertEquals("the new player's load", SEEKS, t.load(p2, U, A))

        assertEquals("its early reload", 5000L, t.onReload(p2, U, 0, false, false))
        assertEquals(SEEKS, t.load(p2, U, A))
    }

    // ---- the return value and the two switches ----

    /** AM-3, AC-8: keep off: the mark is set (rule 3 decides later) but no position is returned. */
    @Test
    fun am3_ac8_keepOff_reloadReturnsNoPositionWhileTheMarkIsSet() {
        settings.keepPosition = false

        assertEquals(NO_POSITION, tracker.onReload(p, U, 5000, false, true))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(NO_POSITION, tracker.onSwitch(p, U, 5000, false, true))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, U, A))
    }

    /** AM-3, AC-9: always-start off: the feature stores nothing; upstream's gate is open. */
    @Test
    fun am3_ac9_alwaysStartOff_reloadReturnsNoPositionWhileTheMarkIsSet() {
        settings.alwaysStartFromBeginning = false

        assertEquals(NO_POSITION, tracker.onReload(p, U, 5000, false, true))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(NO_POSITION, tracker.onSwitch(p, U, 5000, false, true))
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U, A))
    }

    /** AM-3: a failed attempt returns `NO_POSITION`. */
    @Test
    fun am3_failedAttemptReturnsNoPosition() {
        assertEquals("live", NO_POSITION, tracker.onReload(p, U, 5000, true, true))
        assertEquals("no item", NO_POSITION, tracker.onReload(p, null, 5000, false, true))
        assertEquals("not synchronized", NO_POSITION, tracker.onReload(p2, U, 5000, false, true))
        assertEquals("position 0", NO_POSITION, tracker.onReload(p, U, 0, false, true))
        assertEquals("switch, live", NO_POSITION, tracker.onSwitch(p, U, 5000, true, true))
        assertEquals("switch, not synchronized", NO_POSITION, tracker.onSwitch(p2, U, 5000, false, true))
    }

    /** AM-3: `durationKnown` defaults to true, so the calls of the first version keep their meaning. */
    @Test
    fun am3_ac5_durationKnownDefaultsToTrue() {
        reloadAt5000AndLoad()

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false))
        assertEquals(NO_POSITION, tracker.onSwitch(p, U, 0, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
    }

    // ---- whose position it is ----

    /** AM-3 with AC-7: the carried position of p1 gives nothing to p2 (plan 10.7). */
    @Test
    fun am3_ac7_carriedPositionOfOnePlayerGivesNothingToAnother() {
        val t = KeepPositionTracker(FakeKeepPositionSettings())
        t.onSynchronize(p1, U)
        assertEquals(5000L, t.onReload(p1, U, 5000, false, true))
        assertEquals(SEEKS, t.load(p1, U))

        t.onSynchronize(p2, U)

        assertEquals(NO_POSITION, t.onReload(p2, U, 0, false, false))
        assertEquals(Mark.NONE, t.peekMark(p2, U))
    }

    /**
     * AM-3 with AC-12: after a seek in place was granted, the position moved to the link's
     * time. A reload at 0 without a duration sets no mark from the older carried position.
     */
    @Test
    fun am3_ac12_afterAGrantedSeekInPlace_theOlderCarriedPositionIsGone() {
        reloadAt5000AndLoad()
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p, T, U, false))

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
    }

    /**
     * AM-3 with AC-12: the same when a reload follows the seek in place (the player type
     * changed). The mark that `shouldSeekInPlace` set stays a timed one; the reload at 0 gets
     * no position from the older carried one.
     */
    @Test
    fun am3_ac12_afterAGrantedSeekInPlaceWithAReload_noPositionFromTheOlderCarriedOne() {
        reloadAt5000AndLoad()
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p, T, U, true))

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(Mark.TIMED, tracker.peekMark(p, U))
    }

    /** AM-3: a refused seek in place moved nothing, so the carried position stays. */
    @Test
    fun am3_afterARefusedSeekInPlace_theCarriedPositionStays() {
        reloadAt5000AndLoad()

        assertFalse(tracker.shouldSeekInPlace(p, U, U, true))

        assertEquals(5000L, tracker.onReload(p, U, 0, false, false))
    }

    /** AM-3: the carried position survives the synchronization of its own item, with or without address. */
    @Test
    fun am3_carriedPositionSurvivesSynchronizationsOfItsOwnItem() {
        reloadAt5000AndLoad()

        tracker.onSynchronize(p, U, A)
        tracker.onSynchronize(p, U)

        assertEquals(5000L, tracker.onReload(p, U, 0, false, false))
    }
}
