package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The resources of plan section 5 and the two settings entries S2 and S3 of plan section 3, read as XML
 * with the JDK's parser ("R" in plan section 6). No Android: the files are parsed where they lie in
 * `app/src/main/res`.
 *
 * Four files are read: this feature's `res/values/enhancements_default_open_action.xml` and
 * `res/layout/enhancements_default_open_action_remember.xml`, queue-guard's
 * `res/values/enhancements_queue_guard.xml` (two of the default action's labels are queue-guard's
 * strings) and upstream's `res/xml/video_audio_settings.xml` (the settings screen "Player").
 *
 * Until the Code layers of queue-guard and of this feature have written those files and lines, every test
 * here fails, and its message names the missing file, string or element.
 *
 * Amendment 2 (spec AM-1, plan B2.5): one new string, the notice after a remembered choice, with three
 * places for names that come from the app's own strings (upstream's `res/values/strings.xml`, read here as
 * a fifth file, and queue-guard's values file). The tests `am1_...` are about it.
 */
class DefaultOpenActionResourcesTest {

    // below app/src/main
    private val valuesPath = "res/values/enhancements_default_open_action.xml"
    private val layoutPath = "res/layout/enhancements_default_open_action_remember.xml"
    private val queueGuardValuesPath = "res/values/enhancements_queue_guard.xml"
    private val settingsPath = "res/xml/video_audio_settings.xml"
    private val upstreamStringsPath = "res/values/strings.xml"
    private val mainSettingsPath = "res/xml/main_settings.xml"

    // the same files as failure messages name them
    private val valuesFile = "app/src/main/$valuesPath"
    private val layoutFile = "app/src/main/$layoutPath"
    private val queueGuardValuesFile = "app/src/main/$queueGuardValuesPath"
    private val settingsFile = "app/src/main/$settingsPath"

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val toolsNs = "http://schemas.android.com/tools"

    private val askKeyReference = "@string/enh_queue_guard_ask_key"
    private val actionKeyReference = "@string/enh_default_open_action_key"
    private val startInKeyReference = "@string/enh_default_open_action_start_in_key"

    /** Every text this feature adds (plan section 5), with its English wording. */
    private val englishTexts = mapOf(
        "enh_default_open_action_title" to "Default action when tapping a video",
        "enh_default_open_action_play_now" to "Play now",
        "enh_default_open_action_start_in_title" to "Start playback in",
        "enh_default_open_action_start_in_video_page" to "Video page",
        "enh_default_open_action_start_in_background" to "Background player",
        "enh_default_open_action_start_in_popup" to "Popup player",
        "enh_default_open_action_remember" to "Remember my choice",
    )

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

    // ---- the values files ----

    /** Root `<resources>` of this feature's values file. Fails while the file does not exist. */
    private fun values(): Element =
        parse(
            MainFiles.file(
                valuesPath,
                "plan section 5 puts every string and string-array of default-open-action into this file",
            ),
        ).documentElement

    /** Root `<resources>` of queue-guard's values file. Fails while queue-guard's Code layer has not written it. */
    private fun queueGuardValues(): Element =
        parse(
            MainFiles.file(
                queueGuardValuesPath,
                "queue-guard's strings (QG plan 4); queue-guard's code has to be on the branch first " +
                    "(plan section 7, step 0)",
            ),
        ).documentElement

    private fun Element.childrenNamed(tag: String, name: String): List<Element> =
        childElements().filter { it.tagName == tag && it.getAttribute("name") == name }

    private fun named(tag: String, name: String): Element {
        val matches = values().childrenNamed(tag, name)
        if (matches.size != 1) {
            fail(
                "$valuesFile must define <$tag name=\"$name\"> exactly once (plan section 5), " +
                    "found ${matches.size}",
            )
        }
        return matches.single()
    }

    /** English text of `<string name="[name]">` in this feature's values file. */
    private fun string(name: String): String = named("string", name).text()

    /** The items of `<string-array name="[name]">` in this feature's values file, as written. */
    private fun stringArray(name: String): List<String> =
        named("string-array", name).childElements().filter { it.tagName == "item" }.map { it.text() }

    /**
     * English text behind [reference], a `@string/...` of this feature or of queue-guard (names starting
     * with `enh_queue_guard_`). Fails if [reference] is literal text: AC-16 wants every text translatable
     * on its own, as a `<string>`.
     */
    private fun resolve(reference: String): String {
        if (!reference.startsWith("@string/")) {
            fail("\"$reference\" must be a @string/ reference to a <string> in values/ (plan section 5)")
        }
        val name = reference.removePrefix("@string/")
        if (!name.startsWith("enh_queue_guard_")) {
            return string(name)
        }
        val matches = queueGuardValues().childrenNamed("string", name)
        if (matches.size != 1) {
            fail(
                "$queueGuardValuesFile must define <string name=\"$name\"> exactly once (QG plan 4), " +
                    "found ${matches.size}",
            )
        }
        return matches.single().text()
    }

    // ---- the settings screen ----

    private fun preferences(): List<Element> =
        parse(MainFiles.file(settingsPath, "upstream's settings screen \"Player\"")).documentElement
            .descendantElements()

    /** The one preference with `android:key="[keyReference]"`. Fails with [missing] if there is none or several. */
    private fun preference(keyReference: String, missing: String): Element {
        val matches = preferences().filter { it.android("key") == keyReference }
        if (matches.size != 1) {
            fail(
                "$missing: $settingsFile must hold exactly one preference with key $keyReference, " +
                    "found ${matches.size}",
            )
        }
        return matches.single()
    }

    private val s1Missing =
        "queue-guard's switch (its hook S1) is missing, queue-guard's code has to be on the branch first"
    private val s2Missing = "S2 is missing"
    private val s3Missing = "S3 is missing"

    private fun defaultActionSetting(): Element = preference(actionKeyReference, s2Missing)

    private fun startPlaybackInSetting(): Element = preference(startInKeyReference, s3Missing)

    // ---- the checkbox layout ----

    private fun checkBoxes(): List<Element> {
        val root = parse(
            MainFiles.file(
                layoutPath,
                "plan section 5: the layout with the checkbox \"Remember my choice\" for queue-guard's tap dialog",
            ),
        ).documentElement
        return (listOf(root) + root.descendantElements()).filter { it.tagName == "CheckBox" }
    }

    private fun checkBox(): Element {
        val boxes = checkBoxes()
        if (boxes.size != 1) {
            fail("$layoutFile must hold exactly one <CheckBox> (plan section 5), found ${boxes.size}")
        }
        return boxes.single()
    }

    // =============================================================================================
    // AC-1: the setting "Default action when tapping a video" (S2)
    // =============================================================================================

    /**
     * AC-1: the setting "sits with the queue-guard switch (Settings > Player)". Plan section 6: "S2 is the
     * element right after the one with `@string/enh_queue_guard_ask_key`". It is there once.
     */
    @Test
    fun ac1_hookS2_defaultActionSettingSitsDirectlyBelowTheAskSwitch() {
        val next = preference(askKeyReference, s1Missing).nextElement()
            ?: throw AssertionError("$s2Missing: nothing follows queue-guard's switch in $settingsFile")
        assertEquals(
            "S2 is missing or misplaced: the element right after queue-guard's switch in $settingsFile must be " +
                "the default action setting",
            actionKeyReference,
            next.android("key"),
        )
        assertEquals(
            "$settingsFile must hold the default action setting exactly once",
            1,
            preferences().count { it.android("key") == actionKeyReference },
        )
    }

    /** Plan section 3, S2: a list preference with the title, the labels and the stored values of plan section 5. */
    @Test
    fun ac1_hookS2_isAListWithItsTitleItsEntriesAndItsValues() {
        val setting = defaultActionSetting()
        assertEquals("S2 must be a list", "ListPreference", setting.tagName)
        assertEquals("S2 title", "@string/enh_default_open_action_title", setting.android("title"))
        assertEquals("S2 entries", "@array/enh_default_open_action_entries", setting.android("entries"))
        assertEquals("S2 entryValues", "@array/enh_default_open_action_values", setting.android("entryValues"))
    }

    /** AC-1: "*Play now* (default)". The XML default and the code default must be the same value. */
    @Test
    fun ac1_hookS2_defaultValueIsPlayNow() {
        val defaultValue = defaultActionSetting().android("defaultValue")
        assertEquals("android:defaultValue of S2", "play_now", defaultValue)
        assertEquals(
            "android:defaultValue of S2 and DefaultTapAction.DEFAULT.prefValue",
            DefaultTapAction.DEFAULT.prefValue,
            defaultValue,
        )
    }

    /** AC-1: "Its summary shows the selected value." A list preference does that with the summary `%s`. */
    @Test
    fun ac1_hookS2_summaryShowsTheSelectedValue() {
        assertEquals("android:summary of S2", "%s", defaultActionSetting().android("summary"))
    }

    /** AC-1: the setting is called "Default action when tapping a video". Its preference key is plan section 5's. */
    @Test
    fun ac1_settingHasItsTitleAndItsPreferenceKey() {
        assertEquals("Default action when tapping a video", string("enh_default_open_action_title"))
        assertEquals("enh_default_open_action", string("enh_default_open_action_key"))
    }

    /** Plan section 6, AC-1: "the values array = `DefaultTapAction.values().map { it.prefValue }`". */
    @Test
    fun ac1_storedValuesAreThePreferenceValuesOfDefaultTapActionInItsOrder() {
        val stored = stringArray("enh_default_open_action_values")
        assertEquals(listOf("play_now", "add_to_queue", "play_next"), stored)
        assertEquals(
            "<string-array name=\"enh_default_open_action_values\"> and DefaultTapAction.values()",
            DefaultTapAction.values().map { it.prefValue },
            stored,
        )
    }

    /**
     * AC-1: "Values: Play now (default), Add to queue, Play next", in the order of the stored values.
     * AC-3 speaks of "the queue-guard dialog choice of the same name": plan section 5 takes the last two
     * labels from queue-guard's dialog strings, so the names cannot drift apart.
     */
    @Test
    fun ac1_ac3_entriesArePlayNowAndQueueGuardsAddToQueueAndPlayNext() {
        val entries = stringArray("enh_default_open_action_entries")
        assertEquals(
            listOf(
                "@string/enh_default_open_action_play_now",
                "@string/enh_queue_guard_add_to_queue",
                "@string/enh_queue_guard_play_next",
            ),
            entries,
        )
        assertEquals(listOf("Play now", "Add to queue", "Play next"), entries.map { resolve(it) })
    }

    // =============================================================================================
    // AC-2: the setting "Start playback in" (S3)
    // =============================================================================================

    /** AC-2: the setting "sits beside it". Plan section 6: "S3 is the element right after S2". It is there once. */
    @Test
    fun ac2_hookS3_startPlaybackInSitsDirectlyBelowTheDefaultActionSetting() {
        val next = defaultActionSetting().nextElement()
            ?: throw AssertionError("$s3Missing: nothing follows the default action setting in $settingsFile")
        assertEquals(
            "S3 is missing or misplaced: the element right after the default action setting in $settingsFile " +
                "must be \"Start playback in\"",
            startInKeyReference,
            next.android("key"),
        )
        assertEquals(
            "$settingsFile must hold \"Start playback in\" exactly once",
            1,
            preferences().count { it.android("key") == startInKeyReference },
        )
    }

    /** Plan section 3, S3: a list preference with the title, the labels and the stored values of plan section 5. */
    @Test
    fun ac2_hookS3_isAListWithItsTitleItsEntriesAndItsValues() {
        val setting = startPlaybackInSetting()
        assertEquals("S3 must be a list", "ListPreference", setting.tagName)
        assertEquals("S3 title", "@string/enh_default_open_action_start_in_title", setting.android("title"))
        assertEquals("S3 entries", "@array/enh_default_open_action_start_in_entries", setting.android("entries"))
        assertEquals(
            "S3 entryValues",
            "@array/enh_default_open_action_start_in_values",
            setting.android("entryValues"),
        )
    }

    /** AC-2: "*Video page* (default)". The XML default and the code default must be the same value. */
    @Test
    fun ac2_hookS3_defaultValueIsVideoPage() {
        val defaultValue = startPlaybackInSetting().android("defaultValue")
        assertEquals("android:defaultValue of S3", "video_page", defaultValue)
        assertEquals(
            "android:defaultValue of S3 and StartPlaybackIn.DEFAULT.prefValue",
            StartPlaybackIn.DEFAULT.prefValue,
            defaultValue,
        )
    }

    /** AC-2: "Its summary shows the selected value." */
    @Test
    fun ac2_hookS3_summaryShowsTheSelectedValue() {
        assertEquals("android:summary of S3", "%s", startPlaybackInSetting().android("summary"))
    }

    /** AC-2: the setting is called "Start playback in". Its preference key is plan section 5's. */
    @Test
    fun ac2_settingHasItsTitleAndItsPreferenceKey() {
        assertEquals("Start playback in", string("enh_default_open_action_start_in_title"))
        assertEquals("enh_default_open_action_start_in", string("enh_default_open_action_start_in_key"))
    }

    /** Plan section 6, AC-2: "values array = the enum's `prefValue`s". */
    @Test
    fun ac2_storedValuesAreThePreferenceValuesOfStartPlaybackInInItsOrder() {
        val stored = stringArray("enh_default_open_action_start_in_values")
        assertEquals(listOf("video_page", "background_player", "popup_player"), stored)
        assertEquals(
            "<string-array name=\"enh_default_open_action_start_in_values\"> and StartPlaybackIn.values()",
            StartPlaybackIn.values().map { it.prefValue },
            stored,
        )
    }

    /** AC-2: "Values: Video page (default), Background player, Popup player", in the order of the stored values. */
    @Test
    fun ac2_entriesAreVideoPageBackgroundPlayerAndPopupPlayer() {
        val entries = stringArray("enh_default_open_action_start_in_entries")
        assertEquals(
            listOf(
                "@string/enh_default_open_action_start_in_video_page",
                "@string/enh_default_open_action_start_in_background",
                "@string/enh_default_open_action_start_in_popup",
            ),
            entries,
        )
        assertEquals(listOf("Video page", "Background player", "Popup player"), entries.map { resolve(it) })
    }

    // =============================================================================================
    // AC-7: the checkbox "Remember my choice"
    // =============================================================================================

    /** Plan section 6, AC-7: "the layout has exactly one `CheckBox`". */
    @Test
    fun ac7_layoutHasExactlyOneCheckBox() {
        assertEquals("<CheckBox> elements in $layoutFile", 1, checkBoxes().size)
    }

    /**
     * AC-7: a checkbox "Remember my choice". Plan section 6: "its text is
     * `@string/enh_default_open_action_remember`".
     */
    @Test
    fun ac7_checkBoxIsLabelledRememberMyChoice() {
        assertEquals(
            "android:text of the checkbox",
            "@string/enh_default_open_action_remember",
            checkBox().android("text"),
        )
        assertEquals("Remember my choice", string("enh_default_open_action_remember"))
    }

    /** AC-7: "unchecked every time the dialog opens". Plan section 6: "no `android:checked`". */
    @Test
    fun ac7_checkBoxIsUncheckedInTheLayout() {
        assertFalse(
            "the checkbox in $layoutFile must not carry android:checked",
            checkBox().hasAttributeNS(androidNs, "checked"),
        )
    }

    /**
     * AC-7: "unchecked every time the dialog opens". Plan section 5: `android:saveEnabled="false"`, so that
     * no saved view state can tick the box of a new dialog.
     */
    @Test
    fun ac7_checkBoxDoesNotRestoreAnEarlierState() {
        assertEquals("android:saveEnabled of the checkbox", "false", checkBox().android("saveEnabled"))
    }

    /**
     * Plan section 5 and 4.4: the add-on reads the box by `R.id.enh_default_open_action_remember` at the
     * moment of the choice (AC-8).
     */
    @Test
    fun ac7_ac8_checkBoxHasTheIdTheAddOnReadsItBy() {
        assertEquals("android:id of the checkbox", "@+id/enh_default_open_action_remember", checkBox().android("id"))
    }

    // =============================================================================================
    // AC-16: translatable, English delivered
    // =============================================================================================

    /** Plan section 6, AC-16: "every text is a `<string>` in `values/`". English is what is delivered. */
    @Test
    fun ac16_everyTextIsAStringInTheDefaultValuesFolderWithItsEnglishWording() {
        for ((name, english) in englishTexts) {
            assertEquals("<string name=\"$name\"> in $valuesFile", english, string(name))
        }
    }

    /**
     * AC-16: "All new texts are translatable." The labels of both lists are references to `<string>`s, not
     * text written into the arrays, and each reference leads to a text.
     */
    @Test
    fun ac16_labelsOfBothListsAreReferencesToStrings() {
        val labels =
            stringArray("enh_default_open_action_entries") + stringArray("enh_default_open_action_start_in_entries")
        assertEquals("labels of the two lists", 6, labels.size)
        for (label in labels) {
            assertTrue("$label must lead to a text", resolve(label).isNotEmpty())
        }
    }

    /**
     * Plan section 6, AC-16: "only the two keys and the two value arrays are `translatable=\"false\"`".
     * Every other resource of the file is left to the translators.
     */
    @Test
    fun ac16_onlyTheTwoKeysAndTheTwoValueArraysAreUntranslatable() {
        val untranslatable = values().childElements()
            .filter { it.getAttribute("translatable") == "false" }
            .map { "${it.tagName} ${it.getAttribute("name")}" }
            .toSet()
        assertEquals(
            "resources marked translatable=\"false\" in $valuesFile",
            setOf(
                "string enh_default_open_action_key",
                "string-array enh_default_open_action_values",
                "string enh_default_open_action_start_in_key",
                "string-array enh_default_open_action_start_in_values",
            ),
            untranslatable,
        )
    }

    /**
     * AC-16: "other languages fall back to English". No translation is delivered, so the file declares
     * that on its root element as queue-guard's file does (plan section 5):
     * `<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">`.
     */
    @Test
    fun ac16_missingTranslationsAreDeclaredOnTheRootElement() {
        val root = values()
        assertEquals("root element of $valuesFile", "resources", root.tagName)
        val ignored = root.getAttributeNS(toolsNs, "ignore").split(",").map { it.trim() }
        assertTrue(
            "root element of $valuesFile must carry tools:ignore=\"MissingTranslation\", has $ignored",
            "MissingTranslation" in ignored,
        )
    }

    // =============================================================================================
    // AM-1: the notice "asking is now off" (plan B2.5)
    // =============================================================================================

    private val noticeName = "enh_default_open_action_asking_off"

    /** English text of `<string name="[name]">` in upstream's `res/values/strings.xml`; fails if it is missing. */
    private fun upstreamString(name: String): String {
        val root = parse(MainFiles.file(upstreamStringsPath, "upstream's English strings")).documentElement
        val matches = root.childrenNamed("string", name)
        if (matches.size != 1) {
            fail(
                "app/src/main/$upstreamStringsPath must define <string name=\"$name\"> exactly once: the " +
                    "notice of AM-1 is filled from it (plan B2.4), found ${matches.size}",
            )
        }
        return matches.single().text()
    }

    /** Fills `%1$s`, `%2$s`, `%3$s` of [template] as `Context.getString(id, a, b, c)` does. */
    private fun fill(template: String, first: String, second: String, third: String): String =
        String.format(template, first, second, third)

    /**
     * AM-1, plan B2.5: the string `enh_default_open_action_asking_off` exists once in this feature's values
     * file with the English text "Asking is now off. To switch it on again: %1$s > %2$s > %3$s" (read
     * through the XML parser, so `&gt;` in the file is `>` here).
     */
    @Test
    fun am1_noticeStringHasItsNameAndItsEnglishText() {
        assertEquals(
            "<string name=\"$noticeName\"> in $valuesFile",
            "Asking is now off. To switch it on again: %1\$s > %2\$s > %3\$s",
            string(noticeName),
        )
    }

    /**
     * AM-1, plan B2.5 and B2.6: "the string's text and its three placeholders". Each of `%1$s`, `%2$s` and
     * `%3$s` occurs exactly once, in that order, and there is no other format specifier: `getString` with
     * three arguments (plan B2.4) fills every place and nothing is left over.
     */
    @Test
    fun am1_noticeStringHasExactlyTheThreePositionalPlaceholdersInOrder() {
        val text = string(noticeName)
        val specifiers = Regex("%(?:(\\d+)\\$)?[a-zA-Z%]").findAll(text).map { it.value }.toList()
        assertEquals(
            "format specifiers of <string name=\"$noticeName\"> in $valuesFile",
            listOf("%1\$s", "%2\$s", "%3\$s"),
            specifiers,
        )
        assertEquals("'%' signs in \"$text\"", 3, text.count { it == '%' })
    }

    /**
     * AM-1 with AC-16: the notice is a text for the translators like every other one. It is a plain
     * `<string>` without `translatable="false"` and without `formatted="false"`, which would stop Android
     * from treating the placeholders as such.
     */
    @Test
    fun am1_ac16_noticeStringIsTranslatable() {
        val element = named("string", noticeName)
        assertFalse(
            "<string name=\"$noticeName\"> must not be translatable=\"false\"",
            element.getAttribute("translatable") == "false",
        )
        assertFalse(
            "<string name=\"$noticeName\"> must not be formatted=\"false\"",
            element.getAttribute("formatted") == "false",
        )
    }

    /**
     * AM-1, plan B2.4: the three places are filled from `R.string.settings`,
     * `R.string.settings_category_player_title` (both upstream's) and `R.string.enh_queue_guard_ask_title`
     * (queue-guard's): "the screen's and the switch's own strings ..., so the message stays right in every
     * language and after a rename". All three exist, each exactly once, with a text.
     */
    @Test
    fun am1_theThreeAppStringsTheNoticeIsFilledFromExist() {
        assertTrue("upstream's <string name=\"settings\"> has a text", upstreamString("settings").isNotEmpty())
        assertTrue(
            "upstream's <string name=\"settings_category_player_title\"> has a text",
            upstreamString("settings_category_player_title").isNotEmpty(),
        )
        assertTrue(
            "queue-guard's <string name=\"enh_queue_guard_ask_title\"> has a text",
            resolve("@string/enh_queue_guard_ask_title").isNotEmpty(),
        )
    }

    /**
     * AM-1: the message "says that asking is now off and where to switch it on again (Settings > Player)".
     * Plan B2.5: "With the English strings it reads: "Asking is now off. To switch it on again: Settings >
     * Player > Ask before replacing the queue"" (questions.md Q10/A10: accepted with that text).
     */
    @Test
    fun am1_filledWithTheEnglishStringsTheNoticeNamesSettingsPlayerAndTheSwitch() {
        val filled = fill(
            string(noticeName),
            upstreamString("settings"),
            upstreamString("settings_category_player_title"),
            resolve("@string/enh_queue_guard_ask_title"),
        )
        assertEquals(
            "the notice in English",
            "Asking is now off. To switch it on again: Settings > Player > Ask before replacing the queue",
            filled,
        )
    }

    /**
     * AM-1, "where to switch it on again": the three names are those of the path the user walks. Plan B2.9
     * ("Checked in source"): `R.string.settings` and `R.string.settings_category_player_title` "are the
     * titles of `res/xml/main_settings.xml`", i.e. of the settings screen and of its entry that opens the
     * Player screen (`VideoAudioSettingsFragment`); and the third is the title of queue-guard's switch on
     * that screen (`res/xml/video_audio_settings.xml`). If upstream renames a screen by pointing it to
     * another string, this test says that the notice has to follow.
     */
    @Test
    fun am1_theThreeNamesAreTheTitlesOfTheSettingsScreenThePlayerScreenAndTheSwitch() {
        val mainSettings =
            parse(MainFiles.file(mainSettingsPath, "upstream's top settings screen")).documentElement
        assertEquals(
            "android:title of the root of app/src/main/$mainSettingsPath",
            "@string/settings",
            mainSettings.android("title"),
        )
        val playerEntries = mainSettings.descendantElements()
            .filter { it.android("fragment") == "org.schabi.newpipe.settings.VideoAudioSettingsFragment" }
        assertEquals("entries of app/src/main/$mainSettingsPath that open the Player screen", 1, playerEntries.size)
        assertEquals(
            "android:title of the entry that opens the Player screen",
            "@string/settings_category_player_title",
            playerEntries.single().android("title"),
        )

        val switches = preferences().filter { it.android("key") == askKeyReference }
        assertEquals("queue-guard's switch in $settingsFile", 1, switches.size)
        assertEquals(
            "android:title of queue-guard's switch in $settingsFile",
            "@string/enh_queue_guard_ask_title",
            switches.single().android("title"),
        )
    }
}
