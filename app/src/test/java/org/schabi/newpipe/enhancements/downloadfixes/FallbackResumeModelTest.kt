package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection

/**
 * AC-1 and AC-5 for the six lines of plan section 1, run for real on the JVM: real HTTP
 * (`HttpURLConnection` against [LocalServer]), a real file, the real `ResumeCheck`.
 *
 * `DownloadRunnableFallback` itself cannot run here (`android.util.Log`, `SharpStream`,
 * `DownloadMission`). [FallbackModel] is a line-by-line transcription of its `run()` at
 * `cd87fc7` and of the mission fields it touches, with the six planned lines put where the plan
 * puts them. So these tests check the *plan's lines* together with the Code layer's
 * `ResumeCheck`; that the lines really are in the upstream file is `DownloadFixesWiringTest`,
 * and the upstream class itself runs in `FallbackResumeInstrumentedTest` and device step 1.
 *
 * Every test ends with the three things a user depends on:
 * - the file is byte for byte what the server sent, neither longer nor shorter;
 * - "finished" is reported exactly once, and only then; a download that ended in an error is
 *   never reported as finished;
 * - the counters the rest of the mission is built on (`done`, `length`) are true.
 */
class FallbackResumeModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class HttpError(val statusCode: Int) : Exception("HTTP $statusCode")

    /**
     * `DownloadRunnableFallback.run()` and the parts of `DownloadMission` it uses. Names are
     * upstream's (`mMission.` dropped). Line numbers in comments are upstream's at `cd87fc7`.
     */
    private class FallbackModel(private val server: LocalServer, private val file: File, private val withTheSixLines: Boolean = true) {
        // DownloadMission
        var done = 0L
        var length = 0L
        var unknownLength = false
        var offsets = longArrayOf(0L)
        var current = 0
        var fallbackResumeOffset = 0L
        var maxRetry = 3
        var running = false

        // what the mission was told
        var finishedCalls = 0
        val errors = ArrayList<Exception>()
        var recoverCalls = 0

        /** The user pauses once this many bytes were written since [start]; -1: never. */
        var pauseAfterWriting = -1L
        private var written = 0L

        // DownloadRunnableFallback, one object per start()
        private var mRetryCount = 0
        private var mIs: InputStream? = null
        private var mF: RandomAccessFile? = null
        private var mConn: HttpURLConnection? = null

        /** `DownloadMission.start()` for `blocks.length < 1`: a new runnable, then `run()` (here on the calling thread). */
        fun start() {
            mRetryCount = 0
            written = 0
            running = true
            run()
        }

        private fun notifyProgress(deltaLen: Long) { // DownloadMission.java:321-327
            if (unknownLength) length += deltaLen
            done += deltaLen
        }

        private fun dispose() { // :38-50
            runCatching { mIs?.close() }
            mConn?.disconnect()
            mF?.close()
            mIs = null
            mF = null
        }

        private fun run() {
            val done: Boolean
            var start = fallbackResumeOffset // :55

            try {
                val rangeStart = if (unknownLength || start < 1) -1L else start // :62

                val conn = server.open() // :65, DownloadMission.openConnection
                mConn = conn
                if (rangeStart >= 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")

                if (mRetryCount == 0 && rangeStart == -1L) { // :67-70
                    conn.setRequestProperty("Range", "bytes=0-")
                }

                when (val statusCode = conn.responseCode) { // :72, DownloadMission.establishConnection
                    204, 205, 207 -> throw HttpError(statusCode)
                    416 -> Unit
                    else -> if (statusCode < 200 || statusCode > 299) throw HttpError(statusCode)
                }

                if (conn.responseCode == 416 && start > 0) { // :75-80
                    notifyProgress(-start)
                    start = 0
                    mRetryCount--
                    throw HttpError(416)
                }

                if (!unknownLength) unknownLength = conn.contentLengthLong == -1L // :83-84

                if (unknownLength || conn.responseCode == 200) { // :86-89
                    this.done = offsets[current] - offsets[0]
                }

                val f = RandomAccessFile(file, "rw") // :91
                mF = f

                if (withTheSixLines) { // plan section 1, "Edits", in the plan's order
                    val enhResume = ResumeCheck.decide(start, conn.responseCode, conn.getHeaderField("Content-Range"))
                    if (enhResume != ResumeCheck.Action.CONTINUE) start = 0
                    this.done = offsets[current] - offsets[0] + start
                    if (unknownLength) length = start
                    if (enhResume == ResumeCheck.Action.DISCARD_AND_REQUEST_AGAIN) throw IOException("resume not honoured, asking for the whole resource")
                    if (enhResume == ResumeCheck.Action.RESTART_WITH_THIS_BODY && unknownLength) f.setLength(offsets[current])
                }

                f.seek(offsets[current] + start) // :92

                val input = conn.inputStream // :94
                mIs = input

                val buf = ByteArray(BUFFER_SIZE)
                var len = 0

                while (running) { // :99-103
                    len = input.read(buf, 0, buf.size)
                    if (len == -1) break
                    f.write(buf, 0, len)
                    start += len
                    notifyProgress(len.toLong())
                    written += len
                    if (pauseAfterWriting in 0..written) running = false // DownloadMission.pause()
                }

                dispose() // :105

                done = len == -1 // :108
            } catch (e: Exception) {
                dispose() // :110

                fallbackResumeOffset = start // :112

                if (!running) return // :114

                if (e is HttpError && (e.statusCode == 403 || e.statusCode == 401)) { // :116-122
                    recoverCalls++
                    return
                }

                if (mRetryCount++ >= maxRetry) { // :124-127
                    errors.add(e)
                    running = false
                    return
                }

                run() // :133
                return
            }

            if (done) { // :137-141
                finishedCalls++
                running = false
            } else {
                fallbackResumeOffset = start
            }
        }

        companion object {
            /** Upstream: 64 KiB. Smaller here so that a pause falls inside a small test file. */
            const val BUFFER_SIZE = 512
        }
    }

    private fun target(preallocated: Int = 0): File {
        val file = temp.newFile()
        if (preallocated > 0) RandomAccessFile(file, "rw").use { it.setLength(preallocated.toLong()) } // DownloadInitializer.java:152
        return file
    }

    private fun assertFinishedWith(model: FallbackModel, file: File, expectedFile: ByteArray, resourceLength: Int) {
        assertEquals("no error was reported", emptyList<Exception>(), model.errors)
        assertEquals("finished is reported exactly once", 1, model.finishedCalls)
        assertEquals("AC-1: the finished file has the length the server sent", expectedFile.size.toLong(), file.length())
        assertArrayEquals("AC-1: the finished file is byte for byte what the server sent", expectedFile, file.readBytes())
        assertEquals("done counts the bytes of the mission that are written", expectedFile.size.toLong(), model.done)
        assertEquals("length is the length of the current resource", resourceLength.toLong(), model.length)
    }

    // ---- the finding, to show that these tests can see it ----

    /**
     * The model without the six lines reproduces the finding (plan section 1, first row of the
     * table): prefix + whole body, reported as finished. If this test fails, the model no longer
     * shows the defect and the tests below prove nothing.
     */
    @Test
    fun theModelWithoutTheSixLines_reproducesTheFinding() {
        val content = bytesOf(6000, seed = 10)
        val file = target(preallocated = content.size)
        LocalServer { LocalServer.ignoring(content) }.use { server ->
            val model = FallbackModel(server, file, withTheSixLines = false).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            val resumeAt = model.fallbackResumeOffset
            model.pauseAfterWriting = -1
            model.start()

            assertEquals("upstream reports the corrupt file as finished", 1, model.finishedCalls)
            assertEquals("upstream: prefix + whole body", resumeAt + content.size, file.length())
            assertFalse(content.contentEquals(file.readBytes()))
        }
    }

    // ---- AC-1 ----

    /**
     * AC-1, table row 1: known length, paused, the server answers the resume with 200 and the
     * whole file. The download starts over from the beginning of the file.
     */
    @Test
    fun ac1_knownLength_resumeAnsweredWith200_fileIsWhatTheServerSent() {
        val content = bytesOf(6000, seed = 11)
        val file = target(preallocated = content.size)
        LocalServer { LocalServer.ignoring(content) }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            assertEquals("paused, not finished", 0, model.finishedCalls)
            assertTrue("the pause fell inside the file", model.fallbackResumeOffset in 1 until content.size)
            val resumeAt = model.fallbackResumeOffset

            model.pauseAfterWriting = -1
            model.start()

            assertEquals("the resume asked for the rest", "bytes=$resumeAt-", server.requests[1].range)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-1, table row 2: unknown length, resume after a pause. Upstream never sends the offset,
     * so the body always begins at 0; it must be written at 0.
     */
    @Test
    fun ac1_unknownLength_resumeAfterPause_startsOver() {
        val content = bytesOf(6000, seed = 12)
        val file = target()
        LocalServer { LocalServer.ignoring(content, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file).apply { pauseAfterWriting = 2500 }
            model.start()
            assertTrue("the first response made the length unknown", model.unknownLength)
            assertTrue(model.fallbackResumeOffset > 0)

            model.pauseAfterWriting = -1
            model.start()

            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /** AC-1, table row 2, with a server that would honour ranges: `bytes=0-` is answered with 206 from 0. */
    @Test
    fun ac1_unknownLength_resumeAnsweredWith206FromZero_startsOver() {
        val content = bytesOf(6000, seed = 13)
        val file = target()
        LocalServer { LocalServer.honouring(content, it, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file).apply { pauseAfterWriting = 2500 }
            model.start()
            model.pauseAfterWriting = -1
            model.start()

            assertEquals("bytes=0-", server.requests[1].range)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-1, table row 3: unknown length, the transfer breaks and is retried inside the run. The
     * retry carries no `Range` at all; its body is the whole file and is written from 0.
     */
    @Test
    fun ac1_unknownLength_retryInsideTheRun_startsOver() {
        val content = bytesOf(6000, seed = 14)
        val file = target()
        var attempt = 0
        LocalServer { if (attempt++ == 0) Response(200, content, cutAfter = 2600) else LocalServer.ignoring(content) }.use { server ->
            val model = FallbackModel(server, file).apply { unknownLength = true } // known from an earlier response of this mission
            model.start()

            assertEquals(2, server.requests.size)
            assertNull("the retry inside the run sends no Range", server.requests[1].range)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-1, plan "What a restart does": with unknown length the file is cut at the start of the
     * resource, so a body that is shorter than what was already written leaves no tail.
     */
    @Test
    fun ac1_unknownLength_restartWithAShorterBody_leavesNoTail() {
        val first = bytesOf(6000, seed = 15)
        val second = bytesOf(1500, seed = 16)
        val file = target()
        var attempt = 0
        LocalServer { LocalServer.ignoring(if (attempt++ == 0) first else second, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file).apply { pauseAfterWriting = 4000 }
            model.start()
            assertTrue("more is on the disk than the second body is long", file.length() > second.size)

            model.pauseAfterWriting = -1
            model.start()

            assertFinishedWith(model, file, second, resourceLength = second.size)
        }
    }

    /**
     * AC-1, table row 4 ("Counters"): with unknown length `length` ends as the length of the
     * body, not prefix + body, so that the next resource of the mission is placed right behind it.
     */
    @Test
    fun ac1_unknownLength_lengthCounterIsTheBodyLength() {
        val content = bytesOf(5000, seed = 17)
        val file = target()
        LocalServer { LocalServer.ignoring(content, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file).apply { pauseAfterWriting = 1000 }
            model.start()
            model.pauseAfterWriting = 3000
            model.start() // paused a second time, further on
            model.pauseAfterWriting = -1
            model.start()

            assertEquals(content.size.toLong(), model.length)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-1, plan "What a restart does": the restart goes back to the start of the *current*
     * resource; the resources before it stay as they are. Known length.
     */
    @Test
    fun ac1_secondResource_knownLength_restartKeepsTheFirstResource() {
        val firstResource = bytesOf(3000, seed = 18)
        val content = bytesOf(6000, seed = 19)
        val file = target()
        file.writeBytes(firstResource + ByteArray(content.size))
        LocalServer { LocalServer.ignoring(content) }.use { server ->
            val model = FallbackModel(server, file).apply {
                offsets = longArrayOf(0L, firstResource.size.toLong())
                current = 1
                done = firstResource.size.toLong()
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            model.pauseAfterWriting = -1
            model.start()

            assertFinishedWith(model, file, firstResource + content, resourceLength = content.size)
        }
    }

    /** AC-1, the same with unknown length: the file is cut at the start of the current resource, not at 0. */
    @Test
    fun ac1_secondResource_unknownLength_restartKeepsTheFirstResource() {
        val firstResource = bytesOf(3000, seed = 20)
        val content = bytesOf(6000, seed = 21)
        val shorter = content.copyOfRange(0, 1200)
        val file = target()
        file.writeBytes(firstResource)
        var attempt = 0
        LocalServer { LocalServer.ignoring(if (attempt++ == 0) content else shorter, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file).apply {
                offsets = longArrayOf(0L, firstResource.size.toLong())
                current = 1
                done = firstResource.size.toLong()
                pauseAfterWriting = 4000
            }
            model.start()
            model.pauseAfterWriting = -1
            model.start()

            assertFinishedWith(model, file, firstResource + shorter, resourceLength = shorter.size)
        }
    }

    /**
     * AC-1, rule 4 and "The thrown case": a 206 that starts at another offset is not written.
     * The attempt counts as one retry, and the next request carries no `Range`.
     */
    @Test
    fun ac1_resumeAnsweredWith206FromAnotherOffset_isDiscarded_andAskedAgainWithoutRange() {
        val content = bytesOf(6000, seed = 22)
        val file = target(preallocated = content.size)
        var attempt = 0
        LocalServer {
            when (attempt++) {
                0 -> LocalServer.ignoring(content) // the first run, paused
                1 -> Response(206, content.copyOfRange(100, content.size), contentRange = "bytes 100-5999/6000") // not what was asked
                else -> LocalServer.honouring(content, it)
            }
        }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            model.pauseAfterWriting = -1
            model.start()

            assertEquals(3, server.requests.size)
            assertNull("plan: ':133 asks again without Range'", server.requests[2].range)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /** AC-1, rule 4: a 206 without `Content-Range` to a resume is discarded too. */
    @Test
    fun ac1_resumeAnsweredWith206WithoutContentRange_isDiscarded() {
        val content = bytesOf(6000, seed = 23)
        val file = target(preallocated = content.size)
        var attempt = 0
        LocalServer {
            when (attempt++) {
                0 -> LocalServer.ignoring(content)
                1 -> Response(206, content.copyOfRange(2048, content.size))
                else -> LocalServer.ignoring(content)
            }
        }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            model.pauseAfterWriting = -1
            model.start()

            assertEquals(3, server.requests.size)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-1, plan: "a server that keeps answering nonsense ends in `notifyError` after
     * `maxRetry`". The download is never reported as finished, the loop ends, and nothing of
     * the nonsense is written into the part that was already downloaded.
     */
    @Test
    fun ac1_serverKeepsAnsweringNonsense_endsInAnError_neverInFinished() {
        val content = bytesOf(6000, seed = 24)
        val file = target(preallocated = content.size)
        var attempt = 0
        LocalServer {
            if (attempt++ == 0) LocalServer.ignoring(content)
            else Response(206, bytesOf(500, seed = 99), contentRange = "bytes 100-599/6000")
        }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            val onDiskBefore = file.readBytes()

            model.pauseAfterWriting = -1
            model.start()

            assertEquals("AC-1: a failed download is never reported as finished", 0, model.finishedCalls)
            assertEquals("one error after the retries", 1, model.errors.size)
            assertEquals("the resume and maxRetry further attempts", 1 + 1 + model.maxRetry, server.requests.size)
            assertArrayEquals("nothing of a discarded body is written", onDiskBefore, file.readBytes())
            assertEquals("the next start begins at 0", 0L, model.fallbackResumeOffset)
            assertEquals("done says that nothing of this resource counts any more", 0L, model.done)
        }
    }

    // ---- AC-5 ----

    /** AC-5: a download that is never interrupted, answered with 200. */
    @Test
    fun ac5_freshDownload_200() {
        val content = bytesOf(6000, seed = 30)
        val file = target(preallocated = content.size)
        LocalServer { LocalServer.ignoring(content) }.use { server ->
            val model = FallbackModel(server, file).apply { length = content.size.toLong() }
            model.start()

            assertEquals(1, server.requests.size)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /** AC-5: the same answered with 206 from 0, with and without `Content-Range` (rules 1 and 2). */
    @Test
    fun ac5_freshDownload_206FromZero_withAndWithoutContentRange() {
        val content = bytesOf(6000, seed = 31)
        for (contentRange in listOf("bytes 0-5999/6000", null)) {
            val file = target(preallocated = content.size)
            LocalServer { Response(206, content, contentRange = contentRange) }.use { server ->
                val model = FallbackModel(server, file).apply { length = content.size.toLong() }
                model.start()

                assertEquals("Content-Range: $contentRange", 1, server.requests.size)
                assertFinishedWith(model, file, content, resourceLength = content.size)
            }
        }
    }

    /** AC-5: a fresh download of unknown length. */
    @Test
    fun ac5_freshDownload_unknownLength() {
        val content = bytesOf(6000, seed = 32)
        val file = target()
        LocalServer { LocalServer.ignoring(content, declareLength = false) }.use { server ->
            val model = FallbackModel(server, file)
            model.start()

            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-5: pause and resume with a server that honours the range. The resume continues: the
     * second request asks for the rest and only the rest is transferred.
     */
    @Test
    fun ac5_pauseAndResume_honoured_continuesWhereItStopped() {
        val content = bytesOf(6000, seed = 33)
        val file = target(preallocated = content.size)
        var sentOnResume = -1
        LocalServer { request -> LocalServer.honouring(content, request).also { if ((request.rangeStart ?: 0L) > 0L) sentOnResume = it.body.size } }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            val resumeAt = model.fallbackResumeOffset
            assertTrue(resumeAt in 1 until content.size)

            model.pauseAfterWriting = -1
            model.start()

            assertEquals(2, server.requests.size)
            assertEquals("bytes=$resumeAt-", server.requests[1].range)
            assertEquals("only the rest was sent", content.size - resumeAt.toInt(), sentOnResume)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-5: a transfer that breaks is retried inside the run from where it broke, when the
     * server honours the range.
     */
    @Test
    fun ac5_retryInsideTheRun_honoured_continuesWhereItBroke() {
        val content = bytesOf(6000, seed = 34)
        val file = target(preallocated = content.size)
        var attempt = 0
        LocalServer { if (attempt++ == 0) Response(200, content, cutAfter = 2600) else LocalServer.honouring(content, it) }.use { server ->
            val model = FallbackModel(server, file).apply { length = content.size.toLong() }
            model.start()

            assertEquals(2, server.requests.size)
            val resumedAt = server.requests[1].rangeStart
            assertTrue("the retry asked for the rest, was ${server.requests[1].range}", resumedAt != null && resumedAt in 1..2600)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /**
     * AC-5 and plan regression (a): the third and fourth line run on every start and make `done`
     * true again after a process kill (`done` is saved every 512 KiB, the offset only at a pause).
     */
    @Test
    fun ac5_doneIsCorrectedOnEveryStart() {
        val content = bytesOf(6000, seed = 35)
        val file = target(preallocated = content.size)
        LocalServer { LocalServer.honouring(content, it) }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            model.done = 5555 // what a state file saved at another moment says

            model.pauseAfterWriting = -1
            model.start()

            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }

    /** AC-5: pausing twice and resuming twice, honoured both times. */
    @Test
    fun ac5_pauseTwice_resumeTwice() {
        val content = bytesOf(9000, seed = 36)
        val file = target(preallocated = content.size)
        LocalServer { LocalServer.honouring(content, it) }.use { server ->
            val model = FallbackModel(server, file).apply {
                length = content.size.toLong()
                pauseAfterWriting = 2000
            }
            model.start()
            model.start() // pauses again after 2000 more
            assertEquals(0, model.finishedCalls)
            model.pauseAfterWriting = -1
            model.start()

            assertEquals(3, server.requests.size)
            assertFinishedWith(model, file, content, resourceLength = content.size)
        }
    }
}
