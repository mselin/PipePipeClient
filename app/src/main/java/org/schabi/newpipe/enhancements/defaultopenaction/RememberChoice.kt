package org.schabi.newpipe.enhancements.defaultopenaction

import org.schabi.newpipe.enhancements.queueguard.GuardChoice

// Plain Kotlin, no Android imports: the checkbox "Remember my choice" (plan 4.3).

/** Where a remembered choice goes: the default action and queue-guard's switch "Ask before replacing the queue". */
fun interface RememberStore {
    fun save(defaultAction: DefaultTapAction, askBeforeReplacing: Boolean)
}

/** Decides what the checkbox in one tap dialog saves (AC-8) and when it saves nothing (AC-9). */
class RememberChoice(private val store: RememberStore) {

    /** A choice made in the tap dialog, before it is carried out. [checked]: the checkbox at that moment. */
    fun onChoice(choice: GuardChoice, checked: Boolean): Boolean {
        if (!checked) {
            return false
        }
        val action = DefaultTapAction.fromChoice(choice) ?: return false // CANCEL
        store.save(action, askBeforeReplacing = false)
        return true
    }
}
