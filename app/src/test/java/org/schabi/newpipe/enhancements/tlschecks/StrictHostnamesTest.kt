package org.schabi.newpipe.enhancements.tlschecks

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * Not one of the plan's T1 to T4: a direct test of `StrictHostnames.matches` (plan section 3.2)
 * on real TLS sessions. T3 proves the same through `RawHttp` once Code has edited it; this test
 * shows on its own that the verifier the plan delegates to (`OkHostnameVerifier`) loads and
 * works in a plain JVM unit test, which the plan could not check.
 *
 * Sessions come from a handshake with a local server, made with a client context of the test's
 * own that trusts the two test-only certificates. The only process-wide setting changed is the
 * `HttpsURLConnection` defaults (by `NewPipe.trustEveryone()`); they are put back in
 * [restoreProcessDefaults].
 */
class StrictHostnamesTest {
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
     * Sets up: a session with a server presenting the `localhost` certificate (SAN `localhost`,
     * `127.0.0.1`).
     * Asserts: `matches` is true for `localhost` and for the IP literal `127.0.0.1`, false for
     * `other.test` and for `127.0.0.2`.
     */
    @Test
    fun ac4_matchesTheNamesInTheCertificateAndNoOthers() {
        withSession(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64) { session ->
            assertTrue(TlsChecksApi.strictHostnamesMatches("localhost", session))
            assertTrue(TlsChecksApi.strictHostnamesMatches("127.0.0.1", session))
            assertFalse(TlsChecksApi.strictHostnamesMatches("other.test", session))
            assertFalse(TlsChecksApi.strictHostnamesMatches("127.0.0.2", session))
        }
    }

    /**
     * Sets up: a session with a server presenting the certificate for `other.test`, first with
     * the JDK's default host-name verifier, then with the accept-all one that the extractor's
     * real `NewPipe.trustEveryone()` installs process-wide.
     * Asserts: `matches("localhost", ...)` is false both times and `matches("other.test", ...)`
     * is true: the result depends on the certificate only, not on a process-wide setting.
     */
    @Test
    fun ac4_rejectsACertificateForAnotherName_whateverTheProcessDefaultVerifierIs() {
        withSession(TestOnlyTlsFixtures.TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64) { session ->
            assertFalse(TlsChecksApi.strictHostnamesMatches("localhost", session))
            NewPipe.trustEveryone()
            assertFalse(TlsChecksApi.strictHostnamesMatches("localhost", session))
            assertTrue(TlsChecksApi.strictHostnamesMatches("other.test", session))
        }
    }

    /**
     * Sets up: the session of an SSL engine that never made a handshake, so asking it for the
     * peer certificate throws.
     * Asserts: `matches` returns false and does not throw ("False on any error").
     */
    @Test
    fun ac4_sessionWithoutPeerCertificate_isNoMatch() {
        val session = SSLContext.getDefault().createSSLEngine().session
        assertFalse(TlsChecksApi.strictHostnamesMatches("localhost", session))
    }

    private fun withSession(serverKeyStoreBase64: String, block: (SSLSession) -> Unit) {
        LocalTlsServer(serverKeyStoreBase64).use { server ->
            val factory = TestOnlyTlsFixtures.clientContextTrustingBothTestCertificates().socketFactory
            (factory.createSocket("localhost", server.port) as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                socket.startHandshake()
                block(socket.session)
            }
        }
    }
}
