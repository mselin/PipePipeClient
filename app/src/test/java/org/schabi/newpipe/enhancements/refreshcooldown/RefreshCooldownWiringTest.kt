package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the 17 marked lines plan section 9 puts into six upstream files, read as
 * text. The new classes do nothing unless upstream calls them, the loader, the service, the
 * fragments and the dialog cannot run on the JVM, and a rebase can drop or move a marked line
 * without any other test noticing.
 *
 * Per file:
 *
 * 1. **The number of lines carrying `ENH:refresh-cooldown`** (plan section 9, last paragraph):
 *
 *    | File | Lines | Plan |
 *    |---|---|---|
 *    | `FeedLoadManager.kt` | 5 | 4 added, 1 changed |
 *    | `FeedLoadService.kt` | 1 | 1 changed |
 *    | `FeedDAO.kt` | 6 | 6 added |
 *    | `FeedFragment.kt` | 1 | 1 added |
 *    | `FeedGroupDialog.kt` | 2 | 2 added |
 *    | `SubscriptionFragment.kt` | 2 | 1 changed, 1 added |
 *    | total | 17 | 14 added + 3 changed |
 *
 *    A line counts when it carries exactly this marker: `ENH:refresh-cooldown-2` is not it, and
 *    neither is a line of `ENH:subscription-scale` or `ENH:feed-live-update`, on which this
 *    feature is built and which mark lines in three of the six files.
 * 2. **What each marked line says**: the whole line, as section 9 prints it with `P.` written
 *    out, compared without whitespace. With the count, the marked lines of a file are exactly
 *    the plan's.
 * 3. **Where it is**: the lines before and after it, and the function around it.
 * 4. **What a changed line replaced** is gone, and what the plan leaves alone is still there.
 *
 * Until the Code layer adds the lines, the tests whose names start with `ac` or `wiring_17`
 * fail with "must have N lines marked ENH:refresh-cooldown" (found 0). The tests named
 * `unchanged_…` hold before and after: they guard what the feature must not touch, among it
 * the marked lines of the two features below it.
 */
class RefreshCooldownWiringTest {

    private companion object {
        const val MARKER = Sources.MARKER
        const val SCALE = Sources.SCALE_MARKER
        const val LIVE = Sources.LIVE_MARKER
        const val P = "org.schabi.newpipe.enhancements.refreshcooldown."
        const val SCALE_P = "org.schabi.newpipe.enhancements.subscriptionscale."
        const val LIVE_P = "org.schabi.newpipe.enhancements.feedliveupdate."
        const val HELPERS = "java/org/schabi/newpipe/enhancements/refreshcooldown"

        const val FEED_LOAD_MANAGER = "java/org/schabi/newpipe/local/feed/service/FeedLoadManager.kt"
        const val FEED_LOAD_SERVICE = "java/org/schabi/newpipe/local/feed/service/FeedLoadService.kt"
        const val FEED_DAO = "java/org/schabi/newpipe/database/feed/dao/FeedDAO.kt"
        const val FEED_FRAGMENT = "java/org/schabi/newpipe/local/feed/FeedFragment.kt"
        const val FEED_GROUP_DIALOG = "java/org/schabi/newpipe/local/subscription/dialog/FeedGroupDialog.kt"
        const val SUBSCRIPTION_FRAGMENT = "java/org/schabi/newpipe/local/subscription/SubscriptionFragment.kt"

        const val NOTIFICATION_WORKER = "java/org/schabi/newpipe/local/feed/notifications/NotificationWorker.kt"
        const val FEED_VIEW_MODEL = "java/org/schabi/newpipe/local/feed/FeedViewModel.kt"

        /** File to its number of marked lines (plan section 9; total 17 = 14 added + 3 changed). */
        val EXPECTED = linkedMapOf(
            FEED_LOAD_MANAGER to 5,
            FEED_LOAD_SERVICE to 1,
            FEED_DAO to 6,
            FEED_FRAGMENT to 1,
            FEED_GROUP_DIALOG to 2,
            SUBSCRIPTION_FRAGMENT to 2
        )

        /**
         * The lines the two features below marked in the files this feature edits or must leave
         * alone, whole and in the file's order (their plans' edit lists; their own wiring tests
         * pin the same text).
         */
        val EARLIER_LINES = linkedMapOf(
            // subscription-scale has no line left here since upstream 5.6.0 (plan amendment of 2026-11-25)
            FEED_LOAD_MANAGER to listOf(
                ".doOnNext { ${LIVE_P}FeedRefreshLog.app.batchStored() } // $LIVE",
                ".doOnSubscribe { ${LIVE_P}FeedRefreshLog.app.started(groupId) } // $LIVE",
                ".doFinally { ${LIVE_P}FeedRefreshLog.app.ended() } // $LIVE"
            ),
            FEED_LOAD_SERVICE to emptyList(),
            FEED_DAO to emptyList(),
            FEED_FRAGMENT to listOf(
                "private val liveRefresh get() = viewModel.stateLiveData.value is FeedState.ProgressState // $LIVE",
                "viewModel.setOnScreen(false) // $SCALE",
                "viewModel.setOnScreen(true) // $SCALE",
                "feedBinding.swipeRefreshLayout.isRefreshing = liveRefresh // $LIVE",
                "if (progressState.items != null) return handleLiveProgress(progressState.items) // $LIVE",
                "private fun handleLiveProgress(items: List<StreamItem>): Unit = // $LIVE",
                "if (${LIVE_P}FeedListRules.sameRows(originalItems, items) { it.streamWithState }) hideLoading() // $LIVE",
                "else handleLoadedState(FeedState.LoadedState(items, oldestSubscriptionUpdate, 0)) // $LIVE",
                "filteredItems.addAll(if (isFilterEnabled) originalItems.filter { ${LIVE_P}FeedListRules.matches(" +
                    "it.streamWithState.stream.title, it.streamWithState.stream.uploader, editText?.text?.toString().orEmpty()) } else originalItems) // $LIVE",
                "${LIVE_P}FeedListUpdate.show(feedBinding.itemsList, groupAdapter, filteredItems.toList(), liveRefresh, " +
                    "{ if (tryGetNewItemsLoadedButton()?.isVisible == false) showNewItemsLoaded() }) { // $LIVE",
                "feedBinding.refreshSubtitleText.isVisible = if (liveRefresh) feedBinding.refreshSubtitleText.isVisible && " +
                    "feedBinding.refreshSubtitleText.text.isNotEmpty() else feedsNotLoaded // $LIVE",
                "(viewModel.stateLiveData.value as? FeedState.ProgressState)?.takeIf { it.items != null }?.let { feedBinding.refreshText.text = " +
                    "${LIVE_P}FeedListRules.progressText(it.currentProgress, it.maxProgress, getString(R.string.feed_notification_loading), " +
                    "if (it.progressMessage > 0) getString(it.progressMessage) else null) } // $LIVE",
                "if (highlightCount > 0 && !liveRefresh) { // $LIVE",
                "if (${LIVE_P}FeedRefreshLog.app.runningElsewhere(groupId)) feedBinding.swipeRefreshLayout.isRefreshing = false // $LIVE"
            ),
            FEED_GROUP_DIALOG to emptyList(),
            SUBSCRIPTION_FRAGMENT to listOf(
                "viewModel.setOnScreen(true) // $SCALE",
                "viewModel.setOnScreen(false) // $SCALE",
                "${SCALE_P}SectionUpdate.update(subscriptionsSection, items) // $SCALE"
            ),
            NOTIFICATION_WORKER to emptyList(),
            FEED_VIEW_MODEL to listOf(
                "private val onScreenGate = ${SCALE_P}OnScreenGate() // $SCALE",
                "private val live = ${LIVE_P}FeedLiveUpdate<StreamWithState, StreamItem>(groupId) // $LIVE",
                ".let { live.wake(it) } // $LIVE",
                ".let { onScreenGate.gate(it) } // $SCALE",
                ".filter { live.next(it.t1, it.t2) } // $LIVE",
                "val streamItems = if (live.rebuild) // $LIVE",
                "CombineResultDataHolder(live.shownAs(event), live.rows(streamItems), notLoadedCount, oldestUpdate) // $LIVE",
                "is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage, " +
                    "live.items(listFromDB) { e -> StreamItem(e) }) // $LIVE",
                "fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen) // $SCALE"
            )
        )
    }

    private fun squeeze(text: String): String = Sources.squeeze(text)

    /** A file of `app/src/main` and its marked lines. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = Sources.lines(ac, path)
        val squeezed: List<String> = all.map { squeeze(it) }
        val mine: List<Int> = all.indices.filter { Sources.carries(all[it], MARKER) }
        val text: String get() = all.joinToString("\n")

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

        fun expectIn(function: String, vararg indexes: Int): Marked {
            for (index in indexes) {
                assertEquals("$ac: app/src/main/$path: line ${index + 1} `${all[index].trim()}` belongs to $function", function, functionAround(index))
            }
            return this
        }

        fun expectLine(why: String, index: Int, code: String): Marked {
            assertEquals("$ac: app/src/main/$path: $why (line ${index + 1})", squeeze(code), squeezed.getOrNull(index))
            return this
        }
    }

    // ---- FeedLoadManager.kt: 4 added, 1 changed ----

    private fun loader(ac: String) = Marked(ac, FEED_LOAD_MANAGER).expectCount("plan section 9: 4 added, 1 changed")

    /**
     * AC-9, plan 6.3 and section 9: `startLoading` takes the forced ids as its last parameter,
     * with a default, so that `NotificationWorker`'s call and every other caller compile
     * unchanged; and one `CooldownRefresh` is made per call, from the loader's own arguments.
     */
    @Test
    fun ac9_wiring_feedLoadManager_theForcedParameterAndOneCooldownRefreshPerCall() {
        val file = loader("AC-9")
        val parameter = file.marked("cooldownForced: LongArray? = null,")
        val cooldown = file.marked("val cooldown = ${P}CooldownRefresh(context, groupId, ignoreOutdatedThreshold, cooldownForced)")

        file.expectAdjacent(
            "the parameter directly follows ignoreOutdatedThreshold and is the last one",
            file.one("ignoreOutdatedThreshold: Boolean = false,"),
            parameter,
            file.one("): Single<List<FeedUpdateInfo>> {")
        )
        file.expectAdjacent(
            "`val cooldown` directly follows the `filterFutureItems` statement",
            file.one("val filterFutureItems = defaultSharedPreferences.getBoolean("),
            file.one("context.getString(R.string.filter_future_items_key),"),
            file.one("context.getString(R.string.filter_future_items_key),") + 1,
            file.one("context.getString(R.string.filter_future_items_key),") + 2,
            cooldown
        )
        file.expectLine("the statement before it ends with `)`", cooldown - 1, ")")
        file.expectOrder(
            "AC-7: it is made before the threshold is computed, for every call, so that the constructor sees `ignoreOutdatedThreshold`",
            parameter, cooldown, file.one("val outdatedThreshold = if (ignoreOutdatedThreshold) {")
        )
        file.expectIn("startLoading", parameter, cooldown)
    }

    /**
     * AC-5, AC-7, AC-11, plan 5.1 and section 9: the threshold of a manual refresh comes from
     * `queryThreshold`, fed with upstream's own `now` and upstream's own reading of the
     * setting; the branch of the notification check (`ignoreOutdatedThreshold`) is unchanged
     * and unmarked; the three queries get the threshold as before.
     */
    @Test
    fun ac5_wiring_feedLoadManager_theThresholdOfAManualRefresh() {
        val file = loader("AC-5")
        val threshold = file.marked("cooldown.queryThreshold(OffsetDateTime.now(ZoneOffset.UTC), thresholdOutdatedSeconds)")
        val branch = file.one("val outdatedThreshold = if (ignoreOutdatedThreshold) {")

        file.expectGone("OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(thresholdOutdatedSeconds.toLong())", "changed one for one")
        file.expectAdjacent(
            "AC-7: the notification check's branch is as it was: `now`, unmarked; then the `else` branch",
            branch, file.one("OffsetDateTime.now(ZoneOffset.UTC)"), branch + 2, file.one("val thresholdOutdatedSeconds = (")
        )
        file.expectLine("the `else` branch opens", branch + 2, "} else {")
        assertEquals("AC-7: that line carries no marker", emptyList<String>(), Sources.markers(file.all[branch + 1]))
        file.expectAdjacent(
            "AC-1, AC-11: upstream's reading of the global setting stays and supplies the seconds; the changed line is the `else` branch's value",
            file.one("val thresholdOutdatedSeconds = ("),
            file.one("defaultSharedPreferences.getString("),
            file.one("context.getString(R.string.feed_update_threshold_key),"),
            file.one("context.getString(R.string.feed_update_threshold_default_value)"),
            file.one(") ?: context.getString(R.string.feed_update_threshold_default_value)"),
            file.one(").toInt()"),
            threshold
        )
        file.expectLine("the `else` branch closes after it", threshold + 1, "}")
        assertEquals("AC-7: `queryThreshold` is called at one place only", 1, Sources.count(file.text, "queryThreshold("))

        // Plan section 9: ":103-111 stay: they get the whole-scope threshold through `outdatedThreshold`".
        val all = file.one("FeedGroupEntity.GROUP_ALL_ID -> feedDatabaseManager.outdatedSubscriptions(")
        file.expectAdjacent(
            "the three queries and their `outdatedThreshold` arguments are unchanged",
            file.one("val outdatedSubscriptions = when (groupId) {"),
            all,
            file.one("outdatedThreshold"),
            all + 2,
            file.one("GROUP_NOTIFICATION_ENABLED -> feedDatabaseManager.outdatedSubscriptionsWithNotificationMode("),
            file.one("outdatedThreshold, NotificationMode.ENABLED"),
            all + 5,
            file.one("else -> feedDatabaseManager.outdatedSubscriptionsForGroup(groupId, outdatedThreshold)")
        )
        file.expectOrder("the threshold is computed before the queries are chosen", threshold, all)
        file.expectIn("startLoading", threshold)
    }

    /**
     * AC-5, AC-9, AC-11, plan 5.2 and section 9: the cooldown decision sits directly after
     * upstream's filter of unknown services and before the progress maximum is set, so that
     * `maxProgress` is still the number of channels to load and an empty selection ends the
     * refresh as today.
     */
    @Test
    fun ac5_wiring_feedLoadManager_theSelectionSitsBeforeTheProgressMaximum() {
        val file = loader("AC-5")
        val select = file.marked(".map { list -> cooldown.select(list) }")

        file.expectAdjacent(
            "`cooldown.select` directly follows the `ServiceList.getById` line; `maxProgress.set(it.size)` counts its result",
            file.one("return outdatedSubscriptions"),
            file.one(".take(1)"),
            file.one(".take(1)") + 1,
            file.one(".map { list -> list.filter { ServiceList.getById(it.serviceId) != null } }"),
            select,
            select + 1,
            file.one("currentProgress.set(0)"),
            file.one("maxProgress.set(it.size)"),
            file.one("maxProgress.set(it.size)") + 1,
            file.one(".filter { it.isNotEmpty() }")
        )
        file.expectLine("the progress maximum is set in the next operator", select + 1, ".doOnNext {")
        assertEquals("the selection is made once", 1, Sources.count(file.text, "cooldown.select("))
        file.expectIn("startLoading", select)
    }

    /**
     * AC-8, AC-10, plan 6.1 and section 9: the report is published when the loading has ended:
     * directly after `.toList()` and directly before the `postProcessFeed()` line, so it exists
     * before the result event is posted. The lines of the two features below keep their places.
     */
    @Test
    fun ac8_wiring_feedLoadManager_theReportIsPublishedWhenTheLoadingHasEnded() {
        val file = loader("AC-8")
        val done = file.marked(".doOnSuccess { cooldown.done() }")

        val batch = file.one(".doOnNext { ${LIVE_P}FeedRefreshLog.app.batchStored() } // $LIVE")
        val subscribeOn = file.one(".subscribeOn(Schedulers.io())")
        file.expectAdjacent(
            "the batch is stored and counted",
            file.one(".buffer(BUFFER_COUNT_BEFORE_INSERT)"),
            file.one(".doOnNext(DatabaseConsumer())"),
            batch
        )
        file.expectOrder(
            "plan amendment of 2026-11-25: then upstream trims it (`forNotification()`)",
            batch, file.all.indexOfFirst { it.contains("forNotification()") }, subscribeOn
        )
        file.expectAdjacent(
            "the end of startLoading's chain: collected, published, post-processed, started and ended",
            subscribeOn,
            file.one(".toList()"),
            done,
            file.one(".flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }"),
            file.one(".doOnSubscribe { ${LIVE_P}FeedRefreshLog.app.started(groupId) } // $LIVE"),
            file.one(".doFinally { ${LIVE_P}FeedRefreshLog.app.ended() } // $LIVE")
        )
        file.expectLine("the function closes after the chain", file.one(".doFinally { ${LIVE_P}FeedRefreshLog.app.ended() } // $LIVE") + 1, "}")
        assertEquals("the report is published at one place", 1, Sources.count(file.text, "cooldown.done()"))
        file.expectOrder("selected first, published at the end", file.marked(".map { list -> cooldown.select(list) }"), done)
        file.expectIn("startLoading", done)
    }

    // ---- FeedLoadService.kt: 1 changed ----

    /**
     * AC-9, plan 6.3 and section 9: the service passes the intent's forced ids to the loader
     * with the start it belongs to; a start that the service drops (a refresh is running)
     * returns before this line and takes its extra with it.
     */
    @Test
    fun ac9_wiring_feedLoadService_passesTheForcedIdsOfThisStart() {
        val file = Marked("AC-9", FEED_LOAD_SERVICE).expectCount("plan section 9: `:93` changed")
        val start = file.marked("loadingDisposable = feedLoadManager.startLoading(groupId, cooldownForced = intent.getLongArrayExtra(${P}SkipReports.EXTRA_FORCED))")

        file.expectGone("loadingDisposable = feedLoadManager.startLoading(groupId)", "changed one for one")
        file.expectAdjacent(
            "the group id is read from the same intent, directly before",
            file.one("val groupId = intent.getLongExtra(EXTRA_GROUP_ID, FeedGroupEntity.GROUP_ALL_ID)"), start
        )
        file.expectLine("the chain on the returned Single stays", start + 1, ".observeOn(AndroidSchedulers.mainThread())")
        file.expectAdjacent(
            "plan 6.3: a start during a running refresh is dropped before the extra is read",
            file.one("if (intent == null || loadingDisposable != null) {"),
            file.plain("return START_NOT_STICKY").first()
        )
        file.expectOrder("the drop comes first", file.one("if (intent == null || loadingDisposable != null) {"), start)
        assertEquals("the loader is started at one place", 1, Sources.count(file.text, "feedLoadManager.startLoading("))
        file.expectIn("onStartCommand", start)
    }

    // ---- FeedDAO.kt: 6 added ----

    /**
     * AC-5, plan 5.1 and section 9: three read-only queries at the end of `FeedDAO`, each
     * annotation directly above its function, each a plain `List` (run on the calling thread).
     * What Room makes of them: `CooldownQueriesTest` (instrumented).
     */
    @Test
    fun ac5_wiring_feedDao_threeQueriesAtTheEndOfTheClass() {
        val file = Marked("AC-5", FEED_DAO).expectCount("plan section 9: 6 added after `:301`")
        val lastUpdatedQuery = file.marked("@Query(\"SELECT * FROM feed_last_updated\")")
        val lastUpdated = file.marked("abstract fun cooldownLastUpdated(): List<FeedLastUpdatedEntity>")
        val membershipsQuery = file.marked("@Query(\"SELECT * FROM feed_group_subscription_join\")")
        val memberships = file.marked("abstract fun cooldownMemberships(): List<org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity>")
        val groupsQuery = file.marked("@Query(\"SELECT * FROM feed_group\")")
        val groups = file.marked("abstract fun cooldownGroups(): List<org.schabi.newpipe.database.feed.model.FeedGroupEntity>")

        file.expectAdjacent(
            "the six lines follow the last function of the class (getOutdatedWithNotificationMode), each annotation directly above its function",
            file.one("abstract fun getOutdatedWithNotificationMode("),
            file.one("outdatedThreshold: OffsetDateTime,"),
            file.one("@NotificationMode notificationMode: Int"),
            file.one("): Flowable<List<SubscriptionEntity>>"),
            lastUpdatedQuery, lastUpdated, membershipsQuery, memberships, groupsQuery, groups
        )
        file.expectLine("the class closes after them", groups + 1, "}")
        assertEquals("nothing but blank lines follows the class's `}`", emptyList<String>(), file.all.drop(groups + 2).filter { it.isNotBlank() })
        // Plan section 2: "No Room entity, schema, migration … change": upstream's three scope queries are as they were.
        assertEquals("the three scope queries keep their condition", 3, Sources.count(file.text, "lu.last_updated IS NULL OR lu.last_updated < :outdatedThreshold"))
        file.one("abstract fun getAllOutdated(outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>")
        file.one("abstract fun getAllOutdatedForGroup(groupId: Long, outdatedThreshold: OffsetDateTime): Flowable<List<SubscriptionEntity>>")
    }

    // ---- FeedFragment.kt: 1 added ----

    /**
     * AC-8, AC-10, plan 6.1 and section 9: the page offers the report at the end of
     * `handleResult`, for a `LoadedState` only (the end of a refresh or an idle page; never a
     * progress state, so not during a refresh), for its own feed, and says whether upstream's
     * error message is being shown.
     */
    @Test
    fun ac8_wiring_feedFragment_offersTheReportAtTheEndOfHandleResult() {
        val file = Marked("AC-8", FEED_FRAGMENT).expectCount("plan section 9: one line after `:736`")
        val offer = file.marked("if (result is FeedState.LoadedState) ${P}SkippedMessageUi.offer(this, groupId, result.itemsErrors.isNotEmpty())")
        val function = file.one("override fun handleResult(result: FeedState) {")

        file.expectAdjacent(
            "handleResult as it was, then the line, directly after `updateRefreshViewState()` and last",
            function,
            file.one("when (result) {"),
            file.one("is FeedState.ProgressState -> handleProgressState(result)"),
            file.one("is FeedState.LoadedState -> handleLoadedState(result)"),
            file.one("is FeedState.ErrorState -> if (handleErrorState(result)) return"),
            function + 5,
            function + 6,
            function + 7,
            offer
        )
        file.expectLine("the `when` closes", function + 5, "}")
        file.expectLine("a blank line as upstream has it", function + 6, "")
        file.expectLine("AC-8: after the state is shown and the header refreshed", function + 7, "updateRefreshViewState()")
        file.expectLine("the function closes after it", offer + 1, "}")
        file.expectIn("handleResult", offer)
        assertEquals("the report is offered at one place", 1, Sources.count(file.text, "SkippedMessageUi"))
        // AC-8: "when the refresh also has messages about channels that could not be loaded, those come first": upstream's own call stays.
        file.one("handleItemsErrors(loadedState.itemsErrors)")
    }

    // ---- FeedGroupDialog.kt: 2 added ----

    /**
     * AC-2, plan 7.1 and section 9: the value chosen in the dialog is stored when the dialog
     * is confirmed: after its two checks (empty name, no subscription), which return without a
     * save, and before the group is written, with the trimmed name the database gets.
     */
    @Test
    fun ac2_wiring_feedGroupDialog_savesAfterTheChecksAndBeforeTheGroupIsWritten() {
        val file = Marked("AC-2", FEED_GROUP_DIALOG).expectCount("plan section 9: one line after `:303`, one after `:322`")
        val save = file.marked("${P}GroupCooldownUi.save(feedGroupCreateBinding.optionsRoot, groupId, name)")
        val check = file.one("if (selectedSubscriptions.isEmpty()) {")

        file.expectAdjacent(
            "`save` directly follows the `}` of the second check",
            check,
            file.one("Toast.makeText(requireContext(), getString(R.string.feed_group_dialog_empty_selection), Toast.LENGTH_SHORT).show()"),
            check + 2,
            check + 3,
            save
        )
        file.expectLine("the check returns", check + 2, "return")
        file.expectLine("and closes", check + 3, "}")
        file.expectOrder(
            "name, first check, second check, save, write",
            file.one("val name = feedGroupCreateBinding.groupNameInput.text.toString().trim()"),
            file.one("if (name.isBlank()) {"),
            check,
            save,
            file.one("when (groupId) {"),
            file.one("NO_GROUP_SELECTED -> viewModel.createGroup(name, icon, selectedSubscriptions)"),
            file.one("else -> viewModel.updateGroup(name, icon, selectedSubscriptions, groupSortOrder)")
        )
        file.expectIn("handlePositiveButtonInitialScreen", save)
        assertEquals("the value is stored at one place (not on delete, not on cancel)", 1, Sources.count(file.text, "GroupCooldownUi.save("))
        // Plan 7.1: "Delete: goes through handlePositiveButton, not through the save".
        file.one("currentScreen is DeleteScreen -> viewModel.deleteGroup()")
    }

    /**
     * AC-2, plan 7.1 and section 9: the row is added when the group arrives, as the last
     * statement of `handleGroup`, with the group as the database has it. A new group never
     * reaches `handleGroup`, so its dialog has no row.
     */
    @Test
    fun ac2_wiring_feedGroupDialog_attachesTheRowWhenTheGroupArrives() {
        val file = Marked("AC-2", FEED_GROUP_DIALOG).expectCount("plan section 9: one line after `:303`, one after `:322`")
        val attach = file.marked("${P}GroupCooldownUi.attach(feedGroupCreateBinding.optionsRoot, feedGroupEntity)")
        val nameCheck = file.one("if (feedGroupCreateBinding.groupNameInput.text.isNullOrBlank()) {")

        file.expectAdjacent(
            "`attach` directly follows the `}` of handleGroup's last statement",
            nameCheck, file.one("feedGroupCreateBinding.groupNameInput.setText(name)"), nameCheck + 2, attach
        )
        file.expectLine("the name check closes", nameCheck + 2, "}")
        file.expectLine("handleGroup closes after it", attach + 1, "}")
        file.expectOrder("it is handleGroup's", file.one("private fun handleGroup(feedGroupEntity: FeedGroupEntity? = null) {"), attach)
        file.expectIn("handleGroup", attach)
        assertEquals("the row is attached at one place", 1, Sources.count(file.text, "GroupCooldownUi.attach("))
    }

    // ---- SubscriptionFragment.kt: 1 changed, 1 added ----

    /**
     * AC-3, plan 7.2 and section 9: the long-press menu of a channel on the Subscriptions tab
     * gets a fourth entry after "Unsubscribe", whose text shows the current value, and a fourth
     * action that opens the chooser. The three entries and actions before it keep their index.
     */
    @Test
    fun ac3_wiring_subscriptionFragment_aFourthMenuEntryAndItsAction() {
        val file = Marked("AC-3", SUBSCRIPTION_FRAGMENT).expectCount("plan section 9: `:295` changed, one line after `:305`")
        val entry = file.marked("getString(R.string.unsubscribe), ${P}SubscriptionCooldownUi.entry(requireContext(), selectedItem)")
        val action = file.marked("3 -> ${P}SubscriptionCooldownUi.choose(requireContext(), selectedItem)")

        file.expectGone("getString(R.string.unsubscribe)", "changed one for one")
        file.expectAdjacent(
            "the entry is the fourth and last of the menu, on \"Unsubscribe\"'s line",
            file.one("private fun showLongTapDialog(selectedItem: ChannelInfoItem) {"),
            file.one("val commands = arrayOf("),
            file.one("getString(R.string.share),"),
            file.one("getString(R.string.open_in_browser),"),
            entry
        )
        file.expectLine("the array closes after it", entry + 1, ")")
        val unsubscribe = file.one("2 -> deleteChannel(selectedItem)")
        file.expectAdjacent("the action is the fourth and last, directly after \"Unsubscribe\"'s", unsubscribe, action)
        file.expectLine("the `when` closes after it", action + 1, "}")
        file.expectOrder(
            "the three actions before it keep their index",
            file.one("when (i) {"), file.one("0 -> ShareUtils.shareText("), file.one("1 -> ShareUtils.openUrlInBrowser(requireContext(), selectedItem.url)"), unsubscribe, action
        )
        file.expectIn("showLongTapDialog", entry, action)
        assertEquals("AC-3, Q7: the entry is made at one place", 1, Sources.count(file.text, "SubscriptionCooldownUi.entry("))
    }

    // ---- the whole change ----

    private fun sourceFiles(): List<File> {
        val root = Sources.main("java")
        if (!root.isDirectory) fail("app/src/main/java does not exist")
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    }

    /** Every source file outside this feature's own package, by its path under `app/src/main`. */
    private fun upstreamFiles(): Map<String, File> {
        val helpers = Sources.main(HELPERS).canonicalFile
        val root = Sources.main("").canonicalFile
        return sourceFiles()
            .filter { !it.canonicalFile.startsWith(helpers) }
            .associateBy { it.canonicalFile.relativeTo(root).invariantSeparatorsPath }
    }

    /**
     * Plan section 9, last paragraph: marked lines in 6 upstream files, 17 in all (14 added,
     * 3 changed), and nowhere else: not in `NotificationWorker.kt` (AC-7), not in
     * `FeedViewModel.kt`, not in a file of `enhancements/feedliveupdate` or
     * `enhancements/subscriptionscale`.
     */
    @Test
    fun wiring_17MarkedLinesInSixUpstreamFiles_andNoOther() {
        val files = upstreamFiles()
        assertTrue("the sources are where the test looks", files.size > 500)
        val found = files
            .mapValues { (_, file) -> file.readLines().count { line -> Sources.carries(line, MARKER) } }
            .filterValues { it > 0 }

        assertEquals("plan section 9: the marked lines per upstream file", EXPECTED.toSortedMap(), found.toSortedMap())
        assertEquals(17, found.values.sum())
    }

    /** Plan section 2: "no import lines": `P.` is written out in the marked lines. */
    @Test
    fun wiring_noImportLineIsAdded() {
        Marked("plan section 2", FEED_LOAD_MANAGER).expectCount("so that the test fails until the lines are in")
        for (path in EXPECTED.keys) {
            val imports = Sources.lines("plan section 2", path).filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$path must not import from the feature's package (an import is an unmarked line)",
                emptyList<String>(),
                imports.filter { it.contains("enhancements.refreshcooldown") }
            )
            assertEquals("app/src/main/$path: no import line carries a marker", emptyList<String>(), imports.filter { it.contains("ENH:") })
        }
    }

    // ---- what must not change: these hold before and after the Code layer ----

    /**
     * AC-7, plan 5.3: "`NotificationWorker.kt` is not edited". It carries no line of this
     * feature, still asks for `ignoreOutdatedThreshold = true` and its own group, and passes no
     * forced ids.
     */
    @Test
    fun unchanged_ac7_theNotificationCheckIsNotEdited() {
        val file = Marked("AC-7", NOTIFICATION_WORKER)
        val call = file.one("feedLoadManager.startLoading(")

        assertEquals("AC-7: no line of NotificationWorker.kt carries $MARKER", emptyList<String>(), file.mine.map { file.all[it].trim() })
        file.expectAdjacent(
            "AC-7: the notification check's call, as upstream has it",
            call,
            file.one("ignoreOutdatedThreshold = true,"),
            file.one("groupId = FeedLoadManager.GROUP_NOTIFICATION_ENABLED"),
            call + 3
        )
        file.expectLine("the call closes", call + 3, ")")
        assertEquals("AC-7: it passes no forced ids", 0, Sources.count(file.text, "cooldownForced"))
        assertEquals("AC-7: it does not mention this feature", 0, Sources.count(file.text, "refreshcooldown"))
    }

    /** Plan section 9: "Not edited: `FeedViewModel.kt` …": no line of this feature, no mention of its package. */
    @Test
    fun unchanged_theFeedViewModelIsNotEdited() {
        val file = Marked("plan section 9", FEED_VIEW_MODEL)

        assertEquals("no line of FeedViewModel.kt carries $MARKER", emptyList<String>(), file.mine.map { file.all[it].trim() })
        assertEquals(0, Sources.count(file.text, "refreshcooldown"))
        assertEquals(0, Sources.count(file.text, "SkipReports"))
    }

    /**
     * Plan section 9: "The lines marked `ENH:subscription-scale` and `ENH:feed-live-update` are
     * not touched". In the six files, in `NotificationWorker.kt` and in `FeedViewModel.kt`:
     * exactly those lines carry one of the two markers, whole, unchanged and in this order.
     */
    @Test
    fun unchanged_theMarkedLinesOfTheTwoFeaturesBelow_wholeAndInOrder() {
        for ((path, expected) in EARLIER_LINES) {
            val lines = Sources.lines("the features below", path)
            val marked = lines.filter { Sources.carries(it, SCALE) || Sources.carries(it, LIVE) }.map { squeeze(it) }
            assertEquals(
                "app/src/main/$path: the lines marked $SCALE or $LIVE are the ones those features put there, unchanged and in their order",
                expected.map { squeeze(it) },
                marked
            )
            assertEquals(
                "app/src/main/$path: none of them is also counted for this feature",
                emptyList<String>(),
                lines.filter { (Sources.carries(it, SCALE) || Sources.carries(it, LIVE)) && Sources.carries(it, MARKER) }
            )
        }
    }

    /**
     * The places of those lines that their own wiring tests pin and that this feature's edits
     * come near (plan section 9: "`batchStored` directly after `.doOnNext(DatabaseConsumer())`;
     * `.toList()` before the `postProcessFeed()` line (order, not adjacency); `started` and
     * `ended` directly after it and last").
     */
    @Test
    fun unchanged_theLinesOfTheTwoFeaturesBelow_areWhereTheirOwnTestsExpectThem() {
        val loader = Marked("the features below", FEED_LOAD_MANAGER)
        val batch = loader.one(".doOnNext { ${LIVE_P}FeedRefreshLog.app.batchStored() } // $LIVE")
        val post = loader.one(".flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }")
        val started = loader.one(".doOnSubscribe { ${LIVE_P}FeedRefreshLog.app.started(groupId) } // $LIVE")
        val ended = loader.one(".doFinally { ${LIVE_P}FeedRefreshLog.app.ended() } // $LIVE")
        loader.expectAdjacent("feed-live-update: batchStored directly after the DatabaseConsumer", loader.one(".doOnNext(DatabaseConsumer())"), batch)
        loader.expectOrder(
            "stored, counted, trimmed by upstream (plan amendment of 2026-11-25), collected, post-processed",
            batch, loader.all.indexOfFirst { it.contains("forNotification()") }, loader.one(".toList()"), post
        )
        loader.expectAdjacent("feed-live-update: started and ended directly after postProcessFeed() and last", post, started, ended)
        loader.expectLine("the chain ends with ended()", ended + 1, "}")
        assertEquals("subscription-scale's parameter is gone", 0, Sources.count(loader.text, "retainNewStreams"))
        loader.expectIn("startLoading", batch, started, ended)

        val feed = Marked("the features below", FEED_FRAGMENT)
        assertEquals("onPause", feed.functionAround(feed.one("viewModel.setOnScreen(false) // $SCALE")))
        assertEquals("onResume", feed.functionAround(feed.one("viewModel.setOnScreen(true) // $SCALE")))
        assertEquals("hideLoading", feed.functionAround(feed.one("feedBinding.swipeRefreshLayout.isRefreshing = liveRefresh // $LIVE")))
        assertEquals("handleProgressState", feed.functionAround(feed.one("if (progressState.items != null) return handleLiveProgress(progressState.items) // $LIVE")))
        assertEquals("updateRefreshViewState", feed.functionAround(feed.plain("(viewModel.stateLiveData.value as? FeedState.ProgressState)?.takeIf { it.items != null }?.let { feedBinding.refreshText.text = ${LIVE_P}FeedListRules.progressText(it.currentProgress, it.maxProgress, getString(R.string.feed_notification_loading), if (it.progressMessage > 0) getString(it.progressMessage) else null) } // $LIVE").single()))
        assertEquals("reloadContent", feed.functionAround(feed.one("if (${LIVE_P}FeedRefreshLog.app.runningElsewhere(groupId)) feedBinding.swipeRefreshLayout.isRefreshing = false // $LIVE")))
        // feed-live-update's own test: "handleResult calls updateRefreshViewState()", for every state.
        assertTrue(feed.plain("updateRefreshViewState()").any { feed.functionAround(it) == "handleResult" })
        feed.one("is FeedState.ProgressState -> handleProgressState(result)")

        val tab = Marked("the features below", SUBSCRIPTION_FRAGMENT)
        tab.expectOrder(
            "subscription-scale: on screen in onResume, off in onPause, the list update (plan amendment of 2026-11-27), all before the long-press menu",
            tab.one("viewModel.setOnScreen(true) // $SCALE"), tab.one("viewModel.setOnScreen(false) // $SCALE"),
            tab.one("${SCALE_P}SectionUpdate.update(subscriptionsSection, items) // $SCALE"),
            tab.one("private fun showLongTapDialog(selectedItem: ChannelInfoItem) {")
        )
        assertEquals("onResume", tab.functionAround(tab.one("viewModel.setOnScreen(true) // $SCALE")))
        assertEquals("onPause", tab.functionAround(tab.one("viewModel.setOnScreen(false) // $SCALE")))
    }

    /**
     * One line, one feature: no line of `app/src/main/java` carries two `ENH:` markers, so a
     * line counted for this feature is never one of the features below (or the reverse), and
     * no marker merely starts with this feature's name or is a beginning of it.
     */
    @Test
    fun unchanged_noLineCarriesTwoMarkersOrALookAlike() {
        val twice = mutableListOf<String>()
        val lookAlike = mutableListOf<String>()
        for ((path, file) in upstreamFiles()) {
            file.readLines().forEachIndexed { index, line ->
                val markers = Sources.markers(line)
                if (markers.size > 1) twice += "$path:${index + 1} $markers"
                if (markers.any { it != MARKER && (it.startsWith(MARKER) || MARKER.startsWith(it)) }) lookAlike += "$path:${index + 1} $markers"
            }
        }
        assertEquals("a line carries one marker", emptyList<String>(), twice)
        assertEquals("no marker that only starts with $MARKER, or that $MARKER starts with", emptyList<String>(), lookAlike)
        assertTrue(Sources.carries("x // ENH:refresh-cooldown", MARKER))
        assertTrue(Sources.carries("x // ENH:refresh-cooldown.", MARKER))
        assertFalse(Sources.carries("x // ENH:refresh-cooldown-2", MARKER))
        assertFalse(Sources.carries("x // ENH:refresh-cooldowns", MARKER))
        assertFalse(Sources.carries("x // ENH:refresh-cooldown_x", MARKER))
        assertFalse(Sources.carries("x // ENH:refresh", MARKER))
        assertFalse(Sources.carries("x // ENH:subscription-scale", MARKER))
        assertFalse(Sources.carries("x // ENH:feed-live-update", MARKER))
        assertFalse(Sources.carries("x // ENH:refresh-cooldown", SCALE))
        assertFalse(Sources.carries("x // ENH:refresh-cooldown", LIVE))
        assertFalse(Sources.carries("x // ENH:feed-live-update-2", LIVE))
    }

    /**
     * AC-1 and plan section 9 ("Not edited: … every upstream file under `res/`") and section 2
     * ("No Room entity, schema, migration or build file change"): the only resource files that
     * mention the feature are its own two; no build file, manifest or ProGuard file does.
     */
    @Test
    fun unchanged_ac1_noUpstreamResourceAndNoBuildFileMentionsTheFeature() {
        val res = Sources.main("res")
        assertTrue("app/src/main/res exists", res.isDirectory)
        val names = listOf("refresh-cooldown", "refresh_cooldown", "refreshcooldown")
        val own = setOf("values/enhancements_refresh_cooldown.xml", "layout/enhancements_refresh_cooldown_group_row.xml")
        val resources = res.walkTopDown().filter { it.isFile }.toList()
        assertTrue("the resources are where the test looks", resources.size > 100)

        fun path(file: File) = file.canonicalFile.relativeTo(res.canonicalFile).invariantSeparatorsPath
        assertEquals(
            "AC-12, plan section 8: the feature's resource files are its values file and its layout, and no translation of them",
            emptyList<String>(),
            resources.filter { file -> names.any { file.name.contains(it, ignoreCase = true) } }.map { path(it) }.filter { it !in own }
        )
        assertEquals(
            "AC-1: no upstream layout, string, key, settings screen or default mentions this feature or carries its marker",
            emptyList<String>(),
            resources.filter { it.extension == "xml" && path(it) !in own }
                .filter { file -> file.readText().let { text -> names.any { text.contains(it, ignoreCase = true) } } }.map { path(it) }
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
            "plan section 2: no build file or manifest mentions this feature",
            emptyList<String>(),
            buildFiles.filter { file -> file.readText().let { text -> names.any { text.contains(it, ignoreCase = true) } } }.map { it.path }
        )
    }

    /**
     * Plan section 2: "No Room entity, schema … change": no exported schema file names a
     * cooldown (a column or table for the values would show here).
     */
    @Test
    fun unchanged_noSchemaFileMentionsTheFeature() {
        val schemas = File(Sources.moduleFile("build.gradle").absoluteFile.parentFile, "schemas")
        if (!schemas.isDirectory) return
        val files = schemas.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertEquals(
            emptyList<String>(),
            files.filter { it.readText().contains("cooldown", ignoreCase = true) }.map { it.name }
        )
    }
}
