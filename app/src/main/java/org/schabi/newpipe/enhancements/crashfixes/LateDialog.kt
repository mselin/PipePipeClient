package org.schabi.newpipe.enhancements.crashfixes

import androidx.fragment.app.FragmentManager
import java.util.function.Consumer

/**
 * C1 (fix 1b): a dialog that is to be shown on a fragment manager captured earlier.
 * Showing it on a destroyed manager or after its state was saved throws.
 */
object LateDialog {
    @JvmStatic
    fun canShow(fm: FragmentManager?): Boolean =
        fm != null && !fm.isDestroyed && !fm.isStateSaved

    /** A consumer that runs [action] only if [fm] can show a dialog when the value arrives. */
    @JvmStatic
    fun <T> ifCanShow(fm: FragmentManager?, action: Consumer<T>): Consumer<T> =
        Consumer { value ->
            if (canShow(fm)) {
                action.accept(value)
            }
        }
}
