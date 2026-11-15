package org.schabi.newpipe.enhancements.groupdialog

/** What [FakeSaver] does with the callback of a `save` call. */
internal sealed class SaverAnswer {
    /** Keeps the callback until the test calls [FakeSaver.succeed] or [FakeSaver.fail]. */
    data object Later : SaverAnswer()

    /** Reports success before `save` returns. */
    data object SuccessAtOnce : SaverAnswer()

    /** Reports [error] before `save` returns. */
    data class ErrorAtOnce(val error: Throwable) : SaverAnswer()
}

/**
 * Hand-written test double for [ChangeSaver] (plan section 4). It records the [GroupChanges] of
 * every `save` call and either answers at once (success or error) or keeps the callback for the
 * test to answer.
 *
 * It saves nothing: what a save does to the tables is [GroupChangeWriter]'s part and is tested
 * with [FakeTable]. Every callback is called at most once, as [ChangeSaver.save] promises;
 * answering a call that does not exist fails the test.
 */
internal class FakeSaver(
    /** Applies to the calls that arrive from now on; a test may change it between calls. */
    var answer: SaverAnswer = SaverAnswer.Later
) : ChangeSaver {

    private val recorded = mutableListOf<GroupChanges>()
    private val kept = mutableListOf<SaveCallback>()

    /**
     * The changes of every `save` call so far, oldest first; the three lists of each are copies
     * taken at the moment of the call. Empty means the saver was never called.
     */
    val requests: List<GroupChanges> get() = recorded.toList()

    /** Number of calls whose callback is still waiting for [succeed] or [fail]. */
    val unanswered: Int get() = kept.size

    /**
     * Runs inside every `save` call, after it is recorded and before it is answered. A test uses
     * it to read the session's and the ui's state at the moment the saver is called.
     */
    var onSave: (GroupChanges) -> Unit = {}

    override fun save(changes: GroupChanges, callback: SaveCallback) {
        val request = GroupChanges(
            changes.subscriptionId,
            changes.newGroupNames.toList(),
            changes.addToGroupIds.toList(),
            changes.removeFromGroupIds.toList()
        )
        recorded += request
        onSave(request)
        when (val now = answer) {
            SaverAnswer.Later -> kept += callback
            SaverAnswer.SuccessAtOnce -> callback.onResult(null)
            is SaverAnswer.ErrorAtOnce -> callback.onResult(now.error)
        }
    }

    /** Answers the oldest unanswered call with success. */
    fun succeed() = takeOldest().onResult(null)

    /** Answers the oldest unanswered call with [error]. */
    fun fail(error: Throwable) = takeOldest().onResult(error)

    private fun takeOldest(): SaveCallback {
        if (kept.isEmpty()) {
            throw AssertionError(
                "FakeSaver has no unanswered save call: " +
                    "the session did not call the saver, or the call was already answered"
            )
        }
        return kept.removeAt(0)
    }
}
