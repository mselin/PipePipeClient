package org.schabi.newpipe.enhancements.groupdialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupChangeWriter.apply] (plan section 4): how everything one OK changes is written, and
 * [GroupChanges.isEmpty], which decides whether there is anything to write.
 *
 * The contract under test, in the plan's words: `changes.isEmpty` (all three lists empty):
 * return without touching `table`. Otherwise exactly one `table.inTransaction { ... }` with every
 * other call inside it, in this order: `subscriptionExists(subscriptionId)` once, if false throw
 * `IllegalStateException` before any write; for each new name in order `id = insertGroup(name)`
 * immediately followed by `addMember(id, subscriptionId)`; for each id of `addToGroupIds`
 * `addMember(id, subscriptionId)`; for each id of `removeFromGroupIds`
 * `removeMember(id, subscriptionId)`. Nothing is caught.
 *
 * Test names: `ac<n>_` is an AC of `enhancements/group-dialog/spec.md`; `gci_ac<n>_` is an AC of
 * `enhancements/group-create-inline/spec.md`, which AC-1 of group-dialog keeps.
 *
 * [FakeTable] records the calls and also keeps the rows a database would hold after commit or
 * rollback, other members included, so the tests can state the outcome the spec asks for. That a
 * real transaction rolls back is Room's part and is not tested here.
 */
class GroupChangeWriterTest {

    // ---- GroupChanges.isEmpty ----

    @Test
    fun ac2_changesWithThreeEmptyListsAreEmpty() {
        assertTrue(
            "isEmpty: no new group, no add, no remove",
            GroupChanges(SUBSCRIPTION, emptyList(), emptyList(), emptyList()).isEmpty
        )
    }

    @Test
    fun ac2_changesWithANewGroupAnAddOrARemoveAreNotEmpty() {
        assertFalse("isEmpty with a new group only", changes(names = listOf("Music")).isEmpty)
        assertFalse("isEmpty with an add only", changes(add = listOf(3L)).isEmpty)
        assertFalse("isEmpty with a remove only", changes(remove = listOf(4L)).isEmpty)
        assertFalse(
            "isEmpty with all three",
            changes(names = listOf("Music"), add = listOf(3L), remove = listOf(4L)).isEmpty
        )
    }

    // ---- nothing to write ----

    @Test
    fun ac2_emptyChangesDoNotTouchTheTable() {
        val table = FakeTable(existing = theGroups)

        GroupChangeWriter.apply(table, changes())

        assertEquals("empty changes: no table call", emptyList<TableCall>(), table.calls)
        assertEquals("empty changes: no transaction is opened", 0, table.transactionsStarted)
        assertEquals("empty changes: the groups are as before", theGroups, table.storedGroups)
    }

    // ---- one transaction, the order of the calls: AC-2 ----

    @Test
    fun ac2_theCallsComeInThePlannedOrder() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        assertEquals(
            "subscriptionExists once; insertGroup + addMember per name; the adds; the removes",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.AddMember(41L, SUBSCRIPTION),
                TableCall.InsertGroup("Late Night"),
                TableCall.AddMember(17L, SUBSCRIPTION),
                TableCall.AddMember(3L, SUBSCRIPTION),
                TableCall.AddMember(7L, SUBSCRIPTION),
                TableCall.RemoveMember(4L, SUBSCRIPTION),
                TableCall.RemoveMember(9L, SUBSCRIPTION)
            ),
            table.calls
        )
    }

    @Test
    fun ac2_everythingIsWrittenInOneTransaction() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        assertEquals("AC-2: exactly one inTransaction for new groups, adds and removes", 1, table.transactionsStarted)
        assertEquals(
            "AC-2: no table call outside the transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals("the nine calls were recorded", 9, table.calls.size)
        assertEquals("AC-2: the transaction is committed when nothing fails", 1, table.transactionsCommitted)
        assertEquals("nothing is rolled back", 0, table.transactionsRolledBack)
    }

    /** Checkbox changes alone, the case of the old dialog: also one transaction, and no group is inserted. */
    @Test
    fun ac2_addsAndRemovesWithoutNewGroupsAreOneTransactionToo() {
        val table = FakeTable(existing = theGroups)

        GroupChangeWriter.apply(table, changes(add = listOf(3L, 7L), remove = listOf(4L, 9L)))

        assertEquals(
            "subscriptionExists, the adds, the removes; no insertGroup",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.AddMember(3L, SUBSCRIPTION),
                TableCall.AddMember(7L, SUBSCRIPTION),
                TableCall.RemoveMember(4L, SUBSCRIPTION),
                TableCall.RemoveMember(9L, SUBSCRIPTION)
            ),
            table.calls
        )
        assertEquals("AC-2: one inTransaction", 1, table.transactionsStarted)
        assertEquals(
            "AC-2: no table call outside the transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals("AC-2: committed", 1, table.transactionsCommitted)
    }

    /** A single remove is a save like any other: checked, in a transaction, one call. */
    @Test
    fun ac2_aSingleRemoveIsWrittenInItsTransaction() {
        val table = FakeTable(existing = theGroups)

        GroupChangeWriter.apply(table, changes(remove = listOf(9L)))

        assertEquals(
            "subscriptionExists, then the one removeMember",
            listOf(TableCall.SubscriptionExists(SUBSCRIPTION), TableCall.RemoveMember(9L, SUBSCRIPTION)),
            table.calls
        )
        assertEquals("one inTransaction", 1, table.transactionsStarted)
        assertEquals("no call outside it", emptyList<TableCall>(), table.callsOutsideTransaction)
    }

    // ---- new groups: gci AC-8, AC-9, AC-11 ----

    @Test
    fun gci_ac8_ac9_everyCreatedGroupIsStoredWithTheChannelAsItsOnlyMember() {
        val table = FakeTable(ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, changes(names = listOf("Music", "Late Night")))

        assertEquals(
            "AC-8: every group exists with the channel as member; AC-9: none is created empty",
            listOf(
                FakeTable.StoredGroup(41L, "Music", listOf(SUBSCRIPTION)),
                FakeTable.StoredGroup(17L, "Late Night", listOf(SUBSCRIPTION))
            ),
            table.storedGroups
        )
    }

    /** The usual case of *New group*: one name, no other change. */
    @Test
    fun gci_ac8_oneNameGivesOneGroupWithTheChannelInOneTransaction() {
        val table = FakeTable(ids = listOf(93L))

        GroupChangeWriter.apply(table, changes(names = listOf("Music")))

        assertEquals(
            "AC-8: subscriptionExists, insertGroup, addMember with the id insertGroup returned",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.AddMember(93L, SUBSCRIPTION)
            ),
            table.calls
        )
        assertEquals("one inTransaction, for a single group too", 1, table.transactionsStarted)
        assertEquals(
            "no table call outside the transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals(
            "AC-8: the group is stored with the channel as its only member",
            listOf(FakeTable.StoredGroup(93L, "Music", listOf(SUBSCRIPTION))),
            table.storedGroups
        )
    }

    @Test
    fun gci_ac11_groupsAreInsertedInTheOrderOfTheList() {
        val table = FakeTable()

        GroupChangeWriter.apply(table, changes(names = listOf("Zebra", "apple", "Mango")))

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

    // ---- the outcome: AC-3, AC-7 ----

    /**
     * Before: the channel is in groups 4, 5 and 9. Ticked in the dialog: 3, 5, 7 and the new
     * groups "Music" and "Late Night". So 3 and 7 are added, 4 and 9 removed, 5 and 6 untouched.
     */
    @Test
    fun ac3_afterAMixedSaveTheChannelIsInExactlyTheTickedGroups() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        assertEquals(
            "AC-3: member of the ticked groups 3, 5, 7 and of the two new groups, and of no other",
            setOf(3L, 5L, 7L, 41L, 17L),
            table.groupsWithMember(SUBSCRIPTION)
        )
    }

    /** Ten ticks and ten unticks at once, the case that lost writes before (plan section 1). */
    @Test
    fun ac3_manyToggledGroupsAreAllWritten() {
        val wasIn = (11L..20L).toList()
        val tickedNow = (1L..10L).toList()
        val table = FakeTable(
            existing = tickedNow.map { FakeTable.StoredGroup(it, "group $it", listOf(OTHER_A)) } +
                wasIn.map { FakeTable.StoredGroup(it, "group $it", listOf(SUBSCRIPTION, OTHER_A)) }
        )

        GroupChangeWriter.apply(table, changes(add = tickedNow, remove = wasIn))

        assertEquals(
            "AC-3: the channel is a member of exactly the ten ticked groups",
            tickedNow.toSet(),
            table.groupsWithMember(SUBSCRIPTION)
        )
        assertEquals(
            "AC-3: one addMember per ticked group and one removeMember per unticked group, no other write",
            tickedNow.map { TableCall.AddMember(it, SUBSCRIPTION) } +
                wasIn.map { TableCall.RemoveMember(it, SUBSCRIPTION) },
            table.calls.drop(1)
        )
    }

    /** Plan S1: adds and removes are idempotent, so a stale loaded state cannot produce a wrong row. */
    @Test
    fun ac3_addingWhereAlreadyAMemberAndRemovingWhereNotLeavesNoWrongRow() {
        val table = FakeTable(existing = theGroups)

        // The channel already is in group 5 and is not in group 6.
        GroupChangeWriter.apply(table, changes(add = listOf(5L), remove = listOf(6L)))

        assertEquals(
            "AC-3: group 5 has the channel once, group 6 does not have it; nothing else changed",
            theGroups,
            table.storedGroups
        )
    }

    @Test
    fun ac7_otherMembersOfEveryTouchedGroupAreUnchanged() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        assertEquals(
            "AC-7: the other members of each existing group, in their order, are as before",
            theGroups.associate { it.id to (it.members - SUBSCRIPTION) },
            table.storedGroups.filter { it.id in theGroups.map { g -> g.id } }
                .associate { it.id to (it.members - SUBSCRIPTION) }
        )
    }

    /** AC-7 for the group with the most to lose: removing this channel from a group of 21. */
    @Test
    fun ac7_removingTheChannelLeavesTheTwentyOtherMembersOfTheGroup() {
        val others = (1001L..1020L).toList()
        val big = FakeTable.StoredGroup(9L, "Big", others.take(10) + SUBSCRIPTION + others.drop(10))
        val table = FakeTable(existing = listOf(big))

        GroupChangeWriter.apply(table, changes(remove = listOf(9L)))

        assertEquals(
            "AC-7: only this channel left the group; the other twenty are there in their order",
            listOf(FakeTable.StoredGroup(9L, "Big", others)),
            table.storedGroups
        )
    }

    @Test
    fun ac7_addingTheChannelLeavesTheOtherMembersOfTheGroup() {
        val others = (1001L..1020L).toList()
        val table = FakeTable(existing = listOf(FakeTable.StoredGroup(3L, "Big", others)))

        GroupChangeWriter.apply(table, changes(add = listOf(3L)))

        val after = table.storedGroups.single()
        assertEquals(
            "AC-7: the other twenty members are still there, in their order",
            others,
            after.members - SUBSCRIPTION
        )
        assertEquals("AC-3: and the channel has joined them, once", 21, after.members.size)
    }

    /**
     * The table gets no call that names another subscription: every write is about the row
     * (group, this channel), which is how the plan keeps AC-7 (section 6).
     */
    @Test
    fun ac7_everyWriteNamesOnlyThisChannel() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        val subscriptionsWritten = table.calls.mapNotNull {
            when (it) {
                is TableCall.AddMember -> it.subscriptionId
                is TableCall.RemoveMember -> it.subscriptionId
                else -> null
            }
        }
        assertEquals("AC-7: all six member writes are for this channel", List(6) { SUBSCRIPTION }, subscriptionsWritten)
    }

    /** Groups 5 and 6 were not toggled: no call names them, and their rows are as before. */
    @Test
    fun ac7_groupsThatWereNotToggledGetNoWrite() {
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L))

        GroupChangeWriter.apply(table, mixed)

        val groupsWritten = table.calls.mapNotNull {
            when (it) {
                is TableCall.AddMember -> it.groupId
                is TableCall.RemoveMember -> it.groupId
                else -> null
            }
        }
        assertEquals(
            "AC-3: only the new and the toggled groups are written",
            listOf(41L, 17L, 3L, 7L, 4L, 9L),
            groupsWritten
        )
        assertEquals(
            "AC-7: the untouched groups 5 and 6 are as before",
            theGroups.filter { it.id == 5L || it.id == 6L },
            table.storedGroups.filter { it.id == 5L || it.id == 6L }
        )
    }

    // ---- all or nothing: AC-2, AC-4 ----

    @Test
    fun ac4_missingSubscriptionThrowsIllegalStateBeforeAnyWrite() {
        val table = FakeTable(existing = theGroups, subscriptionMissing = true)

        assertThrows(
            "AC-4: a subscription that does not exist must throw IllegalStateException",
            IllegalStateException::class.java
        ) {
            GroupChangeWriter.apply(table, mixed)
        }

        assertEquals(
            "AC-4: the subscription is checked once; no insert, add or remove after it",
            listOf<TableCall>(TableCall.SubscriptionExists(SUBSCRIPTION)),
            table.calls
        )
        assertEquals("the check runs inside the one transaction", 1, table.transactionsStarted)
        assertEquals(
            "the check runs inside the one transaction",
            emptyList<TableCall>(),
            table.callsOutsideTransaction
        )
        assertEquals("AC-4: nothing is saved", theGroups, table.storedGroups)
    }

    /** Checkbox changes alone are checked too: the check does not depend on new groups. */
    @Test
    fun ac4_missingSubscriptionAlsoStopsASaveWithoutNewGroups() {
        val table = FakeTable(existing = theGroups, subscriptionMissing = true)

        assertThrows(IllegalStateException::class.java) {
            GroupChangeWriter.apply(table, changes(add = listOf(3L), remove = listOf(4L)))
        }

        assertEquals(
            "AC-4: only the check was called",
            listOf<TableCall>(TableCall.SubscriptionExists(SUBSCRIPTION)),
            table.calls
        )
        assertEquals("AC-4: nothing is saved", theGroups, table.storedGroups)
    }

    @Test
    fun ac4_exceptionOfTheTableReachesTheCallerUnchanged() {
        val failure = RuntimeException("disk full")
        val table = FakeTable(existing = theGroups, ids = listOf(41L, 17L), failOnWriteCall = 6, failure = failure)

        val thrown = assertThrows(
            "AC-4: an exception thrown by the table must propagate",
            Throwable::class.java
        ) {
            GroupChangeWriter.apply(table, mixed)
        }

        assertSame(
            "AC-4: nothing is caught: the table's own exception arrives, not a wrapper",
            failure,
            thrown
        )
    }

    /** Write 1 of the mixed save is the first `insertGroup`. */
    @Test
    fun ac2_ac4_failureAtTheFirstInsertSavesNothing() {
        val table = failingAt(writeCall = 1)

        assertThrows(RuntimeException::class.java) { GroupChangeWriter.apply(table, mixed) }

        assertEquals(
            "the work stops at the failing insertGroup",
            listOf(TableCall.SubscriptionExists(SUBSCRIPTION), TableCall.InsertGroup("Music")),
            table.calls
        )
        assertNothingSaved(table)
    }

    /** Write 2 is the `addMember` of the first new group: no group is left behind without the channel. */
    @Test
    fun ac2_ac4_failureAtTheMemberOfANewGroupLeavesNoGroupBehind() {
        val table = failingAt(writeCall = 2)

        assertThrows(RuntimeException::class.java) { GroupChangeWriter.apply(table, mixed) }

        assertEquals(
            "the work stops at the failing addMember",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.AddMember(41L, SUBSCRIPTION)
            ),
            table.calls
        )
        assertNothingSaved(table)
    }

    /** Write 3 is the second `insertGroup`: the first new group, complete by then, goes too. */
    @Test
    fun ac2_ac4_failureAtALaterInsertAlsoDiscardsTheGroupBeforeIt() {
        val table = failingAt(writeCall = 3)

        assertThrows(RuntimeException::class.java) { GroupChangeWriter.apply(table, mixed) }

        assertEquals(
            "the work stops at the failing second insertGroup",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.AddMember(41L, SUBSCRIPTION),
                TableCall.InsertGroup("Late Night")
            ),
            table.calls
        )
        assertNothingSaved(table)
    }

    /** Write 6 is the second add to an existing group: the new groups and the first add go too. */
    @Test
    fun ac2_ac4_failureAtAnAddDiscardsTheNewGroupsAndTheEarlierAdd() {
        val table = failingAt(writeCall = 6)

        assertThrows(RuntimeException::class.java) { GroupChangeWriter.apply(table, mixed) }

        assertEquals(
            "the work stops at the failing addMember(7); no remove is tried",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.InsertGroup("Music"),
                TableCall.AddMember(41L, SUBSCRIPTION),
                TableCall.InsertGroup("Late Night"),
                TableCall.AddMember(17L, SUBSCRIPTION),
                TableCall.AddMember(3L, SUBSCRIPTION),
                TableCall.AddMember(7L, SUBSCRIPTION)
            ),
            table.calls
        )
        assertNothingSaved(table)
    }

    /** Write 8 is the last call of all, the second remove: everything before it goes too. */
    @Test
    fun ac2_ac4_failureAtTheLastRemoveDiscardsEverythingBeforeIt() {
        val table = failingAt(writeCall = 8)

        assertThrows(RuntimeException::class.java) { GroupChangeWriter.apply(table, mixed) }

        assertEquals("all nine calls were made, the last one failed", 9, table.calls.size)
        assertEquals(
            "the failing call is removeMember(9)",
            TableCall.RemoveMember(9L, SUBSCRIPTION),
            table.calls.last()
        )
        assertNothingSaved(table)
    }

    /** Checkbox changes alone, two toggles: the failure of the second takes the first back. */
    @Test
    fun ac2_ac4_failureAtARemoveOfASaveWithoutNewGroupsSavesNothing() {
        val table = FakeTable(existing = theGroups, failOnWriteCall = 2)

        assertThrows(RuntimeException::class.java) {
            GroupChangeWriter.apply(table, changes(add = listOf(3L), remove = listOf(4L)))
        }

        assertEquals(
            "the add was made, the remove failed",
            listOf(
                TableCall.SubscriptionExists(SUBSCRIPTION),
                TableCall.AddMember(3L, SUBSCRIPTION),
                TableCall.RemoveMember(4L, SUBSCRIPTION)
            ),
            table.calls
        )
        assertNothingSaved(table)
    }

    // ---- helpers ----

    /** A table holding [theGroups] whose [writeCall]-th write of the [mixed] save throws. */
    private fun failingAt(writeCall: Int) =
        FakeTable(existing = theGroups, ids = listOf(41L, 17L), failOnWriteCall = writeCall)

    /** AC-2 / AC-4: the one transaction was rolled back and the stored groups are as before the save. */
    private fun assertNothingSaved(table: FakeTable) {
        assertEquals("AC-2: everything is written in one transaction", 1, table.transactionsStarted)
        assertEquals(
            "AC-4: the exception leaves the inTransaction block, so the transaction rolls back",
            1,
            table.transactionsRolledBack
        )
        assertEquals("AC-4: nothing is committed", 0, table.transactionsCommitted)
        assertEquals(
            "AC-4: nothing is saved: no new group, every member list as before",
            theGroups,
            table.storedGroups
        )
    }

    private companion object {
        /** Not 1 and beyond the Int range, so that no other number can pass for it. */
        const val SUBSCRIPTION = 5_000_000_042L

        /** Other channels, members of the groups before the save. */
        const val OTHER_A = 100L
        const val OTHER_B = 200L
        const val OTHER_C = 300L

        /**
         * The database before the save. The channel is in groups 4, 5 and 9, at different
         * positions of the member lists; every group but 7 has other members.
         */
        val theGroups = listOf(
            FakeTable.StoredGroup(3L, "Three", listOf(OTHER_A, OTHER_B)),
            FakeTable.StoredGroup(4L, "Four", listOf(SUBSCRIPTION, OTHER_A)),
            FakeTable.StoredGroup(5L, "Five", listOf(OTHER_C, SUBSCRIPTION)),
            FakeTable.StoredGroup(6L, "Six", listOf(OTHER_B)),
            FakeTable.StoredGroup(7L, "Seven", emptyList()),
            FakeTable.StoredGroup(9L, "Nine", listOf(OTHER_C, SUBSCRIPTION, OTHER_A, OTHER_B))
        )

        /** Two new groups, the channel added to 3 and 7 and removed from 4 and 9: eight writes. */
        val mixed = GroupChanges(
            subscriptionId = SUBSCRIPTION,
            newGroupNames = listOf("Music", "Late Night"),
            addToGroupIds = listOf(3L, 7L),
            removeFromGroupIds = listOf(4L, 9L)
        )

        /** Changes for [SUBSCRIPTION]; whatever is not named is empty. */
        fun changes(
            names: List<String> = emptyList(),
            add: List<Long> = emptyList(),
            remove: List<Long> = emptyList()
        ) = GroupChanges(SUBSCRIPTION, names, add, remove)
    }
}
