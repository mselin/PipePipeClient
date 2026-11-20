package org.schabi.newpipe.enhancements.downloadfixes

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.service.DownloadManagerService
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AC-1 (D5) and AC-5, the plan's instrumented test: the real `DownloadMission` and the real
 * `DownloadRunnableFallback` against a local server. Run by hand on a device or emulator with
 * the debug build (it permits cleartext to 127.0.0.1), not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.downloadfixes.FallbackResumeInstrumentedTest`
 *
 * "A mission built with the public constructor on a file in the cache directory, `blocks` and
 * `fallbackResumeOffset` set by reflection, a local server that ignores `Range`; asserts the
 * finished file equals the served bytes."
 *
 * The mission is in the state `DownloadInitializer` and a pause leave it in: single-connection
 * mode (`blocks` empty), part of the file on disk, the resume offset saved. `start()` then runs
 * the upstream thread. The mission tells its handler when it is finished or failed; the test
 * waits for that message (with an upper bound, so that a hanging download fails the test).
 *
 * The JVM test `FallbackResumeModelTest` runs the same cases on a transcription of `run()`;
 * here the upstream class itself runs, with Android's `HttpURLConnection` and `SharpStream`.
 * Nothing outside the app's cache directory is touched and no request leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class FallbackResumeInstrumentedTest {

    private lateinit var context: Context
    private lateinit var directory: File
    private var server: Server? = null

    @Before
    fun prepareDirectory() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "download-fixes-test-" + System.nanoTime())
        assertTrue(directory.mkdirs())
    }

    @After
    fun cleanUp() {
        server?.close()
        directory.deleteRecursively()
    }

    // ---- AC-1 ----

    /**
     * AC-1, the finding: known length, resumed at an offset, the server ignores `Range` and
     * answers 200 with the whole file. Before the fix the file is prefix + whole body and is
     * reported as finished.
     */
    @Test
    fun ac1_knownLength_serverIgnoresRange_finishedFileEqualsTheServedBytes() {
        val content = bytes(700_000, seed = 1)
        val resumeAt = 300_000
        val served = serve { Response(200, content) }
        val file = partFile(content.copyOfRange(0, resumeAt), length = content.size)
        val mission = mission(file, served, resumeAt, known = content.size)

        val outcome = run(mission)

        assertEquals("the mission reports finished", DownloadManagerService.MESSAGE_FINISHED, outcome)
        assertEquals("the resume asked for the rest", "bytes=$resumeAt-", served.requests[0]["range"])
        assertEquals("AC-1: the file has the length the server sent", content.size.toLong(), file.length())
        assertArrayEquals("AC-1: the file is byte for byte what the server sent", content, file.readBytes())
        assertEquals(content.size.toLong(), mission.done)
    }

    /**
     * AC-1, unknown length: the resume request never carries the offset, the body begins at 0.
     * The download starts over; a body shorter than what is on the disk leaves no tail.
     */
    @Test
    fun ac1_unknownLength_resumeStartsOver_andLeavesNoTail() {
        val content = bytes(400_000, seed = 2)
        val served = serve { Response(200, content, declareLength = false) }
        val file = partFile(bytes(600_000, seed = 3), length = 600_000) // more on disk than the body is long
        val mission = mission(file, served, resumeAt = 600_000, known = -1)

        val outcome = run(mission)

        assertEquals(DownloadManagerService.MESSAGE_FINISHED, outcome)
        assertEquals("AC-1: no tail of the earlier attempt", content.size.toLong(), file.length())
        assertArrayEquals(content, file.readBytes())
        assertEquals("the length counter is the body's length", content.size.toLong(), mission.length)
    }

    /**
     * AC-1: a 206 that starts at another offset than the one asked for is not written; the next
     * attempt asks for the whole resource without `Range` and the file is what the server sent.
     */
    @Test
    fun ac1_206FromAnotherOffset_isDiscarded_thenTheWholeResourceIsAsked() {
        val content = bytes(700_000, seed = 4)
        val resumeAt = 300_000
        val attempt = AtomicInteger()
        val served = serve {
            if (attempt.getAndIncrement() == 0) {
                Response(206, content.copyOfRange(100, content.size), contentRange = "bytes 100-${content.size - 1}/${content.size}")
            } else {
                Response(200, content)
            }
        }
        val file = partFile(content.copyOfRange(0, resumeAt), length = content.size)
        val mission = mission(file, served, resumeAt, known = content.size)

        val outcome = run(mission)

        assertEquals(DownloadManagerService.MESSAGE_FINISHED, outcome)
        assertEquals(2, served.requests.size)
        assertEquals("the second request carries no Range", null, served.requests[1]["range"])
        assertArrayEquals(content, file.readBytes())
    }

    /**
     * AC-1: a server that never answers a usable response ends in an error after the retries.
     * The download is not reported as finished and the part on disk is not overwritten.
     */
    @Test
    fun ac1_serverKeepsAnsweringFromAnotherOffset_endsInAnError_notInFinished() {
        val content = bytes(700_000, seed = 5)
        val resumeAt = 300_000
        val served = serve { Response(206, bytes(1000, seed = 6), contentRange = "bytes 100-1099/${content.size}") }
        val file = partFile(content.copyOfRange(0, resumeAt), length = content.size)
        val before = file.readBytes()
        val mission = mission(file, served, resumeAt, known = content.size)

        val outcome = run(mission)

        assertEquals("AC-1: a failed download is never reported as finished", DownloadManagerService.MESSAGE_ERROR, outcome)
        assertEquals("one attempt and maxRetry retries", 1 + mission.maxRetry, served.requests.size)
        assertArrayEquals("nothing of a discarded body was written", before, file.readBytes())
    }

    // ---- AC-5 ----

    /** AC-5: the server honours the range: the download continues at the offset and only the rest is transferred. */
    @Test
    fun ac5_knownLength_serverHonoursRange_continues() {
        val content = bytes(700_000, seed = 7)
        val resumeAt = 300_000
        val sent = AtomicInteger()
        val served = serve { request ->
            val start = request["range"]!!.removePrefix("bytes=").substringBefore('-').toInt()
            sent.addAndGet(content.size - start)
            Response(206, content.copyOfRange(start, content.size), contentRange = "bytes $start-${content.size - 1}/${content.size}")
        }
        val file = partFile(content.copyOfRange(0, resumeAt), length = content.size)
        val mission = mission(file, served, resumeAt, known = content.size)

        val outcome = run(mission)

        assertEquals(DownloadManagerService.MESSAGE_FINISHED, outcome)
        assertEquals(1, served.requests.size)
        assertEquals("only the rest was transferred", content.size - resumeAt, sent.get())
        assertArrayEquals(content, file.readBytes())
        assertEquals(content.size.toLong(), mission.done)
    }

    /** AC-5: a download from the beginning, answered with 200 and with 206 without `Content-Range`. */
    @Test
    fun ac5_freshDownload_200_and206WithoutContentRange() {
        for ((n, status) in listOf(200, 206).withIndex()) {
            val content = bytes(500_000, seed = 8L + n)
            val served = serve { Response(status, content) }
            val file = partFile(ByteArray(0), length = content.size, name = "fresh-$n.bin")
            val mission = mission(file, served, resumeAt = 0, known = content.size)

            val outcome = run(mission)

            assertEquals("status $status", DownloadManagerService.MESSAGE_FINISHED, outcome)
            assertEquals("status $status: upstream asks for bytes=0-", "bytes=0-", served.requests[0]["range"])
            assertArrayEquals("status $status", content, file.readBytes())
            server?.close()
        }
    }

    // ---- the mission ----

    private fun bytes(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    /** The download file as a paused single-connection download leaves it: [prefix], then zeros up to [length]. */
    private fun partFile(prefix: ByteArray, length: Int, name: String = "download.bin"): File {
        val file = File(directory, name)
        file.writeBytes(prefix)
        RandomAccessFile(file, "rw").use { it.setLength(maxOf(length, prefix.size).toLong()) }
        return file
    }

    private fun setField(mission: DownloadMission, name: String, value: Any) {
        val field = DownloadMission::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(mission, value)
    }

    /**
     * A mission in single-connection mode that will resume at [resumeAt]. [known]: the length
     * the initializer found, or -1 for "unknown length".
     */
    private fun mission(file: File, server: Server, resumeAt: Int, known: Int): DownloadMission {
        val storage = StoredFileHelper(context, null, Uri.fromFile(file), "download-fixes-test")
        val mission = DownloadMission(arrayOf(server.url), storage, 'v', null, context)
        mission.metadata = File(directory, file.name + ".state").apply { createNewFile() } // deleted by the mission when it finishes
        mission.maxRetry = 2
        mission.threadCount = 1
        mission.done = resumeAt.toLong()
        if (known >= 0) {
            mission.length = known.toLong()
        } else {
            mission.unknownLength = true
            mission.length = resumeAt.toLong()
        }
        setField(mission, "blocks", IntArray(0)) // single-connection ("fallback") mode
        setField(mission, "fallbackResumeOffset", resumeAt.toLong())
        return mission
    }

    /** Starts [mission] and waits for its "finished" or "error" message. Returns the message. */
    private fun run(mission: DownloadMission): Int {
        val ended = CountDownLatch(1)
        val outcome = AtomicInteger(-1)
        mission.mHandler = Handler(Looper.getMainLooper()) { message ->
            if (message.what == DownloadManagerService.MESSAGE_FINISHED || message.what == DownloadManagerService.MESSAGE_ERROR) {
                outcome.compareAndSet(-1, message.what)
                ended.countDown()
            }
            true
        }

        mission.start()

        if (!ended.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            mission.pause()
            fail("the mission neither finished nor failed within $WAIT_SECONDS s")
        }
        mission.threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
        return outcome.get()
    }

    // ---- the server ----

    private class Response(
        val status: Int,
        val body: ByteArray,
        val contentRange: String? = null,
        val declareLength: Boolean = true,
    )

    private fun serve(answer: (Map<String, String>) -> Response): Server = Server(answer).also { server = it }

    /** A `ServerSocket` on the loopback interface: one response per connection, then the connection is closed. */
    private class Server(private val answer: (Map<String, String>) -> Response) {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

        /** The headers of each request, names in lower case. */
        val requests: MutableList<Map<String, String>> = Collections.synchronizedList(ArrayList())

        @Volatile
        private var closed = false

        private val thread = Thread({
            while (!closed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                try {
                    serve(client)
                } catch (e: Exception) {
                    // the client went away
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "download-fixes-test-server").apply {
            isDaemon = true
            start()
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/file.bin"

        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            input.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            requests.add(headers)
            val response = answer(headers)

            val head = StringBuilder("HTTP/1.1 ${response.status} Test\r\n")
            if (response.declareLength) head.append("Content-Length: ${response.body.size}\r\n")
            if (response.contentRange != null) head.append("Content-Range: ${response.contentRange}\r\n")
            head.append("Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
            val output = client.getOutputStream()
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(response.body)
            output.flush()
        }

        fun close() {
            closed = true
            socket.close()
            thread.join(5000)
        }
    }

    private companion object {
        const val WAIT_SECONDS = 120L
    }
}
