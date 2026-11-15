package org.schabi.newpipe.enhancements.groupcreateinline

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AC-16: all new texts are translatable and delivered in English.
 *
 * Reads the strings file of plan section 6 as plain XML; the Android resource system is not
 * involved. Both tests fail with "strings file not found" until the file exists.
 */
class GroupCreateInlineResourcesTest {

    @Test
    fun ac16_theFourNewTextsExistWithTheirEnglishWording() {
        assertPlannedTexts(locateStringsFile())
    }

    @Test
    fun ac16_noTextOfTheFileIsExcludedFromTranslation() {
        assertNothingUntranslatable(locateStringsFile())
    }

    /** The checks, each for a given file. */
    companion object {

        /** Path of the strings file below the `app` module (plan section 6). */
        const val STRINGS_FILE = "src/main/res/values/enhancements_group_create_inline.xml"

        /** Resource name to English text, as in the table of plan section 6. */
        val PLANNED_TEXTS: Map<String, String> = mapOf(
            "enh_group_create_inline_new_group" to "New group",
            "enh_group_create_inline_add" to "Add",
            "enh_group_create_inline_name_exists" to "A group with this name already exists",
            "enh_group_create_inline_save_failed" to "Could not save. No group was created."
        )

        /**
         * Gradle runs unit tests with `app/` as working directory; an IDE may use the repository
         * root. Fails the test when the file is in neither place.
         */
        fun locateStringsFile(): File {
            val candidates = listOf(File(STRINGS_FILE), File("app/$STRINGS_FILE"))
            return candidates.firstOrNull { it.isFile } ?: throw AssertionError(
                "AC-16: strings file not found. Plan section 6 puts the new texts in " +
                    "app/$STRINGS_FILE. Looked for: " + candidates.joinToString { it.absolutePath }
            )
        }

        /**
         * The four names of plan section 6 are `<string>` resources of [file], each declared
         * once and with exactly the planned English text. Whitespace around the text and line
         * breaks inside it do not count, as for Android. Other strings of the file are not
         * looked at.
         */
        fun assertPlannedTexts(file: File) {
            val found = descendants(parse(file))
                .filter { it.tagName == "string" && it.getAttribute("name") in PLANNED_TEXTS }
                .groupBy({ it.getAttribute("name") }, { it.textContent.trim().replace(Regex("\\s+"), " ") })
            assertEquals(
                "AC-16: ${file.path} must declare each planned string once, with its English text",
                PLANNED_TEXTS.mapValues { listOf(it.value) }.toSortedMap(),
                found.toSortedMap()
            )
        }

        /** No element of [file] carries `translatable="false"`. */
        fun assertNothingUntranslatable(file: File) {
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

        private fun parse(file: File): Element =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

        private fun descendants(root: Element): List<Element> {
            val nodes = root.getElementsByTagName("*")
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }
}
