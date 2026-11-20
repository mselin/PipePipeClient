package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.APPEARANCE_SETTINGS
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.NOTICE_LAYOUT
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.UPSTREAM_SETTINGS_KEYS
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.UPSTREAM_STRINGS
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.VALUES
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.androidAttribute
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.childrenOf
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.elementsOf
import org.schabi.newpipe.enhancements.groupsort.GroupSortMainFiles.textOf
import org.w3c.dom.Element

/**
 * The "resources" tests of plan section 9: the feature's strings, arrays, layout and settings
 * entry (plan section 6), read from `app/src/main/res` as XML. No Android framework is used.
 *
 * Until the Code layer adds `res/values/enhancements_group_sort.xml`,
 * `res/layout/enhancements_group_sort_notice.xml` and the entry in
 * `res/xml/appearance_settings.xml`, every test here fails with "does not exist" or "must have
 * exactly one entry".
 */
class GroupSortResourcesTest {

    private companion object {
        const val KEY = "enh_group_sort_key"
        const val TITLE = "enh_group_sort_title"
        const val LABEL_MANUAL = "enh_group_sort_manual"
        const val LABEL_NAME_ASC = "enh_group_sort_name_asc"
        const val LABEL_NAME_DESC = "enh_group_sort_name_desc"
        const val NOTICE = "enh_group_sort_reorder_notice"
        const val ENTRIES = "enh_group_sort_entries"
        const val VALUES_ARRAY = "enh_group_sort_values"

        /** The texts a user reads (plan section 6): the title, the three labels and the notice. */
        val VISIBLE_STRINGS = listOf(TITLE, LABEL_MANUAL, LABEL_NAME_ASC, LABEL_NAME_DESC, NOTICE)

        const val VALUES_MISSING = "plan section 6: the feature's strings and arrays are not there yet"
        const val LAYOUT_MISSING = "plan section 6: the notice layout is not there yet"
        const val UPSTREAM_MISSING = "an upstream file the feature depends on is gone"
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    /** The `<tag name="name">` child of `<resources>` in the feature's values file. */
    private fun resource(ac: String, tag: String, name: String): Element {
        val root = GroupSortMainFiles.xml(ac, VALUES, VALUES_MISSING).documentElement
        val found = childrenOf(root, tag).filter { it.getAttribute("name") == name }
        assertEquals("$ac: app/src/main/$VALUES must define <$tag name=\"$name\"> exactly once", 1, found.size)
        return found.single()
    }

    private fun string(ac: String, name: String): Element = resource(ac, "string", name)

    private fun stringArrayItems(ac: String, name: String): List<String> =
        childrenOf(resource(ac, "string-array", name), "item").map { textOf(it) }

    /** The one entry of the Appearance settings whose key is the feature's preference key (hook H4). */
    private fun settingEntry(ac: String): Element {
        val entries = elementsOf(GroupSortMainFiles.xml(ac, APPEARANCE_SETTINGS, UPSTREAM_MISSING))
            .filter { androidAttribute(it, "key") == "@string/$KEY" }
        assertEquals(
            "$ac: app/src/main/$APPEARANCE_SETTINGS must have exactly one entry with " +
                "android:key=\"@string/$KEY\" (hook H4, plan section 6)",
            1,
            entries.size
        )
        return entries.single()
    }

    // ---------------------------------------------------------------------------------------
    // AC-1 (and AC-11): the entry in Settings > Appearance
    // ---------------------------------------------------------------------------------------

    /**
     * AC-1: Settings > Appearance has the setting, once, as a list of choices. AC-11: being
     * declared in `appearance_settings.xml` under its key is what puts the value into the
     * default shared preferences and with them into export and import (plan section 8).
     */
    @Test
    fun ac1_ac11_resources_appearanceSettingsDeclareTheSettingOnceAsAListPreference() {
        val entry = settingEntry("AC-1/AC-11")
        assertEquals("AC-1: the entry with android:key=\"@string/$KEY\" must be a ListPreference", "ListPreference", entry.tagName)
    }

    /** AC-1: the setting is titled "Channel group order" (by reference) and "its summary shows the selected value". */
    @Test
    fun ac1_resources_settingHasItsTitleAndShowsTheSelectedValueAsSummary() {
        val entry = settingEntry("AC-1")
        assertEquals("AC-1: android:title of the setting", "@string/$TITLE", androidAttribute(entry, "title"))
        assertEquals(
            "AC-1: android:summary of the setting must be \"%s\" so that it shows the selected value",
            "%s",
            androidAttribute(entry, "summary")
        )
    }

    /** AC-1: *Manual* is the default: `android:defaultValue="manual"`, the stored value of the default mode. */
    @Test
    fun ac1_resources_settingDefaultsToManual() {
        val entry = settingEntry("AC-1")
        assertEquals("AC-1: android:defaultValue of the setting", "manual", androidAttribute(entry, "defaultValue"))
        assertEquals(
            "AC-1: android:defaultValue of the setting must be the preferenceValue of GroupSortMode.DEFAULT",
            GroupSortMode.DEFAULT.preferenceValue,
            androidAttribute(entry, "defaultValue")
        )
    }

    /** AC-1: the setting offers the three values through the two arrays of plan section 6. */
    @Test
    fun ac1_resources_settingUsesTheEntriesAndValuesArrays() {
        val entry = settingEntry("AC-1")
        assertEquals("AC-1: android:entries of the setting", "@array/$ENTRIES", androidAttribute(entry, "entries"))
        assertEquals("AC-1: android:entryValues of the setting", "@array/$VALUES_ARRAY", androidAttribute(entry, "entryValues"))
    }

    /**
     * AC-1, placement inside Settings > Appearance (plan section 3, H4): after the entry for
     * the landscape grid columns and before the "main page content" entry.
     */
    @Test
    fun ac1_resources_settingComesAfterTheGridColumnEntries() {
        settingEntry("AC-1")
        val keys = elementsOf(GroupSortMainFiles.xml("AC-1", APPEARANCE_SETTINGS, UPSTREAM_MISSING))
            .map { androidAttribute(it, "key") }
        val setting = keys.indexOf("@string/$KEY")
        val gridColumnsLandscape = keys.indexOf("@string/grid_columns_landscape_key")
        val mainPageContent = keys.indexOf("@string/main_page_content_key")
        assertNotEquals("AC-1: appearance_settings.xml no longer has the grid_columns_landscape_key entry", -1, gridColumnsLandscape)
        assertNotEquals("AC-1: appearance_settings.xml no longer has the main_page_content_key entry", -1, mainPageContent)
        assertTrue(
            "AC-1: the setting must come after the grid_columns_landscape_key entry (plan section 3, H4)",
            setting > gridColumnsLandscape
        )
        assertTrue(
            "AC-1: the setting must come before the main_page_content_key entry (plan section 3, H4)",
            setting < mainPageContent
        )
    }

    // ---------------------------------------------------------------------------------------
    // AC-1 (and AC-11): values, labels and key
    // ---------------------------------------------------------------------------------------

    /**
     * AC-1: the values the setting stores are the `preferenceValue`s of the modes, in the order
     * of the modes: `manual`, `name_asc`, `name_desc`.
     */
    @Test
    fun ac1_resources_valuesArrayEqualsThePreferenceValuesOfTheModes() {
        val values = stringArrayItems("AC-1", VALUES_ARRAY)
        assertEquals("AC-1: items of <string-array name=\"$VALUES_ARRAY\">", listOf("manual", "name_asc", "name_desc"), values)
        assertEquals(
            "AC-1: items of <string-array name=\"$VALUES_ARRAY\"> must equal GroupSortMode.values().map { it.preferenceValue }",
            GroupSortMode.values().map { it.preferenceValue },
            values
        )
    }

    /**
     * AC-1: the labels are in the order of the values, so that *Manual* stores `manual`,
     * *Name (A-Z)* stores `name_asc` and *Name (Z-A)* stores `name_desc`.
     */
    @Test
    fun ac1_resources_entriesArrayListsTheThreeLabelsInTheOrderOfTheValues() {
        assertEquals(
            "AC-1: items of <string-array name=\"$ENTRIES\">",
            listOf("@string/$LABEL_MANUAL", "@string/$LABEL_NAME_ASC", "@string/$LABEL_NAME_DESC"),
            stringArrayItems("AC-1", ENTRIES)
        )
    }

    /** AC-1: the English texts are "Channel group order", "Manual", "Name (A-Z)" and "Name (Z-A)". */
    @Test
    fun ac1_resources_titleAndLabelsHaveTheTextsOfTheSpec() {
        assertEquals("AC-1: text of <string name=\"$TITLE\">", "Channel group order", textOf(string("AC-1", TITLE)))
        assertEquals("AC-1: text of <string name=\"$LABEL_MANUAL\">", "Manual", textOf(string("AC-1", LABEL_MANUAL)))
        assertEquals("AC-1: text of <string name=\"$LABEL_NAME_ASC\">", "Name (A-Z)", textOf(string("AC-1", LABEL_NAME_ASC)))
        assertEquals("AC-1: text of <string name=\"$LABEL_NAME_DESC\">", "Name (Z-A)", textOf(string("AC-1", LABEL_NAME_DESC)))
    }

    /**
     * AC-1 and AC-11: the preference key, under which the value is stored, exported and
     * imported, is `enh_group_sort` (plan section 6).
     */
    @Test
    fun ac1_ac11_resources_preferenceKeyIsEnhGroupSort() {
        assertEquals("AC-1/AC-11: text of <string name=\"$KEY\">", "enh_group_sort", textOf(string("AC-1/AC-11", KEY)))
    }

    // ---------------------------------------------------------------------------------------
    // AC-10: the notice of the reorder dialog
    // ---------------------------------------------------------------------------------------

    /** AC-10: there is a text for the notice that groups are currently sorted by name. */
    @Test
    fun ac10_resources_noticeStringExists() {
        assertTrue(
            "AC-10: <string name=\"$NOTICE\"> must have a text",
            textOf(string("AC-10", NOTICE)).isNotEmpty()
        )
    }

    /**
     * AC-10: the notice layout exists and shows that text: one `TextView` root with the id and
     * the string of plan section 6.
     */
    @Test
    fun ac10_resources_noticeLayoutIsATextViewShowingTheNoticeString() {
        val root = GroupSortMainFiles.xml("AC-10", NOTICE_LAYOUT, LAYOUT_MISSING).documentElement
        assertEquals("AC-10: root element of app/src/main/$NOTICE_LAYOUT", "TextView", root.tagName)
        assertEquals("AC-10: android:text of the notice", "@string/$NOTICE", androidAttribute(root, "text"))
        assertEquals("AC-10: android:id of the notice", "@+id/enh_group_sort_notice", androidAttribute(root, "id"))
    }

    // ---------------------------------------------------------------------------------------
    // AC-12: translatable texts
    // ---------------------------------------------------------------------------------------

    /**
     * AC-12: "All new texts are translatable": the title, the three labels and the notice exist
     * in the default (English) values and are not marked `translatable="false"`.
     */
    @Test
    fun ac12_resources_visibleTextsExistAndAreTranslatable() {
        for (name in VISIBLE_STRINGS) {
            val element = string("AC-12", name)
            assertTrue("AC-12: <string name=\"$name\"> must have an English text", textOf(element).isNotEmpty())
            assertNotEquals(
                "AC-12: <string name=\"$name\"> is a text the user reads and must not be translatable=\"false\"",
                "false",
                element.getAttribute("translatable")
            )
        }
    }

    /**
     * AC-12: what is not a text is kept away from translators: the preference key and the
     * stored values are `translatable="false"` (a translated value would no longer match a mode).
     */
    @Test
    fun ac12_resources_keyAndStoredValuesAreNotTranslatable() {
        assertEquals(
            "AC-12: <string name=\"$KEY\"> must be translatable=\"false\"",
            "false",
            string("AC-12", KEY).getAttribute("translatable")
        )
        assertEquals(
            "AC-12: <string-array name=\"$VALUES_ARRAY\"> must be translatable=\"false\"",
            "false",
            resource("AC-12", "string-array", VALUES_ARRAY).getAttribute("translatable")
        )
    }

    /**
     * AC-12: the labels in the list of choices are references to translatable strings of the
     * same file, not texts written into the array.
     */
    @Test
    fun ac12_resources_entriesAreReferencesToTranslatableStrings() {
        val items = stringArrayItems("AC-12", ENTRIES)
        assertEquals("AC-12: <string-array name=\"$ENTRIES\"> must have one item per value", 3, items.size)
        for (item in items) {
            assertTrue(
                "AC-12: item \"$item\" of <string-array name=\"$ENTRIES\"> must be a @string/ reference",
                item.startsWith("@string/")
            )
            val referenced = string("AC-12", item.removePrefix("@string/"))
            assertNotEquals(
                "AC-12: $item is shown to the user and must not be translatable=\"false\"",
                "false",
                referenced.getAttribute("translatable")
            )
        }
    }

    /**
     * AC-12 and the fork's rule for it (plan section 6): all eight new names are defined in
     * `res/values/enhancements_group_sort.xml`, and none of them in upstream's `strings.xml` or
     * `settings_keys.xml`.
     */
    @Test
    fun ac12_resources_newNamesAreInTheEnhancementsFileAndNotInUpstreamFiles() {
        for (name in VISIBLE_STRINGS + KEY) {
            string("AC-12", name)
        }
        for (name in listOf(ENTRIES, VALUES_ARRAY)) {
            resource("AC-12", "string-array", name)
        }
        for (upstream in listOf(UPSTREAM_STRINGS, UPSTREAM_SETTINGS_KEYS)) {
            val text = GroupSortMainFiles.text("AC-12", upstream, UPSTREAM_MISSING)
            assertFalse(
                "AC-12: app/src/main/$upstream must not mention enh_group_sort; " +
                    "the feature's names belong in app/src/main/$VALUES",
                text.contains("enh_group_sort")
            )
        }
    }
}
