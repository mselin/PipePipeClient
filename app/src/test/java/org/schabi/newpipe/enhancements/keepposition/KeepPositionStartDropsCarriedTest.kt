package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private const val U = "uuid-U"
private const val A = "0|https://example.org/a"

/**
 * Spec AM-2 and AC-7, questions.md Q15/A15: a start that claims nothing must not inherit the
 * position an earlier reload of the same item carried. Otherwise a re-initialised player, whose
 * new ExoPlayer reports 0 without a duration, would continue at that older position where the
 * spec says 0:00.
 *
 * Before every test U (address A) plays in p and was reloaded once at 5000, so the tracker
 * carries 5000 for U.
 */
class KeepPositionStartDropsCarriedTest {
    private val tracker = KeepPositionTracker(FakeKeepPositionSettings())
    private val p = Any()

    @Before
    fun uPlaysInPAndWasReloadedAt5000() {
        tracker.onSynchronize(p, U, A)
        assertEquals(5000L, tracker.onReload(p, U, 5000, false, true))
        assertEquals(SEEKS, tracker.load(p, U, A))
    }

    /** Control: without a start in between, a reload at 0 without a duration takes the carried position (AM-3). */
    @Test
    fun am3_control_withoutAStartTheCarriedPositionIsUsed() {
        assertEquals(5000L, tracker.onReload(p, U, 0, false, false))
    }

    /** Row 6: the video had ended; the start claims nothing, and the re-initialised player starts at 0:00. */
    @Test
    fun am2_ac7_startOfTheEndedVideoDropsWhatWasCarried() {
        assertEquals(NO_POSITION, tracker.onStartIntent(p, U, A, 60_000, false, true, true, U, A, false))

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(NO_SEEK, tracker.load(p, U, A))
    }

    /** Row 4 without a position: the video sits at 0 with a known duration; nothing is claimed or inherited. */
    @Test
    fun am2_ac7_startOfTheVideoAtZeroDropsWhatWasCarried() {
        assertEquals(NO_POSITION, tracker.onStartIntent(p, U, A, 0, false, false, true, U, A, false))

        assertEquals(NO_POSITION, tracker.onReload(p, U, 0, false, false))
        assertEquals(NO_SEEK, tracker.load(p, U, A))
    }

    /** Row 2: an equal queue is no new start; what was carried stays (AM-3 still holds there). */
    @Test
    fun am3_equalQueueKeepsWhatWasCarried() {
        assertEquals(NO_POSITION, tracker.onStartIntent(p, U, A, 0, false, false, false, U, A, true))

        assertEquals(5000L, tracker.onReload(p, U, 0, false, false))
    }
}
