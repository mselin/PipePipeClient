package org.schabi.newpipe.enhancements.tlschecks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import java.io.IOException
import java.net.Proxy
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * T2 of `enhancements/tls-checks/plan.md`: what `TlsDefaults.restore()` means for a real
 * `HttpsURLConnection`, the class the download engine, HLS downloads and all playback use.
 *
 * Limit (plan section 4): this is the JDK's `HttpsURLConnection`, not Android's.
 * The process-wide defaults are put back in [restoreProcessDefaults].
 */
class TlsDefaultsConnectionTest {
    private lateinit var before: ProcessTlsSnapshot

    @Before
    fun recordProcessDefaults() {
        before = ProcessTlsSnapshot()
    }

    @After
    fun restoreProcessDefaults() {
        before.restore()
    }

    /**
     * Sets up: a local TLS server on `localhost` with the test-only self-signed `localhost`
     * certificate, which no trust store contains; `capture()`, then the extractor's real
     * `NewPipe.trustEveryone()`. Each request carries a `Cookie` header.
     * Asserts: with the extractor's defaults the request succeeds (200) and the server received
     * the cookie, which is today's defect and proves the harness can see a leak; after
     * `restore()` the same request throws `SSLException` and the server read no request byte on
     * any connection (AC-2: no cookie, no headers).
     */
    @Test
    fun ac1_ac2_untrustedCertificate_connectsAfterTrustEveryone_failsAfterRestoreWithNothingSent() {
        val tlsDefaults = FreshTlsDefaults()
        tlsDefaults.capture()
        NewPipe.trustEveryone()

        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64).use { server ->
            assertEquals(200, get(server.port))
            assertTrue(
                "precondition: with checks off the server receives the cookie",
                server.awaitConnection().requestText.contains(TEST_ONLY_COOKIE)
            )

            tlsDefaults.restore()
            try {
                val code = get(server.port)
                fail("an untrusted certificate must fail the handshake, but the answer was $code")
            } catch (expected: SSLException) {
                // as specified
            }
            assertNothingWasSent(server)
        }
    }

    /**
     * Sets up: the "platform default" socket factory is replaced by one that trusts exactly the
     * two test-only certificates (a stand-in for a publicly valid certificate; the JDK's default
     * host-name verifier is left as it is); `capture()`; `NewPipe.trustEveryone()`; a local
     * server on `localhost` presenting the certificate for `other.test`.
     * Asserts: with the extractor's defaults the request succeeds (the host name is not
     * checked); after `restore()` it throws `IOException` (the JDK reports a name mismatch as
     * `SSLException` or as plain `IOException`, depending on the path) and the server read no
     * request byte; a server presenting the `localhost` certificate still answers 200 with the
     * restored defaults, so the failure is the host name and nothing else.
     */
    @Test
    fun ac1_ac2_trustedCertificateForAnotherName_connectsAfterTrustEveryone_failsAfterRestore() {
        HttpsURLConnection.setDefaultSSLSocketFactory(
            TestOnlyTlsFixtures.clientContextTrustingBothTestCertificates().socketFactory
        )
        val tlsDefaults = FreshTlsDefaults()
        tlsDefaults.capture()
        NewPipe.trustEveryone()

        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64).use { server ->
            assertEquals(200, get(server.port))
            assertTrue(
                "precondition: with checks off the server receives the cookie",
                server.awaitConnection().requestText.contains(TEST_ONLY_COOKIE)
            )

            tlsDefaults.restore()
            try {
                val code = get(server.port)
                fail("a certificate for other.test must be rejected, but the answer was $code")
            } catch (expected: IOException) {
                // as specified
            }
            assertNothingWasSent(server)
        }

        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64).use { server ->
            assertEquals("a valid certificate for the right name still works", 200, get(server.port))
        }
    }

    private fun assertNothingWasSent(server: LocalTlsServer) {
        val connections = listOf(server.awaitConnection()) + server.closeAndDrain()
        connections.forEach {
            assertEquals(
                "the server must not read any request byte, but read: ${it.requestText}",
                0, it.requestBytes.size
            )
        }
    }

    /** GET with a cookie, as a download would send it. Returns the status code. */
    private fun get(port: Int): Int {
        val connection = URI("https://localhost:$port/video").toURL()
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Cookie", TEST_ONLY_COOKIE)
        connection.setRequestProperty("Connection", "close")
        try {
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val TEST_ONLY_COOKIE = "SESSDATA=test-only-not-a-real-cookie"
    }
}
