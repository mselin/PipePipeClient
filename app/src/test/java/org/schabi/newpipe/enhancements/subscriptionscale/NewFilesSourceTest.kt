package org.schabi.newpipe.enhancements.subscriptionscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new files of plan section 2, read as text. `RowWatchState` is Android glue (main looper,
 * a view) and cannot run on the JVM; these tests pin only the few things in it that AC-2 stands
 * on and that a refactoring could drop without any other test failing. What it does on a device
 * is step 4 of the plan's device script.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist".
 */
class NewFilesSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/subscriptionscale"

    /** Plan section 2: "six plain Kotlin files". */
    private val plain = listOf(
        "FeedResultRetention.kt", "LatestRequest.kt", "BoundedCache.kt", "OnScreenGate.kt", "SubscriptionRows.kt", "ReusingMapper.kt"
    )

    private fun source(ac: String, name: String): String = MainFiles.lines(ac, "$dir/$name").joinToString("\n")

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue("$ac: app/src/main/$dir/$name must contain `$snippet`: $why", MainFiles.count(source(ac, name), snippet) > 0)
    }

    /**
     * PROCESS.md: decision logic is plain Kotlin with no Android imports. The six logic files
     * import nothing from `android.`, `androidx.` or RxAndroid, and do not log through Android.
     */
    @Test
    fun logicFilesHaveNoAndroidImports() {
        for (name in plain) {
            val text = source("plan section 2", name)
            val imports = text.lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/$name must not import Android classes",
                emptyList<String>(),
                imports.filter { it.contains("import android.") || it.contains("import androidx.") || it.contains(".rxjava3.android.") }
            )
            assertFalse("app/src/main/$dir/$name must not use android.util.Log", text.contains("android.util.Log"))
        }
    }

    /** AC-2: the answer is delivered on the main thread, through `LatestRequest`; nothing waits. */
    @Test
    fun ac2_rowWatchState_asksThroughLatestRequestOnTheMainThread() {
        val name = "RowWatchState.kt"
        assertMentions("AC-2", name, "LatestRequest", "plan section 4: refresh is LatestRequest.start(...)")
        assertMentions("AC-2", name, ".start(", "plan section 4: refresh is LatestRequest.start(...)")
        assertMentions("AC-2", name, ".loadStreamState(", "the request is HistoryRecordManager.loadStreamState(item)")
        assertMentions("AC-2", name, "AndroidSchedulers.mainThread()", "the answer is applied to the view on the main thread")
        assertMentions("AC-2", name, ".cancel()", "RowWatchState.cancel() cancels its LatestRequest")
        for (blocking in listOf("blockingGet", "blockingFirst", "blockingAwait", "blockingSubscribe")) {
            assertEquals("AC-2: app/src/main/$dir/$name must not wait (`$blocking`)", 0, MainFiles.count(source("AC-2", name), blocking))
        }
    }

    /** AC-2: a video drawn before shows its bar at once: a process-wide cache of 500 entries, read by a static `cached`. */
    @Test
    fun ac2_rowWatchState_hasTheBoundedCacheOf500() {
        val name = "RowWatchState.kt"
        assertMentions("AC-2", name, "BoundedCache", "plan section 4: a process-wide BoundedCache")
        assertMentions("AC-2", name, "500", "plan section 4: 500 entries")
        assertMentions("AC-2", name, "companion object", "the cache is process-wide and `cached` is static for the Java holders")
        assertMentions("AC-2", name, "@JvmStatic", "the Java holders and ComposeItemUiHelper call RowWatchState.cached")
        assertMentions("AC-2", name, "fun cached(item: InfoItem): StreamStateEntity?", "plan section 2: the signature")
    }

    /** AC-2: both `refresh` variants of the plan exist, with the plan's parameter types. */
    @Test
    fun ac2_rowWatchState_hasBothRefreshVariants() {
        val name = "RowWatchState.kt"
        assertMentions(
            "AC-2", name, "fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, progressView: AnimatedProgressBar)",
            "the variant of the two Java holders"
        )
        assertMentions(
            "AC-2", name, "fun refresh(item: StreamInfoItem, manager: HistoryRecordManager, onChanged: Runnable)",
            "the variant of the experimental row"
        )
        assertMentions("AC-2", name, "fun cancel()", "plan section 2")
    }
}
