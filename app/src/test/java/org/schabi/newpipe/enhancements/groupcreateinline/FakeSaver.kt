package org.schabi.newpipe.enhancements.groupcreateinline

/** What [FakeSaver] does with the callback of a `createGroups` call. */
internal sealed class SaverAnswer {
    /** Keeps the callback until the test calls [FakeSaver.succeed] or [FakeSaver.fail]. */
    data object Later : SaverAnswer()

    /** Reports success before `createGroups` returns. */
    data object SuccessAtOnce : SaverAnswer()

    /** Reports [error] before `createGroups` returns. */
    data class ErrorAtOnce(val error: Throwable) : SaverAnswer()
}

/**
 * Hand-written test double for [GroupSaver] (plan section 8: "`FakeSaver` records arguments and
 * either answers at once (success or error) or keeps the callback for the test to answer").
 *
 * It keeps the promise of [GroupSaver.createGroups] itself: every callback is called exactly
 * once. Answering a call that does not exist fails the test.
 */
internal class FakeSaver(
    /** Applies to the calls that arrive from now on; a test may change it between calls. */
    var answer: SaverAnswer = SaverAnswer.Later
) : GroupSaver {

    /** The arguments of one `createGroups` call. [names] is a copy taken at the moment of the call. */
    data class Request(val names: List<String>, val subscriptionId: Long)

    private val recorded = mutableListOf<Request>()
    private val kept = mutableListOf<SaveCallback>()

    /** Every `createGroups` call so far, oldest first. Empty means the saver was never called. */
    val requests: List<Request> get() = recorded.toList()

    /** Number of calls whose callback is still waiting for [succeed] or [fail]. */
    val unanswered: Int get() = kept.size

    /**
     * Runs inside every `createGroups` call, after it is recorded and before it is answered.
     * A test uses it to read the session's and the ui's state at the moment the saver is called.
     */
    var onCreateGroups: (Request) -> Unit = {}

    override fun createGroups(names: List<String>, subscriptionId: Long, callback: SaveCallback) {
        val request = Request(names.toList(), subscriptionId)
        recorded += request
        onCreateGroups(request)
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
                "FakeSaver has no unanswered createGroups call: " +
                    "the session did not call the saver, or the call was already answered"
            )
        }
        return kept.removeAt(0)
    }
}
