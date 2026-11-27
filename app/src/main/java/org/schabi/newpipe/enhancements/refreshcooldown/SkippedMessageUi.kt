package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import org.schabi.newpipe.R
import org.schabi.newpipe.local.feed.service.FeedLoadService

/**
 * Glue: the message "n channels were skipped because of their cooldown" with "Refresh anyway".
 *
 * The feed page calls [offer] whenever it shows a loaded state. The report is taken only when
 * the message is really shown, by the page of the feed that was refreshed while it is on
 * screen, so a page that is not on screen leaves it for its return, and it is shown once.
 */
object SkippedMessageUi {
    /** [afterErrors]: the page is showing upstream's error message for the same refresh; this one waits for it. */
    fun offer(fragment: Fragment, groupId: Long, afterErrors: Boolean) {
        val view = fragment.view ?: return
        if (afterErrors) {
            view.postDelayed({ show(fragment, groupId) }, SkipReports.ERROR_MESSAGE_WAIT_MILLIS)
        } else {
            show(fragment, groupId)
        }
    }

    private fun show(fragment: Fragment, groupId: Long) {
        val view = fragment.view ?: return
        if (!fragment.isResumed) return
        val report = SkipReports.app.take(groupId, SystemClock.elapsedRealtime()) ?: return
        // no suitable parent view: no message
        runCatching {
            val context = view.context
            Snackbar.make(
                view,
                context.resources.getQuantityString(R.plurals.enh_refresh_cooldown_skipped, report.count, report.count),
                SkipReports.MESSAGE_MILLIS
            )
                .setActionTextColor(Color.YELLOW)
                .setAction(R.string.enh_refresh_cooldown_refresh_anyway) { refreshAnyway(context, report) }
                .show()
        }
    }

    /**
     * What the page's own refresh does, plus the ids: the loader then loads exactly these
     * channels of the feed. A start during a running refresh is dropped by the service together
     * with its ids.
     */
    private fun refreshAnyway(context: Context, report: SkipReport) {
        context.startService(
            Intent(context, FeedLoadService::class.java)
                .putExtra(FeedLoadService.EXTRA_GROUP_ID, report.groupId)
                .putExtra(SkipReports.EXTRA_FORCED, report.subscriptionIds.toLongArray())
        )
    }
}
