package org.schabi.newpipe.enhancements.groupdialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AC-8 and the defect behind AC-2, AC-3 and AC-7 (plan section 1, D2), checked on the source
 * files as text (plan section 7). The wiring of the dialog cannot be run on the JVM; what can be
 * checked is that the take-over and the old save are gone from it.
 *
 * `FGSD` is `local/subscription/dialog/FeedGroupSelectionDialog.java`. The search is plain text,
 * comments included: the plan says the file "contains none of" these.
 *
 * Until the code layer has edited the dialog, every test fails on what is still there.
 *
 * Assumptions, see Q7 in `enhancements/group-dialog/questions.md`: the three traces of the
 * take-over are one test, because two of them were never in the dialog's own file; the old
 * resource files are checked as well (plan section 3), though section 7 does not list them.
 */
class GroupDialogSourceTest {

    /**
     * AC-8: "the runtime take-over of group-create-inline (replaced OK listener, wrapped adapter,
     * load inferred from adapter notifications) is gone". In the source that is the package it
     * lived in, the `ConcatAdapter` that wrapped the dialog's adapter and the
     * `registerAdapterDataObserver` that inferred the load.
     */
    @Test
    fun ac8_theDialogSourceHasNoTraceOfTheRuntimeTakeOver() {
        assertEquals(
            "AC-8: FeedGroupSelectionDialog.java must contain none of these",
            emptyList<String>(),
            presentIn(dialogSource(), "groupcreateinline", "ConcatAdapter", "registerAdapterDataObserver")
        )
    }

    /**
     * D2: every step of the old save ended with `.toSingle(() -> (Void) null)`, which RxJava 3
     * turns into an error after the write; AC-2 and AC-3 need it gone (plan E6: "no `toSingle`").
     */
    @Test
    fun ac3_theDialogSourceNoLongerEndsItsWritesWithToSingle() {
        assertEquals(
            "AC-2, AC-3: FeedGroupSelectionDialog.java must not contain the old save's toSingle(",
            emptyList<String>(),
            presentIn(dialogSource(), "toSingle(")
        )
    }

    /**
     * AC-7: the old save replaced a group's whole member list through
     * `updateSubscriptionsForGroup`. The dialog must not call it any more (plan section 6).
     */
    @Test
    fun ac7_theDialogSourceNoLongerRewritesAMemberList() {
        assertEquals(
            "AC-7: FeedGroupSelectionDialog.java must not contain updateSubscriptionsForGroup",
            emptyList<String>(),
            presentIn(dialogSource(), "updateSubscriptionsForGroup")
        )
    }

    /** AC-8: the package of the take-over is removed from the main sources (plan section 3). */
    @Test
    fun ac8_thePackageOfGroupCreateInlineIsGoneFromTheMainSources() {
        val directory = fromApp("src/main/java/org/schabi/newpipe/enhancements/groupcreateinline")

        assertFalse(
            "AC-8: ${directory.path} must not exist any more",
            directory.exists()
        )
    }

    /**
     * AC-8, plan section 3: the resources of the take-over are replaced by those of this feature
     * (the strings file), renamed (the name prompt) or deleted (the button layout).
     */
    @Test
    fun ac8_theResourceFilesOfGroupCreateInlineAreGone() {
        val old = listOf(
            "src/main/res/values/enhancements_group_create_inline.xml",
            "src/main/res/layout/enhancements_group_create_inline_name_prompt.xml",
            "src/main/res/layout/enhancements_group_create_inline_new_group.xml"
        )

        assertEquals(
            "AC-8: these resource files of group-create-inline must not exist any more",
            emptyList<String>(),
            old.filter { fromApp(it).exists() }
        )
    }

    /**
     * AC-8: the *New group* button and the load error are part of the dialog's own layout, by
     * one `<include>` of the extras layout (plan section 3), instead of being added at runtime.
     */
    @Test
    fun ac8_theDialogLayoutIncludesTheExtrasLayoutOnce() {
        val layout = existing("src/main/res/layout/dialog_feed_group_selection.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout).documentElement
        val nodes = root.getElementsByTagName("include")
        val includesOfTheExtras = (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttribute("layout") }
            .filter { it == "@layout/enhancements_group_dialog_extras" }

        assertEquals(
            "AC-8: ${layout.path} includes @layout/enhancements_group_dialog_extras exactly once",
            1,
            includesOfTheExtras.size
        )
    }

    private companion object {

        const val DIALOG_SOURCE =
            "src/main/java/org/schabi/newpipe/local/subscription/dialog/FeedGroupSelectionDialog.java"

        fun dialogSource(): String = existing(DIALOG_SOURCE).readText()

        /** Those of [fragments] that occur in [text]. */
        fun presentIn(text: String, vararg fragments: String): List<String> = fragments.filter { it in text }

        /**
         * Gradle runs unit tests with `app/` as working directory; an IDE may use the repository
         * root. The module directory is the one of the two that has `src/main`.
         */
        fun fromApp(path: String): File {
            val app = listOf(File("."), File("app")).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
                ?: throw AssertionError(
                    "the app module was not found from the working directory " + File(".").absolutePath
                )
            return File(app, path)
        }

        /** A file that must be there whatever the state of the feature: an upstream file. */
        fun existing(path: String): File {
            val file = fromApp(path)
            if (!file.isFile) throw AssertionError("upstream file not found: ${file.absolutePath}")
            return file
        }
    }
}
