package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.groupsOf
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.quoted
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.shufflesOf
import java.util.Collections

/**
 * `GroupSorter.sort` (plan section 4): the order of a group list for each value of the setting.
 *
 * Expected orders are the fixture columns of plan section 5 and the example chain of spec AC-5
 * (see [GroupSortFixtures]). The element type is `String` or [TestGroup], never the Android
 * entity.
 */
class GroupSorterSortTest {

    private fun sortNames(names: List<String>, mode: GroupSortMode): List<String> =
        GroupSorter.sort(names, mode) { it }

    private fun sortGroups(groups: List<TestGroup>, mode: GroupSortMode): List<TestGroup> =
        GroupSorter.sort(groups, mode) { it.name }

    private fun describe(names: List<String>): String = names.joinToString(prefix = "[", postfix = "]") { quoted(it) }

    /** Every rearrangement of [aToZ] must sort to [expected] in [mode]. */
    private fun assertEveryShuffleSortsTo(what: String, aToZ: List<String>, mode: GroupSortMode, expected: List<String>) {
        for (shuffle in shufflesOf(aToZ)) {
            assertEquals("$what, $mode, input ${describe(shuffle)}", expected, sortNames(shuffle, mode))
        }
    }

    // ---------------------------------------------------------------------------------------
    // AC-2 Manual
    // ---------------------------------------------------------------------------------------

    /**
     * AC-2: with *Manual* a group list is exactly as without the feature. Plan section 4:
     * `sort(.., MANUAL, ..)` returns its argument, the same instance.
     */
    @Test
    fun ac2_manualReturnsTheSameListInstance() {
        val inputs: List<List<TestGroup>> = listOf(
            groupsOf(GroupSortFixtures.F1_MANUAL),
            groupsOf(GroupSortFixtures.F2_MANUAL),
            groupsOf(GroupSortFixtures.F4_Z_TO_A),
            ArrayList(groupsOf(GroupSortFixtures.F3_Z_TO_A)),
            listOf(TestGroup(1, "only")),
            ArrayList()
        )
        for (input in inputs) {
            assertSame(
                "AC-2: sort(groups, MANUAL, nameOf) must return groups itself, for ${describe(input.map { it.name })}",
                input,
                sortGroups(input, GroupSortMode.MANUAL)
            )
        }
    }

    /** AC-2: with *Manual* the order is the manual order, here of fixtures F1 and F2. */
    @Test
    fun ac2_manualKeepsTheManualOrder() {
        assertEquals(
            "AC-2 fixture F1: MANUAL must keep the manual order",
            GroupSortFixtures.F1_MANUAL,
            sortNames(ArrayList(GroupSortFixtures.F1_MANUAL), GroupSortMode.MANUAL)
        )
        assertEquals(
            "AC-2 fixture F2: MANUAL must keep the manual order",
            GroupSortFixtures.F2_MANUAL,
            sortNames(ArrayList(GroupSortFixtures.F2_MANUAL), GroupSortMode.MANUAL)
        )
    }

    // ---------------------------------------------------------------------------------------
    // AC-3 Name (A-Z), AC-4 Name (Z-A): fixtures F1-F3
    // ---------------------------------------------------------------------------------------

    /** AC-3, fixture F1: the manual order sorts to the A-Z column. */
    @Test
    fun ac3_fixtureF1_nameAscendingGivesTheAToZColumn() {
        assertEquals(
            "AC-3 fixture F1: Name (A-Z)",
            GroupSortFixtures.F1_A_TO_Z,
            sortNames(GroupSortFixtures.F1_MANUAL, GroupSortMode.NAME_ASC)
        )
    }

    /** AC-4, fixture F1: the manual order sorts to the Z-A column, the exact reverse of A-Z. */
    @Test
    fun ac4_fixtureF1_nameDescendingGivesTheZToAColumn() {
        assertEquals(
            "AC-4 fixture F1: Name (Z-A)",
            GroupSortFixtures.F1_Z_TO_A,
            sortNames(GroupSortFixtures.F1_MANUAL, GroupSortMode.NAME_DESC)
        )
    }

    /** AC-3 and AC-4, fixture F1 from every other starting order: the result does not depend on the manual order. */
    @Test
    fun ac3_ac4_fixtureF1_anyManualOrderGivesTheSameNameOrder() {
        assertEveryShuffleSortsTo("AC-3 fixture F1", GroupSortFixtures.F1_A_TO_Z, GroupSortMode.NAME_ASC, GroupSortFixtures.F1_A_TO_Z)
        assertEveryShuffleSortsTo("AC-4 fixture F1", GroupSortFixtures.F1_A_TO_Z, GroupSortMode.NAME_DESC, GroupSortFixtures.F1_Z_TO_A)
    }

    /**
     * AC-3 (with AC-6), fixture F2: "a", "b", then the three equal names "Music", "music",
     * " MUSIC " in their manual order.
     */
    @Test
    fun ac3_ac6_fixtureF2_nameAscendingGivesTheAToZColumn() {
        assertEquals(
            "AC-3/AC-6 fixture F2: Name (A-Z), equal names in manual order",
            GroupSortFixtures.F2_A_TO_Z,
            sortNames(GroupSortFixtures.F2_MANUAL, GroupSortMode.NAME_ASC)
        )
    }

    /**
     * AC-4 (with AC-6), fixture F2: the three equal names "Music", "music", " MUSIC " first,
     * still in their manual order, then "b", "a".
     */
    @Test
    fun ac4_ac6_fixtureF2_nameDescendingGivesTheZToAColumn() {
        assertEquals(
            "AC-4/AC-6 fixture F2: Name (Z-A), equal names in manual order",
            GroupSortFixtures.F2_Z_TO_A,
            sortNames(GroupSortFixtures.F2_MANUAL, GroupSortMode.NAME_DESC)
        )
    }

    /** AC-3, fixture F3: every shuffle of the A-Z column sorts back to it. */
    @Test
    fun ac3_fixtureF3_anyShuffleSortsToTheAToZColumn() {
        assertEveryShuffleSortsTo("AC-3 fixture F3", GroupSortFixtures.F3_A_TO_Z, GroupSortMode.NAME_ASC, GroupSortFixtures.F3_A_TO_Z)
    }

    /** AC-4, fixture F3: every shuffle of the A-Z column sorts to its exact reverse. */
    @Test
    fun ac4_fixtureF3_anyShuffleSortsToTheZToAColumn() {
        assertEveryShuffleSortsTo("AC-4 fixture F3", GroupSortFixtures.F3_A_TO_Z, GroupSortMode.NAME_DESC, GroupSortFixtures.F3_Z_TO_A)
    }

    /**
     * AC-3 and AC-4: the name comes from `nameOf`, not from the element itself. The ids, and
     * with them `toString()` and any natural order of the elements, run against the names.
     */
    @Test
    fun ac3_ac4_orderComesFromTheNameSelector() {
        val groups = listOf(TestGroup(1, "music"), TestGroup(2, "Mix 2"), TestGroup(3, "001 - First"), TestGroup(4, "Mix 10"))
        assertEquals(
            "AC-3: Name (A-Z) of groups, by nameOf",
            listOf(TestGroup(3, "001 - First"), TestGroup(4, "Mix 10"), TestGroup(2, "Mix 2"), TestGroup(1, "music")),
            sortGroups(groups, GroupSortMode.NAME_ASC)
        )
        assertEquals(
            "AC-4: Name (Z-A) of groups, by nameOf",
            listOf(TestGroup(1, "music"), TestGroup(2, "Mix 2"), TestGroup(4, "Mix 10"), TestGroup(3, "001 - First")),
            sortGroups(groups, GroupSortMode.NAME_DESC)
        )
        // a selector that is not the obvious property: sort ids by a name looked up elsewhere
        val nameById = mapOf(10L to "Zebra", 20L to "apple", 30L to "Mix")
        assertEquals(
            "AC-3: Name (A-Z) by a looked-up name",
            listOf(20L, 30L, 10L),
            GroupSorter.sort(listOf(10L, 20L, 30L), GroupSortMode.NAME_ASC) { nameById.getValue(it) }
        )
        assertEquals(
            "AC-4: Name (Z-A) by a looked-up name",
            listOf(10L, 30L, 20L),
            GroupSorter.sort(listOf(10L, 20L, 30L), GroupSortMode.NAME_DESC) { nameById.getValue(it) }
        )
    }

    // ---------------------------------------------------------------------------------------
    // AC-5 as a sort: the spec's example chain and fixture F4
    // ---------------------------------------------------------------------------------------

    /**
     * AC-5, the example chain of the spec as a sort (AC-3): every shuffle sorts to "- Top",
     * "001 - First", "Mix 10", "Mix 2", "music", "ZZZ - Last", "ZZZZ - Last of last", "Äpfel".
     */
    @Test
    fun ac5_exampleChain_anyShuffleSortsAscendingToTheChain() {
        assertEveryShuffleSortsTo("AC-5 example chain", GroupSortFixtures.AC5_CHAIN, GroupSortMode.NAME_ASC, GroupSortFixtures.AC5_CHAIN)
    }

    /** AC-5, the example chain of the spec as a descending sort (AC-4): the chain backwards. */
    @Test
    fun ac5_exampleChain_anyShuffleSortsDescendingToTheReversedChain() {
        val backwards = listOf(
            GroupSortFixtures.AEPFEL,
            "ZZZZ - Last of last",
            "ZZZ - Last",
            "music",
            "Mix 2",
            "Mix 10",
            "001 - First",
            "- Top"
        )
        assertEveryShuffleSortsTo("AC-5 example chain", GroupSortFixtures.AC5_CHAIN, GroupSortMode.NAME_DESC, backwards)
    }

    /** AC-5 (answer A4), fixture F4: every shuffle sorts to the A-Z column, symbols by character code. */
    @Test
    fun ac5_fixtureF4_anyShuffleSortsToTheAToZColumn() {
        assertEveryShuffleSortsTo("AC-5 fixture F4", GroupSortFixtures.F4_A_TO_Z, GroupSortMode.NAME_ASC, GroupSortFixtures.F4_A_TO_Z)
    }

    /** AC-5 (answer A4), fixture F4: every shuffle sorts to the Z-A column, the exact reverse. */
    @Test
    fun ac5_fixtureF4_anyShuffleSortsToTheZToAColumn() {
        assertEveryShuffleSortsTo("AC-5 fixture F4", GroupSortFixtures.F4_A_TO_Z, GroupSortMode.NAME_DESC, GroupSortFixtures.F4_Z_TO_A)
    }

    // ---------------------------------------------------------------------------------------
    // AC-6 equal names keep their manual order
    // ---------------------------------------------------------------------------------------

    /**
     * AC-6, fixture F2 in A-Z with the groups told apart by id (= manual position): b=1,
     * Music=2, a=3, music=4, " MUSIC "=5. The equal names 2, 4, 5 stay in that order.
     *
     * Assumption (plan section 11): "equal names" means equal under AC-5, so names that differ
     * only in case or surrounding whitespace are ties.
     */
    @Test
    fun ac6_fixtureF2_equalNamesKeepManualOrderInAToZ() {
        val sorted = sortGroups(groupsOf(GroupSortFixtures.F2_MANUAL), GroupSortMode.NAME_ASC)
        assertEquals("AC-6 fixture F2, A-Z: ids in the sorted list", listOf(3L, 1L, 2L, 4L, 5L), sorted.map { it.id })
        assertEquals("AC-6 fixture F2, A-Z: names in the sorted list", GroupSortFixtures.F2_A_TO_Z, sorted.map { it.name })
    }

    /**
     * AC-6, fixture F2 in Z-A: the equal names 2, 4, 5 come first and are still in manual
     * order; they are not reversed together with the rest (plan section 4: Z-A is a stable
     * sort with the reversed comparison, not the reversed A-Z list).
     */
    @Test
    fun ac6_fixtureF2_equalNamesKeepManualOrderInZToA() {
        val sorted = sortGroups(groupsOf(GroupSortFixtures.F2_MANUAL), GroupSortMode.NAME_DESC)
        assertEquals("AC-6 fixture F2, Z-A: ids in the sorted list", listOf(2L, 4L, 5L, 1L, 3L), sorted.map { it.id })
        assertEquals("AC-6 fixture F2, Z-A: names in the sorted list", GroupSortFixtures.F2_Z_TO_A, sorted.map { it.name })
    }

    /** AC-6: with ties, Z-A is not the A-Z list read backwards (that would turn the ties around). */
    @Test
    fun ac6_fixtureF2_zToAIsNotTheReversedAToZList() {
        val groups = groupsOf(GroupSortFixtures.F2_MANUAL)
        val aToZ = sortGroups(groups, GroupSortMode.NAME_ASC)
        val zToA = sortGroups(groups, GroupSortMode.NAME_DESC)
        assertNotEquals("AC-6 fixture F2: Z-A must not be reversed(A-Z); equal names keep manual order", aToZ.reversed(), zToA)
    }

    /**
     * AC-6 read literally, with identical names: Music=1, News=2, Music=3, Art=4, Music=5,
     * News=6. Within "Music" (1, 3, 5) and within "News" (2, 6) the manual order stays, in both
     * directions.
     */
    @Test
    fun ac6_identicalNamesKeepManualOrderInBothDirections() {
        val groups = groupsOf(listOf("Music", "News", "Music", "Art", "Music", "News"))
        assertEquals(
            "AC-6, A-Z: Art, then the Music groups in manual order, then the News groups in manual order",
            listOf(4L, 1L, 3L, 5L, 2L, 6L),
            sortGroups(groups, GroupSortMode.NAME_ASC).map { it.id }
        )
        assertEquals(
            "AC-6, Z-A: the News groups in manual order, then the Music groups in manual order, then Art",
            listOf(2L, 6L, 1L, 3L, 5L, 4L),
            sortGroups(groups, GroupSortMode.NAME_DESC).map { it.id }
        )
    }

    /** AC-6: when all names are equal, both name orders show the manual order. */
    @Test
    fun ac6_allNamesEqual_bothDirectionsShowTheManualOrder() {
        val groups = groupsOf(listOf("Mix", "mix", " MIX", "mIx ", "Mix"))
        assertEquals("AC-6, A-Z of all-equal names", listOf(1L, 2L, 3L, 4L, 5L), sortGroups(groups, GroupSortMode.NAME_ASC).map { it.id })
        assertEquals("AC-6, Z-A of all-equal names", listOf(1L, 2L, 3L, 4L, 5L), sortGroups(groups, GroupSortMode.NAME_DESC).map { it.id })
    }

    // ---------------------------------------------------------------------------------------
    // AC-7 only the groups are reordered
    // ---------------------------------------------------------------------------------------

    /**
     * AC-7: "All" and "New" are not groups and never reach the sorter (plan section 9), so what
     * the sorter must guarantee is that it returns exactly the groups it was given: same size,
     * same elements (by identity, each as often as in the input), in every mode. Nothing is
     * added, dropped, duplicated or replaced by a copy.
     */
    @Test
    fun ac7_resultHoldsExactlyTheInputElementsInEveryMode() {
        val twice = TestGroup(99, "Twice")
        val inputs: List<List<TestGroup>> = listOf(
            groupsOf(GroupSortFixtures.F1_MANUAL),
            groupsOf(GroupSortFixtures.F2_MANUAL),
            groupsOf(GroupSortFixtures.F3_Z_TO_A),
            groupsOf(GroupSortFixtures.F4_Z_TO_A),
            groupsOf(GroupSortFixtures.AC5_CHAIN.reversed()),
            // equal elements that are different objects, and one object that is in the list twice
            listOf(TestGroup(1, "Same"), TestGroup(1, "Same"), twice, TestGroup(2, "Other"), twice),
            listOf(TestGroup(1, "only")),
            emptyList()
        )
        for (mode in GroupSortMode.values()) {
            for (input in inputs) {
                val result = sortGroups(input, mode)
                val names = describe(input.map { it.name })
                assertEquals("AC-7: $mode must return as many groups as it was given, for $names", input.size, result.size)
                // equal sizes and equal counts of every input object: the same multiset
                for (group in input) {
                    assertEquals(
                        "AC-7: $mode must return every object it was given exactly as often as it was given; " +
                            "$group of $names",
                        input.count { it === group },
                        result.count { it === group }
                    )
                }
            }
        }
    }

    /** AC-7: an empty list and a single group need no order; every mode returns them as they are. */
    @Test
    fun ac7_emptyAndSingleGroupListsAreReturnedUnchangedInEveryMode() {
        val single = listOf(TestGroup(7, "Lonely"))
        for (mode in GroupSortMode.values()) {
            assertEquals("AC-7: $mode of an empty list", emptyList<TestGroup>(), sortGroups(emptyList(), mode))
            assertEquals("AC-7: $mode of a single group", single, sortGroups(single, mode))
        }
    }

    // ---------------------------------------------------------------------------------------
    // AC-9 the manual order is never modified
    // ---------------------------------------------------------------------------------------

    /**
     * AC-9: sorting never modifies the manual order. The list handed to `sort` equals a copy
     * taken before, in every mode and for every fixture.
     */
    @Test
    fun ac9_inputListIsLeftAsItWasInEveryMode() {
        val manualOrders = listOf(
            GroupSortFixtures.F1_MANUAL,
            GroupSortFixtures.F2_MANUAL,
            GroupSortFixtures.F3_Z_TO_A,
            GroupSortFixtures.F4_Z_TO_A,
            GroupSortFixtures.AC5_CHAIN.reversed()
        )
        for (mode in GroupSortMode.values()) {
            for (names in manualOrders) {
                val input = ArrayList(groupsOf(names))
                val before = ArrayList(input)
                sortGroups(input, mode)
                assertEquals("AC-9: $mode must not reorder the list it is given, ${describe(names)}", before, input)
            }
        }
    }

    /**
     * AC-9: `sort` accepts a list that cannot be modified (plan section 9) and still returns
     * the right order, so it cannot be sorting in place.
     */
    @Test
    fun ac9_unmodifiableInputIsAcceptedInEveryMode() {
        val manual: List<String> = Collections.unmodifiableList(ArrayList(GroupSortFixtures.F1_MANUAL))
        assertEquals("AC-9: MANUAL of an unmodifiable list", GroupSortFixtures.F1_MANUAL, sortNames(manual, GroupSortMode.MANUAL))
        assertEquals("AC-9: Name (A-Z) of an unmodifiable list", GroupSortFixtures.F1_A_TO_Z, sortNames(manual, GroupSortMode.NAME_ASC))
        assertEquals("AC-9: Name (Z-A) of an unmodifiable list", GroupSortFixtures.F1_Z_TO_A, sortNames(manual, GroupSortMode.NAME_DESC))
        assertEquals("AC-9: the unmodifiable list afterwards", GroupSortFixtures.F1_MANUAL, manual)
    }

    /**
     * AC-9: "Switching back to *Manual* shows the order that was there before." After the same
     * list went through both name orders, *Manual* still gives the manual order of fixture F1.
     */
    @Test
    fun ac9_manualAfterBothNameOrdersShowsTheManualOrderAgain() {
        val manual = ArrayList(groupsOf(GroupSortFixtures.F1_MANUAL))
        sortGroups(manual, GroupSortMode.NAME_ASC)
        sortGroups(manual, GroupSortMode.NAME_DESC)
        assertEquals(
            "AC-9: MANUAL after Name (A-Z) and Name (Z-A)",
            GroupSortFixtures.F1_MANUAL,
            sortGroups(manual, GroupSortMode.MANUAL).map { it.name }
        )
        assertEquals(
            "AC-9: ids (manual positions) after switching back",
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            sortGroups(manual, GroupSortMode.MANUAL).map { it.id }
        )
    }
}
