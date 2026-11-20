package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.QueueOverflowException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-8, plan T3: what RxJava really hands to the app's error handler, and what
 * `UndeliveredErrors.decide` answers for exactly that object. Real RxJava (the version of the
 * app's classpath) on the JVM, no scheduler, everything synchronous; a recording error handler
 * stands where `App` installs its own.
 *
 * `decide` only ever sees what RxJava hands over, and RxJava's rule is not "nobody waits":
 * an error is wrapped in `UndeliverableException` unless it is itself of one of seven "bug"
 * types. The tests named `..premise..` pin that rule on the real library (they pass before the
 * Code layer exists and fail if a library update changes it; then plan C8 has to be read
 * again). The others add the decision.
 *
 * **The two accepted limits of AC-8** (spec, answer A10) follow from that rule and are pinned
 * here as tests named `ac8_acceptedLimit..` so that nobody reads them as bugs later:
 * 1. an abandoned load that fails with a bare `NullPointerException`, `IllegalStateException`
 *    or `IllegalArgumentException` arrives unwrapped and still closes the app;
 * 2. an exception of another type thrown inside a live callback arrives wrapped, exactly like
 *    an abandoned load's error, and is logged where today it could close the app.
 */
class UndeliveredErrorsRxTest {

    private val handedOver = ArrayList<Throwable>()

    @Before
    fun recordWhatRxJavaHandsOver() {
        RxJavaPlugins.reset()
        RxJavaPlugins.setErrorHandler { handedOver.add(it) }
    }

    @After
    fun resetPlugins() {
        RxJavaPlugins.reset()
    }

    /** The one error the handler got. */
    private fun single(what: String): Throwable {
        assertEquals("$what: RxJava must have called the error handler exactly once; it got $handedOver", 1, handedOver.size)
        return handedOver[0]
    }

    private fun assertWrapped(what: String, original: Throwable): Throwable {
        val got = single(what)
        assertTrue(
            "premise of plan C8 ($what): RxJava must hand ${describe(original)} over inside an " +
                "UndeliverableException; the handler got ${describe(got)}",
            got is UndeliverableException
        )
        assertSame("premise of plan C8 ($what): the wrapper's cause must be the original error", original, got.cause)
        return got
    }

    private fun assertUnwrapped(what: String, original: Throwable): Throwable {
        val got = single(what)
        assertSame(
            "premise of plan C8 ($what): RxJava must hand ${describe(original)} over as it is, " +
                "not wrapped; the handler got ${describe(got)}",
            original,
            got
        )
        return got
    }

    private fun assertDecision(what: String, got: Throwable, off: String, on: String) {
        assertEquals("AC-8 ($what), debug setting OFF: ${describe(got)}", off, Decision.decide(got, false))
        assertEquals("AC-8 ($what), debug setting ON: ${describe(got)}", on, Decision.decide(got, true))
    }

    /** A load that nobody waits for any more: subscribed, then disposed; the emitter is kept. */
    private fun abandonedLoad(): SingleEmitter<Int> {
        var saved: SingleEmitter<Int>? = null
        val delivered = ArrayList<Any>()
        val disposable = Single.create<Int> { saved = it }.subscribe({ delivered.add(it) }, { delivered.add(it) })
        disposable.dispose()
        assertTrue(handedOver.isEmpty())
        assertTrue(delivered.isEmpty())
        return saved!!
    }

    // ------------------------------------------------------------------------------------
    // The rule of RxJavaPlugins.onError
    // ------------------------------------------------------------------------------------

    /** Premise of plan C8 (A0): an error that is not of a bug type is wrapped; its cause chain does not matter. */
    @Test
    fun ac8_premise_errorOfANonBugType_isHandedOverWrapped() {
        val error = Exception("x", NullPointerException())
        RxJavaPlugins.onError(error)
        assertWrapped("RxJavaPlugins.onError", error)
    }

    /** AC-8, plan T3, first bullet: that wrapped error is logged; with the debug setting on it closes the app as today. */
    @Test
    fun ac8_errorOfANonBugTypeWithABugCause_isLogged() {
        val error = Exception("x", NullPointerException())
        RxJavaPlugins.onError(error)
        assertDecision("RxJavaPlugins.onError(Exception(NullPointerException))", assertWrapped("RxJavaPlugins.onError", error), LOG, CRASH)
    }

    /**
     * Premise of plan C8 (A0, "checked: isBug"): the seven types RxJava never wraps. Each
     * reaches the handler as the same instance. If a library update changes this list, the
     * limits of AC-8 change with it.
     */
    @Test
    fun ac8_premise_theSevenBugTypes_areHandedOverUnwrapped() {
        val unwrapped = listOf(
            NullPointerException("npe"),
            IllegalStateException("ise"),
            IllegalArgumentException("iae"),
            CompositeException(RuntimeException("a"), RuntimeException("b")),
            OnErrorNotImplementedException(RuntimeException("x")),
            MissingBackpressureException("mbe"),
            QueueOverflowException()
        )
        for (error in unwrapped) {
            handedOver.clear()
            RxJavaPlugins.onError(error)
            assertUnwrapped("RxJavaPlugins.onError(${error.javaClass.simpleName})", error)
        }
    }

    /** AC-8 / AC-6, plan T3: none of the seven is ever LOG; each is decided as today (rows 9, 10, 12, 14). */
    @Test
    fun ac8_theSevenBugTypes_areDecidedAsToday() {
        val expected: List<Triple<Throwable, String, String>> = listOf(
            Triple(NullPointerException("npe"), CRASH, CRASH),
            Triple(IllegalStateException("ise"), CRASH, CRASH),
            Triple(IllegalArgumentException("iae"), CRASH, CRASH),
            Triple(MissingBackpressureException("mbe"), CRASH, CRASH),
            Triple(OnErrorNotImplementedException(RuntimeException("x")), CRASH, CRASH),
            Triple(CompositeException(RuntimeException("a"), RuntimeException("b")), IGNORE, REPORT),
            Triple(QueueOverflowException(), IGNORE, REPORT)
        )
        for ((error, off, on) in expected) {
            handedOver.clear()
            RxJavaPlugins.onError(error)
            assertDecision("RxJavaPlugins.onError(${error.javaClass.simpleName})", assertUnwrapped("RxJavaPlugins.onError", error), off, on)
        }
    }

    // ------------------------------------------------------------------------------------
    // Nobody waits: the case AC-8 is about
    // ------------------------------------------------------------------------------------

    /** Premise of plan C8 ("Sequence"): a load that fails after it was disposed hands its error to the handler, wrapped. */
    @Test
    fun ac8_premise_abandonedLoadFailingWithANonBugType_isHandedOverWrapped() {
        val late = Exception("late", NumberFormatException("Could not parse as a Long"))
        abandonedLoad().onError(late)
        assertWrapped("emitter.onError after dispose", late)
    }

    /**
     * AC-8, the criterion itself: the error of a load that was abandoned when its page was left
     * (not of a bug type, with a bug type as cause; the extractor's parsing exceptions) is
     * logged and does not close the app. With the debug setting on: CRASH, as today.
     */
    @Test
    fun ac8_abandonedLoadFailingWithANonBugType_isLoggedAndDoesNotCloseTheApp() {
        val late = Exception("late", NumberFormatException("Could not parse as a Long"))
        abandonedLoad().onError(late)
        assertDecision("abandoned load, Exception(NumberFormatException)", assertWrapped("emitter.onError after dispose", late), LOG, CRASH)
    }

    /**
     * AC-8 with the operator the app's loads use (plan C8: "`SingleFromCallable` finds itself
     * disposed and calls `RxJavaPlugins.onError`"): the page is left (dispose) while the
     * callable runs, then the callable throws.
     */
    @Test
    fun ac8_callableThatFailsAfterItsReceiverWasDisposed_isWrappedAndLogged() {
        val failure = Exception("Could not parse", NumberFormatException())
        val delivered = ArrayList<Any>()
        var upstream: Disposable? = null
        Single.fromCallable<Int> {
            upstream!!.dispose() // the page is left while the load runs
            throw failure
        }.doOnSubscribe { upstream = it }.subscribe({ delivered.add(it) }, { delivered.add(it) })

        assertTrue("nobody waits: nothing may be delivered to the receiver, got $delivered", delivered.isEmpty())
        assertDecision("disposed fromCallable", assertWrapped("fromCallable, disposed while running", failure), LOG, CRASH)
    }

    /** AC-8: an abandoned load that fails because of the network stays ignored, without a log line, as today. */
    @Test
    fun ac8_abandonedLoadFailingWithANetworkError_staysIgnored() {
        val late = java.io.IOException("cancelled")
        abandonedLoad().onError(late)
        assertDecision("abandoned load, IOException", assertWrapped("emitter.onError after dispose", late), IGNORE, IGNORE)
    }

    /**
     * **AC-8, accepted limit 1** (spec; answer A10 a). An abandoned load that fails with a bare
     * `NullPointerException`, `IllegalStateException` or `IllegalArgumentException` reaches the
     * handler **unwrapped**, exactly like a bug in a live callback, so it still closes the app
     * (CRASH, setting off and on). This is intended and named in the criterion; it is not a
     * defect of `decide`. A test that wants LOG here asks for something RxJava does not let the
     * handler know.
     */
    @Test
    fun ac8_acceptedLimit1_abandonedLoadFailingWithABareBugType_arrivesUnwrappedAndStillClosesTheApp() {
        for (late in listOf(NullPointerException("late"), IllegalStateException("late"), IllegalArgumentException("late"))) {
            handedOver.clear()
            abandonedLoad().onError(late)
            val got = assertUnwrapped("emitter.onError(${late.javaClass.simpleName}) after dispose", late)
            assertDecision("accepted limit 1: abandoned load, bare ${late.javaClass.simpleName}", got, CRASH, CRASH)
        }
    }

    // ------------------------------------------------------------------------------------
    // Somebody waits
    // ------------------------------------------------------------------------------------

    /**
     * AC-8 / AC-6 (answer A10 b: "an error that still has a receiver but no handler closes the
     * app as today"): a receiver without error handler gets an `OnErrorNotImplementedException`,
     * never wrapped; CRASH with the setting off and on.
     */
    @Test
    fun ac8_receiverWithoutErrorHandler_arrivesUnwrappedAndClosesTheAppAsToday() {
        val error = RuntimeException("x")
        Single.error<Int>(error).subscribe { _ -> }
        val got = single("subscribe without error handler")
        assertTrue(
            "premise of plan C8: a receiver without error handler hands over an OnErrorNotImplementedException, got ${describe(got)}",
            got is OnErrorNotImplementedException
        )
        assertSame(error, got.cause)
        assertDecision("receiver without error handler", got, CRASH, CRASH)
    }

    /**
     * **AC-8, accepted limit 2** (spec; answer A10 b; plan "Risks"). An exception of a non-bug
     * type thrown **inside the callback of a live receiver** (`onSuccess`) is wrapped by RxJava
     * exactly like an abandoned load's error. So it is logged too:
     * - `IndexOutOfBoundsException`: today dropped silently, now LOG;
     * - `RuntimeException(NullPointerException)`: today CRASH, **now LOG**. This is the case the
     *   change can hide; the page typically stays "loading". Intended and accepted; found with
     *   `adb logcat -s UndeliveredErrors:E` or by switching the debug setting on (then CRASH /
     *   REPORT as today).
     */
    @Test
    fun ac8_acceptedLimit2_liveCallbackThrowingAnotherType_arrivesWrappedAndIsLogged() {
        val silentToday = IndexOutOfBoundsException("thrown in onSuccess")
        Single.just(1).subscribe({ _ -> throw silentToday }, { _ -> })
        var got = assertWrapped("onSuccess of a live receiver throws IndexOutOfBoundsException", silentToday)
        assertEquals(TodaysHandler.Kind.DROPPED_SILENTLY, TodaysHandler.handle(got, false).kind)
        assertDecision("accepted limit 2: live callback, IndexOutOfBoundsException", got, LOG, REPORT)

        handedOver.clear()
        val crashToday = RuntimeException("thrown in onSuccess", NullPointerException())
        Single.just(1).subscribe({ _ -> throw crashToday }, { _ -> })
        got = assertWrapped("onSuccess of a live receiver throws RuntimeException(NullPointerException)", crashToday)
        assertEquals(
            "accepted limit 2: today's handler closes the app for this one",
            TodaysHandler.Kind.CRASH,
            TodaysHandler.handle(got, false).kind
        )
        assertDecision("accepted limit 2: live callback, RuntimeException(NullPointerException)", got, LOG, CRASH)
    }

    /**
     * Where accepted limit 2 ends, pinned on the real library: a stream's `onNext` that throws
     * is **not** undeliverable. RxJava cancels the stream and gives the exception to the same
     * receiver's error callback; the handler is not called and nothing changes with AC-8.
     * (With no error callback it becomes an `OnErrorNotImplementedException`: CRASH as today.)
     */
    @Test
    fun ac8_premise_liveOnNextThatThrows_goesToTheReceiversOwnErrorCallback() {
        val thrown = UnsupportedOperationException("thrown in onNext")
        val errors = ArrayList<Throwable>()
        Observable.never<Int>().startWithItem(1).subscribe({ _ -> throw thrown }, { errors.add(it) })
        assertEquals("the receiver's own error callback gets what its onNext threw", listOf<Throwable>(thrown), errors)
        assertTrue("nothing is undeliverable here; the handler got $handedOver", handedOver.isEmpty())

        Observable.never<Int>().startWithItem(1).subscribe { _ -> throw thrown }
        val got = single("onNext throws, no error callback")
        assertTrue("an OnErrorNotImplementedException, got ${describe(got)}", got is OnErrorNotImplementedException)
        assertDecision("onNext throws, no error callback", got, CRASH, CRASH)
    }

    /**
     * Accepted limit 2 for a stream: the callback that is wrapped there is `onComplete` (after
     * it nothing is left to deliver an error to). An exception of a non-bug type thrown by it
     * arrives wrapped and is logged, like `onSuccess` above.
     */
    @Test
    fun ac8_acceptedLimit2_liveOnCompleteThrowingAnotherType_arrivesWrappedAndIsLogged() {
        val thrown = UnsupportedOperationException("thrown in onComplete")
        Observable.empty<Int>().subscribe({ _ -> }, { _ -> }, { throw thrown })
        assertDecision("accepted limit 2: onComplete throws", assertWrapped("onComplete of a live receiver throws", thrown), LOG, REPORT)
    }

    /**
     * AC-8 / AC-6: a live callback that throws a bare bug type is a bug the app still reports:
     * unwrapped, CRASH with the setting off and on.
     */
    @Test
    fun ac8_liveCallbackThrowingABareBugType_arrivesUnwrappedAndClosesTheAppAsToday() {
        val bug = NullPointerException("thrown in onSuccess")
        Single.just(1).subscribe({ _ -> throw bug }, { _ -> })
        assertDecision("live callback, NullPointerException", assertUnwrapped("onSuccess throws NullPointerException", bug), CRASH, CRASH)
    }

    /**
     * AC-7's shape, to show that AC-8 does not cover it (plan C7: "AC-8's change does not cover
     * it: neither error is an UndeliverableException"): an error callback that throws hands
     * over a `CompositeException` of the original error and the thrown one, unwrapped. With an
     * `IllegalStateException` (the feed page's `requireContext()`) as second member: CRASH.
     * The two guard lines of AC-7 are what prevents it.
     */
    @Test
    fun ac7_errorCallbackThatThrows_arrivesAsUnwrappedComposite_notCoveredByAc8() {
        val original = RuntimeException("Unable to process")
        val thrown = IllegalStateException("Fragment FeedFragment{..} not attached to a context")
        Single.error<Int>(original).subscribe({ _ -> }, { _ -> throw thrown })
        val got = single("onError of a live receiver throws")
        assertTrue("premise of plan C7: a CompositeException, got ${describe(got)}", got is CompositeException)
        assertEquals(listOf(original, thrown), (got as CompositeException).exceptions)
        assertDecision("plan C7, error branch", got, CRASH, CRASH)
    }
}
