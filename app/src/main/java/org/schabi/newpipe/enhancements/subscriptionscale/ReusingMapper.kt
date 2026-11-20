package org.schabi.newpipe.enhancements.subscriptionscale

/**
 * Maps rows to items and hands out the same item again for a row equal to the one last mapped
 * under the same id. A list that compares its items by instance (Groupie) then sees only the
 * rows that really changed.
 *
 * Rows whose id is not in the latest list are forgotten. Work per call is linear in the number
 * of rows. Used by one stream; not thread-safe.
 */
class ReusingMapper<R : Any, I : Any>(private val idOf: (R) -> Long, private val create: (R) -> I) {

    private class Entry<R, I>(val row: R, val item: I)

    private var previous: Map<Long, Entry<R, I>> = emptyMap()

    fun map(rows: List<R>): List<I> {
        val next = HashMap<Long, Entry<R, I>>((rows.size * 4 + 2) / 3)
        val items = ArrayList<I>(rows.size)
        for (row in rows) {
            val id = idOf(row)
            val old = previous[id]
            val entry = if (old != null && old.row == row) old else Entry(row, create(row))
            next[id] = entry
            items.add(entry.item)
        }
        previous = next
        return items
    }
}
