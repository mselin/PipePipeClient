package org.schabi.newpipe.enhancements.feedliveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-3, AC-5, AC-9 (plan 6.1, 6.2 and section 7): the rules the page applies to its list.
 *
 * Ids are the videos' ids, newest first as the feed shows them; the old list is `10, 11, …`
 * and videos a refresh brings have ids from 100.
 */
class FeedListRulesTest {

    private fun ids(vararg id: Long): List<Long> = id.toList()

    /** The list before the update: eight videos. */
    private val old = ids(10, 11, 12, 13, 14, 15, 16, 17)

    private fun after(atTop: Boolean, firstVisible: Int, oldIds: List<Long>, newIds: List<Long>, duringRefresh: Boolean = true) =
        FeedListRules.afterUpdate(atTop, firstVisible, oldIds, newIds, duringRefresh)

    @Test
    fun afterUpdate_theEnumIsThePlans() {
        assertEquals(listOf("NOTHING", "SCROLL_TO_TOP", "SHOW_NEW_ITEMS"), AfterUpdate.values().map { it.name })
    }

    // ---- row 1: `oldIds` or `newIds` is empty -> NOTHING ----

    /**
     * AC-5, row 1, the first fill: "a saved scroll position may be restored; `scrollToPosition`
     * would discard it". An empty list is "at the top" and its first visible position is -1.
     */
    @Test
    fun ac5_row1_firstFill_doesNothing() {
        for (duringRefresh in listOf(true, false)) {
            assertEquals(AfterUpdate.NOTHING, after(true, -1, emptyList(), old, duringRefresh))
            assertEquals(AfterUpdate.NOTHING, after(false, -1, emptyList(), old, duringRefresh))
            assertEquals(AfterUpdate.NOTHING, after(true, 0, emptyList(), old, duringRefresh))
        }
    }

    /** Row 1: the list becomes empty (the filter matches nothing, or every video left the feed). */
    @Test
    fun ac5_row1_theListBecomesEmpty_doesNothing() {
        for (duringRefresh in listOf(true, false)) {
            assertEquals(AfterUpdate.NOTHING, after(true, 0, old, emptyList(), duringRefresh))
            assertEquals(AfterUpdate.NOTHING, after(false, 3, old, emptyList(), duringRefresh))
        }
        assertEquals(AfterUpdate.NOTHING, after(true, -1, emptyList(), emptyList()))
    }

    // ---- row 2: `atTop`, `newIds[0] != oldIds[0]` -> SCROLL_TO_TOP ----

    /** AC-5, row 2: "At the top, new videos appear at the top, and the list stays at the top". */
    @Test
    fun ac5_row2_atTheTop_newVideosAbove_scrollsToTheTop() {
        assertEquals(AfterUpdate.SCROLL_TO_TOP, after(true, 0, old, ids(100, 101) + old))
    }

    /** AC-5, answer A5: "this holds for every update of the list, the final one included", so also outside a refresh. */
    @Test
    fun ac5_row2_atTheTop_holdsForTheFinalUpdateAndOutsideARefresh() {
        assertEquals(AfterUpdate.SCROLL_TO_TOP, after(true, 0, old, ids(100) + old, duringRefresh = false))
    }

    /** Row 2 asks only whether the first video is another one: the old first one left the feed. */
    @Test
    fun ac5_row2_atTheTop_theFirstVideoIsGone_scrollsToTheTop() {
        assertEquals(AfterUpdate.SCROLL_TO_TOP, after(true, 0, old, old.drop(1)))
        assertEquals(AfterUpdate.SCROLL_TO_TOP, after(true, 0, old, ids(200, 201), duringRefresh = false))
    }

    /** Row 2 comes before the rows that look at the first visible position: at the top that position does not matter. */
    @Test
    fun ac5_row2_atTheTop_whateverTheFirstVisiblePosition() {
        for (firstVisible in listOf(-1, 0, 1, 99)) {
            assertEquals("firstVisible=$firstVisible", AfterUpdate.SCROLL_TO_TOP, after(true, firstVisible, old, ids(100) + old))
        }
    }

    // ---- row 3: `atTop` -> NOTHING ----

    /** Row 3: at the top and the first video is the same: nothing to do, and no indicator at the top. */
    @Test
    fun ac5_row3_atTheTop_sameFirstVideo_doesNothing() {
        for (duringRefresh in listOf(true, false)) {
            assertEquals("unchanged list", AfterUpdate.NOTHING, after(true, 0, old, old, duringRefresh))
            assertEquals("a video inserted below the first", AfterUpdate.NOTHING, after(true, 0, old, ids(10, 100) + old.drop(1), duringRefresh))
            assertEquals("videos removed below", AfterUpdate.NOTHING, after(true, 0, old, old.take(3), duringRefresh))
        }
    }

    // ---- row 4: nothing on screen survives, or the position is outside the old list -> NOTHING ----

    /** Row 4, "all old items gone": no old video from the first visible one on is in the new list. */
    @Test
    fun ac5_row4_notAtTheTop_allOldVideosGone_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, ids(100, 101, 102)))
    }

    /** Row 4 looks at the videos "at index `firstVisible` or later" only: the ones above the screen do not count as kept. */
    @Test
    fun ac5_row4_notAtTheTop_onlyVideosAboveTheScreenSurvive_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, ids(100, 10, 11, 12)))
    }

    /** Row 4, "`firstVisible` is outside `oldIds`": beyond the end. */
    @Test
    fun ac5_row4_firstVisibleBeyondTheEnd_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, old.size, old, ids(100) + old))
        assertEquals(AfterUpdate.NOTHING, after(false, 99, old, ids(100) + old))
    }

    // ---- row 5: a new video above what is on screen, during a refresh -> SHOW_NEW_ITEMS ----

    /**
     * AC-5, row 5: "If the list is not at the top when an update arrives, the items on screen
     * stay where they are, and the page's existing 'new items' indicator shows that there is
     * something above."
     */
    @Test
    fun ac5_row5_notAtTheTop_newVideosAbove_showsTheIndicator() {
        assertEquals(AfterUpdate.SHOW_NEW_ITEMS, after(false, 3, old, ids(100, 101) + old))
    }

    /** Row 5: one new video directly above the first visible one is "something above". */
    @Test
    fun ac5_row5_notAtTheTop_oneNewVideoDirectlyAbove_showsTheIndicator() {
        assertEquals(AfterUpdate.SHOW_NEW_ITEMS, after(false, 3, old, ids(10, 11, 12, 100) + old.drop(3)))
    }

    /** Row 5, "anchor item removed": the first visible video left the feed; the next surviving one below it is what stays in place. */
    @Test
    fun ac5_row5_theFirstVisibleVideoIsGone_theNextSurvivorCounts() {
        val withoutThe4th = old.filter { it != 13L }

        assertEquals("new videos above", AfterUpdate.SHOW_NEW_ITEMS, after(false, 3, old, ids(100) + withoutThe4th))
        assertEquals("nothing new above", AfterUpdate.NOTHING, after(false, 3, old, withoutThe4th))
        assertEquals("new videos only below the survivor", AfterUpdate.NOTHING, after(false, 3, old, withoutThe4th + ids(100)))
    }

    /** Row 5, "negative counts as 0": no first visible position was found; the whole old list is looked at. */
    @Test
    fun ac5_row5_firstVisibleMinus1_countsAs0() {
        assertEquals(AfterUpdate.SHOW_NEW_ITEMS, after(false, -1, old, ids(100) + old))
        assertEquals(AfterUpdate.SHOW_NEW_ITEMS, after(false, 0, old, ids(100) + old))
        assertEquals(AfterUpdate.NOTHING, after(false, -1, old, old + ids(100)))
    }

    /** Row 5: the last video of the old list is the first visible one. */
    @Test
    fun ac5_row5_theLastVideoIsTheFirstVisible() {
        assertEquals(AfterUpdate.SHOW_NEW_ITEMS, after(false, old.size - 1, old, ids(100) + old))
    }

    // ---- row 6: otherwise -> NOTHING ----

    /** Row 6: new videos only below what is on screen ("insert below"). */
    @Test
    fun ac5_row6_notAtTheTop_newVideosBelow_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, old + ids(100, 101)))
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, old.take(5) + ids(100) + old.drop(5)))
    }

    /** Row 6: the list is unchanged, or videos above the screen left the feed: nothing new above. */
    @Test
    fun ac5_row6_notAtTheTop_nothingNewAbove_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, old))
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, old.drop(2)))
    }

    /**
     * Row 6, plan section 7 and answer A5: "The button for 'something above' is added only
     * during a refresh; for the final state it is shown as today", by upstream's own code.
     */
    @Test
    fun ac5_row6_notAtTheTop_newVideosAbove_outsideARefresh_doesNothing() {
        assertEquals(AfterUpdate.NOTHING, after(false, 3, old, ids(100, 101) + old, duringRefresh = false))
        assertEquals(AfterUpdate.NOTHING, after(false, -1, old, ids(100) + old, duringRefresh = false))
    }

    /** "Outside a refresh only its 'at the top stays at the top' row can apply": every case of this class, not during a refresh. */
    @Test
    fun ac5_outsideARefresh_theIndicatorIsNeverAsked() {
        val lists = listOf(emptyList(), old, ids(100) + old, old + ids(100), old.drop(3), ids(100, 101), ids(10, 100) + old.drop(1))
        for (oldIds in lists) for (newIds in lists) for (atTop in listOf(true, false)) for (firstVisible in listOf(-1, 0, 3, 99)) {
            val result = after(atTop, firstVisible, oldIds, newIds, duringRefresh = false)
            assertTrue("afterUpdate($atTop, $firstVisible, $oldIds, $newIds, false) = $result", result != AfterUpdate.SHOW_NEW_ITEMS)
            if (!atTop) assertEquals(AfterUpdate.NOTHING, result)
        }
    }

    /** AC-5: scrolled down, the rule never scrolls: "What the user is looking at does not move." */
    @Test
    fun ac5_notAtTheTop_theRuleNeverScrolls() {
        val lists = listOf(emptyList(), old, ids(100) + old, old + ids(100), old.drop(3), ids(100, 101), ids(10, 100) + old.drop(1))
        for (oldIds in lists) for (newIds in lists) for (firstVisible in listOf(-1, 0, 3, 99)) for (duringRefresh in listOf(true, false)) {
            assertTrue(after(false, firstVisible, oldIds, newIds, duringRefresh) != AfterUpdate.SCROLL_TO_TOP)
        }
    }

    // ---- sameRows (plan 6.2) ----

    private class Item(val row: Row)

    /** AC-1, plan 6.2: "same size and the same row instance at each index": the list is not touched. */
    @Test
    fun ac1_sameRows_theSameRowInstances_areTheSameRows() {
        val rows = listOf(Row(1), Row(2), Row(3))
        val shown = rows.map { Item(it) }
        val next = rows.map { Item(it) } // new items, as the first tick of a refresh has

        assertTrue(FeedListRules.sameRows(shown, next) { it.row })
        assertTrue(FeedListRules.sameRows(shown, shown) { it.row })
    }

    /** AC-2: a rebuild returns new row objects, equal or not: the list is given to the adapter, which finds what changed. */
    @Test
    fun ac2_sameRows_equalRowsOfANewQuery_areNotTheSameRows() {
        val shown = listOf(Row(1), Row(2), Row(3)).map { Item(it) }
        val next = listOf(Row(1), Row(2), Row(3)).map { Item(it) }
        assertEquals("the rows are equal", shown.map { it.row }, next.map { it.row })

        assertFalse(FeedListRules.sameRows(shown, next) { it.row })
    }

    @Test
    fun ac2_sameRows_oneOtherInstance_isEnough() {
        val rows = listOf(Row(1), Row(2), Row(3))
        val shown = rows.map { Item(it) }

        assertFalse("last", FeedListRules.sameRows(shown, listOf(Item(rows[0]), Item(rows[1]), Item(Row(3)))) { it.row })
        assertFalse("first", FeedListRules.sameRows(shown, listOf(Item(Row(1)), Item(rows[1]), Item(rows[2]))) { it.row })
        assertFalse("another order", FeedListRules.sameRows(shown, listOf(Item(rows[1]), Item(rows[0]), Item(rows[2]))) { it.row })
    }

    @Test
    fun ac2_sameRows_anotherSize_isNotTheSameRows() {
        val rows = listOf(Row(1), Row(2), Row(3))
        val shown = rows.map { Item(it) }

        assertFalse("a prefix", FeedListRules.sameRows(shown, rows.take(2).map { Item(it) }) { it.row })
        assertFalse("longer", FeedListRules.sameRows(shown.take(2), shown) { it.row })
        assertFalse(FeedListRules.sameRows(shown, emptyList()) { it.row })
        assertFalse("the search filter shows fewer rows than the page has", FeedListRules.sameRows(emptyList(), shown) { it.row })
    }

    @Test
    fun sameRows_bothEmpty_areTheSameRows() {
        assertTrue(FeedListRules.sameRows(emptyList<Item>(), emptyList()) { it.row })
    }

    // ---- matches (AC-9, plan 6.2: the test of `FeedFragment.filterItems`) ----

    /** AC-9: "A search text that is set keeps applying to every update of the list": title contains the text. */
    @Test
    fun ac9_matches_theTitle() {
        assertTrue(FeedListRules.matches("How to bake bread", "Some Channel", "bake"))
        assertTrue(FeedListRules.matches("How to bake bread", "Some Channel", "How to bake bread"))
        assertFalse(FeedListRules.matches("How to bake bread", "Some Channel", "cake"))
    }

    /** AC-9: or the channel's name contains it ("Search for a channel name during the refresh", device step 9). */
    @Test
    fun ac9_matches_theUploader() {
        assertTrue(FeedListRules.matches("How to bake bread", "Some Channel", "chan"))
        assertTrue(FeedListRules.matches("How to bake bread", "Some Channel", "Some Channel"))
        assertFalse("not across title and uploader", FeedListRules.matches("How to bake", "bread", "bake bread"))
    }

    /** AC-9: case is ignored on both sides, as `filterItems` does with `lowercase()`. */
    @Test
    fun ac9_matches_ignoresCase() {
        assertTrue(FeedListRules.matches("How to BAKE bread", "Some Channel", "bake"))
        assertTrue(FeedListRules.matches("how to bake bread", "Some Channel", "BAKE"))
        assertTrue(FeedListRules.matches("Video", "SOME channel", "some CHANNEL"))
        assertTrue(FeedListRules.matches("Die ÄRZTE live", null, "ärzte"))
    }

    /** AC-9: the text is taken as typed: spaces count. */
    @Test
    fun ac9_matches_doesNotTrimTheText() {
        assertFalse(FeedListRules.matches("bake", "channel", "bake "))
        assertTrue(FeedListRules.matches("bake bread", "channel", "bake "))
    }

    /** An empty text matches a video that has a title; the page does not ask then (`isFilterEnabled` is false). */
    @Test
    fun ac9_matches_theEmptyText_matches() {
        assertTrue(FeedListRules.matches("How to bake bread", "Some Channel", ""))
        assertTrue(FeedListRules.matches("", "", ""))
    }

    /** Plan section 2: title and uploader may be null (the database columns are); a null one matches nothing, and nothing throws. */
    @Test
    fun ac9_matches_nulls() {
        assertTrue(FeedListRules.matches(null, "Some Channel", "chan"))
        assertTrue(FeedListRules.matches("How to bake bread", null, "bake"))
        assertFalse(FeedListRules.matches(null, "Some Channel", "bake"))
        assertFalse(FeedListRules.matches("How to bake bread", null, "chan"))
        assertFalse(FeedListRules.matches(null, null, "bake"))
        assertFalse("the text \"null\" is not found in a missing title", FeedListRules.matches(null, null, "null"))
    }

    /** AC-9 as a whole: a filtered list, updated by a rebuild during the refresh, still holds only matching videos. */
    @Test
    fun ac9_theFilterAppliedToAnUpdatedList() {
        data class Video(val title: String?, val uploader: String?)
        val before = listOf(Video("Bread basics", "Baking Daily"), Video("News at nine", "The News"), Video("Sourdough", "Baking Daily"))
        val update = listOf(Video("Rye loaf", "Baking Daily"), Video("Weather", "The News")) + before

        val shown = update.filter { FeedListRules.matches(it.title, it.uploader, "baking") }

        assertEquals(listOf("Rye loaf", "Bread basics", "Sourdough"), shown.map { it.title })
    }

    // ---- progressText (AC-3, plan 6.1) ----

    private val loading = "Loading feed…"

    /** AC-3: the counts shown today, "in the line above the list, with the existing texts": "Loading feed… 12/1600". */
    @Test
    fun ac3_progressText_withCounts_isTheLoadingTextAndTheCounts() {
        assertEquals("Loading feed… 12/1600", FeedListRules.progressText(12, 1600, loading, null))
        assertEquals("Loading feed… 1600/1600", FeedListRules.progressText(1600, 1600, loading, null))
    }

    /** AC-3, plan 10.1: at the start "the header line shows `0/n`". */
    @Test
    fun ac3_progressText_atTheStart_isZeroOfN() {
        assertEquals("Loading feed… 0/1600", FeedListRules.progressText(0, 1600, loading, null))
    }

    /** AC-3: the counts win over a message, as in `FeedFragment.kt:753-759`. */
    @Test
    fun ac3_progressText_withCountsAndAMessage_isTheCounts() {
        assertEquals("Loading feed… 12/1600", FeedListRules.progressText(12, 1600, loading, "Processing feed…"))
    }

    /** AC-3: without counts (both -1) the event's message: "Processing feed…". */
    @Test
    fun ac3_progressText_withoutCounts_isTheMessage() {
        assertEquals("Processing feed…", FeedListRules.progressText(-1, -1, loading, "Processing feed…"))
    }

    /** AC-3: with neither, today's "∞/∞" (assumed without the loading text: question Q8). */
    @Test
    fun ac3_progressText_withNeither_isInfinity() {
        assertEquals("∞/∞", FeedListRules.progressText(-1, -1, loading, null))
    }

    /** The loading text is the one passed in (the page passes `feed_notification_loading` in the user's language). */
    @Test
    fun ac3_progressText_usesTheTextItIsGiven() {
        assertEquals("Feed wird geladen… 3/40", FeedListRules.progressText(3, 40, "Feed wird geladen…", null))
    }
}
