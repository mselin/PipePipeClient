package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top-level rule functions that amendment 2 adds (plan 10.3, values of plan 10.7 "Rule
 * functions"). The glue and the tracker decide with them, so they are tested on their own:
 *
 * - `shouldStore`: when hooks H1 and H6 write the mark's position into the queue (AM-3).
 * - `itemAddress`: what "the same video" is (AM-1, AM-2).
 * - `hasPlayedOn`: whether a player that had an error has played on since (AM-1, A13).
 */
class KeepPositionAmendmentRulesTest {

    // ---- NO_POSITION ----

    /** Plan 10.3: `const val NO_POSITION = -1L`. The glue stores only positions above 0. */
    @Test
    fun am3_noPositionIsMinusOne() {
        assertEquals(-1L, NO_POSITION)
    }

    // ---- shouldStore: mark > 0 && stored <= 0 ----

    /** AM-3: the queue has nothing stored (`RECOVERY_UNSET` is `Long.MIN_VALUE`): store. */
    @Test
    fun am3_shouldStore_whenTheQueueHasNoPosition() {
        assertTrue(shouldStore(5000, Long.MIN_VALUE))
    }

    /** AM-3: a 0 written by a progress save counts as "none": store. */
    @Test
    fun am3_shouldStore_whenTheQueueHoldsZero() {
        assertTrue(shouldStore(5000, 0))
    }

    /** AC-5: upstream's own stored value stays where it has one; H1 and H6 only fill a gap. */
    @Test
    fun ac5_am3_shouldStore_neverOverwritesUpstreamsPosition() {
        assertFalse(shouldStore(5000, 4000))
        assertFalse(shouldStore(5000, 1))
        assertFalse(shouldStore(5000, 5000))
    }

    /** AM-3: without a mark position nothing is stored. */
    @Test
    fun am3_shouldStore_notWithoutAMarkPosition() {
        assertFalse(shouldStore(NO_POSITION, Long.MIN_VALUE))
        assertFalse(shouldStore(NO_POSITION, 0))
        assertFalse(shouldStore(0, 0))
        assertFalse(shouldStore(0, Long.MIN_VALUE))
    }

    /** The guard is `mark > 0`: the smallest position above 0 is stored. */
    @Test
    fun am3_shouldStore_atOneMillisecond() {
        assertTrue(shouldStore(1, Long.MIN_VALUE))
        assertTrue(shouldStore(1, -1))
    }

    // ---- itemAddress ----

    /** AM-1, AM-2: the address is "serviceId|url". */
    @Test
    fun am1_am2_itemAddress_isServiceIdAndUrl() {
        assertEquals("0|u", itemAddress(0, "u"))
        assertEquals("5|https://example.org/watch?v=a", itemAddress(5, "https://example.org/watch?v=a"))
    }

    /** AM-1, AM-2: the same url on another service is another video. */
    @Test
    fun am1_am2_itemAddress_differsByService() {
        assertNotEquals(itemAddress(0, "u"), itemAddress(1, "u"))
    }

    /** AM-1, AM-2: another url on the same service is another video; the same pair is the same. */
    @Test
    fun am1_am2_itemAddress_differsByUrl_andIsStable() {
        assertNotEquals(itemAddress(0, "u"), itemAddress(0, "v"))
        assertEquals(itemAddress(3, "u"), itemAddress(3, "u"))
    }

    /** AM-1, AM-2: without a url there is no address, so nothing can claim "the same video". */
    @Test
    fun am1_am2_itemAddress_isNullWithoutAUrl() {
        assertNull(itemAddress(0, null))
        assertNull(itemAddress(0, ""))
    }

    // ---- hasPlayedOn: isPlaying || (durationKnown && |pos - failedAt| > 2000) ----

    /** Plan 10.3: `MOVED_MS` is 2 seconds. */
    @Test
    fun am1_movedThresholdIsTwoSeconds() {
        assertEquals(2000L, KeepPositionTracker.MOVED_MS)
    }

    /** AM-1: still at the error position and not playing: has not played on. */
    @Test
    fun am1_hasPlayedOn_notAtTheErrorPosition() {
        assertFalse(hasPlayedOn(5000, 5000, true, false))
    }

    /** AM-1: exactly 2 seconds away is not "more than 2 seconds". */
    @Test
    fun am1_hasPlayedOn_notAtExactlyTwoSeconds() {
        assertFalse(hasPlayedOn(5000, 7000, true, false))
        assertFalse(hasPlayedOn(5000, 3000, true, false))
    }

    /** AM-1: more than 2 seconds further on, with a known duration: played on. */
    @Test
    fun am1_hasPlayedOn_moreThanTwoSecondsForward() {
        assertTrue(hasPlayedOn(5000, 7001, true, false))
    }

    /** AM-1: the distance counts in both directions (the user sought back and paused). */
    @Test
    fun am1_hasPlayedOn_moreThanTwoSecondsBackward() {
        assertTrue(hasPlayedOn(5000, 2999, true, false))
    }

    /**
     * AM-1, A13: a source that does not load has no duration. Whatever position such a player
     * reports, the retry loop cannot fake "played on".
     */
    @Test
    fun am1_hasPlayedOn_neverWithoutADurationWhileNotPlaying() {
        assertFalse(hasPlayedOn(5000, 90000, false, false))
        assertFalse(hasPlayedOn(5000, 0, false, false))
    }

    /** AM-1: a player that is playing right now has played on, wherever it is. */
    @Test
    fun am1_hasPlayedOn_whilePlaying() {
        assertTrue(hasPlayedOn(5000, 5000, false, true))
        assertTrue(hasPlayedOn(5000, 5000, true, true))
    }
}
