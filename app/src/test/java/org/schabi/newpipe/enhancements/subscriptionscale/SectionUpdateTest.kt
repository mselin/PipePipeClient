package org.schabi.newpipe.enhancements.subscriptionscale

import com.xwray.groupie.Group
import com.xwray.groupie.GroupDataObserver
import com.xwray.groupie.GroupieViewHolder
import com.xwray.groupie.Item
import com.xwray.groupie.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-8 and AC-9 (plan amendments of 2026-11-27): `SectionUpdate.update(section, groups,
 * detectMoves)` does what Groupie's `section.update(groups, detectMoves)` does, without its
 * quadratic item lookups.
 *
 * These tests run Groupie's real `Section` and `androidx.recyclerview`'s real `DiffUtil` on the
 * JVM; neither touches an Android framework class on these paths. What a section tells its
 * parent (and through it the RecyclerView) is recorded by a [Recorder] registered on it.
 */
class SectionUpdateTest {

    /** Counts `getItemCount()` calls: Groupie's lookup walks the list calling it on every group it passes. */
    private class Counter {
        var calls = 0L
    }

    /**
     * Stands for `ChannelItem`: identity by id (Groupie's `isSameAs` compares id and layout),
     * content by [text], and a change payload so that the payload's direction is checked.
     */
    private class Row(id: Long, val text: String, private val counter: Counter? = null) : Item<GroupieViewHolder>(id) {
        override fun getLayout(): Int = 1
        override fun bind(viewHolder: GroupieViewHolder, position: Int) = Unit
        override fun hasSameContentAs(other: Item<*>): Boolean = other is Row && other.text == text
        override fun getChangePayload(newItem: Item<*>): Any = "$text>${(newItem as Row).text}"
        override fun getItemCount(): Int {
            counter?.let { it.calls++ }
            return 1
        }
        override fun toString(): String = "Row($id,$text)"
    }

    /** Everything a section reports to its parent, in order. */
    private class Recorder : GroupDataObserver {
        val events = mutableListOf<String>()
        override fun onChanged(group: Group) { events += "changed" }
        override fun onItemInserted(group: Group, position: Int) { events += "inserted $position" }
        override fun onItemChanged(group: Group, position: Int) { events += "itemChanged $position" }
        override fun onItemChanged(group: Group, position: Int, payload: Any?) { events += "itemChanged $position $payload" }
        override fun onItemRemoved(group: Group, position: Int) { events += "removed $position" }
        override fun onItemRangeChanged(group: Group, positionStart: Int, itemCount: Int) { events += "rangeChanged $positionStart $itemCount" }
        override fun onItemRangeChanged(group: Group, positionStart: Int, itemCount: Int, payload: Any?) {
            events += "rangeChanged $positionStart $itemCount $payload"
        }
        override fun onItemRangeInserted(group: Group, positionStart: Int, itemCount: Int) { events += "rangeInserted $positionStart $itemCount" }
        override fun onItemRangeRemoved(group: Group, positionStart: Int, itemCount: Int) { events += "rangeRemoved $positionStart $itemCount" }
        override fun onItemMoved(group: Group, fromPosition: Int, toPosition: Int) { events += "moved $fromPosition $toPosition" }
        override fun onDataSetInvalidated() { events += "invalidated" }
    }

    /**
     * One section with its own items, made from (id, text) pairs. Like `ReusingMapper`, the same
     * pair always gives the same instance within one world, so that two worlds built from the
     * same pairs have the same pattern of reused and new instances.
     */
    private class World(first: List<Pair<Long, String>>, private val reuse: Boolean = true) {
        private val made = HashMap<Pair<Long, String>, Row>()
        val section = Section()
        val recorder = Recorder()

        init {
            section.update(rows(first))
            section.registerGroupDataObserver(recorder)
        }

        /**
         * With [reuse] (the Subscriptions tab, `ReusingMapper`), the same pair gives the same
         * instance; without (the group editor and a group's page), every list has new instances.
         */
        fun rows(pairs: List<Pair<Long, String>>): List<Group> =
            pairs.map { if (reuse) made.getOrPut(it) { Row(it.first, it.second) } else Row(it.first, it.second) }

        fun children(): List<String> = section.groups.map { it.toString() }
    }

    private fun pairs(vararg ids: Long, text: (Long) -> String = { "r$it" }): List<Pair<Long, String>> = ids.map { it to text(it) }

    /** Applies [next] with SectionUpdate in one world and with Groupie's own update in a twin world. */
    private fun assertSameAsGroupie(
        why: String,
        first: List<Pair<Long, String>>,
        next: List<Pair<Long, String>>,
        detectMoves: Boolean = true,
        reuse: Boolean = true
    ) {
        val ours = World(first, reuse)
        val theirs = World(first, reuse)

        if (detectMoves) {
            SectionUpdate.update(ours.section, ours.rows(next))
            theirs.section.update(theirs.rows(next))
        } else {
            SectionUpdate.update(ours.section, ours.rows(next), false)
            theirs.section.update(theirs.rows(next), false)
        }

        assertEquals("$why: the children", theirs.children(), ours.children())
        assertEquals("$why: what the section reports to its parent", theirs.recorder.events, ours.recorder.events)
    }

    @Test
    fun ac8_theSameInstancesAgain_changeAndReportNothing() {
        val world = World(pairs(1, 2, 3, 4))
        val before = world.section.groups

        SectionUpdate.update(world.section, world.rows(pairs(1, 2, 3, 4)))

        assertEquals("no notification for the list the tab already shows", emptyList<String>(), world.recorder.events)
        val after = world.section.groups
        assertEquals(before.size, after.size)
        before.indices.forEach { assertSame("the same child at $it", before[it], after[it]) }
    }

    @Test
    fun ac8_insertRemoveChangeAndMove_asGroupie() {
        assertSameAsGroupie("insert", pairs(1, 2, 3), pairs(1, 2, 9, 3))
        assertSameAsGroupie("remove", pairs(1, 2, 3, 4), pairs(1, 3, 4))
        assertSameAsGroupie("content change with a payload", pairs(1, 2, 3), listOf(1L to "r1", 2L to "renamed", 3L to "r3"))
        assertSameAsGroupie("move", pairs(1, 2, 3, 4, 5), pairs(1, 4, 2, 3, 5))
        assertSameAsGroupie("all at once", pairs(1, 2, 3, 4, 5, 6), listOf(6L to "r6", 1L to "x", 3L to "r3", 7L to "r7", 5L to "r5"))
    }

    @Test
    fun ac8_filterToASubsetAndBack_asGroupie() {
        val all = (1L..40L).map { it to "r$it" }
        val few = all.filter { it.first % 7 == 0L }
        assertSameAsGroupie("filter to a few", all, few)
        assertSameAsGroupie("clear the filter", few, all)
    }

    @Test
    fun ac8_emptyToFullAndFullToEmpty_asGroupie() {
        assertSameAsGroupie("first fill", emptyList(), pairs(1, 2, 3))
        assertSameAsGroupie("emptied", pairs(1, 2, 3), emptyList())
    }

    /** AC-9: the group editor calls `update(subscriptions, false)`; without move detection the result is Groupie's too. */
    @Test
    fun ac9_withoutMoveDetection_asGroupie() {
        assertSameAsGroupie("move", pairs(1, 2, 3, 4, 5), pairs(1, 4, 2, 3, 5), detectMoves = false)
        assertSameAsGroupie("all at once", pairs(1, 2, 3, 4, 5, 6), listOf(6L to "r6", 1L to "x", 3L to "r3", 7L to "r7", 5L to "r5"), detectMoves = false)
        val all = (1L..40L).map { it to "r$it" }
        assertSameAsGroupie("filter to a few", all, all.filter { it.first % 7 == 0L }, detectMoves = false)
        assertSameAsGroupie("first fill", emptyList(), pairs(1, 2, 3), detectMoves = false)
    }

    /**
     * AC-9: both lists get new items for every list (`PickerSubscriptionItem`, `ChannelItem`), with
     * the subscription's id and content compared by value. The result is Groupie's, with and
     * without move detection.
     */
    @Test
    fun ac9_newInstancesEveryTime_asGroupie() {
        val all = (1L..40L).map { it to "r$it" }
        for (detectMoves in listOf(true, false)) {
            assertSameAsGroupie("the same rows again [$detectMoves]", all, all, detectMoves, reuse = false)
            assertSameAsGroupie("one selected [$detectMoves]", all, all.map { if (it.first == 9L) 9L to "selected" else it }, detectMoves, reuse = false)
            assertSameAsGroupie("search [$detectMoves]", all, all.filter { it.first % 5 == 0L }, detectMoves, reuse = false)
            assertSameAsGroupie("search cleared [$detectMoves]", all.filter { it.first % 5 == 0L }, all, detectMoves, reuse = false)
        }
    }

    @Test
    fun ac8_aGroupThatIsNotOneItem_isLeftToGroupie() {
        fun nested(world: World) = Section(world.rows(pairs(20, 21)))
        val ours = World(pairs(1, 2))
        val theirs = World(pairs(1, 2))

        SectionUpdate.update(ours.section, ours.rows(pairs(1)) + nested(ours) + ours.rows(pairs(2)))
        theirs.section.update(theirs.rows(pairs(1)) + nested(theirs) + theirs.rows(pairs(2)))

        assertEquals(theirs.recorder.events, ours.recorder.events)
        assertEquals(theirs.section.itemCount, ours.section.itemCount)
    }

    /**
     * AC-8: "the work for one update grows in step with the number of subscriptions". With 1600
     * rows and 20 of them renamed, the update asks the rows for their size a few times per row.
     * Groupie's own update of the same lists walks the list for every comparison (the finding of
     * the plan amendment); if an upgrade of Groupie changes that, the second assertion fails and
     * the fix can be reconsidered.
     */
    @Test
    fun ac8_1600Rows_linearHere_quadraticInGroupiesOwnUpdate() {
        val n = 1600
        fun lists(counter: Counter): Pair<List<Row>, List<Row>> {
            val old = (1..n).map { Row(it.toLong(), "r$it", counter) }
            val renamed = (0 until 20).map { (it * 79) % n }.toSet()
            val new = old.mapIndexed { i, row -> if (i in renamed) Row(row.id, "renamed", counter) else row }
            return old to new
        }

        val ourCounter = Counter()
        val (ourOld, ourNew) = lists(ourCounter)
        val ours = Section(ourOld)
        ourCounter.calls = 0
        SectionUpdate.update(ours, ourNew)
        assertTrue("at most 10 size queries per row, was ${ourCounter.calls}", ourCounter.calls <= 10L * n)
        assertEquals(n, ours.itemCount)

        // AC-9: the group editor's case, new instances for every row and no move detection.
        val pickerCounter = Counter()
        val picker = Section((1..n).map { Row(it.toLong(), "r$it", pickerCounter) })
        val fresh = (1..n).map { Row(it.toLong(), if (it % 80 == 0) "selected" else "r$it", pickerCounter) }
        pickerCounter.calls = 0
        SectionUpdate.update(picker, fresh, false)
        assertTrue("group editor: at most 10 size queries per row, was ${pickerCounter.calls}", pickerCounter.calls <= 10L * n)
        assertEquals(n, picker.itemCount)

        val theirCounter = Counter()
        val (theirOld, theirNew) = lists(theirCounter)
        val theirs = Section(theirOld)
        theirCounter.calls = 0
        theirs.update(theirNew)
        assertTrue(
            "the finding: Groupie's own update walks the list per comparison, was ${theirCounter.calls}",
            theirCounter.calls > n.toLong() * n / 4
        )
    }
}
