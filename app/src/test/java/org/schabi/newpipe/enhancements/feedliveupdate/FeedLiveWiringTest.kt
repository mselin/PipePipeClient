package org.schabi.newpipe.enhancements.feedliveupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the 22 marked lines plan section 8 puts into four upstream files, read
 * as text. The new classes do nothing unless upstream calls them, the view model and the
 * fragment cannot run on the JVM, and a rebase can drop or move a marked line without any
 * other test noticing.
 *
 * Per file:
 *
 * 1. **The number of lines carrying `ENH:feed-live-update`** (plan section 8, last paragraph):
 *
 *    | File | Lines | Plan |
 *    |---|---|---|
 *    | `FeedState.kt` | 1 | 1 changed |
 *    | `FeedLoadManager.kt` | 3 | 3 added |
 *    | `FeedViewModel.kt` | 6 | 3 added, 3 changed |
 *    | `FeedFragment.kt` | 12 | 7 added, 5 changed |
 *    | total | 22 | 13 added + 9 changed |
 *
 *    A line counts when it carries exactly this marker: `ENH:feed-live-update-2` is not it,
 *    and neither is a line of `ENH:subscription-scale`, on which this feature is built and
 *    which marks lines in three of the four files.
 * 2. **What each marked line says**: the whole line, as section 8 prints it with `P.` written
 *    out, compared without whitespace. With the count, the marked lines of a file are exactly
 *    the plan's.
 * 3. **Where it is**: the line before or after it, and the function around it.
 * 4. **What a changed line replaced** is gone, and what the plan leaves alone is still there.
 *
 * Until the Code layer adds the lines, the tests whose names start with `ac` or `wiring_22`
 * fail with "must have N lines marked ENH:feed-live-update" (found 0). The tests named
 * `unchanged_…` hold before and after: they guard what the feature must not touch.
 */
class FeedLiveWiringTest {

    private companion object {
        const val MARKER = Sources.MARKER
        const val PARENT = Sources.PARENT_MARKER
        const val P = "org.schabi.newpipe.enhancements.feedliveupdate."
        const val PARENT_P = "org.schabi.newpipe.enhancements.subscriptionscale."
        const val HELPERS = "java/org/schabi/newpipe/enhancements/feedliveupdate"

        const val FEED_STATE = "java/org/schabi/newpipe/local/feed/FeedState.kt"
        const val FEED_LOAD_MANAGER = "java/org/schabi/newpipe/local/feed/service/FeedLoadManager.kt"
        const val FEED_VIEW_MODEL = "java/org/schabi/newpipe/local/feed/FeedViewModel.kt"
        const val FEED_FRAGMENT = "java/org/schabi/newpipe/local/feed/FeedFragment.kt"

        /** File to its number of marked lines (plan section 8; total 22 = 13 added + 9 changed). */
        val EXPECTED = linkedMapOf(
            FEED_STATE to 1,
            FEED_LOAD_MANAGER to 3,
            FEED_VIEW_MODEL to 6,
            FEED_FRAGMENT to 12
        )

        /** `subscription-scale`'s marked lines in the same files, whole (its plan, sections 3 and 5.2). */
        val PARENT_LINES = linkedMapOf(
            FEED_STATE to emptyList(),
            FEED_LOAD_MANAGER to listOf(
                "retainNewStreams: Boolean = false, // $PARENT",
                ".map { batch -> ${PARENT_P}FeedResultRetention.retain(batch, retainNewStreams) } // $PARENT"
            ),
            FEED_VIEW_MODEL to listOf(
                "private val onScreenGate = ${PARENT_P}OnScreenGate() // $PARENT",
                ".let { onScreenGate.gate(it) } // $PARENT",
                "fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen) // $PARENT"
            ),
            FEED_FRAGMENT to listOf(
                "viewModel.setOnScreen(false) // $PARENT",
                "viewModel.setOnScreen(true) // $PARENT"
            )
        )
    }

    private fun squeeze(text: String): String = Sources.squeeze(text)

    /** A file of `app/src/main` and its marked lines. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = Sources.lines(ac, path)
        val squeezed: List<String> = all.map { squeeze(it) }
        val mine: List<Int> = all.indices.filter { Sources.carries(all[it], MARKER) }

        fun expectCount(what: String): Marked {
            val expected = EXPECTED.getValue(path)
            assertEquals(
                "$ac: app/src/main/$path must have $expected lines marked $MARKER ($what); marked lines: ${mine.map { all[it].trim() }}",
                expected,
                mine.size
            )
            return this
        }

        /** Index of the one line that is, whitespace ignored, [code] followed by this feature's marker comment. */
        fun marked(code: String): Int {
            val wanted = squeeze("$code // $MARKER")
            val hits = all.indices.filter { squeezed[it] == wanted }
            assertEquals(
                "$ac: app/src/main/$path must have exactly one line `$code // $MARKER`; marked lines: ${mine.map { all[it].trim() }}",
                1,
                hits.size
            )
            return hits[0]
        }

        /** Indexes of the lines that are, whitespace ignored, exactly [code] (no marker). */
        fun plain(code: String): List<Int> {
            val wanted = squeeze(code)
            return all.indices.filter { squeezed[it] == wanted }
        }

        /** Index of the one line that is exactly [code]. */
        fun one(code: String): Int {
            val hits = plain(code)
            assertEquals("$ac: app/src/main/$path must have exactly one line `$code`", 1, hits.size)
            return hits[0]
        }

        fun expectGone(code: String, why: String): Marked {
            assertEquals("$ac: app/src/main/$path must no longer have the line `$code` ($why)", emptyList<Int>(), plain(code).map { it + 1 })
            return this
        }

        fun expectAdjacent(why: String, vararg indexes: Int): Marked {
            assertEquals(
                "$ac: app/src/main/$path: $why (line numbers ${indexes.map { it + 1 }} must follow one another directly)",
                (indexes[0] until indexes[0] + indexes.size).toList(),
                indexes.toList()
            )
            return this
        }

        fun expectOrder(why: String, vararg indexes: Int): Marked {
            assertEquals("$ac: app/src/main/$path: $why (line numbers ${indexes.map { it + 1 }})", indexes.sorted(), indexes.toList())
            assertEquals("$ac: app/src/main/$path: $why: distinct lines", indexes.size, indexes.toSet().size)
            return this
        }

        /** The name of the function whose declaration is the nearest one at or above line [index]. */
        fun functionAround(index: Int): String? {
            val declaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(""")
            for (i in index downTo 0) return (declaration.find(all[i]) ?: continue).groupValues[1]
            return null
        }

        fun expectIn(function: String, index: Int): Marked {
            assertEquals("$ac: app/src/main/$path: line ${index + 1} `${all[index].trim()}` belongs to $function", function, functionAround(index))
            return this
        }
    }

    // ---- FeedState.kt: 1 changed ----

    /** AC-1, plan 5.1: the progress state can carry the list ("`null`: full-screen progress as today"). */
    @Test
    fun ac1_wiring_feedState_theProgressStateCarriesTheList() {
        val file = Marked("AC-1", FEED_STATE).expectCount("plan section 8: `:11` changed")
        val items = file.marked("@StringRes val progressMessage: Int = 0, val items: List<StreamItem>? = null")
        file.expectGone("@StringRes val progressMessage: Int = 0", "changed one for one")
        file.expectAdjacent(
            "the new field is the last one of ProgressState, with a default so that `ProgressState(current, max, message)` still compiles",
            file.one("val maxProgress: Int = -1,"), items, file.plain(") : FeedState()").first()
        )
        file.expectOrder("it is ProgressState's", file.one("data class ProgressState("), items, file.one("data class LoadedState("))
        // LoadedState and ErrorState are unchanged (plan 5.1).
        file.one("val items: List<StreamItem>,")
        file.one("val error: Throwable? = null")
    }

    // ---- FeedLoadManager.kt: 3 added ----

    /**
     * AC-2, AC-7, plan 3.1 and section 4: the loader reports each stored batch directly after
     * its transaction, and its group and its end at the end of the chain.
     */
    @Test
    fun ac2_wiring_feedLoadManager_reportsEachBatchItsStartAndItsEnd() {
        val file = Marked("AC-2", FEED_LOAD_MANAGER).expectCount("plan section 8: one line after the DatabaseConsumer, two at the end of the chain")
        val batch = file.marked(".doOnNext { ${P}FeedRefreshLog.app.batchStored() }")
        val started = file.marked(".doOnSubscribe { ${P}FeedRefreshLog.app.started(groupId) }")
        val ended = file.marked(".doFinally { ${P}FeedRefreshLog.app.ended() }")
        val post = file.one(".flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }")

        file.expectAdjacent(
            "AC-2: `batchStored()` is \"reached only when the transaction has committed\": directly after the DatabaseConsumer",
            file.one(".doOnNext(DatabaseConsumer())"), batch
        )
        file.expectOrder("the batch is counted before the results are collected", batch, file.one(".toList()"), post)
        file.expectAdjacent(
            "AC-7: started(groupId) and ended() are the last two operators of startLoading's chain, after postProcessFeed()",
            post, started, ended
        )
        assertEquals("AC-7: the chain ends with ended(): the function closes after it", "}", file.squeezed[ended + 1])
        file.expectIn("startLoading", batch).expectIn("startLoading", started).expectIn("startLoading", ended)
        // The events stay anonymous (plan section 4): no group id in a posted event.
        assertEquals(
            "plan section 4: the four places that post an event are not edited",
            4,
            Sources.count(file.all.joinToString("\n"), "FeedEventManager.postEvent(")
        )
    }

    // ---- FeedViewModel.kt: 3 added, 3 changed ----

    /**
     * AC-2, AC-10, plan 3.2 and section 8: the page's stream is woken by stored batches inside
     * the on-screen gate, and every item is decided on behind the page's throttle.
     */
    @Test
    fun ac2_wiring_feedViewModel_wakesInsideTheGateAndDecidesBehindTheThrottle() {
        val file = Marked("AC-2", FEED_VIEW_MODEL).expectCount("plan section 8: 3 added, 3 changed")
        val field = file.marked("private val live = ${P}FeedLiveUpdate<StreamWithState, StreamItem>(groupId)")
        val wake = file.marked(".let { live.wake(it) }")
        val next = file.marked(".filter { live.next(it.t1, it.t2) }")
        val gate = file.one(".let { onScreenGate.gate(it) } // $PARENT")

        file.expectAdjacent(
            "the field follows the gate's field and is initialised before the stream that uses it",
            file.one("private val onScreenGate = ${PARENT_P}OnScreenGate() // $PARENT"), field
        )
        file.expectOrder("the field is initialised before the stream", field, file.one("private var combineDisposable = Flowable"))
        file.expectAdjacent(
            "AC-10: `wake` is directly before the gate, so \"off screen it is not subscribed\"",
            wake, gate, file.one(".throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)")
        )
        assertEquals("wake follows the `)` that closes combineLatest", ")", file.squeezed[wake - 1])
        file.expectOrder(
            "wake takes the combined stream of the four sources",
            file.one("feedDatabaseManager.oldestSubscriptionUpdate(groupId),"), wake
        )
        file.expectAdjacent(
            "the decision runs on the stream's background thread, directly before the map that queries",
            file.one(".observeOn(Schedulers.io())"), next, file.one(".map { (event, showPlayedItems, notLoadedCount, oldestUpdate) ->")
        )
        assertEquals(
            "plan section 8: subscription-scale's wiring test still holds: one `.throttleLatest(` line in the file",
            1,
            Sources.count(file.all.joinToString("\n"), ".throttleLatest(")
        )
        assertEquals(
            "plan section 8: and one `.distinctUntilChanged()`",
            1,
            Sources.count(file.all.joinToString("\n"), ".distinctUntilChanged()")
        )
    }

    /** AC-1, AC-2, AC-7, plan 5.2 and section 8: the query runs when the decision says so; the state is made from what it says. */
    @Test
    fun ac2_wiring_feedViewModel_queriesAndShowsWhatTheDecisionSays() {
        val file = Marked("AC-2", FEED_VIEW_MODEL).expectCount("plan section 8: 3 added, 3 changed")
        val rebuild = file.marked("val streamItems = if (live.rebuild)")
        val holder = file.marked("CombineResultDataHolder(live.shownAs(event), live.rows(streamItems), notLoadedCount, oldestUpdate)")
        val progress = file.marked(
            "is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage, " +
                "live.items(listFromDB) { e -> StreamItem(e) })"
        )
        file.expectGone("val streamItems = if (event is SuccessResultEvent || event is IdleEvent)", "changed one for one")
            .expectGone("CombineResultDataHolder(event, streamItems, notLoadedCount, oldestUpdate)", "changed one for one")
            .expectGone(
                "is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage)",
                "changed one for one"
            )

        file.expectAdjacent(
            "`rebuild` decides whether the 500-row query runs",
            file.marked(".filter { live.next(it.t1, it.t2) }"),
            file.one(".map { (event, showPlayedItems, notLoadedCount, oldestUpdate) ->"),
            rebuild,
            file.one("feedDatabaseManager"),
            file.one(".getStreams(groupId, showPlayedItems)"),
            file.one(".blockingGet(arrayListOf())"),
            file.one("else"),
            file.one("arrayListOf()")
        )
        file.expectOrder("the holder is the map's result", file.one("arrayListOf()"), holder, file.one(".observeOn(AndroidSchedulers.mainThread())"))
        // Plan section 8: ":74, :76-77 and :81-83 (reset()) stay: they now see the event shownAs returned and the kept rows".
        file.expectAdjacent(
            "the three other states are made as today, around the progress state",
            file.one("is IdleEvent -> FeedState.LoadedState(listFromDB.map { e -> StreamItem(e) }, oldestUpdate, notLoadedCount)"),
            progress,
            file.one("is SuccessResultEvent -> FeedState.LoadedState(listFromDB.map { e -> StreamItem(e) }, oldestUpdate, notLoadedCount, event.itemsErrors)"),
            file.one("is ErrorResultEvent -> FeedState.ErrorState(event.error)")
        )
        file.expectAdjacent(
            "AC-8: a result is consumed as today",
            file.one("if (event is ErrorResultEvent || event is SuccessResultEvent) {"),
            file.one("FeedEventManager.reset()")
        )
    }

    // ---- FeedFragment.kt: 7 added, 5 changed ----

    private fun fragment(ac: String) = Marked(ac, FEED_FRAGMENT).expectCount("plan section 8: 7 added, 5 changed")

    /** Plan 6.2: "While the state is a progress state" is one added property, next to `isRefreshing`. */
    @Test
    fun ac1_wiring_feedFragment_liveRefreshProperty() {
        val file = fragment("AC-1")
        val property = file.marked("private val liveRefresh get() = viewModel.stateLiveData.value is FeedState.ProgressState")

        file.expectAdjacent("the property follows `isRefreshing`", file.one("private var isRefreshing = false"), property)
    }

    /**
     * AC-1, AC-11, plan 6.1: in list mode the page is in its loaded look, but "the pull
     * indicator stays on", so a pull during a refresh does nothing, as today.
     */
    @Test
    fun ac11_wiring_feedFragment_hideLoadingLeavesThePullIndicatorOnDuringARefresh() {
        val file = fragment("AC-11")
        val indicator = file.marked("feedBinding.swipeRefreshLayout.isRefreshing = liveRefresh")

        file.expectIn("hideLoading", indicator)
        assertEquals(
            "the changed line is hideLoading's line after the centre text is hidden",
            squeeze("feedBinding.loadingProgressText.animate(false, 0)"),
            file.squeezed[indicator - 1]
        )
        assertEquals("AC-4: `isRefreshing = false` directly follows it", "isRefreshing=false", file.squeezed[indicator + 1])
        val untouched = file.plain("feedBinding.swipeRefreshLayout.isRefreshing = false")
        assertEquals(
            "AC-11: the two other places that turn the indicator off (showEmptyState, handleError) are not edited",
            listOf("showEmptyState", "handleError"),
            untouched.map { file.functionAround(it) }
        )
        assertEquals("showLoading turns it on as today", "showLoading", file.functionAround(file.one("feedBinding.swipeRefreshLayout.isRefreshing = true")))
    }

    /**
     * AC-1, AC-2, AC-6, plan 6.1 and 6.2: a progress state with a list goes to
     * `handleLiveProgress`; without one (`null`) `handleProgressState` runs as today.
     */
    @Test
    fun ac1_wiring_feedFragment_aProgressStateWithAListKeepsTheList() {
        val file = fragment("AC-1")
        val branch = file.marked("if (progressState.items != null) return handleLiveProgress(progressState.items)")
        val declaration = file.marked("private fun handleLiveProgress(items: List<StreamItem>): Unit =")
        val same = file.marked("if (${P}FeedListRules.sameRows(originalItems, items) { it.streamWithState }) hideLoading()")
        val other = file.marked("else handleLoadedState(FeedState.LoadedState(items, oldestSubscriptionUpdate, 0))")

        file.expectAdjacent(
            "AC-6: the branch is the first line of handleProgressState, before showLoading()",
            file.one("private fun handleProgressState(progressState: FeedState.ProgressState) {"), branch, file.plain("showLoading()").first()
        )
        file.expectAdjacent(
            "handleLiveProgress follows handleProgressState: three lines, no blank line",
            file.one("feedBinding.loadingProgressBar.max = progressState.maxProgress"),
            file.one("feedBinding.loadingProgressBar.max = progressState.maxProgress") + 1,
            declaration, same, other
        )
        assertEquals("the line between is the `}` that closes handleProgressState", "}", file.squeezed[declaration - 1])
        file.expectOrder("and comes before the next function", other, file.one("private fun showInfoItemDialog(item: StreamInfoItem) {"))
        // handleResult is not edited: the progress state still goes to handleProgressState, and the header line is refreshed after it.
        file.one("is FeedState.ProgressState -> handleProgressState(result)")
    }

    /**
     * AC-2, AC-5, AC-9, plan 6.2 and section 7: the three changed lines of `handleLoadedState`:
     * the search text applies to every update, the list is shown through `FeedListUpdate.show`,
     * and the "Not loaded" line is left alone during a refresh.
     */
    @Test
    fun ac9_wiring_feedFragment_handleLoadedState_filterListUpdateAndNotLoadedLine() {
        val file = fragment("AC-9")
        val filter = file.marked(
            "filteredItems.addAll(if (isFilterEnabled) originalItems.filter { ${P}FeedListRules.matches(" +
                "it.streamWithState.stream.title, it.streamWithState.stream.uploader, editText?.text?.toString().orEmpty()) } else originalItems)"
        )
        val show = file.marked(
            "${P}FeedListUpdate.show(feedBinding.itemsList, groupAdapter, filteredItems.toList(), liveRefresh, " +
                "{ if (tryGetNewItemsLoadedButton()?.isVisible == false) showNewItemsLoaded() }) {"
        )
        val notLoaded = file.marked(
            "feedBinding.refreshSubtitleText.isVisible = if (liveRefresh) feedBinding.refreshSubtitleText.isVisible && " +
                "feedBinding.refreshSubtitleText.text.isNotEmpty() else feedsNotLoaded"
        )
        file.expectIn("handleLoadedState", filter).expectIn("handleLoadedState", show).expectIn("handleLoadedState", notLoaded)

        // :830
        file.expectAdjacent(
            "AC-9: the filtered list is built from the page's items, directly after they are stored",
            file.one("originalItems.addAll(loadedState.items)"),
            file.one("originalItems.addAll(loadedState.items)") + 1,
            filter
        )
        assertEquals("filteredItems.clear()", file.squeezed[filter - 1])
        assertEquals(
            "AC-9: the unfiltered copy of handleLoadedState is replaced; the one of filterItems (an empty search text) stays",
            listOf("filterItems"),
            file.plain("filteredItems.addAll(originalItems)").map { file.functionAround(it) }
        )

        // :837
        file.expectGone("groupAdapter.updateAsync(loadedState.items, false) {", "changed one for one: the list goes through FeedListUpdate.show")
        file.expectAdjacent(
            "AC-8: the lambda that follows stays and is `onDone`: the highlighting of new items",
            show,
            file.one("oldOldestSubscriptionUpdate?.run {"),
            file.one("highlightNewItemsAfter(oldOldestSubscriptionUpdate)")
        )
        file.expectOrder(
            "the list is shown after the time of before the update is saved and before the scroll position is restored",
            filter,
            file.one("val oldOldestSubscriptionUpdate = oldestSubscriptionUpdate"),
            show,
            file.one("feedBinding.itemsList.layoutManager?.onRestoreInstanceState(listState)")
        )

        // :848
        file.expectGone("feedBinding.refreshSubtitleText.isVisible = feedsNotLoaded", "changed one for one")
        file.expectAdjacent(
            "the \"Not loaded: n\" line",
            file.one("val feedsNotLoaded = loadedState.notLoadedCount > 0"), notLoaded, file.one("if (feedsNotLoaded) {")
        )
        // AC-8: the rest of the end is as today.
        file.one("handleItemsErrors(loadedState.itemsErrors)")
        file.one("oldestSubscriptionUpdate = loadedState.oldestUpdate")
    }

    /** AC-3, plan 6.1: the progress is written into the header's first line, at the end of `updateRefreshViewState()`, with the existing texts. */
    @Test
    fun ac3_wiring_feedFragment_theHeaderLineShowsTheProgress() {
        val file = fragment("AC-3")
        val text = file.marked(
            "(viewModel.stateLiveData.value as? FeedState.ProgressState)?.takeIf { it.items != null }?.let { feedBinding.refreshText.text = " +
                "${P}FeedListRules.progressText(it.currentProgress, it.maxProgress, getString(R.string.feed_notification_loading), " +
                "if (it.progressMessage > 0) getString(it.progressMessage) else null) }"
        )
        val function = file.one("private fun updateRefreshViewState() {")

        file.expectIn("updateRefreshViewState", text)
        file.expectAdjacent(
            "AC-3: it is the last statement of updateRefreshViewState, after \"Feed last updated: …\" is written, so the final state restores the line",
            function,
            file.one("feedBinding.refreshText.text = getString("),
            file.one("R.string.feed_oldest_subscription_update,"),
            file.one("oldestSubscriptionUpdate?.let { Localization.relativeTime(it) } ?: \"—\""),
            function + 4,
            text
        )
        assertEquals(")", file.squeezed[text - 1])
        assertEquals("the function closes after it", "}", file.squeezed[text + 1])
        // handleResult still ends with the call, for every state.
        assertTrue("handleResult calls updateRefreshViewState()", file.plain("updateRefreshViewState()").any { file.functionAround(it) == "handleResult" })
    }

    /** AC-5, plan 6.2 and section 7: during a refresh the highlight pass does not show the "new items" button; `FeedListRules.afterUpdate` decides. */
    @Test
    fun ac5_wiring_feedFragment_theHighlightPassDoesNotShowTheButtonDuringARefresh() {
        val file = fragment("AC-5")
        val condition = file.marked("if (highlightCount > 0 && !liveRefresh) {")

        file.expectGone("if (highlightCount > 0) {", "changed one for one")
        file.expectIn("highlightNewItemsAfter", condition)
        file.expectAdjacent("it guards upstream's showNewItemsLoaded()", condition, file.plain("showNewItemsLoaded()").last())
        file.expectOrder("it is the end of the function", condition, file.one("lastNewItemsCount = highlightCount"))
    }

    /** AC-7, AC-11, plan 6.3: a refresh asked for on a page the running refresh does not concern: the pull indicator goes back. */
    @Test
    fun ac11_wiring_feedFragment_reloadContentTakesTheIndicatorBackWhenAnotherGroupIsRefreshed() {
        val file = fragment("AC-11")
        val back = file.marked("if (${P}FeedRefreshLog.app.runningElsewhere(groupId)) feedBinding.swipeRefreshLayout.isRefreshing = false")

        file.expectIn("reloadContent", back)
        file.expectAdjacent(
            "AC-11: the request is made as today (the service drops it), then the indicator is taken back",
            file.one("putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)"),
            file.one("putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)") + 1,
            file.one("putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)") + 2,
            back
        )
        assertEquals("}", file.squeezed[back - 2])
        assertEquals(")", file.squeezed[back - 1])
        assertEquals("listState=null", file.squeezed[back + 1])
    }

    // ---- the whole change ----

    private fun sourceFiles(): List<File> {
        val root = Sources.main("java")
        if (!root.isDirectory) fail("app/src/main/java does not exist")
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    private fun upstreamFiles(): Map<String, File> {
        val helpers = Sources.main(HELPERS).canonicalFile
        val root = Sources.main("").canonicalFile
        return sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .associateBy { it.canonicalFile.relativeTo(root).invariantSeparatorsPath }
    }

    /**
     * Plan section 2: marked lines in 4 upstream files, 22 in all (13 added, 9 changed), and
     * nowhere else. A marked line in a fifth file is an edit the plan does not have.
     */
    @Test
    fun wiring_22MarkedLinesInFourUpstreamFiles_andNoOther() {
        val found = upstreamFiles()
            .mapValues { (_, file) -> file.readLines().count { line -> Sources.carries(line, MARKER) } }
            .filterValues { it > 0 }

        assertEquals("plan section 8: the marked lines per upstream file", EXPECTED.toSortedMap(), found.toSortedMap())
        assertEquals(22, found.values.sum())
    }

    /** Plan section 2: "no import lines": `P.` is written out in the marked lines. */
    @Test
    fun wiring_noImportLineIsAdded() {
        Marked("plan section 2", FEED_FRAGMENT).expectCount("so that the test fails until the lines are in")
        for (path in EXPECTED.keys) {
            val imports = Sources.lines("plan section 2", path).filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$path must not import from the feature's package (an import is an unmarked line)",
                emptyList<String>(),
                imports.filter { it.contains("enhancements.feedliveupdate") }
            )
            assertEquals("app/src/main/$path: no import line carries a marker", emptyList<String>(), imports.filter { it.contains("ENH:") })
        }
    }

    // ---- what must not change: these hold before and after the Code layer ----

    /**
     * AC-4, plan 6.1: "`isRefreshing == false` is what switches taps and the long-press menu
     * back on; the two listeners are not edited." Exactly two uses of `!isRefreshing`, the tap
     * and the long press, unmarked, as upstream has them.
     */
    @Test
    fun unchanged_ac4_theTwoTapChecksInFeedFragment() {
        val file = Marked("AC-4", FEED_FRAGMENT)
        val checks = file.plain("if (item is StreamItem && !isRefreshing) {")

        assertEquals("AC-4: the tap and the long press each test `!isRefreshing`", listOf("onItemClick", "onItemLongClick"), checks.map { file.functionAround(it) })
        val uses = file.all.indices.filter { Regex("""!\s*isRefreshing\b""").containsMatchIn(file.all[it]) }
        assertEquals("AC-4: and nothing else does (`!isRefreshing` as a word, not `!isRefreshingX`)", checks, uses)
        assertEquals("AC-4: the two lines carry no marker", emptyList<String>(), checks.flatMap { Sources.markers(file.all[it]) })
        // A tap in selection mode is handled before that test, as today.
        file.expectOrder(
            "selection first",
            file.one("if (item is StreamItem && selection.isActive) {"), checks[0]
        )
    }

    /**
     * Plan section 8: "The lines marked `ENH:subscription-scale` in three of these files are
     * not touched". Per file: exactly those lines carry that marker, whole and unchanged.
     */
    @Test
    fun unchanged_subscriptionScalesMarkedLinesInTheFourFiles() {
        for ((path, expected) in PARENT_LINES) {
            val lines = Sources.lines("subscription-scale", path)
            val marked = lines.filter { Sources.carries(it, PARENT) }.map { squeeze(it) }
            assertEquals(
                "app/src/main/$path: the lines marked $PARENT are the ones subscription-scale put there, unchanged",
                expected.map { squeeze(it) },
                marked
            )
        }
    }

    /** The place of `subscription-scale`'s lines that this feature builds on: the gate before the throttle and the query, the retention after the store. */
    @Test
    fun unchanged_subscriptionScalesLinesAreWhereTheyWere() {
        val model = Marked("subscription-scale", FEED_VIEW_MODEL)
        model.expectOrder(
            "the gate sits after combineLatest and before the throttle and the 500-row query",
            model.one("private val onScreenGate = ${PARENT_P}OnScreenGate() // $PARENT"),
            model.one("private var combineDisposable = Flowable"),
            model.one("feedDatabaseManager.oldestSubscriptionUpdate(groupId),"),
            model.one(".let { onScreenGate.gate(it) } // $PARENT"),
            model.one(".throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)"),
            model.one(".getStreams(groupId, showPlayedItems)")
        )
        model.expectAdjacent(
            "the throttle directly follows the gate",
            model.one(".let { onScreenGate.gate(it) } // $PARENT"),
            model.one(".throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)")
        )
        val loader = Marked("subscription-scale", FEED_LOAD_MANAGER)
        loader.expectOrder(
            "the batch is slimmed after it is stored and before the results are collected",
            loader.one(".doOnNext(DatabaseConsumer())"),
            loader.one(".map { batch -> ${PARENT_P}FeedResultRetention.retain(batch, retainNewStreams) } // $PARENT"),
            loader.one(".toList()")
        )
        val fragment = Marked("subscription-scale", FEED_FRAGMENT)
        assertEquals("onPause", fragment.functionAround(fragment.one("viewModel.setOnScreen(false) // $PARENT")))
        assertEquals("onResume", fragment.functionAround(fragment.one("viewModel.setOnScreen(true) // $PARENT")))
    }

    /**
     * One line, one feature: no line of `app/src/main/java` carries two `ENH:` markers, so a
     * line counted for this feature is never one of `subscription-scale` (or the reverse), and
     * no marker merely starts with this feature's name.
     */
    @Test
    fun unchanged_noLineCarriesTwoMarkersOrALookAlike() {
        val twice = mutableListOf<String>()
        val lookAlike = mutableListOf<String>()
        for ((path, file) in upstreamFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val markers = Sources.markers(line)
                if (markers.size > 1) twice += "$path:${index + 1} $markers"
                if (markers.any { it != MARKER && it.startsWith(MARKER) }) lookAlike += "$path:${index + 1} $markers"
            }
        }
        assertEquals("a line carries one marker", emptyList<String>(), twice)
        assertEquals("no marker that only starts with $MARKER", emptyList<String>(), lookAlike)
        assertTrue(Sources.carries("x // ENH:feed-live-update", MARKER))
        assertFalse(Sources.carries("x // ENH:feed-live-update-2", MARKER))
        assertFalse(Sources.carries("x // ENH:feed-live-updates", MARKER))
        assertFalse(Sources.carries("x // ENH:subscription-scale", MARKER))
        assertFalse(Sources.carries("x // ENH:feed-live-update", PARENT))
    }

    /**
     * AC-12 ("No setting. Texts are added only if AC-3 cannot be met with the existing ones")
     * and plan section 2: "No layout, string, setting, schema or build file change". No
     * resource file of the feature, no mention of it in any resource, the manifest or a build
     * file.
     */
    @Test
    fun unchanged_ac12_noResourceNoSettingNoBuildFileOfThisFeature() {
        val res = Sources.main("res")
        assertTrue("app/src/main/res exists", res.isDirectory)
        val names = listOf("feed-live-update", "feed_live_update", "feedliveupdate")
        val resources = res.walkTopDown().filter { it.isFile }.toList()
        assertTrue("the resources are where the test looks", resources.size > 100)

        assertEquals(
            "AC-12: no resource file of this feature (`enhancements_feed_live_update.xml` does not exist)",
            emptyList<String>(),
            resources.filter { file -> names.any { file.name.contains(it, ignoreCase = true) } }.map { it.path }
        )
        assertEquals(
            "AC-12: no layout, string, key or default mentions this feature or carries its marker",
            emptyList<String>(),
            resources.filter { it.extension == "xml" }.filter { file -> file.readText().let { text -> names.any { text.contains(it, ignoreCase = true) } } }.map { it.path }
        )

        val module = Sources.moduleFile("build.gradle").absoluteFile.parentFile!!
        val buildFiles = listOf(
            File(module, "build.gradle"),
            File(module, "proguard-rules.pro"),
            Sources.main("AndroidManifest.xml"),
            File(module.parentFile, "build.gradle"),
            File(module.parentFile, "settings.gradle"),
            File(module.parentFile, "gradle.properties")
        ).filter { it.isFile }
        assertTrue("app/build.gradle is where the test looks", buildFiles.any { it.name == "build.gradle" })
        assertEquals(
            "AC-12: no build file or manifest mentions this feature",
            emptyList<String>(),
            buildFiles.filter { file -> file.readText().let { text -> names.any { text.contains(it, ignoreCase = true) } } }.map { it.path }
        )
    }
}
