package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The hooks of plan section 2 as changed by plan A2.3, A2.15 and A2.16, checked on the JVM by reading
 * the 13 upstream files as text (plan A2.8: 13 files, 34 lines marked `ENH:queue-guard`). The glue
 * itself cannot run without Android, but a hook that is missing, misplaced or worded differently can be
 * seen.
 *
 * Three kinds of checks:
 * - Counts: each file holds the number of marked lines of plan A2.8, no other file below `app/src/main`
 *   holds one, and `PlayQueue.java` holds none any more (hook Q1 is removed with the bookkeeping of
 *   auto-enqueued videos, AM-1).
 * - The hooks of the first plan (T1-T4, B1-B5, M1, S1), as before Amendment 2: a code hook names
 *   `org.schabi.newpipe.enhancements.queueguard.QueueGuard` in full on the marked line (so that no import
 *   line is added), and the tap and menu hooks call `onVideoTap(` and `addStreamDetailsEntry(`.
 * - The hooks of Amendment 2 (P1-P6, D1, L1, L2, R1, R2): the exact text of plan A2.3, A2.15 and A2.16
 *   with `QG` written in full, the two upstream lines it sits between, and the indentation of the
 *   statement below it.
 *
 * Until the Code layer has changed the hooks, the tests of Amendment 2 fail with "expected n but was 0"
 * and the `PlayQueue.java` test with "expected 0 but was 1".
 */
class QueueGuardWiringTest {

    private val marker = "ENH:queue-guard"

    /** The marker as a whole word: `ENH:queue-guard-x` or `ENH:queue-guards` would be another feature's. */
    private val markerPattern = Regex("""ENH:queue-guard(?![\w-])""")
    private val queueGuard = "org.schabi.newpipe.enhancements.queueguard.QueueGuard"

    private val videoDetailFragment = "java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.kt"
    private val queueItemMenuUtil = "java/org/schabi/newpipe/QueueItemMenuUtil.java"
    private val internalUrlsHandler = "java/org/schabi/newpipe/util/external_communication/InternalUrlsHandler.java"
    private val searchFragment = "java/org/schabi/newpipe/fragments/list/search/SearchFragment.java"
    private val routerActivity = "java/org/schabi/newpipe/RouterActivity.java"
    private val playQueue = "java/org/schabi/newpipe/player/playqueue/PlayQueue.java"

    /** Plan A2.8, the table: every upstream file with a marked line and how many it holds. */
    private val markedLinesPerFile: Map<String, Int> = mapOf(
        "java/org/schabi/newpipe/fragments/list/BaseListFragment.java" to 1,
        "java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java" to 5,
        "java/org/schabi/newpipe/local/history/StatisticsPlaylistFragment.java" to 4,
        "java/org/schabi/newpipe/local/feed/FeedFragment.kt" to 5,
        "java/org/schabi/newpipe/fragments/list/playlist/PlaylistFragment.java" to 3,
        "java/org/schabi/newpipe/fragments/list/channel/ChannelVideosFragment.java" to 3,
        "java/org/schabi/newpipe/info_list/dialog/InfoItemDialog.java" to 1,
        "res/xml/video_audio_settings.xml" to 1,
        videoDetailFragment to 6,
        queueItemMenuUtil to 1,
        internalUrlsHandler to 1,
        searchFragment to 1,
        routerActivity to 2,
    )

    private fun isMarked(line: String): Boolean = markerPattern.containsMatchIn(line)

    private fun allLines(path: String): List<String> =
        MainSources.file(path, "upstream file that plan section 2 or A2.3 hooks").readLines()

    private fun hookLines(path: String): List<String> = allLines(path).filter { isMarked(it) }

    /** [path] as failure messages show it. */
    private fun shown(path: String): String = "app/src/main/$path"

    private fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /**
     * [path] holds [total] hook lines, [taps] of them video-tap hooks (form A, `QueueGuard.onVideoTap(`);
     * the others are play-button hooks (form B, the receiver swapped to `QueueGuard`). Each names the
     * class in full.
     */
    private fun assertCodeHooks(path: String, hooks: String, total: Int, taps: Int) {
        val lines = hookLines(path)
        assertEquals("$hooks: lines marked $marker in ${shown(path)}", total, lines.size)
        for (line in lines) {
            assertTrue(
                "$hooks: a hook line in ${shown(path)} does not name $queueGuard: ${line.trim()}",
                queueGuard in line,
            )
        }
        assertEquals(
            "$hooks: hook lines in ${shown(path)} that call $queueGuard.onVideoTap(",
            taps,
            lines.count { "$queueGuard.onVideoTap(" in it },
        )
    }

    /**
     * A hook of Amendment 2 as the plan writes it: [text] is the plan's line with `QG` still in it; in
     * the file `QG` is the class name in full. The line is there exactly once, directly below a line
     * that reads [above] and directly above lines that read [below] (compared without indentation; an
     * empty string is a blank line), and it is indented like the first statement below it. Returns its
     * index in the file.
     */
    private fun assertHook(path: String, hook: String, text: String, above: String, below: List<String>): Int {
        val expected = text.replace("QG.", "$queueGuard.")
        val lines = allLines(path)
        val found = lines.indices.filter { lines[it].trim() == expected }
        if (found.size != 1) {
            val near = lines.filter { isMarked(it) }.joinToString("\n") { "    ${it.trim()}" }
            fail(
                "$hook: ${shown(path)} must hold this line exactly once (found ${found.size}):\n    $expected\n" +
                    "marked lines in the file now:\n$near",
            )
        }
        val index = found.single()
        assertTrue("$hook: there is a line above the hook", index > 0)
        assertEquals("$hook: the line above the hook in ${shown(path)}", above, lines[index - 1].trim())
        for ((offset, wanted) in below.withIndex()) {
            val at = index + 1 + offset
            assertTrue(
                "$hook: ${shown(path)} ends ${below.size - offset} lines too early below the hook",
                at < lines.size,
            )
            assertEquals("$hook: line ${offset + 1} below the hook in ${shown(path)}", wanted, lines[at].trim())
        }
        val statementBelow = lines.drop(index + 1).first { it.isNotBlank() }
        assertEquals(
            "$hook: the hook in ${shown(path)} is indented like the statement below it",
            indentOf(statementBelow).length,
            indentOf(lines[index]).length,
        )
        return index
    }

    private val kotlinFunction = Regex("""\bfun\s+(\w+)\s*\(""")

    /** A line on which a Java method starts. */
    private val javaMethodHead = Regex("""^\s*(private|protected|public)\s.*\)\s*\{\s*$""")

    /** The Kotlin function line [index] of [lines] is in: the name of the last `fun name(` at or above it. */
    private fun enclosingFunction(lines: List<String>, index: Int): String? =
        (index downTo 0).firstNotNullOfOrNull { kotlinFunction.find(lines[it])?.groupValues?.get(1) }

    // =============================================================================================
    // Counts (plan A2.8)
    // =============================================================================================

    /**
     * AM-8 and plan A2.8: 34 marked lines in 13 upstream files, and no upstream file the plan leaves
     * alone carries one. That covers `MainActivity.java` and `fragments/detail/DetailActions.kt`, the
     * long-press entries and the multi-select actions ("they are explicit choices"), the player switches
     * and `PlayQueue.java`. The feature's own new files (`enhancements/queueguard/`,
     * `res/values/enhancements_*.xml`) are not upstream files and are not counted.
     */
    @Test
    fun am8_exactlyThirteenUpstreamFilesCarryThirtyFourMarkedLines() {
        assertEquals("files in the table of plan A2.8", 13, markedLinesPerFile.size)
        assertEquals("marked lines in the table of plan A2.8", 34, markedLinesPerFile.values.sum())

        val root = MainSources.root
        val found = sortedMapOf<String, Int>()
        root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("java", "kt", "xml") }
            .forEach { file ->
                val path = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val own = path.startsWith("java/org/schabi/newpipe/enhancements/") ||
                    file.name.startsWith("enhancements_")
                if (!own) {
                    val count = file.readLines().count { isMarked(it) }
                    if (count > 0) found[path] = count
                }
            }

        assertEquals(
            "upstream files below app/src/main with lines marked $marker, and how many",
            markedLinesPerFile.toSortedMap().toString().replace(", ", ",\n"),
            found.toString().replace(", ", ",\n"),
        )
        assertEquals("marked lines in upstream files", 34, found.values.sum())
    }

    /**
     * No hook may need an import: every marked code line names the class in full, and no hooked file imports the
     * package.
     */
    @Test
    fun am3_am4_am5_am6_noHookedFileImportsTheQueueGuardPackage() {
        for (path in markedLinesPerFile.keys + playQueue) {
            val imports = allLines(path).filter {
                it.trimStart().startsWith("import ") && "enhancements.queueguard" in it
            }
            assertEquals("import lines for the queueguard package in ${shown(path)}", emptyList<String>(), imports)
        }
        val hookedByAmendment2 =
            listOf(videoDetailFragment, queueItemMenuUtil, internalUrlsHandler, searchFragment, routerActivity)
        for (path in hookedByAmendment2) {
            for (line in hookLines(path)) {
                assertTrue(
                    "a hook line in ${shown(path)} does not name $queueGuard: ${line.trim()}",
                    queueGuard in line,
                )
                assertFalse(
                    "a hook line in ${shown(path)} still says QG: ${line.trim()}",
                    Regex("""\bQG\.""").containsMatchIn(line),
                )
            }
        }
    }

    // =============================================================================================
    // Video taps: T1-T4 (AC-1); play buttons: B1-B5 (AC-10). Unchanged by Amendment 2.
    // =============================================================================================

    /**
     * T1: search, trending, channel videos and tabs, remote playlists, related videos (AM-2 rides on it, unchanged).
     */
    @Test
    fun ac1_hookT1_baseListFragmentGuardsItsVideoTap() {
        assertCodeHooks("java/org/schabi/newpipe/fragments/list/BaseListFragment.java", "T1", total = 1, taps = 1)
    }

    /** T2 and B3 (main, popup, background shuffled, background). */
    @Test
    fun ac1_ac10_hooksT2B3_localPlaylistFragmentGuardsItsVideoTapAndItsFourPlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java",
            "T2, B3",
            total = 5,
            taps = 1,
        )
    }

    /** T3 and B4 (main, popup, background). */
    @Test
    fun ac1_ac10_hooksT3B4_statisticsPlaylistFragmentGuardsItsVideoTapAndItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/local/history/StatisticsPlaylistFragment.java",
            "T3, B4",
            total = 4,
            taps = 1,
        )
    }

    /** T4 and B5 (main, popup, background shuffled, background). */
    @Test
    fun ac1_ac10_hooksT4B5_feedFragmentGuardsItsVideoTapAndItsFourPlayButtonCalls() {
        assertCodeHooks("java/org/schabi/newpipe/local/feed/FeedFragment.kt", "T4, B5", total = 5, taps = 1)
    }

    /** B1 (main, popup, background). The video tap of a remote playlist is T1. */
    @Test
    fun ac10_hookB1_playlistFragmentGuardsItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/fragments/list/playlist/PlaylistFragment.java",
            "B1",
            total = 3,
            taps = 0,
        )
    }

    /** B2 (main, popup, background). The video tap of a channel is T1. */
    @Test
    fun ac10_hookB2_channelVideosFragmentGuardsItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/fragments/list/channel/ChannelVideosFragment.java",
            "B2",
            total = 3,
            taps = 0,
        )
    }

    // =============================================================================================
    // Q1 is removed (AM-1)
    // =============================================================================================

    /**
     * INVERTED by AM-1. Was `ac1_ac7_hookQ1_playQueueReportsEveryAutoQueuedVideoOnOneMarkedLine` and
     * expected one marked line calling `QueueGuard.noteAutoQueued(itemList)`. "How the items got there
     * no longer matters", so nothing reports auto-enqueued videos any more: plan A2.3 removes the whole
     * line, "the file is then byte-identical to `dev`". It holds no marked line and names the feature
     * nowhere.
     */
    @Test
    fun am1_hookQ1Removed_playQueueHoldsNoMarkedLineAndDoesNotNameQueueGuard() {
        val lines = allLines(playQueue)
        assertEquals(
            "Q1 is removed: lines marked $marker in ${shown(playQueue)}",
            0,
            lines.count { isMarked(it) },
        )
        assertEquals(
            "Q1 is removed: lines in ${shown(playQueue)} that mention the queueguard package or noteAutoQueued",
            emptyList<String>(),
            lines.filter { "queueguard" in it || "QueueGuard" in it || "noteAutoQueued" in it }.map { it.trim() },
        )
    }

    /**
     * AM-1, plan A2.4 and A2.7: the bookkeeping behind Q1 is removed from the feature's own code as well
     * (`noteAutoQueued`, `autoQueuedRecord`, `AutoQueuedRecord`, `AUTO_QUEUED_RECORD_LIMIT`,
     * `autoQueuedCount`, `worthGuarding`), so that the old rule cannot be switched back on by one line.
     */
    @Test
    fun am1_theBookkeepingOfAutoQueuedVideosIsGoneFromTheFeaturesOwnCode() {
        val gone = listOf(
            "noteAutoQueued",
            "autoQueuedRecord",
            "AutoQueuedRecord",
            "AUTO_QUEUED_RECORD_LIMIT",
            "autoQueuedCount",
            "worthGuarding",
        )
        val directory = MainSources.resolve("java/org/schabi/newpipe/enhancements/queueguard")
        assertTrue("${shown("java/org/schabi/newpipe/enhancements/queueguard")} is a directory", directory.isDirectory)
        val hits = directory.listFiles().orEmpty().sortedBy { it.name }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> gone.any { it in line } }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals("lines that still name the removed bookkeeping", emptyList<String>(), hits)
    }

    /**
     * Plan A2.8 and A2.3 ("`openMainPlayer` itself cannot carry the hook", "`DetailActions.kt` ... not touched, on
     * purpose").
     */
    @Test
    fun am3_detailActionsAndMainActivityHoldNoMarkedLine() {
        for (path in listOf(
            "java/org/schabi/newpipe/fragments/detail/DetailActions.kt",
            "java/org/schabi/newpipe/MainActivity.java",
        )) {
            assertEquals("lines marked $marker in ${shown(path)}", 0, hookLines(path).size)
        }
    }

    // =============================================================================================
    // "Show stream details": M1 (AC-17). The switch: S1 (AC-15). Unchanged by Amendment 2.
    // =============================================================================================

    @Test
    fun ac17_hookM1_infoItemDialogAddsTheStreamDetailsEntry() {
        val path = "java/org/schabi/newpipe/info_list/dialog/InfoItemDialog.java"
        val lines = hookLines(path)
        assertEquals("M1: lines marked $marker in ${shown(path)}", 1, lines.size)
        assertTrue(
            "M1: the hook line in ${shown(path)} must call $queueGuard.addStreamDetailsEntry(: " +
                lines.single().trim(),
            "$queueGuard.addStreamDetailsEntry(" in lines.single(),
        )
    }

    /** S1 is XML and one line; where it sits and what it holds is checked in [QueueGuardResourcesTest]. */
    @Test
    fun ac15_hookS1_playerSettingsScreenHasTheSwitchOnOneMarkedLine() {
        val path = "res/xml/video_audio_settings.xml"
        val lines = hookLines(path)
        assertEquals("S1: lines marked $marker in ${shown(path)}", 1, lines.size)
        assertTrue(
            "S1: the marked line in ${shown(path)} must hold the preference with key " +
                "@string/enh_queue_guard_ask_key: ${lines.single().trim()}",
            "android:key=\"@string/enh_queue_guard_ask_key\"" in lines.single(),
        )
    }

    // =============================================================================================
    // The video page: P1-P6 (AM-3, AM-9, AM-11)
    // =============================================================================================

    /**
     * Plan A2.8: `VideoDetailFragment.kt` holds 6 marked lines: P1-P4 each with `QueueGuard.onPageControl(`,
     * P5 and P6 each with `QueueGuard.playersQueueFor(`. The two names are told apart with their opening
     * bracket and argument, so that neither count can be met by the other hook.
     */
    @Test
    fun am3_am9_am11_videoDetailFragmentHoldsSixMarkedLines_fourPageControlsAndTwoPlayersQueue() {
        val lines = hookLines(videoDetailFragment)
        assertEquals("P1-P6: lines marked $marker in ${shown(videoDetailFragment)}", 6, lines.size)
        assertEquals(
            "P1-P4: marked lines that call $queueGuard.onPageControl(",
            4,
            lines.count { "$queueGuard.onPageControl(" in it },
        )
        assertEquals(
            "P5: marked lines with $queueGuard.playersQueueFor(vm.currentInfo)?.let { return it }",
            1,
            lines.count { "$queueGuard.playersQueueFor(vm.currentInfo)?.let { return it }" in it },
        )
        assertEquals(
            "P6: marked lines with $queueGuard.playersQueueFor(result.info) and " +
                "else hideMainPlayerOnLoadingNewStream()",
            1,
            lines.count {
                "$queueGuard.playersQueueFor(result.info)" in it && "else hideMainPlayerOnLoadingNewStream()" in it
            },
        )
        assertEquals("no marked line calls onVideoTap here", 0, lines.count { "$queueGuard.onVideoTap(" in it })
    }

    /**
     * P1 (AM-3): the page's Background button. First statement of `private fun openBackgroundPlayer()`,
     * above `player?.changeFullscreen(false)`; the replay calls the function again.
     */
    @Test
    fun am3_hookP1_backgroundButtonOfTheVideoPageAsksFirst() {
        val index = assertHook(
            videoDetailFragment,
            "P1",
            "if (QG.onPageControl(this, vm.currentInfo) { openBackgroundPlayer() }) return // ENH:queue-guard",
            above = "private fun openBackgroundPlayer() {",
            below = listOf("player?.changeFullscreen(false)"),
        )
        assertEquals("P1 is in", "openBackgroundPlayer", enclosingFunction(allLines(videoDetailFragment), index))
    }

    /**
     * P2 (AM-3): the page's Popup button, in `private fun openPopupPlayer()` "after the permission check
     * so that a missing popup permission gives today's toast instead of a dialog": below the `}` that
     * closes the check, above the comment `// See UI changes while the remote play queue changes`.
     */
    @Test
    fun am3_hookP2_popupButtonOfTheVideoPageAsksAfterThePermissionCheck() {
        val lines = allLines(videoDetailFragment)
        val index = assertHook(
            videoDetailFragment,
            "P2",
            "if (QG.onPageControl(this, vm.currentInfo) { openPopupPlayer() }) return // ENH:queue-guard",
            above = "}",
            below = listOf("// See UI changes while the remote play queue changes"),
        )
        assertEquals("P2 is in", "openPopupPlayer", enclosingFunction(lines, index))
        val function = lines.indexOfFirst { it.trim() == "private fun openPopupPlayer() {" }
        val permissionToast =
            (function until index).filter { "PermissionHelper.showPopupEnablementToast(activity)" in lines[it] }
        assertEquals(
            "P2: the permission check lies between the head of openPopupPlayer() and the hook",
            1,
            permissionToast.size,
        )
    }

    /**
     * P3 (AM-3): the mini player's play button, `hosts.onPlayPauseClicked()`. The first branch only
     * toggles play and pause and stays unhooked; the hook is the first statement of the `else` branch,
     * above `beginStartupTrace()`.
     */
    @Test
    fun am3_hookP3_miniPlayerPlayButtonAsksOnlyWhereItWouldStartThePagesVideo() {
        val lines = allLines(videoDetailFragment)
        val index = assertHook(
            videoDetailFragment,
            "P3",
            "if (QG.onPageControl(this@VideoDetailFragment, vm.currentInfo) { onPlayPauseClicked() }) return " +
                "// ENH:queue-guard",
            above = "} else {",
            below = listOf(
                "beginStartupTrace()",
                "autoPlayEnabled = true // forcefully start playing",
                "openVideoPlayer(false)",
            ),
        )
        assertEquals("P3 is in", "onPlayPauseClicked", enclosingFunction(lines, index))
        val function = (index downTo 0).first { "fun onPlayPauseClicked(" in lines[it] }
        val toggle = (function until index).filter { lines[it].trim() == "player.playPause()" }
        assertEquals("P3: the play/pause toggle stays above the hook, in the unhooked first branch", 1, toggle.size)
        assertTrue(
            "P3: no marked line between the head of onPlayPauseClicked() and its else branch",
            (function until index).none { isMarked(lines[it]) },
        )
    }

    /**
     * P4 (AM-3): the page's play button and thumbnail, `hosts.playInMainPlayer()`: its first statement,
     * above `beginStartupTrace()`. `openMainPlayer()` itself stays unhooked (autoplay, the service
     * connecting and a player switch call it too, and none of them is a press).
     */
    @Test
    fun am3_hookP4_playButtonAndThumbnailOfTheVideoPageAskFirst() {
        val lines = allLines(videoDetailFragment)
        val index = assertHook(
            videoDetailFragment,
            "P4",
            "if (QG.onPageControl(this@VideoDetailFragment, vm.currentInfo) { playInMainPlayer() }) return " +
                "// ENH:queue-guard",
            above = "override fun playInMainPlayer() {",
            below = listOf("beginStartupTrace()", "autoPlayEnabled = true // forcefully start playing"),
        )
        assertEquals("P4 is in", "playInMainPlayer", enclosingFunction(lines, index))

        val openMainPlayer = lines.indexOfFirst { it.trim() == "private fun openMainPlayer() {" }
        assertTrue("${shown(videoDetailFragment)} defines private fun openMainPlayer()", openMainPlayer >= 0)
        val marked = lines.indices.filter { isMarked(lines[it]) && enclosingFunction(lines, it) == "openMainPlayer" }
        assertEquals("P4: marked lines inside openMainPlayer()", emptyList<Int>(), marked)
    }

    /**
     * P5 (AM-9): "its play, Background and Popup controls and its thumbnail act on the player's queue".
     * One line in the one function all senders share: the line right after
     * `private fun playQueueForIntent(): PlayQueue? {`, above `val queue = vm.playQueue` (plan A2.15).
     * It reads no setting: nothing but the page's video is passed.
     */
    @Test
    fun am9_hookP5_everySenderOfTheVideoPageGetsThePlayersQueueOnThePageOfTheCurrentItem() {
        val lines = allLines(videoDetailFragment)
        val index = assertHook(
            videoDetailFragment,
            "P5",
            "QG.playersQueueFor(vm.currentInfo)?.let { return it } // ENH:queue-guard",
            above = "private fun playQueueForIntent(): PlayQueue? {",
            below = listOf("val queue = vm.playQueue"),
        )
        assertEquals("P5 is in", "playQueueForIntent", enclosingFunction(lines, index))
        val senders = lines.filter { it.trim() == "val queue = playQueueForIntent() ?: return" }
        assertEquals(
            "P5: the Background, Popup and main-player senders still take their queue from playQueueForIntent()",
            3,
            senders.size,
        )
    }

    /**
     * P6 (AM-11): "Opening the video page of the current item never closes the player or empties its
     * queue, whatever Autoplay allows." The one line changed in place (plan A2.16), in `onLoadResult`
     * between `isLoading.set(false)` and `render(result.info, rebuildTabs = true)`: the page takes the
     * player's queue when it has loaded the player's current item, and the old call stays in the
     * `else` branch for every other video.
     */
    @Test
    fun am11_hookP6_thePageThatLoadedTheCurrentItemKeepsThePlayerAndElseHidesItAsBefore() {
        val lines = allLines(videoDetailFragment)
        val index = assertHook(
            videoDetailFragment,
            "P6",
            "val playing = QG.playersQueueFor(result.info); " +
                "if (playing != null) vm.playQueue = playing else hideMainPlayerOnLoadingNewStream() " +
                "// ENH:queue-guard",
            above = "isLoading.set(false)",
            below = listOf("render(result.info, rebuildTabs = true)", "header?.showContent()"),
        )
        assertEquals("P6 is in", "onLoadResult", enclosingFunction(lines, index))
        assertTrue(
            "P6: the old call must stay on the line, in the else branch: ${lines[index].trim()}",
            Regex("""\belse hideMainPlayerOnLoadingNewStream\(\)""").containsMatchIn(lines[index]),
        )
        val unconditional = lines.indices.filter {
            lines[it].trim() == "hideMainPlayerOnLoadingNewStream()" && enclosingFunction(lines, it) == "onLoadResult"
        }
        assertEquals(
            "P6: onLoadResult no longer hides the main player unconditionally " +
                "(the old line was changed, not kept beside the new one)",
            emptyList<Int>(),
            unconditional,
        )
    }

    /**
     * P6, the other callers (plan A2.16): "the file still calls `hideMainPlayerOnLoadingNewStream()` on
     * two unmarked lines (Back and the error) and defines it once, unmarked". Back inside the page
     * (`setupFromHistoryItem`) and a player error (`onPlayerError`) stop the player exactly as today.
     */
    @Test
    fun am11_hookP6_backAndThePlayerErrorStillHideTheMainPlayerOnUnmarkedLines() {
        val lines = allLines(videoDetailFragment)
        val mentions = lines.indices.filter { "hideMainPlayerOnLoadingNewStream(" in lines[it] }
        assertEquals(
            "lines of ${shown(videoDetailFragment)} that name hideMainPlayerOnLoadingNewStream( " +
                "(P6, Back, the error, the definition)",
            4,
            mentions.size,
        )

        val definitions = mentions.filter { lines[it].trim() == "private fun hideMainPlayerOnLoadingNewStream() {" }
        assertEquals("the function is defined once", 1, definitions.size)
        assertFalse("the definition is unmarked", isMarked(lines[definitions.single()]))

        val plainCalls = mentions.filter { lines[it].trim() == "hideMainPlayerOnLoadingNewStream()" }
        assertEquals("unconditional, unmarked calls (Back and the error)", 2, plainCalls.size)
        assertEquals(
            "the functions that still call it unconditionally",
            listOf("setupFromHistoryItem", "onPlayerError"),
            plainCalls.map { enclosingFunction(lines, it) },
        )

        val body = (definitions.single() + 1 until lines.size)
            .takeWhile { lines[it].trim() != "}" || indentOf(lines[it]).length > 4 }
        assertTrue(
            "the body of hideMainPlayerOnLoadingNewStream() carries no marked line " +
                "(the condition is at the caller, P6)",
            body.none { isMarked(lines[it]) },
        )
        for (function in listOf("setupFromHistoryItem", "onPlayerError")) {
            val marked = lines.indices.filter { isMarked(lines[it]) && enclosingFunction(lines, it) == function }
            assertEquals("marked lines inside $function()", emptyList<Int>(), marked)
        }
    }

    // =============================================================================================
    // "Details" of a queue item: D1 (AM-4)
    // =============================================================================================

    /**
     * D1 (AM-4): "Details in a queue item's menu opens that video's page and leaves the queue as it is".
     * First statement of the `menu_item_details` branch, above upstream's `NavigationHelper.openVideoDetail(`,
     * which still runs when the hook returns `false` (background or popup player).
     */
    @Test
    fun am4_hookD1_detailsOfAQueueItemGoesThroughTheGuardFirst() {
        assertEquals("D1: lines marked $marker in ${shown(queueItemMenuUtil)}", 1, hookLines(queueItemMenuUtil).size)
        assertHook(
            queueItemMenuUtil,
            "D1",
            "if (QG.openQueueItemDetails(context, playQueue, item)) return true; // ENH:queue-guard",
            above = "} else if (menuItem.getItemId() == R.id.menu_item_details) {",
            below = listOf(
                "NavigationHelper.openVideoDetail(context, item.getServiceId(),",
                "item.getUrl(), item.getTitle(), null,",
                "false);",
                "return true;",
            ),
        )
    }

    // =============================================================================================
    // Links and typed addresses: L1, L2 (AM-5, AM-10)
    // =============================================================================================

    /**
     * L1 (AM-5, AM-10): a link whose address ends in a time. In `handleTimestampUrl`, in front of
     * `playOnMain`, not inside it: the replay is the untouched upstream call with the same five
     * arguments. `InternalUrlsHandler.java` holds this one marked line and no other (AM-10 needs none:
     * the seek is in the glue).
     */
    @Test
    fun am5_am10_hookL1_aTimedLinkGoesThroughTheGuardInFrontOfPlayOnMain() {
        assertEquals(
            "L1: lines marked $marker in ${shown(internalUrlsHandler)}",
            1,
            hookLines(internalUrlsHandler).size,
        )
        assertHook(
            internalUrlsHandler,
            "L1",
            "if (QG.onTimedLink(context, service, matchedUrl, seconds, " +
                "() -> playOnMain(context, matchedUrl, service, seconds, disposables))) return true; " +
                "// ENH:queue-guard",
            above = "if (linkType == StreamingService.LinkType.STREAM && seconds != -1) {",
            below = listOf(
                "return playOnMain(context, matchedUrl, service, seconds, disposables);",
                "} else {",
                "NavigationHelper.openRouterActivity(context, matchedUrl);",
            ),
        )
    }

    /**
     * AM-10: "A link to a time in the current item seeks in place and keeps the queue, also when the
     * queue is longer than that one video." The step is glue (plan A2.15: `QueueGuard.onTimedLink` calls
     * `DetailPlayerEvents.seekTo(seconds)` and returns `true` when the link's video is the current item)
     * and runs only on a device (step 10). What can be seen here is that `QueueGuard.kt` declares the
     * entry points the hooks L1, P5 and P6 call, and that the feature's own code makes the seek call at
     * all, in whichever of its files. See Q22 in `questions.md`.
     */
    @Test
    fun am10_theGlueSeeksForATimedLinkToTheCurrentItem() {
        val directory = "java/org/schabi/newpipe/enhancements/queueguard"
        val path = "$directory/QueueGuard.kt"
        val text = MainSources.file(path, "the entry point of queue-guard").readText()
        assertTrue("${shown(path)} declares fun onTimedLink(", "fun onTimedLink(" in text)
        assertTrue("${shown(path)} declares fun playersQueueFor( (hooks P5 and P6)", "fun playersQueueFor(" in text)
        val seeking = MainSources.resolve(directory).listFiles().orEmpty()
            .filter { "DetailPlayerEvents.seekTo(seconds)" in it.readText() }
            .map { it.name }
        assertTrue(
            "a file in ${shown(directory)} must call DetailPlayerEvents.seekTo(seconds) for a timed link to the " +
                "current item (plan A2.15)",
            seeking.isNotEmpty(),
        )
    }

    /**
     * L2 (AM-5): a video address entered in search. First statement of the lambda that starts the
     * intent. Its replay repeats the two upstream statements below it, because the lambda cannot call
     * itself (plan A2.3); so those two lines are pinned here: if upstream changes what the lambda does,
     * this test fails and the replay is brought back in step (plan A2.12).
     */
    @Test
    fun am5_hookL2_aTypedVideoAddressGoesThroughTheGuardAndItsReplayRepeatsTheTwoStatementsBelowIt() {
        assertEquals("L2: lines marked $marker in ${shown(searchFragment)}", 1, hookLines(searchFragment).size)
        val index = assertHook(
            searchFragment,
            "L2",
            "if (QG.onSearchAddress(SearchFragment.this, intent, " +
                "() -> { getFM().popBackStackImmediate(); activity.startActivity(intent); })) return; " +
                "// ENH:queue-guard",
            above = ".subscribe(intent -> {",
            below = listOf(
                "getFM().popBackStackImmediate();",
                "activity.startActivity(intent);",
                "}, throwable -> showTextError(getString(R.string.unsupported_url))));",
            ),
        )
        val lines = allLines(searchFragment)
        val replay = lines[index].substringAfter("() -> {").substringBefore("})) return;").trim()
        val upstream = lines[index + 1].trim() + " " + lines[index + 2].trim()
        assertEquals("L2: the replay is the lambda's own two statements, in their order", upstream, replay)
    }

    // =============================================================================================
    // Videos from other apps and links without a time: R1, R2 (AM-5, AM-6)
    // =============================================================================================

    /**
     * R1 (AM-6): every action of the router passes `handleChoice`. The hook sits after the popup
     * permission check (below its closing `}`) and before the first action, download: above the blank
     * line and `if (selectedChoiceKey.equals(getString(R.string.download_key))) {`. The replay calls
     * `handleChoice` again with the same key.
     */
    @Test
    fun am6_hookR1_everyRouterActionGoesThroughTheGuardAfterThePermissionCheck() {
        val lines = allLines(routerActivity)
        val index = assertHook(
            routerActivity,
            "R1",
            "if (QG.onRouterChoice(this, currentService, currentLinkType, currentUrl, selectedChoiceKey, " +
                "() -> handleChoice(selectedChoiceKey))) return; // ENH:queue-guard",
            above = "}",
            below = listOf("", "if (selectedChoiceKey.equals(getString(R.string.download_key))) {"),
        )
        val head = (index downTo 0).first { "void handleChoice(final String selectedChoiceKey)" in lines[it] }
        val permissionToast =
            (head until index).filter { "PermissionHelper.showPopupEnablementToast(this);" in lines[it] }
        assertEquals(
            "R1: the popup permission check lies between the head of handleChoice and the hook",
            1,
            permissionToast.size,
        )
        assertTrue(
            "R1: no other method starts between the head of handleChoice and the hook",
            (head + 1 until index).none { javaMethodHead.containsMatchIn(lines[it]) },
        )
    }

    /**
     * R2 (AM-6): the chooser finishes the router when it is dismissed; without R2 the router would
     * finish under the dialog R1 has just opened. First statement of the chooser's dismiss listener,
     * above `if (!selectionIsDownload && !selectionIsAddToPlaylist) {`.
     */
    @Test
    fun am6_hookR2_theChooserDoesNotFinishTheRouterWhileTheGuardIsAsking() {
        assertHook(
            routerActivity,
            "R2",
            "if (QG.isAsking(RouterActivity.this)) return; // ENH:queue-guard",
            above = ".setOnDismissListener((dialog) -> {",
            below = listOf("if (!selectionIsDownload && !selectionIsAddToPlaylist) {", "finish();"),
        )
    }

    /**
     * Plan A2.8: `RouterActivity.java` holds 2 marked lines, R1 with `QueueGuard.onRouterChoice(` and R2 with
     * `QueueGuard.isAsking(`.
     */
    @Test
    fun am6_routerActivityHoldsTwoMarkedLines_oneRouterChoiceAndOneIsAsking() {
        val lines = hookLines(routerActivity)
        assertEquals("R1, R2: lines marked $marker in ${shown(routerActivity)}", 2, lines.size)
        assertEquals(
            "R1: marked lines that call $queueGuard.onRouterChoice(",
            1,
            lines.count { "$queueGuard.onRouterChoice(" in it },
        )
        assertEquals(
            "R2: marked lines that call $queueGuard.isAsking(",
            1,
            lines.count { "$queueGuard.isAsking(" in it },
        )
    }
}
