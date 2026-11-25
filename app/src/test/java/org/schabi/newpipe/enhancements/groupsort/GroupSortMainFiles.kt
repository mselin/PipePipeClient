package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.fail
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads files of the main source set as text or XML, for the "resources" and "wiring" tests of
 * plan section 9. Nothing here needs Android: the files are read from disk.
 *
 * Gradle runs unit tests with `app/` as working directory, so a path is resolved against
 * `src/main/` first and against `app/src/main/` (repository root as working directory) second.
 */
internal object GroupSortMainFiles {

    const val VALUES = "res/values/enhancements_group_sort.xml"
    const val NOTICE_LAYOUT = "res/layout/enhancements_group_sort_notice.xml"
    const val APPEARANCE_SETTINGS = "res/xml/appearance_settings.xml"
    const val UPSTREAM_STRINGS = "res/values/strings.xml"
    const val UPSTREAM_SETTINGS_KEYS = "res/values/settings_keys.xml"

    const val FEED_DATABASE_MANAGER = "java/org/schabi/newpipe/local/feed/FeedDatabaseManager.kt"
    const val REORDER_DIALOG_VIEW_MODEL =
        "java/org/schabi/newpipe/local/subscription/dialog/FeedGroupReorderDialogViewModel.kt"
    const val REORDER_DIALOG = "java/org/schabi/newpipe/local/subscription/dialog/FeedGroupReorderDialog.kt"

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /** `src/main/<path>` if it is a file, else `app/src/main/<path>` (which may not exist either). */
    private fun locate(path: String): File {
        val fromAppDirectory = File("src/main", path)
        return if (fromAppDirectory.isFile) fromAppDirectory else File("app/src/main", path)
    }

    /**
     * The file `app/src/main/<path>`. Fails the test, naming [ac] and [missingMeans], when the
     * file is not there, which for a file of this feature means "not implemented yet".
     */
    fun file(ac: String, path: String, missingMeans: String): File {
        val file = locate(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist ($missingMeans); working directory ${File("").absolutePath}")
        }
        return file
    }

    fun lines(ac: String, path: String, missingMeans: String): List<String> =
        file(ac, path, missingMeans).readLines(Charsets.UTF_8)

    fun text(ac: String, path: String, missingMeans: String): String =
        file(ac, path, missingMeans).readText(Charsets.UTF_8)

    /** The file parsed as XML, with namespaces (for `android:` attributes). */
    fun xml(ac: String, path: String, missingMeans: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file(ac, path, missingMeans))
    }

    /** Every element of the document, in document order. */
    fun elementsOf(document: Document): List<Element> {
        val nodes = document.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The children of [parent] that are elements with tag [tag]. */
    fun childrenOf(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    /** The value of attribute `android:<name>`, or "" when the element does not have it. */
    fun androidAttribute(element: Element, name: String): String = element.getAttributeNS(ANDROID_NAMESPACE, name)

    /** The text of a `<string>` or `<item>`: trimmed, runs of whitespace as one space, as Android reads it. */
    fun textOf(element: Element): String = element.textContent.trim().replace(Regex("\\s+"), " ")
}
