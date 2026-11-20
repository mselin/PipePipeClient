package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two top-level functions of plan section 3.1.
 *
 * `shouldSeek` is the rule table "first match wins", one test per rule. Together the five
 * rule tests state the result for all twelve combinations of the three inputs
 * (2 x 2 x 3): rule 1 six, rule 2 two, rule 3 one, rule 4 three.
 *
 * `isTimedLinkStart` is `startPositionSeconds > 0 && !isRoundPlay && !isLive`; the four tests
 * state the result for all eight combinations of "has a start time", round play and live.
 */
class ShouldSeekRulesTest {

    private fun assertSeek(
        expected: Boolean,
        alwaysStart: Boolean,
        keep: Boolean,
        mark: Mark
    ) {
        assertEquals(
            "shouldSeek(alwaysStartFromBeginning=$alwaysStart, keepPosition=$keep, mark=$mark)",
            expected,
            shouldSeek(alwaysStart, keep, mark)
        )
    }

    private fun assertTimedLinkStart(
        expected: Boolean,
        seconds: Long,
        roundPlay: Boolean,
        live: Boolean
    ) {
        assertEquals(
            "isTimedLinkStart(startPositionSeconds=$seconds, isRoundPlay=$roundPlay, isLive=$live)",
            expected,
            isTimedLinkStart(seconds, roundPlay, live)
        )
    }

    // ---- shouldSeek ----

    /** Rule 1: always-start off is upstream's behaviour, the gate is open for everything. */
    @Test
    fun ac9_rule1_alwaysStartOff_seeksForEveryMarkAndEveryKeepValue() {
        for (keep in listOf(true, false)) {
            for (mark in listOf(Mark.NONE, Mark.RELOAD, Mark.TIMED)) {
                assertSeek(true, alwaysStart = false, keep = keep, mark = mark)
            }
        }
    }

    /** Rule 2: a timed start has no setting of its own; the keep switch does not matter. */
    @Test
    fun ac8_ac12_ac13_ac14_rule2_timedMarkSeeksWhateverKeepIs() {
        assertSeek(true, alwaysStart = true, keep = true, mark = Mark.TIMED)
        assertSeek(true, alwaysStart = true, keep = false, mark = Mark.TIMED)
    }

    /** Rule 3: a reload continues while the keep switch is on. */
    @Test
    fun ac2_ac3_ac4_rule3_reloadMarkSeeksWhileKeepIsOn() {
        assertSeek(true, alwaysStart = true, keep = true, mark = Mark.RELOAD)
    }

    /** Rule 4: with the keep switch off a reload restarts at 0:00, as without the feature. */
    @Test
    fun ac8_rule4_reloadMarkDoesNotSeekWhileKeepIsOff() {
        assertSeek(false, alwaysStart = true, keep = false, mark = Mark.RELOAD)
    }

    /** Rule 4: a start (and a live stream, which never gets a mark) begins at 0:00 as today. */
    @Test
    fun ac7_ac10_rule4_withoutAMarkNothingSeeksWhateverKeepIs() {
        assertSeek(false, alwaysStart = true, keep = true, mark = Mark.NONE)
        assertSeek(false, alwaysStart = true, keep = false, mark = Mark.NONE)
    }

    // ---- isTimedLinkStart ----

    /** Plan section 6, AC-13: (90, false, false) is true. */
    @Test
    fun ac13_startTimeOfAnOrdinaryVideoIsATimedStart() {
        assertTimedLinkStart(true, seconds = 90, roundPlay = false, live = false)
        assertTimedLinkStart(true, seconds = 1, roundPlay = false, live = false)
    }

    /** Plan section 6, AC-13: (0, false, false) is false. An address without a time is a plain start. */
    @Test
    fun ac13_withoutAStartTimeThereIsNoTimedStart() {
        for (roundPlay in listOf(false, true)) {
            for (live in listOf(false, true)) {
                assertTimedLinkStart(false, seconds = 0, roundPlay = roundPlay, live = live)
                assertTimedLinkStart(false, seconds = -1, roundPlay = roundPlay, live = live)
            }
        }
    }

    /**
     * Plan section 6, AC-13: (90, true, false) is false. Round play reports the round's
     * elapsed time as start position without a link (spec, "Not covered").
     */
    @Test
    fun ac13_roundPlayIsNotATimedStart() {
        assertTimedLinkStart(false, seconds = 90, roundPlay = true, live = false)
    }

    /**
     * Plan section 6, AC-10: (90, false, true) is false. A live stream is not exempt (A7).
     *
     * Q10 (tests -> plan, open): this proves the live exception for a video link with a start
     * time (H8) only. For a timestamp link (H7) the live check sits in the Android glue
     * (`KeepPosition.onTimestampLink`), which JVM tests do not reach, and the tracker cannot
     * tell: `onTimedStart` takes only a uuid. Assumed until Q10 is answered: that half of
     * AC-10 is verified outside these tests.
     */
    @Test
    fun ac10_startTimeOfALiveStreamIsNotATimedStart() {
        assertTimedLinkStart(false, seconds = 90, roundPlay = false, live = true)
        assertTimedLinkStart(false, seconds = 90, roundPlay = true, live = true)
    }
}
