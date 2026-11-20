package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GroupSortMode` (plan section 4): the three values of the "Channel group order" setting, the
 * strings they are stored as, and how a stored string is read back.
 */
class GroupSortModeTest {

    /** AC-1: the setting has three values, *Manual*, *Name (A-Z)*, *Name (Z-A)*, in this order. */
    @Test
    fun ac1_threeModesInTheOrderOfTheSetting() {
        assertEquals(
            "AC-1: GroupSortMode must have exactly MANUAL, NAME_ASC, NAME_DESC in this order",
            listOf("MANUAL", "NAME_ASC", "NAME_DESC"),
            GroupSortMode.values().map { it.name }
        )
    }

    /** AC-1: each mode is stored as the string of plan section 4 / section 6. */
    @Test
    fun ac1_preferenceValuesAreManualNameAscNameDesc() {
        assertEquals("AC-1: MANUAL.preferenceValue", "manual", GroupSortMode.MANUAL.preferenceValue)
        assertEquals("AC-1: NAME_ASC.preferenceValue", "name_asc", GroupSortMode.NAME_ASC.preferenceValue)
        assertEquals("AC-1: NAME_DESC.preferenceValue", "name_desc", GroupSortMode.NAME_DESC.preferenceValue)
    }

    /** AC-1: *Manual* is the default. */
    @Test
    fun ac1_defaultIsManual() {
        assertSame("AC-1: GroupSortMode.DEFAULT", GroupSortMode.MANUAL, GroupSortMode.DEFAULT)
    }

    /** AC-1: a stored string selects the mode with exactly that `preferenceValue`. */
    @Test
    fun ac1_fromPreferenceValueSelectsTheModeWithThatValue() {
        assertSame("AC-1: \"manual\"", GroupSortMode.MANUAL, GroupSortMode.fromPreferenceValue("manual"))
        assertSame("AC-1: \"name_asc\"", GroupSortMode.NAME_ASC, GroupSortMode.fromPreferenceValue("name_asc"))
        assertSame("AC-1: \"name_desc\"", GroupSortMode.NAME_DESC, GroupSortMode.fromPreferenceValue("name_desc"))
    }

    /** AC-1: nothing stored yet (`null`) means the default, *Manual*. */
    @Test
    fun ac1_fromPreferenceValueOfNullIsManual() {
        assertSame("AC-1: fromPreferenceValue(null)", GroupSortMode.MANUAL, GroupSortMode.fromPreferenceValue(null))
    }

    /**
     * AC-1: the match is exact and case-sensitive (plan section 4). The empty string, another
     * case, surrounding whitespace, enum names and unrelated strings all give *Manual*, and
     * none of them throws.
     */
    @Test
    fun ac1_fromPreferenceValueOfAnythingElseIsManual() {
        val notAValue = listOf(
            "",
            " ",
            "MANUAL",
            "Manual",
            " manual",
            "manual ",
            "az",
            "NAME_ASC",
            "Name_Asc",
            "name_asc ",
            " name_asc",
            "name-asc",
            "nameasc",
            "name_asc\n",
            "NAME_DESC",
            "name_descending",
            "name",
            "0",
            "true"
        )
        for (value in notAValue) {
            assertSame(
                "AC-1: fromPreferenceValue(${GroupSortFixtures.quoted(value)}) is not a stored value " +
                    "and must give MANUAL (exact, case-sensitive match only)",
                GroupSortMode.MANUAL,
                GroupSortMode.fromPreferenceValue(value)
            )
        }
    }

    /**
     * AC-10: the reorder dialog shows its notice exactly when groups are sorted by name, which
     * `sortsByName` answers: false for *Manual*, true for both name orders.
     */
    @Test
    fun ac10_sortsByNameIsFalseForManualAndTrueForBothNameOrders() {
        assertFalse("AC-10: MANUAL.sortsByName must be false (no notice)", GroupSortMode.MANUAL.sortsByName)
        assertTrue("AC-10: NAME_ASC.sortsByName must be true (notice)", GroupSortMode.NAME_ASC.sortsByName)
        assertTrue("AC-10: NAME_DESC.sortsByName must be true (notice)", GroupSortMode.NAME_DESC.sortsByName)
    }

    /**
     * AC-11: the setting travels through export and import as its stored string, so every mode
     * must read back from its own `preferenceValue`.
     */
    @Test
    fun ac11_everyModeReadsBackFromItsStoredValue() {
        for (mode in GroupSortMode.values()) {
            assertSame(
                "AC-11: fromPreferenceValue(\"${mode.preferenceValue}\") must give back $mode",
                mode,
                GroupSortMode.fromPreferenceValue(mode.preferenceValue)
            )
        }
    }

    /**
     * AC-11: a backup without the key (`null`) or with a value this version does not know
     * (hand-edited, or written by another version) reads as *Manual* (plan section 8).
     */
    @Test
    fun ac11_missingOrUnknownStoredValueReadsAsManual() {
        for (stored in listOf(null, "", "z_to_a", "name_desc_v2", "2", "false")) {
            assertSame(
                "AC-11: an imported value of ${stored?.let(GroupSortFixtures::quoted)} must read as MANUAL",
                GroupSortMode.MANUAL,
                GroupSortMode.fromPreferenceValue(stored)
            )
        }
    }
}
