package org.schabi.newpipe.enhancements.crashfixes

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import java.util.function.Consumer

/**
 * C1 (fix 1a): a result that arrives after its page was left. A page is usable when it has a
 * view, its state is not saved and its view is at least started: the test `InfoItemDialog`
 * applies at click time, plus the saved-state check.
 */
object LatePage {
    @JvmStatic
    fun usable(fragment: Fragment): Boolean =
        fragment.view != null &&
            !fragment.isStateSaved &&
            fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** A consumer that runs [action] only if [fragment] is usable when the value arrives. */
    @JvmStatic
    fun <T> ifUsable(fragment: Fragment, action: Consumer<T>): Consumer<T> =
        Consumer { value ->
            if (usable(fragment)) {
                action.accept(value)
            }
        }
}
