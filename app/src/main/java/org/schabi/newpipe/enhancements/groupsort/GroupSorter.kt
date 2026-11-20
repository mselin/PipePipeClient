package org.schabi.newpipe.enhancements.groupsort

/**
 * Orders a group list for a [GroupSortMode]. Pure Kotlin, no Android.
 */
object GroupSorter {

    /**
     * Compares two names character by character (per UTF-16 unit) by character code, after
     * trimming surrounding whitespace and folding case. A name that is the beginning of the
     * other comes first. Negative, 0 or positive.
     */
    fun compareNames(a: String, b: String): Int {
        val x = a.trim()
        val y = b.trim()
        for (i in 0 until minOf(x.length, y.length)) {
            val cx = fold(x[i])
            val cy = fold(y[i])
            if (cx != cy) {
                return cx.code - cy.code
            }
        }
        return x.length - y.length
    }

    /**
     * [groups] in the order of [mode]: itself for [GroupSortMode.MANUAL], otherwise a new,
     * stable-sorted list (equal names keep their order in both directions). The input is
     * never modified.
     */
    fun <T> sort(groups: List<T>, mode: GroupSortMode, nameOf: (T) -> String): List<T> =
        when (mode) {
            GroupSortMode.MANUAL -> groups
            GroupSortMode.NAME_ASC -> groups.sortedWith(Comparator { x, y -> compareNames(nameOf(x), nameOf(y)) })
            GroupSortMode.NAME_DESC -> groups.sortedWith(Comparator { x, y -> compareNames(nameOf(y), nameOf(x)) })
        }

    /** Case folding per UTF-16 unit, independent of the default locale. */
    private fun fold(c: Char): Char = Character.toLowerCase(Character.toUpperCase(c))
}
