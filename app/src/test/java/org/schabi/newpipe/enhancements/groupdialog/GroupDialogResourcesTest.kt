package org.schabi.newpipe.enhancements.groupdialog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The resources of plan section 3, read as plain XML; the Android resource system is not
 * involved.
 *
 * - gci AC-16 (kept by AC-1): all new texts are translatable and delivered in English. The five
 *   strings of `res/values/enhancements_group_dialog.xml`.
 * - AC-6: in `res/layout/enhancements_group_dialog_extras.xml` the *New group* button starts
 *   disabled (the session enables it through `showLoaded`, which a failed load never calls) and
 *   the load error starts hidden.
 *
 * Every test fails with "file not found" until its file exists.
 */
class GroupDialogResourcesTest {

    @Test
    fun gci_ac16_theFiveNewTextsExistWithTheirEnglishWording() {
        val file = locate(STRINGS_FILE)
        val found = descendants(parse(file))
            .filter { it.tagName == "string" && it.getAttribute("name") in PLANNED_TEXTS }
            .groupBy({ it.getAttribute("name") }, { it.textContent.trim().replace(Regex("\\s+"), " ") })

        assertEquals(
            "AC-16: ${file.path} must declare each planned string once, with its English text",
            PLANNED_TEXTS.mapValues { listOf(it.value) }.toSortedMap(),
            found.toSortedMap()
        )
    }

    @Test
    fun gci_ac16_noTextOfTheFileIsExcludedFromTranslation() {
        val file = locate(STRINGS_FILE)
        val root = parse(file)
        val untranslatable = (listOf(root) + descendants(root))
            .filter { it.getAttribute("translatable").trim().equals("false", ignoreCase = true) }
            .map { "<${it.tagName} name=\"${it.getAttribute("name")}\">" }

        assertEquals(
            "AC-16: all new texts are translatable; ${file.path} must not use translatable=\"false\"",
            emptyList<String>(),
            untranslatable
        )
    }

    /**
     * AC-6: "*New group* stays disabled" after a failed load. The button therefore is disabled
     * in the layout and only `showLoaded` enables it (plan E7; gci plan S2).
     */
    @Test
    fun ac6_theNewGroupButtonIsDisabledInTheLayout() {
        val file = locate(EXTRAS_LAYOUT)
        val button = theViewWithId(file, "enh_group_dialog_new_group_button")

        assertEquals("the New group view of ${file.path} is a Button", "Button", button.tagName)
        assertEquals(
            "AC-6: the New group button has android:enabled=\"false\" in ${file.path}",
            "false",
            button.getAttribute("android:enabled")
        )
        assertEquals(
            "gci AC-16: the button's text is the translatable string",
            "@string/enh_group_dialog_new_group",
            button.getAttribute("android:text")
        )
    }

    /** AC-6: the error is shown "instead of an empty list" only when the load failed; until then it is gone. */
    @Test
    fun ac6_theLoadErrorIsGoneInTheLayoutAndHasItsText() {
        val file = locate(EXTRAS_LAYOUT)
        val error = theViewWithId(file, "enh_group_dialog_load_error")

        assertEquals("the load error view of ${file.path} is a TextView", "TextView", error.tagName)
        assertEquals(
            "AC-6: the load error has android:visibility=\"gone\" in ${file.path}",
            "gone",
            error.getAttribute("android:visibility")
        )
        assertEquals(
            "AC-6, gci AC-16: the error's text is the translatable string",
            "@string/enh_group_dialog_load_failed",
            error.getAttribute("android:text")
        )
    }

    /** The extras are included into the dialog's own layout, so their root is a `<merge>` (plan section 3). */
    @Test
    fun ac6_theExtrasLayoutIsAMerge() {
        val file = locate(EXTRAS_LAYOUT)

        assertEquals("the root element of ${file.path}", "merge", parse(file).tagName)
    }

    private companion object {

        /** Paths below the `app` module (plan section 3). */
        const val STRINGS_FILE = "src/main/res/values/enhancements_group_dialog.xml"
        const val EXTRAS_LAYOUT = "src/main/res/layout/enhancements_group_dialog_extras.xml"

        /** Resource name to English text, as listed in plan section 3. */
        val PLANNED_TEXTS: Map<String, String> = mapOf(
            "enh_group_dialog_new_group" to "New group",
            "enh_group_dialog_add" to "Add",
            "enh_group_dialog_name_exists" to "A group with this name already exists",
            "enh_group_dialog_save_failed" to "Could not save. Nothing was changed.",
            "enh_group_dialog_load_failed" to "Could not load the groups."
        )

        /**
         * Gradle runs unit tests with `app/` as working directory; an IDE may use the repository
         * root. Fails the test when the file is in neither place.
         */
        fun locate(path: String): File {
            val candidates = listOf(File(path), File("app/$path"))
            return candidates.firstOrNull { it.isFile } ?: throw AssertionError(
                "file not found: plan section 3 puts it at app/$path. Looked for: " +
                    candidates.joinToString { it.absolutePath }
            )
        }

        /** The one element of [file] whose `android:id` is `@+id/<id>`; fails if there is none or several. */
        fun theViewWithId(file: File, id: String): Element {
            val root = parse(file)
            val found = (listOf(root) + descendants(root)).filter { it.getAttribute("android:id") == "@+id/$id" }
            assertEquals("${file.path} must have exactly one view with android:id=\"@+id/$id\"", 1, found.size)
            return found.single()
        }

        fun parse(file: File): Element =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

        fun descendants(root: Element): List<Element> {
            val nodes = root.getElementsByTagName("*")
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }
}
