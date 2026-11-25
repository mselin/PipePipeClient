package org.schabi.newpipe.enhancements.subscriptionscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the marked lines the plan puts into nine upstream files, read as text.
 * The new classes do nothing unless upstream calls them, the holders and fragments cannot run on
 * the JVM, and a rebase can drop a marked line without any other test noticing.
 *
 * Per file:
 *
 * 1. **The number of lines containing `ENH:subscription-scale`.** The plan lists every line
 *    (section 2, as amended on 2026-11-25: 20 added lines, 13 changed one for one), so the count
 *    is exact everywhere:
 *
 *    | File | Lines | Plan |
 *    |---|---|---|
 *    | `StreamMiniInfoItemHolder.java` | 7 | section 4: 2 added, 5 changed |
 *    | `StreamInfoItemHolder.java` | 7 | section 4: 2 added, 5 changed |
 *    | `ComposeItemUiHelper.kt` | 1 | section 4: `:226` changed |
 *    | `ComposeInfoItemHolder.kt` | 3 | section 4: field, first line, last line |
 *    | `FeedViewModel.kt` | 3 | 5.2: field, gate, `setOnScreen` |
 *    | `FeedFragment.kt` | 2 | 5.2: `onPause`, `onResume` |
 *    | `SubscriptionDAO.kt` | 2 | 5.3: annotation, method |
 *    | `SubscriptionViewModel.kt` | 6 | 5.3: 4 added, 2 changed |
 *    | `SubscriptionFragment.kt` | 2 | 5.3: `onResume`, `onPause` |
 *    | total | 33 | 20 + 13 |
 *
 *    Section 3 (AC-1) is upstream's since 5.6.0; `FeedLoadManager.kt` and `NotificationWorker.kt`
 *    carry no marked line, and the AC-1 tests pin that upstream still trims the results.
 *
 * 2. **What the marked lines say.** The marked lines of a file are joined, the marker and all
 *    whitespace are removed, and the plan's calls are looked up in the result. `P.` of the plan
 *    is the written-out package (section 2: "fully qualified names", no import lines).
 * 3. **Where they are**, where the place is the point: the gate before the query, the fields
 *    before the streams that use them, `cancel()` before the state is read.
 * 4. **That the blocking call is gone** from the row holders (AC-2).
 *
 * Until the Code layer adds the lines, every test here fails for the reason "must have N lines
 * marked ENH:subscription-scale" (found 0), except the two that say otherwise.
 */
class SubscriptionScaleWiringTest {

    private companion object {
        const val MARKER = "ENH:subscription-scale"
        const val P = "org.schabi.newpipe.enhancements.subscriptionscale."
        const val HELPERS = "java/org/schabi/newpipe/enhancements/subscriptionscale"

        const val FEED_LOAD_MANAGER = "java/org/schabi/newpipe/local/feed/service/FeedLoadManager.kt"
        const val NOTIFICATION_WORKER = "java/org/schabi/newpipe/local/feed/notifications/NotificationWorker.kt"
        const val MINI_HOLDER = "java/org/schabi/newpipe/info_list/holder/StreamMiniInfoItemHolder.java"
        const val STREAM_HOLDER = "java/org/schabi/newpipe/info_list/holder/StreamInfoItemHolder.java"
        const val COMPOSE_HELPER = "java/org/schabi/newpipe/info_list/ComposeItemUiHelper.kt"
        const val COMPOSE_HOLDER = "java/org/schabi/newpipe/info_list/holder/ComposeInfoItemHolder.kt"
        const val FEED_VIEW_MODEL = "java/org/schabi/newpipe/local/feed/FeedViewModel.kt"
        const val FEED_FRAGMENT = "java/org/schabi/newpipe/local/feed/FeedFragment.kt"
        const val SUBSCRIPTION_DAO = "java/org/schabi/newpipe/database/subscription/SubscriptionDAO.kt"
        const val SUBSCRIPTION_VIEW_MODEL = "java/org/schabi/newpipe/local/subscription/SubscriptionViewModel.kt"
        const val SUBSCRIPTION_FRAGMENT = "java/org/schabi/newpipe/local/subscription/SubscriptionFragment.kt"

        /** File to its number of marked lines (plan sections 4 and 5; total 33 = 20 added + 13 changed). */
        val EXPECTED = linkedMapOf(
            MINI_HOLDER to 7,
            STREAM_HOLDER to 7,
            COMPOSE_HELPER to 1,
            COMPOSE_HOLDER to 3,
            FEED_VIEW_MODEL to 3,
            FEED_FRAGMENT to 2,
            SUBSCRIPTION_DAO to 2,
            SUBSCRIPTION_VIEW_MODEL to 6,
            SUBSCRIPTION_FRAGMENT to 2
        )
    }

    private fun squeeze(text: String): String = MainFiles.squeeze(text)

    /** A file of `app/src/main` and its marked lines. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = MainFiles.lines(ac, path)
        val lines: List<String> = all.filter { it.contains(MARKER) }

        /** Marked lines joined, without the marker comment and without whitespace. */
        val code: String = squeeze(lines.joinToString("") { it.replace("// $MARKER", "").replace("//$MARKER", "") })

        fun expectCount(what: String): Marked {
            val expected = EXPECTED.getValue(path)
            assertEquals("$ac: app/src/main/$path must have $expected lines marked $MARKER ($what)", expected, lines.size)
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

        /** [snippet] (whitespace ignored) must not occur anywhere in the file. */
        fun expectGone(snippet: String): Marked {
            assertEquals("$ac: app/src/main/$path must no longer contain `$snippet`", 0, MainFiles.count(all.joinToString(""), snippet))
            return this
        }

        /** Index of the only line of the file (marked or not, as [marked] says) containing [snippet]. */
        fun lineOf(snippet: String, marked: Boolean = true): Int {
            val wanted = squeeze(snippet)
            val hits = all.indices.filter { squeeze(all[it]).contains(wanted) && (!marked || all[it].contains(MARKER)) }
            assertEquals(
                "$ac: app/src/main/$path must have exactly one ${if (marked) "marked " else ""}line containing `$snippet`",
                1,
                hits.size
            )
            return hits[0]
        }

        fun linesOf(snippet: String): List<Int> {
            val wanted = squeeze(snippet)
            return all.indices.filter { squeeze(all[it]).contains(wanted) && all[it].contains(MARKER) }
        }

        fun expectOrder(why: String, vararg lineIndexes: Int): Marked {
            assertEquals(
                "$ac: app/src/main/$path: $why (line numbers ${lineIndexes.map { it + 1 }})",
                lineIndexes.sorted(),
                lineIndexes.toList()
            )
            assertEquals("$ac: app/src/main/$path: $why: distinct lines", lineIndexes.size, lineIndexes.toSet().size)
            return this
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

    // ---- section 3, M6: upstream's since 5.6.0 (plan amendment of 2026-11-25) ----

    private fun mainLines(path: String): List<String> = MainFiles.lines("AC-1", path)

    /**
     * AC-1 is provided by upstream: `FeedLoadManager.startLoading` trims every stored batch to the
     * channels with new streams (`forNotification()`), after the batch is stored and before the
     * results are collected. If upstream drops it, AC-1 needs a part of this fix again.
     */
    @Test
    fun ac1_providedByUpstream_feedLoadManagerTrimsEveryStoredBatch() {
        val all = mainLines(FEED_LOAD_MANAGER)
        val stored = all.indexOfFirst { squeeze(it).contains(squeeze(".doOnNext(DatabaseConsumer())")) }
        val trimmed = all.indexOfFirst { it.contains("forNotification()") }
        val collected = all.indexOfFirst { squeeze(it) == squeeze(".toList()") }
        assertTrue("AC-1: FeedLoadManager.kt stores each batch (`.doOnNext(DatabaseConsumer())`)", stored >= 0)
        assertTrue("AC-1: upstream's trimming (`forNotification()`) follows the storing and comes before `.toList()`", stored < trimmed && trimmed < collected)
        assertEquals("AC-1: FeedLoadManager.kt carries no line of this fix", 0, all.count { it.contains(MARKER) })
    }

    /** AC-1: the notification run is upstream's as it is; nothing of this fix is left in it. */
    @Test
    fun ac1_providedByUpstream_noLineOfThisFixInTheNotificationWorker() {
        assertEquals("AC-1: NotificationWorker.kt carries no line of this fix", 0, mainLines(NOTIFICATION_WORKER).count { it.contains(MARKER) })
    }

    /** AC-1: the withdrawn switch and class are gone from every source file. */
    @Test
    fun ac1_providedByUpstream_theWithdrawnPartIsGone() {
        val left = sourceFiles().filter { val text = it.readText(); text.contains("retainNewStreams") || text.contains("FeedResultRetention") }.map { it.name }
        assertEquals("AC-1: no source file mentions retainNewStreams or FeedResultRetention", emptyList<String>(), left)
    }

    // ---- section 4, J1 ----

    private fun assertStreamHolder(path: String) {
        val file = Marked("AC-2", path)
            .expectCount("plan section 4: field and cancel added; five lines of blockingGet statements changed")
            .expectCode("private final ${P}RowWatchState watched = new ${P}RowWatchState();")
            .expectCode("watched.cancel();")
            .expectCode("final StreamStateEntity paidState = watched.cached(item);")
            .expectCode("final StreamStateEntity state = watched.cached(item);")
            .expectCode("final StreamStateEntity state = watched.cached(infoItem);")
            .expectCode("watched.cached(", times = 3)
            .expectCode("watched.refresh(item, historyRecordManager, itemProgressView);", times = 2)
            .expectCode("watched.refresh(", times = 2)
            .expectGone("blockingGet")
            .expectGone("historyRecordManager.loadStreamState(")

        val cancel = file.lineOf("watched.cancel();")
        val paid = file.lineOf("paidState = watched.cached(item);")
        val state = file.lineOf("state = watched.cached(item);")
        val refreshes = file.linesOf("watched.refresh(item, historyRecordManager, itemProgressView);")
        assertEquals("AC-2: app/src/main/$path: each refresh is a line of its own", 2, refreshes.size)
        file.expectOrder(
            "the field, then in updateFromItem: cancel first, then per branch the cached state and directly after it the refresh",
            file.lineOf("RowWatchState watched = new"), cancel, paid, refreshes[0], state, refreshes[1],
            file.lineOf("state = watched.cached(infoItem);")
        )
        assertEquals("AC-2: the refresh directly follows the cached state (paid branch)", paid + 1, refreshes[0])
        assertEquals("AC-2: the refresh directly follows the cached state", state + 1, refreshes[1])
        assertEquals("AC-2: cancel() is in the bind", "updateFromItem", file.functionAround(cancel))
        assertEquals("AC-2: the dead updateState reads the cache only", "updateState", file.functionAround(file.lineOf("state = watched.cached(infoItem);")))
        // The upstream blocks that draw the bar stay and now draw the cached state.
        assertEquals(
            "AC-2: upstream's blocks that draw the bar stay (two in updateFromItem, one in updateState)",
            3,
            MainFiles.count(file.all.joinToString(""), "itemProgressView.setMax((int) item.getDuration());")
        )
    }

    /** AC-2: the list row asks `RowWatchState` instead of waiting for the database (4 live sites, 2 dead: this file has 2 + 1). */
    @Test
    fun ac2_wiring_miniHolderDoesNotBlock() = assertStreamHolder(MINI_HOLDER)

    /** AC-2: the same for the holder behind the normal, grid and card rows. */
    @Test
    fun ac2_wiring_streamHolderDoesNotBlock() = assertStreamHolder(STREAM_HOLDER)

    /** AC-2: the rows of the experimental UI read the cache while building their state. */
    @Test
    fun ac2_wiring_composeHelperReadsTheCache() {
        Marked("AC-2", COMPOSE_HELPER)
            .expectCount("plan section 4: :226 changed")
            .expectCode("val state = ${P}RowWatchState.cached(item)")
            .expectGone("blockingGet")
    }

    /** AC-2: the experimental row cancels on every bind and redraws itself when the answer differs. */
    @Test
    fun ac2_wiring_composeHolderRefreshesAfterTheBind() {
        val file = Marked("AC-2", COMPOSE_HOLDER)
            .expectCount("plan section 4: field, first and last line of updateFromItem")
            .expectCode("private val watched = ${P}RowWatchState()")
            .expectCode("watched.cancel()")
            .expectCode("if (infoItem is")
            .expectCode("StreamInfoItem) watched.refresh(infoItem, historyRecordManager) { updateFromItem(infoItem, historyRecordManager) }")
        val cancel = file.lineOf("watched.cancel()")
        val refresh = file.lineOf("watched.refresh(")
        file.expectOrder(
            "the field, then in updateFromItem: cancel first, the row is built and drawn, refresh last",
            file.lineOf("private val watched ="),
            file.lineOf("override fun updateFromItem(", marked = false),
            cancel,
            file.lineOf("buildInfoItemState(composeView.context", marked = false),
            file.lineOf("composeView.setContent {", marked = false),
            refresh
        )
        assertEquals("AC-2: cancel() is the first line of updateFromItem", file.lineOf("override fun updateFromItem(", marked = false) + 1, cancel)
        assertEquals("updateFromItem", file.functionAround(refresh))
    }

    /** AC-2: the `git grep blockingGet` over `info_list/holder` and `ComposeItemUiHelper.kt` of plan section 7 is empty. */
    @Test
    fun ac2_wiring_noRowHolderBlocks() {
        Marked("AC-2", MINI_HOLDER).expectCount("plan section 4") // so that the test fails until the fix is in
        val holders = MainFiles.file("java/org/schabi/newpipe/info_list/holder").listFiles()!!.toList() + MainFiles.file(COMPOSE_HELPER)
        assertTrue("the holder directory is where the plan says", holders.size > 5)
        assertEquals(
            "AC-2: no file of info_list/holder, nor ComposeItemUiHelper.kt, calls blockingGet",
            emptyList<String>(),
            holders.filter { it.isFile && it.readText().contains("blockingGet") }.map { it.name }
        )
    }

    // ---- 5.2, J2 ----

    /** AC-3: the group feed's stream goes through the gate before the throttle and the query. */
    @Test
    fun ac3_wiring_feedViewModelGatesItsStream() {
        val file = Marked("AC-3", FEED_VIEW_MODEL)
            .expectCount("plan 5.2: field, gate line, setOnScreen")
            .expectCode("private val onScreenGate = ${P}OnScreenGate()")
            .expectCode(".let { onScreenGate.gate(it) }")
            .expectCode("fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen)")
        file.expectOrder(
            "the gate field is initialised before the stream that uses it; the gate sits after combineLatest and before the throttle and the 500-row query",
            file.lineOf("private val onScreenGate ="),
            file.lineOf("private var combineDisposable = Flowable", marked = false),
            file.lineOf("feedDatabaseManager.oldestSubscriptionUpdate(groupId)", marked = false),
            file.lineOf(".let { onScreenGate.gate(it) }"),
            file.lineOf(".throttleLatest(", marked = false),
            file.lineOf(".getStreams(groupId, showPlayedItems)", marked = false)
        )
        // No distinctUntilChanged is added to the feed stream (plan 5.2, AC-5): the one upstream use stays the only one.
        assertEquals("AC-5: no filter is added to the feed stream", 1, MainFiles.count(file.all.joinToString(""), ".distinctUntilChanged()"))
    }

    /** AC-3: the feed page reports "on screen" from onResume and "off" from onPause. */
    @Test
    fun ac3_wiring_feedFragmentReportsOnScreen() {
        val file = Marked("AC-3", FEED_FRAGMENT)
            .expectCount("plan 5.2: one line in onPause, one in onResume")
            .expectCode("viewModel.setOnScreen(false)")
            .expectCode("viewModel.setOnScreen(true)")
        assertEquals("AC-3: off screen is reported from onPause", "onPause", file.functionAround(file.lineOf("viewModel.setOnScreen(false)")))
        assertEquals("AC-3: on screen is reported from onResume", "onResume", file.functionAround(file.lineOf("viewModel.setOnScreen(true)")))
    }

    // ---- 5.3, J3 ----

    /** AC-4: the DAO gets the slim list query. */
    @Test
    fun ac4_wiring_subscriptionDaoHasTheSlimQuery() {
        val file = Marked("AC-4", SUBSCRIPTION_DAO)
            .expectCount("plan 5.3: annotation and method")
            .expectCode("@Query(${P}SUBSCRIPTION_ROWS_QUERY)")
            .expectCode("abstract fun getAllRows(): Flowable<List<${P}SubscriptionRow>>")
        assertEquals(
            "AC-4: the annotation is directly above its method",
            file.lineOf("SUBSCRIPTION_ROWS_QUERY") + 1,
            file.lineOf("abstract fun getAllRows()")
        )
    }

    /** AC-4, AC-5: the tab's two streams are gated; the channel list comes from `listing` and reuses its items. */
    @Test
    fun ac4_wiring_subscriptionViewModelUsesTheSlimGatedListing() {
        val file = Marked("AC-4", SUBSCRIPTION_VIEW_MODEL)
            .expectCount("plan 5.3: 4 added (two fields, setOnScreen, groups gate), 2 changed (:81, :83)")
            .expectCode("private val onScreenGate = ${P}OnScreenGate()")
            .expectCode(
                "private val channelItems = ${P}ReusingMapper<${P}SubscriptionRow, ChannelItem>({ it.uid }) " +
                    "{ ChannelItem(it.toChannelInfoItem(), it.uid, ChannelItem.ItemVersion.MINI) }"
            )
            .expectCode("fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen)")
            .expectCode(".let { onScreenGate.gate(it) }")
            .expectCode(
                "private var stateItemsDisposable = ${P}SubscriptionRows.listing(" +
                    "subscriptionManager.subscriptionTable().getAllRows(), onScreenGate)"
            )
            .expectCode(".map { rows -> channelItems.map(rows) }")
            .expectGone("subscriptionManager.subscriptions()")
            .expectGone("entity.toChannelInfoItem()")
        file.expectOrder(
            "both fields are initialised before the streams; the groups stream is gated before its throttle; " +
                "the channel list is the gated listing, mapped through the reusing mapper",
            file.lineOf("private val onScreenGate ="),
            file.lineOf("private val channelItems ="),
            file.lineOf("private var feedGroupItemsDisposable = feedDatabaseManager.groups()", marked = false),
            file.lineOf(".let { onScreenGate.gate(it) }"),
            file.lineOf(".map { it.map(::FeedGroupCardItem) }", marked = false),
            file.lineOf("private var stateItemsDisposable ="),
            file.lineOf(".map { rows -> channelItems.map(rows) }"),
            file.lineOf("SubscriptionState.LoadedState(it)", marked = false)
        )
        assertEquals(
            "AC-4: the groups stream is gated directly after `feedDatabaseManager.groups()`",
            file.lineOf("private var feedGroupItemsDisposable = feedDatabaseManager.groups()", marked = false) + 1,
            file.lineOf(".let { onScreenGate.gate(it) }")
        )
    }

    /** AC-4: the tab reports "on screen" from onResume and "off" from onPause. */
    @Test
    fun ac4_wiring_subscriptionFragmentReportsOnScreen() {
        val file = Marked("AC-4", SUBSCRIPTION_FRAGMENT)
            .expectCount("plan 5.3: one line in onResume, one in onPause")
            .expectCode("viewModel.setOnScreen(true)")
            .expectCode("viewModel.setOnScreen(false)")
        assertEquals("AC-4: on screen is reported from onResume", "onResume", file.functionAround(file.lineOf("viewModel.setOnScreen(true)")))
        assertEquals("AC-4: off screen is reported from onPause", "onPause", file.functionAround(file.lineOf("viewModel.setOnScreen(false)")))
    }

    // ---- the whole change ----

    private fun sourceFiles(): List<File> {
        val root = MainFiles.file("java")
        if (!root.isDirectory) fail("app/src/main/java does not exist")
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    /**
     * Plan section 2, as amended on 2026-11-25: marked lines in 9 upstream files, 33 in all
     * (20 added, 13 changed), and nowhere else. A marked line in a tenth file is an edit the plan
     * does not have.
     */
    @Test
    fun wiring_nineUpstreamFiles_33MarkedLines_andNoOther() {
        val helpers = MainFiles.file(HELPERS).canonicalFile
        val root = MainFiles.file("").canonicalFile
        val found = sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .associate { it.canonicalFile.relativeTo(root).invariantSeparatorsPath to it.readLines().count { line -> line.contains(MARKER) } }
            .filterValues { it > 0 }

        assertEquals("plan section 2: the marked lines per upstream file", EXPECTED.toSortedMap(), found.toSortedMap())
        assertEquals(33, found.values.sum())
    }

    /**
     * AC-7 (no new setting) and plan section 2: no resource of this fix. Holds before Code
     * exists; it fails when a resource file or a preference key of the fix appears.
     */
    @Test
    fun ac7_noResourceAndNoSettingOfThisFix() {
        val res = MainFiles.file("res")
        assertTrue("app/src/main/res exists", res.isDirectory)
        val named = res.walkTopDown().filter { it.isFile }.filter {
            it.name.contains("subscription_scale") || it.name.contains("subscriptionscale")
        }.map { it.name }.toList()
        assertEquals("AC-7: no resource file of this fix", emptyList<String>(), named)
        val mentioning = res.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name.orEmpty().startsWith("values") }
            .filter { file -> file.readText().let { it.contains("subscription_scale") || it.contains(MARKER) } }
            .map { it.name }.toList()
        assertEquals("AC-7: no string, key or default of this fix in res/values*", emptyList<String>(), mentioning)
    }
}
