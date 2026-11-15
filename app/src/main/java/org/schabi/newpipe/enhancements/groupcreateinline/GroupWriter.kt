package org.schabi.newpipe.enhancements.groupcreateinline

/** The database calls [GroupWriter] needs. */
interface GroupTable {
    /** Commits when [block] returns; rolls back if [block] throws. */
    fun inTransaction(block: () -> Unit)

    fun subscriptionExists(subscriptionId: Long): Boolean

    /** Inserts a group with the default icon, last in the manual order; returns the new id. */
    fun insertGroup(name: String): Long

    fun setMembers(groupId: Long, subscriptionIds: List<Long>)
}

/** How the checked pending groups are written. Plain Kotlin, no Android. */
object GroupWriter {

    /**
     * Creates one group per name, in list order, each with [subscriptionId] as its only member,
     * in one transaction. Nothing is caught: an exception of [table] reaches the caller, and the
     * transaction is rolled back.
     */
    fun createGroupsWithMember(table: GroupTable, names: List<String>, subscriptionId: Long) {
        if (names.isEmpty()) {
            return
        }

        table.inTransaction {
            if (!table.subscriptionExists(subscriptionId)) {
                throw IllegalStateException("Subscription $subscriptionId does not exist")
            }

            for (name in names) {
                val groupId = table.insertGroup(name)
                table.setMembers(groupId, listOf(subscriptionId))
            }
        }
    }
}
