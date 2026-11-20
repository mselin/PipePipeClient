package org.schabi.newpipe.enhancements.groupdialog

import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon

/**
 * [GroupTable] on the app's Room database. Every method blocks and must run off the main thread.
 * The DAO's own `@Transaction` methods join the transaction of [inTransaction]. Only rows
 * (group, this subscription) of the join table are inserted or deleted.
 */
class RoomGroupTable(private val database: AppDatabase) : GroupTable {

    override fun inTransaction(block: () -> Unit) {
        database.runInTransaction(Runnable { block() })
    }

    override fun subscriptionExists(subscriptionId: Long): Boolean =
        database.subscriptionDAO().getSubscription(subscriptionId) != null

    override fun insertGroup(name: String): Long =
        database.feedGroupDAO().insert(FeedGroupEntity(0, name, FeedGroupIcon.ALL))

    override fun addMember(groupId: Long, subscriptionId: Long) {
        database.feedGroupDAO()
            .insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(groupId, subscriptionId)))
    }

    override fun removeMember(groupId: Long, subscriptionId: Long) {
        database.feedGroupDAO().deleteSubscriptionFromGroup(groupId, subscriptionId)
    }
}
