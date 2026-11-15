package org.schabi.newpipe.enhancements.crashfixes

/**
 * C5: null-safe replacement for `err.getMessage().contains(text)` in the download engine.
 * An error without a message (for example `EOFException`) contains nothing.
 */
object ErrorMessages {
    @JvmStatic
    fun contains(error: Throwable?, text: String): Boolean =
        error?.message?.contains(text) == true
}
