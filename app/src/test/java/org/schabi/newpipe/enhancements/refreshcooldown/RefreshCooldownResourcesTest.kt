package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * AC-12, AC-8, AC-2 (plan section 8): the feature's own values file and its one layout, read as
 * XML. "All new texts are translatable. English is delivered; other languages fall back to
 * English."
 *
 * Until the Code layer writes the two files, every test here fails with "does not exist".
 *
 * Not shown here: that Android picks `one` for 1 and `other` for every other number in English
 * and formats the number (the test applies that rule itself), the texts on a screen, and the
 * fallback in another language (device steps 8 and 12).
 */
class RefreshCooldownResourcesTest {

    private val valuesFile = "values/enhancements_refresh_cooldown.xml"
    private val layoutFile = "layout/enhancements_refresh_cooldown_group_row.xml"
    private val android = "http://schemas.android.com/apk/res/android"
    private val app = "http://schemas.android.com/apk/res-auto"

    private fun root(): Element = Res.document("AC-12", valuesFile).documentElement
    private fun strings(): List<Element> = Res.elements(root(), "string")
    private fun string(name: String): Element =
        strings().singleOrNull { it.getAttribute("name") == name } ?: throw AssertionError("AC-12: app/src/main/res/$valuesFile must have one <string name=\"$name\">")

    /** What `getQuantityString(R.plurals.enh_refresh_cooldown_skipped, count, count)` gives in English: `one` for 1, `other` otherwise. */
    private fun skippedMessage(count: Int): String {
        val plural = Res.elements(root(), "plurals").single { it.getAttribute("name") == "enh_refresh_cooldown_skipped" }
        val quantity = if (count == 1) "one" else "other"
        return String.format(Res.elements(plural, "item").single { it.getAttribute("quantity") == quantity }.textContent, count)
    }

    /** AC-12, plan section 8: the file holds the six strings and the one plural of the plan, and nothing else. */
    @Test
    fun ac12_theValuesFileHasThePlansNames_andNoOther() {
        assertEquals("resources", root().tagName)
        assertEquals(
            listOf(
                "enh_refresh_cooldown_entry", "enh_refresh_cooldown_groups_key", "enh_refresh_cooldown_refresh_anyway",
                "enh_refresh_cooldown_subscriptions_key", "enh_refresh_cooldown_title", "enh_refresh_cooldown_use_default"
            ),
            strings().map { it.getAttribute("name") }.sorted()
        )
        assertEquals(listOf("enh_refresh_cooldown_skipped"), Res.elements(root(), "plurals").map { it.getAttribute("name") })
        val children = root().childNodes
        val tags = (0 until children.length).map { children.item(it) }.filterIsInstance<Element>().map { it.tagName }.toSet()
        assertEquals("only strings and plurals: no array, no bool, no integer", setOf("string", "plurals"), tags)
    }

    /** AC-12, plan section 8: the English texts. */
    @Test
    fun ac12_theEnglishTexts() {
        assertEquals("Refresh cooldown", string("enh_refresh_cooldown_title").textContent)
        assertEquals("Refresh cooldown: %s", string("enh_refresh_cooldown_entry").textContent)
        assertEquals("Use default", string("enh_refresh_cooldown_use_default").textContent)
        assertEquals("Refresh anyway", string("enh_refresh_cooldown_refresh_anyway").textContent)
    }

    /** AC-2, AC-3 ("shows the current value"): the entry text has exactly one `%s`, for the value's label. */
    @Test
    fun ac3_theEntryTextHasOnePlaceholderForTheValue() {
        val entry = string("enh_refresh_cooldown_entry").textContent

        assertEquals(1, Regex("%").findAll(entry).count())
        assertEquals(1, Regex("%s").findAll(entry).count())
        assertEquals("Refresh cooldown: Use default", String.format(entry, string("enh_refresh_cooldown_use_default").textContent))
        assertEquals("Refresh cooldown: 1 hour", String.format(entry, "1 hour"))
        for (name in listOf("enh_refresh_cooldown_title", "enh_refresh_cooldown_use_default", "enh_refresh_cooldown_refresh_anyway")) {
            assertFalse("$name has no placeholder", string(name).textContent.contains("%"))
        }
    }

    /** AC-12: every text is translatable; only the two preference keys are not. */
    @Test
    fun ac12_theTextsAreTranslatable_theTwoKeysAreNot() {
        for (element in strings()) {
            val name = element.getAttribute("name")
            if (name.endsWith("_key")) {
                assertEquals("$name is a key", "false", element.getAttribute("translatable"))
            } else {
                assertTrue("AC-12: $name must be translatable", element.getAttribute("translatable") != "false")
            }
        }
        val plural = Res.elements(root(), "plurals").single()
        assertTrue("AC-12: the plural must be translatable", plural.getAttribute("translatable") != "false")
    }

    /** AC-4, plan 3.1: the two preference keys have the literal values the storage format names. */
    @Test
    fun ac4_theTwoPreferenceKeys() {
        assertEquals("enh_refresh_cooldown_groups", string("enh_refresh_cooldown_groups_key").textContent)
        assertEquals("enh_refresh_cooldown_subscriptions", string("enh_refresh_cooldown_subscriptions_key").textContent)
        assertEquals("false", string("enh_refresh_cooldown_groups_key").getAttribute("translatable"))
        assertEquals("false", string("enh_refresh_cooldown_subscriptions_key").getAttribute("translatable"))
    }

    /**
     * AC-4, plan section 1 ("Backup"): the values travel with a backup of the settings.
     * `feature/backup-safety` holds back keys by a fixed list of names and tests unclassified
     * keys for these words; the two keys contain none, and are plain strings.
     */
    @Test
    fun ac4_theKeysDoNotLookLikeLoginData() {
        for (element in strings()) {
            for (word in listOf("cookie", "token", "password", "secret", "credential")) {
                assertFalse("${element.getAttribute("name")} must not contain `$word`", element.getAttribute("name").contains(word, ignoreCase = true))
                assertFalse("the value of ${element.getAttribute("name")} must not contain `$word`", element.textContent.contains(word, ignoreCase = true))
            }
        }
    }

    /** AC-8, AC-12, plan section 8: the plural has the quantities `one` and `other`, each with one `%d` for the count, and the plan's texts. */
    @Test
    fun ac8_thePluralHasOneAndOther_eachWithTheCount() {
        val plural = Res.elements(root(), "plurals").single { it.getAttribute("name") == "enh_refresh_cooldown_skipped" }
        val items = Res.elements(plural, "item").associate { it.getAttribute("quantity") to it.textContent }

        assertEquals("English needs `one` and `other`; no other quantity is delivered", setOf("one", "other"), items.keys)
        assertEquals(2, Res.elements(plural, "item").size)
        assertEquals("%d channel was skipped because of its cooldown", items["one"])
        assertEquals("%d channels were skipped because of their cooldown", items["other"])
        for ((quantity, text) in items) {
            assertEquals("`$quantity` has one placeholder", 1, Regex("%").findAll(text).count())
            assertEquals("`$quantity`: the placeholder is `%d`, the count", 1, Regex("%d").findAll(text).count())
        }
    }

    /**
     * AC-8, AC-10: the message for 0, 1 and many skipped channels. 0 gives no report
     * (`SkipReportsTest`), so no message; 1 is singular; 2 and 1600 are plural.
     */
    @Test
    fun ac8_theMessageForOneAndForMany() {
        assertEquals("1 channel was skipped because of its cooldown", skippedMessage(1))
        assertEquals("2 channels were skipped because of their cooldown", skippedMessage(2))
        assertEquals("21 channels were skipped because of their cooldown", skippedMessage(21))
        assertEquals("1600 channels were skipped because of their cooldown", skippedMessage(1600))

        // From a refresh to the text: one channel skipped, then many.
        val scope = (1..SUBSCRIPTIONS.toLong()).map { Channel(it) }
        val one = SkipReports().apply { publish(ALL, select(scope, FakeData(last = mapOf(5L to NOW)), 300).skipped, 0) }.take(ALL, 0)!!
        assertEquals("1 channel was skipped because of its cooldown", skippedMessage(one.count))
        val many = SkipReports().apply { publish(ALL, select(scope, FakeData(last = scope.drop(1).associate { it.id to NOW }), 300).skipped, 0) }.take(ALL, 0)!!
        assertEquals("1599 channels were skipped because of their cooldown", skippedMessage(many.count))
        val none = SkipReports().apply { publish(ALL, select(scope, FakeData(), 300).skipped, 0) }.take(ALL, 0)
        assertEquals("AC-10: nothing skipped: no report, no message", null, none)
    }

    /** AC-12: "English is delivered; other languages fall back to English": the file exists in `values/` only. */
    @Test
    fun ac12_noTranslationOfTheFileIsDelivered() {
        root()
        val res = Sources.main("res")
        val translated = res.listFiles()!!.filter { it.isDirectory && it.name.startsWith("values-") }
        assertTrue("the translations are where the test looks", translated.size > 20)
        assertEquals(
            emptyList<String>(),
            translated.filter { dir -> dir.listFiles()!!.any { it.name.contains("refresh_cooldown") } }.map { it.name }
        )
        assertEquals(
            "and no translated file defines one of the names",
            emptyList<String>(),
            translated.flatMap { it.listFiles()!!.toList() }.filter { it.extension == "xml" && it.readText().contains("enh_refresh_cooldown") }.map { it.path }
        )
    }

    /** AC-12: the new names are defined once: upstream's `strings.xml` and `settings_keys.xml` do not define or mention them. */
    @Test
    fun ac12_theNamesAreDefinedInTheFeaturesFileOnly() {
        root()
        for (path in listOf("values/strings.xml", "values/settings_keys.xml")) {
            assertEquals("app/src/main/res/$path", 0, Sources.count(Sources.lines("AC-12", "res/$path").joinToString("\n"), "enh_refresh_cooldown"))
        }
    }

    /**
     * AC-2, plan section 8: the row of the group dialog: one borderless `Button` with the
     * plan's id, 48 dp high, as wide as its constraints, placed below the "n selected" line of
     * `dialog_feed_group_create.xml`.
     */
    @Test
    fun ac2_theGroupRowLayout() {
        val button = Res.document("AC-2", layoutFile).documentElement

        assertEquals("Button", button.tagName)
        assertEquals("the layout is the one view", 0, (0 until button.childNodes.length).map { button.childNodes.item(it) }.filterIsInstance<Element>().size)
        assertEquals("@+id/enh_refresh_cooldown_group_button", button.getAttributeNS(android, "id"))
        assertEquals("@style/Widget.AppCompat.Button.Borderless", button.getAttribute("style"))
        assertEquals("0dp", button.getAttributeNS(android, "layout_width"))
        assertEquals("plan 7.1: the dialog grows by these 48 dp", "48dp", button.getAttributeNS(android, "layout_height"))
        assertEquals("8dp", button.getAttributeNS(android, "layout_marginStart"))
        assertEquals("8dp", button.getAttributeNS(android, "layout_marginEnd"))
        assertEquals("false", button.getAttributeNS(android, "textAllCaps"))
        assertEquals("parent", button.getAttributeNS(app, "layout_constraintStart_toStartOf"))
        assertEquals("parent", button.getAttributeNS(app, "layout_constraintEnd_toEndOf"))
        assertEquals("@id/selected_subscription_count_view", button.getAttributeNS(app, "layout_constraintTop_toBottomOf"))
    }

    /** AC-2, plan section 8: the dialog's own layout is not edited, and still has what the row is placed between. */
    @Test
    fun ac2_theDialogsLayoutIsAsItWas() {
        val text = Sources.lines("AC-2", "res/layout/dialog_feed_group_create.xml").joinToString("\n")

        assertEquals(0, Sources.count(text, "refresh_cooldown"))
        assertEquals(0, Sources.count(text, "ENH:"))
        assertEquals(1, Sources.count(text, "android:id=\"@+id/options_root\""))
        assertEquals(1, Sources.count(text, "android:id=\"@+id/selected_subscription_count_view\""))
        assertEquals(1, Sources.count(text, "android:id=\"@+id/select_channel_button\""))
        assertEquals("plan 7.1: the fixed height the row adds 48 dp to", 1, Sources.count(text, "android:layout_height=\"180dp\""))
        assertEquals("\"Select subscriptions\" is constrained below the count line (the code moves it below the row)", 1, Sources.count(text, "app:layout_constraintTop_toBottomOf=\"@+id/selected_subscription_count_view\""))
    }
}
