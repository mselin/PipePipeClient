package org.schabi.newpipe.enhancements.tlschecks

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.URLClassLoader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The three process-wide TLS settings the tls-checks tests change, recorded so that a test can
 * put them back in `@After` and cannot affect other tests in the same JVM.
 */
internal class ProcessTlsSnapshot {
    val socketFactory: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    val sslContext: SSLContext = SSLContext.getDefault()

    fun restore() {
        HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
        SSLContext.setDefault(sslContext)
    }
}

/**
 * Local TLS server for the tests: an [SSLServerSocket] on a free port of the loopback interface,
 * presenting the certificate of one test-only key store from [TestOnlyTlsFixtures].
 *
 * For every accepted connection it records whether the handshake completed and every request
 * byte it could read (application data, i.e. what the client sent after the handshake). When it
 * has read a complete request head it answers `HTTP/1.0 200` with the body `ok` and closes.
 */
internal class LocalTlsServer(
    keyStoreBase64: String,
    bindHost: String = "localhost"
) : Closeable {

    /** What the server saw of one connection. */
    class Connection(val handshakeCompleted: Boolean, val requestBytes: ByteArray) {
        val requestText: String get() = String(requestBytes, Charsets.ISO_8859_1)
    }

    private val serverSocket = TestOnlyTlsFixtures.serverContext(keyStoreBase64)
        .serverSocketFactory
        .createServerSocket(0, 16, InetAddress.getByName(bindHost)) as SSLServerSocket
    private val seen = LinkedBlockingQueue<Connection>()
    private val thread = Thread({ acceptLoop() }, "test-only-tls-server").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = serverSocket.localPort

    private fun acceptLoop() {
        while (true) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (e: IOException) {
                return // closed
            }
            seen.add(serve(socket))
        }
    }

    private fun serve(socket: SSLSocket): Connection {
        var handshakeCompleted = false
        val request = ByteArrayOutputStream()
        try {
            socket.use { s ->
                s.soTimeout = IO_TIMEOUT_MS
                s.startHandshake()
                handshakeCompleted = true
                val input = s.getInputStream()
                while (!request.toString("ISO-8859-1").contains("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return Connection(true, request.toByteArray())
                    request.write(b)
                }
                val out = s.getOutputStream()
                out.write(RESPONSE.toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        } catch (e: IOException) {
            // failed handshake, reset or timeout: what was read so far is the record
        }
        return Connection(handshakeCompleted, request.toByteArray())
    }

    /** The next connection the server finished handling; fails the test if none arrives. */
    fun awaitConnection(): Connection =
        seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS)
            ?: throw AssertionError("the local TLS server saw no connection in $AWAIT_SECONDS s")

    /** Stops the server and returns the connections not yet taken with [awaitConnection]. */
    fun closeAndDrain(): List<Connection> {
        close()
        val rest = ArrayList<Connection>()
        seen.drainTo(rest)
        return rest
    }

    override fun close() {
        serverSocket.close()
        thread.join(IO_TIMEOUT_MS.toLong())
    }

    private companion object {
        const val IO_TIMEOUT_MS = 5_000
        const val AWAIT_SECONDS = 10L
        const val RESPONSE = "HTTP/1.0 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    }
}

/**
 * Reflective access to the two classes of plan sections 3.1 and 3.2.
 *
 * The tests do not reference `TlsDefaults` and `StrictHostnames` at compile time, for two
 * reasons (see Q5 in `enhancements/tls-checks/questions.md`):
 *  - a test source set that does not compile runs no test at all, so T3 and T4 could not fail
 *    "for the reason not implemented" before Code exists, and the other features' unit tests on
 *    the same branch would not run either;
 *  - `TlsDefaults` is a singleton that records only once per class, so "restore() without
 *    capture() throws" and "a second capture() does not record" need a pristine copy per test.
 *
 * A missing class or a signature other than the plan's fails the test with a message saying so.
 */
internal object TlsChecksApi {
    const val TLS_DEFAULTS = "org.schabi.newpipe.enhancements.tlschecks.TlsDefaults"
    const val STRICT_HOSTNAMES = "org.schabi.newpipe.enhancements.tlschecks.StrictHostnames"

    fun load(name: String): Class<*> = try {
        Class.forName(name)
    } catch (e: ClassNotFoundException) {
        throw AssertionError("$name does not exist: not implemented yet (plan section 3)", e)
    }

    /** A `public static` method as Java callers (`App.java`) see it, i.e. `@JvmStatic`. */
    fun staticMethod(cls: Class<*>, name: String, vararg parameters: Class<*>): Method {
        val method = try {
            cls.getMethod(name, *parameters)
        } catch (e: NoSuchMethodException) {
            throw AssertionError("${cls.name}.$name(...) with the plan's signature is missing", e)
        }
        if (!Modifier.isStatic(method.modifiers)) {
            throw AssertionError("${cls.name}.$name must be @JvmStatic (plan section 3)")
        }
        return method
    }

    /** Invokes and rethrows what the method itself threw. */
    fun call(method: Method, vararg args: Any?): Any? = try {
        method.invoke(null, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

    /** `StrictHostnames.matches(host, session)` (plan section 3.2). */
    fun strictHostnamesMatches(host: String, session: SSLSession): Boolean {
        val method = staticMethod(
            load(STRICT_HOSTNAMES), "matches", String::class.java, SSLSession::class.java
        )
        return call(method, host, session) as Boolean
    }
}

/**
 * A pristine copy of `TlsDefaults` (plan section 3.1): the class is loaded again in a class
 * loader of its own, so its "recorded once" state starts empty in every test, whatever other
 * tests did before. The `HttpsURLConnection` defaults it reads and writes are the JDK's and
 * therefore the real process-wide ones.
 *
 * Assumption: `TlsDefaults` needs nothing but the JDK, the Kotlin stdlib and classes of the app
 * itself, which is what the plan specifies ("no Android imports").
 */
internal class FreshTlsDefaults {
    private val captureMethod: Method
    private val restoreMethod: Method

    init {
        val shared = TlsChecksApi.load(TlsChecksApi.TLS_DEFAULTS)
        val urls = LinkedHashSet<java.net.URL>()
        urls.add(shared.protectionDomain!!.codeSource.location)
        urls.add(Unit::class.java.protectionDomain!!.codeSource.location)
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .forEach { urls.add(File(it).toURI().toURL()) }
        // parent = the JDK's own classes only (the tests compile against the Android API,
        // which has no ClassLoader.getPlatformClassLoader())
        val isolated = URLClassLoader(urls.toTypedArray(), null as ClassLoader?)
        val fresh = isolated.loadClass(TlsChecksApi.TLS_DEFAULTS)
        check(fresh !== shared) { "TlsDefaults was not loaded a second time" }
        captureMethod = TlsChecksApi.staticMethod(fresh, "capture")
        restoreMethod = TlsChecksApi.staticMethod(fresh, "restore")
    }

    /** True if both methods are `synchronized`, as the plan's signatures say. */
    val isSynchronized: Boolean
        get() = Modifier.isSynchronized(captureMethod.modifiers) &&
            Modifier.isSynchronized(restoreMethod.modifiers)

    fun capture() {
        TlsChecksApi.call(captureMethod)
    }

    fun restore() {
        TlsChecksApi.call(restoreMethod)
    }
}
