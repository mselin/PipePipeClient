package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import org.schabi.newpipe.ktx.hasAssignableCause
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

object UndeliveredErrors {
    enum class Action { IGNORE, LOG, REPORT, CRASH }

    const val LOG_TAG = "UndeliveredErrors"
    const val LOG_MESSAGE = "Undelivered error, logged and not rethrown"

    @JvmStatic
    fun decide(throwable: Throwable, reportDisposed: Boolean): Action {
        val undeliverable = throwable is UndeliverableException
        val actual: Throwable? = if (undeliverable) throwable.cause else throwable
        val errors: List<Throwable?> = if (actual is CompositeException) actual.exceptions else listOf(actual)
        for (error in errors) {
            if (error.hasAssignableCause(
                    IOException::class.java, SocketException::class.java,
                    InterruptedException::class.java, InterruptedIOException::class.java
                )
            ) {
                return Action.IGNORE
            }
            if (error.hasAssignableCause(
                    NullPointerException::class.java, IllegalArgumentException::class.java,
                    OnErrorNotImplementedException::class.java, MissingBackpressureException::class.java,
                    IllegalStateException::class.java
                )
            ) {
                return if (undeliverable && !reportDisposed) Action.LOG else Action.CRASH
            }
        }
        return when {
            reportDisposed -> Action.REPORT
            undeliverable -> Action.LOG
            else -> Action.IGNORE
        }
    }
}
