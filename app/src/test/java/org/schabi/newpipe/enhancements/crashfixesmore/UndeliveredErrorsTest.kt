package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.QueueOverflowException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

/**
 * AC-8, plan C8: `UndeliveredErrors.decide(throwable, reportDisposed)`, the decision the four
 * new lines of `App.java` ask before the upstream error handler runs. Plain JVM, no Android.
 *
 * - **T1** (`ac8_row..`): the plan's 22 rows, each with the debug setting off and on. Rows 3 to
 *   7 are the one change of AC-8; every other row is "as today".
 * - **The two accepted limits** of AC-8 (`ac8_acceptedLimit..`), as decisions; what RxJava
 *   really hands over in those cases is pinned in [UndeliveredErrorsRxTest].
 * - **T2** (`ac8_matrix..`): today's handler, written out a second time in
 *   `UndeliveredErrorsApi.kt`, compared with `decide` over a generated matrix. The two may
 *   differ only by `LOG`, and `LOG` only for an `UndeliverableException` with the setting off.
 *
 * `reportDisposed` is the debug setting "report out-of-lifecycle errors"
 * (`allow_disposed_exceptions_key`); it is always false in a release build.
 *
 * `decide` is reached by name (see `Decision`), so until the Code layer writes the file every
 * test here fails with "AC-8, plan C8: not implemented".
 */
class UndeliveredErrorsTest {

    private fun u(cause: Throwable) = UndeliverableException(cause)

    /** One row of the plan's table: every argument of it decides [off] / [on]. */
    private fun row(number: Int, off: String, on: String, note: String, vararg arguments: Throwable) {
        assertTrue(arguments.isNotEmpty())
        for (argument in arguments) {
            assertEquals(
                "AC-8, plan C8 row $number, debug setting OFF: ${describe(argument)} ($note)",
                off,
                Decision.decide(argument, false)
            )
            assertEquals(
                "AC-8, plan C8 row $number, debug setting ON: ${describe(argument)} ($note)",
                on,
                Decision.decide(argument, true)
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // The contract App.java compiles against
    // ------------------------------------------------------------------------------------

    /** AC-8, plan C8: the four actions, by name; `App.java` compares with `Action.LOG`. */
    @Test
    fun ac8_actions_areTheFourOfThePlan() {
        assertEquals(
            "AC-8, plan C8: UndeliveredErrors.Action",
            setOf(IGNORE, LOG, REPORT, CRASH),
            Decision.actionNames().toSet()
        )
        assertEquals(4, Decision.actionNames().size)
    }

    /**
     * AC-8 "is written to the log", plan C8 and "Risks: how it is found": the log entry has the
     * fixed tag and text the device script looks for (`adb logcat -s UndeliveredErrors:E`).
     */
    @Test
    fun ac8_logTagAndMessage_areThePlans() {
        assertEquals("AC-8, plan C8: LOG_TAG", "UndeliveredErrors", Decision.logTag)
        assertEquals("AC-8, plan C8: LOG_MESSAGE", "Undelivered error, logged and not rethrown", Decision.logMessage)
    }

    // ------------------------------------------------------------------------------------
    // T1: rows 3 to 7, the one change
    // ------------------------------------------------------------------------------------

    /**
     * AC-8, rows 3 to 6: an undeliverable error that is not of a bug type itself but has one in
     * its cause chain (the extractor's `ParsingException(.., NumberFormatException)`) is logged
     * and no longer closes the app. Today: CRASH. With the debug setting on: CRASH, as today.
     */
    @Test
    fun ac8_rows3to6_undeliverableWithABugTypeAsCause_isLoggedInsteadOfClosingTheApp() {
        val note = "the one change of AC-8; today CRASH"
        row(3, LOG, CRASH, note, u(Exception("parse", NullPointerException())))
        row(4, LOG, CRASH, note, u(Exception("parse", NumberFormatException())))
        row(5, LOG, CRASH, note, u(Exception("parse", IllegalStateException())))
        row(6, LOG, CRASH, note, u(Exception(Exception(Exception(NullPointerException())))))
    }

    /**
     * AC-8, row 7: an undeliverable error with neither a network nor a bug type in its chain is
     * dropped without a trace today; it is logged now. With the debug setting on: REPORT, as
     * today.
     */
    @Test
    fun ac8_row7_undeliverableOfAnyOtherType_isLoggedInsteadOfDroppedSilently() {
        row(
            7, LOG, REPORT, "today dropped silently",
            u(RuntimeException("plain")),
            u(IndexOutOfBoundsException())
        )
    }

    // ------------------------------------------------------------------------------------
    // T1: network and interruption stay ignored
    // ------------------------------------------------------------------------------------

    /**
     * AC-8 "unless it was caused by the network or an interruption, which stay ignored as
     * today", rows 1, 2 and 8: not even a log line (there is one such error for nearly every
     * page left while loading), also when the chain holds a bug type as well, and also with
     * the debug setting on.
     */
    @Test
    fun ac8_rows1_2_8_undeliverableCausedByNetworkOrInterruption_staysIgnoredWithoutLog() {
        val note = "network or interruption: ignored as today"
        row(
            1, IGNORE, IGNORE, note,
            u(IOException()), u(SocketException()), u(InterruptedIOException()), u(InterruptedException())
        )
        row(2, IGNORE, IGNORE, note, u(RuntimeException(InterruptedException())))
        row(
            8, IGNORE, IGNORE, "$note, even with a bug type in the same chain",
            u(Exception(IOException(NullPointerException()))),
            u(Exception(NullPointerException().initCause(IOException())))
        )
    }

    /** AC-8 / AC-6, rows 11 and 13: errors that are not undeliverable and network-caused: ignored, as today. */
    @Test
    fun ac8_rows11_13_notUndeliverableCausedByNetworkOrInterruption_ignoredAsToday() {
        row(11, IGNORE, IGNORE, "as today", OnErrorNotImplementedException(IOException()))
        row(13, IGNORE, IGNORE, "as today", IOException(), RuntimeException(InterruptedException()))
    }

    // ------------------------------------------------------------------------------------
    // T1: what is not undeliverable is decided as today
    // ------------------------------------------------------------------------------------

    /**
     * AC-8 / AC-6, row 10 (answer A10 b: "an error that still has a receiver but no handler
     * closes the app as today"): `OnErrorNotImplementedException` is never wrapped and always
     * CRASH, whatever the debug setting.
     */
    @Test
    fun ac8_row10_receiverWithoutErrorHandler_closesTheAppAsToday() {
        row(
            10, CRASH, CRASH, "as today",
            OnErrorNotImplementedException(RuntimeException()),
            OnErrorNotImplementedException(NullPointerException())
        )
    }

    /** AC-8 / AC-6, row 12: not undeliverable, no bug type, no network: nothing with the setting off, REPORT with it on; never LOG. */
    @Test
    fun ac8_row12_notUndeliverableOfAnotherType_asToday() {
        row(12, IGNORE, REPORT, "as today", QueueOverflowException(), RuntimeException("plain"))
    }

    /**
     * AC-8 / AC-6, rows 14 to 18: a `CompositeException` that is not wrapped is decided by its
     * first member that is network-caused or of a bug type, as today. Row 18 is the shape of
     * the feed page's error branch (plan C7): AC-8 does not cover it, AC-7 does.
     */
    @Test
    fun ac8_rows14to18_notUndeliverableComposite_firstDecidingMemberAsToday() {
        row(14, IGNORE, REPORT, "as today", CompositeException(RuntimeException(), RuntimeException()))
        row(15, CRASH, CRASH, "as today", CompositeException(NullPointerException(), IOException()))
        row(16, IGNORE, IGNORE, "as today", CompositeException(IOException(), NullPointerException()))
        row(17, IGNORE, IGNORE, "as today", CompositeException(RuntimeException(), IOException()))
        row(
            18, CRASH, CRASH, "as today; the shape of plan C7's error branch, which AC-8 does not cover",
            CompositeException(RuntimeException(), IllegalStateException())
        )
    }

    /**
     * AC-8 / AC-6, row 19: only the argument itself counts as "undeliverable". A wrapper further
     * inside is just an `IllegalStateException` in the chain: CRASH, as today.
     */
    @Test
    fun ac8_row19_wrapperInsideAnotherException_isNotUndeliverable() {
        row(19, CRASH, CRASH, "as today", RuntimeException(u(RuntimeException())))
    }

    // ------------------------------------------------------------------------------------
    // T1: shapes RxJava never builds, decided by the same rule
    // ------------------------------------------------------------------------------------

    /**
     * AC-8, rows 20 to 22, built by hand (RxJava never wraps a bug type or a composite): the
     * wrapper decides, whatever is inside, and the members of a wrapped composite are looked
     * at as today's handler looks at them.
     */
    @Test
    fun ac8_rows20to22_handBuiltWrappers_followTheSameRule() {
        row(
            20, LOG, CRASH, "built by hand",
            u(NullPointerException()),
            u(u(RuntimeException())),
            u(CompositeException(RuntimeException(), NullPointerException()))
        )
        row(21, IGNORE, IGNORE, "built by hand", u(CompositeException(IOException(), NullPointerException())))
        row(22, LOG, REPORT, "built by hand", u(CompositeException(RuntimeException(), RuntimeException())))
    }

    // ------------------------------------------------------------------------------------
    // The two accepted limits of AC-8 (spec, answer A10). These are not bugs.
    // ------------------------------------------------------------------------------------

    /**
     * AC-8, accepted limit 1 (spec: "an abandoned load that fails with a bare
     * `NullPointerException`, `IllegalStateException` or `IllegalArgumentException` still
     * closes the app"), row 9. RxJava does not wrap these, so the handler cannot know that
     * nobody waits: CRASH with the setting off and on, exactly as today. **Intended**: if this
     * test fails because one of them became LOG, the spec's limit was crossed, not fixed.
     */
    @Test
    fun ac8_acceptedLimit1_row9_bareBugTypeStillClosesTheApp() {
        row(
            9, CRASH, CRASH, "accepted limit 1 of AC-8: not wrapped by RxJava, closes the app as today",
            NullPointerException(),
            IllegalStateException(),
            IllegalArgumentException(),
            MissingBackpressureException()
        )
    }

    /**
     * AC-8, accepted limit 2 (spec: "an exception of another type thrown inside a live callback
     * is also logged where today it could close the app"). Such an exception reaches the
     * handler in the same wrapper as an abandoned load's error ([UndeliveredErrorsRxTest] shows
     * that RxJava does this), so `decide` cannot tell them apart: LOG. **Intended.** The
     * arguments here are what RxJava hands over when a callback throws
     * `RuntimeException(NullPointerException)` (today CRASH) and `IndexOutOfBoundsException`
     * (today silent).
     */
    @Test
    fun ac8_acceptedLimit2_exceptionOfAnotherTypeFromALiveCallback_isLoggedToo() {
        val thrownByCallback = RuntimeException("thrown inside onSuccess", NullPointerException())
        assertEquals(
            "AC-8, accepted limit 2: today's handler closes the app here",
            TodaysHandler.Kind.CRASH,
            TodaysHandler.handle(u(thrownByCallback), false).kind
        )
        assertEquals("AC-8, accepted limit 2: logged, no longer a crash", LOG, Decision.decide(u(thrownByCallback), false))
        assertEquals("AC-8: with the debug setting on, as today", CRASH, Decision.decide(u(thrownByCallback), true))
        assertEquals(LOG, Decision.decide(u(IndexOutOfBoundsException()), false))
    }

    // ------------------------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------------------------

    /**
     * AC-8, plan C8: `decide` runs inside the error handler for every error, so it must not
     * throw for a wrapper without cause (today's handler does not either): nothing in the
     * chain, so LOG / REPORT like row 7.
     */
    @Test
    fun ac8_wrapperWithoutCause_doesNotThrow() {
        val empty = UndeliverableException(null)
        assertEquals(null, empty.cause)
        assertEquals("AC-8, plan C8: a wrapper without cause, setting OFF", LOG, Decision.decide(empty, false))
        assertEquals("AC-8, plan C8: a wrapper without cause, setting ON", REPORT, Decision.decide(empty, true))
    }

    // ------------------------------------------------------------------------------------
    // T2: decide against today's handler, over the generated matrix
    // ------------------------------------------------------------------------------------

    private class Case(val shape: ErrorShape, val reportDisposed: Boolean) {
        val throwable: Throwable = shape.make()
        val today: TodaysHandler.Outcome = TodaysHandler.handle(throwable, reportDisposed)
        val label: String get() = "${shape.name}, setting ${onOff(reportDisposed)}"
    }

    private fun cases(): List<Case> = ErrorShapes.all.flatMap { listOf(Case(it, false), Case(it, true)) }

    private fun failures(what: String, lines: List<String>, of: Int) {
        if (lines.isNotEmpty()) {
            throw AssertionError(
                "$what: ${lines.size} of $of cases differ. First ${minOf(lines.size, 25)}:\n" +
                    lines.take(25).joinToString("\n")
            )
        }
    }

    /**
     * The restated handler itself, on the rows of the plan whose "today" column is given, and
     * the matrix: that it is big enough and reaches every outcome. Passes before Code exists;
     * if it fails, the comparison below proves nothing.
     */
    @Test
    fun ac8_matrix_restatedHandlerAndMatrixAreWhatThePlanDescribes() {
        fun today(throwable: Throwable, on: Boolean) = TodaysHandler.asAction(TodaysHandler.handle(throwable, on).kind)
        assertEquals("row 3 today", CRASH, today(u(Exception("parse", NullPointerException())), false))
        assertEquals("row 4 today", CRASH, today(u(Exception("parse", NumberFormatException())), false))
        assertEquals("row 7 today: silent", IGNORE, today(u(RuntimeException("plain")), false))
        assertEquals("row 7 today, setting on", REPORT, today(u(RuntimeException("plain")), true))
        assertEquals("row 1 today", IGNORE, today(u(IOException()), true))
        assertEquals("row 9 today", CRASH, today(NullPointerException(), false))
        assertEquals("row 12 today", IGNORE, today(QueueOverflowException(), false))
        assertEquals("row 16 today", IGNORE, today(CompositeException(IOException(), NullPointerException()), true))
        assertEquals("row 18 today", CRASH, today(CompositeException(RuntimeException(), IllegalStateException()), false))
        val member = NullPointerException()
        val composite = CompositeException(RuntimeException(), member)
        assertTrue("step 4 hands over the member, not the composite", TodaysHandler.handle(composite, false).handedOver === member)
        assertTrue("step 5 hands over the unwrapped error", TodaysHandler.handle(u(CompositeException(RuntimeException(), Exception())), true).handedOver is CompositeException)

        val all = cases()
        assertTrue("the matrix should hold some thousand cases, it has ${all.size}", all.size > 2000)
        for (kind in TodaysHandler.Kind.values()) {
            for (wrapped in listOf(true, false)) {
                assertTrue(
                    "the matrix must reach today's outcome $kind for wrapped = $wrapped",
                    all.any { it.today.kind == kind && (it.throwable is UndeliverableException) == wrapped }
                )
            }
        }
    }

    /**
     * AC-8 and AC-6, plan T2: over the whole matrix, `decide` equals today's handler, except
     * that it answers `LOG` exactly where the argument is an `UndeliverableException`, the
     * debug setting is off and today's handler did not return for a network or interruption
     * cause.
     */
    @Test
    fun ac8_matrix_decideEqualsTodaysHandlerExceptForLog() {
        val all = cases()
        val wrong = all.mapNotNull { case ->
            val expected = TodaysHandler.expectedAfterTheChange(case.throwable, case.reportDisposed)
            val decided = Decision.decide(case.throwable, case.reportDisposed)
            if (decided == expected) {
                null
            } else {
                "${case.label}: today ${case.today.kind}, expected $expected, decide says $decided"
            }
        }
        failures("AC-8, plan T2: decide against today's handler", wrong, all.size)
    }

    /**
     * AC-6 for AC-8 ("with the debug setting switched on, today's behaviour is kept"): with the
     * setting on, `decide` never answers LOG and equals today's handler for every case.
     */
    @Test
    fun ac8_matrix_debugSettingOn_isExactlyToday() {
        val on = cases().filter { it.reportDisposed }
        val wrong = on.mapNotNull { case ->
            val decided = Decision.decide(case.throwable, true)
            if (decided == TodaysHandler.asAction(case.today.kind)) null else "${case.label}: today ${case.today.kind}, decide says $decided"
        }
        failures("AC-8 / AC-6: with the debug setting on nothing may change", wrong, on.size)
    }

    /**
     * The safety argument of the edit in `App.java` (plan C8, "only LOG is acted on"): `LOG` is
     * never answered for an error that is not an `UndeliverableException`, so the four lines
     * cannot swallow anything else; for those errors `decide` equals today's handler.
     */
    @Test
    fun ac8_matrix_notUndeliverable_isNeverLoggedAndExactlyToday() {
        val plain = cases().filter { it.throwable !is UndeliverableException }
        val wrong = plain.mapNotNull { case ->
            val decided = Decision.decide(case.throwable, case.reportDisposed)
            if (decided == TodaysHandler.asAction(case.today.kind)) null else "${case.label}: today ${case.today.kind}, decide says $decided"
        }
        failures("AC-8 / AC-6: an error that is not undeliverable is decided as today, never LOG", wrong, plain.size)
    }

    /**
     * AC-8, the change itself over the matrix: with the setting off, every undeliverable error
     * that today closes the app (CRASH) or is dropped silently is LOG, and every undeliverable
     * error caused by the network or an interruption stays IGNORE.
     */
    @Test
    fun ac8_matrix_undeliverable_settingOff_isLoggedUnlessNetworkOrInterruption() {
        val wrapped = cases().filter { it.throwable is UndeliverableException && !it.reportDisposed }
        assertTrue(wrapped.count { it.today.kind == TodaysHandler.Kind.CRASH } > 100)
        assertTrue(wrapped.count { it.today.kind == TodaysHandler.Kind.DROPPED_SILENTLY } > 100)
        assertTrue(wrapped.count { it.today.kind == TodaysHandler.Kind.IGNORED_FOR_IO } > 100)
        val wrong = wrapped.mapNotNull { case ->
            val expected = if (case.today.kind == TodaysHandler.Kind.IGNORED_FOR_IO) IGNORE else LOG
            val decided = Decision.decide(case.throwable, false)
            if (decided == expected) null else "${case.label}: today ${case.today.kind}, expected $expected, decide says $decided"
        }
        failures("AC-8: undeliverable errors with the debug setting off", wrong, wrapped.size)
    }
}
