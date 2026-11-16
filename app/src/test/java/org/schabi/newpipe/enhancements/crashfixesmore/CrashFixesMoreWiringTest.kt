package org.schabi.newpipe.enhancements.crashfixesmore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the marked lines the plan puts into upstream files, read as text.
 *
 * This package adds no class of its own (plan section 0): every fix is a marked line inside
 * upstream code. So this source scan is the main contract on the JVM. A rebase that drops or
 * moves one of the lines is noticed by nothing else that runs in CI.
 *
 * The plan lists every line (39 lines in 10 upstream files), so each test asserts, per file:
 *
 * 1. **The exact number of lines marked `ENH:crash-fixes-more`**, and that each of them *ends*
 *    with `// ENH:crash-fixes-more` (plan section 0).
 * 2. **The text of every marked line**, compared with the plan's line after the marker comment
 *    and all whitespace are removed, and that the lines of one edit follow each other without
 *    a gap.
 * 3. **The position**, where the plan fixes it: the unmarked line directly before or directly
 *    after the edit.
 * 4. **That the faulty expression is gone**, looked up in the whole file.
 *
 * The marker of the parent package, `ENH:crash-fixes`, is a prefix of this package's marker.
 * Lines are counted with a regular expression that requires `-more` and nothing after it, so
 * a parent line is never counted here (see [markerOfThisPackageIsNotTheParents]); the parent's
 * own wiring test does the reverse.
 *
 * A failure message starts with the acceptance criterion and the plan step the line belongs
 * to, for example `AC-5, plan C5b`. Until the Code layer adds the lines, every `*_wiring_*`
 * test of an edited file fails with "must have N lines marked"; the tests about files the plan
 * leaves alone pass from the start and guard against a marked line appearing there.
 */
class CrashFixesMoreWiringTest {

    private companion object {
        const val MARKER = "ENH:crash-fixes-more"

        /** This package's marker: not followed by `-` or a word character. */
        val OWN_MARKER = Regex("ENH:crash-fixes-more(?![-\\w])")

        /** The parent's marker only: `ENH:crash-fixes` not followed by `-` or a word character. */
        val PARENT_MARKER = Regex("ENH:crash-fixes(?![-\\w])")

        const val NEWPIPE = "java/org/schabi/newpipe"
        const val LATE_DIALOG = "org.schabi.newpipe.enhancements.crashfixes.LateDialog"
        const val LATE_PAGE = "org.schabi.newpipe.enhancements.crashfixes.LatePage"

        const val PLAYER_CLICK = "$NEWPIPE/player/PlayerClickController.kt"
        const val ROUTER = "$NEWPIPE/RouterActivity.java"
        const val DETAIL_ACTIONS = "$NEWPIPE/fragments/detail/DetailActions.kt"
        const val SELECTION_ACTIONS = "$NEWPIPE/util/selection/StreamSelectionActions.java"
        const val LOCAL_PLAYLIST_FRAGMENT = "$NEWPIPE/local/playlist/LocalPlaylistFragment.java"
        const val PLAYLIST_FRAGMENT = "$NEWPIPE/fragments/list/playlist/PlaylistFragment.java"
        const val CHANNEL_FRAGMENT = "$NEWPIPE/fragments/list/channel/ChannelFragment.java"
        const val TAB_ADAPTER = "$NEWPIPE/fragments/detail/TabAdapter.java"
        const val APPEND_DIALOG = "$NEWPIPE/local/dialog/PlaylistAppendDialog.java"
        const val PROGRESS_CONTROLLER = "$NEWPIPE/player/PlayerProgressController.kt"

        const val PLAYLIST_MANAGER = "$NEWPIPE/local/playlist/LocalPlaylistManager.java"

        /** Plan, "Verification": marked lines per upstream file. Nothing else may be marked. */
        val PLANNED = linkedMapOf(
            PLAYER_CLICK to 1,
            ROUTER to 8,
            DETAIL_ACTIONS to 1,
            SELECTION_ACTIONS to 2,
            LOCAL_PLAYLIST_FRAGMENT to 1,
            PLAYLIST_FRAGMENT to 1,
            CHANNEL_FRAGMENT to 11,
            TAB_ADAPTER to 12,
            APPEND_DIALOG to 1,
            PROGRESS_CONTROLLER to 1
        )
        const val PLANNED_TOTAL = 39
    }

    /** `src/main` when run by Gradle (working directory `app/`), else `app/src/main`. */
    private fun mainDir(): File {
        val inModule = File("src/main")
        return if (inModule.isDirectory) inModule else File("app/src/main")
    }

    private fun lines(where: String, path: String): List<String> {
        val file = File(mainDir(), path)
        if (!file.isFile) {
            fail("$where: app/src/main/$path does not exist: an upstream file the plan names is gone or was moved")
        }
        return file.readLines()
    }

    private fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** A line without this package's marker comment and without whitespace. */
    private fun code(line: String): String = squeeze(line.replace("// $MARKER", "").replace("//$MARKER", ""))

    /** One upstream file and what the plan puts into it. [ac] is the criterion of the file's fixes. */
    private inner class Source(val ac: String, val path: String) {
        val all: List<String> = lines(ac, path)
        val marked: List<Int> = all.indices.filter { OWN_MARKER.containsMatchIn(all[it]) }

        fun expectCount(expected: Int, what: String): Source {
            assertEquals(
                "$ac: app/src/main/$path must have $expected line(s) marked $MARKER ($what); " +
                    "marked now: ${marked.map { "${it + 1}: ${all[it].trim()}" }}",
                expected,
                marked.size
            )
            for (index in marked) {
                assertTrue(
                    "$ac: line ${index + 1} of app/src/main/$path must end with `// $MARKER` " +
                        "(plan section 0): ${all[index].trim()}",
                    all[index].trimEnd().endsWith("// $MARKER")
                )
                assertFalse(
                    "$ac: line ${index + 1} of app/src/main/$path carries the parent's marker too; " +
                        "this package does not change a line of the parent: ${all[index].trim()}",
                    PARENT_MARKER.containsMatchIn(all[index])
                )
            }
            return this
        }

        /**
         * The edit [step] of the plan: [expected] are its lines, in order and without a gap,
         * each marked. An entry that ends with `…` is compared as a prefix. [after] is the
         * line that must stand directly before the edit, [before] the one directly after it.
         */
        fun expectEdit(
            step: String,
            expected: List<String>,
            after: String? = null,
            before: String? = null
        ): Source {
            val where = "$ac, plan $step"

            fun matches(index: Int, wanted: String): Boolean {
                if (index !in all.indices || index !in marked) {
                    return false
                }
                val have = code(all[index])
                return if (wanted.endsWith("…")) {
                    have.startsWith(squeeze(wanted.removeSuffix("…")))
                } else {
                    have == squeeze(wanted)
                }
            }

            val starts = marked.filter { matches(it, expected[0]) }
            assertEquals(
                "$where: app/src/main/$path must have exactly one line marked $MARKER that says " +
                    "`${expected[0]}`; marked lines now: ${marked.map { all[it].trim() }}",
                1,
                starts.size
            )
            val start = starts[0]
            for (offset in expected.indices) {
                val index = start + offset
                assertTrue(
                    "$where: line ${index + 1} of app/src/main/$path must be the marked line " +
                        "`${expected[offset]}` (line ${offset + 1} of ${expected.size} of this edit, " +
                        "no other line in between); it is: ${all.getOrNull(index)?.trim()}",
                    matches(index, expected[offset])
                )
            }
            if (after != null) {
                assertEquals(
                    "$where: in app/src/main/$path the edit must directly follow the line `$after`",
                    squeeze(after),
                    squeeze(all.getOrElse(start - 1) { "" })
                )
            }
            if (before != null) {
                assertEquals(
                    "$where: in app/src/main/$path the edit must be directly followed by the line `$before`",
                    squeeze(before),
                    squeeze(all.getOrElse(start + expected.size) { "" })
                )
            }
            return this
        }

        /** [snippet] (whitespace ignored) must not occur anywhere in the file. */
        fun expectGone(step: String, snippet: String): Source {
            assertFalse(
                "$ac, plan $step: app/src/main/$path must no longer contain `$snippet`",
                squeeze(all.joinToString("")).contains(squeeze(snippet))
            )
            return this
        }

        /** [line] (whitespace ignored) must still be a whole, unmarked line of the file. */
        fun expectUntouched(step: String, line: String): Source {
            assertTrue(
                "$ac, plan $step: app/src/main/$path must still have the unmarked line `$line` " +
                    "(the plan does not change it)",
                all.any { squeeze(it) == squeeze(line) }
            )
            return this
        }
    }

    // ------------------------------------------------------------------------------------
    // AC-1: dialogs shown after a wait
    // ------------------------------------------------------------------------------------

    /**
     * AC-1, plan C1a: "Add to playlist" from the player shows its dialog only if `LateDialog`
     * allows it on the fragment manager captured at the tap. One changed line.
     */
    @Test
    fun ac1_wiring_playerDialog() {
        Source("AC-1", PLAYER_CLICK)
            .expectEdit(
                "C1a",
                listOf(") { dialog -> if ($LATE_DIALOG.canShow(fragmentManager)) dialog.show(fragmentManager, Player.TAG) }"),
                after = "playQueue.streams.map { StreamEntity(it) },"
            )
            .expectGone("C1a", "{ dialog -> dialog.show(fragmentManager, Player.TAG) }")
            .expectCount(1, "plan C1a: the callback of createCorrespondingDialog")
    }

    /**
     * AC-1, plan C1b and C1c: the share menu's "Add to playlist" and "Download" finish the
     * share screen instead of showing a dialog that cannot be shown (answer A3). Four inserted
     * lines each, as the first statement of the callback (C1b) and directly after the fragment
     * manager was taken (C1c), so before the dialog is created or shown.
     */
    @Test
    fun ac1_wiring_shareMenu() {
        Source("AC-1", ROUTER)
            .expectEdit(
                "C1b",
                listOf(
                    "if (!$LATE_DIALOG.canShow(getSupportFragmentManager())) {",
                    "finish();",
                    "return;",
                    "}"
                ),
                after = "playlistDialog -> {",
                before = "playlistDialog.setOnDismissListener(dialog -> finish());"
            )
            .expectEdit(
                "C1c",
                listOf(
                    "if (!$LATE_DIALOG.canShow(fm)) {",
                    "finish();",
                    "return;",
                    "}"
                ),
                after = "final FragmentManager fm = getSupportFragmentManager();",
                before = "final DownloadDialog downloadDialog ="
            )
            .expectCount(8, "plan C1b: 4 lines in openAddToPlaylistDialog, plan C1c: 4 lines in openDownloadDialog")
    }

    /**
     * AC-1, plan C1d: "Add to playlist" on the video detail page. One changed line.
     * Plan C1e (the page's Download) is not confirmed: its two lines stay as they are.
     */
    @Test
    fun ac1_wiring_detailPage() {
        Source("AC-1", DETAIL_ACTIONS)
            .expectEdit(
                "C1d",
                listOf("if ($LATE_DIALOG.canShow(fm)) it.show(fm, TAG)"),
                after = "disposables.add(PlaylistDialog.createCorrespondingDialog(fragment.context, streams) {",
                before = "})"
            )
            .expectGone("C1d", "streams) { it.show(fm, TAG)")
            .expectUntouched("C1e (not confirmed, no edit)", "DownloadDialog.newInstance(activity, info)")
            .expectUntouched("C1e (not confirmed, no edit)", ".show(activity.supportFragmentManager, \"downloadDialog\")")
            .expectCount(1, "plan C1d: showPlaylistDialog; C1e, openDownloadDialog, gets no edit")
    }

    /**
     * AC-1, plan C1f: "Add to playlist" from a selection runs through `LatePage.ifUsable`.
     * Two changed lines (the call is wrapped as upstream wrapped it).
     */
    @Test
    fun ac1_wiring_selection() {
        Source("AC-1", SELECTION_ACTIONS)
            .expectEdit(
                "C1f",
                listOf(
                    "streams, $LATE_PAGE.ifUsable(fragment, dialog -> dialog.show(fragment.getParentFragmentManager(),",
                    "\"StreamSelectionActions@playlist\"))));"
                ),
                after = "disposables.add(PlaylistDialog.createCorrespondingDialog(fragment.requireContext(),",
                before = "} else if (id == R.id.action_selection_enqueue) {"
            )
            .expectGone("C1f", "streams, dialog -> dialog.show(")
            .expectCount(2, "plan C1f: the two lines of the callback")
    }

    /**
     * AC-1, plan C1g: a local playlist's "append to another playlist". One changed line.
     * The file also carries the parent's 8 lines; they are not counted here.
     */
    @Test
    fun ac1_wiring_localPlaylist() {
        Source("AC-1", LOCAL_PLAYLIST_FRAGMENT)
            .expectEdit(
                "C1g",
                listOf("$LATE_PAGE.ifUsable(this, dialog -> dialog.show(getParentFragmentManager(), TAG))"),
                after = "streamEntities,",
                before = "));"
            )
            .expectGone("C1g", "streamEntities, dialog -> dialog.show(")
            .expectCount(1, "plan C1g: the callback in appendAllToOtherPlaylist")
    }

    /** AC-1, plan C1h: "Add to playlist" in a remote playlist's menu. One changed line. */
    @Test
    fun ac1_wiring_remotePlaylist() {
        Source("AC-1", PLAYLIST_FRAGMENT)
            .expectEdit(
                "C1h",
                listOf("$LATE_PAGE.ifUsable(this, dialog -> dialog.show(getFM(), TAG))"),
                after = ".collect(Collectors.toList()),",
                before = "));"
            )
            .expectGone("C1h", ".collect(Collectors.toList()), dialog -> dialog.show(")
            .expectCount(1, "plan C1h: the callback in onOptionsItemSelected")
    }

    // ------------------------------------------------------------------------------------
    // AC-2 and AC-5: the channel page
    // ------------------------------------------------------------------------------------

    /**
     * AC-2, plan C2b (10 lines in three places) and AC-5, plan C5a (1 changed line), all in
     * `ChannelFragment`. The parent's 9 lines of `onResume` are in the same file and are not
     * counted here.
     *
     * - C2b.1: the field, directly after `private int lastTab;`.
     * - C2b.2: the kept result is handled first thing in the parent's `onResume`, directly
     *   after its `super.onResume();`.
     * - C2b.3: a blocked result is kept instead of going back when the page has no view or its
     *   state is saved; directly before the toast, so inside the "blocked" branch. The
     *   condition is deliberately not `LatePage.usable` (plan C2b).
     * - C2a (not confirmed): both `requireContext()` lines stay.
     * - C5a: titles are set only for tabs the tab bar has.
     */
    @Test
    fun ac2_ac5_wiring_channelPage() {
        Source("AC-2", CHANNEL_FRAGMENT)
            .expectEdit(
                "C2b.1 (field)",
                listOf("private ChannelInfo lateBlockedResult;"),
                after = "private int lastTab;"
            )
            .expectEdit(
                "C2b.2 (onResume)",
                listOf(
                    "if (lateBlockedResult != null) {",
                    "final ChannelInfo late = lateBlockedResult;",
                    "lateBlockedResult = null;",
                    "handleResult(late);",
                    "}"
                ),
                after = "super.onResume(); // ENH:crash-fixes"
            )
            .expectEdit(
                "C2b.3 (guard in handleResult)",
                listOf(
                    "if (getView() == null || getParentFragmentManager().isStateSaved()) {",
                    "lateBlockedResult = result;",
                    "return;",
                    "}"
                ),
                after = "new HashSet<>()).contains(channelKey))) {",
                before = "Toast.makeText(requireContext(),"
            )
            .expectUntouched("C2a (not confirmed, no edit)", ".getDefaultSharedPreferences(requireContext());")
            .expectUntouched("C2a (not confirmed, no edit)", "getParentFragmentManager().popBackStack();")
        Source("AC-5", CHANNEL_FRAGMENT)
            .expectEdit(
                "C5a",
                listOf("for (int i = 0; i < Math.min(tabAdapter.getCount(), binding.tabLayout.getTabCount()); i++) {"),
                before = "binding.tabLayout.getTabAt(i).setText(tabAdapter.getItemTitle(i));"
            )
            .expectGone("C5a", "i < tabAdapter.getCount(); i++")
        Source("AC-2/AC-5", CHANNEL_FRAGMENT)
            .expectCount(11, "plan C2b: field 1 + onResume 5 + handleResult 4 lines; plan C5a: 1 line")
    }

    /**
     * AC-5, plan C5b: `TabAdapter` overrides `finishUpdate`, swallows the pager's
     * `IllegalStateException` and logs it once. 12 inserted lines directly after the closing
     * brace of `destroyItem`, without a blank line (plan C5b). The text of the log message is
     * not fixed here; its tag, the stack trace argument and the "once" guard are. What the
     * lines do when they run is in [TabAdapterFinishUpdateTest].
     */
    @Test
    fun ac5_wiring_tabAdapter() {
        Source("AC-5", TAB_ADAPTER)
            .expectEdit(
                "C5b",
                listOf(
                    "private boolean pagerFailed;",
                    "@Override",
                    "public void finishUpdate(@NonNull final ViewGroup container) {",
                    "try {",
                    "super.finishUpdate(container);",
                    "} catch (final IllegalStateException e) {",
                    "if (!pagerFailed) {",
                    "Log.e(\"TabAdapter\", \"…",
                    "}",
                    "pagerFailed = true;",
                    "}",
                    "}"
                ),
                after = "}"
            )
            .expectUntouched(
                "C5b (position: after destroyItem)",
                "fragmentManager.beginTransaction().remove((Fragment) object).commitNowAllowingStateLoss();"
            )
            // the existing catch in notifyDataSetChanged stays (plan C5b)
            .expectUntouched("C5b (the existing printStackTrace stays)", "e.printStackTrace();")
            .expectCount(12, "plan C5b: the field and the finishUpdate override")
        val source = Source("AC-5, plan C5b", TAB_ADAPTER)
        val start = source.marked.first()
        assertEquals(
            "AC-5, plan C5b: the 12 lines must directly follow the last statement and the closing brace " +
                "of destroyItem in app/src/main/$TAB_ADAPTER",
            squeeze("fragmentManager.beginTransaction().remove((Fragment) object).commitNowAllowingStateLoss();"),
            squeeze(source.all.getOrElse(start - 2) { "" })
        )
        val logLine = source.marked.map { code(source.all[it]) }.single { it.startsWith("Log.e(") }
        assertTrue(
            "AC-5, plan C5b: the log line in app/src/main/$TAB_ADAPTER must pass the exception, " +
                "`Log.e(\"TabAdapter\", \"..\", e);`, so that the failure is in the log with its stack trace",
            logLine.endsWith(",e);")
        )
    }

    // ------------------------------------------------------------------------------------
    // AC-3: playlists without a thumbnail
    // ------------------------------------------------------------------------------------

    /**
     * AC-3, plan C3a: the comparison with the placeholder thumbnail is turned round so that a
     * playlist without thumbnail does not throw. One changed line.
     */
    @Test
    fun ac3_wiring_appendDialog() {
        Source("AC-3", APPEND_DIALOG)
            .expectEdit(
                "C3a",
                listOf("if ((\"drawable://\" + R.drawable.dummy_thumbnail_playlist).equals(playlist.thumbnailUrl)) {"),
                before = "playlistDisposables.add(manager"
            )
            .expectGone("C3a", "playlist.thumbnailUrl.equals(")
            .expectCount(1, "plan C3a: the comparison in onPlaylistSelected")
    }

    /**
     * AC-3, plan C3b: not confirmed, no edit (answer A1 removed the second sentence of AC-3).
     * `LocalPlaylistManager` carries no line of this package. Passes before Code exists.
     */
    @Test
    fun ac3_wiring_playlistManagerUntouched() {
        Source("AC-3", PLAYLIST_MANAGER)
            .expectCount(0, "plan C3b is not confirmed: no edit")
    }

    // ------------------------------------------------------------------------------------
    // AC-4: idle live stream after a seek
    // ------------------------------------------------------------------------------------

    /**
     * AC-4, plan C4: after a seek, "play" on an idle live stream goes through `Player.play()`,
     * which rebuilds the source. One changed line, inside the existing `if`.
     */
    @Test
    fun ac4_wiring_seekOnIdleLive() {
        Source("AC-4", PROGRESS_CONTROLLER)
            .expectEdit(
                "C4",
                listOf("if (player.isLiveIdle()) player.play() else player.simpleExoPlayer.play()"),
                after = "if (wasPlaying || player.simpleExoPlayer.duration == seekBar.progress.toLong()) {",
                before = "}"
            )
            .expectGone("C4", "seekBar.progress.toLong()) { player.simpleExoPlayer.play()")
            .expectCount(1, "plan C4: the play call in onStopTrackingTouch")
    }

    // ------------------------------------------------------------------------------------
    // AC-6: nothing else changes
    // ------------------------------------------------------------------------------------

    /**
     * AC-6, plan "Verification" (39 marked lines in 10 upstream files) and section 0: the
     * marker of this package is in the ten planned files, with the planned count, and in no
     * other file under `app/src/main` (sources, resources, manifest). The message lists every
     * file that differs.
     */
    @Test
    fun ac6_wiring_onlyThePlannedFilesAreMarked() {
        val main = mainDir()
        assertTrue("app/src/main not found from ${File("").absolutePath}", main.isDirectory)
        val found = sortedMapOf<String, Int>()
        main.walkTopDown().filter { it.isFile }.forEach { file ->
            val count = file.readLines(Charsets.ISO_8859_1).count { OWN_MARKER.containsMatchIn(it) }
            if (count > 0) {
                found[file.relativeTo(main).invariantSeparatorsPath] = count
            }
        }
        val differences = (PLANNED.keys + found.keys).toSortedSet().mapNotNull { path ->
            val planned = PLANNED[path] ?: 0
            val have = found[path] ?: 0
            if (planned == have) null else "app/src/main/$path: planned $planned, found $have"
        }
        assertTrue(
            "AC-6, plan Verification: the lines marked $MARKER must be the planned ones and nothing else: $differences",
            differences.isEmpty()
        )
        assertEquals("AC-6, plan Verification: marked lines in total", PLANNED_TOTAL, found.values.sum())
    }

    /**
     * AC-6, plan "Merging with the other branches": the files that other branches edit and
     * this plan names as not edited carry no line of this package. Passes before Code exists.
     */
    @Test
    fun ac6_wiring_filesThePlanLeavesAlone() {
        for (
            path in listOf(
                "$NEWPIPE/player/Player.java",
                "$NEWPIPE/fragments/list/BaseListFragment.java",
                "$NEWPIPE/fragments/list/search/SearchFragment.java",
                "$NEWPIPE/fragments/list/channel/ChannelVideosFragment.java",
                "$NEWPIPE/info_list/dialog/InfoItemDialog.java"
            )
        ) {
            Source("AC-6", path).expectCount(0, "the plan does not edit this file")
        }
    }

    /**
     * AC-6, plan section 0: no resource, no setting, no text and no class of its own. No file
     * under `app/src/main/res` is named after this package or mentions it, and there is no
     * `enhancements/crashfixesmore` package in `app/src/main`. Passes before Code exists.
     */
    @Test
    fun ac6_wiring_noResourcesAndNoOwnClasses() {
        val main = mainDir()
        val res = File(main, "res")
        assertTrue("app/src/main/res not found", res.isDirectory)
        val names = listOf("crash_fixes_more", "crash-fixes-more", "crashfixesmore")
        val offending = ArrayList<String>()
        res.walkTopDown().filter { it.isFile }.forEach { file ->
            val path = "app/src/main/" + file.relativeTo(main).invariantSeparatorsPath
            if (names.any { file.name.contains(it, ignoreCase = true) }) {
                offending.add("$path (file name)")
            }
            file.readLines(Charsets.ISO_8859_1).forEachIndexed { index, line ->
                if (names.any { line.contains(it, ignoreCase = true) }) {
                    offending.add("$path:${index + 1}")
                }
            }
        }
        assertTrue("AC-6, plan section 0: this package adds no resource: $offending", offending.isEmpty())
        val ownPackage = File(main, "$NEWPIPE/enhancements/crashfixesmore")
        assertFalse(
            "AC-6, plan section 0: no new file under app/src/main/$NEWPIPE/enhancements/crashfixesmore " +
                "(the parent's helpers are reused); found ${ownPackage.list()?.toList()}",
            ownPackage.exists()
        )
    }

    /**
     * The counting itself (plan P0, answer A5): `ENH:crash-fixes` is a prefix of
     * `ENH:crash-fixes-more`. A line of the parent is not a line of this package, a line of
     * this package is not a line of the parent, and a later `ENH:crash-fixes-more-x` would be
     * neither. Checked on text and on the two files both packages edit.
     */
    @Test
    fun markerOfThisPackageIsNotTheParents() {
        val parentLine = "        super.onResume(); // ENH:crash-fixes"
        val ownLine = "            handleResult(late); // ENH:crash-fixes-more"
        assertFalse(OWN_MARKER.containsMatchIn(parentLine))
        assertTrue(OWN_MARKER.containsMatchIn(ownLine))
        assertTrue(PARENT_MARKER.containsMatchIn(parentLine))
        assertFalse(PARENT_MARKER.containsMatchIn(ownLine))
        assertFalse(OWN_MARKER.containsMatchIn("x(); // ENH:crash-fixes-more-again"))
        assertFalse(OWN_MARKER.containsMatchIn("x(); // ENH:crash-fixes-more2"))
        assertFalse(OWN_MARKER.containsMatchIn("x(); // ENH:crash-fixes-moreover"))
        assertTrue(OWN_MARKER.containsMatchIn("x(); // ENH:crash-fixes-more"))

        // the two files both packages edit: the parent's lines exist and none of them is ours
        for (path in listOf(CHANNEL_FRAGMENT, LOCAL_PLAYLIST_FRAGMENT)) {
            val all = lines("marker", path)
            val parents = all.filter { PARENT_MARKER.containsMatchIn(it) }
            assertTrue(
                "app/src/main/$path is expected to carry lines of the parent package (ENH:crash-fixes)",
                parents.isNotEmpty()
            )
            assertTrue(
                "a line of the parent package in app/src/main/$path is counted as one of this package",
                parents.none { OWN_MARKER.containsMatchIn(it) }
            )
        }
    }
}
