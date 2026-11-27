package org.schabi.newpipe.enhancements.tlschecks

import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import javax.net.ssl.HttpsURLConnection

/**
 * T1 of `enhancements/tls-checks/plan.md`: `TlsDefaults` keeps the platform's
 * `HttpsURLConnection` defaults across the extractor's real `NewPipe.trustEveryone()`, which is
 * what `NewPipe.init(...)` runs at every start of the app.
 *
 * Every test works on a pristine copy of `TlsDefaults` ([FreshTlsDefaults]) and the process-wide
 * defaults are put back in [restoreProcessDefaults], whatever the test did.
 */
class TlsDefaultsTest {
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
     * Sets up: the defaults as the JVM has them, `capture()`, then the extractor's real
     * `NewPipe.trustEveryone()`.
     * Asserts: the extractor did replace both defaults (otherwise the test would prove nothing);
     * after `restore()` the default socket factory and the default host-name verifier are the
     * same instances as before; `restore()` a second time changes nothing (idempotent); both
     * methods are static and synchronized as in plan section 3.1.
     */
    @Test
    fun ac1_captureThenExtractorTrustEveryoneThenRestore_putsBackTheSameInstances() {
        val tlsDefaults = FreshTlsDefaults()
        assertTrue("capture() and restore() must be @Synchronized", tlsDefaults.isSynchronized)

        tlsDefaults.capture()
        NewPipe.trustEveryone()
        assertNotSame(
            "precondition: the extractor replaces the default socket factory",
            before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory()
        )
        assertNotSame(
            "precondition: the extractor replaces the default host-name verifier",
            before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier()
        )

        tlsDefaults.restore()
        assertSame(before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier())

        tlsDefaults.restore()
        assertSame(before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }

    /**
     * Sets up: `capture()`, `NewPipe.trustEveryone()`, then `capture()` again while the
     * accept-all defaults are installed (a second process start path, or a repeated call).
     * Asserts: `restore()` still puts back the original instances, so the second `capture()`
     * recorded nothing; and after the defaults are replaced once more, `restore()` puts the
     * originals back again.
     */
    @Test
    fun ac1_secondCaptureAfterDefaultsWereReplaced_doesNotRecordTheReplacedOnes() {
        val tlsDefaults = FreshTlsDefaults()
        tlsDefaults.capture()
        NewPipe.trustEveryone()
        tlsDefaults.capture()

        tlsDefaults.restore()
        assertSame(before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier())

        NewPipe.trustEveryone()
        tlsDefaults.capture()
        tlsDefaults.restore()
        assertSame(before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }

    /**
     * Sets up: a pristine `TlsDefaults` on which `capture()` was never called.
     * Asserts: `restore()` throws `IllegalStateException` and leaves both defaults untouched.
     */
    @Test
    fun ac1_restoreWithoutCapture_throwsIllegalStateExceptionAndChangesNothing() {
        val tlsDefaults = FreshTlsDefaults()
        try {
            tlsDefaults.restore()
            fail("restore() without capture() must throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            // as specified
        }
        assertSame(before.socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory())
        assertSame(before.hostnameVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
    }
}
