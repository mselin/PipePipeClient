package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.SingleSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AC-2 (finding J1, plan section 4): the watched state of a row is asked for without waiting,
 * and a row that was rebound in the meantime never shows the answer for its previous video.
 * `LatestRequest` is one per row; "a newer start() or cancel() makes an older result
 * undeliverable".
 *
 * RxJava runs for real. The row's main thread is a [TestScheduler]: nothing is delivered until
 * the test lets it run, which is how "drawing a row never waits" shows on the JVM. `onResult` is
 * passed as a Kotlin lambda, so the tests compile with any Java `Consumer` interface.
 */
class LatestRequestTest {

    private val main = TestScheduler()
    private val delivered = ArrayList<String>()
    private val rxErrors = CopyOnWriteArrayList<Throwable>()
    private val request = LatestRequest<String>()

    @Before
    fun collectUnhandledRxErrors() {
        RxJavaPlugins.setErrorHandler { rxErrors.add(it) }
    }

    @After
    fun resetRx() {
        RxJavaPlugins.reset()
    }

    private fun start(key: String, source: Single<String>) =
        request.start(key, source, main) { delivered.add(it) }

    @Test
    fun ac2_start_asksAtOnce() {
        val answer = SingleSubject.create<String>()

        start("video", answer)

        assertTrue("AC-2: start() subscribes to the request itself", answer.hasObservers())
    }

    @Test
    fun ac2_result_isDeliveredOnlyWhenTheSchedulerRuns_andOnce() {
        val answer = SingleSubject.create<String>()
        start("video", answer)

        answer.onSuccess("state")
        assertEquals("AC-2: nothing is delivered on the thread that produced the answer", emptyList<String>(), delivered)

        main.triggerActions()
        assertEquals("AC-2: the answer is delivered on the given scheduler", listOf("state"), delivered)

        main.triggerActions()
        assertEquals("AC-2: and only once", listOf("state"), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    /** `start` itself must not deliver, even if the answer is there at once (a cache inside the source, say). */
    @Test
    fun ac2_anAnswerThatIsThereAtOnce_isStillDeliveredThroughTheScheduler() {
        start("video", Single.just("state"))

        assertEquals("AC-2: start() returns before anything is delivered", emptyList<String>(), delivered)
        main.triggerActions()
        assertEquals(listOf("state"), delivered)
    }

    @Test
    fun ac2_secondStart_theFirstAnswerArrivingLaterIsNeverDelivered() {
        val first = SingleSubject.create<String>()
        val second = SingleSubject.create<String>()
        start("old video", first)
        start("new video", second)

        assertFalse("AC-2: the old request is disposed", first.hasObservers())
        first.onSuccess("old state")
        main.triggerActions()
        assertEquals("AC-2: a rebound row never gets the answer for its previous video", emptyList<String>(), delivered)

        second.onSuccess("new state")
        main.triggerActions()
        assertEquals(listOf("new state"), delivered)
    }

    /** The race that matters: the old answer is already on its way to the main thread when the row is rebound. */
    @Test
    fun ac2_secondStart_theFirstAnswerAlreadyOnItsWayIsNeverDelivered() {
        val first = SingleSubject.create<String>()
        val second = SingleSubject.create<String>()
        start("old video", first)
        first.onSuccess("old state") // completed, waiting for the scheduler

        start("new video", second)
        main.triggerActions()
        assertEquals("AC-2: the old answer is dropped although it had completed before the rebind", emptyList<String>(), delivered)

        second.onSuccess("new state")
        main.triggerActions()
        assertEquals(listOf("new state"), delivered)
    }

    @Test
    fun ac2_secondStartWithTheSameKey_onlyTheNewRequestDelivers() {
        val first = SingleSubject.create<String>()
        val second = SingleSubject.create<String>()
        start("video", first)
        first.onSuccess("first answer")
        start("video", second)
        second.onSuccess("second answer")

        main.triggerActions()

        assertEquals("AC-2: a newer start() replaces the older one, whatever the key", listOf("second answer"), delivered)
    }

    @Test
    fun ac2_cancel_anAnswerArrivingLaterIsNeverDelivered() {
        val answer = SingleSubject.create<String>()
        start("video", answer)

        request.cancel()

        assertFalse("AC-2: cancel() disposes the request", answer.hasObservers())
        answer.onSuccess("state")
        main.triggerActions()
        assertEquals("AC-2: nothing is delivered after cancel()", emptyList<String>(), delivered)
    }

    /** A live row calls only `cancel()` on rebind and starts nothing: the old answer must not land in it. */
    @Test
    fun ac2_cancel_anAnswerAlreadyOnItsWayIsNeverDelivered() {
        val answer = SingleSubject.create<String>()
        start("video", answer)
        answer.onSuccess("state") // completed, waiting for the scheduler

        request.cancel()
        main.triggerActions()

        assertEquals("AC-2: nothing is delivered after cancel()", emptyList<String>(), delivered)
    }

    @Test
    fun ac2_cancel_withoutARequestAndTwice_doesNothing() {
        request.cancel()
        request.cancel()
        val answer = SingleSubject.create<String>()
        start("video", answer)
        request.cancel()
        request.cancel()
        main.triggerActions()

        assertEquals(emptyList<String>(), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    @Test
    fun ac2_startAfterCancel_delivers() {
        start("video", SingleSubject.create())
        request.cancel()

        start("video", Single.just("state"))
        main.triggerActions()

        assertEquals(listOf("state"), delivered)
    }

    @Test
    fun ac2_afterADelivery_theNextRequestDeliversToo() {
        start("a", Single.just("state of a"))
        main.triggerActions()
        start("b", Single.just("state of b"))
        main.triggerActions()

        assertEquals(listOf("state of a", "state of b"), delivered)
    }

    /**
     * Plan section 4: "error -> no callback, no throw" (today an error crashes the bind). An
     * error without a handler would reach RxJava's global error handler, which on Android ends
     * the process; none may arrive there.
     */
    @Test
    fun ac2_error_noCallbackNoThrowNothingUnhandled() {
        val answer = SingleSubject.create<String>()
        start("video", answer)

        answer.onError(IllegalStateException("database is closed"))
        main.triggerActions()

        assertEquals("AC-2: no callback for a failed request", emptyList<String>(), delivered)
        assertEquals("AC-2: the error is handled, not left to RxJava's global handler", emptyList<Throwable>(), rxErrors)
    }

    @Test
    fun ac2_error_ofARequestThatFailsAtOnce_doesNotThrowFromStart() {
        start("video", Single.error(IllegalStateException("database is closed")))
        main.triggerActions()

        assertEquals(emptyList<String>(), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    @Test
    fun ac2_afterAnError_theNextRequestDelivers() {
        start("a", Single.error(IllegalStateException("database is closed")))
        main.triggerActions()

        start("b", Single.just("state of b"))
        main.triggerActions()

        assertEquals(listOf("state of b"), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    @Test
    fun ac2_anErrorOfASupersededRequest_isNotReportedAnywhere() {
        val first = SingleSubject.create<String>()
        start("old video", first)
        start("new video", Single.just("new state"))

        first.onError(IllegalStateException("too late"))
        main.triggerActions()

        assertEquals(listOf("new state"), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    /**
     * The shape of the real call: the request does its work on an io thread
     * (`HistoryRecordManager.loadStreamState` is `subscribeOn(Schedulers.io())`), the answer comes
     * back on the thread of `deliverOn`. The caller's thread is never the one that waits.
     */
    @Test
    fun ac2_theCallerDoesNotWait_theAnswerComesOnTheDeliveryThread() {
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<String>()
        val slow = Single.fromCallable {
            threads.add("work:" + Thread.currentThread().name)
            if (release.await(10, TimeUnit.SECONDS)) "state" else "start() did not return while the request was working"
        }.subscribeOn(Schedulers.io())

        val results = CopyOnWriteArrayList<String>()
        LatestRequest<String>().start("video", slow, Schedulers.single()) {
            results.add(it)
            threads.add("result:" + Thread.currentThread().name)
            done.countDown()
        }
        // start() has returned although the request cannot finish before this line:
        release.countDown()

        assertTrue("AC-2: the answer arrives", done.await(10, TimeUnit.SECONDS))
        assertEquals("AC-2: start() returned while the request was still working", listOf("state"), results)
        val caller = Thread.currentThread().name
        assertEquals("AC-2: one unit of work, one result", 2, threads.size)
        assertTrue("AC-2: the request runs on the io scheduler, not on the caller: $threads", threads[0].startsWith("work:RxCachedThreadScheduler"))
        assertTrue("AC-2: the result arrives on the delivery scheduler: $threads", threads[1].startsWith("result:RxSingleScheduler"))
        assertFalse(threads.any { it.endsWith(":$caller") })
    }

    // ---- at the owner's scale: one row flung through a long list ----

    /**
     * A fling rebinds the same row holder again and again before any answer is drawn. After
     * 1600 rebinds exactly one request is alive and exactly one answer is delivered, whether the
     * superseded answers had already completed (even rows) or complete afterwards (odd rows).
     */
    @Test
    fun ac2_scale_1600RebindsOfOneRow_oneLiveRequestOneDelivery() {
        val answers = (0 until SUBSCRIPTIONS).map { SingleSubject.create<String>() }
        for ((i, answer) in answers.withIndex()) {
            start("video $i", answer)
            if (i % 2 == 0) answer.onSuccess("state $i")
        }
        for ((i, answer) in answers.withIndex()) {
            if (i % 2 == 1) answer.onSuccess("state $i")
        }

        assertEquals(
            "AC-2: every superseded request was disposed; none is left running",
            0,
            answers.dropLast(1).count { it.hasObservers() }
        )
        main.triggerActions()
        assertEquals("AC-2: 1600 rebinds, one answer drawn: the last", listOf("state ${SUBSCRIPTIONS - 1}"), delivered)
        assertEquals(emptyList<Throwable>(), rxErrors)
    }

    /** Slow scrolling: each answer arrives before the next rebind. Each is drawn once, none twice. */
    @Test
    fun ac2_scale_1600BindsWithTimeToAnswer_eachDeliveredOnce() {
        for (i in 0 until SUBSCRIPTIONS) {
            request.cancel() // first statement of every bind
            start("video $i", Single.just("state $i"))
            main.triggerActions()
        }

        assertEquals("AC-2: one delivery per bind", (0 until SUBSCRIPTIONS).map { "state $it" }, delivered)
    }
}
