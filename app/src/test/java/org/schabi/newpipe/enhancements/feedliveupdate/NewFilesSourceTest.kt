package org.schabi.newpipe.enhancements.feedliveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four new files of plan section 2, read as text: "Three plain Kotlin files (RxJava 3 and
 * `FeedEventManager.Event`, no Android import) and one Android glue file". The plain files'
 * behaviour is what the other tests of this package run; `FeedListUpdate` is glue around a
 * `RecyclerView` and cannot run on the JVM, so these tests pin the few things in it that AC-5
 * stands on. What it does on a device: `FeedListUpdateTest` (instrumented) and device step 5.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist".
 */
class NewFilesSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/feedliveupdate"

    private val plain = listOf("FeedRefreshLog.kt", "FeedLiveUpdate.kt", "FeedListRules.kt")
    private val glue = "FeedListUpdate.kt"

    private fun source(ac: String, name: String): String = Sources.lines(ac, "$dir/$name").joinToString("\n")

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue("$ac: app/src/main/$dir/$name must contain `$snippet`: $why", Sources.count(source(ac, name), snippet) > 0)
    }

    /** Plan section 2: the package holds the four planned files and no other source file. */
    @Test
    fun thePackageHasThePlansFourFiles() {
        for (name in plain + glue) Sources.lines("plan section 2", "$dir/$name")
        val files = Sources.main(dir).listFiles()!!.filter { it.isFile }.map { it.name }.sorted()

        assertEquals((plain + glue).sorted(), files)
    }

    /**
     * PROCESS.md: decision logic is plain Kotlin with no Android imports. The three logic files
     * import nothing from `android.`, `androidx.` or RxAndroid, and do not log through Android.
     */
    @Test
    fun theThreePlainFilesHaveNoAndroidImports() {
        for (name in plain) {
            val text = source("plan section 2", name)
            val imports = text.lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/$name must not import Android classes",
                emptyList<String>(),
                imports.filter { it.contains("import android.") || it.contains("import androidx.") || it.contains(".rxjava3.android.") }
            )
            assertFalse("app/src/main/$dir/$name must not use android.util.Log", text.contains("android.util.Log"))
            val code = text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/") }.joinToString("\n")
            assertFalse("app/src/main/$dir/$name must not name Android classes in full either", Regex("""\bandroidx?\.\w+\.\w""").containsMatchIn(code))
        }
    }

    /** Each file is in the package the marked lines write out. */
    @Test
    fun theFilesAreInThePlansPackage() {
        for (name in plain + glue) {
            assertTrue(
                "app/src/main/$dir/$name: package org.schabi.newpipe.enhancements.feedliveupdate",
                source("plan section 2", name).lines().any { it.trim() == "package org.schabi.newpipe.enhancements.feedliveupdate" }
            )
        }
    }

    /** AC-2, plan 3.2: the pace is `throttleLatest` on the scheduler given to the constructor, with the plan's defaults. */
    @Test
    fun ac2_feedLiveUpdate_pacesWithThrottleLatestAndThePlansDefaults() {
        val name = "FeedLiveUpdate.kt"
        assertMentions("AC-2", name, ".throttleLatest(", "plan 3.2: the paced batch counts")
        assertMentions("AC-2", name, "log: FeedRefreshLog = FeedRefreshLog.app", "plan section 2: one log for the app is the default")
        assertMentions("AC-2", name, "paceMillis: Long = PACE_MILLIS", "plan section 2")
        assertMentions("AC-2", name, "paceScheduler: Scheduler = Schedulers.computation()", "plan section 2: the pace does not run on the main thread")
        assertMentions("AC-2", name, "const val REBUILD_PERIOD_MILLIS = 2_000L", "AC-2: two seconds")
        for (blocking in listOf("blockingGet", "blockingFirst", "blockingAwait", "blockingSubscribe", "Thread.sleep")) {
            assertEquals("AC-2: app/src/main/$dir/$name must not wait (`$blocking`)", 0, Sources.count(source("AC-2", name), blocking))
        }
    }

    /**
     * AC-5, plan section 7: the glue's signature, as `FeedFragment`'s changed line calls it, and
     * its two ways of updating: synchronous during a refresh, asynchronous as today for the
     * final state.
     */
    @Test
    fun ac5_feedListUpdate_showHasThePlansSignatureAndBothUpdates() {
        assertMentions("AC-5", glue, "object FeedListUpdate", "plan section 2")
        assertMentions("AC-5", glue, "fun show(", "plan section 2")
        for (parameter in listOf(
            "list: RecyclerView", "adapter: GroupAdapter<*>", "shown: List<Item<*>>", "duringRefresh: Boolean",
            "showNewItems: () -> Unit", "onDone: () -> Unit"
        )) {
            assertMentions("AC-5", glue, parameter, "plan section 2: a parameter of show")
        }
        assertMentions("AC-5", glue, "adapter.update(shown, false)", "plan 6.2: \"The update during a refresh is synchronous\"")
        assertMentions("AC-5", glue, "adapter.updateAsync(shown, false,", "plan 6.2: the final state is \"asynchronous as today\"")
    }

    /** AC-5, plan section 7: what the list looked like is read before the update, and the rule decides after it. */
    @Test
    fun ac5_feedListUpdate_asksTheRuleAndActsOnIt() {
        val text = source("AC-5", glue)
        assertMentions("AC-5", glue, "canScrollVertically(-1)", "\"at the top\" = the list cannot scroll up")
        assertMentions("AC-5", glue, "findFirstVisibleItemPosition()", "the first visible item, for the rule")
        assertMentions("AC-5", glue, "FeedListRules.afterUpdate(", "the decision is the plain rule's")
        assertMentions("AC-5", glue, "AfterUpdate.SCROLL_TO_TOP", "plan section 7")
        assertMentions("AC-5", glue, "scrollToPosition(0)", "AC-5: at the top, new videos appear at the top")
        assertMentions("AC-5", glue, "AfterUpdate.SHOW_NEW_ITEMS", "plan section 7")
        assertMentions("AC-5", glue, "showNewItems()", "AC-5: the page's existing \"new items\" indicator")
        assertMentions("AC-8", glue, "onDone()", "AC-8: upstream's callback (the highlighting) still runs")
        val squeezed = Sources.squeeze(text)
        val readTop = squeezed.indexOf("canScrollVertically(-1)")
        val update = squeezed.indexOf("adapter.update(shown,false)")
        val updateAsync = squeezed.indexOf("adapter.updateAsync(shown,false,")
        assertTrue("AC-5: the position is read before the list is updated", readTop in 0 until update && readTop < updateAsync)
        assertEquals("AC-5: the glue never scrolls anywhere else", 1, Sources.count(text, "scrollToPosition("))
        assertEquals("AC-5: and does not move the list by other means", 0, Sources.count(text, "scrollBy(") + Sources.count(text, "smoothScroll"))
    }
}
