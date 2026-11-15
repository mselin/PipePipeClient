package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets a stream work only while its screen is on screen.
 *
 * - On screen: the source is subscribed and everything is passed.
 * - Off screen: the source is not subscribed at all (a Room `Flowable` then has no table observer
 *   and runs no query), except for the first load: until a gated stream has delivered one item
 *   it may fetch exactly one, so a screen that was never shown, or one that never reports, has
 *   its data.
 * - Every return to the screen subscribes to the source again; a source that emits its current
 *   value on subscription (Room, a behaviour processor) therefore shows current data.
 *
 * A gate starts off screen. "Has delivered" is kept per stream returned by [gate], not per gate.
 */
class OnScreenGate {

    private val onScreen = BehaviorProcessor.createDefault(false)

    fun setOnScreen(onScreen: Boolean) {
        // BehaviorProcessor.onNext must not be called concurrently; reports come from the main
        // thread, the lock only makes that a non-assumption.
        synchronized(this.onScreen) { this.onScreen.onNext(onScreen) }
    }

    fun <T : Any> gate(source: Flowable<T>): Flowable<T> = Flowable.defer {
        // Written on the thread the source emits on, read on the thread that reports the screen.
        val delivered = AtomicBoolean(false)
        onScreen
            .distinctUntilChanged()
            .switchMap { on ->
                when {
                    on -> source
                    !delivered.get() -> source.take(1)
                    else -> Flowable.never()
                }
            }
            .doOnNext { delivered.set(true) }
    }
}
