package org.schabi.newpipe.enhancements.groupcreateinline

/** One call that arrived at [FakeTable], as a value. `inTransaction` is tracked separately. */
internal sealed class TableCall {
    /** `subscriptionExists(subscriptionId)`. */
    data class SubscriptionExists(val subscriptionId: Long) : TableCall()

    /** `insertGroup(name)`. */
    data class InsertGroup(val name: String) : TableCall()

    /** `setMembers(groupId, subscriptionIds)`. [subscriptionIds] is a copy. */
    data class SetMembers(val groupId: Long, val subscriptionIds: List<Long>) : TableCall()
}

/**
 * Hand-written test double for [GroupTable] (plan section 8: "`FakeTable` records calls and
 * whether each was inside `inTransaction`, hands out ids, can throw on the n-th `setMembers`,
 * can report the subscription missing").
 *
 * Besides the calls it keeps the rows a database would hold, with the behaviour the interface
 * promises for [inTransaction]: the block's writes are committed when it returns and thrown away
 * when it throws. Writes made outside a transaction are committed at once. [storedGroups] is
 * therefore what a user would find afterwards.
 */
internal class FakeTable(
    /** `subscriptionExists` answers false for every id. */
    private val subscriptionMissing: Boolean = false,
    /**
     * The ids `insertGroup` hands out, in this order. Neither starting at 1 nor ascending, so
     * that only the returned value can be the right argument for `setMembers`.
     */
    private val ids: List<Long> = listOf(41L, 17L, 93L, 58L),
    /** 1 = the first `setMembers` call throws [failure], 2 = the second, ...; 0 = none. */
    private val failOnSetMembersCall: Int = 0,
    /** What the failing `setMembers` call throws. */
    val failure: RuntimeException = RuntimeException("FakeTable: setMembers fails on purpose")
) : GroupTable {

    /** A call and whether it arrived while an [inTransaction] block was running. */
    data class Recorded(val call: TableCall, val insideTransaction: Boolean)

    /** A group row together with its member subscriptions. */
    data class StoredGroup(val id: Long, val name: String, val members: List<Long>)

    private val log = mutableListOf<Recorded>()
    private val committed = mutableListOf<StoredGroup>()
    private var uncommitted: MutableList<StoredGroup>? = null
    private var depth = 0
    private var idsHandedOut = 0
    private var setMembersCalls = 0

    /** Number of [inTransaction] calls. */
    var transactionsStarted = 0
        private set

    /** Number of outermost [inTransaction] blocks that returned normally. */
    var transactionsCommitted = 0
        private set

    /** Number of outermost [inTransaction] blocks that threw. */
    var transactionsRolledBack = 0
        private set

    /** Every call except `inTransaction`, oldest first, each with its transaction flag. */
    val recorded: List<Recorded> get() = log.toList()

    /** Every call except `inTransaction`, oldest first. */
    val calls: List<TableCall> get() = log.map { it.call }

    /** The calls that arrived while no [inTransaction] block was running. */
    val callsOutsideTransaction: List<TableCall>
        get() = log.filterNot { it.insideTransaction }.map { it.call }

    /** The committed groups in the order they were inserted: what the database holds now. */
    val storedGroups: List<StoredGroup> get() = committed.toList()

    override fun inTransaction(block: () -> Unit) {
        transactionsStarted++
        if (depth == 0) uncommitted = committed.toMutableList()
        depth++
        try {
            block()
        } catch (thrown: Throwable) {
            depth--
            if (depth == 0) {
                uncommitted = null
                transactionsRolledBack++
            }
            throw thrown
        }
        depth--
        if (depth == 0) {
            val written = uncommitted ?: throw AssertionError("FakeTable lost its open transaction")
            committed.clear()
            committed.addAll(written)
            uncommitted = null
            transactionsCommitted++
        }
    }

    override fun subscriptionExists(subscriptionId: Long): Boolean {
        record(TableCall.SubscriptionExists(subscriptionId))
        return !subscriptionMissing
    }

    override fun insertGroup(name: String): Long {
        record(TableCall.InsertGroup(name))
        if (idsHandedOut == ids.size) {
            throw AssertionError("FakeTable was set up with ${ids.size} ids, one more group was inserted")
        }
        val id = ids[idsHandedOut++]
        rows().add(StoredGroup(id, name, emptyList()))
        return id
    }

    override fun setMembers(groupId: Long, subscriptionIds: List<Long>) {
        record(TableCall.SetMembers(groupId, subscriptionIds.toList()))
        setMembersCalls++
        if (setMembersCalls == failOnSetMembersCall) throw failure
        val rows = rows()
        val index = rows.indexOfFirst { it.id == groupId }
        if (index >= 0) rows[index] = rows[index].copy(members = subscriptionIds.toList())
    }

    private fun rows(): MutableList<StoredGroup> = uncommitted ?: committed

    private fun record(call: TableCall) {
        log += Recorded(call, insideTransaction = depth > 0)
    }
}
