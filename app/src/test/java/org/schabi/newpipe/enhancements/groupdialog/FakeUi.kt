package org.schabi.newpipe.enhancements.groupdialog

/** One call that arrived at [FakeUi], as a value. Tests compare whole lists of these. */
internal sealed class UiCall {
    /** `showPending(pending)`. [pending] is a copy taken at the moment of the call. */
    data class ShowPending(val pending: List<PendingGroup>) : UiCall()

    /** `showLoaded()`. */
    data object ShowLoaded : UiCall()

    /** `showLoadError()`. */
    data object ShowLoadError : UiCall()

    /** `setBusy(busy)`. */
    data class SetBusy(val busy: Boolean) : UiCall()

    /** `showSaveError()`. */
    data object ShowSaveError : UiCall()

    /** `close()`. */
    data object Close : UiCall()
}

/**
 * Hand-written test double for [SelectionUi] (plan section 4).
 *
 * All six methods of the interface are recorded in one list. Comparing [calls] with a complete
 * expected list therefore proves both the order of the calls and that nothing else was called.
 *
 * The test doubles are `internal`, so that they compile whether the feature declares its types
 * public or internal.
 */
internal class FakeUi : SelectionUi {
    private val recorded = mutableListOf<UiCall>()
    private val handedOver = mutableListOf<List<PendingGroup>>()

    /** Every call since construction or the last [forgetCalls], oldest first. */
    val calls: List<UiCall> get() = recorded.toList()

    /**
     * The list objects themselves that were passed to [showPending], not copies, oldest first.
     * Lets a test see whether such a list changes after it was handed over.
     */
    val listsHandedOver: List<List<PendingGroup>> get() = handedOver.toList()

    /**
     * Runs inside every call, after the call is recorded. A test uses it to read the session's
     * state at the moment the ui is called.
     */
    var onCall: (UiCall) -> Unit = {}

    /** Drops everything recorded so far, so that a test asserts only on what follows its set-up. */
    fun forgetCalls() {
        recorded.clear()
        handedOver.clear()
    }

    override fun showPending(pending: List<PendingGroup>) {
        handedOver.add(pending)
        record(UiCall.ShowPending(pending.toList()))
    }

    override fun showLoaded() = record(UiCall.ShowLoaded)

    override fun showLoadError() = record(UiCall.ShowLoadError)

    override fun setBusy(busy: Boolean) = record(UiCall.SetBusy(busy))

    override fun showSaveError() = record(UiCall.ShowSaveError)

    override fun close() = record(UiCall.Close)

    private fun record(call: UiCall) {
        recorded += call
        onCall(call)
    }
}
