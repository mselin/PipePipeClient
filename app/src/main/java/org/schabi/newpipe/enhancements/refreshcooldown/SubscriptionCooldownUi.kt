package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.channel.ChannelInfoItem

/** Glue: the fourth entry of a channel's long-press menu on the Subscriptions tab. */
object SubscriptionCooldownUi {
    fun entry(context: Context, item: ChannelInfoItem): String {
        val value = CooldownStore.subscriptions(context)[CooldownValues.subscriptionKey(item.serviceId, item.url)]
        return context.getString(
            R.string.enh_refresh_cooldown_entry,
            CooldownStore.choices(context).label(value, context.getString(R.string.enh_refresh_cooldown_use_default))
        )
    }

    fun choose(context: Context, item: ChannelInfoItem) {
        val key = CooldownValues.subscriptionKey(item.serviceId, item.url)
        CooldownChooser.show(context, CooldownStore.subscriptions(context)[key]) { seconds ->
            CooldownStore.setSubscription(context, key, seconds)
        }
    }
}
