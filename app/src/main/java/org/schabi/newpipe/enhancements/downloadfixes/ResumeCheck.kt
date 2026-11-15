package org.schabi.newpipe.enhancements.downloadfixes

/**
 * What the single-connection download does with a response before it writes the first byte.
 *
 * The caller is about to write the response body at `expectedStart` (relative to the resource).
 * That is only right when the body starts there. A server that ignores `Range` answers 200 with
 * the whole resource, and writing that after the part already on disk gives a file that is
 * longer than the resource and wrong from the resume offset on.
 */
object ResumeCheck {
    enum class Action {
        /** The body starts where the caller writes. */
        CONTINUE,

        /** The body is the whole resource: write it from the start of the resource. */
        RESTART_WITH_THIS_BODY,

        /** The body starts somewhere else or nowhere known: drop it, ask for the whole resource. */
        DISCARD_AND_REQUEST_AGAIN,
    }

    /** At most 19 digits: more cannot be a `Long`, and `toLongOrNull` settles the rest. */
    private val CONTENT_RANGE = Regex("bytes[ \\t]+([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})", RegexOption.IGNORE_CASE)

    /**
     * First byte position of `bytes a-b/len` (unit case-insensitive, surrounding blanks
     * allowed). -1 for null, for `bytes STAR/len`, for an unknown complete length
     * (`bytes a-b/STAR`, see A7) and for anything else that is not exactly that form. Never throws.
     */
    @JvmStatic
    fun rangeStart(contentRange: String?): Long {
        if (contentRange == null) return -1
        val match = CONTENT_RANGE.matchEntire(contentRange.trim()) ?: return -1
        val first = match.groupValues[1].toLongOrNull() ?: return -1
        // the two other numbers must be numbers too; "bytes 1-99999999999999999999/..." is not a range
        match.groupValues[2].toLongOrNull() ?: return -1
        match.groupValues[3].toLongOrNull() ?: return -1
        return first
    }

    /**
     * @param expectedStart offset the caller is about to write at (>= 0)
     * @param statusCode a 2xx
     * @param contentRange the `Content-Range` response header, if any
     */
    @JvmStatic
    fun decide(expectedStart: Long, statusCode: Int, contentRange: String?): Action {
        // Only a 206 carries a part. A 200 (or any other 2xx) carries the whole resource,
        // whatever a Content-Range header next to it says.
        val bodyStart = if (statusCode == 206) rangeStart(contentRange) else 0L
        return when {
            bodyStart == expectedStart -> Action.CONTINUE
            // 206 without a usable Content-Range to a request from byte 0: nothing can be misplaced
            expectedStart == 0L && bodyStart == -1L -> Action.CONTINUE
            bodyStart == 0L -> Action.RESTART_WITH_THIS_BODY
            else -> Action.DISCARD_AND_REQUEST_AGAIN
        }
    }
}
