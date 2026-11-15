package org.schabi.newpipe.enhancements.tlschecks

import okhttp3.internal.tls.OkHostnameVerifier
import javax.net.ssl.SSLSession

/** Host-name check that depends on the certificate only, never on a replaceable default. */
object StrictHostnames {
    /**
     * RFC 2818 match of [host] (DNS name or IP literal) against the [session]'s peer
     * certificate. Delegates to OkHttp's [OkHostnameVerifier], the verifier the browsing client
     * already uses. Reads no process-wide or per-connection setting. False on any error.
     */
    @JvmStatic
    fun matches(host: String, session: SSLSession): Boolean {
        return try {
            OkHostnameVerifier.verify(host, session)
        } catch (e: Exception) {
            false
        }
    }
}
