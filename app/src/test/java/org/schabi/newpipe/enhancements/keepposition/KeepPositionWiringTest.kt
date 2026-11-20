package org.schabi.newpipe.enhancements.keepposition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan section 6, "Wiring on the JVM" (code review finding F13), as amended by plan 10.6 and
 * 10.7 "Wiring test": the hooks of this feature are single lines inside upstream files. A
 * hook lost, moved or changed in a rebase leaves the feature silently off or wrong, so this
 * test reads the upstream files as text.
 *
 * Amendment 2 changed this test: it covered 7 files and 9 lines and checked only which
 * function a line calls. It now covers 9 files and 13 lines (hooks G1, E1, D1 and F1 are
 * new, for AM-1 and AM-2), compares the whole text of every marked line with the plan, and
 * checks where the four new lines sit, because each of them reads the player at one exact
 * moment (plan 10.4).
 *
 * Which criterion a hook serves: H1 to H4 and H6 AC-2 to AC-5; H5, H7, H8 AC-12 to AC-14;
 * H9 AC-1; E1, D1 AM-1; F1 AM-1 and AM-2; G1 AM-1 and AM-2; the glue behind H1 and H6 AM-3.
 */
class KeepPositionWiringTest {
    private val marker = "ENH:keep-position"
    private val glue = "org.schabi.newpipe.enhancements.keepposition.KeepPosition"
    private val java = "java/org/schabi/newpipe"

    private val playerJava = "$java/player/Player.java"
    private val transportKt = "$java/player/PlayerTransportController.kt"
    private val startKt = "$java/player/PlayerStartController.kt"
    private val clickKt = "$java/player/PlayerClickController.kt"
    private val errorKt = "$java/player/PlayerErrorHandler.kt"
    private val detailKt = "$java/fragments/detail/VideoDetailFragment.kt"
    private val urlsJava = "$java/util/external_communication/InternalUrlsHandler.java"
    private val singleQueueJava = "$java/player/playqueue/SinglePlayQueue.java"
    private val settingsXml = "res/xml/advanced_settings.xml"

    // The four lines amendment 2 adds, exactly as in plan 10.6.
    private val d1 = "$glue.onPlayerClosed(this); // $marker"
    private val g1 = "$glue.onStartIntent(player, newQueue) // $marker"
    private val e1 = "$glue.onErrorBegin(player) // $marker"
    private val f1 = "$glue.onPageStopsPlayer(player, vm.serviceId, vm.url) // $marker"

    /**
     * Plan 10.6: file -> its marked lines without indentation, in file order. The nine lines
     * of plan section 4 "keep their exact text" (plan 10.4).
     */
    private val hooks = linkedMapOf(
        playerJava to listOf(
            d1,
            "$glue.onReload(this); // $marker",
            "final long initialPositionMs = $glue.shouldSeekOnLoad(this, item) // $marker",
        ),
        transportKt to listOf(
            "$glue.onSynchronize(player, item) // $marker",
            "&& $glue.shouldSeekOnSynchronize(player, item) // $marker",
        ),
        startKt to listOf(
            g1,
            "if ($glue.shouldSeekInPlace(player, newQueue, oldPlayerType)) { // $marker",
        ),
        clickKt to listOf("$glue.onSwitchToMainPlayer(player) // $marker"),
        errorKt to listOf(e1),
        detailKt to listOf(f1),
        urlsJava to listOf("$glue.onTimestampLink(playQueue); // $marker"),
        singleQueueJava to listOf("$glue.onLinkStartPosition(this, info); // $marker"),
        settingsXml to listOf(
            "<SwitchPreferenceCompat android:defaultValue=\"true\"" +
                " android:key=\"@string/enh_keep_position_key\"" +
                " android:title=\"@string/enh_keep_position_title\"" +
                " android:summary=\"@string/enh_keep_position_summary\"" +
                " app:singleLineTitle=\"false\" app:iconSpaceReserved=\"false\" /> <!-- $marker -->"
        ),
    )

    /** Every glue function a hook calls, each by exactly one marked line (plan 10.6). */
    private val functions = listOf(
        "onPlayerClosed", "onReload", "shouldSeekOnLoad", "onSynchronize", "shouldSeekOnSynchronize",
        "onStartIntent", "shouldSeekInPlace", "onSwitchToMainPlayer", "onErrorBegin",
        "onPageStopsPlayer", "onTimestampLink", "onLinkStartPosition",
    )

    private fun lines(path: String): List<String> {
        val inModule = File("src/main/$path")
        val file = if (inModule.exists()) inModule else File("app/src/main/$path")
        assertTrue("$path must exist", file.isFile)
        return file.readLines()
    }

    private fun markedLines(path: String): List<String> = lines(path).filter { it.contains(marker) }

    /** The index of the one line of [path] whose text without indentation is [hook]. */
    private fun indexOfHook(path: String, hook: String): Int {
        val found = lines(path).withIndex().filter { it.value.trim() == hook }.map { it.index }
        assertEquals("$path: lines that are exactly `$hook`", 1, found.size)
        return found[0]
    }

    private fun firstIndexContaining(path: String, text: String, from: Int = 0): Int {
        val all = lines(path)
        val index = (from until all.size).firstOrNull { all[it].contains(text) } ?: -1
        assertTrue("$path: a line containing `$text` must exist from line ${from + 1} on", index >= 0)
        return index
    }

    private fun previousNonBlank(path: String, index: Int): String =
        lines(path).subList(0, index).last { it.isNotBlank() }.trim()

    private fun nextNonBlank(path: String, index: Int): String =
        lines(path).drop(index + 1).first { it.isNotBlank() }.trim()

    // ---- every line, its text, its order ----

    /**
     * Plan 10.6: per file the number of marked lines, their order and their exact text. The
     * whole line is compared, so a call of another function that merely starts with the same
     * name, other arguments or a lost marker all fail.
     */
    @Test
    fun `every hook line has the plan's exact text, in file order`() {
        for ((path, expected) in hooks) {
            val actual = markedLines(path).map { it.trim() }
            assertEquals("$path: number of lines marked $marker", expected.size, actual.size)
            expected.forEachIndexed { i, line ->
                assertEquals("$path: marked line ${i + 1}", line, actual[i])
            }
        }
    }

    /** Plan 10.6: "9 upstream files, 13 lines". */
    @Test
    fun `there are 13 marked lines in 9 files`() {
        assertEquals(9, hooks.size)
        assertEquals(13, hooks.values.sumOf { it.size })
        assertEquals(13, hooks.keys.sumOf { markedLines(it).size })
    }

    /**
     * Plan 10.6: each glue function is called by exactly one marked line. The name is matched
     * between "KeepPosition." and "(", so `onReload(` does not count `onReloadSomething(`,
     * and `onSynchronize(` does not count `shouldSeekOnSynchronize(`.
     */
    @Test
    fun `no function is hooked twice and none is missing`() {
        val code = hooks.keys.filter { it != settingsXml }.flatMap { markedLines(it) }
        val called = code.flatMap { line ->
            Regex(Regex.escape("$glue.") + "([A-Za-z0-9_]+)\\(").findAll(line).map { it.groupValues[1] }.toList()
        }
        assertEquals("one call of the glue per marked code line", code.size, called.size)
        assertEquals(functions.sorted(), called.sorted())
    }

    /** AC-1: the settings switch is present once. */
    @Test
    fun `the settings switch is present once`() {
        val marked = markedLines(settingsXml)
        assertEquals("$settingsXml: lines marked $marker", 1, marked.size)
        assertTrue(marked[0].contains("@string/enh_keep_position_key"))
    }

    /** Plan 10.6: the indentation of the four new lines (8 spaces for G1, E1, D1; 12 for F1). */
    @Test
    fun `the four new lines are indented as the plan says`() {
        val expected = listOf(playerJava to d1, startKt to g1, errorKt to e1, detailKt to f1)
        val indents = listOf(8, 8, 8, 12)
        expected.forEachIndexed { i, (path, hook) ->
            val line = lines(path)[indexOfHook(path, hook)]
            assertEquals("$path: indentation of `$hook`", " ".repeat(indents[i]) + hook, line)
        }
    }

    // ---- placement of the four new lines (plan 10.4, 10.7 "Wiring test") ----

    /**
     * AM-1, hook D1: in `Player.destroy()`, the one statement every close passes, and before
     * `destroyPlayer()` stops and releases the ExoPlayer whose position it reads.
     */
    @Test
    fun `AM-1 onPlayerClosed sits in destroy() before the ExoPlayer is destroyed`() {
        val hook = indexOfHook(playerJava, d1)
        val destroy = firstIndexContaining(playerJava, "public void destroy()")
        val destroyPlayer = firstIndexContaining(playerJava, "destroyPlayer();", destroy + 1)

        assertTrue("onPlayerClosed must come after `public void destroy()`", hook > destroy)
        assertTrue("onPlayerClosed must come before the next `destroyPlayer();`", hook < destroyPlayer)
    }

    /** AC-2 to AC-4, hook H1 (plan section 4): still the first statement of `reloadPlayQueueManager()`, below the new D1. */
    @Test
    fun `AC-2 onReload is still the first statement of reloadPlayQueueManager()`() {
        val hook = indexOfHook(playerJava, "$glue.onReload(this); // $marker")

        assertTrue(
            "the line before onReload must open reloadPlayQueueManager()",
            previousNonBlank(playerJava, hook).contains("void reloadPlayQueueManager()")
        )
    }

    /**
     * AM-1, hook E1: the first statement of `onPlayerError` after its log line, before any
     * branch saves progress, stops, reloads or shuts the player down.
     */
    @Test
    fun `AM-1 onErrorBegin is the first statement of the error handler after its log line`() {
        val hook = indexOfHook(errorKt, e1)
        val handler = firstIndexContaining(errorKt, "fun onPlayerError(error: PlaybackException)")
        val shutdown = firstIndexContaining(errorKt, "player.onPlaybackShutdown()")
        val saveProgress = firstIndexContaining(errorKt, "player.saveStreamProgressState()")

        assertTrue("onErrorBegin must be inside onPlayerError", hook > handler)
        assertTrue("onErrorBegin must come before the first player.onPlaybackShutdown()", hook < shutdown)
        assertTrue(
            "onErrorBegin must come before the first player.saveStreamProgressState()",
            hook < saveProgress
        )
        val between = lines(errorKt).subList(handler + 1, hook).filter { it.isNotBlank() }.map { it.trim() }
        assertEquals(
            "between the head of onPlayerError and onErrorBegin there is only the log line",
            listOf("Log.e(Player.TAG, \"ExoPlayer - onPlayerError() called with:\", error)"),
            between
        )
    }

    /**
     * AM-1 and AM-2, hook F1: directly before the statement with which the page stops the
     * player, in the branch without Autoplay of `hideMainPlayerOnLoadingNewStream`.
     */
    @Test
    fun `AM-1 AM-2 onPageStopsPlayer sits directly before the page's stopService()`() {
        val hook = indexOfHook(detailKt, f1)

        assertEquals("the next line after the hook", "playerHolder.stopService()", nextNonBlank(detailKt, hook))
        assertEquals("the line before the hook", "} else {", previousNonBlank(detailKt, hook))
        val function = lines(detailKt).subList(0, hook).last { it.contains("fun ") }
        assertTrue(
            "the hook must be inside hideMainPlayerOnLoadingNewStream(), but is in: ${function.trim()}",
            function.contains("fun hideMainPlayerOnLoadingNewStream()")
        )
    }

    /**
     * AM-1 and AM-2, hook G1: in `handleIntent` after the enqueue returns and
     * `samePlayQueue`, before the first read of the new queue's stored position (and so
     * before the seek in place, H5, and before `initPlayback` destroys the ExoPlayer).
     */
    @Test
    fun `AM-1 AM-2 onStartIntent sits after samePlayQueue and before the stored position is read`() {
        val hook = indexOfHook(startKt, g1)
        val samePlayQueue = firstIndexContaining(startKt, "val samePlayQueue")
        val firstRead = firstIndexContaining(startKt, "newQueue.getRecoveryPosition(")
        val seekInPlace = indexOfHook(startKt, hooks.getValue(startKt)[1])

        assertTrue("onStartIntent must come after `val samePlayQueue`", hook > samePlayQueue)
        assertTrue("onStartIntent must come before the first `newQueue.getRecoveryPosition(`", hook < firstRead)
        assertTrue("onStartIntent must come before shouldSeekInPlace", hook < seekInPlace)
        assertEquals(
            "the line after the hook (plan 10.4)",
            "// seek to timestamp if stream is already playing",
            nextNonBlank(startKt, hook)
        )
    }
}
