package org.schabi.newpipe.enhancements.crashfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the marked lines the plan puts into upstream files, read as text. The
 * helpers are useless unless upstream calls them, most fixes have no unit test at all, and a
 * rebase can drop a marked line without any other test noticing.
 *
 * Per file, three kinds of checks:
 *
 * 1. **The number of lines containing `ENH:crash-fixes`.** The plan calls its counts "about",
 *    because a long edit may be wrapped over more marked lines (plan section 0). The exact count
 *    is asserted where the plan lists the lines one by one; otherwise only "at least one"
 *    (question Q6):
 *
 *    | File | Asserted | Why |
 *    |---|---|---|
 *    | `StreamDialogDefaultEntry` | at least 1 | "about 13"; the listed edits add up to 10 |
 *    | `QueueItemMenuUtil` | 5 | `:86` in `if { .. }` is 3 lines, `:64` and `:67` |
 *    | `SparseItemUtil` | 0 or 1 | step 1c is conditional, see [ac1_wiring_lateDeliveryOnlyTogetherWithItsHelper] |
 *    | `SearchFragment` | at least 1 | "9 lines", but the plan's code block has 5 |
 *    | `ChannelFragment` | 9 | the plan's code block has 9 lines |
 *    | `AudioReactor` | 7 | field 1, setter 3, guard 3 |
 *    | `Player` | at least 1 | "5 lines" for one statement, no line list |
 *    | `PlayerBroadcastReceiver` | 1 | "1 changed line" |
 *    | `DownloadMission` | 2 | `:373`, `:376` |
 *    | `DownloadInitializer` | 1 | `:187` |
 *    | `LocalPlaylistFragment` | 8 | four places of two lines |
 *
 * 2. **What the marked lines say.** The marked lines of a file are joined, the marker and all
 *    whitespace are removed, and the plan's calls are looked up in the result; a call wrapped
 *    over several marked lines is therefore still found.
 * 3. **That the faulty expression is gone** (C5, C6), looked up in the whole file.
 *
 * Until the Code layer adds the lines, every test here fails for the reason "no line marked
 * ENH:crash-fixes", except the `SparseItemUtil` one, which holds while step 1c is left out.
 */
class CrashFixesWiringTest {

    private companion object {
        const val MARKER = "ENH:crash-fixes"

        /** The marker of this package only: `ENH:crash-fixes-more` belongs to the follow-up package. */
        val OWN_MARKER = Regex("ENH:crash-fixes(?![-\\w])")
        const val HELPERS = "java/org/schabi/newpipe/enhancements/crashfixes"

        const val STREAM_DIALOG_ENTRY = "java/org/schabi/newpipe/info_list/dialog/StreamDialogDefaultEntry.java"
        const val QUEUE_ITEM_MENU = "java/org/schabi/newpipe/QueueItemMenuUtil.java"
        const val SPARSE_ITEM_UTIL = "java/org/schabi/newpipe/util/SparseItemUtil.java"
        const val SEARCH_FRAGMENT = "java/org/schabi/newpipe/fragments/list/search/SearchFragment.java"
        const val CHANNEL_FRAGMENT = "java/org/schabi/newpipe/fragments/list/channel/ChannelFragment.java"
        const val AUDIO_REACTOR = "java/org/schabi/newpipe/player/helper/AudioReactor.java"
        const val PLAYER = "java/org/schabi/newpipe/player/Player.java"
        const val PLAYER_BROADCAST_RECEIVER = "java/org/schabi/newpipe/player/PlayerBroadcastReceiver.kt"
        const val DOWNLOAD_MISSION = "java/us/shandian/giga/get/DownloadMission.java"
        const val DOWNLOAD_INITIALIZER = "java/us/shandian/giga/get/DownloadInitializer.java"
        const val LOCAL_PLAYLIST_FRAGMENT = "java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java"
    }

    /** `src/main/<path>` when run by Gradle (working directory `app/`), else `app/src/main/<path>`. */
    private fun mainFile(path: String): File {
        val inModule = File("src/main/$path")
        return if (inModule.exists()) inModule else File("app/src/main/$path")
    }

    private fun lines(ac: String, path: String): List<String> {
        val file = mainFile(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist: an upstream file the fix edits is gone or was moved")
        }
        return file.readLines()
    }

    private fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** The marked lines of a file. */
    private inner class Marked(val ac: String, val path: String) {
        val lines: List<String> = lines(ac, path).filter { OWN_MARKER.containsMatchIn(it) }

        /** Marked lines joined, without the marker comment and without whitespace. */
        val code: String = squeeze(
            lines.joinToString("") { it.replace("// $MARKER", "").replace("//$MARKER", "") }
        )

        fun expectAtLeastOne(): Marked {
            assertTrue("$ac: app/src/main/$path must have at least one line marked $MARKER", lines.isNotEmpty())
            return this
        }

        fun expectCount(expected: Int, what: String): Marked {
            assertEquals(
                "$ac: app/src/main/$path must have $expected lines marked $MARKER ($what)",
                expected,
                lines.size
            )
            return this
        }

        /** [snippet] must be found [times] times in the marked lines (whitespace ignored). */
        fun expectCode(snippet: String, times: Int = 1): Marked {
            val wanted = squeeze(snippet)
            val found = code.windowed(wanted.length).count { it == wanted }
            assertEquals(
                "$ac: the lines marked $MARKER in app/src/main/$path must contain `$snippet` " +
                    "$times time(s); marked lines: ${lines.map { it.trim() }}",
                times,
                found
            )
            return this
        }
    }

    /** [snippet] (whitespace ignored) must not occur anywhere in the file. */
    private fun assertGone(ac: String, path: String, snippet: String) {
        val wanted = squeeze(snippet)
        val text = squeeze(lines(ac, path).joinToString(""))
        assertEquals(
            "$ac: app/src/main/$path must no longer contain `$snippet`",
            0,
            text.windowed(wanted.length).count { it == wanted }
        )
    }

    /**
     * AC-1, fix 1a: Open channel, Add to playlist and Download are run through
     * `LatePage.ifUsable(fragment, ..)`; Enqueue takes its context at click time.
     * Count: at least one (the plan says "about 13", its listed edits add up to 10; Q6).
     */
    @Test
    fun ac1_wiring_listActionsAreGuardedByLatePage() {
        Marked("AC-1", STREAM_DIALOG_ENTRY)
            .expectAtLeastOne()
            .expectCode("LatePage.ifUsable(fragment, url -> openChannelFragment(fragment, item, url))")
            .expectCode("LatePage.ifUsable(fragment, dialog -> dialog.show(")
            .expectCode("LatePage.ifUsable(fragment, info -> {")
            .expectCode("LatePage.ifUsable(fragment,", times = 3)
            .expectCode("enqueueOnPlayer(context, singlePlayQueue)")
        // Enqueue no longer reads the context when the details arrive
        assertGone("AC-1", STREAM_DIALOG_ENTRY, "enqueueOnPlayer(fragment.getContext(), singlePlayQueue)")
    }

    /**
     * AC-1, fix 1b: the play queue's item menu shows its two dialogs only if `LateDialog` allows.
     * Count: 5 = `:86` wrapped in `if (..) { .. }` (3 lines) + `:64` + `:67`.
     */
    @Test
    fun ac1_wiring_queueItemMenuIsGuardedByLateDialog() {
        Marked("AC-1", QUEUE_ITEM_MENU)
            .expectCount(5, "plan fix 1b: :86 in an if block is 3 lines, :64 and :67")
            .expectCode("LateDialog.canShow(fragmentManager)) {")
            .expectCode("downloadDialog.show(fragmentManager, \"downloadDialog\");")
            .expectCode("LateDialog.ifCanShow(fragmentManager, dialog -> dialog.show(")
    }

    /**
     * AC-1, fix 1c, which the plan commits only if device step D1c reproduces the crash. So
     * both outcomes are right, but only as a whole: `SparseItemUtil` has its one marked line
     * (`LateDelivery.deliver(callback, result);` in place of `callback.accept(result);`) exactly
     * when `LateDelivery.kt` exists. This test passes before Code exists (neither is there).
     */
    @Test
    fun ac1_wiring_lateDeliveryOnlyTogetherWithItsHelper() {
        val marked = Marked("AC-1", SPARSE_ITEM_UTIL)
        if (mainFile("$HELPERS/LateDelivery.kt").isFile) {
            marked.expectCount(1, "plan fix 1c: LateDelivery.kt exists, so :127 must use it")
                .expectCode("LateDelivery.deliver(callback, result);")
        } else {
            marked.expectCount(0, "plan fix 1c was left out: LateDelivery.kt does not exist")
        }
    }

    /**
     * AC-2: `SearchFragment.onResume` asks `SearchResume.decide` and acts on both answers.
     * Count: at least one (the plan says 9 lines but shows 5; Q6).
     */
    @Test
    fun ac2_wiring_searchFragmentAsksSearchResume() {
        Marked("AC-2", SEARCH_FRAGMENT)
            .expectAtLeastOne()
            .expectCode("SearchResume.decide(isLoading.get(),")
            .expectCode("searchDisposable != null && !searchDisposable.isDisposed(),")
            .expectCode("TextUtils.isEmpty(searchString))")
            .expectCode("loadMoreItems();")
            .expectCode("startLoading(false);")
    }

    /** AC-3: `ChannelFragment` gets the 9-line `onResume` of the plan, which rebuilds dropped tabs. */
    @Test
    fun ac3_wiring_channelFragmentRebuildsTabsOnResume() {
        Marked("AC-3", CHANNEL_FRAGMENT)
            .expectCount(9, "plan C3: the new onResume method, comment line included")
            .expectCode("@Override public void onResume() { super.onResume();")
            .expectCode("if (currentInfo != null && tabAdapter != null && tabAdapter.getCount() == 0")
            .expectCode("&& !isContentUnsupported()) { updateTabs(); } }")
    }

    /** AC-4, fix 4a: `AudioReactor` has the override (field 1, setter 3, guard 3 lines). */
    @Test
    fun ac4_wiring_audioReactorAsksTheResumeOverride() {
        Marked("AC-4", AUDIO_REACTOR)
            .expectCount(7, "plan fix 4a: field 1 line, setter 3 lines, guard 3 lines")
            .expectCode("private java.util.function.BooleanSupplier resumeOverride = () -> false;")
            .expectCode("setResumeOverride(")
            .expectCode("if (resumeOverride.getAsBoolean()) { return; }")
    }

    /**
     * AC-4, fix 4a: `Player` routes focus gain to its own `play()` while a live stream is idle.
     * Count: at least one (one statement, "5 lines", no line list; Q6).
     */
    @Test
    fun ac4_wiring_playerSetsTheResumeOverride() {
        Marked("AC-4", PLAYER)
            .expectAtLeastOne()
            .expectCode("audioReactor.setResumeOverride(() -> {")
            .expectCode("final boolean idle = liveIdle;")
            .expectCode("if (idle) { play(); }")
            .expectCode("return idle;")
    }

    /** AC-4, fix 4b: the timestamp case, one changed line. */
    @Test
    fun ac4_wiring_broadcastReceiverResumesAnIdleLiveStream() {
        Marked("AC-4", PLAYER_BROADCAST_RECEIVER)
            .expectCount(1, "plan fix 4b: :41 changed")
            .expectCode("if (player.isLiveIdle()) player.play() else player.simpleExoPlayer.play()")
    }

    /** AC-5: both message checks of `DownloadMission.notifyError` go through `ErrorMessages`. */
    @Test
    fun ac5_wiring_downloadMissionUsesErrorMessages() {
        Marked("AC-5", DOWNLOAD_MISSION)
            .expectCount(2, "plan C5: :373 and :376")
            .expectCode("ErrorMessages.contains(err, \"Permission denied\")")
            .expectCode("ErrorMessages.contains(err, \"ENOSPC\")")
        assertGone("AC-5", DOWNLOAD_MISSION, "getMessage().contains(")
    }

    /** AC-5: the message check of `DownloadInitializer`'s catch block goes through `ErrorMessages`. */
    @Test
    fun ac5_wiring_downloadInitializerUsesErrorMessages() {
        Marked("AC-5", DOWNLOAD_INITIALIZER)
            .expectCount(1, "plan C5: :187")
            .expectCode("e instanceof IOException &&")
            .expectCode("ErrorMessages.contains(e, \"Permission denied\")")
        assertGone("AC-5", DOWNLOAD_INITIALIZER, "getMessage().contains(")
    }

    /**
     * AC-6: the four comparisons with the playlist's thumbnail (delete, remove duplicates,
     * remove watched twice) use `Objects.equals`, two lines each, and no `.equals` call on the
     * possibly null thumbnail is left.
     */
    @Test
    fun ac6_wiring_localPlaylistComparesThumbnailsNullSafely() {
        Marked("AC-6", LOCAL_PLAYLIST_FRAGMENT)
            .expectCount(8, "plan C6: :811-812, :882-883, :902-903, :1109-1110")
            .expectCode("Objects.equals(playlistManager.getPlaylistThumbnail(playlistId),", times = 4)
        assertGone("AC-6", LOCAL_PLAYLIST_FRAGMENT, "getPlaylistThumbnail(playlistId).equals(")
    }
}
