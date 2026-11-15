package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.backupsafety.RepositoryAddress.Offer

/**
 * AC-4 and AC-5: the plugin install offered after an import ([RepositoryAddress], plan
 * section 3).
 *
 * - AC-4: it "shows the full repository address and asks for confirmation that names it".
 * - AC-5: it "is refused for an address that is not https, like an address typed in the plugin
 *   settings". The settings rule is `url.startsWith("https://") || (BuildConfig.DEBUG &&
 *   url.startsWith("http://"))`; `allowPlainHttp` stands for `BuildConfig.DEBUG`.
 */
class RepositoryAddressTest {

    /** AC-5: an https address is allowed, in release and in debug builds. */
    @Test
    fun ac5_httpsIsAllowed() {
        for (debug in listOf(false, true)) {
            assertTrue(RepositoryAddress.isAllowed("https://example.org/plugins/index.json", debug))
            assertTrue(RepositoryAddress.isAllowed("https://example.org:8443/a/b?c=d&e=%20f#g", debug))
        }
    }

    /** AC-5: plain http is refused; only a debug build allows it, as in the plugin settings. */
    @Test
    fun ac5_plainHttpIsRefusedExceptInDebugBuilds() {
        assertFalse(RepositoryAddress.isAllowed("http://example.org/plugins/index.json", allowPlainHttp = false))
        assertTrue(RepositoryAddress.isAllowed("http://192.168.1.10:8000/index.json", allowPlainHttp = true))
    }

    /** AC-5: anything else is refused in every build, like the settings rule's prefix check. */
    @Test
    fun ac5_otherAddressesAreRefused() {
        val refused = listOf(
            "", "example.org", "//example.org/x", "ftp://example.org/x", "file:///sdcard/repo.json",
            "content://provider/x", "javascript:alert(1)", "https:/example.org", "https:example.org",
            "HTTPS://example.org/x", "Https://example.org/x", "httpss://example.org", "xhttps://example.org",
            "http s://example.org"
        )
        for (url in refused) {
            for (debug in listOf(false, true)) {
                assertFalse("\"$url\", debug=$debug", RepositoryAddress.isAllowed(url, debug))
            }
        }
    }

    /**
     * AC-4, AC-5: an address with whitespace or a control character is refused even when it
     * starts with `https://`: shown in a dialog, a line break or a tab could push the real
     * target out of sight.
     */
    @Test
    fun ac5_whitespaceAndControlCharactersAreRefused() {
        val hidden = listOf(
            "https://good.example/\nhttps://evil.example/x",
            "https://good.example/\r\nevil",
            "https://good.example/ evil",
            "https://good.example/\tevil",
            " https://example.org/x",
            "https://example.org/x ",
            "https://example.org/x\n",
            "https://example.org/\u0000x",
            "https://example.org/\u001bx",
            "https://example.org/\u007fx",
            "https://example.org/\u000bx"
        )
        for (url in hidden) {
            for (debug in listOf(false, true)) {
                assertFalse("address with hidden part, debug=$debug: ${url.map { it.code }}", RepositoryAddress.isAllowed(url, debug))
            }
        }
    }

    /** AC-5: `split` sorts the offers into allowed and refused and keeps their order. */
    @Test
    fun ac5_splitSeparatesAllowedFromRefused() {
        val a = Offer("Alpha", "https://a.example/index.json")
        val b = Offer("Beta", "http://b.example/index.json")
        val c = Offer("Gamma", "https://c.example/index.json")
        val d = Offer("Delta", "https://d.example/\nindex.json")
        val split = RepositoryAddress.split(listOf(a, b, c, d), allowPlainHttp = false)
        assertEquals(listOf(a, c), split.allowed)
        assertEquals(listOf(b, d), split.refused)

        val debug = RepositoryAddress.split(listOf(a, b, c, d), allowPlainHttp = true)
        assertEquals(listOf(a, b, c), debug.allowed)
        assertEquals(listOf(d), debug.refused)
    }

    /** AC-5: nothing allowed means nothing to install (the dialog then has only OK). */
    @Test
    fun ac5_splitWithNothingAllowed() {
        val b = Offer("Beta", "http://b.example/index.json")
        val split = RepositoryAddress.split(listOf(b), allowPlainHttp = false)
        assertTrue(split.allowed.isEmpty())
        assertEquals(listOf(b), split.refused)
        assertEquals(RepositoryAddress.Split(emptyList(), emptyList()), RepositoryAddress.split(emptyList(), false))
    }

    /** AC-5: two plugins of one repository are both kept; nothing is merged away. */
    @Test
    fun ac5_splitKeepsEveryOffer() {
        val one = Offer("One", "https://a.example/index.json")
        val two = Offer("Two", "https://a.example/index.json")
        assertEquals(listOf(one, two), RepositoryAddress.split(listOf(one, two), false).allowed)
    }

    /** AC-4: one block per plugin, the name and below it the address, in the order given. */
    @Test
    fun ac4_linesAreNameThenFullAddress() {
        val offers = listOf(
            Offer("Alpha", "https://a.example/index.json"),
            Offer("Beta", "http://b.example/index.json")
        )
        assertEquals(
            listOf("Alpha\nhttps://a.example/index.json", "Beta\nhttp://b.example/index.json"),
            RepositoryAddress.lines(offers)
        )
        assertTrue(RepositoryAddress.lines(emptyList()).isEmpty())
    }

    /**
     * AC-4: "the full repository address". A long address is shown complete, to its last
     * character: not shortened, not reduced to its host, query and fragment included.
     */
    @Test
    fun ac4_linesContainTheCompleteAddress() {
        val long = "https://user@plugins.example.org:8443/" + "very-long-path-segment/".repeat(30) +
            "index.json?channel=stable&mirror=https%3A%2F%2Fother.example#section"
        val line = RepositoryAddress.lines(listOf(Offer("Long", long))).single()
        assertEquals("Long\n$long", line)
        assertTrue(line.endsWith("#section"))
    }

    /** AC-4: the built-in address is shown like any other; the code cannot tell which records came from an import. */
    @Test
    fun ac4_noAddressIsLeftOut() {
        val offers = (1..4).map { Offer("Plugin $it", "https://repo.example/$it") }
        val lines = RepositoryAddress.lines(offers)
        assertEquals(4, lines.size)
        offers.forEachIndexed { index, offer -> assertTrue(lines[index].endsWith("\n" + offer.repositoryUrl)) }
    }
}
