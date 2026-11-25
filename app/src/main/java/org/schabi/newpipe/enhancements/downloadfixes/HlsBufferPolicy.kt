package org.schabi.newpipe.enhancements.downloadfixes

/** The numbers that bound what one HLS transfer keeps in memory. */
object HlsBufferPolicy {
    /** Heap one transfer may hold for segment data: received, assembled, decrypted, waiting. */
    const val LIMIT_BYTES: Long = 64L * 1024 * 1024

    /** Most that is read of an encryption key; a valid AES-128 key is 16 bytes. */
    const val KEY_LIMIT_BYTES: Long = 64L * 1024

    /** Segments that may be submitted and not yet written: 2 per worker. */
    @JvmStatic
    fun window(parallelism: Int): Int = 2 * parallelism.coerceAtLeast(1)

    /**
     * Whether one more segment may be handed to a worker. The window is never smaller than the
     * parallelism and never empty, so with nothing outstanding the answer is always yes, and
     * when it is no, the segment at [nextToAppend] has been submitted and is not written yet.
     */
    @JvmStatic
    fun maySubmit(nextToSubmit: Int, nextToAppend: Int, parallelism: Int): Boolean =
        nextToSubmit.toLong() - nextToAppend.toLong() < window(parallelism)
}
