package org.schabi.newpipe.enhancements.feedliveupdate

import android.os.Parcelable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.GroupieViewHolder
import com.xwray.groupie.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-5 (plan section 7): `FeedListUpdate.show` on a real `RecyclerView` with the page's layout
 * manager (`GridLayoutManager` with the adapter's span lookup, `FeedFragment.kt:277-279`) and a
 * real `GroupieAdapter`. The list is 600 x 1000 px, measured and laid out by hand on the main
 * thread; every row is 100 px high. Old videos have ids from 1000, new ones from 1.
 *
 * What the JVM tests cannot show, and these do: what RecyclerView and Groupie make of the
 * update, which plan section 12 partly only assumes ("`scrollToPosition(0)` called right after
 * a synchronous `update` is applied to the list as updated, in the next layout").
 *
 * What a pass does not prove, for every test here: nothing about `FeedFragment` (its header,
 * the "new items" button, the highlighting, the search field), nothing about `StreamItem`'s
 * three layouts, and nothing about animations: the list is not attached to a window and has no
 * item animator, so "does not move" means "is laid out at the same place", not "is not animated".
 * The asynchronous update of the final state with a list already shown is not tested: Groupie
 * holds its listener weakly (plan, risk 5), so such a test could not be made reliable.
 *
 * Instrumented: needs a device or emulator. Not run by CI, only compiled; run it with the
 * plan's device script. It uses only what `androidTest` already depends on.
 */
@RunWith(AndroidJUnit4::class)
class FeedListUpdateTest {

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 1000
        const val ROW = 100
    }

    /** A row of fixed height. [span] 0: the whole row (list and card mode); else that many columns (grid mode: 1). */
    private class Row(id: Long, private val span: Int = 0) : Item<GroupieViewHolder>(id) {
        override fun getLayout(): Int = android.R.layout.simple_list_item_1

        override fun createViewHolder(itemView: View): GroupieViewHolder {
            itemView.minimumHeight = 0
            itemView.layoutParams = itemView.layoutParams.apply { height = ROW }
            return GroupieViewHolder(itemView)
        }

        override fun bind(viewHolder: GroupieViewHolder, position: Int) {
            (viewHolder.itemView as? TextView)?.text = id.toString()
        }

        override fun getSpanSize(spanCount: Int, position: Int): Int = if (span == 0) spanCount else span
    }

    private class Screen(val list: RecyclerView, val adapter: GroupieAdapter, val manager: GridLayoutManager) {
        fun layout() {
            list.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
            )
            list.layout(0, 0, WIDTH, HEIGHT)
        }

        val firstVisible: Int get() = manager.findFirstVisibleItemPosition()
        fun idAt(position: Int): Long = adapter.getItem(position).id
        fun topOf(position: Int): Int = manager.findViewByPosition(position)!!.top
        fun leftOf(position: Int): Int = manager.findViewByPosition(position)!!.left
        fun positionOf(id: Long): Int = (0 until adapter.itemCount).first { adapter.getItem(it).id == id }
    }

    /** What a test saw, read on the main thread and asserted on the test's thread. */
    private class Seen {
        var firstVisibleBefore = -1
        var idBefore = -1L
        var topBefore = Int.MIN_VALUE
        var firstVisibleAfter = -1
        var idAfter = -1L
        var topAfter = Int.MIN_VALUE
        var topOfTheSameVideoAfter = Int.MIN_VALUE
        var atTopBefore = false
        var atTopAfter = false
        var showNewItems = 0
        var onDone = 0
        var onDoneBeforeShowReturned = 0
        var itemCount = 0
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** A list showing [items], laid out; scrolled so that [scrollTo] is the first row, 30 px of it above the edge. */
    private fun screen(items: List<Row>, spans: Int = 1, scrollTo: Int? = null): Screen {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            android.R.style.Theme_DeviceDefault
        )
        val list = RecyclerView(context)
        val adapter = GroupieAdapter()
        adapter.spanCount = spans
        val manager = GridLayoutManager(context, spans).apply { spanSizeLookup = adapter.spanSizeLookup }
        list.layoutManager = manager
        list.itemAnimator = null
        list.adapter = adapter
        val screen = Screen(list, adapter, manager)
        if (items.isNotEmpty()) adapter.update(items)
        screen.layout()
        if (scrollTo != null) {
            manager.scrollToPositionWithOffset(scrollTo, -30)
            screen.layout()
        }
        return screen
    }

    private fun rows(ids: LongRange, span: Int = 0): List<Row> = ids.map { Row(it, span) }

    /** Runs `show` with [shown] during a refresh on [screen], lays out, and records what is on screen before and after. */
    private fun showAndSee(screen: Screen, shown: List<Row>, duringRefresh: Boolean, seen: Seen) {
        seen.firstVisibleBefore = screen.firstVisible
        seen.atTopBefore = !screen.list.canScrollVertically(-1)
        if (seen.firstVisibleBefore >= 0) {
            seen.idBefore = screen.idAt(seen.firstVisibleBefore)
            seen.topBefore = screen.topOf(seen.firstVisibleBefore)
        }
        FeedListUpdate.show(screen.list, screen.adapter, shown, duringRefresh, { seen.showNewItems++ }) { seen.onDone++ }
        seen.onDoneBeforeShowReturned = seen.onDone
        screen.layout()
        seen.itemCount = screen.adapter.itemCount
        seen.firstVisibleAfter = screen.firstVisible
        seen.atTopAfter = !screen.list.canScrollVertically(-1)
        if (seen.firstVisibleAfter >= 0) {
            seen.idAfter = screen.idAt(seen.firstVisibleAfter)
            seen.topAfter = screen.topOf(seen.firstVisibleAfter)
        }
        if (seen.idBefore >= 0 && shown.any { it.id == seen.idBefore }) {
            screen.manager.findViewByPosition(screen.positionOf(seen.idBefore))?.let { seen.topOfTheSameVideoAfter = it.top }
        }
    }

    /**
     * AC-5, list and card mode (one video per row): "If the list is not at the top when an
     * update arrives, the items on screen stay where they are, and the page's existing 'new
     * items' indicator shows that there is something above."
     *
     * A pass proves: with 40 videos shown and the list scrolled to the 21st (30 px of it above
     * the edge), a synchronous update that puts five new videos in front leaves the same video
     * first on screen at the same pixel, asks for the indicator once and calls `onDone` once,
     * before `show` returns. It does not prove that the fragment's button appears.
     */
    @Test
    fun ac5_listMode_scrolledDown_fiveNewVideosAbove_nothingOnScreenMoves_andTheIndicatorIsAsked() {
        val seen = Seen()
        onMain {
            val old = rows(1000L..1039L)
            val screen = screen(old, scrollTo = 20)
            showAndSee(screen, rows(1L..5L) + old, duringRefresh = true, seen = seen)
        }

        assertEquals("the test's own setup: scrolled to the 21st video", 20, seen.firstVisibleBefore)
        assertEquals(1020L, seen.idBefore)
        assertEquals(-30, seen.topBefore)
        assertEquals(45, seen.itemCount)
        assertEquals("AC-5: the same video is first on screen", 1020L, seen.idAfter)
        assertEquals("AC-5: it is now the 26th of the list", 25, seen.firstVisibleAfter)
        assertEquals("AC-5: at the same place", -30, seen.topAfter)
        assertEquals("AC-5: the indicator for \"something above\" is asked for once", 1, seen.showNewItems)
        assertEquals("upstream's callback runs once", 1, seen.onDone)
        assertEquals("plan 6.2: the update during a refresh is synchronous", 1, seen.onDoneBeforeShowReturned)
    }

    /**
     * AC-5: "At the top, new videos appear at the top, and the list stays at the top".
     *
     * A pass proves the plan's assumption (section 12): `scrollToPosition(0)` called right
     * after the synchronous `update` lands on the list as updated: after the next layout the
     * first row on screen is position 0, holds the first new video and sits at the top edge;
     * the indicator is not asked for.
     */
    @Test
    fun ac5_listMode_atTheTop_fiveNewVideos_areShownAtTheTop() {
        val seen = Seen()
        onMain {
            val old = rows(1000L..1039L)
            val screen = screen(old)
            showAndSee(screen, rows(1L..5L) + old, duringRefresh = true, seen = seen)
        }

        assertTrue("the test's own setup: at the top", seen.atTopBefore)
        assertEquals(1000L, seen.idBefore)
        assertEquals("AC-5: position 0 is on screen", 0, seen.firstVisibleAfter)
        assertEquals("AC-5: and holds the first new video", 1L, seen.idAfter)
        assertEquals(0, seen.topAfter)
        assertTrue("AC-5: \"the list stays at the top\"", seen.atTopAfter)
        assertEquals("AC-5: no indicator at the top", 0, seen.showNewItems)
        assertEquals(1, seen.onDone)
    }

    /**
     * The control for the test above, without `FeedListUpdate`: plan section 7, "This also
     * holds at the top: the old first item stays where it is and the new ones are above it, out
     * of sight." A pass proves that the scroll to the top is needed with this RecyclerView
     * version; a failure here means only that the library changed and the rule's
     * `SCROLL_TO_TOP` may have become unnecessary.
     */
    @Test
    fun control_atTheTop_aPlainUpdate_leavesTheNewVideosOutOfSight() {
        var firstVisible = -1
        var id = -1L
        onMain {
            val old = rows(1000L..1039L)
            val screen = screen(old)
            screen.adapter.update(rows(1L..5L) + old, false)
            screen.layout()
            firstVisible = screen.firstVisible
            id = screen.idAt(firstVisible)
        }

        assertEquals("the old first video is still the first on screen", 1000L, id)
        assertEquals("and five new ones are above it", 5, firstVisible)
    }

    /**
     * AC-5, plan section 7, first row of the rule: the first fill of an empty list does not
     * scroll, because "a saved scroll position may be restored; `scrollToPosition` would
     * discard it".
     *
     * A pass proves: `show` into an empty adapter (the final state, not during a refresh) fills
     * it and calls `onDone` before it returns (Groupie updates an empty adapter synchronously),
     * and a scroll position restored directly after it, as `handleLoadedState` does
     * (`FeedFragment.kt:842-845`), is where the list is after the layout.
     */
    @Test
    fun ac5_firstFill_doesNotScroll_soARestoredPositionIsKept() {
        val seen = Seen()
        onMain {
            val videos = rows(1000L..1039L)
            val saved: Parcelable = screen(videos, scrollTo = 12).manager.onSaveInstanceState()!!
            val screen = screen(emptyList())
            FeedListUpdate.show(screen.list, screen.adapter, videos, false, { seen.showNewItems++ }) { seen.onDone++ }
            seen.onDoneBeforeShowReturned = seen.onDone
            screen.manager.onRestoreInstanceState(saved)
            screen.layout()
            seen.itemCount = screen.adapter.itemCount
            seen.firstVisibleAfter = screen.firstVisible
            seen.idAfter = screen.idAt(seen.firstVisibleAfter)
            seen.topAfter = screen.topOf(seen.firstVisibleAfter)
        }

        assertEquals(40, seen.itemCount)
        assertEquals("upstream's callback runs, at once for an empty list", 1, seen.onDoneBeforeShowReturned)
        assertEquals(1, seen.onDone)
        assertEquals("AC-5: the restored position is kept", 12, seen.firstVisibleAfter)
        assertEquals(1012L, seen.idAfter)
        assertEquals(-30, seen.topAfter)
        assertEquals(0, seen.showNewItems)
    }

    /** AC-6 to AC-2: the first list of a page that showed the full-screen progress, during the refresh: filled, at the top, no indicator. */
    @Test
    fun ac5_firstFillDuringARefresh_isAtTheTopWithoutIndicator() {
        val seen = Seen()
        onMain {
            val screen = screen(emptyList())
            showAndSee(screen, rows(1L..40L), duringRefresh = true, seen = seen)
        }

        assertEquals(40, seen.itemCount)
        assertEquals(0, seen.firstVisibleAfter)
        assertEquals(1L, seen.idAfter)
        assertEquals(0, seen.showNewItems)
        assertEquals(1, seen.onDone)
    }

    /**
     * AC-5, grid mode, three columns: "In grid view the rows stay where they are".
     *
     * A pass proves: with the grid scrolled to its 8th row and six new videos (two full rows)
     * put in front, the same video is first on screen at the same pixel, and the indicator is
     * asked for once.
     */
    @Test
    fun ac5_gridMode_scrolledDown_sixNewVideosAbove_nothingOnScreenMoves() {
        val seen = Seen()
        onMain {
            val old = rows(1000L..1059L, span = 1)
            val screen = screen(old, spans = 3, scrollTo = 21)
            showAndSee(screen, rows(1L..6L, span = 1) + old, duringRefresh = true, seen = seen)
        }

        assertEquals("the test's own setup: the 8th row of three", 21, seen.firstVisibleBefore)
        assertEquals(1021L, seen.idBefore)
        assertEquals(-30, seen.topBefore)
        assertEquals("AC-5: the same video is first on screen", 1021L, seen.idAfter)
        assertEquals(27, seen.firstVisibleAfter)
        assertEquals("AC-5: at the same place", -30, seen.topAfter)
        assertEquals(1, seen.showNewItems)
        assertEquals(1, seen.onDone)
    }

    /**
     * AC-5 and answer A2, grid mode, the accepted limit: "videos can move between columns when
     * the number of new videos above is not a multiple of the column count".
     *
     * A pass proves: with four new videos in front of a three-column grid the rows keep their
     * vertical place: the first row on screen is still 30 px above the edge, and the video that
     * was first on screen is still in that row. It does not say in which column a video ends up.
     */
    @Test
    fun ac5_gridMode_scrolledDown_fourNewVideosAbove_theRowsKeepTheirPlace() {
        val seen = Seen()
        onMain {
            val old = rows(1000L..1059L, span = 1)
            val screen = screen(old, spans = 3, scrollTo = 21)
            showAndSee(screen, rows(1L..4L, span = 1) + old, duringRefresh = true, seen = seen)
        }

        assertEquals(1021L, seen.idBefore)
        assertEquals(-30, seen.topBefore)
        assertEquals("AC-5: the first row on screen is where it was", -30, seen.topAfter)
        assertEquals("AC-5: the video that was first on screen is still in that row", -30, seen.topOfTheSameVideoAfter)
        assertEquals(1, seen.showNewItems)
    }

    /**
     * AC-5, a filtered or shrinking list during a refresh: videos above the screen leave the
     * feed. A pass proves that the video first on screen stays at its place and that the
     * indicator is not asked for (nothing new is above).
     */
    @Test
    fun ac5_listMode_scrolledDown_videosAboveRemoved_nothingOnScreenMoves_noIndicator() {
        val seen = Seen()
        onMain {
            val old = rows(1000L..1039L)
            val screen = screen(old, scrollTo = 20)
            showAndSee(screen, old.drop(4), duringRefresh = true, seen = seen)
        }

        assertEquals(1020L, seen.idBefore)
        assertEquals(1020L, seen.idAfter)
        assertEquals(16, seen.firstVisibleAfter)
        assertEquals(-30, seen.topAfter)
        assertEquals(0, seen.showNewItems)
        assertEquals(1, seen.onDone)
    }
}
