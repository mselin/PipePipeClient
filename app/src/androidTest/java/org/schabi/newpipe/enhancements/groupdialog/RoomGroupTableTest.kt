package org.schabi.newpipe.enhancements.groupdialog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon

/**
 * [RoomGroupTable] with [GroupChangeWriter.apply] on a real, in-memory [AppDatabase] (plan
 * sections 6 and 7): what the fake of the JVM tests can only imitate, the transaction of Room
 * and the foreign keys of the join table.
 *
 * Instrumented: needs a device or emulator. Not run by CI; it is device check 7 of the plan.
 * It uses only what `androidTest` already depends on.
 *
 * Test names: `ac<n>_` is an AC of `enhancements/group-dialog/spec.md`; `gci_ac<n>_` is an AC of
 * `enhancements/group-create-inline/spec.md`, which AC-1 of group-dialog keeps.
 */
@RunWith(AndroidJUnit4::class)
class RoomGroupTableTest {

    private lateinit var database: AppDatabase
    private lateinit var table: RoomGroupTable

    /** The channel of the dialog and three other channels. */
    private var channel = 0L
    private var otherA = 0L
    private var otherB = 0L
    private var otherC = 0L

    /** "Was in": the channel and two others. "Not in": two others. "Untouched": the channel and one other. */
    private var wasIn = 0L
    private var notIn = 0L
    private var untouched = 0L

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        table = RoomGroupTable(database)

        channel = subscription("channel")
        otherA = subscription("a")
        otherB = subscription("b")
        otherC = subscription("c")
        wasIn = group("Was in", otherA, channel, otherB)
        notIn = group("Not in", otherC, otherA)
        untouched = group("Untouched", channel, otherC)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun ac2_ac3_aMixedSaveCommitsAllOfIt() {
        val before = groups()

        GroupChangeWriter.apply(
            table,
            GroupChanges(channel, listOf("Music", "Late Night"), listOf(notIn), listOf(wasIn))
        )

        val created = groups().filterNot { it in before }
        assertEquals(
            "AC-2: the two new groups exist, in the order added",
            listOf("Music", "Late Night"),
            created.map { it.name }
        )
        assertEquals(
            "AC-3: the channel is a member of exactly the ticked groups",
            setOf(notIn, untouched) + created.map { it.uid },
            groups().map { it.uid }.filter { channel in members(it) }.toSet()
        )
        for (group in created) {
            assertEquals(
                "gci AC-8: \"${group.name}\" has the channel as its only member",
                setOf(channel),
                members(group.uid)
            )
        }
    }

    /** gci AC-11: default icon, last in the manual order, several in the order added. */
    @Test
    fun gci_ac11_createdGroupsGetTheDefaultIconAndComeLastInTheOrderAdded() {
        GroupChangeWriter.apply(table, GroupChanges(channel, listOf("Zebra", "apple"), emptyList(), emptyList()))

        val ordered = groups()
        assertEquals(
            "AC-11: the existing groups keep their places, the new ones follow in the order added",
            listOf("Was in", "Not in", "Untouched", "Zebra", "apple"),
            ordered.map { it.name }
        )
        assertEquals(
            "AC-11: both new groups have the default icon of the existing group dialog",
            listOf(FeedGroupIcon.ALL, FeedGroupIcon.ALL),
            ordered.takeLast(2).map { it.icon }
        )
    }

    @Test
    fun ac2_ac4_aSaveThatThrowsAfterWritesLeavesTheTablesAsBefore() {
        val groupsBefore = groups()
        val membersBefore = allMembers()
        val failure = RuntimeException("fails on purpose")
        // Writes of this save: insertGroup, addMember, addMember(notIn), removeMember(wasIn).
        val failing = FailingOnWrite(table, failOnWriteCall = 4, failure = failure)

        val thrown = assertThrows(RuntimeException::class.java) {
            GroupChangeWriter.apply(failing, GroupChanges(channel, listOf("Music"), listOf(notIn), listOf(wasIn)))
        }

        assertEquals("the failure of the table reaches the caller", failure, thrown)
        assertEquals("AC-4: no group is left behind", groupsBefore, groups())
        assertEquals("AC-4: every member list is as before", membersBefore, allMembers())
    }

    @Test
    fun ac7_removingTheChannelLeavesTheOtherMembersOfTheGroup() {
        GroupChangeWriter.apply(table, GroupChanges(channel, emptyList(), emptyList(), listOf(wasIn)))

        assertEquals("AC-7: only the channel left the group", setOf(otherA, otherB), members(wasIn))
        assertEquals("AC-7: the other groups are as before", setOf(otherC, otherA), members(notIn))
        assertEquals("AC-7: the other groups are as before", setOf(channel, otherC), members(untouched))
    }

    @Test
    fun ac7_addingTheChannelLeavesTheOtherMembersOfTheGroup() {
        GroupChangeWriter.apply(table, GroupChanges(channel, emptyList(), listOf(notIn), emptyList()))

        assertEquals("AC-7: the channel joined, the others stayed", setOf(otherC, otherA, channel), members(notIn))
        assertEquals("AC-7: the other groups are as before", setOf(otherA, channel, otherB), members(wasIn))
    }

    /** Plan S1: a stale loaded state cannot produce a wrong row or a failure. */
    @Test
    fun ac3_addingWhereAlreadyAMemberAndRemovingWhereNotChangesNothing() {
        val membersBefore = allMembers()

        GroupChangeWriter.apply(table, GroupChanges(channel, emptyList(), listOf(wasIn), listOf(notIn)))

        assertEquals("AC-3: the member lists are as before", membersBefore, allMembers())
    }

    /** Plan S4: a group deleted elsewhere and then ticked here fails the whole save, at commit. */
    @Test
    fun ac4_addingToAGroupThatDoesNotExistFailsAndSavesNothing() {
        val groupsBefore = groups()
        val membersBefore = allMembers()
        val missingGroup = groupsBefore.maxOf { it.uid } + 1000

        assertThrows(Exception::class.java) {
            GroupChangeWriter.apply(
                table,
                GroupChanges(channel, listOf("Music"), listOf(notIn, missingGroup), listOf(wasIn))
            )
        }

        assertEquals("AC-4: no group is left behind", groupsBefore, groups())
        assertEquals("AC-4: every member list is as before", membersBefore, allMembers())
    }

    @Test
    fun ac4_aSubscriptionThatDoesNotExistFailsAndSavesNothing() {
        val groupsBefore = groups()
        val membersBefore = allMembers()

        assertThrows(IllegalStateException::class.java) {
            GroupChangeWriter.apply(table, GroupChanges(channel + 1000, listOf("Music"), listOf(notIn), emptyList()))
        }

        assertEquals("AC-4: no group is left behind", groupsBefore, groups())
        assertEquals("AC-4: every member list is as before", membersBefore, allMembers())
    }

    // ---- helpers ----

    private fun subscription(name: String): Long {
        val entity = SubscriptionEntity()
        entity.serviceId = 0
        entity.url = "https://example.invalid/$name"
        entity.name = name
        return database.subscriptionDAO().insert(entity)
    }

    private fun group(name: String, vararg memberIds: Long): Long {
        val id = database.feedGroupDAO().insert(FeedGroupEntity(0, name, FeedGroupIcon.ALL))
        database.feedGroupDAO().insertSubscriptionsToGroup(memberIds.map { FeedGroupSubscriptionEntity(id, it) })
        return id
    }

    /** All groups in the manual order. */
    private fun groups(): List<FeedGroupEntity> = database.feedGroupDAO().getAll().blockingFirst()

    private fun members(groupId: Long): Set<Long> =
        database.feedGroupDAO().getSubscriptionIdsFor(groupId).blockingFirst().toSet()

    private fun allMembers(): Map<Long, Set<Long>> = groups().associate { it.uid to members(it.uid) }

    /** [delegate], except that the [failOnWriteCall]-th insert, add or remove throws [failure] instead. */
    private class FailingOnWrite(
        private val delegate: GroupTable,
        private val failOnWriteCall: Int,
        private val failure: RuntimeException
    ) : GroupTable {
        private var writeCalls = 0

        override fun inTransaction(block: () -> Unit) = delegate.inTransaction(block)

        override fun subscriptionExists(subscriptionId: Long): Boolean = delegate.subscriptionExists(subscriptionId)

        override fun insertGroup(name: String): Long {
            failIfThisWriteShould()
            return delegate.insertGroup(name)
        }

        override fun addMember(groupId: Long, subscriptionId: Long) {
            failIfThisWriteShould()
            delegate.addMember(groupId, subscriptionId)
        }

        override fun removeMember(groupId: Long, subscriptionId: Long) {
            failIfThisWriteShould()
            delegate.removeMember(groupId, subscriptionId)
        }

        private fun failIfThisWriteShould() {
            writeCalls++
            if (writeCalls == failOnWriteCall) throw failure
        }
    }
}
