package org.schabi.newpipe.enhancements.tlschecks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * T4 of `enhancements/tls-checks/plan.md`: a scan of the app's sources that keeps TLS checking
 * from being switched off again, by this fork or by a rebase onto upstream.
 *
 * It reads `src/main/java` relative to the working directory, which Gradle sets to the module
 * directory (`app/`) for unit tests. Patterns are matched on the raw lines, comments included:
 * a forbidden call that is merely commented out fails too, on purpose.
 *
 * Beyond the plan's list (Q6, Q7 in `enhancements/tls-checks/questions.md`), each marked
 * "extension" below: `SSLContext.setDefault(`, per-connection setters, trust-manager
 * implementations, and the AC-6 checks.
 */
class NoTlsWeakeningSourceTest {
    private val mainJava = File("src/main/java")
    private val mainRes = File("src/main/res")

    private class Hit(val file: File, val lineNumber: Int, val line: String) {
        override fun toString() = "${file.path}:$lineNumber: ${line.trim()}"
    }

    @Before
    fun sourcesAreWhereTheScanLooks() {
        assertTrue(
            "source directory not found: ${mainJava.absolutePath} (working directory must be app/)",
            mainJava.isDirectory
        )
        assertTrue("found no MainActivity.java to scan", mainActivity().isFile)
    }

    /**
     * Sets up: every `.java` and `.kt` file under `src/main/java`.
     * Asserts: `setDefaultHostnameVerifier(` and `setDefaultSSLSocketFactory(` occur only in
     * `enhancements/tlschecks/TlsDefaults.kt`, in `util/TLSSocketFactoryCompat.java` and inside
     * the body of `MainActivity.trustEveryone()` (which another test requires to be inert).
     * Extension: `SSLContext.setDefault(` occurs nowhere (it would replace the trust decisions
     * of the sync client).
     */
    @Test
    fun ac1_processWideTlsDefaultsAreSetOnlyInTheAllowedPlaces() {
        val inertBody = trustEveryoneBody()
        val offending = hits(Regex("""setDefault(HostnameVerifier|SSLSocketFactory)\s*\(""")).filter {
            when {
                it.file.invariantSeparatorsPath.endsWith("/enhancements/tlschecks/TlsDefaults.kt") -> false
                it.file.name == "TLSSocketFactoryCompat.java" -> false
                it.file == mainActivity() -> it.lineNumber !in inertBody
                else -> true
            }
        } + hits(Regex("""SSLContext\s*\.\s*setDefault\s*\("""))
        assertNone("process-wide TLS defaults are set outside the allowed places", offending)
    }

    /**
     * Sets up: every source file under `src/main/java`.
     * Asserts: no statement `trustEveryone();` (with or without a qualifier) and no method
     * reference `::trustEveryone` exists. Before Code exists this fails on
     * `MainActivity.java:143`.
     */
    @Test
    fun ac1_trustEveryoneIsNeverCalled() {
        assertNone(
            "trustEveryone() is called",
            hits(Regex("""trustEveryone\s*\(\s*\)\s*;|::\s*trustEveryone"""))
        )
    }

    /**
     * Sets up: the body of `public static void trustEveryone()` in `MainActivity.java`.
     * Asserts: its first statement is `if (true) return;` (plan section 3.3), so the method
     * cannot do anything even if upstream adds a caller. Spaces are ignored.
     */
    @Test
    fun ac1_trustEveryoneInMainActivityIsInert() {
        val lines = mainActivity().readLines()
        val body = trustEveryoneBody()
        val first = body.map { lines[it - 1] }.firstOrNull { it.isNotBlank() } ?: ""
        assertTrue(
            "first statement of MainActivity.trustEveryone() must be 'if (true) return;' " +
                "but line is: ${first.trim()}",
            first.filterNot { it.isWhitespace() }.startsWith("if(true)return;")
        )
    }

    /**
     * Sets up: every source file under `src/main/java`.
     * Asserts: `NewPipe.init(` occurs exactly once, in `App.java`; the statement before it
     * (blank and comment-only lines skipped) is `TlsDefaults.capture();` and the statement after
     * the end of the `NewPipe.init(...)` statement is `TlsDefaults.restore();`, with or without
     * a package qualifier. So the extractor's accept-all defaults live for one statement only.
     */
    @Test
    fun ac1_extractorInitHappensOnceAndBetweenCaptureAndRestore() {
        val inits = hits(Regex("""NewPipe\s*\.\s*init\s*\("""))
        assertEquals("NewPipe.init( must occur exactly once, found: $inits", 1, inits.size)
        val init = inits.single()
        assertEquals("NewPipe.init( must be in App.java", "App.java", init.file.name)

        val lines = init.file.readLines()
        val code = { i: Int -> lines[i].substringBefore("//").trim() }
        var beforeInit = init.lineNumber - 2
        while (beforeInit >= 0 && code(beforeInit).isEmpty()) beforeInit--
        var endOfInit = init.lineNumber - 1
        while (endOfInit < lines.size && !code(endOfInit).endsWith(";")) endOfInit++
        var afterInit = endOfInit + 1
        while (afterInit < lines.size && code(afterInit).isEmpty()) afterInit++

        val previous = if (beforeInit >= 0) code(beforeInit) else ""
        val next = if (afterInit < lines.size) code(afterInit) else ""
        assertTrue(
            "the statement before NewPipe.init(...) in App.java must be TlsDefaults.capture(); " +
                "but is: $previous",
            Regex("""^([\w.]+\.)?TlsDefaults\.capture\(\);$""").matches(previous)
        )
        assertTrue(
            "the statement after NewPipe.init(...) in App.java must be TlsDefaults.restore(); " +
                "but is: $next",
            Regex("""^([\w.]+\.)?TlsDefaults\.restore\(\);$""").matches(next)
        )
    }

    /**
     * Sets up: every source file under `src/main/java`.
     * Asserts: `onReceivedSslError` occurs nowhere, so no web view can proceed past a
     * certificate error.
     */
    @Test
    fun ac1_noWebViewHandlesCertificateErrors() {
        assertNone("onReceivedSslError is overridden", hits(Regex("onReceivedSslError")))
    }

    /**
     * Sets up: every source file under `src/main/java`.
     * Asserts: `sslSocketFactory(` and `hostnameVerifier(` (the OkHttp builder calls) occur only
     * in `DownloaderImpl.java`. With no exception list shipped (plan section 2), nothing relaxes
     * a check for any host, which is how AC-3 is met.
     * Extension: the per-connection forms `setSSLSocketFactory(`, `setHostnameVerifier(` and the
     * Kotlin property assignments `.sslSocketFactory =`, `.hostnameVerifier =` occur nowhere. If
     * plan section 2 is ever carried out, that plan round has to revise this test.
     */
    @Test
    fun ac3_noClientOrConnectionReplacesTheTlsChecksExceptDownloaderImpl() {
        val builderCalls = hits(Regex("""\b(sslSocketFactory|hostnameVerifier)\s*\("""))
            .filter { it.file.name != "DownloaderImpl.java" }
        val perConnection = hits(
            Regex("""\bset(SSLSocketFactory|HostnameVerifier)\s*\(|\.\s*(sslSocketFactory|hostnameVerifier)\s*=(?!=)""")
        )
        assertNone("a socket factory or host-name verifier is replaced", builderCalls + perConnection)
    }

    /**
     * Extension. Sets up: every source file under `src/main/java`.
     * Asserts: `checkServerTrusted` (the method every hand-written trust manager implements)
     * occurs only inside the inert body of `MainActivity.trustEveryone()`.
     */
    @Test
    fun ac1_ac3_noHandWrittenTrustManagerOutsideTheInertMethod() {
        val inertBody = trustEveryoneBody()
        val offending = hits(Regex("checkServerTrusted")).filter {
            it.file != mainActivity() || it.lineNumber !in inertBody
        }
        assertNone("a hand-written trust manager exists", offending)
    }

    /**
     * Sets up: the source files under `org/schabi/newpipe/sync/`.
     * Asserts: none mentions `getDefaultHostnameVerifier` (the process default the sync client
     * must not depend on) or `TlsExceptionHosts` (the exception list of plan section 2, which
     * must never reach sync). Before Code exists this fails on `RawHttp.kt:60`.
     */
    @Test
    fun ac4_syncUsesNeitherTheProcessDefaultVerifierNorAnExceptionList() {
        val sync = hits(Regex("getDefaultHostnameVerifier|TlsExceptionHosts"))
            .filter { it.file.invariantSeparatorsPath.contains("/org/schabi/newpipe/sync/") }
        assertNone("the sync client depends on a replaceable host-name check", sync)
    }

    /**
     * Extension (the plan lists AC-6 for T4 without naming a check; Q7).
     * Sets up: the two files of plan sections 3.1 and 3.2 under
     * `src/main/java/org/schabi/newpipe/enhancements/tlschecks/` and every XML file under
     * `src/main/res`.
     * Asserts: both files exist (this fails until Code exists); no file of that package imports
     * `android.*`/`androidx.*` or mentions a preference, so the fix cannot read a setting; and
     * no resource file or resource line is named after the fix (`tls-checks`, `tls_checks`,
     * `enhancements_tls*`), so it adds no setting, string or key.
     */
    @Test
    fun ac6_theFixAddsNoSettingAndReadsNone() {
        val dir = File(mainJava, "org/schabi/newpipe/enhancements/tlschecks")
        listOf("TlsDefaults.kt", "StrictHostnames.kt").forEach {
            assertTrue("${File(dir, it).path} does not exist: not implemented yet", File(dir, it).isFile)
        }
        val inCode = hits(Regex("""^\s*import\s+androidx?\.|Preference""")).filter {
            it.file.invariantSeparatorsPath.contains("/enhancements/tlschecks/")
        }
        assertNone("the tls-checks code uses Android or a preference", inCode)

        val resFiles = mainRes.walkTopDown().filter { it.isFile }.toList()
        val named = resFiles.filter { it.name.contains("tls", ignoreCase = true) }
        assertTrue("resource files named after the fix: $named", named.isEmpty())
        val inRes = resFiles.filter { it.extension == "xml" }
            .flatMap { hitsIn(it, Regex("""(?i)tls[-_]?checks?""")) }
        assertNone("resources mention the fix", inRes)
    }

    private fun mainActivity() = File(mainJava, "org/schabi/newpipe/MainActivity.java")

    /** 1-based line numbers strictly inside the braces of `MainActivity.trustEveryone()`. */
    private fun trustEveryoneBody(): IntRange {
        val lines = mainActivity().readLines()
        val start = lines.indexOfFirst { Regex("""void\s+trustEveryone\s*\(\s*\)\s*\{""").containsMatchIn(it) }
        assertTrue("MainActivity.java has no 'void trustEveryone() {'", start >= 0)
        var depth = 0
        for (i in start until lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth == 0) return (start + 2)..i
        }
        throw AssertionError("MainActivity.trustEveryone() has no closing brace")
    }

    private fun hits(pattern: Regex): List<Hit> =
        mainJava.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .sortedBy { it.path }
            .flatMap { hitsIn(it, pattern) }
            .toList()

    private fun hitsIn(file: File, pattern: Regex): List<Hit> =
        file.readLines().mapIndexedNotNull { index, line ->
            if (pattern.containsMatchIn(line)) Hit(file, index + 1, line) else null
        }

    private fun assertNone(what: String, found: List<Hit>) {
        assertTrue("$what:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
