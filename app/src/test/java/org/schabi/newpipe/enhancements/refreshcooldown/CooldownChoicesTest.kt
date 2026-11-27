package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-1, AC-2, AC-3 (plan 4.5): the choices a group and a subscription are offered are the
 * global setting's own, in its order, with *Use default* in front. The arrays are read from
 * `res/values/settings_keys.xml`, so the test also notices when upstream changes them
 * (plan, risk 7).
 *
 * Not shown here: that `CooldownStore.choices(context)` passes these two arrays and
 * `Localization.localizeDuration` (glue), and the labels in another language (device step 12).
 */
class CooldownChoicesTest {

    private val keysFile = "values/settings_keys.xml"

    /** `getStringArray` resolves a `@string/…` item (plan section 12); the test does the same from `strings.xml`. */
    private fun resolved(item: String): String =
        if (item.startsWith("@string/")) Res.string("AC-1", "values/strings.xml", item.removePrefix("@string/")) ?: item else item

    private fun appValues(): Array<String> = Res.stringArray("AC-1", keysFile, "feed_update_threshold_values").toTypedArray()
    private fun appOptions(): Array<String> = Res.stringArray("AC-1", keysFile, "feed_update_threshold_options").map { resolved(it) }.toTypedArray()

    /** Stands for `Localization.localizeDuration`: marks its results so that the test sees who made a label. */
    private val localize: (Int) -> String = { "<$it s>" }

    private fun appChoices(): CooldownChoices = CooldownChoices.of(appValues(), appOptions(), localize)

    /** AC-1: the global setting's choices and default are as the spec lists them; nothing here is changed by the feature. */
    @Test
    fun ac1_theGlobalSettingsChoicesAndDefaultAreAsTheyWere() {
        assertEquals(listOf("0", "300", "900", "3600", "21600", "43200", "86400"), appValues().toList())
        assertEquals(listOf("Always update", "5 minutes", "15 minutes", "1 hour", "6 hours", "12 hours", "1 day"), appOptions().toList())
        assertEquals("the test's own constant", CHOICES, appValues().map { it.toInt() })
        assertEquals("300", Res.string("AC-1", keysFile, "feed_update_threshold_default_value"))
        assertEquals("feed_update_threshold_key", Res.string("AC-1", keysFile, "feed_update_threshold_key"))

        val screen = Sources.lines("AC-1", "res/xml/notifications_settings.xml").joinToString("\n")
        for (attribute in listOf(
            "android:defaultValue=\"@string/feed_update_threshold_default_value\"",
            "android:entries=\"@array/feed_update_threshold_options\"",
            "android:entryValues=\"@array/feed_update_threshold_values\"",
            "android:key=\"@string/feed_update_threshold_key\"",
            "android:title=\"@string/settings_category_feed_title\""
        )) {
            assertEquals("AC-1: res/xml/notifications_settings.xml still has $attribute", 1, Sources.count(screen, attribute))
        }
        assertEquals("AC-1: the setting is the screen's first entry", true, screen.indexOf("<org.schabi.newpipe.settings.custom.DurationListPreference") in 0 until screen.indexOf("<MultiSelectListPreference"))
    }

    /** AC-2, AC-3, plan 4.5: with the app's arrays: the seven seconds, in order; the 0 entry keeps its option text, every other label is localized. */
    @Test
    fun ac2_of_theAppsArrays_secondsAndLabels() {
        val choices = appChoices()

        assertEquals(listOf(0, 300, 900, 3600, 21600, 43200, 86400), choices.seconds)
        assertEquals(listOf("Always update", "<300 s>", "<900 s>", "<3600 s>", "<21600 s>", "<43200 s>", "<86400 s>"), choices.labels)
        assertEquals(setOf(0, 300, 900, 3600, 21600, 43200, 86400), choices.allowed)
    }

    /** AC-2, AC-3: the list a chooser shows: *Use default* first, then the global setting's choices in its order. */
    @Test
    fun ac2_items_useDefaultFirstThenTheChoices() {
        assertEquals(
            listOf("Use default", "Always update", "<300 s>", "<900 s>", "<3600 s>", "<21600 s>", "<43200 s>", "<86400 s>"),
            appChoices().items("Use default")
        )
        assertEquals(listOf("x"), CooldownChoices(emptyList(), emptyList()).items("x"))
    }

    /** AC-2, AC-3 ("shows the current value"), plan 4.5: the position of a value in that list, and back. */
    @Test
    fun ac2_indexOfAndValueAt_thePlansExamples() {
        val choices = appChoices()

        assertEquals(0, choices.indexOf(null))
        assertEquals(1, choices.indexOf(0))
        assertEquals(2, choices.indexOf(300))
        assertEquals(7, choices.indexOf(86400))
        assertEquals("a value that is not a choice shows as Use default", 0, choices.indexOf(301))
        assertEquals(0, choices.indexOf(-1))

        assertNull(choices.valueAt(0))
        assertEquals("\"Always update\" is 0 seconds, not \"no value\"", 0, choices.valueAt(1))
        assertEquals(300, choices.valueAt(2))
        assertEquals(86400, choices.valueAt(7))
        assertNull(choices.valueAt(8))
        assertNull(choices.valueAt(-1))
        assertNull(choices.valueAt(Int.MAX_VALUE))
        assertNull(choices.valueAt(Int.MIN_VALUE))
    }

    /** AC-2, AC-3: every item of the chooser gives a value that is shown at the same item when the chooser is opened again. */
    @Test
    fun ac2_indexOfAndValueAt_areEachOthersInverse() {
        val choices = appChoices()
        val items = choices.items("Use default")

        for (index in items.indices) {
            assertEquals("item $index (`${items[index]}`)", index, choices.indexOf(choices.valueAt(index)))
            assertEquals("the label of item $index", items[index], choices.label(choices.valueAt(index), "Use default"))
        }
        for (seconds in CHOICES) assertEquals(seconds, choices.valueAt(choices.indexOf(seconds)))
    }

    /** AC-2, AC-3 ("shows the current value"), plan 4.5: the label of a value; no value, or one that is no longer a choice, is *Use default*. */
    @Test
    fun ac3_label_ofAValue() {
        val choices = appChoices()

        assertEquals("Use default", choices.label(null, "Use default"))
        assertEquals("Always update", choices.label(0, "Use default"))
        assertEquals("<3600 s>", choices.label(3600, "Use default"))
        assertEquals("<86400 s>", choices.label(86400, "Use default"))
        assertEquals("Use default", choices.label(301, "Use default"))
        assertEquals("the text for Use default is the caller's", "Standard", choices.label(null, "Standard"))
        assertEquals("Standard", choices.label(7200, "Standard"))
    }

    /** Plan 4.5: "an entry that is none (an integer) is left out", with its option; the others keep their own option. */
    @Test
    fun ac1_of_aNonNumericValueIsLeftOut() {
        val choices = CooldownChoices.of(arrayOf("0", "abc", "300", "", "3e2", "900"), arrayOf("Always", "B", "C", "D", "E", "F"), localize)

        assertEquals(listOf(0, 300, 900), choices.seconds)
        assertEquals(listOf("Always", "<300 s>", "<900 s>"), choices.labels)
        assertEquals(setOf(0, 300, 900), choices.allowed)
        assertEquals(2, choices.indexOf(300))
    }

    /** Plan 4.5: the label of a value of 0 or less is its own option text (also when it is not the first), or the number when the options are shorter. */
    @Test
    fun ac1_of_zeroOrLessKeepsItsOptionText() {
        val moved = CooldownChoices.of(arrayOf("300", "0", "-1"), arrayOf("five", "always", "never"), localize)
        assertEquals(listOf(300, 0, -1), moved.seconds)
        assertEquals(listOf("<300 s>", "always", "never"), moved.labels)

        val shorter = CooldownChoices.of(arrayOf("300", "0"), arrayOf("five"), localize)
        assertEquals(listOf("<300 s>", "0"), shorter.labels)

        val none = CooldownChoices.of(emptyArray(), emptyArray(), localize)
        assertEquals(emptyList<Int>(), none.seconds)
        assertEquals(emptySet<Int>(), none.allowed)
        assertEquals(0, none.indexOf(300))
        assertNull(none.valueAt(1))
    }

    /** Plan section 4: the constructor takes the two lists as they are. */
    @Test
    fun ac1_constructor_keepsSecondsAndLabels() {
        val choices = CooldownChoices(listOf(0, 60), listOf("always", "a minute"))

        assertEquals(listOf(0, 60), choices.seconds)
        assertEquals(listOf("always", "a minute"), choices.labels)
        assertEquals(setOf(0, 60), choices.allowed)
        assertEquals("a minute", choices.label(60, "d"))
        assertEquals(listOf("d", "always", "a minute"), choices.items("d"))
    }

    /** AC-4: what `parse` accepts as seconds is exactly what the choices allow: every choice can be stored and read back, nothing else. */
    @Test
    fun ac4_allowed_isWhatParseAccepts() {
        val allowed = appChoices().allowed
        for (seconds in CHOICES) {
            assertEquals(seconds, CooldownValues.parse("7:0473fe05=$seconds", allowed)["7:0473fe05"])
        }
        for (seconds in listOf(1, 60, 299, 301, 7200, 86401)) {
            assertNull("$seconds s is not a choice", CooldownValues.parse("7:0473fe05=$seconds", allowed)["7:0473fe05"])
        }
    }
}
