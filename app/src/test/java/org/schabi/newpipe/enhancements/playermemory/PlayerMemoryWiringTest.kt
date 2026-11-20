package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the marked lines the plan puts into four upstream files, read as text.
 * The new classes do nothing unless upstream calls them; `Player`, `MediaSourceManager` and the
 * controllers cannot run on the JVM; and a rebase can drop a marked line without any other test
 * noticing. For AC-6 (finding M8) the two marked lines are the whole fix, and this is its only
 * automated check; what the fix does on a device is D1 of the plan's device script.
 *
 * Per file:
 *
 * 1. **The number of lines containing `ENH:player-memory`.** The plan lists every line
 *    (section 0: "6 marked lines in 4 upstream files, none removed"), so the count is exact:
 *
 *    | File | Lines | Plan |
 *    |---|---|---|
 *    | `player/Player.java` | 1 | section 1: first statement of `setupMediaSession()` (added) |
 *    | `player/PlayerStartController.kt` | 1 | section 1: `:217` replaced by a comment (changed) |
 *    | `player/playback/MediaSourceManager.java` | 1 | section 2: after `maybeSync(isBlockReleased);` (added) |
 *    | `player/PlayerProgressController.kt` | 3 | section 3: `:18` and `:36` changed, one added after `saveWasPlaying()` |
 *    | total | 6 | |
 *
 * 2. **What the marked lines say.** The marked lines of a file are joined, the marker and all
 *    whitespace are removed, and the plan's code is looked up in the result.
 * 3. **Where they are**, where the place is the point: the dispose before the new session is
 *    made, the unload after the synchronization and inside its `if`, the touch in
 *    `onStartTrackingTouch`.
 * 4. **What is gone**: the second `setupMediaSession()` call, the upstream holder's use.
 *
 * Until the Code layer adds the lines, every test here fails for the reason "must have N lines
 * marked ENH:player-memory" (found 0), except `ac8_noResourceAndNoSettingOfThisFix`, which
 * holds before and after.
 */
class PlayerMemoryWiringTest {

    private companion object {
        const val MARKER = "ENH:player-memory"
        const val P = "org.schabi.newpipe.enhancements.playermemory."
        const val HELPERS = "java/org/schabi/newpipe/enhancements/playermemory"

        const val PLAYER = "java/org/schabi/newpipe/player/Player.java"
        const val START_CONTROLLER = "java/org/schabi/newpipe/player/PlayerStartController.kt"
        const val MEDIA_SOURCE_MANAGER = "java/org/schabi/newpipe/player/playback/MediaSourceManager.java"
        const val PROGRESS_CONTROLLER = "java/org/schabi/newpipe/player/PlayerProgressController.kt"

        /** File to its number of marked lines (plan section 0). */
        val EXPECTED = linkedMapOf(
            PLAYER to 1,
            START_CONTROLLER to 1,
            MEDIA_SOURCE_MANAGER to 1,
            PROGRESS_CONTROLLER to 3
        )
    }

    private fun squeeze(text: String): String = MainFiles.squeeze(text)

    /** A file of `app/src/main` and its marked lines. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = MainFiles.lines(ac, path)
        val lines: List<String> = all.filter { it.contains(MARKER) }
        val text: String = all.joinToString("\n")

        /** Marked lines joined, without the marker comment and without whitespace. */
        val code: String = squeeze(lines.joinToString("") { it.replace("// $MARKER", "").replace("//$MARKER", "") })

        fun expectCount(what: String): Marked {
            val expected = EXPECTED.getValue(path)
            assertEquals("$ac: app/src/main/$path must have $expected line(s) marked $MARKER ($what)", expected, lines.size)
            return this
        }

        /** [snippet] must be found [times] times in the marked lines (whitespace ignored). */
        fun expectCode(snippet: String, times: Int = 1): Marked {
            val wanted = squeeze(snippet)
            val found = code.windowed(wanted.length).count { it == wanted }
            assertEquals(
                "$ac: the lines marked $MARKER in app/src/main/$path must contain `$snippet` $times time(s); " +
                    "marked lines: ${lines.map { it.trim() }}",
                times,
                found
            )
            return this
        }

        /** How often [snippet] (whitespace ignored) occurs anywhere in the file. */
        fun count(snippet: String): Int = MainFiles.count(text, snippet)

        fun expectGone(snippet: String): Marked {
            assertEquals("$ac: app/src/main/$path must no longer contain `$snippet`", 0, count(snippet))
            return this
        }

        /**
         * Index of the only line whose code (whitespace and a trailing marker comment removed)
         * *is* [statement]. Equality, not containment: `saveWasPlaying()` must not find
         * `fun saveWasPlaying() {`, nor `setupMediaSession();` its declaration.
         */
        fun lineThatIs(statement: String, marked: Boolean): Int {
            val wanted = squeeze(statement)
            val hits = all.indices.filter {
                val isMarked = all[it].contains(MARKER)
                squeeze(all[it].substringBefore("// $MARKER").substringBefore("//$MARKER")) == wanted && isMarked == marked
            }
            assertEquals(
                "$ac: app/src/main/$path must have exactly one ${if (marked) "marked" else "unmarked"} line that is `$statement`",
                1,
                hits.size
            )
            return hits[0]
        }

        /** The name of the function whose declaration is the nearest one above line [index]. */
        fun functionAround(index: Int): String? {
            val declaration = Regex("""\bfun\s+(\w+)\s*\(|\bvoid\s+(\w+)\s*\(""")
            for (i in index downTo 0) {
                val match = declaration.find(all[i]) ?: continue
                return match.groupValues[1].ifEmpty { match.groupValues[2] }
            }
            return null
        }
    }

    // ---- section 1, M8 ----

    /**
     * AC-6: "starting playback creates one media session and releases the one before":
     * `setupMediaSession()` disposes the manager it replaces, as its first statement.
     */
    @Test
    fun ac6_wiring_setupMediaSessionDisposesWhatItReplaces() {
        val file = Marked("AC-6", PLAYER)
            .expectCount("plan section 1: the new first statement of setupMediaSession()")
            .expectCode("if (mediaSessionManager != null) { mediaSessionManager.dispose(); }")

        val guard = file.lineThatIs("if (mediaSessionManager != null) { mediaSessionManager.dispose(); }", marked = true)
        val declaration = file.lineThatIs("void setupMediaSession() {", marked = false)
        val newSession = file.lineThatIs("playerMediaSession = new PlayerMediaSession(this, simpleExoPlayer);", marked = false)
        assertEquals("AC-6: the dispose is the first statement of setupMediaSession()", declaration + 1, guard)
        assertEquals("AC-6: the old manager is disposed before the new session is made", guard + 1, newSession)
        assertEquals("setupMediaSession", file.functionAround(guard))

        // Section 1: "The call at Player.java:353 is kept"; destroyPlayer keeps disposing the manager.
        val call = file.lineThatIs("setupMediaSession();", marked = false)
        assertEquals("AC-6: initPlayer still creates the session", "initPlayer", file.functionAround(call))
        assertEquals(
            "AC-6: destroyPlayer's own dispose stays, next to the new one",
            2,
            file.count("mediaSessionManager.dispose();")
        )
        assertEquals("AC-6: the manager is created in one place", 1, file.count("mediaSessionManager = new MediaSessionManager("))
    }

    /**
     * AC-6: "starting playback creates one media session": the second `setupMediaSession()` of
     * a playback start, in `initPlayback`, is replaced in place by the marked comment.
     */
    @Test
    fun ac6_wiring_initPlaybackNoLongerCreatesASecondSession() {
        val file = Marked("AC-6", START_CONTROLLER)
            .expectCount("plan section 1: :217 replaced in place by a comment")
            .expectGone("setupMediaSession")

        val comment = file.all.indexOfFirst { it.contains(MARKER) }
        assertTrue(
            "AC-6: the marked line of app/src/main/$START_CONTROLLER is the comment that took the call's place, " +
                "was `${file.all[comment].trim()}`",
            file.all[comment].trim().startsWith("// $MARKER")
        )
        val shuffle = file.lineThatIs("player.simpleExoPlayer.setShuffleModeEnabled(playQueue.isShuffled)", marked = false)
        assertEquals("AC-6: the comment is where the call was, directly after setShuffleModeEnabled", shuffle + 1, comment)
        // The start still builds the player, and with it the one session, first.
        val init = file.lineThatIs("player.initPlayer(playOnReady)", marked = false)
        val destroy = file.lineThatIs("player.destroyPlayer()", marked = false)
        assertTrue("AC-6: destroyPlayer() (releases the session before), initPlayer() (creates the one), then the comment", destroy < init && init < comment)
    }

    /** AC-6: `setupMediaSession` has exactly one caller left in the whole app: `initPlayer`. */
    @Test
    fun ac6_wiring_setupMediaSessionHasOneCaller() {
        Marked("AC-6", START_CONTROLLER).expectCount("plan section 1") // so that the test fails until the fix is in
        val mentions = sourceFiles()
            .associate { it.name to MainFiles.count(it.readText(), "setupMediaSession(") }
            .filterValues { it > 0 }

        assertEquals(
            "AC-6: setupMediaSession( appears as the declaration and the call in initPlayer, both in Player.java, and nowhere else",
            mapOf("Player.java" to 2),
            mentions
        )
    }

    // ---- section 2, M5 ----

    /**
     * AC-4: items outside the window are unloaded after every synchronization of the player
     * with the queue's current item, and only there (plan section 2, "Why there and nowhere else").
     */
    @Test
    fun ac4_wiring_mediaSourceManagerUnloadsAfterTheSynchronization() {
        val call = "${P}SourceUnloader.unloadOutsideWindow(playlist, playQueue.getIndex());"
        val file = Marked("AC-4", MEDIA_SOURCE_MANAGER)
            .expectCount("plan section 2: one line after maybeSync(isBlockReleased);")
            .expectCode(call)

        val unload = file.lineThatIs(call, marked = true)
        val sync = file.lineThatIs("maybeSync(isBlockReleased);", marked = false)
        assertEquals("AC-4: the unload directly follows maybeSync(isBlockReleased);", sync + 1, unload)
        assertEquals("AC-4: the unload runs in maybeSynchronizePlayer", "maybeSynchronizePlayer", file.functionAround(unload))
        assertEquals(
            "AC-4: inside the `if (isPlayQueueReady() && isPlaybackReady())`: the line after the unload closes it",
            "}",
            file.all[unload + 1].trim()
        )
        assertEquals(
            "AC-4/AC-5: the unload is as deep as maybeSync, so it runs only after a synchronization",
            file.all[sync].takeWhile { it == ' ' }.length,
            file.all[unload].takeWhile { it == ' ' }.length
        )
        assertEquals("AC-4: unloadOutsideWindow is called once in the file", 1, file.count("unloadOutsideWindow("))
    }

    /** AC-4, plan sections 2 and 6: nothing else in the app unloads (not `loadImmediate`, not `PlayerTransportController`). */
    @Test
    fun ac4_wiring_noOtherCallerUnloads() {
        Marked("AC-4", MEDIA_SOURCE_MANAGER).expectCount("plan section 2") // so that the test fails until the fix is in
        val helpers = MainFiles.file(HELPERS).canonicalFile
        val callers = sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .filter { it.readText().contains("unloadOutsideWindow") }
            .map { it.name }

        assertEquals("AC-4: the only upstream file that unloads", listOf("MediaSourceManager.java"), callers)
    }

    // ---- section 3, M4 ----

    /** AC-1, AC-2: the player's previews are the lazy ones; the upstream holder is no longer used here. */
    @Test
    fun ac1_wiring_progressControllerUsesTheLazyPreviews() {
        val file = Marked("AC-1", PROGRESS_CONTROLLER)
            .expectCount("plan section 3: import and field changed, one line added after saveWasPlaying()")
            .expectCode("import ${P}LazySeekbarPreviews")
            .expectCode("private val seekbarPreviewThumbnailHolder = LazySeekbarPreviews(player, ::onProgressChanged)")
            .expectCode("seekbarPreviewThumbnailHolder.onSeekbarTouched(seekBar.progress)")
            .expectGone("SeekbarPreviewThumbnailHolder")

        val import = file.lineThatIs("import ${P}LazySeekbarPreviews", marked = true)
        val field = file.lineThatIs("private val seekbarPreviewThumbnailHolder = LazySeekbarPreviews(player, ::onProgressChanged)", marked = true)
        val classLine = file.all.indexOfFirst { it.startsWith("class PlayerProgressController(") }
        assertTrue("the import is above the class, the field inside it", import < classLine && classLine < field)

        // ":44 and :199 are unchanged: the new class has the same two method names and types."
        val reset = file.lineThatIs("seekbarPreviewThumbnailHolder.resetFrom(player.context, frames)", marked = false)
        assertEquals("AC-2: the item change still resets the previews", "resetPreviewThumbnails", file.functionAround(reset))
        val bitmap = file.lineThatIs("val previewThumbnail = seekbarPreviewThumbnailHolder.getBitmapAt(progress)", marked = false)
        assertEquals("AC-7: the preview is still asked for in onProgressChanged", "onProgressChanged", file.functionAround(bitmap))
        assertEquals("AC-1: one previews object per player", 1, file.count("LazySeekbarPreviews("))
        // The helper of the same package keeps its import (its name differs from the holder's only at the end).
        assertEquals(1, file.count("import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper"))
    }

    /** AC-1: "not before the user first touches the seekbar of that item": the touch is reported from `onStartTrackingTouch`. */
    @Test
    fun ac1_wiring_theFirstTouchIsReportedFromOnStartTrackingTouch() {
        val file = Marked("AC-1", PROGRESS_CONTROLLER)
            .expectCount("plan section 3")
            .expectCode("seekbarPreviewThumbnailHolder.onSeekbarTouched(seekBar.progress)")

        val touched = file.lineThatIs("seekbarPreviewThumbnailHolder.onSeekbarTouched(seekBar.progress)", marked = true)
        val save = file.lineThatIs("saveWasPlaying()", marked = false)
        assertEquals("AC-1: the touch is reported directly after saveWasPlaying()", save + 1, touched)
        assertEquals("AC-1: in the seekbar's touch callback", "onStartTrackingTouch", file.functionAround(touched))
        assertEquals("AC-1: and nowhere else", 1, file.count("onSeekbarTouched("))
    }

    /**
     * AC-1: no other upstream file talks to the lazy previews (the queue screen has its own
     * seekbar listener and must not arm them), and nothing uses the upstream holder any more:
     * it "stays in the tree, unused", so nothing can start its 48 downloads.
     */
    @Test
    fun ac1_wiring_onlyTheProgressControllerUsesPreviews_andNothingUsesTheUpstreamHolder() {
        Marked("AC-1", PROGRESS_CONTROLLER).expectCount("plan section 3") // so that the test fails until the fix is in
        val helpers = MainFiles.file(HELPERS).canonicalFile
        val upstream = sourceFiles().filter { !it.canonicalFile.startsWith(helpers) }

        assertEquals(
            "AC-1: the only upstream file that names LazySeekbarPreviews",
            listOf("PlayerProgressController.kt"),
            upstream.filter { it.readText().contains("LazySeekbarPreviews") }.map { it.name }
        )
        assertEquals(
            "AC-1: in upstream code the old holder is named only by its own file",
            listOf("SeekbarPreviewThumbnailHolder.java"),
            upstream.filter { it.readText().contains("SeekbarPreviewThumbnailHolder") }.map { it.name }
        )
    }

    // ---- the whole change ----

    private fun sourceFiles(): List<File> {
        val root = MainFiles.file("java")
        if (!root.isDirectory) fail("app/src/main/java does not exist")
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    /**
     * Plan section 0 and 7: marked lines in 4 upstream files, 6 in all ("`git grep
     * "ENH:player-memory"` lists 6 lines"), and nowhere else. A marked line in a fifth file is
     * an edit the plan does not have.
     */
    @Test
    fun wiring_fourUpstreamFiles_6MarkedLines_andNoOther() {
        val helpers = MainFiles.file(HELPERS).canonicalFile
        val root = MainFiles.file("").canonicalFile
        val found = sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .associate { it.canonicalFile.relativeTo(root).invariantSeparatorsPath to it.readLines().count { line -> line.contains(MARKER) } }
            .filterValues { it > 0 }

        assertEquals("plan section 0: the marked lines per upstream file", EXPECTED.toSortedMap(), found.toSortedMap())
        assertEquals(6, found.values.sum())
        // "git grep lists 6 lines": the new files themselves carry no marker.
        val inHelpers = sourceFiles().filter { it.canonicalFile.startsWith(helpers) }.sumOf { it.readLines().count { line -> line.contains(MARKER) } }
        assertEquals("plan section 7: no marker inside the new package, so that git grep lists exactly the 6 hooks", 0, inHelpers)
    }

    /**
     * AC-8 (no new setting) and plan section 0: no resource of this fix. Holds before Code
     * exists; it fails when a resource file, a string or a preference key of the fix appears.
     */
    @Test
    fun ac8_noResourceAndNoSettingOfThisFix() {
        val res = MainFiles.file("res")
        assertTrue("app/src/main/res exists", res.isDirectory)
        val named = res.walkTopDown().filter { it.isFile }.filter {
            it.name.contains("player_memory") || it.name.contains("playermemory")
        }.map { it.name }.toList()
        assertEquals("AC-8: no resource file of this fix", emptyList<String>(), named)
        val mentioning = res.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { file -> file.readText().let { it.contains("player_memory") || it.contains("playermemory") || it.contains(MARKER) } }
            .map { it.name }.toList()
        assertEquals("AC-8: no string, key, default or preference of this fix in res", emptyList<String>(), mentioning)
    }

    /** AC-8: the new package reads no preference of its own; the only setting it looks at is upstream's preview quality. */
    @Test
    fun ac8_theNewPackageReadsNoPreferenceOfItsOwn() {
        val dir = MainFiles.file(HELPERS)
        if (!dir.isDirectory) fail("AC-8: app/src/main/$HELPERS does not exist")
        val offenders = dir.listFiles()!!.filter { file ->
            val text = file.readText()
            text.contains("SharedPreferences") || text.contains("PreferenceManager") || text.contains("R.string.")
        }.map { it.name }

        assertEquals(
            "AC-8: the new files read no preference themselves (the preview quality comes from " +
                "SeekbarPreviewThumbnailHelper.getSeekbarPreviewThumbnailType, as upstream)",
            emptyList<String>(),
            offenders
        )
    }
}
