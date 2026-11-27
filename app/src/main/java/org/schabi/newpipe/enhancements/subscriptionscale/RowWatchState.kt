package org.schabi.newpipe.enhancements.subscriptionscale

import android.util.Log
import android.view.View
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.views.AnimatedProgressBar
import java.util.concurrent.TimeUnit

/**
 * The watched state of one video row, without the row waiting for the database.
 *
 * A bind (main thread) does three things in this order:
 * 1. [cancel]: whatever the holder asked for its previous video can no longer arrive;
 * 2. [cached]: draw what is known about the video from an earlier draw in this process;
 * 3. `refresh`: ask the database on an io thread. The answer comes back on the main thread and
 *    is applied only if it differs from what step 2 drew.
 *
 * One instance per row holder. All methods are for the main thread.
 */
class RowWatchState {

    private val latest = LatestRequest<String>()

    /**
     * Brings [progressView] up to date with the stored state of [item]. Applies the rule of the
     * holders' own blocks, without animation: a state and a known duration -> visible, with the
     * duration as maximum and the watched seconds as progress; otherwise gone.
     */
    fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, progressView: AnimatedProgressBar) {
        load(item, manager) { state ->
            if (state != null && item.duration > 0) {
                progressView.visibility = View.VISIBLE
                progressView.max = item.duration.toInt()
                progressView.progress = TimeUnit.MILLISECONDS.toSeconds(state.progressMillis).toInt()
            } else {
                progressView.visibility = View.GONE
            }
        }
    }

    /** Runs [onChanged] if the stored state of [item] differs from the cached one; [cached] then returns the new one. */
    fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, onChanged: Runnable) {
        load(item, manager) { onChanged.run() }
    }

    fun cancel() {
        latest.cancel()
    }

    private fun load(item: StreamInfoItem, manager: HistoryRecordManager, apply: (StreamStateEntity?) -> Unit) {
        val key = keyOf(item)
        // What the bind has just drawn (the holders call cached() right before refresh()). The
        // answer is compared with this and not with the cache at answer time: two rows showing
        // the same video would otherwise leave the second one uncorrected.
        val drawn = cached(item)
        val request = manager.loadStreamState(item)
            .doOnError { Log.w(TAG, "Could not load the watched state of ${item.url}", it) }
        latest.start(key, request, AndroidSchedulers.mainThread()) { result ->
            val state: StreamStateEntity? = result.firstOrNull()
            cache.put(key, if (state == null) Known.None else Known.State(state))
            if (state != drawn) apply(state)
        }
    }

    /** What the cache knows about a video: its state, or that it has none. Absent = never asked. */
    private sealed class Known {
        object None : Known()
        class State(val state: StreamStateEntity) : Known()
    }

    companion object {
        private const val TAG = "RowWatchState"

        /** Process-wide; a few screens of rows. */
        private val cache = BoundedCache<String, Known>(500)

        private fun keyOf(item: InfoItem): String = "${item.serviceId}|${item.url}"

        /** The state [item] was last seen with in this process; null if it had none or was never asked. */
        @JvmStatic
        fun cached(item: InfoItem): StreamStateEntity? = (cache.get(keyOf(item)) as? Known.State)?.state
    }
}
