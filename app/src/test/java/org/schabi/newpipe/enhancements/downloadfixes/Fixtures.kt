package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plan section 3: `HlsBufferPolicy.LIMIT_BYTES`, 64 MiB per transfer. */
internal const val MIB = 1024L * 1024

/** Plan section 3: "256 KiB chunks" of `SegmentBuffer`, also `HttpTransferConfig.bufferSize`. */
internal const val CHUNK = 256 * 1024

/**
 * How long a test waits for another thread before it gives up and fails. Only a bound against a
 * hanging build: no test asserts on elapsed time, and no test sleeps.
 */
internal const val PATIENCE_SECONDS = 60L

internal fun CountDownLatch.awaitOrFail(what: String) {
    if (!await(PATIENCE_SECONDS, TimeUnit.SECONDS)) fail("timed out waiting for $what")
}

/** Repeatable content: every byte depends on its position, so misplaced data is seen. */
internal fun bytesOf(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

/** Files of `app/src/main`, read as text by the wiring and source tests. */
internal object MainFiles {
    /** `src/main/<path>` when run by Gradle (working directory `app/`), else `app/src/main/<path>`. */
    fun file(path: String): File {
        val inModule = File("src/main/$path")
        return if (inModule.exists() || File("src/main").isDirectory) inModule else File("app/src/main/$path")
    }

    fun lines(ac: String, path: String): List<String> {
        val file = file(path)
        if (!file.isFile) {
            fail("$ac: app/src/main/$path does not exist")
        }
        return file.readLines()
    }

    fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /** How often [snippet] occurs in [text], whitespace ignored in both. */
    fun count(text: String, snippet: String): Int {
        val wanted = squeeze(snippet)
        return squeeze(text).windowed(wanted.length).count { it == wanted }
    }
}

// ---- serialization helpers ----

internal fun serialized(value: Any?): ByteArray {
    val bytes = ByteArrayOutputStream()
    ObjectOutputStream(bytes).use { it.writeObject(value) }
    return bytes.toByteArray()
}

/** Reads [bytes] with a plain `ObjectInputStream`: what a complete state file must always allow. */
internal fun deserializedPlainly(bytes: ByteArray): Any? = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

/**
 * Replaces the modified-UTF-8 string [from] (with its 2-byte length) by [to] in a serialized
 * stream: the way a class name in a stream is changed without that class existing anywhere.
 */
internal fun withNameReplaced(stream: ByteArray, from: String, to: String): ByteArray {
    fun utf(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.US_ASCII)
        return byteArrayOf((raw.size shr 8).toByte(), raw.size.toByte()) + raw
    }
    val needle = utf(from)
    val at = (0..stream.size - needle.size).firstOrNull { start -> needle.indices.all { stream[start + it] == needle[it] } }
        ?: throw IllegalArgumentException("$from is not in the stream")
    return stream.copyOfRange(0, at) + utf(to) + stream.copyOfRange(at + needle.size, stream.size)
}

/**
 * Stands in for the saved state of a pending download: a version that tells which save it is,
 * and enough payload (headers, urls, an error) to span several kB like a real mission file.
 */
internal data class SavedState(
    val version: Int,
    val urls: Array<String> = arrayOf("https://example.invalid/video?expire=1#cookie=session%3D$version"),
    val headers: HashMap<String, String> = hashMapOf("Referer" to "https://example.invalid/", "X-Version" to "$version"),
    val blocks: IntArray = IntArray(2048) { it * 31 + version },
    val done: Long = 512L * 1024 * version,
    val error: Exception? = null,
) : Serializable {
    override fun equals(other: Any?): Boolean =
        other is SavedState && version == other.version && urls.contentEquals(other.urls) && headers == other.headers &&
            blocks.contentEquals(other.blocks) && done == other.done && error?.message == other.error?.message

    override fun hashCode(): Int = version

    override fun toString(): String = "SavedState(version=$version)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A state whose serialization fails after part of it was written (plan section 5, `PendingStateFilesTest`). */
internal class FailsWhileWritten(private val failure: Exception) : Serializable {
    private val before = ByteArray(64 * 1024) { 7 }

    @Suppress("unused")
    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        out.write(ByteArray(64 * 1024) { 9 })
        out.flush() // the bytes written so far are on the disk when the failure comes
        throw failure
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A serializable class of the app's own packages (`org.schabi.newpipe.`), allowed by its prefix. */
internal data class AppOwnPayload(val name: String, val numbers: List<Int>, val nested: AppOwnPayload? = null) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

internal enum class AppOwnEnum { FIRST, SECOND }

internal class AppOwnException(message: String, cause: Throwable?) : Exception(message, cause) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Stands in for a class that only a plugin defines. In the stream its name is changed to
 * [PLUGIN_ONLY_NAME], which no class loader of the test knows.
 */
internal data class PluginPayload(val text: String) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        const val PLUGIN_ONLY_NAME = "com.example.pipepipe.plugin.PluginPayload"

        fun streamWithPluginOnlyName(text: String): ByteArray =
            withNameReplaced(serialized(PluginPayload(text)), PluginPayload::class.java.name, PLUGIN_ONLY_NAME)
    }
}

// ---- a local HTTP server ----

internal class Request(val method: String, val path: String, val headers: Map<String, String>) {
    /** The `Range` request header, `null` when the request has none. */
    val range: String? get() = headers["range"]

    /** First byte of `Range: bytes=n-`, `null` without the header. */
    val rangeStart: Long? get() = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
}

/**
 * @param declareLength `false`: no `Content-Length`, the body ends with the connection (what
 *   upstream calls "unknown length").
 * @param cutAfter the connection is closed after this many body bytes although more were
 *   declared: a transfer that breaks.
 */
internal class Response(
    val status: Int,
    val body: ByteArray,
    val contentRange: String? = null,
    val declareLength: Boolean = true,
    val cutAfter: Int = -1,
)

/**
 * A plain `ServerSocket` on the loopback interface that answers every request with what [answer]
 * returns and closes the connection. Requests are recorded in [requests].
 */
internal class LocalServer(private val answer: (Request) -> Response) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(ArrayList())

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
                // the client went away; the test that caused it asserts on its own side
            } finally {
                runCatching { client.close() }
            }
        }
    }, "download-fixes-test-server").apply {
        isDaemon = true
        start()
    }

    val url: URL get() = URL("http://127.0.0.1:${socket.localPort}/file")

    /** A connection to this server that bypasses any proxy of the build machine. */
    fun open(range: String? = null): HttpURLConnection {
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val parts = requestLine.split(' ')
        val request = Request(parts[0], parts.getOrElse(1) { "/" }, headers)
        requests.add(request)
        val response = answer(request)

        val head = StringBuilder("HTTP/1.1 ${response.status} Test\r\n")
        if (response.declareLength) head.append("Content-Length: ${response.body.size}\r\n")
        if (response.contentRange != null) head.append("Content-Range: ${response.contentRange}\r\n")
        head.append("Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
        val output = client.getOutputStream()
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        val send = if (response.cutAfter >= 0) minOf(response.cutAfter, response.body.size) else response.body.size
        output.write(response.body, 0, send)
        output.flush()
    }

    override fun close() {
        closed = true
        socket.close()
        thread.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS))
    }

    companion object {
        /** Answers as a server that supports ranges: 206 with `Content-Range` when asked, else 200. */
        fun honouring(content: ByteArray, request: Request, declareLength: Boolean = true): Response {
            val start = request.rangeStart ?: return Response(200, content, declareLength = declareLength)
            if (start >= content.size) return Response(416, ByteArray(0), contentRange = "bytes */${content.size}")
            return Response(
                206,
                content.copyOfRange(start.toInt(), content.size),
                contentRange = "bytes $start-${content.size - 1}/${content.size}",
                declareLength = declareLength
            )
        }

        /** Answers as a server that ignores `Range`: always 200 with the whole resource. */
        fun ignoring(content: ByteArray, declareLength: Boolean = true): Response = Response(200, content, declareLength = declareLength)
    }
}
