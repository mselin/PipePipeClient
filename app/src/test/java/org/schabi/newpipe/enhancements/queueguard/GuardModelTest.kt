package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model of plan 3.1 (`GuardModel.kt`): what "something is playing" and "current item" mean, and the
 * two pure functions behind *Play all next* (AC-11) and the "Show stream details" entry (AC-17).
 */
class GuardModelTest {

    // ---- "Something is playing" (spec, Terms): a player is open and holds at least one item ----

    @Test
    fun ac1_ac7_somethingIsPlayingWhenAnOpenPlayerHoldsAtLeastOneItem() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind with one item", playing(kind, queueSize = 1).somethingPlaying)
            assertTrue("$kind with several items", playing(kind, queueSize = 25).somethingPlaying)
        }
    }

    @Test
    fun ac7_nothingIsPlayingWhileNoPlayerIsOpen() {
        assertFalse("IDLE", PlayerState.IDLE.somethingPlaying)
        val noPlayerButStaleQueueData =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("kind == null decides, whatever the other fields say", noPlayerButStaleQueueData.somethingPlaying)
    }

    @Test
    fun ac7_nothingIsPlayingWhileTheOpenPlayerHoldsNoItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind with an empty queue", openButEmpty(kind).somethingPlaying)
        }
    }

    @Test
    fun ac7_idleIsNoPlayerNoQueueNoCurrentItem() {
        assertEquals(
            PlayerState(kind = null, queueSize = 0, currentServiceId = -1, currentUrl = null),
            PlayerState.IDLE,
        )
    }

    // ---- "Current item" (AC-8): matched by service id and exact url ----

    @Test
    fun ac8_currentItemIsTheOneWithTheSameServiceAndTheSameUrl() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind", playing(kind).isCurrent(tapOnCurrent()))
            assertTrue("$kind, queue of one", playing(kind, queueSize = 1).isCurrent(tapOnCurrent()))
            assertTrue(
                "$kind, Music Player Mode does not change which item is current",
                playing(kind).isCurrent(tapOnCurrent(startsListInBackground = true)),
            )
        }
    }

    @Test
    fun ac8_currentItemIsRecognisedWhateverTitleTheListShows() {
        val tap = tapOn(url = CURRENT_URL, title = "Another title for the same video")
        assertTrue(playing(PlayerKind.MAIN).isCurrent(tap))
    }

    @Test
    fun ac8_anotherUrlIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind).isCurrent(tapOn(url = TAPPED_URL)))
        }
    }

    /** Plan section 8: the url is compared exactly, as upstream does; another form of the url is another item. */
    @Test
    fun ac8_urlIsComparedExactly() {
        val state = playing(PlayerKind.BACKGROUND, url = CURRENT_URL)
        assertFalse("extra parameter", state.isCurrent(tapOn(url = "$CURRENT_URL&t=10")))
        assertFalse("other case", state.isCurrent(tapOn(url = CURRENT_URL.uppercase())))
    }

    @Test
    fun ac8_sameUrlOnAnotherServiceIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
            assertFalse("$kind", playing(kind, serviceId = SERVICE).isCurrent(tap))
        }
    }

    @Test
    fun ac8_noItemIsCurrentWhileNothingIsPlaying() {
        val urlOfIdle = tapOn(url = CURRENT_URL, serviceId = -1)
        assertFalse("IDLE", PlayerState.IDLE.isCurrent(urlOfIdle))
        for (kind in ALL_KINDS) {
            val emptyQueueWithStaleItem =
                PlayerState(kind = kind, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            assertFalse("$kind with an empty queue", emptyQueueWithStaleItem.isCurrent(tapOnCurrent()))
        }
        val noPlayerWithStaleItem =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("no player", noPlayerWithStaleItem.isCurrent(tapOnCurrent()))
    }

    @Test
    fun ac8_noItemIsCurrentWhileThePlayerReportsNoCurrentUrl() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind, url = null).isCurrent(tapOnCurrent()))
        }
    }

    // ---- TapRequest ----

    /** Plan 3.1: `startsListInBackground` defaults to `false`; only Music Player Mode in a list sets it. */
    @Test
    fun ac5_tapStartsNoListInTheBackgroundUnlessToldSo() {
        val tap = TapRequest(SERVICE, TAPPED_URL, "Tapped video")
        assertFalse(tap.startsListInBackground)
        assertEquals(TapRequest(SERVICE, TAPPED_URL, "Tapped video", false), tap)
    }

    // ---- movesForPlayNext (AC-11, "Play all next") ----
    //
    // The glue appends the list to the player's queue and then moves the appended videos, one move per
    // video, to just behind the current item. A move is applied as PlayQueue.move does on its list:
    // add(to, removeAt(from)).

    private fun <T> MutableList<T>.applyMoves(moves: List<QueueMove>) {
        for (move in moves) add(move.to, removeAt(move.from))
    }

    private fun queueAfterPlayAllNext(queue: List<String>, currentIndex: Int, list: List<String>): List<String> {
        val appended = (queue + list).toMutableList()
        appended.applyMoves(movesForPlayNext(appended.size, list.size, currentIndex))
        return appended
    }

    @Test
    fun ac11_playAllNext_currentItemFirst_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "x", "y", "b", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 0, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemInTheMiddle_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "x", "y", "z", "c", "d"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c", "d"), currentIndex = 1, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemLast_videosStayRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "c", "x", "y"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 2, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_queueOfOneItem_videosFollowIt() {
        assertEquals(
            listOf("a", "x", "y", "z"),
            queueAfterPlayAllNext(queue = listOf("a"), currentIndex = 0, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_listOfOneVideo_landsRightBehindTheCurrentItem() {
        assertEquals(
            listOf("a", "b", "x", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 1, list = listOf("x")),
        )
    }

    /** Plan 3.1: i in 0 until added -> (sizeAfterAppend - added + i, currentIndex + 1 + i). */
    @Test
    fun ac11_movesForPlayNext_isOneMovePerVideoFromTheTailToBehindTheCurrentItem() {
        assertEquals(
            "queue of 3 on its first item, 2 videos appended",
            listOf(QueueMove(3, 1), QueueMove(4, 2)),
            movesForPlayNext(5, 2, 0),
        )
        assertEquals(
            "queue of 4 on its second item, 3 videos appended",
            listOf(QueueMove(4, 2), QueueMove(5, 3), QueueMove(6, 4)),
            movesForPlayNext(7, 3, 1),
        )
        assertEquals(
            "queue of 3 on its second item, 1 video appended",
            listOf(QueueMove(3, 2)),
            movesForPlayNext(4, 1, 1),
        )
    }

    /** Plan 3.1: "minus from == to". With the current item last, the appended videos are in place already. */
    @Test
    fun ac11_movesForPlayNext_leavesOutMovesThatMoveNothing() {
        assertEquals("current item last", emptyList<QueueMove>(), movesForPlayNext(5, 2, 2))
        assertEquals("queue of one item", emptyList<QueueMove>(), movesForPlayNext(4, 3, 0))
    }

    @Test
    fun ac11_movesForPlayNext_nothingAddedNeedsNoMove() {
        assertEquals(emptyList<QueueMove>(), movesForPlayNext(3, 0, 1))
    }

    /** Every queue of 1..6 items, every current index, every list of 1..5 videos. */
    @Test
    fun ac11_movesForPlayNext_putsTheListBehindTheCurrentItemForEveryQueueAndPosition() {
        for (queueSize in 1..6) {
            for (currentIndex in 0 until queueSize) {
                for (added in 1..5) {
                    val queue = List(queueSize) { "q$it" }
                    val list = List(added) { "new$it" }
                    val case = "queue of $queueSize on index $currentIndex, $added added"

                    val moves = movesForPlayNext(queueSize + added, added, currentIndex)
                    val result = (queue + list).toMutableList().apply { applyMoves(moves) }

                    val expected =
                        queue.subList(0, currentIndex + 1) + list + queue.subList(currentIndex + 1, queueSize)
                    assertEquals("$case: order", expected, result)
                    assertEquals("$case: the current item keeps its index", "q$currentIndex", result[currentIndex])
                    assertTrue("$case: no move with from == to in $moves", moves.none { it.from == it.to })
                    val expectedMoves = if (currentIndex == queueSize - 1) 0 else added
                    assertEquals("$case: one move per video that has to move", expectedMoves, moves.size)
                }
            }
        }
    }

    // ---- streamDetailsInsertIndex (AC-17) ----
    //
    // Menus are given as string resource ids. Any ints do.

    private val details = 7001
    private val anchor = 7002
    private val other1 = 7003
    private val other2 = 7004
    private val other3 = 7005

    /** AC-17: "Where the menu has that entry already, nothing changes." */
    @Test
    fun ac17_streamDetailsIsNotInsertedWhereTheMenuHasItAlready() {
        assertEquals("only entry", -1, streamDetailsInsertIndex(listOf(details), details, anchor))
        assertEquals("no anchor", -1, streamDetailsInsertIndex(listOf(other1, details, other2), details, anchor))
        assertEquals("before anchor", -1, streamDetailsInsertIndex(listOf(other1, details, anchor), details, anchor))
        assertEquals("behind anchor", -1, streamDetailsInsertIndex(listOf(anchor, other1, details), details, anchor))
    }

    @Test
    fun ac17_streamDetailsGoesToTheIndexOfTheAnchorEntry() {
        assertEquals("anchor first", 0, streamDetailsInsertIndex(listOf(anchor, other1, other2), details, anchor))
        assertEquals(
            "anchor in the middle",
            2,
            streamDetailsInsertIndex(listOf(other1, other2, anchor, other3), details, anchor),
        )
        assertEquals("anchor last", 2, streamDetailsInsertIndex(listOf(other1, other2, anchor), details, anchor))
        assertEquals("anchor alone", 0, streamDetailsInsertIndex(listOf(anchor), details, anchor))
    }

    @Test
    fun ac17_streamDetailsIsAppendedWhereTheMenuHasNoAnchorEntry() {
        assertEquals("three others", 3, streamDetailsInsertIndex(listOf(other1, other2, other3), details, anchor))
        assertEquals("one other entry", 1, streamDetailsInsertIndex(listOf(other1), details, anchor))
        assertEquals("empty menu", 0, streamDetailsInsertIndex(emptyList(), details, anchor))
    }

    /** Inserting at the returned index puts the entry directly in front of the anchor and keeps the rest. */
    @Test
    fun ac17_streamDetailsInsertedAtTheReturnedIndexSitsInFrontOfTheAnchor() {
        val menu = mutableListOf(other1, anchor, other2)
        menu.add(streamDetailsInsertIndex(menu, details, anchor), details)
        assertEquals(listOf(other1, details, anchor, other2), menu)
    }
}
