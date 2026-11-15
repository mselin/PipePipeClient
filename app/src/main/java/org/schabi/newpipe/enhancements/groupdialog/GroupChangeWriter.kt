package org.schabi.newpipe.enhancements.groupdialog

/** The database calls [GroupChangeWriter] needs. None of them sets a group's whole member list. */
interface GroupTable {
    /** Commits when [block] returns; rolls back if [block] throws. */
    fun inTransaction(block: () -> Unit)

    fun subscriptionExists(subscriptionId: Long): Boolean

    /** Inserts a group with the default icon, last in the manual order; returns the new id. */
    fun insertGroup(name: String): Long

    /** No effect if the subscription is already a member. */
    fun addMember(groupId: Long, subscriptionId: Long)

    /** No effect if the subscription is not a member. */
    fun removeMember(groupId: Long, subscriptionId: Long)
}

/** How everything one OK changes is written. Plain Kotlin, no Android. */
object GroupChangeWriter {

    /**
     * Writes all of [changes] in one transaction: the new groups with the subscription as member,
     * then the adds, then the removes. Nothing is caught: an exception of [table] reaches the
     * caller, and the transaction is rolled back.
     */
    fun apply(table: GroupTable, changes: GroupChanges) {
        if (changes.isEmpty) {
            return
        }

        val subscriptionId = changes.subscriptionId
        table.inTransaction {
            if (!table.subscriptionExists(subscriptionId)) {
                throw IllegalStateException("Subscription $subscriptionId does not exist")
            }

            for (name in changes.newGroupNames) {
                val groupId = table.insertGroup(name)
                table.addMember(groupId, subscriptionId)
            }
            for (groupId in changes.addToGroupIds) {
                table.addMember(groupId, subscriptionId)
            }
            for (groupId in changes.removeFromGroupIds) {
                table.removeMember(groupId, subscriptionId)
            }
        }
    }
}
