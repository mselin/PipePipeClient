package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.QueueOverflowException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.SocketException
import java.net.SocketTimeoutException

/*
 * What the AC-8 tests of this package share. No test of its own.
 *
 * 1. `Decision`: the planned `UndeliveredErrors` (plan C8), reached by name. The class does not
 *    exist until the Code layer writes it; reached by name, its absence is a failing test with
 *    the message "not implemented" instead of a test source set that does not compile (which
 *    would also stop the wiring test and the parent package's tests from running).
 * 2. `TodaysHandler`: the upstream error handler of `App.java` (plan C8, "Today's handler, read
 *    line by line", steps 2 to 5) written out a second time, independently of the code under
 *    test, and `expectedAfterTheChange`, the one rule AC-8 adds to it.
 * 3. `ErrorShapes`: the generated matrix of error shapes both are compared over.
 */

/** The four answers of `UndeliveredErrors.decide`, by name (plan C8). */
internal const val IGNORE = "IGNORE"
internal const val LOG = "LOG"
internal const val REPORT = "REPORT"
internal const val CRASH = "CRASH"

/** `UndeliveredErrors` of plan C8, by reflection. Every member fails with "not implemented" until it exists. */
internal object Decision {
    const val CLASS_NAME = "org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrors"

    private fun notImplemented(what: String, cause: Throwable): Nothing = throw AssertionError(
        "AC-8, plan C8: not implemented: $what " +
            "(app/src/main/java/org/schabi/newpipe/enhancements/crashfixesmore/UndeliveredErrors.kt)",
        cause
    )

    private fun owner(): Class<*> = try {
        Class.forName(CLASS_NAME)
    } catch (e: ClassNotFoundException) {
        notImplemented("the object $CLASS_NAME does not exist", e)
    }

    /** `UndeliveredErrors.Action`, the nested enum. */
    fun actionNames(): List<String> = try {
        Class.forName("$CLASS_NAME\$Action").enumConstants!!.map { (it as Enum<*>).name }
    } catch (e: ClassNotFoundException) {
        notImplemented("the enum $CLASS_NAME.Action does not exist", e)
    }

    /**
     * `UndeliveredErrors.decide(throwable, reportDisposed)` as `App.java` calls it: a static
     * method (`@JvmStatic`). Returns the name of the action.
     */
    fun decide(throwable: Throwable, reportDisposed: Boolean): String {
        val method = try {
            owner().getMethod("decide", Throwable::class.java, Boolean::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            notImplemented("$CLASS_NAME.decide(Throwable, boolean) does not exist", e)
        }
        if (!Modifier.isStatic(method.modifiers)) {
            throw AssertionError(
                "AC-8, plan C8: UndeliveredErrors.decide must be @JvmStatic; the four lines in App.java call it as a static method"
            )
        }
        val action = try {
            method.invoke(null, throwable, reportDisposed)
        } catch (e: InvocationTargetException) {
            throw AssertionError(
                "AC-8, plan C8: UndeliveredErrors.decide must never throw (it runs inside RxJava's error " +
                    "handler, on any thread); it threw for ${describe(throwable)}, setting ${onOff(reportDisposed)}",
                e.targetException
            )
        }
        return (action as Enum<*>).name
    }

    private fun constant(name: String): String = try {
        owner().getField(name).get(null) as String
    } catch (e: NoSuchFieldException) {
        notImplemented("the constant $CLASS_NAME.$name does not exist (a `const val` of the object)", e)
    }

    val logTag: String get() = constant("LOG_TAG")
    val logMessage: String get() = constant("LOG_MESSAGE")
}

internal fun onOff(reportDisposed: Boolean) = if (reportDisposed) "ON" else "OFF"

/** `Outer(Inner(..))` with class names only, composite members in brackets. */
internal fun describe(throwable: Throwable?): String {
    if (throwable == null) return "null"
    val name = throwable.javaClass.simpleName
    if (throwable is CompositeException) {
        return name + throwable.exceptions.joinToString(", ", "[", "]") { describe(it) }
    }
    val cause = throwable.cause
    return if (cause == null || cause === throwable) name else "$name(${describe(cause)})"
}

/**
 * Today's handler, restated (plan C8, steps 2 to 5; `App.java` `configureRxJavaErrorHandler`).
 * Deliberately not written with `hasAssignableCause`, the helper both the handler and the
 * planned function use: the chain walk is spelled out here.
 */
internal object TodaysHandler {
    /** What today's handler does with one error. */
    enum class Kind {
        /** Step 4: a member is caused by the network or an interruption; return, nothing happens. */
        IGNORED_FOR_IO,

        /** Step 4: [Outcome.handedOver] (the member) goes to the thread's uncaught-exception handler. */
        CRASH,

        /** Step 5, debug setting on: [Outcome.handedOver] (the unwrapped error) goes to that handler. */
        REPORT,

        /** Step 5, debug setting off: nothing happens, not even a log line. */
        DROPPED_SILENTLY
    }

    class Outcome(val kind: Kind, val handedOver: Throwable?)

    private val ignored: List<Class<*>> = listOf(
        IOException::class.java, SocketException::class.java,
        InterruptedException::class.java, InterruptedIOException::class.java
    )
    private val critical: List<Class<*>> = listOf(
        NullPointerException::class.java, IllegalArgumentException::class.java,
        OnErrorNotImplementedException::class.java, MissingBackpressureException::class.java,
        IllegalStateException::class.java
    )

    /** The throwable or one of its causes is an instance of one of [types]. */
    private fun chainHolds(throwable: Throwable?, types: List<Class<*>>): Boolean {
        var current = throwable
        while (current != null) {
            if (types.any { it.isInstance(current) }) {
                return true
            }
            val next = current.cause
            if (next === current) {
                return false
            }
            current = next
        }
        return false
    }

    fun handle(throwable: Throwable, reportDisposed: Boolean): Outcome {
        // step 2
        val actual: Throwable? = if (throwable is UndeliverableException) throwable.cause else throwable
        // step 3
        val errors: List<Throwable?> = if (actual is CompositeException) actual.exceptions else listOf(actual)
        // step 4
        for (error in errors) {
            if (chainHolds(error, ignored)) {
                return Outcome(Kind.IGNORED_FOR_IO, null)
            }
            if (chainHolds(error, critical)) {
                return Outcome(Kind.CRASH, error)
            }
        }
        // step 5
        return if (reportDisposed) Outcome(Kind.REPORT, actual) else Outcome(Kind.DROPPED_SILENTLY, null)
    }

    /** Today's outcome in the vocabulary of `UndeliveredErrors.Action` (today never logs). */
    fun asAction(kind: Kind): String = when (kind) {
        Kind.IGNORED_FOR_IO, Kind.DROPPED_SILENTLY -> IGNORE
        Kind.CRASH -> CRASH
        Kind.REPORT -> REPORT
    }

    /**
     * AC-8 as the plan's decision table has it: equal to today, except `LOG` exactly where the
     * argument is an `UndeliverableException`, the debug setting is off and today's step 4 did
     * not return for a network or interruption cause.
     */
    fun expectedAfterTheChange(throwable: Throwable, reportDisposed: Boolean): String {
        val today = handle(throwable, reportDisposed).kind
        return if (throwable is UndeliverableException && !reportDisposed && today != Kind.IGNORED_FOR_IO) {
            LOG
        } else {
            asAction(today)
        }
    }
}

/** One generated error: [make] builds a fresh instance every time. */
internal class ErrorShape(val name: String, val make: () -> Throwable)

/**
 * The matrix of plan T2: every exception type of the decision table (and a few neighbours:
 * subclasses, errors, an inner wrapper), plain, as a cause at depth 1 and 2, as first and as
 * second member of a `CompositeException`, as a member one level down, and each of these
 * wrapped in `UndeliverableException` and not.
 */
internal object ErrorShapes {
    private fun shape(name: String, make: () -> Throwable) = ErrorShape(name, make)

    /** The single exceptions. */
    val leaves: List<ErrorShape> = listOf(
        // network and interruption
        shape("IOException") { IOException("io") },
        shape("SocketException") { SocketException("socket") },
        shape("SocketTimeoutException") { SocketTimeoutException("timeout") },
        shape("InterruptedIOException") { InterruptedIOException("interrupted io") },
        shape("InterruptedException") { InterruptedException("interrupted") },
        shape("FileNotFoundException") { FileNotFoundException("subclass of IOException") },
        // the five "bug" types of the handler, and subclasses
        shape("NullPointerException") { NullPointerException("npe") },
        shape("IllegalArgumentException") { IllegalArgumentException("iae") },
        shape("NumberFormatException") { NumberFormatException("subclass of IllegalArgumentException") },
        shape("IllegalStateException") { IllegalStateException("ise") },
        shape("MissingBackpressureException") { MissingBackpressureException("mbe") },
        shape("OnErrorNotImplemented(RuntimeException)") { OnErrorNotImplementedException(RuntimeException("x")) },
        shape("OnErrorNotImplemented(NullPointerException)") { OnErrorNotImplementedException(NullPointerException()) },
        shape("OnErrorNotImplemented(IOException)") { OnErrorNotImplementedException(IOException()) },
        // a wrapper inside: it is an IllegalStateException
        shape("Undeliverable(RuntimeException) as inner error") { UndeliverableException(RuntimeException("inner")) },
        // neither
        shape("RuntimeException") { RuntimeException("plain") },
        shape("Exception") { Exception("checked") },
        shape("IndexOutOfBoundsException") { IndexOutOfBoundsException("index") },
        shape("UnsupportedOperationException") { UnsupportedOperationException("unsupported") },
        shape("ClassCastException") { ClassCastException("cast") },
        shape("QueueOverflowException") { QueueOverflowException() },
        shape("AssertionError") { AssertionError("error, not exception") },
        shape("StackOverflowError") { StackOverflowError("error") }
    )

    private fun under(outer: String, wrap: (Throwable) -> Throwable, inner: List<ErrorShape>) =
        inner.map { leaf -> shape("$outer(${leaf.name})") { wrap(leaf.make()) } }

    /** Everything RxJava or the app could hand over that is not itself the wrapper. */
    val unwrapped: List<ErrorShape> by lazy {
        val depth1 = under("Exception", { Exception("parse", it) }, leaves) +
            under("RuntimeException", { RuntimeException(it) }, leaves)
        val depth2 = under("Exception", { Exception("outer", it) }, under("Exception", { Exception("parse", it) }, leaves)) +
            under("RuntimeException", { RuntimeException(it) }, under("IOException", { IOException("io around", it) }, leaves)) +
            under("IllegalStateException", { IllegalStateException("ise around", it) }, under("Exception", { Exception(it) }, leaves))
        val pairs = leaves.flatMap { first ->
            leaves.map { second ->
                shape("Composite[${first.name}, ${second.name}]") { CompositeException(first.make(), second.make()) }
            }
        }
        val pairsOneDown = leaves.flatMap { first ->
            leaves.map { second ->
                shape("Composite[Exception(${first.name}), RuntimeException(${second.name})]") {
                    CompositeException(Exception("first", first.make()), RuntimeException(second.make()))
                }
            }
        }
        val triples = leaves.map { last ->
            shape("Composite[RuntimeException, Exception, ${last.name}]") {
                CompositeException(RuntimeException("a"), Exception("b"), last.make())
            }
        }
        // a composite that is not the error itself but its cause: its members are not looked at
        val compositeAsCause = leaves.map { member ->
            shape("Exception(Composite[${member.name}, RuntimeException])") {
                Exception("around a composite", CompositeException(member.make(), RuntimeException("second")))
            }
        }
        leaves + depth1 + depth2 + pairs + pairsOneDown + triples + compositeAsCause
    }

    /** [unwrapped], and each of them inside an `UndeliverableException`. */
    val all: List<ErrorShape> by lazy {
        unwrapped + unwrapped.map { inner -> shape("Undeliverable(${inner.name})") { UndeliverableException(inner.make()) } }
    }
}
