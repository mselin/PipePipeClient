package org.schabi.newpipe.enhancements.groupsort

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.FeedDatabaseManager

/**
 * Everything upstream code calls for the group-sort feature (hook lines marked
 * `ENH:group-sort`).
 */
object GroupSortHooks {

    /** H1: the groups in manual order, sorted by the current setting and re-sorted when it changes. */
    fun sortedGroups(manualOrder: Flowable<List<FeedGroupEntity>>): Flowable<List<FeedGroupEntity>> =
        GroupSortStreams.sorted(manualOrder, GroupSortPreference.modes(App.getApp())) { it.name }

    /** H2: the groups in manual order, unsorted, for the reorder dialog. */
    fun manualGroups(manager: FeedDatabaseManager): Flowable<List<FeedGroupEntity>> =
        manager.database().feedGroupDAO().getAll()

    /** H3: puts the notice above the list of the reorder dialog while groups are sorted by name. */
    fun attachReorderNotice(dialogRoot: View) {
        if (GroupSortPreference.current(dialogRoot.context).sortsByName && dialogRoot is ViewGroup) {
            val notice = LayoutInflater.from(dialogRoot.context)
                .inflate(R.layout.enhancements_group_sort_notice, dialogRoot, false)
            dialogRoot.addView(notice, 0)
        }
    }
}
