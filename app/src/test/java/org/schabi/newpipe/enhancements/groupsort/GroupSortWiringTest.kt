package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.APPEARANCE_SETTINGS
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.FEED_DATABASE_MANAGER
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.REORDER_DIALOG
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.REORDER_DIALOG_VIEW_MODEL

/**
 * The "wiring" tests of plan section 9: the four hook lines of plan section 3 (H1-H4), read
 * from the upstream files as text. The sorter and the stream are useless unless upstream calls
 * them, and a rebase can drop a hook without any other test noticing (plan section 12).
 *
 * Each upstream file gets exactly one line marked `ENH:group-sort` (plan section 1: "4 upstream
 * files touched, one line each"). Until the Code layer adds the hooks, every test here fails
 * with "must have exactly one line marked ENH:group-sort ... expected:<1> but was:<0>".
 */
class GroupSortWiringTest {

    private companion object {
        const val MARKER = "ENH:group-sort"
        const val UPSTREAM_MISSING = "an upstream file the feature hooks into is gone or was moved"
    }

    /** The one line of `app/src/main/<path>` that carries the marker. */
    private fun hookLine(ac: String, hook: String, path: String): String {
        val marked = GroupSortMainFiles.lines(ac, path, UPSTREAM_MISSING).filter { it.contains(MARKER) }
        assertEquals(
            "$ac: app/src/main/$path must have exactly one line marked $MARKER (hook $hook, plan section 3)",
            1,
            marked.size
        )
        return marked.single()
    }

    private fun assertHookLineContains(ac: String, hook: String, line: String, expected: String) {
        assertTrue(
            "$ac: the $MARKER line of hook $hook must contain `$expected` (plan section 3) but is: ${line.trim()}",
            line.contains(expected)
        )
    }

    /**
     * AC-3 and AC-4, hook H1: `FeedDatabaseManager.groups()`, the one stream all three group
     * lists read, returns the DAO stream wrapped by `GroupSortHooks.sortedGroups`.
     */
    @Test
    fun ac3_ac4_wiring_groupsOfFeedDatabaseManagerGoThroughSortedGroups() {
        val line = hookLine("AC-3/AC-4", "H1", FEED_DATABASE_MANAGER)
        assertHookLineContains("AC-3/AC-4", "H1", line, "GroupSortHooks.sortedGroups(feedGroupTable.getAll())")
        assertHookLineContains("AC-3/AC-4", "H1", line, "fun groups()")
    }

    /**
     * AC-9 and AC-10, hook H2: the reorder dialog reads the manual order through
     * `GroupSortHooks.manualGroups` and no longer through `feedDatabaseManager.groups()`, which
     * is sorted since H1. With H1 alone the dialog would show the name order and "OK" would
     * store it as the manual order (plan section 3).
     */
    @Test
    fun ac9_ac10_wiring_reorderDialogReadsTheManualOrder() {
        val line = hookLine("AC-9/AC-10", "H2", REORDER_DIALOG_VIEW_MODEL)
        assertHookLineContains("AC-9/AC-10", "H2", line, "GroupSortHooks.manualGroups(feedDatabaseManager)")

        val sortedReads = GroupSortMainFiles.lines("AC-9/AC-10", REORDER_DIALOG_VIEW_MODEL, UPSTREAM_MISSING)
            .filter { it.contains("feedDatabaseManager.groups()") }
        assertEquals(
            "AC-9/AC-10: app/src/main/$REORDER_DIALOG_VIEW_MODEL must not call feedDatabaseManager.groups() " +
                "(the sorted stream); found: ${sortedReads.map { it.trim() }}",
            0,
            sortedReads.size
        )
    }

    /** AC-10, hook H3: the reorder dialog attaches the notice to its root view. */
    @Test
    fun ac10_wiring_reorderDialogAttachesTheNotice() {
        val line = hookLine("AC-10", "H3", REORDER_DIALOG)
        assertHookLineContains("AC-10", "H3", line, "GroupSortHooks.attachReorderNotice(view)")
    }

    /**
     * AC-1, hook H4: the settings entry is one marked line of `appearance_settings.xml`, the
     * `<ListPreference ... /> <!-- ENH:group-sort -->` line of plan section 6.
     */
    @Test
    fun ac1_wiring_settingsEntryIsOneMarkedLineOfAppearanceSettings() {
        val line = hookLine("AC-1", "H4", APPEARANCE_SETTINGS)
        assertHookLineContains("AC-1", "H4", line, "<ListPreference")
        assertHookLineContains("AC-1", "H4", line, "android:key=\"@string/enh_group_sort_key\"")
    }
}
