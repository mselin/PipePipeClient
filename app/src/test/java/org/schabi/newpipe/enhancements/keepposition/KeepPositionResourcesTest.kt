package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

private const val SETTINGS_XML = "xml/advanced_settings.xml"
private const val VALUES_XML = "values/enhancements_keep_position.xml"

private const val ALWAYS_START_KEY_REF = "@string/always_start_from_beginning_key"
private const val KEY = "enh_keep_position_key"
private const val TITLE = "enh_keep_position_title"
private const val SUMMARY = "enh_keep_position_summary"

/**
 * The resources of plan section 5 and hook H9, read from the source tree with `javax.xml`
 * (no Android, no Robolectric).
 *
 * - AC-1: the switch sits directly below "Always start play from beginning", is on by
 *   default, and shows the title and summary of plan section 5.
 * - AC-11: the texts are string resources in the default `values/` directory, so every
 *   language falls back to English, and only the preference key is not translatable.
 *
 * Gradle runs unit tests with `app/` as working directory; from the repository root the
 * files are found under `app/`.
 */
class KeepPositionResourcesTest {

    // ---- AC-1: the switch in Settings > Advanced (H9) ----

    @Test
    fun ac1_switchSitsDirectlyBelowAlwaysStartFromBeginning() {
        val alwaysStart = preferencesWithKey(ALWAYS_START_KEY_REF).singleOrNull()
            ?: throw AssertionError("$SETTINGS_XML: expected one preference with key $ALWAYS_START_KEY_REF")

        val next = generateSequence(alwaysStart.nextSibling) { it.nextSibling }
            .filterIsInstance<Element>()
            .firstOrNull()

        assertEquals(
            "$SETTINGS_XML: the element directly after the always-start switch " +
                "must be the keep-position switch (hook H9)",
            "@string/$KEY",
            next?.android("key")
        )
        assertEquals("$SETTINGS_XML: the new element is a switch", "SwitchPreferenceCompat", next?.tagName)
    }

    @Test
    fun ac1_switchIsOnByDefault() {
        assertEquals(
            "$SETTINGS_XML: android:defaultValue of the keep-position switch",
            "true",
            keepPositionSwitch().android("defaultValue")
        )
    }

    @Test
    fun ac1_ac11_switchShowsTheTitleAndSummaryStrings() {
        val switch = keepPositionSwitch()

        assertEquals("$SETTINGS_XML: android:title", "@string/$TITLE", switch.android("title"))
        assertEquals("$SETTINGS_XML: android:summary", "@string/$SUMMARY", switch.android("summary"))
    }

    @Test
    fun ac1_englishTitleAndSummaryAreTheTextsOfThePlan() {
        val strings = keepPositionStrings()

        assertEquals(
            "$VALUES_XML: $TITLE",
            "Keep position of the playing video",
            strings[TITLE]?.text()
        )
        assertEquals(
            "$VALUES_XML: $SUMMARY",
            "The video continues where it was when it moves between players, changes quality or recovers from an error",
            strings[SUMMARY]?.text()
        )
    }

    /** Plan section 5: the key string holds the preference key the glue reads, its own name. */
    @Test
    fun ac1_preferenceKeyIsItsOwnName() {
        assertEquals("$VALUES_XML: $KEY", KEY, keepPositionStrings()[KEY]?.text())
    }

    // ---- AC-11: translatable texts, English delivered ----

    @Test
    fun ac11_keyTitleAndSummaryAreDeliveredInTheDefaultValuesDirectory() {
        val strings = keepPositionStrings()

        for (name in listOf(KEY, TITLE, SUMMARY)) {
            assertTrue("$VALUES_XML: <string name=\"$name\"> is missing", name in strings)
        }
    }

    @Test
    fun ac11_onlyThePreferenceKeyIsNotTranslatable() {
        val strings = keepPositionStrings()

        assertEquals(
            "$VALUES_XML: $KEY must have translatable=\"false\"",
            "false",
            strings[KEY]?.getAttribute("translatable")
        )
        for (name in listOf(TITLE, SUMMARY)) {
            assertTrue("$VALUES_XML: <string name=\"$name\"> is missing", name in strings)
            assertNotEquals(
                "$VALUES_XML: $name is a text and must stay translatable",
                "false",
                strings[name]?.getAttribute("translatable")
            )
        }
    }

    // ---- reading the files ----

    /** `src/main/res/<path>` from `app/`, else `app/src/main/res/<path>` from the repository root. */
    private fun mainRes(path: String, whenMissing: String): File {
        val candidates = listOf("src/main/res", "app/src/main/res").map { File(it, path) }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$whenMissing; looked for " + candidates.joinToString(" and ") { it.absolutePath }
            )
    }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun Element.descendants(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID_NS, attribute)

    /** The text as Android shows it: whitespace collapsed. */
    private fun Element.text(): String = textContent.trim().replace(Regex("\\s+"), " ")

    private fun preferencesWithKey(keyReference: String): List<Element> =
        parse(mainRes(SETTINGS_XML, "res/$SETTINGS_XML not found"))
            .descendants()
            .filter { it.android("key") == keyReference }

    private fun keepPositionSwitch(): Element {
        val found = preferencesWithKey("@string/$KEY")
        assertEquals(
            "$SETTINGS_XML: preferences with key @string/$KEY (hook H9 adds exactly one)",
            1,
            found.size
        )
        return found.single()
    }

    /** The `<string>` elements of the feature's values file, by name. */
    private fun keepPositionStrings(): Map<String, Element> =
        parse(mainRes(VALUES_XML, "res/$VALUES_XML does not exist yet (plan section 5 adds it)"))
            .descendants()
            .filter { it.tagName == "string" }
            .associateBy { it.getAttribute("name") }
}
