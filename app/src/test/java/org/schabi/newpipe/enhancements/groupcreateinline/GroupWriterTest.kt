package org.schabi.newpipe.enhancements.groupcreateinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [GroupWriter.createGroupsWithMember] (plan section 4): how the checked pending groups are
 * written.
 *
 * The contract under test, in the plan's words: `names` empty: return without touching `table`.
 * Otherwise exactly one `table.inTransaction { ... }`, with every other table call inside it, in
 * this order: `subscriptionExists(subscriptionId)` once, and if false throw
 * `IllegalStateException` before any insert; then for each name in list order
 * `id = insertGroup(name)` immediately followed by `setMembers(id, listOf(subscriptionId))`.
 * Nothing is caught: a table exception propagates.
 *
 * [FakeTable] records the calls and also keeps the rows a database would hold after commit or
 * rollback, so the tests can state the outcome the spec asks for. That a real transaction rolls
 * back is Room's part and is not tested here.
 */
class GroupWriterTest {

    // ---- AC-8: every created group gets the channel ----

    @Test
    fun ac8_eachInsertGroupIsImmediatelyFollowedBySetMembersWithTheChannel() {
        val table = FakeTable(ids = listOf(41L, 17L))

        GroupWriter.createGroupsWithMember(table, listOf("Music", "Late Night"), SUBSCRIPTION)

        assertEquals(
            "AC-8: subscriptionExists once, then insertGroup and setMembers(id, [subscription]) per name",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.SetMembers(41L, listOf(SUBSCRIPTION)),
                TableCall.InsertGroup("Late Night"),
                TableCall.SetMembers(17L, listOf(SUBSCRIPTION))
            ),
            table.calls
        )
    }

    @Test
    fun ac8_ac9_everyCreatedGroupIsStoredWithTheChannelAsItsOnlyMember() {
        val table = FakeTable(ids = listOf(41L, 17L))

        GroupWriter.createGroupsWithMember(table, listOf("Music", "Late Night"), SUBSCRIPTION)

        assertEquals(
            "AC-8: every group exists with the channel as member; AC-9: none is created empty",
            listOf(
                FakeTable.StoredGroup(41L, "Music", listOf(SUBSCRIPTION)),
                FakeTable.StoredGroup(17L, "Late Night", listOf(SUBSCRIPTION))
            ),
            table.storedGroups
        )
    }

    /** The usual case, one new group: the same calls, and also inside one transaction. */
    @Test
    fun ac8_ac12_oneNameGivesOneGroupWithTheChannelInOneTransaction() {
        val table = FakeTable(ids = listOf(93L))

        GroupWriter.createGroupsWithMember(table, listOf("Music"), SUBSCRIPTION)

        assertEquals(
            "AC-8: subscriptionExists, insertGroup, setMembers with the id insertGroup returned",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.SetMembers(93L, listOf(SUBSCRIPTION))
            ),
            table.calls
        )
        assertEquals("AC-12: one inTransaction, for a single group too", 1, table.transactionsStarted)
        assertEquals(
            "AC-12: no table call outside the transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals(
            "AC-8: the group is stored with the channel as its only member",
            listOf(FakeTable.StoredGroup(93L, "Music", listOf(SUBSCRIPTION))),
            table.storedGroups
        )
    }

    // ---- AC-9: nothing to create ----

    @Test
    fun ac9_noNamesMeansTheTableIsNotTouched() {
        val table = FakeTable()

        GroupWriter.createGroupsWithMember(table, emptyList(), SUBSCRIPTION)

        assertEquals("AC-9: no names: no table call", emptyList<TableCall>(), table.calls)
        assertEquals("AC-9: no names: no transaction is opened", 0, table.transactionsStarted)
        assertEquals("AC-9: no names: no group", emptyList<FakeTable.StoredGroup>(), table.storedGroups)
    }

    // ---- AC-11: order ----

    @Test
    fun ac11_groupsAreInsertedInTheOrderOfTheList() {
        val table = FakeTable()

        GroupWriter.createGroupsWithMember(table, listOf("Zebra", "apple", "Mango"), SUBSCRIPTION)

        assertEquals(
            "AC-11: several groups are inserted in the order in which they were added (not sorted)",
            listOf("Zebra", "apple", "Mango"),
            table.calls.filterIsInstance<TableCall.InsertGroup>().map { it.name }
        )
        assertEquals(
            "AC-11: and stored in that order",
            listOf("Zebra", "apple", "Mango"),
            table.storedGroups.map { it.name }
        )
    }

    // ---- AC-12: all or nothing ----

    @Test
    fun ac12_everyTableCallIsInsideTheOneTransaction() {
        val table = FakeTable()

        GroupWriter.createGroupsWithMember(table, listOf("Music", "News", "Podcasts"), SUBSCRIPTION)

        assertEquals("AC-12: exactly one inTransaction for all groups", 1, table.transactionsStarted)
        assertEquals(
            "AC-12: no table call outside the transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals("the seven calls were recorded", 7, table.calls.size)
        assertEquals("AC-12: the transaction is committed when nothing fails", 1, table.transactionsCommitted)
    }

    @Test
    fun ac12_missingSubscriptionThrowsIllegalStateBeforeAnyInsert() {
        val table = FakeTable(subscriptionMissing = true)

        assertThrows(
            "AC-12: a subscription that does not exist must throw IllegalStateException",
            IllegalStateException::class.java
        ) {
            GroupWriter.createGroupsWithMember(table, listOf("Music", "News"), SUBSCRIPTION)
        }

        assertEquals(
            "AC-12: the subscription is checked once; no insertGroup, no setMembers after it",
            listOf<TableCall>(TableCall.SubscriptionExists(SUBSCRIPTION)),
            table.calls
        )
        assertEquals("AC-12: the check runs inside the one transaction", 1, table.transactionsStarted)
        assertEquals(
            "AC-12: the check runs inside the one transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals("AC-12: no group is left behind", emptyList<FakeTable.StoredGroup>(), table.storedGroups)
    }

    @Test
    fun ac12_exceptionOfSetMembersReachesTheCallerUnchanged() {
        val failure = RuntimeException("disk full")
        val table = FakeTable(failOnSetMembersCall = 1, failure = failure)

        val thrown = assertThrows(
            "AC-12: an exception thrown by the table must propagate",
            Throwable::class.java
        ) {
            GroupWriter.createGroupsWithMember(table, listOf("Music", "News"), SUBSCRIPTION)
        }

        assertSame(
            "AC-12: nothing is caught: the table's own exception arrives, not a wrapper",
            failure,
            thrown
        )
    }

    @Test
    fun ac12_failingSetMembersStopsTheWorkAndLeavesNoGroupBehind() {
        val table = FakeTable(ids = listOf(41L, 17L), failOnSetMembersCall = 1)

        assertThrows(RuntimeException::class.java) {
            GroupWriter.createGroupsWithMember(table, listOf("Music", "News"), SUBSCRIPTION)
        }

        assertEquals(
            "AC-12: after the failing setMembers no further group is inserted",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.SetMembers(41L, listOf(SUBSCRIPTION))
            ),
            table.calls
        )
        assertEquals(
            "AC-12: the exception leaves the inTransaction block, so the transaction rolls back",
            1,
            table.transactionsRolledBack
        )
        assertEquals("AC-12: nothing is committed", 0, table.transactionsCommitted)
        assertEquals(
            "AC-12: no group is left behind without the channel",
            emptyList<FakeTable.StoredGroup>(),
            table.storedGroups
        )
    }

    @Test
    fun ac12_failureAtALaterGroupAlsoDiscardsTheGroupsBeforeIt() {
        val table = FakeTable(ids = listOf(41L, 17L, 93L), failOnSetMembersCall = 2)

        assertThrows(RuntimeException::class.java) {
            GroupWriter.createGroupsWithMember(table, listOf("Music", "News", "Podcasts"), SUBSCRIPTION)
        }

        assertEquals(
            "AC-12: the work stops at the failing setMembers of the second group",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.SetMembers(41L, listOf(SUBSCRIPTION)),
                TableCall.InsertGroup("News"),
                TableCall.SetMembers(17L, listOf(SUBSCRIPTION))
            ),
            table.calls
        )
        assertEquals("AC-12: all groups are written in one transaction", 1, table.transactionsStarted)
        assertEquals(
            "AC-12: nothing at all is saved, the first group is not kept either",
            emptyList<FakeTable.StoredGroup>(),
            table.storedGroups
        )
    }

    private companion object {
        /** Not 1 and beyond the Int range, so that no other number can pass for it. */
        const val SUBSCRIPTION = 5_000_000_042L
    }
}
