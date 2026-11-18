package org.schabi.newpipe.enhancements.feedliveupdate

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.OnAsyncUpdateListener

/** Android glue: gives the feed's list its new items and applies [FeedListRules.afterUpdate]. */
object FeedListUpdate {

    /**
     * Shows [shown] in [adapter]. Main thread.
     *
     * During a refresh the update is synchronous: Groupie applies an asynchronous result on
     * top of whatever a synchronous update did meanwhile, and the search field updates
     * synchronously. Otherwise it is asynchronous, as upstream's was. Groupie holds the
     * asynchronous listener weakly and skips it when a newer asynchronous update follows.
     *
     * @param list captured, because the page's binding may be gone when an asynchronous result arrives
     * @param showNewItems the page's "new items" indicator
     * @param onDone upstream's own callback of the update; runs before the list is scrolled
     */
    fun show(
        list: RecyclerView,
        adapter: GroupAdapter<*>,
        shown: List<Item<*>>,
        duringRefresh: Boolean,
        showNewItems: () -> Unit,
        onDone: () -> Unit
    ) {
        // what the reader sees, read before the update
        val oldIds = (0 until adapter.itemCount).map { adapter.getItem(it).id }
        val atTop = !list.canScrollVertically(-1)
        val first = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val done = OnAsyncUpdateListener {
            onDone()
            when (FeedListRules.afterUpdate(atTop, first, oldIds, shown.map { it.id }, duringRefresh)) {
                AfterUpdate.SCROLL_TO_TOP -> list.scrollToPosition(0)
                AfterUpdate.SHOW_NEW_ITEMS -> showNewItems()
                AfterUpdate.NOTHING -> Unit
            }
        }
        if (duringRefresh) {
            adapter.update(shown, false)
            done.onUpdateComplete()
        } else {
            adapter.updateAsync(shown, false, done)
        }
    }
}
