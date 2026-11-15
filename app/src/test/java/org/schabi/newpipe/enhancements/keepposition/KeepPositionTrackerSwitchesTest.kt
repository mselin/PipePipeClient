package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T = "uuid-T"
private const val U = "uuid-U"
private const val V = "uuid-V"

/**
 * The two switches as the tracker sees them.
 *
 * - AC-8: with the keep switch off the reload sequences of AC-2 to AC-4 restart at 0:00, as
 *   without the feature. (The other half of AC-8, timed starts still going to their time, is
 *   in [KeepPositionTrackerTimedStartTest], which runs with the keep switch off.)
 * - AC-9: with "Always start play from beginning" off every decision is `true`, as upstream's
 *   gate is, with or without marks.
 * - Plan section 3.1: "Settings are read at decision time, never when a mark is set."
 */
class KeepPositionTrackerSwitchesTest {

    private val settings = FakeKeepPositionSettings()
    private val tracker = KeepPositionTracker(settings)

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p = Any()
    private val p1 = Any()
    private val p2 = Any()

    // ---- AC-8: keep off ----

    /** Plan section 6, AC-8: the first AC-2 sequence -> false. The mark exists, rule 4 refuses it. */
    @Test
    fun ac8_keepOff_reloadInTheSamePlayerBeginsAtZero() {
        settings.keepPosition = false
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals("the reload's own load", NO_SEEK, tracker.load(p, U))
        assertEquals("a second load", NO_SEEK, tracker.load(p, U))
    }

    /** Plan section 6, AC-8: the second AC-2 sequence -> false. */
    @Test
    fun ac8_keepOff_switchThroughANewPlayerBeginsAtZero() {
        settings.keepPosition = false
        tracker.onSynchronize(p1, U)
        tracker.onSwitch(p1, U, 5000, false)
        tracker.onReload(p2, U, 0, false)

        assertEquals(NO_SEEK, tracker.load(p2, U))
    }

    /** Plan section 6, AC-8: the AC-3 / AC-4 sequence -> false both times. */
    @Test
    fun ac8_keepOff_reloadAndLoadTwiceInARowBeginsAtZeroBothTimes() {
        settings.keepPosition = false
        tracker.onSynchronize(p, U)

        tracker.onReload(p, U, 5000, false)
        assertEquals("first reload", NO_SEEK, tracker.load(p, U))
        tracker.onReload(p, U, 5000, false)
        assertEquals("second reload", NO_SEEK, tracker.load(p, U))
    }

    /** The switch inside the running player (seek in place asked first, then the reload). */
    @Test
    fun ac8_keepOff_switchInsideTheRunningPlayerBeginsAtZero() {
        settings.keepPosition = false
        tracker.onSynchronize(p, U)

        assertFalse(tracker.shouldSeekInPlace(p, U, U, true))
        tracker.onReload(p, U, 5000, false)

        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    // ---- AC-9: always-start off ----

    private fun alwaysStartOffTracker(keep: Boolean) = KeepPositionTracker(
        FakeKeepPositionSettings(alwaysStartFromBeginning = false, keepPosition = keep)
    )

    /**
     * Plan section 6, AC-9: all three tracker decisions are true without any mark, for both
     * keep values.
     */
    @Test
    fun ac9_alwaysStartOff_allThreeDecisionsSeekWithoutAnyMark() {
        for (keep in listOf(true, false)) {
            val tracker = alwaysStartOffTracker(keep)

            assertEquals("keep=$keep", Mark.NONE, tracker.peekMark(p, U))
            assertTrue("keep=$keep: seek in place", tracker.shouldSeekInPlace(p, U, U, false))
            assertTrue("keep=$keep: seek in place, player change", tracker.shouldSeekInPlace(p, U, V, true))
            assertTrue("keep=$keep: seek in place, no items", tracker.shouldSeekInPlace(p, null, null, false))
            assertTrue("keep=$keep: load decision", tracker.shouldSeekOnLoad(p, U))
            assertTrue("keep=$keep: decision without synchronization", tracker.shouldSeekOnSynchronize(p, U))
            tracker.onSynchronize(p, U)
            assertTrue("keep=$keep: decision at the synchronization", tracker.shouldSeekOnSynchronize(p, U))
            assertTrue("keep=$keep: the same decision again", tracker.shouldSeekOnSynchronize(p, U))
        }
    }

    /**
     * Spec AC-9: "exactly as it does without this feature". Upstream's gate is open for every
     * load while the switch is off: starts, reloads, spent marks, timed starts alike.
     */
    @Test
    fun ac9_alwaysStartOff_everyLoadSeeks_aroundReloadsAndTimedStarts() {
        for (keep in listOf(true, false)) {
            val tracker = alwaysStartOffTracker(keep)

            assertEquals("keep=$keep: a start", SEEKS, tracker.load(p, U))
            tracker.onReload(p, U, 5000, false)
            assertEquals("keep=$keep: a reload", SEEKS, tracker.load(p, U))
            assertEquals("keep=$keep: a load after the reload", SEEKS, tracker.load(p, U))
            assertEquals("keep=$keep: the next video", SEEKS, tracker.load(p, V))
            assertEquals("keep=$keep: the earlier video", SEEKS, tracker.load(p, U))
            assertEquals("keep=$keep: in another player", SEEKS, tracker.load(p2, U))
            tracker.onTimedStart(T)
            assertEquals("keep=$keep: a timed start", SEEKS, tracker.load(p, T))
            assertEquals("keep=$keep: the same item again", SEEKS, tracker.load(p, T))
            assertTrue("keep=$keep: seek in place, spent mark", tracker.shouldSeekInPlace(p, T, T, false))
        }
    }

    // ---- settings are read at decision time ----

    /** The keep switch is turned off while a reload is in flight: the load obeys the new value. */
    @Test
    fun ac8_settingsAreReadAtDecisionTime_keepTurnedOffAfterTheReloadBegan() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        settings.keepPosition = false

        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    /** The mark is set whatever the keep switch says; turned on before the load, it counts. */
    @Test
    fun ac2_settingsAreReadAtDecisionTime_keepTurnedOnAfterTheReloadBegan() {
        settings.keepPosition = false
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        settings.keepPosition = true

        assertEquals(SEEKS, tracker.load(p, U))
    }

    /** The grant holds a mark, not a decision: each of the two decisions reads the switch itself. */
    @Test
    fun ac8_settingsAreReadAtDecisionTime_keepTurnedOffBetweenTheTwoDecisionsOfALoad() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)

        assertTrue("load decision, keep on", tracker.shouldSeekOnLoad(p, U))
        tracker.onSynchronize(p, U)
        settings.keepPosition = false

        assertFalse("decision at the synchronization, keep off", tracker.shouldSeekOnSynchronize(p, U))
    }

    /** A mark set while always-start was off counts once the switch is on at the load. */
    @Test
    fun ac2_settingsAreReadAtDecisionTime_reloadMarkSetWhileAlwaysStartWasOff() {
        settings.alwaysStartFromBeginning = false
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)
        assertEquals(Mark.RELOAD, tracker.peekMark(p, U))

        settings.alwaysStartFromBeginning = true

        assertEquals(SEEKS, tracker.load(p, U))
    }

    /** The same for a timed mark, with the keep switch off. */
    @Test
    fun ac12_ac13_settingsAreReadAtDecisionTime_timedMarkSetWhileAlwaysStartWasOff() {
        settings.alwaysStartFromBeginning = false
        settings.keepPosition = false
        tracker.onTimedStart(T)

        settings.alwaysStartFromBeginning = true

        assertEquals(SEEKS, tracker.load(p, T))
    }

    /** Always-start turned off: from then on every decision is upstream's open gate. */
    @Test
    fun ac9_settingsAreReadAtDecisionTime_alwaysStartTurnedOff() {
        assertEquals("always-start on", NO_SEEK, tracker.load(p, U))

        settings.alwaysStartFromBeginning = false

        assertEquals("always-start off", SEEKS, tracker.load(p, U))
        assertTrue("seek in place", tracker.shouldSeekInPlace(p, U, U, false))
    }

    /** Always-start turned on: what was loaded before left no mark, every start begins at 0:00. */
    @Test
    fun ac7_settingsAreReadAtDecisionTime_alwaysStartTurnedOn() {
        settings.alwaysStartFromBeginning = false
        assertEquals("always-start off", SEEKS, tracker.load(p, U))
        assertEquals("always-start off", SEEKS, tracker.load(p, V))

        settings.alwaysStartFromBeginning = true

        assertEquals("the earlier video", NO_SEEK, tracker.load(p, U))
        assertEquals("the next video", NO_SEEK, tracker.load(p, V))
        assertFalse("seek in place", tracker.shouldSeekInPlace(p, V, V, false))
    }
}
