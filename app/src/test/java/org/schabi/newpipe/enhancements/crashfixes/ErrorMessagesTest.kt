package org.schabi.newpipe.enhancements.crashfixes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.nio.channels.ClosedChannelException

/**
 * AC-5 (C5): `ErrorMessages.contains(error, text)` replaces `err.getMessage().contains(text)` in
 * the download engine, which threw a `NullPointerException` for an error without a message.
 *
 * These tests do not prove that the download engine calls the helper; `CrashFixesWiringTest`
 * checks the three call sites as text. There is no device provocation for AC-5 (plan,
 * "Verification").
 */
class ErrorMessagesTest {

    /** The old crash input: exceptions whose `message` is null. */
    @Test
    fun ac5_noMessage_isFalse() {
        assertFalse(ErrorMessages.contains(IOException(), "Permission denied"))
        assertFalse(ErrorMessages.contains(IOException(), "ENOSPC"))
        assertFalse(ErrorMessages.contains(EOFException(), "Permission denied"))
        assertFalse(ErrorMessages.contains(EOFException(), "ENOSPC"))
        assertFalse(ErrorMessages.contains(ClosedChannelException(), "ENOSPC"))
        assertFalse(ErrorMessages.contains(IOException(null as String?), "ENOSPC"))
    }

    @Test
    fun ac5_nullError_isFalse() {
        assertFalse(ErrorMessages.contains(null, "Permission denied"))
        assertFalse(ErrorMessages.contains(null, ""))
    }

    /** Errors with a message are classified as before (AC-7). */
    @Test
    fun ac5_messageContainsText_isTrue() {
        assertTrue(
            ErrorMessages.contains(
                IOException("/storage/emulated/0/x.mp4: open failed: EACCES (Permission denied)"),
                "Permission denied"
            )
        )
        assertTrue(ErrorMessages.contains(IOException("write failed: ENOSPC (No space left on device)"), "ENOSPC"))
        assertTrue(ErrorMessages.contains(IOException("ENOSPC"), "ENOSPC"))
        assertTrue(ErrorMessages.contains(RuntimeException("Permission denied"), "Permission denied"))
    }

    /** Other messages, and the same text in another case, do not match (as `String.contains`). */
    @Test
    fun ac5_otherMessage_isFalse() {
        assertFalse(ErrorMessages.contains(IOException("Connection reset"), "Permission denied"))
        assertFalse(ErrorMessages.contains(IOException("Permission denied"), "ENOSPC"))
        assertFalse(ErrorMessages.contains(IOException(""), "ENOSPC"))
        assertFalse(ErrorMessages.contains(IOException("permission denied"), "Permission denied"))
    }

    /** The message of the error itself is read, not that of its cause (as the replaced code did). */
    @Test
    fun ac5_messageOfCauseOnly_isFalse() {
        val wrapped = object : IOException(IOException("ENOSPC")) {
            override val message: String? get() = null
        }
        assertFalse(ErrorMessages.contains(wrapped, "ENOSPC"))
    }
}
