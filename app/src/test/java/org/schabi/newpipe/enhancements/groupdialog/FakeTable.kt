package org.schabi.newpipe.enhancements.groupdialog

/** One call that arrived at [FakeTable], as a value. `inTransaction` is tracked separately. */
internal sealed class TableCall {
    /** `subscriptionExists(subscriptionId)`. */
    data class SubscriptionExists(val subscriptionId: Long) : TableCall()

    /** `insertGroup(name)`. */
    data class InsertGroup(val name: String) : TableCall()

    /** `addMember(groupId, subscriptionId)`. */
    data class AddMember(val groupId: Long, val subscriptionId: Long) : TableCall()

    /** `removeMember(groupId, subscriptionId)`. */
    data class RemoveMember(val groupId: Long, val subscriptionId: Long) : TableCall()
}

/**
 * Hand-written test double for [GroupTable] (plan section 7: "`FakeTable` stores groups with
 * member lists, including other members; `addMember` / `removeMember` are idempotent; it can
 * fail on the n-th write call and report the subscription missing").
 *
 * Besides the calls it keeps the rows a database would hold, with the behaviour the interface
 * promises: [inTransaction] commits the block's writes when it returns and throws them away when
 * it throws; [addMember] has no effect if the subscription is already a member, [removeMember]
 * none if it is not. Writes made outside a transaction are committed at once. [storedGroups] is
 * therefore what a user would find afterwards.
 *
 * The write calls are [insertGroup], [addMember] and [removeMember], counted together in the
 * order they arrive; [subscriptionExists] is a read and not counted.
 */
internal class FakeTable(
    /** The groups the database holds before the save, with all their members. */
    existing: List<StoredGroup> = emptyList(),
    /** `subscriptionExists` answers false for every id. */
    private val subscriptionMissing: Boolean = false,
    /**
     * The ids `insertGroup` hands out, in this order. Neither starting at 1 nor ascending, so
     * that only the returned value can be the right argument for `addMember`.
     */
    private val ids: List<Long> = listOf(41L, 17L, 93L, 58L),
    /** 1 = the first write call throws [failure], 2 = the second, ...; 0 = none. */
    private val failOnWriteCall: Int = 0,
    /** What the failing write call throws. */
    val failure: RuntimeException = RuntimeException("FakeTable: a write fails on purpose")
) : GroupTable {

    /** A call and whether it arrived while an [inTransaction] block was running. */
    data class Recorded(val call: TableCall, val insideTransaction: Boolean)

    /** A group row together with its member subscriptions, in the order they joined. */
    data class StoredGroup(val id: Long, val name: String, val members: List<Long>)

    private val log = mutableListOf<Recorded>()
    private val committed = existing.toMutableList()
    private var uncommitted: MutableList<StoredGroup>? = null
    private var depth = 0
    private var idsHandedOut = 0
    private var writeCalls = 0

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

    /** The committed groups, existing ones first, then in the order inserted: what the database holds now. */
    val storedGroups: List<StoredGroup> get() = committed.toList()

    /** The ids of the committed groups that have [subscriptionId] as a member. */
    fun groupsWithMember(subscriptionId: Long): Set<Long> =
        committed.filter { subscriptionId in it.members }.map { it.id }.toSet()

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
        failIfThisWriteShould()
        if (idsHandedOut == ids.size) {
            throw AssertionError("FakeTable was set up with ${ids.size} ids, one more group was inserted")
        }
        val id = ids[idsHandedOut++]
        rows().add(StoredGroup(id, name, emptyList()))
        return id
    }

    override fun addMember(groupId: Long, subscriptionId: Long) {
        record(TableCall.AddMember(groupId, subscriptionId))
        failIfThisWriteShould()
        val rows = rows()
        val index = rows.indexOfFirst { it.id == groupId }
        if (index < 0) {
            throw AssertionError("FakeTable has no group $groupId: addMember was called with an id it does not know")
        }
        if (subscriptionId !in rows[index].members) {
            rows[index] = rows[index].copy(members = rows[index].members + subscriptionId)
        }
    }

    override fun removeMember(groupId: Long, subscriptionId: Long) {
        record(TableCall.RemoveMember(groupId, subscriptionId))
        failIfThisWriteShould()
        val rows = rows()
        val index = rows.indexOfFirst { it.id == groupId }
        if (index >= 0) {
            rows[index] = rows[index].copy(members = rows[index].members - subscriptionId)
        }
    }

    private fun failIfThisWriteShould() {
        writeCalls++
        if (writeCalls == failOnWriteCall) throw failure
    }

    private fun rows(): MutableList<StoredGroup> = uncommitted ?: committed

    private fun record(call: TableCall) {
        log += Recorded(call, insideTransaction = depth > 0)
    }
}
