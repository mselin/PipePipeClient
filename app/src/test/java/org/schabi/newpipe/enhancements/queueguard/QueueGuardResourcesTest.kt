package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The resources of plan section 4 and the settings entry S1 of plan section 2, read as XML with the
 * JDK's parser ("R" in plan section 5). No Android: the files are parsed where they lie in
 * `app/src/main/res`.
 *
 * Until the Code layer has written `res/values/enhancements_queue_guard.xml` and the S1 line, every test
 * here fails and its message names the missing file, string or element.
 */
class QueueGuardResourcesTest {

    // below app/src/main
    private val valuesPath = "res/values/enhancements_queue_guard.xml"
    private val settingsPath = "res/xml/video_audio_settings.xml"
    private val upstreamStringsPath = "res/values/strings.xml"

    // the same files as failure messages name them
    private val valuesFile = "app/src/main/$valuesPath"
    private val settingsFile = "app/src/main/$settingsPath"
    private val upstreamStringsFile = "app/src/main/$upstreamStringsPath"

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private val askKeyReference = "@string/enh_queue_guard_ask_key"

    /** Every `<string>` of plan section 4. */
    private val stringNames = listOf(
        "enh_queue_guard_ask_key",
        "enh_queue_guard_ask_title",
        "enh_queue_guard_ask_summary",
        "enh_queue_guard_add_to_queue",
        "enh_queue_guard_play_next",
        "enh_queue_guard_play_now",
        "enh_queue_guard_add_all",
        "enh_queue_guard_play_all_next",
        "enh_queue_guard_replace_queue",
    )

    /** Every `<plurals>` of plan section 4. */
    private val pluralNames = listOf("enh_queue_guard_queue_count", "enh_queue_guard_list_count")

    // ---- XML access ----

    private fun parse(file: File): Document {
        val factory = DocumentBuilderFactory.newDefaultInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element) result += node
            node = node.nextSibling
        }
        return result
    }

    private fun Element.descendantElements(): List<Element> =
        childElements().flatMap { listOf(it) + it.descendantElements() }

    private fun Element.nextElement(): Element? {
        var node: Node? = nextSibling
        while (node != null) {
            if (node is Element) return node
            node = node.nextSibling
        }
        return null
    }

    /** Text as Android reads an unquoted string resource: runs of whitespace are one space. */
    private fun Element.text(): String = textContent.trim().replace(Regex("\\s+"), " ")

    private fun Element.android(attribute: String): String = getAttributeNS(androidNs, attribute)

    /** Root `<resources>` of the feature's strings file. Fails while the file does not exist. */
    private fun values(): Element =
        parse(
            MainSources.file(valuesPath, "plan section 4 puts every string and plural of queue-guard into this file"),
        ).documentElement

    private fun named(tag: String, name: String): Element {
        val matches = values().childElements().filter { it.tagName == tag && it.getAttribute("name") == name }
        if (matches.size != 1) {
            fail(
                "$valuesFile must define <$tag name=\"$name\"> exactly once (plan section 4), " +
                    "found ${matches.size}",
            )
        }
        return matches.single()
    }

    /** English text of `<string name="[name]">`. */
    private fun string(name: String): String = named("string", name).text()

    /** Quantity -> English text of `<plurals name="[name]">`. */
    private fun plurals(name: String): Map<String, String> =
        named("plurals", name).childElements()
            .filter { it.tagName == "item" }
            .associate { it.getAttribute("quantity") to it.text() }

    /** English text of a string upstream already has and the feature reuses. */
    private fun upstreamString(name: String): String {
        val root = parse(MainSources.file(upstreamStringsPath, "upstream's strings")).documentElement
        val matches = root.childElements().filter { it.tagName == "string" && it.getAttribute("name") == name }
        assertEquals("upstream $upstreamStringsFile: <string name=\"$name\">", 1, matches.size)
        return matches.single().text()
    }

    private fun settingsScreen(): Element =
        parse(MainSources.file(settingsPath, "upstream's settings screen \"Player\"")).documentElement

    // ---- AC-2: the tap dialog ----

    /**
     * AC-2: "four choices: Add to queue, Play next, Play now (replaces the queue), Cancel". Three are new
     * strings (plan section 4); *Cancel* is the dialog's negative button with upstream's `cancel`, which
     * plan section 4 lists as reused. Its English text is checked in upstream's `strings.xml`.
     */
    @Test
    fun ac2_tapDialogChoicesAreDeliveredInEnglish() {
        assertEquals("Add to queue", string("enh_queue_guard_add_to_queue"))
        assertEquals("Play next", string("enh_queue_guard_play_next"))
        assertEquals("Play now (replaces the queue)", string("enh_queue_guard_play_now"))
        assertEquals("Cancel", upstreamString("cancel"))
    }

    /** AC-2 and AC-10: both dialog forms show "the number of items in the queue". */
    @Test
    fun ac2_ac10_queueCountIsDeliveredInSingularAndPlural() {
        assertEquals(
            mapOf("one" to "%d item in the queue", "other" to "%d items in the queue"),
            plurals("enh_queue_guard_queue_count"),
        )
    }

    // ---- AC-10: the list dialog ----

    /** AC-10: "four choices: Add all to queue, Play all next, Replace queue, Cancel". */
    @Test
    fun ac10_listDialogChoicesAreDeliveredInEnglish() {
        assertEquals("Add all to queue", string("enh_queue_guard_add_all"))
        assertEquals("Play all next", string("enh_queue_guard_play_all_next"))
        assertEquals("Replace queue", string("enh_queue_guard_replace_queue"))
        assertEquals("Cancel", upstreamString("cancel"))
    }

    /** AC-10: the list form "shows how many videos the list would add". */
    @Test
    fun ac10_listCountIsDeliveredInSingularAndPlural() {
        assertEquals(
            mapOf("one" to "%d video in this list", "other" to "%d videos in this list"),
            plurals("enh_queue_guard_list_count"),
        )
    }

    // ---- AC-15: the switch ----

    /** AC-15: a switch "Ask before replacing the queue". Summary and key as in plan section 4. */
    @Test
    fun ac15_switchHasItsTitleSummaryAndPreferenceKey() {
        assertEquals("Ask before replacing the queue", string("enh_queue_guard_ask_title"))
        assertEquals(
            "Tapping a video or a play button asks what to do with the queue that is playing. " +
                "A single video in the main player is replaced without asking",
            string("enh_queue_guard_ask_summary"),
        )
        assertEquals("enh_queue_guard_ask", string("enh_queue_guard_ask_key"))
    }

    /**
     * AC-15: the switch "sits directly below Preferred 'open' action (Settings > Player)". Hook S1: it is
     * the element right after the one with `@string/preferred_open_action_key`, and it is there once.
     */
    @Test
    fun ac15_switchSitsDirectlyBelowPreferredOpenAction() {
        val preferences = settingsScreen().descendantElements()
        val openAction = preferences.filter { it.android("key") == "@string/preferred_open_action_key" }
        assertEquals("$settingsFile: preferences with key @string/preferred_open_action_key", 1, openAction.size)

        val next = openAction.single().nextElement()
            ?: throw AssertionError("S1 is missing: nothing follows \"Preferred 'open' action\" in $settingsFile")
        assertEquals(
            "S1 is missing or misplaced: the element right after \"Preferred 'open' action\" in $settingsFile " +
                "must be the queue-guard switch",
            askKeyReference,
            next.android("key"),
        )
        assertEquals("S1 must be a switch", "SwitchPreferenceCompat", next.tagName)
        assertEquals("S1 title", "@string/enh_queue_guard_ask_title", next.android("title"))
        assertEquals("S1 summary", "@string/enh_queue_guard_ask_summary", next.android("summary"))
        assertEquals(
            "$settingsFile must hold the switch exactly once",
            1,
            preferences.count { it.android("key") == askKeyReference },
        )
    }

    /** AC-15: "It is on by default". The XML default and the code default must be the same value. */
    @Test
    fun ac15_switchIsOnByDefaultInTheSettingsScreen() {
        val switches = settingsScreen().descendantElements().filter { it.android("key") == askKeyReference }
        if (switches.size != 1) {
            fail(
                "S1 is missing: $settingsFile must hold one preference with key $askKeyReference, " +
                    "found ${switches.size}",
            )
        }
        val defaultValue = switches.single().android("defaultValue")
        assertEquals("android:defaultValue of S1", "true", defaultValue)
        assertEquals(
            "android:defaultValue of S1 and ASK_BEFORE_REPLACING_DEFAULT",
            ASK_BEFORE_REPLACING_DEFAULT.toString(),
            defaultValue,
        )
    }

    // ---- AC-18: translatable, English delivered ----

    /** Plan section 5, AC-18: "every name is in `values/`", each with a text. */
    @Test
    fun ac18_everyTextIsDeliveredInTheDefaultValuesFolder() {
        for (name in stringNames) {
            assertTrue("<string name=\"$name\"> must have a text", string(name).isNotEmpty())
        }
        for (name in pluralNames) {
            val forms = plurals(name)
            assertEquals("<plurals name=\"$name\">: quantities", setOf("one", "other"), forms.keys)
            assertTrue(
                "<plurals name=\"$name\">: every quantity must have a text",
                forms.values.all { it.isNotEmpty() },
            )
        }
    }

    /** AC-18: "All new texts are translatable". Plan section 5: "only the key is untranslatable". */
    @Test
    fun ac18_onlyThePreferenceKeyIsUntranslatable() {
        val untranslatable = values().childElements()
            .filter { it.getAttribute("translatable") == "false" }
            .map { it.getAttribute("name") }
        assertEquals(
            "resources marked translatable=\"false\" in $valuesFile",
            listOf("enh_queue_guard_ask_key"),
            untranslatable,
        )
    }

    /**
     * AC-18: "other languages fall back to English". No translation is delivered, so the file declares
     * that on its root element as upstream's `strings.xml` does (plan section 4):
     * `<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">`.
     */
    @Test
    fun ac18_missingTranslationsAreDeclaredOnTheRootElement() {
        val root = values()
        assertEquals("root element of $valuesFile", "resources", root.tagName)
        val ignored = root.getAttributeNS(toolsNs, "ignore").split(",").map { it.trim() }
        assertTrue(
            "root element of $valuesFile must carry tools:ignore=\"MissingTranslation\", has $ignored",
            "MissingTranslation" in ignored,
        )
    }
}
