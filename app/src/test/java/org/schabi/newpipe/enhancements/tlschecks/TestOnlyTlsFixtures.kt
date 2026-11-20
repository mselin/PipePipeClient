package org.schabi.newpipe.enhancements.tlschecks

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * TEST-ONLY key material for the local TLS server used by the tls-checks unit tests.
 *
 * Both key stores hold a throwaway, SELF-SIGNED certificate and its private key. They were made
 * once with `keytool` for these tests, protect nothing, are trusted by no trust store, and must
 * never be used outside `app/src/test`. The private keys are public by being in this file.
 *
 * How they were made (JDK 25 `keytool`, PKCS12, EC P-256, valid 2026-11-15 to 2126-10-22):
 * ```
 * keytool -genkeypair -alias test-only-localhost -keyalg EC -groupname secp256r1 \
 *   -sigalg SHA256withECDSA -validity 36500 \
 *   -dname "CN=localhost, O=TEST ONLY self-signed, OU=PipePipe tls-checks unit tests" \
 *   -ext "SAN=dns:localhost,ip:127.0.0.1" -storetype PKCS12 -keystore localhost.p12 \
 *   -storepass test-only
 * keytool -genkeypair -alias test-only-other -keyalg EC -groupname secp256r1 \
 *   -sigalg SHA256withECDSA -validity 36500 \
 *   -dname "CN=other.test, O=TEST ONLY self-signed, OU=PipePipe tls-checks unit tests" \
 *   -ext "SAN=dns:other.test" -storetype PKCS12 -keystore other.p12 -storepass test-only
 * base64 -w 88 localhost.p12; base64 -w 88 other.p12
 * ```
 */
internal object TestOnlyTlsFixtures {
    /** Password of both test-only key stores. Not a secret. */
    const val TEST_ONLY_PASSWORD = "test-only"

    /** PKCS12, alias `test-only-localhost`: self-signed, SAN `localhost` and `127.0.0.1`. */
    val TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64 = """
        MIIEygIBAzCCBHQGCSqGSIb3DQEHAaCCBGUEggRhMIIEXTCCAUQGCSqGSIb3DQEHAaCCATUEggExMIIBLTCCASkG
        CyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBShIrw7rtfIeYvB9rEhEeZb
        +k5I/gICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEJJlDKi6bg3pPMOp8n0Ry5IEUOCt+hlp2BDE
        VwBHCY+c68XTHkbVj3CngR8IKXJnm+wMfuv0hwbZCvPaAsZRhaAE1H/iSDivSkJLhA73Rwe4BW6zMHL5iUiSHrtq
        AcDHZpmbMVowNQYJKoZIhvcNAQkUMSgeJgB0AGUAcwB0AC0AbwBuAGwAeQAtAGwAbwBjAGEAbABoAG8AcwB0MCEG
        CSqGSIb3DQEJFTEUBBJUaW1lIDE3OTQ3Njg2NjUyMDIwggMRBgkqhkiG9w0BBwagggMCMIIC/gIBADCCAvcGCSqG
        SIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQURfcDWzW0LZV1F/up2+/sycuwaG4CAicQAgEg
        MAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBBL6jTbVosp/Ms6NQmHgW88gIICgDF1FoX17nvvDaIupepRGhHX
        hssb0Oqh1bzkAswHm2ru6or0hCFDWdhGEwdn7kLSsX0IkfKXWzi4nDRF9uvqg8qCUt12JZd87j8wMWCvl6XWQ6YX
        wj9nagC9PfOkeaG0TbrMxSOfiAhhaEyu2+OEKsN+IgSr0+KaUWrSSOhxPhmGKO15dlsBHyp5khmVrreDUulk5gN7
        8ss7+a+ighR3f8Ed9Zor/i4KMZH1959JkrAIptKnDfA87cwKKFj4TPWXB8rtBXEqafYJhY0lUqM4lGjBtSuEaWVm
        cX3IbkLIKsO0KWOF94IBYVdtXBNu7veO1c0xdhNqeVpSS6gJ9dHOCjx9XbxkjitOirEzatt8djdoVuq3py32WjhI
        7pY/JArq6qkH+Q6M7QfW8c3hHBT5OPczXyRpwQANJqACiEzQ6Ytjw2OCmPG98+KMhzmr0yhgUlXdmLLrkzvgoxDQ
        Udrw48zhX0NBKOKXK2uFo8TNhLSADmnCkMqKSOHYhDq33YIpSyppGSOiRrY34J5ndD+n8JrEPMcV8QiWctIjpguV
        Xr9l0PkHyiTaYbOdWiJUe5oDr1r75LBYaJ+RQiUlqtD119hCTeoYigNzPlkB15RA1r4y2wLfTYQtO0jObjqLFUj3
        pF1jOmQmwp74xQIivxX1JMDRYRbjJEQ33LqERS2pCbvbac02wX9nboLcPe5E4LFQrcF0bJdQX8k8/1ge1Ip/yJOd
        ls/kUqEju63jE+4L2Q64hK7aQWrSJK4dzlYTM6L4YMQV2qQD4xWuPlUj5L1xmDkg5deFbbj8cZqfJJ2KNeak6+wO
        2SqZsCwptzCvDn30xMKEN15wy7e0fs6sGqwuURcwTTAxMA0GCWCGSAFlAwQCAQUABCAeh7a4TNNDKXbXr5e2D47z
        mN8nwYd8WPMhHtnoor1BzAQUph89TCrNAxz9/na5/WwGT0NJLdgCAicQ
    """.trimIndent()

    /** PKCS12, alias `test-only-other`: self-signed, SAN `other.test` only. */
    val TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64 = """
        MIIEwgIBAzCCBGwGCSqGSIb3DQEHAaCCBF0EggRZMIIEVTCCATwGCSqGSIb3DQEHAaCCAS0EggEpMIIBJTCCASEG
        CyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRz2jVIkt02NIw76LNrXGGr
        5jGMyQICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEB1E+bzv0Sv51EE996O1skgEUIQVO3qALobs
        YVHSexK+mezbJo4FWP+gbxL+wggqmyyeub5Na75ISIBIwt1iuo46mAJGDuwv4mjAWCn42mfD/GiPkV4l4ywDgMkc
        g7y+n+PNMVIwLQYJKoZIhvcNAQkUMSAeHgB0AGUAcwB0AC0AbwBuAGwAeQAtAG8AdABoAGUAcjAhBgkqhkiG9w0B
        CRUxFAQSVGltZSAxNzk0NzY4NjY1NTUyMIIDEQYJKoZIhvcNAQcGoIIDAjCCAv4CAQAwggL3BgkqhkiG9w0BBwEw
        ZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFNRXuXuPw/wuUC+OIHy+7aV9wPNIAgInEAIBIDAMBggqhkiG
        9w0CCQUAMB0GCWCGSAFlAwQBKgQQYOz48EU7BOsmgjrCYbB3PoCCAoD/NDgxsWuJas2R77neYCodNubUS1yFPXUI
        jtxyUWUx1diYCHb8Bu6RbnMm75hHa4KMjPP18OImwTN3nnnaUsH33qchTQbGDwpEZJuQ0X2TSQ62LI+FriY6D/+2
        R9As8Bxek72EbWqWxJ8vLmeLJrLTytHv+jj6tIwous5oOK2R3VLzEHtTbAYgpUN20y4iXQrx7X7D3dmpCtczUAYx
        1kd3GBoVh1oLesdE6/LjCDRMJwK8rl/W6EZMFMVpMk6E8U2MwVY05K3gm5bGm7ljhPnYXu/o7r3Dk5LNWC3xQGE1
        O4afc+UjlqYLyvUveUxP0A9dUsj3GrshlelBYUMeamh/H/mf9QNk8kQoWvdliNaMHM8Z6jGHO6Lx48bnPsmmizu2
        h5SKSk33JHntshwpR34yWGthjVg31bZiXZIlcYmCZ+pRWje2dwgZ8c4DxKLNqe4EVF3wlFGIWsc0v0BA3/AD1And
        i/kiC9M9XHUitsEcf4/xQ+IvNZJE+DqPvK1llc5bKQzxPcmrysoSjUGq65JFtVKnwfIWgdq/xXIUWjSL8c0dGJmW
        cvKZIhyowAdUxzYCAZ/ruLnz6eMztPDaGch9kyMU/GOGLCOf4NrQ+XLg3a2MnsTSz1hw4imUxiHuB7nwq3JTBjJ4
        20+kFecOjvHRU5se27+3s9mlpHhDNeD7o1yjMkYSROd6PJQlcVnXEBexKjxqUf/ugxLyKTWcWMOH8//aMHi/zkY7
        TyHrePY+Fjmz18b5XwigSpeAGcOt2aA7MY2VK+wmGlXBWAFUUvMBsqPR+ZwpYLEbsLvI7Ngd3NpC1Jr8i9KaNolc
        X+QRZq5F6UMf2sLL82G/nsZyWEJ2ME0wMTANBglghkgBZQMEAgEFAAQg6rjdQ1Mjl6QJxl9d7B/FeYQcYNKxVMw7
        +VkcZe3kFyAEFGTmKeAFgr1gGiw8kUOyH1pyyogJAgInEA==
    """.trimIndent()

    /** Loads one of the Base64 constants above as a key store. */
    fun keyStore(base64: String): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(Base64.getMimeDecoder().decode(base64)).use {
            store.load(it, TEST_ONLY_PASSWORD.toCharArray())
        }
        return store
    }

    /** Server-side context presenting the certificate of the given test-only key store. */
    fun serverContext(base64: String): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore(base64), TEST_ONLY_PASSWORD.toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        return context
    }

    /**
     * Client-side context whose only trust anchors are the two test-only certificates. It checks
     * the chain normally (so both local servers are "publicly valid" for it) and never trusts
     * anything else.
     */
    fun clientContextTrustingBothTestCertificates(): SSLContext {
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType())
        trusted.load(null, null)
        val localhost = keyStore(TEST_ONLY_SELF_SIGNED_LOCALHOST_P12_BASE64)
        val other = keyStore(TEST_ONLY_SELF_SIGNED_OTHER_P12_BASE64)
        trusted.setCertificateEntry("test-only-localhost", localhost.getCertificate("test-only-localhost"))
        trusted.setCertificateEntry("test-only-other", other.getCertificate("test-only-other"))
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(trusted)
        val context = SSLContext.getInstance("TLS")
        context.init(null, trust.trustManagers, null)
        return context
    }
}
