package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer

/**
 * At most one pending request, for one list row. A newer [start] or a [cancel] makes the result
 * of an older request undeliverable, also when that result is already queued on [start]'s
 * `deliverOn` scheduler.
 *
 * [start] and [cancel] must be called on the thread `deliverOn` delivers on (the main thread for
 * a row): then "is this still the current request" is decided on one thread and an old answer
 * can never be applied after a rebind.
 *
 * An error of a request is consumed: no callback, nothing thrown, nothing passed to RxJava's
 * global error handler. The row then simply keeps what it shows.
 */
class LatestRequest<K : Any> {

    /** One started request. [key] says what it was for; identity says whether it is still current. */
    private class Pending<K>(val key: K) {
        var disposable: Disposable? = null
        override fun toString(): String = "Pending($key)"
    }

    @Volatile
    private var current: Pending<K>? = null

    fun <T : Any> start(key: K, request: Single<T>, deliverOn: Scheduler, onResult: Consumer<T>) {
        cancel()
        val pending = Pending(key)
        current = pending
        pending.disposable = request
            .observeOn(deliverOn)
            .subscribe(
                { result ->
                    // Identity, not the key: a second request for the same key supersedes the first.
                    if (current === pending) {
                        current = null
                        onResult.accept(result)
                    }
                },
                { if (current === pending) current = null }
            )
    }

    fun cancel() {
        val pending = current ?: return
        current = null
        pending.disposable?.dispose()
    }
}
