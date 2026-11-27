package org.schabi.newpipe.enhancements.subscriptionscale

import androidx.recyclerview.widget.DiffUtil
import com.xwray.groupie.Group
import com.xwray.groupie.Item
import com.xwray.groupie.Section

/**
 * Groupie's `Section.update(groups)`, in time linear in the rows.
 *
 * Groupie 2.10.1 diffs with a callback that finds each item by walking the list from its start,
 * so one update of N rows costs about 2·N² steps: some 5 million for 1600 subscriptions, seconds
 * on the main thread of a debug build. Here the items are read by index, and the list the section
 * already shows (the same instances in the same order) is not diffed at all. DiffUtil gets the
 * same answers for the same pairs, so the section ends with the same children and reports the
 * same changes as with its own update.
 *
 * Main thread only, as `Section.update`.
 */
object SectionUpdate {

    /**
     * What `section.update(groups, detectMoves)` does, in time linear in the rows when every
     * group is one item. The default is Groupie's: `section.update(groups)` detects moves.
     */
    fun update(section: Section, groups: List<Group>, detectMoves: Boolean = true) {
        val old = section.groups // a copy of the children
        if (!old.all { it is Item<*> } || !groups.all { it is Item<*> }) {
            section.update(groups, detectMoves)
            return
        }
        if (sameInstances(old, groups)) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        val callback = ByIndex(old as List<Item<*>>, groups as List<Item<*>>)
        section.update(groups, DiffUtil.calculateDiff(callback, detectMoves))
    }

    private fun sameInstances(a: List<Group>, b: List<Group>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i] !== b[i]) return false
        }
        return true
    }

    /** Groupie's `DiffCallback`, asking the same questions in the same direction, with reads by index. */
    private class ByIndex(private val old: List<Item<*>>, private val new: List<Item<*>>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            new[newItemPosition].isSameAs(old[oldItemPosition])
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            new[newItemPosition].hasSameContentAs(old[oldItemPosition])
        override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
            old[oldItemPosition].getChangePayload(new[newItemPosition])
    }
}
