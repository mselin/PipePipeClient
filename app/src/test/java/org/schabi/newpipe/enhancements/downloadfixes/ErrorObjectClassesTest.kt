package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-4, AC-5 (questions.md, Q10): classes that real error objects of a download hold must be
 * readable, or a pending download with such an error leaves the list at the next start.
 */
class ErrorObjectClassesTest {
    private fun allowed(name: String) =
        PendingStateAllowList.isAllowed(Class.forName(name, false, javaClass.classLoader), false)

    @Test
    fun `ac4 the classes held by http2 and certificate errors are allowed`() {
        for (name in listOf(
            "okhttp3.internal.http2.ErrorCode",
            "java.security.cert.CertPathValidatorException\$BasicReason",
            "java.security.cert.PKIXReason",
            "java.security.cert.CertPath\$CertPathRep",
        )) {
            assertTrue("$name must be allowed", allowed(name))
        }
    }

    @Test
    fun `ac4 their neighbours are still refused`() {
        for (name in listOf(
            "okhttp3.internal.http2.Settings",
            "java.security.cert.CertificateFactory",
            "java.security.cert.CertificateRevokedException",
        ).filter { !Throwable::class.java.isAssignableFrom(Class.forName(it, false, javaClass.classLoader)) }) {
            assertFalse("$name must not be allowed", allowed(name))
        }
    }
}
