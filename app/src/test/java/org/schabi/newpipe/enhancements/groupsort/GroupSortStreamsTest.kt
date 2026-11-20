package org.schabi.newpipe.enhancements.groupsort

import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.subscribers.TestSubscriber
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.groupsOf

/**
 * `GroupSortStreams.sorted` (plan section 4): the stream every group list reads. It combines
 * the group lists of the database (manual order) with the current value of the setting.
 *
 * The two sources are `PublishProcessor`s, so the test decides when a group list arrives and
 * when the setting changes. Plan section 4 says "no scheduler": an emission is there as soon as
 * the source item is pushed, which is why the assertions follow `onNext` directly.
 */
class GroupSortStreamsTest {

    /** One group list on screen: its two sources and everything it was given to show. */
    private class GroupList {
        /** Stands for the database: each item is the whole group list in manual order. */
        val groups: PublishProcessor<List<TestGroup>> = PublishProcessor.create()

        /** Stands for the setting: the value on subscription and every reported change. */
        val modes: PublishProcessor<GroupSortMode> = PublishProcessor.create()

        val shown: TestSubscriber<List<TestGroup>> = GroupSortStreams.sorted(groups, modes) { it.name }.test()

        /** Every list emitted so far, as names. Also fails on an error or a completion. */
        fun shownNames(): List<List<String>> {
            shown.assertNoErrors()
            shown.assertNotComplete()
            return shown.values().map { list -> list.map { it.name } }
        }
    }

    private val f1Manual: List<TestGroup> = groupsOf(GroupSortFixtures.F1_MANUAL)

    /** Fixture F1 after a group "News" was created: a new group goes last in the manual order. */
    private val f1WithNewsManual: List<TestGroup> = f1Manual + TestGroup(7, "News")

    private val f1WithNewsAToZ = listOf("001 - First", "Mix 10", "Mix 2", "music", "News", "ZZZ - Last", "ZZZZ - Last of last")
    private val f1WithNewsZToA = listOf("ZZZZ - Last of last", "ZZZ - Last", "News", "music", "Mix 2", "Mix 10", "001 - First")

    /** Fixture F1 after "Mix 2" (id 3) was renamed to "A-side": a rename keeps the manual position. */
    private val f1RenamedManual: List<TestGroup> = f1Manual.map { if (it.id == 3L) it.copy(name = "A-side") else it }

    private val f1RenamedAToZ = listOf("001 - First", "A-side", "Mix 10", "music", "ZZZ - Last", "ZZZZ - Last of last")
    private val f1RenamedZToA = listOf("ZZZZ - Last of last", "ZZZ - Last", "music", "Mix 10", "A-side", "001 - First")

    // ---------------------------------------------------------------------------------------
    // AC-2 Manual
    // ---------------------------------------------------------------------------------------

    /**
     * AC-2: with *Manual* every group list is exactly as without the feature: each list of the
     * source comes out once, unchanged and in the order of the source. Plan section 4: "one
     * emission per `groups` item", so also when the database delivers an equal list twice in a
     * row (it emits after every write to the table).
     */
    @Test
    fun ac2_manualEmitsEverySourceListUnchanged() {
        val f2Manual = groupsOf(GroupSortFixtures.F2_MANUAL)
        val f1Again = groupsOf(GroupSortFixtures.F1_MANUAL)
        val list = GroupList()

        list.modes.onNext(GroupSortMode.MANUAL)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1Again)
        list.groups.onNext(f2Manual)
        list.groups.onNext(f1Manual)

        list.shown.assertNoErrors()
        assertEquals(
            "AC-2: with MANUAL the stream must emit the source lists one for one and unchanged",
            listOf(f1Manual, f1Again, f2Manual, f1Manual),
            list.shown.values()
        )
    }

    /** AC-2: the same when the group list is there before the setting is known. */
    @Test
    fun ac2_manualEmitsTheWaitingSourceListUnchanged() {
        val list = GroupList()

        list.groups.onNext(f1Manual)
        list.modes.onNext(GroupSortMode.MANUAL)

        assertEquals(
            "AC-2: with MANUAL the list that was waiting for the mode must come out in manual order",
            listOf(GroupSortFixtures.F1_MANUAL),
            list.shownNames()
        )
    }

    /**
     * AC-2: a failure to load the groups reaches the list as it did without the feature. Plan
     * section 4: "errors pass through". The lists emitted before the error stay as they were.
     */
    @Test
    fun ac2_manualPassesAnErrorOfTheSourceThrough() {
        val failure = IllegalStateException("database closed")
        val list = GroupList()

        list.modes.onNext(GroupSortMode.MANUAL)
        list.groups.onNext(f1Manual)
        list.groups.onError(failure)

        list.shown.assertError(failure)
        assertEquals("AC-2: the list emitted before the error", listOf(f1Manual), list.shown.values())
    }

    // ---------------------------------------------------------------------------------------
    // AC-3, AC-4, AC-6 the name orders arrive through the stream
    // ---------------------------------------------------------------------------------------

    /**
     * AC-3 and AC-4 with the ties of AC-6, fixture F2 through the stream, the groups told apart
     * by id (b=1, Music=2, a=3, music=4, " MUSIC "=5): A-Z is 3, 1, 2, 4, 5 and Z-A is
     * 2, 4, 5, 1, 3.
     */
    @Test
    fun ac3_ac4_ac6_streamEmitsFixtureF2InBothNameOrders() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(groupsOf(GroupSortFixtures.F2_MANUAL))
        list.modes.onNext(GroupSortMode.NAME_DESC)

        list.shown.assertNoErrors()
        assertEquals(
            "AC-3/AC-4/AC-6 fixture F2: ids emitted for Name (A-Z), then for Name (Z-A)",
            listOf(listOf(3L, 1L, 2L, 4L, 5L), listOf(2L, 4L, 5L, 1L, 3L)),
            list.shown.values().map { emitted -> emitted.map { it.id } }
        )
        assertEquals(
            "AC-3/AC-4/AC-6 fixture F2: names emitted for Name (A-Z), then for Name (Z-A)",
            listOf(GroupSortFixtures.F2_A_TO_Z, GroupSortFixtures.F2_Z_TO_A),
            list.shownNames()
        )
    }

    // ---------------------------------------------------------------------------------------
    // AC-8 the order follows changes
    // ---------------------------------------------------------------------------------------

    /** AC-8: nothing is emitted while only the group list is known; it comes sorted once the mode is known. */
    @Test
    fun ac8_nothingIsEmittedBeforeTheModeIsKnown() {
        val list = GroupList()

        list.groups.onNext(f1Manual)
        assertEquals("AC-8: no emission before the mode has been emitted", emptyList<List<String>>(), list.shownNames())

        list.modes.onNext(GroupSortMode.NAME_ASC)
        assertEquals("AC-8: one emission, sorted, once the mode is known", listOf(GroupSortFixtures.F1_A_TO_Z), list.shownNames())
    }

    /** AC-8: nothing is emitted while only the mode is known; the first group list comes out sorted. */
    @Test
    fun ac8_nothingIsEmittedBeforeTheGroupsAreKnown() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_DESC)
        assertEquals("AC-8: no emission before a group list has been emitted", emptyList<List<String>>(), list.shownNames())

        list.groups.onNext(f1Manual)
        assertEquals("AC-8: one emission, sorted, once the groups are known", listOf(GroupSortFixtures.F1_Z_TO_A), list.shownNames())
    }

    /**
     * AC-8, "creating ... a group puts it at its sorted position": with *Name (A-Z)* the list
     * the database emits after "News" was created (last in manual order) comes out with "News"
     * between "music" and "ZZZ - Last". One emission per group list.
     */
    @Test
    fun ac8_createdGroupIsEmittedAtItsSortedPositionInAToZ() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1WithNewsManual)

        assertEquals(
            "AC-8: Name (A-Z), emissions before and after the group was created",
            listOf(GroupSortFixtures.F1_A_TO_Z, f1WithNewsAToZ),
            list.shownNames()
        )
    }

    /** AC-8, a created group with *Name (Z-A)*: "News" comes out between "ZZZ - Last" and "music". */
    @Test
    fun ac8_createdGroupIsEmittedAtItsSortedPositionInZToA() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1WithNewsManual)

        assertEquals(
            "AC-8: Name (Z-A), emissions before and after the group was created",
            listOf(GroupSortFixtures.F1_Z_TO_A, f1WithNewsZToA),
            list.shownNames()
        )
    }

    /**
     * AC-8, "renaming a group puts it at its sorted position": "Mix 2" becomes "A-side" and
     * keeps its manual position; with *Name (A-Z)* it moves up to second place.
     */
    @Test
    fun ac8_renamedGroupIsEmittedAtItsSortedPositionInAToZ() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1RenamedManual)

        assertEquals(
            "AC-8: Name (A-Z), emissions before and after the rename",
            listOf(GroupSortFixtures.F1_A_TO_Z, f1RenamedAToZ),
            list.shownNames()
        )
    }

    /** AC-8, a renamed group with *Name (Z-A)*: "A-side" moves down to the place before "001 - First". */
    @Test
    fun ac8_renamedGroupIsEmittedAtItsSortedPositionInZToA() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1RenamedManual)

        assertEquals(
            "AC-8: Name (Z-A), emissions before and after the rename",
            listOf(GroupSortFixtures.F1_Z_TO_A, f1RenamedZToA),
            list.shownNames()
        )
    }

    /**
     * AC-8, "changing the setting reorders the lists": each change of the mode re-emits the last
     * group list in the new order, without the database emitting again.
     */
    @Test
    fun ac8_modeChangeReEmitsTheLastListInTheNewOrderWithoutANewGroupList() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.MANUAL)
        list.groups.onNext(f1Manual)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.modes.onNext(GroupSortMode.MANUAL)

        assertEquals(
            "AC-8: one group list, then the mode going MANUAL -> NAME_ASC -> NAME_DESC -> MANUAL",
            listOf(
                GroupSortFixtures.F1_MANUAL,
                GroupSortFixtures.F1_A_TO_Z,
                GroupSortFixtures.F1_Z_TO_A,
                GroupSortFixtures.F1_MANUAL
            ),
            list.shownNames()
        )
    }

    /** AC-8: a mode change sorts the latest group list, not an earlier one. */
    @Test
    fun ac8_modeChangeUsesTheLatestGroupList() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1Manual)
        list.groups.onNext(f1WithNewsManual)
        list.modes.onNext(GroupSortMode.NAME_DESC)

        assertEquals(
            "AC-8: two group lists with NAME_ASC, then the change to NAME_DESC",
            listOf(GroupSortFixtures.F1_A_TO_Z, f1WithNewsAToZ, f1WithNewsZToA),
            list.shownNames()
        )
    }

    /** AC-8: a new group list is sorted by the latest mode, not by an earlier one. */
    @Test
    fun ac8_newGroupListUsesTheLatestMode() {
        val list = GroupList()

        list.groups.onNext(f1Manual)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.groups.onNext(f1RenamedManual)
        list.modes.onNext(GroupSortMode.MANUAL)
        list.groups.onNext(f1WithNewsManual)

        assertEquals(
            "AC-8: every emission is the latest group list in the order of the latest mode",
            listOf(
                GroupSortFixtures.F1_A_TO_Z,
                GroupSortFixtures.F1_Z_TO_A,
                f1RenamedZToA,
                f1RenamedManual.map { it.name },
                f1WithNewsManual.map { it.name }
            ),
            list.shownNames()
        )
    }

    /**
     * AC-8: the setting reporting the mode it already has (plan section 4:
     * `modes.distinctUntilChanged()`) emits nothing; only a changed mode re-emits the list.
     */
    @Test
    fun ac8_repeatedModeEmitsNothing() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1Manual)
        assertEquals("AC-8: NAME_ASC twice, then the groups: one emission", listOf(GroupSortFixtures.F1_A_TO_Z), list.shownNames())

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        assertEquals("AC-8: NAME_ASC repeated: no further emission", listOf(GroupSortFixtures.F1_A_TO_Z), list.shownNames())

        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.modes.onNext(GroupSortMode.NAME_DESC)
        assertEquals(
            "AC-8: NAME_DESC twice: exactly one further emission",
            listOf(GroupSortFixtures.F1_A_TO_Z, GroupSortFixtures.F1_Z_TO_A),
            list.shownNames()
        )

        list.modes.onNext(GroupSortMode.MANUAL)
        list.modes.onNext(GroupSortMode.MANUAL)
        assertEquals(
            "AC-8: MANUAL twice: exactly one further emission",
            listOf(GroupSortFixtures.F1_A_TO_Z, GroupSortFixtures.F1_Z_TO_A, GroupSortFixtures.F1_MANUAL),
            list.shownNames()
        )
    }

    /** AC-8: after a repeated mode the next group list is still emitted, once and sorted. */
    @Test
    fun ac8_groupListAfterARepeatedModeIsStillEmittedOnce() {
        val list = GroupList()

        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1Manual)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.groups.onNext(f1WithNewsManual)

        assertEquals(
            "AC-8: group list, repeated NAME_ASC, group list: two emissions",
            listOf(GroupSortFixtures.F1_A_TO_Z, f1WithNewsAToZ),
            list.shownNames()
        )
    }

    // ---------------------------------------------------------------------------------------
    // AC-9 the manual order survives
    // ---------------------------------------------------------------------------------------

    /**
     * AC-9: "Switching back to *Manual* shows the order that was there before." After a name
     * order, *Manual* re-emits the list in the manual order of the source, from either
     * direction, and the list of the source is still as it was delivered.
     */
    @Test
    fun ac9_nameOrderThenManualEmitsTheManualOrderAgain() {
        val source = ArrayList(f1Manual)
        val list = GroupList()

        list.groups.onNext(source)
        list.modes.onNext(GroupSortMode.NAME_ASC)
        list.modes.onNext(GroupSortMode.MANUAL)
        list.modes.onNext(GroupSortMode.NAME_DESC)
        list.modes.onNext(GroupSortMode.MANUAL)

        assertEquals(
            "AC-9: NAME_ASC -> MANUAL -> NAME_DESC -> MANUAL on one group list",
            listOf(
                GroupSortFixtures.F1_A_TO_Z,
                GroupSortFixtures.F1_MANUAL,
                GroupSortFixtures.F1_Z_TO_A,
                GroupSortFixtures.F1_MANUAL
            ),
            list.shownNames()
        )
        assertEquals(
            "AC-9: ids (manual positions) of the last emission, after switching back to MANUAL",
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            list.shown.values().last().map { it.id }
        )
        assertEquals("AC-9: sorting must not modify the list the source delivered", f1Manual, source)
    }
}
