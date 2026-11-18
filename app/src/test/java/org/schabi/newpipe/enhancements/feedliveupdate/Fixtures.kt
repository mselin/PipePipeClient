package org.schabi.newpipe.enhancements.feedliveupdate

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.fail
import org.schabi.newpipe.enhancements.subscriptionscale.OnScreenGate
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.SuccessResultEvent
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The owner's data: about 1600 subscriptions, stored in batches of 20 (`FeedLoadManager.kt`, `buffer(20)`). */
internal const val SUBSCRIPTIONS = 1600
internal const val BATCH = 20
internal const val BATCHES = SUBSCRIPTIONS / BATCH

internal const val ALL = FeedRefreshLog.ALL
internal const val NOTIFICATIONS = FeedRefreshLog.NOTIFICATIONS

/** Two channel groups without a common channel, as in the plan's device script. */
internal const val GROUP_A = 7L
internal const val GROUP_B = 9L

/** Stands for `R.string.feed_processing_message`; any positive number is "a message". */
internal const val PROCESSING = 4711

/** Stands for `StreamWithState`: a row of the 500-row query. Equal rows of two queries are different instances. */
internal data class Row(val id: Long, val group: Long = GROUP_A)

/** Stands for `StreamItem`: no `equals`, made from one row. */
internal class RowItem(val row: Row)

/**
 * A stand-in for `FeedEventManager` (`FeedEventManager.kt:9-27`), which is one object for the
 * whole app and cannot be used by tests that run side by side. Same behaviour, line for line:
 * a subscriber gets `IdleEvent` and then the latest event, and [reset] posts `IdleEvent` while
 * [events] filters it out, so a page that is subscribed keeps the result event as its latest.
 */
internal class FakeEvents {
    private val processor = BehaviorProcessor.create<FeedEventManager.Event>()
    private val ignoreUpstream = AtomicBoolean()
    private val eventsFlowable = processor.startWithItem(IdleEvent)

    /** How often a page consumed a result (`FeedViewModel.kt:81-83`). */
    var resets = 0
        private set

    fun post(event: FeedEventManager.Event) = processor.onNext(event)

    fun events(): Flowable<FeedEventManager.Event> = eventsFlowable.filter { !ignoreUpstream.get() }

    fun reset() {
        resets++
        ignoreUpstream.set(true)
        post(IdleEvent)
        ignoreUpstream.set(false)
    }
}

/**
 * The loader and the database, as far as a feed page sees them: the events it posts
 * (`FeedLoadManager.kt:263-305`), the report to the [FeedRefreshLog] the plan adds (section 8:
 * `started` on subscription, `batchStored` after each transaction, `ended` when it terminates),
 * the rows the 500-row query returns, and the two small Room queries that re-emit after every
 * batch (plan section 1).
 */
internal class FakeLoader(val log: FeedRefreshLog, val events: FakeEvents) {
    /** The feed table, newest first. A page's query filters it by group. */
    var stored: List<Row> = emptyList()

    /** Stands for `notLoadedCount` and `oldestSubscriptionUpdate`: emits at once and after every batch. */
    val smallQueries: BehaviorProcessor<Long> = BehaviorProcessor.createDefault(0L)

    private var nextId = 1L
    private var writes = 0L

    fun rowsOf(group: Long): List<Row> = if (group == ALL) stored else stored.filter { it.group == group }

    /** New videos of [group], as a batch brings them; not yet stored. */
    fun newRows(count: Int, group: Long = GROUP_A): List<Row> = (1..count).map { Row(nextId++, group) }

    fun start(groupId: Long) = log.started(groupId)

    fun progress(current: Int, max: Int) = events.post(ProgressEvent(current, max))

    /** One transaction of `DatabaseConsumer`, then the marked line after it, then Room's re-emission. */
    fun storeBatch(rows: List<Row> = emptyList(), roomEmits: Boolean = true) {
        stored = rows + stored
        log.batchStored()
        if (roomEmits) smallQueries.onNext(++writes)
    }

    /** `postProcessFeed()`: "Processing feed…" twice, then the result; the chain then terminates. */
    fun succeed(errors: List<Throwable> = emptyList()) {
        events.post(ProgressEvent(PROCESSING))
        events.post(ProgressEvent(PROCESSING))
        events.post(SuccessResultEvent(errors))
        log.ended()
    }

    /** `FeedLoadService.handleError`: the chain has terminated, then the error event is posted. */
    fun fail(error: Throwable) {
        log.ended()
        events.post(ErrorResultEvent(error))
    }
}

/** What a page was told to show (`FeedState`, with the field plan 5.1 adds to the progress state). */
internal sealed class Shown {
    data class Loaded(val items: List<RowItem>, val errors: List<Throwable>) : Shown()
    data class Progress(val current: Int, val max: Int, val message: Int, val items: List<RowItem>?) : Shown()
    data class Error(val error: Throwable) : Shown()
}

/**
 * One feed page: the stream of `FeedViewModel.kt:44-84` as plan section 8 leaves it, and the
 * part of `FeedFragment` that decides whether the list is touched (plan 6.2).
 *
 * ```
 * combineLatest(events, showPlayed, small queries)
 *     .let { live.wake(it) }                      // ENH:feed-live-update
 *     .let { onScreenGate.gate(it) }              // ENH:subscription-scale, the real class
 *     .throttleLatest(120 ms)
 *     .filter { live.next(it.t1, it.t2) }         // ENH:feed-live-update
 *     .map { if (live.rebuild) query; live.shownAs(event), live.rows(streamItems) }
 *     .subscribe { state; reset() after a result }
 * ```
 *
 * Not modelled: the threads (everything runs on the test thread, in the order of the chain;
 * the one case where the thread hop matters is `FeedLiveUpdateTest`'s "risk 6" test), Room,
 * the views. The pace and the page's throttle both run on [scheduler].
 */
internal class Page(
    val groupId: Long,
    private val loader: FakeLoader,
    private val scheduler: TestScheduler,
    paceScheduler: Scheduler = scheduler,
    showPlayed: Boolean = true,
    /** True: the page is on screen before its stream starts, so opening it is one load, not two. */
    openOnScreen: Boolean = false
) {
    val gate = OnScreenGate().also { if (openOnScreen) it.setOnScreen(true) }
    val live = FeedLiveUpdate<Row, RowItem>(groupId, loader.log, FeedLiveUpdate.PACE_MILLIS, paceScheduler)
    private val showPlayedSwitch = BehaviorProcessor.createDefault(showPlayed)

    /** Every state posted, in order. */
    val states = mutableListOf<Shown>()

    /** How often the 500-row query ran ("list builds"). */
    var listBuilds = 0
        private set

    /** Virtual time of each list build. */
    val buildTimes = mutableListOf<Long>()

    /** How many items (`StreamItem`s) were created. */
    var itemsCreated = 0
        private set

    /** How often the adapter was given a list (`handleLoadedState`), and the rows it shows now. */
    var listUpdates = 0
        private set
    var list: List<Row> = emptyList()
        private set
    private var shownItems: List<RowItem> = emptyList()

    /** True while the page shows the full-screen progress display of today. */
    var fullScreenProgress = false
        private set

    private data class Combined(val t1: FeedEventManager.Event, val t2: Boolean, val t3: Long)
    private data class Data(val t1: FeedEventManager.Event, val t2: List<Row>)

    private fun item(row: Row): RowItem {
        itemsCreated++
        return RowItem(row)
    }

    private val disposable: Disposable = Flowable
        .combineLatest(loader.events.events(), showPlayedSwitch.distinctUntilChanged(), loader.smallQueries) { e, s, n -> Combined(e, s, n) }
        .let { live.wake(it) }
        .let { gate.gate(it) }
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS, scheduler)
        .filter { live.next(it.t1, it.t2) }
        .map { (event, _, _) ->
            val streamItems = if (live.rebuild) {
                listBuilds++
                buildTimes += scheduler.now(TimeUnit.MILLISECONDS)
                loader.rowsOf(groupId).map { it.copy() } // a query returns new objects
            } else {
                arrayListOf()
            }
            Data(live.shownAs(event), live.rows(streamItems))
        }
        .subscribe { (event, listFromDB) ->
            val state = when (event) {
                is IdleEvent -> Shown.Loaded(listFromDB.map { item(it) }, emptyList())
                is ProgressEvent -> Shown.Progress(event.currentProgress, event.maxProgress, event.progressMessage, live.items(listFromDB) { item(it) })
                is SuccessResultEvent -> Shown.Loaded(listFromDB.map { item(it) }, event.itemsErrors)
                is ErrorResultEvent -> Shown.Error(event.error)
            }
            states += state
            if (event is ErrorResultEvent || event is SuccessResultEvent) loader.events.reset()
            handleResult(state)
        }

    /** `FeedFragment.handleResult` as plan 6.1 and 6.2 leave it, reduced to "is the list touched". */
    private fun handleResult(state: Shown) {
        when (state) {
            is Shown.Loaded -> show(state.items)
            is Shown.Progress -> {
                val items = state.items
                if (items == null) {
                    fullScreenProgress = true
                } else if (FeedListRules.sameRows(shownItems, items) { it.row }) {
                    fullScreenProgress = false
                } else {
                    show(items)
                }
            }
            is Shown.Error -> Unit
        }
    }

    private fun show(items: List<RowItem>) {
        listUpdates++
        shownItems = items
        list = items.map { it.row }
        fullScreenProgress = false
    }

    fun onResume() = gate.setOnScreen(true)
    fun onPause() = gate.setOnScreen(false)
    fun togglePlayedItems(show: Boolean) = showPlayedSwitch.onNext(show)
    fun close() = disposable.dispose()

    val last: Shown get() = states.last()
    val ids: List<Long> get() = list.map { it.id }
}

/** A [TestScheduler] that counts its workers: `throttleLatest` holds one for as long as it is subscribed. */
internal class CountingScheduler(private val delegate: TestScheduler) : Scheduler() {
    var created = 0
        private set
    var alive = 0
        private set

    override fun createWorker(): Worker {
        val worker = delegate.createWorker()
        created++
        alive++
        return object : Worker() {
            private val disposed = AtomicBoolean()
            override fun schedule(run: Runnable, delay: Long, unit: TimeUnit): Disposable = worker.schedule(run, delay, unit)
            override fun now(unit: TimeUnit): Long = worker.now(unit)
            override fun isDisposed(): Boolean = worker.isDisposed
            override fun dispose() {
                if (disposed.compareAndSet(false, true)) alive--
                worker.dispose()
            }
        }
    }

    override fun now(unit: TimeUnit): Long = delegate.now(unit)
}

internal fun TestScheduler.advanceMillis(millis: Long) = advanceTimeBy(millis, TimeUnit.MILLISECONDS)
internal fun TestScheduler.advanceToMillis(millis: Long) = advanceTimeTo(millis, TimeUnit.MILLISECONDS)

/** Files of the module, read as text by the wiring tests. */
internal object Sources {
    const val MARKER = "ENH:feed-live-update"
    const val PARENT_MARKER = "ENH:subscription-scale"

    /** `<path>` when run by Gradle (working directory `app/`), else `app/<path>`. */
    fun moduleFile(path: String): File {
        val inModule = File(path)
        return if (inModule.exists() || File("src/main").isDirectory) inModule else File("app/$path")
    }

    fun main(path: String): File = moduleFile("src/main/$path")

    fun lines(ac: String, path: String): List<String> {
        val file = main(path)
        if (!file.isFile) fail("$ac: app/src/main/$path does not exist")
        return file.readLines()
    }

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** How often [snippet] occurs in [text], whitespace ignored in both. */
    fun count(text: String, snippet: String): Int {
        val wanted = squeeze(snippet)
        return squeeze(text).windowed(wanted.length).count { it == wanted }
    }

    /**
     * Whether [line] carries exactly the marker [marker]: `ENH:feed-live-update-2` or
     * `ENH:subscription-scaled` is another feature's line, not this one's.
     */
    fun carries(line: String, marker: String): Boolean = Regex(Regex.escape(marker) + "(?![\\w-])").containsMatchIn(line)

    /** Every `ENH:<name>` on [line]. */
    fun markers(line: String): List<String> = Regex("ENH:[\\w-]+").findAll(line).map { it.value }.toList()
}
