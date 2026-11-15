package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val U = "uuid-U"
private const val V = "uuid-V"
private const val W = "uuid-W"

/**
 * Spec AC-7: a start is not a reload. While "Always start play from beginning" is on, every
 * start begins at 0:00, "whatever the new switch is set to", including "an item that was
 * played earlier in the same queue and the video that follows a reloaded one".
 *
 * Plan section 2, "A start is never taken for a reload": a reload mark lives from the
 * instant the reload began until the next synchronization of any item in any player, fits
 * only its item and its owner, and its grant serves one decision. Rows `onSynchronize` and
 * `shouldSeekOnSynchronize` of the method table in plan section 3.1.
 *
 * Both switches are on, so a mark that wrongly survived would seek.
 */
class KeepPositionTrackerStartTest {

    private val tracker = KeepPositionTracker(FakeKeepPositionSettings())

    // The tracker holds players weakly: the tokens stay referenced from these fields.
    private val p = Any()
    private val p1 = Any()
    private val p2 = Any()

    /** "Reload mark for U" of plan section 6: U is the playing item of [player], its reload begins. */
    private fun beginReload(player: Any, uuid: String) {
        tracker.onSynchronize(player, uuid)
        tracker.onReload(player, uuid, 5000, false)
        assertEquals("precondition: the reload mark is set", Mark.RELOAD, tracker.peekMark(player, uuid))
    }

    // ---- no mark ----

    /** Plan section 6, AC-7: no mark -> false. All three decisions, for both keep values. */
    @Test
    fun ac7_withoutAMarkNoDecisionSeeks_whateverKeepIs() {
        for (keep in listOf(true, false)) {
            val tracker = KeepPositionTracker(FakeKeepPositionSettings(keepPosition = keep))

            assertEquals("keep=$keep", Mark.NONE, tracker.peekMark(p, U))
            assertFalse("keep=$keep: seek in place", tracker.shouldSeekInPlace(p, U, U, false))
            assertFalse("keep=$keep: decision without synchronization", tracker.shouldSeekOnSynchronize(p, U))
            assertEquals("keep=$keep: load", NO_SEEK, tracker.load(p, U))
        }
    }

    /** Next, previous, an item picked in the queue: no reload anywhere, every load is a start. */
    @Test
    fun ac7_everyStartInAQueueBeginsAtZero() {
        assertEquals("first item", NO_SEEK, tracker.load(p, U))
        assertEquals("next", NO_SEEK, tracker.load(p, V))
        assertEquals("previous, played earlier", NO_SEEK, tracker.load(p, U))
        assertEquals("picked in the queue", NO_SEEK, tracker.load(p, W))
        assertEquals("the same item again", NO_SEEK, tracker.load(p, W))
    }

    /**
     * Pressing play after the player was closed: a new player, a new ExoPlayer at position 0.
     * `initPlayback` and the reload right after it call `onReload`, which sets nothing.
     */
    @Test
    fun ac7_playAfterThePlayerWasClosedBeginsAtZero() {
        assertEquals("in the old player", NO_SEEK, tracker.load(p1, U))

        tracker.onReload(p2, U, 0, false)
        tracker.onReload(p2, U, 0, false)

        assertEquals(Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("in the new player", NO_SEEK, tracker.load(p2, U))
    }

    // ---- the reload mark fits one item, one owner, one load ----

    /** Plan section 6, AC-7: reload mark for U, `sync(p,V)`, load U -> false. */
    @Test
    fun ac7_anotherItemSynchronizedFirst_theReloadedItemBeginsAtZeroLater() {
        beginReload(p, U)

        tracker.onSynchronize(p, V)

        assertEquals(Mark.NONE, tracker.peekMark(p, U))
        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    /**
     * Plan section 6, AC-7: `peekMark(p,V)` during U's reload -> NONE. Items preloaded
     * during a reload have another uuid; asking for them leaves U's mark alone.
     */
    @Test
    fun ac7_reloadMarkOfOneItemGivesNothingToAnotherItem() {
        beginReload(p, U)

        assertEquals(Mark.NONE, tracker.peekMark(p, V))
        assertFalse(tracker.shouldSeekOnLoad(p, V))
        assertEquals("U keeps its mark", Mark.RELOAD, tracker.peekMark(p, U))
    }

    /**
     * Plan section 6, AC-7: mark of p1, load U in p2 -> false. The player was closed during
     * the reload; a new player's load of U does not fit the owner, and it discards the mark.
     */
    @Test
    fun ac7_reloadMarkOfOnePlayerDoesNotFitAnotherPlayer() {
        beginReload(p1, U)

        assertEquals(Mark.NONE, tracker.peekMark(p2, U))
        assertEquals("the other player's load", NO_SEEK, tracker.load(p2, U))
        assertEquals("the owner's mark is gone too", Mark.NONE, tracker.peekMark(p1, U))
        assertEquals("the owner's later load", NO_SEEK, tracker.load(p1, U))
    }

    /** Plan section 2: every synchronization "of any item in any player" discards the reload mark. */
    @Test
    fun ac7_synchronizationInAnotherPlayerDiscardsTheReloadMark() {
        beginReload(p1, U)

        tracker.onSynchronize(p2, V)

        assertEquals(Mark.NONE, tracker.peekMark(p1, U))
        assertEquals(NO_SEEK, tracker.load(p1, U))
    }

    /**
     * Plan section 6, AC-7: mark, `sync(p,U)` without the decision, `sync(p,V)`, load U ->
     * false. The seek decision (H4) is not reached on every synchronization; a grant that was
     * not asked for must not wait for a later start.
     */
    @Test
    fun ac7_grantThatWasNotAskedForIsGoneAfterTheNextSynchronization() {
        beginReload(p, U)

        tracker.onSynchronize(p, U)
        tracker.onSynchronize(p, V)

        assertEquals(NO_SEEK, tracker.load(p, U))
    }

    /**
     * The same sequence, asking the decision directly: row `onSynchronize` sets the grant to
     * none when the synchronized item has no mark.
     */
    @Test
    fun ac7_synchronizationOfAnUnmarkedItemClearsAnEarlierGrant() {
        beginReload(p, U)

        tracker.onSynchronize(p, U)
        tracker.onSynchronize(p, V)

        assertFalse(tracker.shouldSeekOnSynchronize(p, U))
    }

    /** The same when the unmarked item is synchronized by another player. */
    @Test
    fun ac7_synchronizationInAnotherPlayerClearsAnEarlierGrant() {
        beginReload(p1, U)

        tracker.onSynchronize(p1, U)
        tracker.onSynchronize(p2, V)

        assertFalse(tracker.shouldSeekOnSynchronize(p1, U))
    }

    /** Row `shouldSeekOnSynchronize`: the grant is read once, then it is none. */
    @Test
    fun ac7_reloadGrantServesOneDecision() {
        beginReload(p, U)

        tracker.onSynchronize(p, U)

        assertTrue("first decision", tracker.shouldSeekOnSynchronize(p, U))
        assertFalse("second decision", tracker.shouldSeekOnSynchronize(p, U))
        assertFalse("the mark was spent by the synchronization", tracker.shouldSeekOnLoad(p, U))
        assertEquals(Mark.NONE, tracker.peekMark(p, U))
    }

    /**
     * Row `shouldSeekOnSynchronize`: the grant counts only for its own uuid, and the call
     * clears it whatever uuid it asks for.
     */
    @Test
    fun ac7_grantIsOnlyForTheSynchronizedItem() {
        beginReload(p, U)

        tracker.onSynchronize(p, U)

        assertFalse("decision for another item", tracker.shouldSeekOnSynchronize(p, V))
        assertFalse("the grant is gone after it", tracker.shouldSeekOnSynchronize(p, U))
    }

    /**
     * Row `shouldSeekOnSynchronize`: the decision reads the grant, never the mark. Without a
     * synchronization there is no grant, and the mark is left as it was.
     */
    @Test
    fun ac7_decisionWithoutASynchronizationGetsNoGrant() {
        beginReload(p, U)

        assertFalse(tracker.shouldSeekOnSynchronize(p, U))
        assertEquals("the mark is untouched", Mark.RELOAD, tracker.peekMark(p, U))
    }

    // ---- the starts around a reload ----

    /** Spec AC-7: "the video that follows a reloaded one". */
    @Test
    fun ac7_videoThatFollowsAReloadedOneBeginsAtZero() {
        beginReload(p, U)
        assertEquals("the reload", SEEKS, tracker.load(p, U))

        assertEquals("the next video", NO_SEEK, tracker.load(p, V))
    }

    /** Spec AC-7: "an item that was played earlier in the same queue". */
    @Test
    fun ac7_earlierQueueItemStartsAtZeroAfterReloadOfAnother() {
        assertEquals("U, the earlier item", NO_SEEK, tracker.load(p, U))
        assertEquals("V, next", NO_SEEK, tracker.load(p, V))
        tracker.onReload(p, V, 5000, false)
        assertEquals("V, reloaded", SEEKS, tracker.load(p, V))

        assertEquals("back to U", NO_SEEK, tracker.load(p, U))
    }

    /** The reloaded item itself, started again later, has no mark left. */
    @Test
    fun ac7_reloadedItemStartedAgainLaterBeginsAtZero() {
        beginReload(p, U)
        assertEquals("the reload", SEEKS, tracker.load(p, U))
        assertEquals("next", NO_SEEK, tracker.load(p, V))

        assertEquals("back to the reloaded item", NO_SEEK, tracker.load(p, U))
    }

    /** With the keep switch off nothing is exempt either: reloads and starts all begin at 0:00. */
    @Test
    fun ac7_keepOff_startsAroundAReloadBeginAtZero() {
        val tracker = KeepPositionTracker(FakeKeepPositionSettings(keepPosition = false))

        assertEquals("first item", NO_SEEK, tracker.load(p, U))
        tracker.onReload(p, U, 5000, false)
        assertEquals("the reload", NO_SEEK, tracker.load(p, U))
        assertEquals("next", NO_SEEK, tracker.load(p, V))
        assertEquals("previous", NO_SEEK, tracker.load(p, U))
    }

    // ---- players ----

    /**
     * Plan section 3.1: a player is "an opaque token compared by identity". Two players that
     * are equal but not the same object are two players: neither the owner check of the mark
     * nor the `synced` guard of `onReload` may use `equals`.
     */
    @Test
    fun ac7_playersAreToldApartByIdentityNotByEquals() {
        data class Token(val id: Int)
        val a = Token(1)
        val b = Token(1)
        assertEquals("precondition: the tokens are equal", a, b)

        tracker.onSynchronize(a, U)
        tracker.onReload(b, U, 5000, false)
        assertEquals("b is not on U: no mark", Mark.NONE, tracker.peekMark(b, U))
        assertEquals("b is not on U: no mark", Mark.NONE, tracker.peekMark(a, U))

        tracker.onReload(a, U, 5000, false)
        assertEquals("the mark of a", Mark.RELOAD, tracker.peekMark(a, U))
        assertEquals("does not fit b", Mark.NONE, tracker.peekMark(b, U))
        assertEquals(NO_SEEK, tracker.load(b, U))
    }
}
