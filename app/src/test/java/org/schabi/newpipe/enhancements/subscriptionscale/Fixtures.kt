package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import org.junit.Assert.fail
import java.io.File

/** The owner's data: about 1600 subscriptions, refreshed in batches of 20, and dozens of groups. */
internal const val SUBSCRIPTIONS = 1600
internal const val BATCH = 20
internal const val GROUPS = 40

/**
 * A hand-written stand-in for a Room `Flowable` (plan 5.1: "a Room Flowable emits its current
 * value on subscription"): every subscriber gets the current value at once and every value
 * written afterwards. Each value handed to a subscriber is one "query", which is what Room runs
 * per observer per write.
 */
internal class FakeTable<T : Any>(initial: T? = null) {
    private val values: BehaviorProcessor<T> =
        if (initial == null) BehaviorProcessor.create() else BehaviorProcessor.createDefault(initial)

    /** How often [flowable] was subscribed to, ever. */
    var subscriptions = 0
        private set

    /** How many subscriptions are alive now (Room keeps a table observer for each). */
    var observers = 0
        private set

    /** How many values were handed to subscribers, ever. */
    var queries = 0
        private set

    fun flowable(): Flowable<T> = values
        .doOnSubscribe { subscriptions++; observers++ }
        .doFinally { observers-- }
        .doOnNext { queries++ }

    fun write(value: T) = values.onNext(value)

    fun fail(error: Throwable) = values.onError(error)
}

internal fun row(
    uid: Long,
    name: String? = "Channel %04d".format(uid),
    count: Long? = 1000 + uid,
    avatar: String? = "https://img.invalid/$uid.jpg",
    url: String? = "https://example.invalid/channel/$uid",
    serviceId: Int = 0
) = SubscriptionRow(uid, serviceId, url, name, avatar, count)

/** [n] rows with uid 1..n, already in name order. Every call makes new, equal objects, as a query does. */
internal fun rows(n: Int): List<SubscriptionRow> = (1..n).map { row(it.toLong()) }

/** Files of `app/src/main`, read as text by the wiring tests. */
internal object MainFiles {
    /** `src/main/<path>` when run by Gradle (working directory `app/`), else `app/src/main/<path>`. */
    fun file(path: String): File {
        val inModule = File("src/main/$path")
        return if (inModule.exists()) inModule else File("app/src/main/$path")
    }

    fun lines(ac: String, path: String): List<String> {
        val file = file(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist")
        }
        return file.readLines()
    }

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** How often [snippet] occurs in [text], whitespace ignored in both. */
    fun count(text: String, snippet: String): Int {
        val wanted = squeeze(snippet)
        return squeeze(text).windowed(wanted.length).count { it == wanted }
    }
}
