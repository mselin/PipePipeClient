package org.schabi.newpipe.enhancements.tlschecks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.sync.RawHttp
import java.io.IOException
import java.net.URI
import javax.net.ssl.SSLContext

/**
 * T3 of `enhancements/tls-checks/plan.md`: the sync client (`RawHttp`) checks the host name
 * itself, whatever the process-wide default verifier is.
 *
 * Unless a test says otherwise, the client side trusts both test-only certificates: the default
 * `SSLContext` is replaced in [trustBothTestCertificates] by one whose only trust anchors are
 * those two, so each local server is "publicly valid" and only the host name decides.
 * Assumption (plan section 4, unchecked there): `RawHttp` takes its socket factory from
 * `SSLSocketFactory.getDefault()`, which follows `SSLContext.setDefault`.
 * All process-wide settings are put back in [restoreProcessDefaults].
 *
 * Before Code exists the two tests that expect 200 fail with "Certificate does not match ...",
 * because `RawHttp` asks the JDK's default verifier, which rejects every name; and the
 * accept-all test fails because the request goes through.
 */
class RawHttpHostnameTest {
    private lateinit var before: ProcessTlsSnapshot

    @Before
    fun trustBothTestCertificates() {
        before = ProcessTlsSnapshot()
        SSLContext.setDefault(TestOnlyTlsFixtures.clientContextTrustingBothTestCertificates())
    }

    @After
    fun restoreProcessDefaults() {
        before.restore()
    }

    /**
     * Sets up: local server on `localhost` presenting the `localhost` certificate.
     * Asserts: `RawHttp.request("GET", https://localhost:<port>/...)` answers 200 and the server
     * received the request, including the header passed in.
     */
    @Test
    fun ac4_certificateForTheRequestedName_answers200() {
        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64).use { server ->
            assertEquals(200, request("localhost", server.port))
            assertTrue(server.awaitConnection().requestText.contains(TEST_ONLY_COOKIE))
        }
    }

    /**
     * Sets up: local server bound to `127.0.0.1` presenting the `localhost` certificate, whose
     * subject alternative names include the IP address `127.0.0.1`.
     * Asserts: a request to `https://127.0.0.1:<port>/...` answers 200, i.e. an IP literal is
     * matched against the certificate's IP entries (plan section 3.2: "DNS name or IP literal").
     */
    @Test
    fun ac4_certificateForTheRequestedIpAddress_answers200() {
        LocalTlsServer(
            TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64, "127.0.0.1"
        ).use { server ->
            assertEquals(200, request("127.0.0.1", server.port))
        }
    }

    /**
     * Sets up: local server on `localhost` presenting the trusted certificate for `other.test`;
     * the process-wide verifier is the JDK's default.
     * Asserts: the request throws `IOException` and the server read no request byte.
     */
    @Test
    fun ac2_ac4_certificateForAnotherName_throwsIOExceptionAndSendsNothing() {
        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64).use { server ->
            assertRejectedWithNothingSent(server)
        }
    }

    /**
     * Sets up: as above, but the extractor's real `NewPipe.trustEveryone()` has installed an
     * accept-all default host-name verifier (the state of the app at startup today).
     * Asserts: the request still throws `IOException` and the server read no request byte, so
     * `RawHttp` does not depend on the process default.
     */
    @Test
    fun ac2_ac4_certificateForAnotherName_withAcceptAllDefaultVerifier_stillThrowsAndSendsNothing() {
        NewPipe.trustEveryone()
        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64).use { server ->
            assertRejectedWithNothingSent(server)
        }
    }

    /**
     * Sets up: the JVM's own default `SSLContext` (which does not trust the test certificates),
     * an accept-all default host-name verifier from `NewPipe.trustEveryone()`, and a local
     * server presenting the `localhost` certificate.
     * Asserts: the request throws `IOException` and the server read no request byte: the chain
     * is checked against the trust store whatever the `HttpsURLConnection` defaults are.
     */
    @Test
    fun ac2_untrustedCertificate_withAcceptAllDefaults_throwsIOExceptionAndSendsNothing() {
        SSLContext.setDefault(before.sslContext)
        NewPipe.trustEveryone()
        LocalTlsServer(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64).use { server ->
            assertRejectedWithNothingSent(server)
        }
    }

    private fun assertRejectedWithNothingSent(server: LocalTlsServer) {
        try {
            val code = request("localhost", server.port)
            fail("RawHttp must reject this certificate, but the request was answered with $code")
        } catch (expected: IOException) {
            // as specified
        }
        val connections = listOf(server.awaitConnection()) + server.closeAndDrain()
        connections.forEach {
            assertEquals(
                "the server must not read any request byte, but read: ${it.requestText}",
                0, it.requestBytes.size
            )
        }
    }

    private fun request(host: String, port: Int): Int =
        RawHttp.request(
            "GET", URI("https://$host:$port/sync"), mapOf("Cookie" to TEST_ONLY_COOKIE)
        ).code

    private companion object {
        const val TEST_ONLY_COOKIE = "session=test-only-not-a-real-cookie"
    }
}
