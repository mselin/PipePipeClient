package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import org.schabi.newpipe.NewPipeDatabase

/**
 * Glue: what a refresh reads for the cooldown decision. Each function is one query or one
 * preference read, whatever the number of channels. The queries are blocking: call them off the
 * main thread (Room refuses them there).
 */
class RoomCooldownData(private val context: Context) : CooldownData {
    private val dao get() = NewPipeDatabase.getInstance(context).feedDAO()

    /** A row without a time is a failed refresh: no entry, like a channel that was never refreshed. */
    override fun lastUpdatedMillis(): Map<Long, Long> {
        val result = HashMap<Long, Long>()
        for (row in dao.cooldownLastUpdated()) row.lastUpdated?.let { result[row.subscriptionId] = it.toInstant().toEpochMilli() }
        return result
    }

    override fun memberships(): List<Membership> = dao.cooldownMemberships().map { Membership(it.feedGroupId, it.subscriptionId) }

    override fun groups(): List<GroupName> = dao.cooldownGroups().map { GroupName(it.uid, it.name) }

    override fun ownValues(): CooldownValues = CooldownStore.subscriptions(context)

    override fun groupValues(): CooldownValues = CooldownStore.groups(context)
}
