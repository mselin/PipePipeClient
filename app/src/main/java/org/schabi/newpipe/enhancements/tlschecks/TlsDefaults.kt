package org.schabi.newpipe.enhancements.tlschecks

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Keeps the platform's [HttpsURLConnection] defaults across code that replaces them.
 *
 * Initialising the extractor installs an accept-all socket factory and host-name
 * verifier as the process-wide defaults. `App` calls [capture] directly before it and [restore]
 * directly after it, so downloads and playback, which use those defaults, are checked against
 * the system's trusted certificates and the host name again.
 */
object TlsDefaults {
    private var captured = false
    private var socketFactory: SSLSocketFactory? = null
    private var hostnameVerifier: HostnameVerifier? = null

    /**
     * Remembers [HttpsURLConnection.getDefaultSSLSocketFactory] and
     * [HttpsURLConnection.getDefaultHostnameVerifier]. Only the first call records; later calls
     * do nothing, so already replaced defaults can never be recorded over the good ones.
     */
    @JvmStatic
    @Synchronized
    fun capture() {
        if (captured) {
            return
        }
        socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        captured = true
    }

    /**
     * Sets both defaults back to the recorded ones. Idempotent.
     *
     * @throws IllegalStateException if [capture] was never called.
     */
    @JvmStatic
    @Synchronized
    fun restore() {
        check(captured) { "TlsDefaults.restore() called before capture()" }
        HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
    }
}
