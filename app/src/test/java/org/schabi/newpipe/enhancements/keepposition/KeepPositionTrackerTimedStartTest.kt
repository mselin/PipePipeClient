package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T = "uuid-T"
private const val U = "uuid-U"
private const val V = "uuid-V"

/**
 * Timed starts: a timestamp link (AC-12) and a video link with a start time (AC-13). Both
 * end in `onTimedStart` for the uuid of the queue built for that tap (hooks H7, H8); the
 * tracker cannot tell them apart, so the sequences are named after both ACs. Whether a link
 * start is a timed start at all is `isTimedLinkStart`, see [ShouldSeekRulesTest].
 *
 * Rows `onTimedStart`, `shouldSeekInPlace`, `peekMark` and `onSynchronize` of the method
 * table in plan section 3.1, and spec AC-14: a timed start is the only exempt start, and its
 * mark serves at most one load.
 *
 * "Always start play from beginning" is on and the keep switch is **off**, as plan section 6
 * states for AC-12: a seek can then only come from a timed mark (rule 2), never from a reload
 * mark (rule 3). These sequences are therefore also the "AC-12 sequences" of AC-8.
 */
class KeepPositionTrackerTimedStartTest {

    private val tracker = KeepPositionTracker(FakeKeepPositionSettings(keepPosition = false))

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p = Any()
    private val p1 = Any()
    private val p2 = Any()

    // ---- AC-12, AC-13: the timed start loads at its time ----

    /** Plan section 6, AC-12 (and AC-8, AC-13): `onTimedStart(T)`, load T -> true. */
    @Test
    fun ac8_ac12_ac13_keepOff_timedStartLoadsAtItsTime() {
        tracker.onTimedStart(T)

        assertEquals(Mark.TIMED, tracker.peekMark(p, T))
        assertEquals(SEEKS, tracker.load(p, T))
    }

    /** Spec AC-12, AC-13: "whatever" the two switches are set to. */
    @Test
    fun ac12_ac13_timedStartLoadsAtItsTime_whateverTheTwoSwitchesAre() {
        for (alwaysStart in listOf(true, false)) {
            for (keep in listOf(true, false)) {
                val tracker = KeepPositionTracker(FakeKeepPositionSettings(alwaysStart, keep))

                tracker.onTimedStart(T)

                assertEquals("alwaysStart=$alwaysStart keep=$keep", SEEKS, tracker.load(p, T))
            }
        }
    }

    /**
     * Row `peekMark`: "TIMED if u is in `timed`", for any player. The mark is set where the
     * queue is built, before it is known which player loads it: the one that is playing
     * another video, or a new one.
     */
    @Test
    fun ac12_ac13_timedMarkFitsWhicheverPlayerLoadsTheItem() {
        tracker.onSynchronize(p1, U)
        tracker.onTimedStart(T)

        assertEquals("the player that is on another video", Mark.TIMED, tracker.peekMark(p1, T))
        assertEquals("a new player", Mark.TIMED, tracker.peekMark(p2, T))
        assertEquals(SEEKS, tracker.load(p2, T))
    }

    /** Rows `peekMark` and `shouldSeekOnLoad`: "no change", however often they are asked. */
    @Test
    fun ac12_ac13_askingForTheMarkOrTheLoadDecisionDoesNotSpendTheTimedMark() {
        tracker.onTimedStart(T)

        repeat(3) {
            assertEquals(Mark.TIMED, tracker.peekMark(p, T))
            assertTrue(tracker.shouldSeekOnLoad(p, T))
        }
        tracker.onSynchronize(p, T)
        assertTrue(tracker.shouldSeekOnSynchronize(p, T))
    }

    /**
     * Row `peekMark`: the timed mark is looked at first. An item that has a timed mark and a
     * reload mark is a timed start, so it seeks with the keep switch off.
     */
    @Test
    fun ac12_ac13_timedMarkWinsOverAReloadMarkOfTheSameItem() {
        tracker.onSynchronize(p, U)
        tracker.onReload(p, U, 5000, false)
        tracker.onTimedStart(U)

        assertEquals(Mark.TIMED, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U))
    }

    // ---- AC-12: the playing video alone in its queue is sought in place ----

    /** Plan section 6, AC-12 (and AC-8, AC-13): `shouldSeekInPlace(p,T,U,false)` true once, then false. */
    @Test
    fun ac8_ac12_ac13_keepOff_seekInPlaceIsGrantedOnceThenRefused() {
        tracker.onTimedStart(T)

        assertTrue("first tap", tracker.shouldSeekInPlace(p, T, U, false))
        assertFalse("the same queue item again", tracker.shouldSeekInPlace(p, T, U, false))
    }

    /** Row `shouldSeekInPlace`: without a reload to follow it only removes the timed mark. */
    @Test
    fun ac12_seekInPlaceWithoutPlayerChangeLeavesNoMarkBehind() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p, T, U, false))

        assertEquals("the link item", Mark.NONE, tracker.peekMark(p, T))
        assertEquals("the playing item", Mark.NONE, tracker.peekMark(p, U))
    }

    /**
     * Plan section 6, AC-12 (and AC-8, AC-13): `sync(p,U)`, `onTimedStart(T)`,
     * `shouldSeekInPlace(p,T,U,true)`, `onReload(p,U,90000,false)`, load U -> true.
     * The tap also changes the player type, so a reload follows the seek in place; it must
     * keep the link's time even with the keep switch off.
     */
    @Test
    fun ac8_ac12_ac13_keepOff_reloadAfterTheSeekInPlaceKeepsTheLinkTime() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p, T, U, true))
        tracker.onReload(p, U, 90000, false)

        assertEquals(Mark.TIMED, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U))
    }

    /**
     * The same, but the reload's own attempt sets nothing (position 0, the seek has not
     * arrived yet): the mark that `shouldSeekInPlace` set stays as it is.
     */
    @Test
    fun ac12_reloadAfterTheSeekInPlace_whoseOwnAttemptFails_keepsTheLinkTime() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p, T, U, true))
        assertEquals("set by the seek in place", Mark.TIMED, tracker.peekMark(p, U))
        tracker.onReload(p, U, 0, false)

        assertEquals("after the failed attempt", Mark.TIMED, tracker.peekMark(p, U))
        assertEquals(SEEKS, tracker.load(p, U))
    }

    /** Row `shouldSeekInPlace`: `reload = (playing, p, true)`, owned by that player, for that item. */
    @Test
    fun ac12_markSetByTheSeekInPlaceFitsOnlyItsPlayerAndThePlayingItem() {
        tracker.onSynchronize(p1, U)
        tracker.onTimedStart(T)

        assertTrue(tracker.shouldSeekInPlace(p1, T, U, true))

        assertEquals("the playing item, its player", Mark.TIMED, tracker.peekMark(p1, U))
        assertEquals("the playing item, another player", Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("another item", Mark.NONE, tracker.peekMark(p1, V))
        assertEquals("the link item", Mark.NONE, tracker.peekMark(p1, T))
    }

    /**
     * Row `onSwitch`: "the same with owner ANY", so it takes over the timed flag of a mark
     * that fits its player and item. Any player then loads the item as a timed start.
     */
    @Test
    fun ac12_switchAfterTheSeekInPlaceKeepsTheLinkTimeForAnyPlayer() {
        tracker.onSynchronize(p1, U)
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p1, T, U, true))

        tracker.onSwitch(p1, U, 90000, false)

        assertEquals(Mark.TIMED, tracker.peekMark(p2, U))
        assertEquals(SEEKS, tracker.load(p2, U))
    }

    /** Row `shouldSeekInPlace`: no playing item, no reload mark; the timed mark is spent all the same. */
    @Test
    fun ac12_seekInPlaceWithoutAPlayingItemIsGrantedOnce() {
        tracker.onTimedStart(T)

        assertTrue("first", tracker.shouldSeekInPlace(p, T, null, true))
        assertFalse("second", tracker.shouldSeekInPlace(p, T, null, true))
        assertEquals(Mark.NONE, tracker.peekMark(p, T))
    }

    /**
     * Row `shouldSeekInPlace`: a link item without a timed mark is no timed start, whatever
     * else is marked. No seek in place (as today), no reload mark, and the timed marks of
     * other items stay.
     */
    @Test
    fun ac7_seekInPlaceWithoutATimedMarkIsRefusedAndSetsNoMark() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)

        assertFalse("unmarked link item", tracker.shouldSeekInPlace(p, V, U, true))
        assertFalse("the playing item as link item", tracker.shouldSeekInPlace(p, U, U, true))
        assertFalse("no link item", tracker.shouldSeekInPlace(p, null, U, true))

        assertEquals("no reload mark for the playing item", Mark.NONE, tracker.peekMark(p, U))
        assertEquals("the other item's timed mark stays", Mark.TIMED, tracker.peekMark(p, T))
    }

    // ---- AC-14: the only exempt start, and only once ----

    /** Plan section 6, AC-14: a second load of T -> false, for both keep values. */
    @Test
    fun ac14_secondLoadOfATimedStartBeginsAtZero() {
        for (keep in listOf(true, false)) {
            val tracker = KeepPositionTracker(FakeKeepPositionSettings(keepPosition = keep))
            tracker.onTimedStart(T)

            assertEquals("keep=$keep: the timed start", SEEKS, tracker.load(p, T))
            assertEquals("keep=$keep: its mark is spent", Mark.NONE, tracker.peekMark(p, T))
            assertEquals("keep=$keep: the second load", NO_SEEK, tracker.load(p, T))
        }
    }

    /** Spec AC-14: "after it, AC-7 applies again and the next start begins at 0:00". */
    @Test
    fun ac14_startsAfterATimedStartBeginAtZero() {
        tracker.onTimedStart(T)
        assertEquals("the timed start", SEEKS, tracker.load(p, T))

        assertEquals("the next video", NO_SEEK, tracker.load(p, V))
        assertEquals("back to the linked video", NO_SEEK, tracker.load(p, T))
        assertEquals("in another player", NO_SEEK, tracker.load(p2, T))
    }

    /**
     * Spec AC-14: "the only start that is exempt". The mark of T exempts no other item, and
     * (row `onSynchronize`: "remove u from `timed`") another item's load does not spend it.
     */
    @Test
    fun ac14_timedMarkExemptsOnlyItsOwnItem() {
        tracker.onTimedStart(T)

        assertEquals("another item", NO_SEEK, tracker.load(p, V))
        assertFalse("another item, in place", tracker.shouldSeekInPlace(p, V, V, false))
        assertEquals("T is still marked", Mark.TIMED, tracker.peekMark(p, T))
        assertEquals("T itself", SEEKS, tracker.load(p, T))
    }

    /**
     * Row `onSynchronize`: the mark is removed at the synchronization, whether or not the
     * seek decision (H4) is reached in that call.
     */
    @Test
    fun ac14_timedMarkIsSpentByTheSynchronization_evenWithoutTheSeekDecision() {
        tracker.onTimedStart(T)

        tracker.onSynchronize(p, T)

        assertEquals(Mark.NONE, tracker.peekMark(p, T))
        assertEquals(NO_SEEK, tracker.load(p, T))
    }

    /** Row `shouldSeekOnSynchronize`: the grant is read once, then it is none. */
    @Test
    fun ac14_timedGrantServesOneDecision() {
        tracker.onTimedStart(T)
        tracker.onSynchronize(p, T)

        assertTrue("first decision", tracker.shouldSeekOnSynchronize(p, T))
        assertFalse("second decision", tracker.shouldSeekOnSynchronize(p, T))
    }

    /** A mark spent by the seek in place serves no load. */
    @Test
    fun ac14_timedMarkSpentInPlaceServesNoLoad() {
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p, T, U, false))

        assertEquals("the link item", NO_SEEK, tracker.load(p, T))
        assertEquals("the playing item", NO_SEEK, tracker.load(p, U))
    }

    /** A mark spent by a load serves no seek in place. */
    @Test
    fun ac14_timedMarkSpentByALoadServesNoSeekInPlace() {
        tracker.onTimedStart(T)
        assertEquals(SEEKS, tracker.load(p, T))

        assertFalse(tracker.shouldSeekInPlace(p, T, T, false))
    }

    /**
     * Plan section 2: the timed mark is a set of uuids. An item marked twice (two hooks may
     * see the same queue) is in it once and serves one load.
     */
    @Test
    fun ac14_itemMarkedTwiceStillServesOneLoad() {
        tracker.onTimedStart(T)
        tracker.onTimedStart(T)

        assertEquals("first load", SEEKS, tracker.load(p, T))
        assertEquals("second load", NO_SEEK, tracker.load(p, T))
    }

    /**
     * Plan section 6, AC-14: after a timed load of T, `onReload(p,T,90000,false)` gives
     * RELOAD, not TIMED. With the keep switch off that reload restarts at 0:00 (AC-8).
     */
    @Test
    fun ac8_ac14_keepOff_reloadAfterATimedLoadIsAnOrdinaryReload() {
        tracker.onTimedStart(T)
        assertEquals("the timed start", SEEKS, tracker.load(p, T))

        tracker.onReload(p, T, 90000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p, T))
        assertEquals("the reload, keep off", NO_SEEK, tracker.load(p, T))
    }

    /** The same reload with the keep switch on continues, as any reload does. */
    @Test
    fun ac3_ac14_keepOn_reloadAfterATimedLoadContinuesAsAnOrdinaryReload() {
        val tracker = KeepPositionTracker(FakeKeepPositionSettings(keepPosition = true))
        tracker.onTimedStart(T)
        assertEquals("the timed start", SEEKS, tracker.load(p, T))

        tracker.onReload(p, T, 90000, false)

        assertEquals(Mark.RELOAD, tracker.peekMark(p, T))
        assertEquals("the reload", SEEKS, tracker.load(p, T))
        assertEquals("a load without reload", NO_SEEK, tracker.load(p, T))
    }

    /**
     * The reload that follows a seek in place is exempt once: its load spends the mark, and
     * the next reload of the same item is an ordinary one again.
     */
    @Test
    fun ac14_reloadAfterTheSeekInPlaceIsExemptOnce() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p, T, U, true))
        tracker.onReload(p, U, 90000, false)
        assertEquals("the reload after the seek in place", SEEKS, tracker.load(p, U))

        assertEquals("a second load", NO_SEEK, tracker.load(p, U))
        tracker.onReload(p, U, 90000, false)
        assertEquals("the next reload's mark", Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals("the next reload, keep off", NO_SEEK, tracker.load(p, U))
    }

    /** Row `onSynchronize`: `reload = none`, also for the mark the seek in place set. */
    @Test
    fun ac14_markSetByTheSeekInPlaceIsDiscardedWhenAnotherItemSynchronizesFirst() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p, T, U, true))

        tracker.onSynchronize(p, V)

        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    /**
     * Row `onReload`: the timed flag is taken over only from a previous mark that fits the
     * reload's player and item, "else false". Here the previous mark belongs to another
     * player.
     */
    @Test
    fun ac14_reloadByAnotherPlayerDoesNotTakeOverTheTimedFlag() {
        tracker.onSynchronize(p2, U)
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p1, T, U, true))
        assertEquals("precondition: timed mark of p1", Mark.TIMED, tracker.peekMark(p1, U))

        tracker.onReload(p2, U, 5000, false)

        assertEquals("the reload of p2 is an ordinary one", Mark.RELOAD, tracker.peekMark(p2, U))
        assertEquals("the mark of p1 was replaced", Mark.NONE, tracker.peekMark(p1, U))
    }

    /** The same rule; here the previous mark names another item. */
    @Test
    fun ac14_reloadOfAnotherItemDoesNotTakeOverTheTimedFlag() {
        tracker.onSynchronize(p, U)
        tracker.onTimedStart(T)
        assertTrue(tracker.shouldSeekInPlace(p, T, V, true))
        assertEquals("precondition: timed mark for V", Mark.TIMED, tracker.peekMark(p, V))

        tracker.onReload(p, U, 5000, false)

        assertEquals("the reload of U is an ordinary one", Mark.RELOAD, tracker.peekMark(p, U))
        assertEquals("the mark for V was replaced", Mark.NONE, tracker.peekMark(p, V))
    }

    // ---- AC-14: the set of timed marks ----

    /** Plan section 3.1: `MAX_TIMED = 16`; sixteen marks are all kept. */
    @Test
    fun ac14_sixteenTimedStartsAreAllKept() {
        assertEquals(16, KeepPositionTracker.MAX_TIMED)
        val uuids = (1..16).map { "timed-$it" }

        uuids.forEach { tracker.onTimedStart(it) }

        for (uuid in uuids) {
            assertEquals(uuid, Mark.TIMED, tracker.peekMark(p, uuid))
        }
    }

    /** Plan section 6, AC-14: the 17th timed uuid drops the first. */
    @Test
    fun ac14_seventeenthTimedStartDropsTheFirst() {
        val uuids = (1..17).map { "timed-$it" }

        uuids.forEach { tracker.onTimedStart(it) }

        assertEquals("the first", Mark.NONE, tracker.peekMark(p, uuids.first()))
        for (uuid in uuids.drop(1)) {
            assertEquals(uuid, Mark.TIMED, tracker.peekMark(p, uuid))
        }
        assertEquals("the dropped one begins at 0:00", NO_SEEK, tracker.load(p, uuids.first()))
    }

    /** Each further mark drops the oldest that is left: only the newest sixteen remain. */
    @Test
    fun ac14_onlyTheNewestSixteenTimedStartsAreKept() {
        val uuids = (1..40).map { "timed-$it" }

        uuids.forEach { tracker.onTimedStart(it) }

        for (uuid in uuids.dropLast(16)) {
            assertEquals(uuid, Mark.NONE, tracker.peekMark(p, uuid))
        }
        for (uuid in uuids.takeLast(16)) {
            assertEquals(uuid, Mark.TIMED, tracker.peekMark(p, uuid))
        }
    }

    /** Row `onSynchronize` removes the uuid from the set: a spent mark makes room for a new one. */
    @Test
    fun ac14_spentTimedMarkMakesRoomForAnother() {
        val uuids = (1..17).map { "timed-$it" }
        uuids.take(16).forEach { tracker.onTimedStart(it) }
        assertEquals("the first is loaded", SEEKS, tracker.load(p, uuids[0]))

        tracker.onTimedStart(uuids[16])

        for (uuid in uuids.drop(1)) {
            assertEquals(uuid, Mark.TIMED, tracker.peekMark(p, uuid))
        }
    }
}
