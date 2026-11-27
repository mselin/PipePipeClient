package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.extractor.channel.ChannelInfoItem

/**
 * The Subscriptions tab's list query: the six columns a channel row shows or needs, in the order
 * of `SubscriptionDAO.getAll()`. No `description`, which is most of a row's size.
 * The aliases are the property names of [SubscriptionRow]; Room maps by them.
 */
const val SUBSCRIPTION_ROWS_QUERY = "SELECT uid, service_id AS serviceId, url, name, avatar_url AS avatarUrl, subscriber_count AS subscriberCount FROM subscriptions ORDER BY name COLLATE NOCASE ASC"

data class SubscriptionRow(val uid: Long, val serviceId: Int, val url: String?, val name: String?, val avatarUrl: String?, val subscriberCount: Long?) {

    /** As `SubscriptionEntity.toChannelInfoItem()`, without the description. */
    fun toChannelInfoItem(): ChannelInfoItem {
        val item = ChannelInfoItem(serviceId, url, name)
        item.thumbnailUrl = avatarUrl
        item.subscriberCount = subscriberCount ?: UNKNOWN_SUBSCRIPTION_COUNT
        return item
    }
}

/** `ChannelInfoItem`'s "not known", as `SubscriptionEntity.UNKNOWN_SUBSCRIBER_COUNT`. */
private const val UNKNOWN_SUBSCRIPTION_COUNT = -1L

object SubscriptionRows {

    /** Same channels in the same order with the same names and pictures; subscriber counts may differ. */
    fun sameNamesAndPictures(old: List<SubscriptionRow>, new: List<SubscriptionRow>): Boolean {
        if (old === new) return true
        if (old.size != new.size) return false
        val a = old.iterator()
        val b = new.iterator()
        while (a.hasNext() && b.hasNext()) {
            val x = a.next()
            val y = b.next()
            if (x.uid != y.uid || x.serviceId != y.serviceId || x.url != y.url || x.name != y.name || x.avatarUrl != y.avatarUrl) {
                return false
            }
        }
        return true
    }

    /**
     * The list the tab shows. While on screen, a write that changes only subscriber counts emits
     * nothing (inner filter, which lives for one stay on screen). A return to the screen lets the
     * current list through unless nothing at all changed since the last list shown (outer filter,
     * all six fields).
     */
    fun listing(rows: Flowable<List<SubscriptionRow>>, gate: OnScreenGate): Flowable<List<SubscriptionRow>> =
        gate.gate(rows.distinctUntilChanged(::sameNamesAndPictures)).distinctUntilChanged()
}
