package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Notification
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo

/**
 * What a feed refresh keeps of a batch after the batch has been stored in the database.
 *
 * A manual refresh keeps nothing. The notification run keeps, per channel with new videos, a
 * copy without the channel's whole video list and without its errors: the notification needs
 * only the channel's name, address, picture and its new videos.
 */
object FeedResultRetention {

    fun retain(batch: List<Notification<FeedUpdateInfo>>, retainNewStreams: Boolean): List<Notification<FeedUpdateInfo>> {
        if (!retainNewStreams) return emptyList()

        val kept = ArrayList<Notification<FeedUpdateInfo>>()
        for (notification in batch) {
            val info = notification.value ?: continue // an error, or completion: nothing to notify about
            // newStreams is lateinit and stays unset for a channel unsubscribed during the refresh.
            val newStreams = runCatching { info.newStreams }.getOrNull()
            if (newStreams.isNullOrEmpty()) continue

            val slim = info.copy(streams = emptyList(), errors = emptyList())
            slim.newStreams = newStreams
            kept.add(Notification.createOnNext(slim))
        }
        return kept
    }
}
